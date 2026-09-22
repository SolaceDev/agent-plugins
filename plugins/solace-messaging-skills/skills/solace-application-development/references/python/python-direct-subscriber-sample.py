"""
Derived from patterns/direct_receiver.py
  SolaceSamples/solace-samples-python
  https://github.com/SolaceSamples/solace-samples-python

Elevated to documented best practices per the Python API developer guide:
  Messaging Service:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md
  Consuming Direct Messages:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Receive.md
  the Python API reference (units, defaults, listener contracts):
    https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html
  and the C API Best Practices (the Python API wraps the C API, so its callback,
  reconnect, acknowledgement, and time-to-live practices apply):
    https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md

Reference sample. Wired for a basic direct (at-most-once) pub/sub journey:
basic-auth connect with a reconnection retry strategy, a plain topic subscription
(no queue, no provisioning), an async DIRECT receiver that detects discards,
reconnection and service interruption listeners registered BEFORE connect(), and a
graceful SIGINT/SIGTERM shutdown. Direct messaging is at-most-once: there is no
broker ACK and no redelivery, so there is NO client acknowledgement here (the
inverse of the guaranteed subscriber). Structured as setup_solace(...),
await_messages(), and teardown_solace(), with teardown run from main's finally on
every exit path.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as direct_subscriber.py, next to the shared helper
python-solace-connection-config.py copied as solace_connection_config.py.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

import logging
import signal
import sys
import threading
from typing import Optional

from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.errors.pubsubplus_client_error import PubSubPlusClientError
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.receiver.direct_message_receiver import DirectMessageReceiver
from solace.messaging.receiver.inbound_message import InboundMessage
from solace.messaging.receiver.message_receiver import MessageHandler
from solace.messaging.resources.topic_subscription import TopicSubscription

from solace_connection_config import SolaceConnectionConfig

APP_NAME = "DirectSubscriber"
# topic to subscribe to directly; matches the direct publisher sample's topic root
TOPIC_NAME = "solace/samples/python/direct/pub/>"
API = "Python"
# reconnect budget: 20 attempts 3 s apart, a minute of reconnect attempts, a reasonable
# default when the design says nothing about high availability. The interval is in
# MILLISECONDS per the RetryStrategy API reference.
RECONNECT_RETRIES = 20
RECONNECT_RETRY_INTERVAL_MS = 3000
# For HA failover resilience, use the C API Best Practices values
# (https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md):
#   HA failover: with_connection_retry_strategy(RetryStrategy.parametrized_retry(1, 3000)),
#     with_reconnection_retry_strategy(RetryStrategy.parametrized_retry(20, 3000)),
#     properties[transport_layer_properties.CONNECTION_RETRIES_PER_HOST] = 5
# terminate(grace_period): how long to wait for the handler to drain the messages the API
# has already received before the receiver stops (the API default is 600000 ms);
# terminate() raises IncompleteMessageDeliveryError when messages remain after that
TERMINATE_GRACE_PERIOD_MS = 10_000

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


class DirectSubscriber:
    """The app: the service, the receiver, the flags the listeners flip, and the counters.
    Use this type of app for receiving Direct (at-most-once) messages from a topic."""

    def __init__(self) -> None:
        self.messaging_service: Optional[MessagingService] = None
        self.receiver: Optional[DirectMessageReceiver] = None
        self.connect_attempted = False
        self.shutdown = threading.Event()
        self.exit_code = 0  # set to 1 on a failure exit (service interruption)
        self.msg_recv_counter = 0  # num messages received
        self.has_detected_discard = False  # any discards seen?

    def main(self, args: list[str]) -> int:
        trace(f"{API} {APP_NAME} initializing...")
        try:
            # graceful shutdown: SIGINT (Ctrl-C) or SIGTERM sets the shutdown event, the main
            # loop exits, and teardown_solace() in the finally below stops the receiver and
            # disconnects. Python runs signal handlers on the main thread, so the handler only
            # flags; the cleanup runs on the main thread's normal exit path. Registered before
            # setup, so a SIGTERM during the blocking connect() still reaches teardown.
            signal.signal(signal.SIGINT, self.on_shutdown_signal)
            signal.signal(signal.SIGTERM, self.on_shutdown_signal)
            self.setup_solace(args)
            self.await_messages()
        finally:
            self.teardown_solace()
            trace("Main thread quitting.")
        return self.exit_code  # non-zero after a failure, so scripts and supervisors see it

    def on_shutdown_signal(self, signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping subscriber...")
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

        # DIRECT receiver: a topic subscription on the receiver (no queue, no provisioning).
        # Direct messaging is at-most-once: messages flow straight to the subscriber with no
        # broker ACK and no redelivery. start() applies the subscription and blocks until the
        # broker confirms it, so the route is live before this subscriber reports ready. The
        # receiver keeps an unbounded internal buffer by default (on_back_pressure_elastic);
        # on_back_pressure_drop_latest(n) or on_back_pressure_drop_oldest(n) bound it, and a
        # dropped message shows up as an internal discard indication on the next one delivered.
        trace(f"Adding direct topic subscription '{TOPIC_NAME}'.")
        self.receiver = (
            self.messaging_service.create_direct_message_receiver_builder()
            .with_subscriptions([TopicSubscription.of(TOPIC_NAME)])
            .build()
        )
        self.receiver.start()
        # see bottom of file for DirectMessageHandler, which receives the messages from the topic
        self.receiver.receive_async(DirectMessageHandler(self))
        trace(f"{APP_NAME} subscribed and consuming. Press Ctrl-C to quit.")

    def await_messages(self) -> None:
        # async direct receive working now, so time to wait until done...
        while not self.shutdown.wait(1.0):  # wait 1 second; the wait returns True once shutdown is requested
            trace(f"{API} {APP_NAME} Received msgs/s: {self.msg_recv_counter:,}")  # simple way of calculating message rates
            self.msg_recv_counter = 0
            if self.has_detected_discard:  # at least one direct message was dropped before this subscriber
                trace("*** Discard detected (at-most-once: a direct message was dropped) ***")
                self.has_detected_discard = False  # only show the warning once per second

    def teardown_solace(self) -> None:
        # Application cleanup belongs here: teardown_solace() runs in main's finally on
        # EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, or an
        # exception), so Solace teardown and application-side cleanup are never skipped.
        self.shutdown.set()
        if self.receiver is not None and self.receiver.is_running():
            # direct is at-most-once: there are no acknowledgements to drain before exit, so
            # terminate() stops delivery and gives the handler up to the grace period to drain
            # the messages the API has already received. It raises IncompleteMessageDeliveryError
            # when messages remain after that; catch it so the disconnect below still runs
            try:
                self.receiver.terminate(TERMINATE_GRACE_PERIOD_MS)
            except PubSubPlusClientError as error:
                logger.error("Receiver stopped with messages still undelivered to the handler after %d ms: %s",
                             TERMINATE_GRACE_PERIOD_MS, error)
        if self.connect_attempted:
            # disconnect() raises IllegalStateError on a service that never attempted to connect,
            # hence the flag; on a service that is already down it returns quietly
            self.messaging_service.disconnect()  # will also release the receiver


def trace(message: str) -> None:
    """Demo narration sink: every status line in this sample funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system. The logger calls for API events (service) are a
    separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread:
    return promptly and do not block in them, since events and delivery stall while a
    callback runs (C API Best Practices)."""

    def __init__(self, app: DirectSubscriber) -> None:
        self.app = app

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress: direct delivery is paused,
        # and the broker does not store direct messages for this subscriber while it is
        # disconnected (at-most-once)
        logger.warning("Service reconnecting; direct delivery is paused until re-established: %s (%s)",
                       event.get_message(), event.get_cause())

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded; the API re-applies the topic subscription and delivery
        # resumes (unlike JCSMP, no reapply-subscriptions property is needed)
        logger.info("Service reconnected to %s; the subscription is restored and delivery resumes",
                    event.get_broker_uri())

    def on_service_interrupted(self, event: ServiceEvent) -> None:
        # the connection went down and cannot be restored (reconnect attempts exhausted)
        logger.error("Service interrupted and cannot be restored, quitting: %s (%s)",
                     event.get_message(), event.get_cause())
        # Application cleanup signal: the service will not recover. Trigger application-side
        # cleanup from here. This sample sets shutdown, so the main loop exits and
        # teardown_solace() runs in main's finally.
        self.app.exit_code = 1
        self.app.shutdown.set()


class DirectMessageHandler(MessageHandler):
    """Very simple class, used for receiving direct messages from the topic. on_message runs
    on an API-owned receiver thread, not the main thread: return promptly and do not block in
    it (hand heavy work to the application's own thread or queue), since delivery stalls while
    it runs (C API Best Practices)."""

    def __init__(self, app: DirectSubscriber) -> None:
        self.app = app

    def on_message(self, message: InboundMessage) -> None:
        self.app.msg_recv_counter += 1
        # the publisher sample sends a bytearray payload (the binary attachment), read back with
        # get_payload_as_bytes(); a str payload arrives via get_payload_as_string() instead
        # best practice: handle an unexpected message format without raising (C API Best
        # Practices); errors="replace" keeps a non-UTF-8 payload from raising in the callback
        payload = message.get_payload_as_bytes()
        text = payload.decode("utf-8", errors="replace") if payload is not None else message.get_payload_as_string()
        trace(f"{API} {APP_NAME} received: {text}")
        # at-most-once discard evidence (API reference): the broker sets the broker discard
        # indication when it discarded direct messages destined for this subscriber for any
        # reason, and the API sets the internal one when its own receiver buffer dropped
        # messages. There is NO redelivery in direct messaging.
        discard = message.get_message_discard_notification()
        if discard.has_broker_discard_indication() or discard.has_internal_discard_indication():
            self.app.has_detected_discard = True
        # Direct messaging is at-most-once: there is no consumer-side acknowledgement here (the
        # inverse of the guaranteed CLIENT-ack subscriber). The message is consumed as it
        # arrives; the broker holds no copy and expects no ack.


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(DirectSubscriber().main(sys.argv[1:]))
