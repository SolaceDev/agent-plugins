"""
Derived from patterns/guaranteed_receiver.py
  SolaceSamples/solace-samples-python
  https://github.com/SolaceSamples/solace-samples-python

Elevated to documented best practices per the Python API developer guide:
  Messaging Service:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md
  Consuming Persistent Messages:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md
  Creating Queues with the Solace Python API:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md
  the Python API reference (units, defaults, listener contracts):
    https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html
  and the C API Best Practices (the Python API wraps the C API, so its callback,
  reconnect, acknowledgement, and time-to-live practices apply):
    https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md

Reference sample. Wired for a basic publish-subscribe journey, BLOCKING variant:
basic-auth connect with a reconnection retry strategy, in-process provisioning of a
durable exclusive queue plus a topic subscription so a fresh broker works out of the
box, a CLIENT-ack receiver whose main loop calls receive_message(timeout), processes
the message, and then ACKs it, receiver active/passive state and termination
handling, reconnection and service interruption listeners registered BEFORE
connect(), and a graceful SIGINT/SIGTERM shutdown. The non-blocking variant is
python-guaranteed-subscriber-sample.py. Structured as module-level functions,
setup_solace(...), connect_solace(...), receive_messages(...), and
teardown_solace(...), that share one SubscriberState; setup_solace() creates the
service and the receiver before any connection exists, and main() runs teardown from
its finally on every exit path after that.

Why blocking: the application's own thread is the single thread of control for the
messaging data path. The main thread takes each message from the receiver, processes
it, and ACKs it, so no message handler runs on an API thread and no application state
is shared with one. In Python the GIL lets only one thread run Python code at a time,
so the message-handler design gains less from its extra thread than it does in other
languages. This sample makes no throughput claim. The cost: the loop processes one
message at a time, and a shutdown signal takes effect only when receive_message()
returns. The service event, receiver state, and termination listeners still run on
API threads.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as guaranteed_subscriber_blocking.py, next to the
shared helper python-solace-connection-config.py copied as solace_connection_config.py.

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

from solace.messaging.config.missing_resources_creation_configuration import MissingResourcesCreationStrategy
from solace.messaging.config.receiver_activation_passivation_configuration import (
    ReceiverState,
    ReceiverStateChangeListener,
)
from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.errors.pubsubplus_client_error import IncompleteMessageDeliveryError, PubSubPlusClientError
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.receiver.inbound_message import InboundMessage
from solace.messaging.receiver.persistent_message_receiver import PersistentMessageReceiver
from solace.messaging.resources.queue import Queue
from solace.messaging.resources.topic_subscription import TopicSubscription
from solace.messaging.utils.life_cycle_control import TerminationEvent, TerminationNotificationListener

from solace_connection_config import load_service_properties

APP_NAME = "GuaranteedSubscriberBlocking"
QUEUE_NAME = "q_python_sub"
# topic to map onto the queue; matches the publisher sample's topic root
TOPIC_NAME = "solace/samples/python/pers/pub/>"
API = "Python"
# receive_message(timeout) blocks up to this many MILLISECONDS for the next message, then
# returns None. The loop checks the shutdown event each time the call returns, so this value
# bounds how long a shutdown signal waits during an empty wait. 1 s keeps Ctrl-C prompt and
# matches the rate report interval; a longer timeout wakes the loop less often but stops
# slower. Never pass None: the call then waits without a limit and a shutdown signal never
# takes effect while the queue is empty.
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
# terminate(grace_period): how long to wait for the messages the API has already received
# to be taken from its buffer before the receiver stops. In this blocking sample only the
# receive loop takes them, and that loop has stopped by teardown, so a grace period only
# delays the exit. 0 stops at once: a message received but not yet ACKed stays on the queue
# and is redelivered, so nothing is lost.
TERMINATE_GRACE_PERIOD_MS = 0

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


@dataclass
class SubscriberState:
    """What the main loop, the signal handler, and the API listener threads share: the
    service, the receiver, the shutdown event, and the counters. The service and the
    receiver are required, so every SubscriberState holds both (built, though not
    necessarily connected or started). Use this type of app for receiving Guaranteed
    messages (e.g. via a queue endpoint)."""
    messaging_service: MessagingService
    receiver: PersistentMessageReceiver
    shutdown: threading.Event
    connect_attempted: bool = False
    exit_code: int = 0  # set to 1 on a failure exit (service interruption, receiver termination, or a receive failure)
    msg_recv_counter: int = 0  # num messages received
    has_detected_redelivery: bool = False  # detected any messages being redelivered?


def main(args: list[str]) -> int:
    trace(f"{API} {APP_NAME} initializing...")
    shutdown = threading.Event()

    def on_shutdown_signal(signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping consumer...")
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
        if not connect_solace(state):
            return 1  # the bind failed; the finally below still runs teardown
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
    # configure the queue API object locally: durable, exclusive (single-consumer sample)
    queue = Queue.durable_exclusive_queue(QUEUE_NAME)

    # ELEVATION: provision the durable queue in-process and map the topic onto it at
    # start, so a fresh broker works out of the box. CREATE_ON_START creates the queue
    # passed to build() when start() runs, as long as the client has permission; the
    # default, DO_NOT_CREATE, disables provisioning so an administrator provisions the
    # queue instead. with_subscriptions() on a durable queue adds the topic subscription
    # to the queue at start (pub/sub onto a queue). A re-run reuses the existing queue
    # and subscription.
    receiver_builder = (
        messaging_service.create_persistent_message_receiver_builder()
        .with_missing_resources_creation_strategy(MissingResourcesCreationStrategy.CREATE_ON_START)
        .with_subscriptions([TopicSubscription.of(TOPIC_NAME)])
        # best practice: client acknowledgement (also the API default), so a message leaves
        # the queue only after the loop has processed it and called ack()
        .with_message_client_acknowledgement()
        # request active/passive state changes: on an exclusive queue only one bound receiver
        # is ACTIVE; any other is PASSIVE (bound, not receiving) until the active one goes away
        .with_activation_passivation_support(ReceiverStateHandler())
    )
    # building the receiver needs only the built service; start() is what requires the
    # connection
    receiver = receiver_builder.build(queue)
    state = SubscriberState(messaging_service=messaging_service, receiver=receiver, shutdown=shutdown)
    # a broker-initiated termination (for example the queue was deleted or shut down)
    # surfaces here; the handler ends the receive loop (see ReceiverTerminationHandler below)
    receiver.set_termination_notification_listener(ReceiverTerminationHandler(state))
    # best practice: register the service event listeners BEFORE connect(), so no
    # reconnection or interruption event raised during or right after the connect is lost,
    # and handle each event appropriately rather than only logging it
    service_events = ServiceEventHandler(state)
    messaging_service.add_reconnection_attempt_listener(service_events)
    messaging_service.add_reconnection_listener(service_events)
    messaging_service.add_service_interruption_listener(service_events)
    return state


def connect_solace(state: SubscriberState) -> bool:
    state.connect_attempted = True
    state.messaging_service.connect()  # blocking connect
    # no receive_async(): the receive loop takes each message with receive_message(). The
    # API reference states the two receive styles are mutually exclusive on one receiver.
    trace(f"Attempting to bind to queue '{QUEUE_NAME}' on the broker.")
    try:
        # start() provisions the queue plus its subscription (CREATE_ON_START) and binds
        # to the queue; the broker then starts sending messages, and the API buffers them
        # until receive_message() takes them
        state.receiver.start()
    except PubSubPlusClientError as error:
        logger.error("Could not bind to queue '%s': %s. If this client may not create endpoints, "
                     "provision the queue and its topic subscription out-of-band or grant the "
                     "capability. Exiting.", QUEUE_NAME, error)
        return False  # teardown_solace() in main's finally disconnects
    return True


def receive_messages(state: SubscriberState) -> None:
    trace(f"{APP_NAME} connected, and running. Press Ctrl-C to quit.")
    next_report = time.monotonic() + STATS_INTERVAL_S
    while not state.shutdown.is_set():
        try:
            # blocks up to RECEIVE_TIMEOUT_MS for the next message. None means a timeout, or
            # that the receiver or the service is shutting down; either way the loop goes
            # round and the shutdown check above decides whether to stop
            message = state.receiver.receive_message(RECEIVE_TIMEOUT_MS)
        except PubSubPlusClientError as error:
            # raised once the service is down or the receiver is terminated. A service
            # interruption or a receiver termination has already set shutdown and exit_code
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
            if state.has_detected_redelivery:  # try shutting -> enabling the queue on the broker to see this
                trace("*** Redelivery detected ***")
                state.has_detected_redelivery = False  # only show the error once per report
            next_report = now + STATS_INTERVAL_S


def process_message(state: SubscriberState, message: InboundMessage) -> None:
    state.msg_recv_counter += 1
    try:
        # the publisher sample sends a bytearray payload (the binary attachment), read back with
        # get_payload_as_bytes(); a str payload arrives via get_payload_as_string() instead.
        # errors="replace" keeps a non-UTF-8 payload from raising, but the payload read or the
        # trace can still raise, hence the try/finally around the processing
        payload = message.get_payload_as_bytes()
        text = payload.decode("utf-8", errors="replace") if payload is not None else message.get_payload_as_string()
        trace(f"{API} {APP_NAME} received: {text}")
        if message.is_redelivered():  # useful check
            # this is the broker telling the consumer that this message has been sent and not ACKed before.
            # this can happen if an exception is thrown, or the broker restarts, or the network disconnects
            # perhaps an error in processing? Should do extra checks to avoid duplicate processing
            state.has_detected_redelivery = True
    except Exception:
        # best practice: handle an unexpected message format without crashing, log it, and
        # still ACK below (C API Best Practices). Without this, the exception would end the
        # receive loop and the message would stay unacknowledged on the queue.
        logger.exception("Processing failed for message id %s; acknowledging anyway",
                         message.get_application_message_id())
    finally:
        # Messages are removed from the broker queue when the ACK is received.
        # ACK only after processing is complete: DO NOT ACK until all processing/storing
        # of this message is done. ACK even an unexpectedly formatted message (C API Best
        # Practices): a message left unacknowledged stays on the queue and is redelivered. To
        # NACK explicitly instead, build the receiver with
        # with_required_message_outcome_support(Outcome.FAILED, Outcome.REJECTED) and call
        # receiver.settle(message, outcome).
        try:
            state.receiver.ack(message)  # ACKs are asynchronous
        except PubSubPlusClientError as error:
            # the ACK did not reach the API (for example the broker terminated the receiver,
            # or the service went down): the message stays on the queue and is redelivered
            logger.warning("ACK failed for message id %s; the broker redelivers it: %s",
                           message.get_application_message_id(), error)


def teardown_solace(state: SubscriberState) -> None:
    # Application cleanup belongs here: once setup_solace() returns, teardown_solace() runs
    # in main's finally on EVERY exit path (normal quit, SIGINT/SIGTERM, service
    # interruption, receiver termination, or an exception), so Solace teardown and
    # application-side cleanup are never skipped.
    state.shutdown.set()
    if state.receiver.is_running():
        # stop delivery at once (TERMINATE_GRACE_PERIOD_MS is 0): the receive loop has
        # stopped, so nothing takes more messages from the API's buffer. terminate() raises
        # IncompleteMessageDeliveryError when the buffer still holds messages. Those messages
        # were never ACKed, so they stay on the queue and are redelivered: a normal stop, not
        # a failure. Catch it so the disconnect below still runs
        try:
            state.receiver.terminate(TERMINATE_GRACE_PERIOD_MS)
        except IncompleteMessageDeliveryError as error:
            logger.warning("Receiver stopped with unprocessed messages in its buffer; the broker "
                           "redelivers them: %s", error)
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
    logger or reporting system. The logger calls for API events (service, receiver
    state, termination) are a separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread, even
    in this blocking sample: return promptly and do not block in them, since events and
    delivery stall while a callback runs (C API Best Practices)."""

    def __init__(self, state: SubscriberState) -> None:
        self.state = state

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress; receive_message() keeps
        # timing out and the loop keeps going
        logger.warning("Service reconnecting; message delivery is paused until re-established: %s (%s)",
                       event.get_message(), event.get_cause())

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded; the receiver rebinds to the queue and delivery resumes
        logger.info("Service reconnected to %s; the receiver rebinds and delivery resumes", event.get_broker_uri())

    def on_service_interrupted(self, event: ServiceEvent) -> None:
        # the connection went down and cannot be restored (reconnect attempts exhausted)
        logger.error("Service interrupted and cannot be restored, quitting: %s (%s)",
                     event.get_message(), event.get_cause())
        # Application cleanup signal: the service will not recover. Trigger application-side
        # cleanup from here. This sample sets shutdown, so the receive loop exits and
        # teardown_solace() runs in main's finally.
        self.state.exit_code = 1
        self.state.shutdown.set()


class ReceiverStateHandler(ReceiverStateChangeListener):
    """Reports whether this receiver is the one the broker delivers to. Runs on an API thread:
    return promptly and do not block in it (C API Best Practices)."""

    def on_change(self, old_state: ReceiverState, new_state: ReceiverState, change_time_stamp: float) -> None:
        if new_state == ReceiverState.ACTIVE:  # bound and actively receiving from the queue
            logger.info("Receiver active: consuming from queue %s", QUEUE_NAME)
        else:  # PASSIVE: bound but not receiving (another consumer is active on this exclusive queue)
            logger.warning("Receiver passive: bound but not receiving (another consumer is active on "
                           "exclusive queue %s)", QUEUE_NAME)


class ReceiverTerminationHandler(TerminationNotificationListener):
    """Handles a broker-initiated termination of the receiver. Runs on an API thread:
    return promptly and do not block in it (C API Best Practices)."""

    def __init__(self, state: SubscriberState) -> None:
        self.state = state

    def on_termination(self, event: TerminationEvent) -> None:
        # the receiver was terminated by the broker: delivery from the queue has stopped
        logger.error("Receiver terminated, delivery from queue %s has stopped: %s (%s)",
                     QUEUE_NAME, event.message, event.cause)
        # Application cleanup signal: decide here whether to recreate the receiver or shut
        # down. This sample shuts down: the receive loop exits and teardown_solace() runs in
        # main's finally.
        self.state.exit_code = 1
        self.state.shutdown.set()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(main(sys.argv[1:]))
