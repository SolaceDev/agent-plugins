"""
Derived from patterns/guaranteed_publisher.py
  SolaceSamples/solace-samples-python
  https://github.com/SolaceSamples/solace-samples-python

Elevated to documented best practices per the Python API developer guide:
  Messaging Service:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md
  Publishing Persistent Messages:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md
  the Python API reference (units, defaults, listener contracts):
    https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html
  and the C API Best Practices (the Python API wraps the C API, so its callback,
  reconnect, acknowledgement, and time-to-live practices apply):
    https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md

Reference sample. Wired for a basic publish-subscribe journey: basic-auth connect
with a reconnection retry strategy, PERSISTENT binary publish to a topic, publish
receipt (ACK/NACK) handling correlated by user context, capacity-bounded back
pressure paired with a publisher readiness listener, reconnection and service
interruption listeners registered BEFORE connect() that pause publishing while the
transport is down, and a graceful SIGINT/SIGTERM shutdown that drains outstanding
receipts before disconnecting. Structured as setup_solace(...),
run_publish_loop(), and teardown_solace(), with teardown run from main's finally
on every exit path.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as guaranteed_publisher.py, next to the shared
helper python-solace-connection-config.py copied as solace_connection_config.py.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

# PEP 563: keep annotations unevaluated so the built-in generics below (list[str],
# dict[str, object]) also import on Python 3.7 and 3.8
from __future__ import annotations

import logging
import signal
import sys
import threading
import uuid
from typing import Optional

from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.errors.pubsubplus_client_error import PublisherOverflowError, PubSubPlusClientError
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.publisher.outbound_message import OutboundMessageBuilder
from solace.messaging.publisher.persistent_message_publisher import (
    MessagePublishReceiptListener,
    PersistentMessagePublisher,
    PublishReceipt,
)
from solace.messaging.publisher.publisher_health_check import PublisherReadinessListener
from solace.messaging.resources.topic import Topic

from solace_connection_config import SolaceConnectionConfig

APP_NAME = "GuaranteedPublisher"
TOPIC_PREFIX = "solace/samples/"  # used as the topic "root"
API = "Python"
APPROX_MSG_RATE_PER_SEC = 100
PAYLOAD_SIZE = 512
# reconnect budget: 20 attempts 3 s apart, a minute of reconnect attempts, a reasonable
# default when the design says nothing about high availability. The interval is in
# MILLISECONDS per the RetryStrategy API reference.
RECONNECT_RETRIES = 20
RECONNECT_RETRY_INTERVAL_MS = 3000
# For HA or replication failover resilience, follow the C API Best Practices: the
# reconnect duration "should be set to last for at least 300 seconds" for HA. Its example
# lists 1 connect retry, 20 reconnect retries, a 3000 ms wait, and 5 connect retries per
# host; the Python retry strategy sets only the reconnect count and the wait, so
# parametrized_retry(100, 3000) is one way to reach 300 s here, and the per-host value is
# the CONNECTION_RETRIES_PER_HOST transport property. Use -1 retries
# (RetryStrategy.forever_retry()) so the API retries indefinitely during a replication
# failover, and a comma-separated host list in the host property
# (tcp://host-a:55555,tcp://host-b:55555; the HOST property reference documents the
# comma-separated form) so the API fails over between brokers.
# back pressure: the publisher buffers at most this many unsent messages before
# publish() raises PublisherOverflowError (the documented reject strategy)
PUBLISH_BUFFER_CAPACITY = 1000
# terminate(grace_period): how long to wait for buffered sends and their broker receipts
# to complete before the publisher stops (the API default is 600000 ms); terminate() raises
# IncompleteMessageDeliveryError when sends or receipts are still outstanding after that
TERMINATE_GRACE_PERIOD_MS = 10_000

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


class GuaranteedPublisher:
    """The app: the service, the publisher, the flags the listeners flip, and the counters."""

    def __init__(self) -> None:
        self.messaging_service: Optional[MessagingService] = None
        self.publisher: Optional[PersistentMessagePublisher] = None
        self.message_builder: Optional[OutboundMessageBuilder] = None
        self.connect_attempted = False
        self.is_connected = True  # tracks transport state via the reconnection listeners
        self.shutdown = threading.Event()
        self.publisher_ready = threading.Event()  # set by the readiness listener when the buffer has room
        self.msg_sent_counter = 0  # num messages sent

    def main(self, args: list[str]) -> None:
        trace(f"{API} {APP_NAME} initializing...")
        try:
            self.setup_solace(args)
            # graceful shutdown: SIGINT (Ctrl-C) or SIGTERM sets the shutdown event, the publish
            # loop exits, and teardown_solace() in the finally below drains the outstanding
            # receipts and disconnects. Python runs signal handlers on the main thread, so the
            # handler only flags; the cleanup runs on the main thread's normal exit path.
            signal.signal(signal.SIGINT, self.on_shutdown_signal)
            signal.signal(signal.SIGTERM, self.on_shutdown_signal)
            self.run_publish_loop()
        finally:
            self.teardown_solace()
            trace("Main thread quitting.")

    def on_shutdown_signal(self, signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping publisher...")
        self.shutdown.set()

    def setup_solace(self, args: list[str]) -> None:
        # basic username/password connection details, built by the shared SolaceConnectionConfig
        # helper: read from a config.json in the working directory if present, else from the
        # command line (<host:port> <message-vpn> <client-username> [password])
        properties = SolaceConnectionConfig.load(args, APP_NAME).to_service_properties()
        # build() creates the native session and resolves the host, so an unresolvable host
        # fails here, before connect()
        self.messaging_service = (
            MessagingService.builder()
            .from_properties(properties)
            .with_reconnection_retry_strategy(
                RetryStrategy.parametrized_retry(RECONNECT_RETRIES, RECONNECT_RETRY_INTERVAL_MS))
            .build()
        )
        # best practice: register the service event listeners BEFORE connect(), so no
        # reconnection or interruption event raised during or right after the connect is lost,
        # and handle each event appropriately rather than only logging it
        service_events = ServiceEventHandler(self)
        self.messaging_service.add_reconnection_attempt_listener(service_events)
        self.messaging_service.add_reconnection_listener(service_events)
        self.messaging_service.add_service_interruption_listener(service_events)
        self.connect_attempted = True
        self.messaging_service.connect()  # blocking connect

        # PERSISTENT publisher with capacity-bounded back pressure: publish() raises
        # PublisherOverflowError once PUBLISH_BUFFER_CAPACITY messages wait unsent, and the
        # readiness listener below reports when there is room again. The API default
        # (on_back_pressure_elastic) buffers without bound and the developer guide cautions it
        # can exhaust memory; on_back_pressure_wait(n) blocks publish() instead of raising.
        self.publisher = (
            self.messaging_service.create_persistent_message_publisher_builder()
            .on_back_pressure_reject(PUBLISH_BUFFER_CAPACITY)
            .build()
        )
        self.publisher.start()
        # best practice: check every publish receipt; a receipt that carries an exception is a
        # NACK or a failure to persist (see PublishReceiptHandler at the bottom of the file).
        # The listener is set AFTER start(): the API raises IllegalStateError on a publisher
        # that is not started yet.
        self.publisher.set_message_publish_receipt_listener(PublishReceiptHandler())
        # documented pairing with on_back_pressure_reject: ready() fires when the buffer has
        # room again, so the loop waits on the event instead of spinning on overflow
        self.publisher.set_publisher_readiness_listener(PublisherReadinessHandler(self.publisher_ready))
        # best practice: one OutboundMessageBuilder, reused for every message
        self.message_builder = self.messaging_service.message_builder()
        # best practice for Guaranteed publishing (C API Best Practices): set a time-to-live so
        # an unconsumed message does not sit on a queue forever. The queue must have respect-ttl
        # enabled (off by default), or the TTL is ignored. Add the Solace message property to the
        # builder in milliseconds (0, the default, never expires), for example
        #   .with_property(message_properties.PERSISTENT_TIME_TO_LIVE, 60_000)
        # with `from solace.messaging.config.solace_properties import message_properties`.

    def run_publish_loop(self) -> None:
        threading.Thread(target=self.print_stats, name="stats", daemon=True).start()

        trace(f"{API} {APP_NAME} connected, and running. Press Ctrl-C to quit.")
        trace(f"Publishing to topic '{TOPIC_PREFIX}{API.lower()}/pers/pub/...', "
              "please ensure queue has matching subscription.")
        while not self.shutdown.is_set():
            if not self.is_connected:  # transport is down: wait for the API's automatic reconnect
                self.shutdown.wait(0.1)
                continue
            # each loop, change the payload as an example
            chosen_character = chr(self.msg_sent_counter % 26 + 65)  # choose a "random" letter [A-Z]
            # a bytearray payload travels as the binary attachment; consumers read it back with
            # get_payload_as_bytes() (a str payload would arrive via get_payload_as_string())
            payload = bytearray(chosen_character.encode("ascii") * PAYLOAD_SIZE)
            msg_id = str(uuid.uuid4())
            message = self.message_builder.with_application_message_id(msg_id).build(payload)  # as an example
            # NOTE: publishing to topic, so make sure the consumer's queue is subscribed to the same topic,
            #       or enable "Reject Message to Sender on No Subscription Match" in the client-profile
            topic = Topic.of(f"{TOPIC_PREFIX}{API.lower()}/pers/pub/{chosen_character}")
            try:
                # user_context for local ACK/NACK correlation: the API hands it back in the
                # PublishReceipt, so an immutable per-send identifier (the message id) lets the
                # receipt handler name the exact message the broker accepted or rejected
                self.publisher.publish(message, topic, user_context=msg_id)
                self.msg_sent_counter += 1
            except PublisherOverflowError:
                # buffer full (back pressure): wait for the ready() callback the API sends when the
                # buffer has room again, with a bound so a shutdown request is still honoured, and
                # retry. is_ready() closes the race with a ready() that fired before clear().
                # notify_when_ready() is not called here: the API sends ready() at once, whatever
                # the buffer state, so the wait would return immediately and this loop would spin.
                self.publisher_ready.clear()
                if not self.publisher.is_ready():
                    self.publisher_ready.wait(timeout=1.0)
                continue
            except PubSubPlusClientError as error:
                # publish() raises when the message cannot be sent and retrying would not help
                logger.warning("publish() failed, quitting: %s", error)
                self.shutdown.set()  # let's quit; or, could initiate a new connection attempt
                break
            # delay between messages; the wait returns early once shutdown is requested
            self.shutdown.wait(1.0 / APPROX_MSG_RATE_PER_SEC)  # wait(0) for max speed
            # Note: STANDARD Edition Solace broker is limited to 10k msg/s max ingress
        self.shutdown.set()

    def print_stats(self) -> None:
        # simple way of calculating message rates; wait() returns True (ending the loop) on shutdown
        while not self.shutdown.wait(1.0):
            trace(f"{API} {APP_NAME} Published msgs/s: {self.msg_sent_counter:,}")
            self.msg_sent_counter = 0

    def teardown_solace(self) -> None:
        # Application cleanup belongs here: teardown_solace() runs in main's finally on
        # EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, or an
        # exception), so Solace teardown and application-side cleanup are never skipped.
        self.shutdown.set()
        if self.publisher is not None and self.publisher.is_running():
            # gracefully finish outstanding receipts before exit: terminate(grace_period) first
            # sends what is still buffered, then waits up to the grace period for the broker's
            # receipt of every published message, so no in-flight PERSISTENT message is
            # abandoned unacknowledged. This is the explicit-condition shutdown; no fixed sleep.
            # It raises IncompleteMessageDeliveryError when sends or receipts are still
            # outstanding after the grace period; catch it so the disconnect below still runs
            try:
                self.publisher.terminate(TERMINATE_GRACE_PERIOD_MS)
            except PubSubPlusClientError as error:
                logger.error("Publisher stopped with sends or receipts still outstanding after %d ms: %s",
                             TERMINATE_GRACE_PERIOD_MS, error)
        if self.connect_attempted:
            # disconnect() raises IllegalStateError on a service that never attempted to connect,
            # hence the flag; on a service that is already down it returns quietly
            self.messaging_service.disconnect()


def trace(message: str) -> None:
    """Demo narration sink: every status line in this sample funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system. The logger calls for API events (service, receipt,
    readiness) are a separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread:
    return promptly and do not block in them, since events and delivery stall while a
    callback runs (C API Best Practices)."""

    def __init__(self, app: GuaranteedPublisher) -> None:
        self.app = app

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress: pause publishing
        logger.warning("Service reconnecting, pausing publishing: %s (%s)", event.get_message(), event.get_cause())
        self.app.is_connected = False

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded: resume publishing
        logger.info("Service reconnected to %s, resuming publishing", event.get_broker_uri())
        self.app.is_connected = True

    def on_service_interrupted(self, event: ServiceEvent) -> None:
        # the connection went down and cannot be restored (reconnect attempts exhausted)
        logger.error("Service interrupted and cannot be restored, quitting: %s (%s)",
                     event.get_message(), event.get_cause())
        # Application cleanup signal: the service will not recover. Trigger application-side
        # cleanup from here. This sample sets shutdown, so the publish loop exits and
        # teardown_solace() runs in main's finally.
        self.app.shutdown.set()


class PublisherReadinessHandler(PublisherReadinessListener):
    """Flags the publish loop when the publisher can publish again. Runs on an API thread:
    return promptly and do not block in it (C API Best Practices)."""

    def __init__(self, publisher_ready: threading.Event) -> None:
        self.publisher_ready = publisher_ready

    def ready(self) -> None:
        self.publisher_ready.set()  # the buffer has room: the publish loop resumes


class PublishReceiptHandler(MessagePublishReceiptListener):
    """Very simple class, used for handling publish receipts (ACKs/NACKs) from the broker.
    Runs on an API thread:
    return promptly and do not block in it (C API Best Practices)."""

    def on_publish_receipt(self, publish_receipt: PublishReceipt) -> None:
        msg_id = publish_receipt.user_context  # the message id passed to publish()
        if publish_receipt.exception is None:  # ACK
            logger.debug("ACK for Message ID %s", msg_id)  # good enough, the broker has it now
            return
        # NACK: the exception says why (for example MessageRejectedByBrokerError when no queue
        # subscribes to the topic and the client-profile rejects on no subscription match)
        logger.warning("NACK for Message ID %s - %s", msg_id, publish_receipt.exception)
        # probably want to do something here. some error handling possibilities:
        #  - look the message up by this ID in your application's outbound store and send it again
        #  - send it somewhere else (error handling queue?)
        #  - log and continue
        #  - pause and retry (backoff) - maybe set a flag to slow down the publisher


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    GuaranteedPublisher().main(sys.argv[1:])
