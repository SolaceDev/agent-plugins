"""
Derived from patterns/direct_publisher.py
  SolaceSamples/solace-samples-python
  https://github.com/SolaceSamples/solace-samples-python

Elevated to documented best practices per the Python API developer guide:
  Messaging Service:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md
  Publishing Direct Messages:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Publish.md
  the Python API reference (units, defaults, listener contracts):
    https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html
  and the C API Best Practices (the Python API wraps the C API, so its callback,
  reconnect, acknowledgement, and time-to-live practices apply):
    https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md

Reference sample. Wired for a basic direct (at-most-once) pub/sub journey:
basic-auth connect with a reconnection retry strategy, a continuous DIRECT binary
publish loop with a rotating payload to a topic, a publish failure listener for the
asynchronous failures the API reports, capacity-bounded back pressure that blocks
publish() while the buffer is full, reconnection and service interruption listeners
registered BEFORE connect(), and a graceful SIGINT/SIGTERM shutdown. Direct
messaging is at-most-once: there is no broker receipt, so there is NO publish
receipt listener and NO user context here (the inverse of the guaranteed
publisher, which acts on each ACK/NACK), and there are no outstanding receipts to
drain at shutdown. Messages published while the API reconnects are lost, and each one
is reported through the publish failure listener. Publishes the payload directly, with
no OutboundMessageBuilder. Structured as module-level functions, setup_solace(...),
connect_solace(...), run_publish_loop(...), and teardown_solace(...), that share one
PublisherState; setup_solace() creates the service and the publisher before any
connection exists, and main() runs teardown from its finally on every exit path after that.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as direct_publisher.py, next to the shared helper
python-solace-connection-config.py copied as solace_connection_config.py.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

import logging
import signal
import sys
import threading
from dataclasses import dataclass

from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.errors.pubsubplus_client_error import IncompleteMessageDeliveryError, PubSubPlusClientError
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.publisher.direct_message_publisher import (
    DirectMessagePublisher,
    FailedPublishEvent,
    PublishFailureListener,
)
from solace.messaging.resources.topic import Topic

from solace_connection_config import load_service_properties

APP_NAME = "DirectPublisher"
TOPIC_PREFIX = "solace/samples/"  # used as the topic "root"
API = "Python"
PAYLOAD_SIZE = 512
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
# back pressure: publish() blocks once this many messages wait unsent, until there is room
# The capacity is a count of messages, not a size in bytes: the memory it holds depends on
# the size of each message. Tune it to the memory the application's deployment can give
# the buffer. The API has no default capacity (its default, on_back_pressure_elastic,
# buffers without bound); 1000 is the value the Python developer guide uses in its back
# pressure examples.
PUBLISH_BUFFER_CAPACITY = 1000
# terminate(grace_period): how long to wait for buffered sends to leave before the
# publisher stops; terminate() raises IncompleteMessageDeliveryError when messages are
# still buffered after that. A bounded grace period keeps shutdown time predictable, for
# example within the stop timeout of a process supervisor, so the disconnect still runs.
# The API default is 600000 ms (10 minutes). Adjust the value to suit the application:
# longer gives buffered sends more time to leave, shorter exits sooner.
TERMINATE_GRACE_PERIOD_MS = 10_000

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


@dataclass
class PublisherState:
    """What the publish loop, the signal handler, and the API listener threads share: the
    service, the publisher, the shutdown event, and the counters. The service and the
    publisher are required, so every PublisherState holds both (built, though not
    necessarily connected or started)."""
    messaging_service: MessagingService
    publisher: DirectMessagePublisher
    shutdown: threading.Event
    connect_attempted: bool = False
    exit_code: int = 0  # set to 1 on a failure exit (service interruption, a publish() failure, or a failed terminate())
    msg_sent_counter: int = 0  # num messages sent


def main(args: list[str]) -> int:
    trace(f"{API} {APP_NAME} initializing...")
    shutdown = threading.Event()

    def on_shutdown_signal(signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping publisher...")
        shutdown.set()

    # graceful shutdown: SIGINT (Ctrl-C) or SIGTERM sets the shutdown event, the publish
    # loop exits, and teardown_solace() in the finally below stops the publisher and
    # disconnects. Python runs signal handlers on the main thread, so the handler only
    # flags; the cleanup runs on the main thread's normal exit path. Registered before
    # connect, so a SIGTERM during the blocking connect() still reaches teardown.
    signal.signal(signal.SIGINT, on_shutdown_signal)
    signal.signal(signal.SIGTERM, on_shutdown_signal)
    # setup_solace() raises before any connection exists, so there is nothing to tear down
    # if it fails
    state = setup_solace(args, shutdown)
    try:
        connect_solace(state)
        run_publish_loop(state)
    finally:
        teardown_solace(state)
        trace("Main thread quitting.")
    return state.exit_code  # non-zero after a failure, so scripts and supervisors see it


def setup_solace(args: list[str], shutdown: threading.Event) -> PublisherState:
    # basic username/password connection details, built by the shared connection-config
    # helper: read from a config.json in the working directory if present, else from the
    # command line (<host:port> <message-vpn> <client-username> [password])
    properties = load_service_properties(args, APP_NAME)
    # build() creates the native session and resolves the host, so an unresolvable host
    # fails here, before connect()
    messaging_service = (
        MessagingService.builder()
        .from_properties(properties)
        .with_reconnection_retry_strategy(
            RetryStrategy.parametrized_retry(RECONNECT_RETRIES, RECONNECT_RETRY_INTERVAL_MS))
        .build()
    )
    # DIRECT publisher with capacity-bounded back pressure: publish() blocks once
    # PUBLISH_BUFFER_CAPACITY messages wait unsent, and returns when there is room again.
    # The API default (on_back_pressure_elastic) buffers without bound and the developer
    # guide cautions it can exhaust memory; on_back_pressure_reject(n) raises
    # PublisherOverflowError instead of blocking. Building the publisher needs only the
    # built service; start() is what requires the connection. One service can serve
    # several publishers.
    publisher = (
        messaging_service.create_direct_message_publisher_builder()
        .on_back_pressure_wait(PUBLISH_BUFFER_CAPACITY)
        .build()
    )
    # direct messaging has no broker receipt, so failures the API detects after publish()
    # returned (a buffered message that could not be sent) arrive asynchronously through
    # this listener; without it they are silent
    publisher.set_publish_failure_listener(PublishFailureHandler())
    state = PublisherState(messaging_service=messaging_service, publisher=publisher, shutdown=shutdown)
    # best practice: register the service event listeners BEFORE connect(), so no
    # reconnection or interruption event raised during or right after the connect is lost,
    # and handle each event appropriately rather than only logging it
    service_events = ServiceEventHandler(state)
    messaging_service.add_reconnection_attempt_listener(service_events)
    messaging_service.add_reconnection_listener(service_events)
    messaging_service.add_service_interruption_listener(service_events)
    return state


def connect_solace(state: PublisherState) -> None:
    state.connect_attempted = True
    state.messaging_service.connect()  # blocking connect
    state.publisher.start()  # raises IllegalStateError unless the service is connected


def run_publish_loop(state: PublisherState) -> None:
    threading.Thread(target=print_stats, args=(state,), name="stats", daemon=True).start()

    trace(f"{API} {APP_NAME} connected, and running. Press Ctrl-C to quit.")
    trace(f"Publishing to topic '{TOPIC_PREFIX}{API.lower()}/direct/pub/...', "
          "at-most-once (no broker receipt, no redelivery).")
    while not state.shutdown.is_set():
        # each loop, change the payload as an example
        chosen_character = chr(state.msg_sent_counter % 26 + 65)  # choose a "random" letter [A-Z]
        # a bytearray payload travels as the binary attachment; consumers read it back with
        # get_payload_as_bytes() (a str payload would arrive via get_payload_as_string())
        payload = bytearray(chosen_character.encode("ascii") * PAYLOAD_SIZE)
        # NO user context: direct is at-most-once with no broker receipt, so there is
        # nothing to correlate (the inverse of the guaranteed publisher, which passes the
        # message id as user_context for local ACK/NACK correlation)
        topic = Topic.of(f"{TOPIC_PREFIX}{API.lower()}/direct/pub/{chosen_character}")
        try:
            # blocks while the buffer is full (back pressure) on a connected transport; during
            # a reconnect it does not block (see ServiceEventHandler.on_reconnecting). A
            # shutdown signal that arrives during that wait takes effect once publish()
            # returns: when the buffer drains, or when the service goes down and the API
            # releases the wait.
            # builderless publish: the payload goes to publish() directly, and the API builds the
            # message. Set a per-message header with additional_message_properties, for example
            #   additional_message_properties={message_properties.APPLICATION_MESSAGE_ID: msg_id}
            # with `from solace.messaging.config.solace_properties import message_properties`.
            # Note: an OutboundMessageBuilder (messaging_service.message_builder()) is very
            # useful for "templated" messages: set the headers that are the same on every
            # message once on the builder, and build each message with only its few
            # differences (see the guaranteed publisher sample).
            state.publisher.publish(payload, topic)
            state.msg_sent_counter += 1
        except PubSubPlusClientError as error:
            # publish() raises when the message cannot be sent and retrying would not help
            logger.warning("publish() failed, quitting: %s", error)
            state.exit_code = 1
            break  # let's quit; or, could initiate a new connection attempt
        # no delay between messages: the loop publishes as fast as publish() returns, the way
        # an application publishes each message as soon as it has one
        # Note: STANDARD Edition Solace broker is limited to 10k msg/s max ingress
    state.shutdown.set()


def print_stats(state: PublisherState) -> None:
    # simple way of calculating message rates; wait() returns True (ending the loop) on shutdown
    while not state.shutdown.wait(1.0):
        trace(f"{API} {APP_NAME} Published msgs/s: {state.msg_sent_counter:,}")
        state.msg_sent_counter = 0


def teardown_solace(state: PublisherState) -> None:
    # Application cleanup belongs here: once setup_solace() returns, teardown_solace() runs
    # in main's finally on EVERY exit path (normal quit, SIGINT/SIGTERM, service
    # interruption, or an exception), so Solace teardown and application-side cleanup are
    # never skipped.
    state.shutdown.set()
    if state.publisher.is_running():
        # direct is at-most-once: there are no broker receipts to drain, so terminate() only
        # gives the messages still in the publisher's buffer up to the grace period to leave.
        # It raises IncompleteMessageDeliveryError when messages are still buffered after
        # that; catch it so the disconnect below still runs
        try:
            state.publisher.terminate(TERMINATE_GRACE_PERIOD_MS)
        except IncompleteMessageDeliveryError as error:
            logger.error("Publisher stopped with messages still buffered after %d ms: %s",
                         TERMINATE_GRACE_PERIOD_MS, error)
            state.exit_code = 1
        except PubSubPlusClientError as error:
            logger.error("Publisher terminate() failed: %s", error)
            state.exit_code = 1
    if state.connect_attempted:
        # disconnect() raises IllegalStateError on a service that never attempted to connect,
        # hence the flag; on a service that is already down it returns quietly
        state.messaging_service.disconnect()


def trace(message: str) -> None:
    """Demo narration sink: every status line in this sample funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system. The logger calls for API events (service, publish
    failure) are a separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread:
    return promptly and do not block in them, since events and delivery stall while a
    callback runs (C API Best Practices)."""

    def __init__(self, state: PublisherState) -> None:
        self.state = state

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress. Direct messaging is
        # at-most-once: while the transport is down, publish() does not block, and the API
        # fails each message it cannot send and reports it through the publish failure
        # listener (PublishFailureHandler below). An application that must not lose messages
        # uses guaranteed messaging instead (see the guaranteed publisher sample); one that
        # must not produce messages during an outage can pause its publish loop between this
        # event and on_reconnected().
        logger.warning("Service reconnecting: %s (%s)", event.get_message(), event.get_cause())

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded: messages published from now on are sent again
        logger.info("Service reconnected to %s", event.get_broker_uri())

    def on_service_interrupted(self, event: ServiceEvent) -> None:
        # the connection went down and cannot be restored (reconnect attempts exhausted)
        logger.error("Service interrupted and cannot be restored, quitting: %s (%s)",
                     event.get_message(), event.get_cause())
        # Application cleanup signal: the service will not recover. Trigger application-side
        # cleanup from here. This sample sets shutdown, so the publish loop exits and
        # teardown_solace() runs in main's finally.
        self.state.exit_code = 1
        self.state.shutdown.set()


class PublishFailureHandler(PublishFailureListener):
    """Very simple class, used for the asynchronous publish failures the API reports for
    direct messages (there is no broker receipt to act on). Runs on an API thread:
    return promptly and do not block in it (C API Best Practices)."""

    def on_failed_publish(self, failed_publish_event: FailedPublishEvent) -> None:
        # a buffered message could not be sent (the developer guide names an invalid topic or a
        # termination of the service as causes); direct has no NACK, so this never fires for one
        logger.warning("Failed to publish to %s: %s",
                       failed_publish_event.get_destination().get_name(), failed_publish_event.get_exception())


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(main(sys.argv[1:]))
