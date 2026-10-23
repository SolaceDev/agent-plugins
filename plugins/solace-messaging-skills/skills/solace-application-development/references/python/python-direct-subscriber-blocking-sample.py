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

Reference sample. Wired for a basic direct (at-most-once) pub/sub journey, BLOCKING
variant: basic-auth connect with a reconnection retry strategy, a plain topic
subscription (no queue, no provisioning), a DIRECT receiver with a capacity-bounded
buffer that detects discards and whose main loop calls receive_message(timeout),
reconnection and service interruption listeners registered BEFORE connect(), and a
graceful SIGINT/SIGTERM shutdown. Direct messaging is at-most-once: there is no
broker ACK and no redelivery, so there is NO client acknowledgement here (the inverse
of the guaranteed subscriber). The non-blocking variant is
python-direct-subscriber-sample.py. Structured as module-level functions,
setup_solace(...), connect_solace(...), receive_messages(...), and
teardown_solace(...), that share one SubscriberState; setup_solace() creates the
service and the receiver before any connection exists, and main() runs teardown from
its finally on every exit path after that.

Why blocking: the application's own thread is the single thread of control for the
messaging data path. The main thread takes each message from the receiver and
processes it, so no message handler runs on an API thread and no application state is
shared with one. In Python the GIL lets only one thread run Python code at a time, so
the message-handler design gains less from its extra thread than it does in other
languages. This sample makes no throughput claim. The cost: the loop processes one
message at a time, so a slow loop fills the receive buffer and the receiver discards
messages, and a shutdown signal takes effect only when receive_message() returns. The
service event listeners still run on API threads.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as direct_subscriber_blocking.py, next to the shared
helper python-solace-connection-config.py copied as solace_connection_config.py.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

import logging
import signal
import sys
import threading
import time
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
from solace.messaging.receiver.direct_message_receiver import DirectMessageReceiver
from solace.messaging.receiver.inbound_message import InboundMessage
from solace.messaging.resources.topic_subscription import TopicSubscription

from solace_connection_config import load_service_properties

APP_NAME = "DirectSubscriberBlocking"
# topic to subscribe to directly; matches the direct publisher sample's topic root
TOPIC_NAME = "solace/samples/python/direct/pub/>"
API = "Python"
# receive_message(timeout) blocks up to this many MILLISECONDS for the next message, then
# returns None. The loop checks the shutdown event each time the call returns, so this value
# bounds how long a shutdown signal waits during an empty wait. 1 s keeps Ctrl-C prompt and
# matches the rate report interval; a longer timeout wakes the loop less often but stops
# slower. Never pass None: the call then waits without a limit and a shutdown signal never
# takes effect while no message arrives.
RECEIVE_TIMEOUT_MS = 1000
# how often the receive loop reports its message rate, in seconds
STATS_INTERVAL_S = 1.0
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
# back pressure: once this many received messages wait for the receive loop, the receiver
# discards each new incoming message until there is room
# The capacity is a count of messages, not a size in bytes: the memory it holds depends on
# the size of each message. Tune it to the memory the application's deployment can give
# the buffer. The API has no default capacity (its default, on_back_pressure_elastic,
# buffers without bound); 1000 is the value the Python developer guide uses in its back
# pressure examples.
RECEIVE_BUFFER_CAPACITY = 1000
# terminate(grace_period): how long to wait for the messages the API has already received
# to be taken from its buffer before the receiver stops. In this blocking sample only the
# receive loop takes them, and that loop has stopped by teardown, so a grace period only
# delays the exit. 0 stops at once: the buffered messages are dropped, which direct
# (at-most-once) messaging permits.
TERMINATE_GRACE_PERIOD_MS = 0

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


@dataclass
class SubscriberState:
    """What the main loop, the signal handler, and the API listener threads share: the
    service, the receiver, the shutdown event, and the counters. The service and the
    receiver are required, so every SubscriberState holds both (built, though not
    necessarily connected or started). Use this type of app for receiving Direct
    (at-most-once) messages from a topic."""
    messaging_service: MessagingService
    receiver: DirectMessageReceiver
    shutdown: threading.Event
    connect_attempted: bool = False
    exit_code: int = 0  # set to 1 on a failure exit (service interruption, a receive failure, or a failed terminate())
    msg_recv_counter: int = 0  # num messages received
    has_detected_discard: bool = False  # any discards seen?


def main(args: list[str]) -> int:
    trace(f"{API} {APP_NAME} initializing...")
    shutdown = threading.Event()

    def on_shutdown_signal(signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping subscriber...")
        shutdown.set()

    # graceful shutdown: SIGINT (Ctrl-C) or SIGTERM sets the shutdown event, the receive
    # loop exits once receive_message() returns, and teardown_solace() in the finally below
    # stops the receiver and disconnects. Python runs signal handlers on the main thread, so
    # the handler only flags; the cleanup runs on the main thread's normal exit path.
    # Registered before connect, so a SIGTERM during the blocking connect() still reaches
    # teardown.
    signal.signal(signal.SIGINT, on_shutdown_signal)
    signal.signal(signal.SIGTERM, on_shutdown_signal)
    # basic username/password connection details, built by the shared connection-config
    # helper: read from a config.json in the working directory if present, else from the
    # command line (<host:port> <message-vpn> <client-username> [password]). Turning the
    # application's input into Solace properties is the application's job, so it stays out
    # of setup_solace(), which works from the properties alone.
    properties = load_service_properties(args, APP_NAME)
    # setup_solace() raises before any connection exists, so there is nothing to tear down
    # if it fails
    state = setup_solace(properties, shutdown)
    try:
        connect_solace(state)
        receive_messages(state)
    finally:
        teardown_solace(state)
        trace("Main thread quitting.")
    return state.exit_code  # non-zero after a failure, so scripts and supervisors see it


def setup_solace(properties: dict, shutdown: threading.Event) -> SubscriberState:
    # build() creates the native session and resolves the host, so an unresolvable host
    # fails here, before connect()
    messaging_service = (
        MessagingService.builder()
        # the builder merges each call in order and a later call wins, so the reconnection
        # strategy comes first: it is the default, and reconnection keys in the properties
        # (for example from config.json) override it
        .with_reconnection_retry_strategy(
            RetryStrategy.parametrized_retry(RECONNECT_RETRIES, RECONNECT_RETRY_INTERVAL_MS))
        .from_properties(properties)
        .build()
    )
    # DIRECT receiver: a topic subscription on the receiver (no queue, no provisioning).
    # Direct messaging is at-most-once: messages flow straight to the subscriber with no
    # broker ACK and no redelivery. start() applies the subscription and blocks until the
    # broker confirms it, so the route is live before this subscriber reports ready.
    # Capacity-bounded back pressure: once RECEIVE_BUFFER_CAPACITY messages wait for the
    # receive loop, on_back_pressure_drop_latest(n) discards each new incoming message, and
    # the next message delivered carries an internal discard indication. The API default
    # (on_back_pressure_elastic) buffers without bound; on_back_pressure_drop_oldest(n)
    # discards the oldest buffered message instead. Building the receiver needs only the
    # built service; start() is what requires the connection.
    receiver = (
        messaging_service.create_direct_message_receiver_builder()
        .with_subscriptions([TopicSubscription.of(TOPIC_NAME)])
        .on_back_pressure_drop_latest(RECEIVE_BUFFER_CAPACITY)
        .build()
    )
    state = SubscriberState(messaging_service=messaging_service, receiver=receiver, shutdown=shutdown)
    # best practice: register the service event listeners BEFORE connect(), so no
    # reconnection or interruption event raised during or right after the connect is lost,
    # and handle each event appropriately rather than only logging it
    service_events = ServiceEventHandler(state)
    messaging_service.add_reconnection_attempt_listener(service_events)
    messaging_service.add_reconnection_listener(service_events)
    messaging_service.add_service_interruption_listener(service_events)
    return state


def connect_solace(state: SubscriberState) -> None:
    state.connect_attempted = True
    state.messaging_service.connect()  # blocking connect
    # no receive_async(): the receive loop takes each message with receive_message(). The
    # API reference states the two receive styles are mutually exclusive on one receiver.
    trace(f"Adding direct topic subscription '{TOPIC_NAME}'.")
    state.receiver.start()
    trace(f"{APP_NAME} subscribed and consuming. Press Ctrl-C to quit.")


def receive_messages(state: SubscriberState) -> None:
    next_report = time.monotonic() + STATS_INTERVAL_S
    while not state.shutdown.is_set():
        try:
            # blocks up to RECEIVE_TIMEOUT_MS for the next message. None means a timeout, or
            # that the receiver or the service is shutting down; either way the loop goes
            # round and the shutdown check above decides whether to stop
            message = state.receiver.receive_message(RECEIVE_TIMEOUT_MS)
        except PubSubPlusClientError as error:
            # raised once the service is down or the receiver is terminated. A service
            # interruption has already set shutdown and exit_code
            if not state.shutdown.is_set():
                logger.error("receive_message() failed, quitting: %s", error)
                state.exit_code = 1
            break
        if message is not None:
            process_message(state, message)
        # simple way of calculating message rates
        now = time.monotonic()
        if now >= next_report:
            trace(f"{API} {APP_NAME} Received msgs/s: {state.msg_recv_counter:,}")
            state.msg_recv_counter = 0
            if state.has_detected_discard:  # at least one direct message was dropped before this subscriber
                trace("*** Discard detected (at-most-once: a direct message was dropped) ***")
                state.has_detected_discard = False  # only show the warning once per report
            next_report = now + STATS_INTERVAL_S


def process_message(state: SubscriberState, message: InboundMessage) -> None:
    state.msg_recv_counter += 1
    # the publisher sample sends a bytearray payload (the binary attachment), read back with
    # get_payload_as_bytes(); a str payload arrives via get_payload_as_string() instead
    # best practice: handle an unexpected message format without raising (C API Best
    # Practices); errors="replace" keeps a non-UTF-8 payload from raising in the receive loop
    payload = message.get_payload_as_bytes()
    text = payload.decode("utf-8", errors="replace") if payload is not None else message.get_payload_as_string()
    trace(f"{API} {APP_NAME} received: {text}")
    # at-most-once discard evidence (API reference): the broker sets the broker discard
    # indication when it discarded direct messages destined for this subscriber for any
    # reason, and the API sets the internal one when its own receiver buffer dropped
    # messages. There is NO redelivery in direct messaging.
    discard = message.get_message_discard_notification()
    if discard.has_broker_discard_indication() or discard.has_internal_discard_indication():
        state.has_detected_discard = True
    # Direct messaging is at-most-once: there is no consumer-side acknowledgement here (the
    # inverse of the guaranteed CLIENT-ack subscriber). The message is consumed as it
    # arrives; the broker holds no copy and expects no ack.


def teardown_solace(state: SubscriberState) -> None:
    # Application cleanup belongs here: once setup_solace() returns, teardown_solace() runs
    # in main's finally on EVERY exit path (normal quit, SIGINT/SIGTERM, service
    # interruption, or an exception), so Solace teardown and application-side cleanup are
    # never skipped.
    state.shutdown.set()
    if state.receiver.is_running():
        # stop delivery at once (TERMINATE_GRACE_PERIOD_MS is 0): the receive loop has
        # stopped, so nothing takes more messages from the API's buffer. terminate() raises
        # IncompleteMessageDeliveryError when the buffer still holds messages. Direct is
        # at-most-once, so dropping them is a normal stop, not a failure. Catch it so the
        # disconnect below still runs
        try:
            state.receiver.terminate(TERMINATE_GRACE_PERIOD_MS)
        except IncompleteMessageDeliveryError as error:
            logger.warning("Receiver stopped with unprocessed direct messages in its buffer; "
                           "they are dropped (at-most-once): %s", error)
        except PubSubPlusClientError as error:
            logger.error("Receiver terminate() failed: %s", error)
            state.exit_code = 1
    if state.connect_attempted:
        # disconnect() raises IllegalStateError on a service that never attempted to connect,
        # hence the flag; on a service that is already down it returns quietly
        state.messaging_service.disconnect()  # will also release the receiver


def trace(message: str) -> None:
    """Demo narration sink: every status line in this sample funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system. The logger calls for API events (service) are a
    separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread, even
    in this blocking sample: return promptly and do not block in them, since events and
    delivery stall while a callback runs (C API Best Practices)."""

    def __init__(self, state: SubscriberState) -> None:
        self.state = state

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress: direct delivery is paused,
        # and the broker does not store direct messages for this subscriber while it is
        # disconnected (at-most-once). receive_message() keeps timing out meanwhile
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
        # cleanup from here. This sample sets shutdown, so the receive loop exits and
        # teardown_solace() runs in main's finally.
        self.state.exit_code = 1
        self.state.shutdown.set()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(main(sys.argv[1:]))
