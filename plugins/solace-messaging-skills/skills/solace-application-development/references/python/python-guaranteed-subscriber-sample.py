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

Reference sample. Wired for a basic publish-subscribe journey: basic-auth connect
with a reconnection retry strategy, in-process provisioning of a durable exclusive
queue plus a topic subscription so a fresh broker works out of the box, a
CLIENT-ack receiver that ACKs only after processing, receiver active/passive state
and termination handling, reconnection and service interruption listeners
registered BEFORE connect(), and a graceful SIGINT/SIGTERM shutdown. Structured as
setup_solace(...), await_messages(), and teardown_solace(), with teardown run
from main's finally on every exit path.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as guaranteed_subscriber.py, next to the shared
helper python-solace-connection-config.py copied as solace_connection_config.py.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

import logging
import signal
import sys
import threading
from typing import Optional

from solace.messaging.config.missing_resources_creation_configuration import MissingResourcesCreationStrategy
from solace.messaging.config.receiver_activation_passivation_configuration import (
    ReceiverState,
    ReceiverStateChangeListener,
)
from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.errors.pubsubplus_client_error import PubSubPlusClientError
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.receiver.inbound_message import InboundMessage
from solace.messaging.receiver.message_receiver import MessageHandler
from solace.messaging.receiver.persistent_message_receiver import PersistentMessageReceiver
from solace.messaging.resources.queue import Queue
from solace.messaging.resources.topic_subscription import TopicSubscription
from solace.messaging.utils.life_cycle_control import TerminationEvent, TerminationNotificationListener

from solace_connection_config import SolaceConnectionConfig

APP_NAME = "GuaranteedSubscriber"
QUEUE_NAME = "q_python_sub"
# topic to map onto the queue; matches the publisher sample's topic root
TOPIC_NAME = "solace/samples/python/pers/pub/>"
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


class GuaranteedSubscriber:
    """The app: the service, the receiver, the flags the listeners flip, and the counters.
    Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint)."""

    def __init__(self) -> None:
        self.messaging_service: Optional[MessagingService] = None
        self.receiver: Optional[PersistentMessageReceiver] = None
        self.connect_attempted = False
        self.shutdown = threading.Event()
        self.exit_code = 0  # set to 1 on a failure exit (failed bind, service interruption, or receiver termination)
        self.msg_recv_counter = 0  # num messages received
        self.has_detected_redelivery = False  # detected any messages being redelivered?

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
            if not self.setup_solace(args):
                return 1  # the bind failed; the finally below still runs teardown
            self.await_messages()
        finally:
            self.teardown_solace()
            trace("Main thread quitting.")
        return self.exit_code  # non-zero after a failure, so scripts and supervisors see it

    def on_shutdown_signal(self, signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping consumer...")
        self.shutdown.set()

    def setup_solace(self, args: list[str]) -> bool:
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
            self.messaging_service.create_persistent_message_receiver_builder()
            .with_missing_resources_creation_strategy(MissingResourcesCreationStrategy.CREATE_ON_START)
            .with_subscriptions([TopicSubscription.of(TOPIC_NAME)])
            # best practice: client acknowledgement (also the API default), so a message leaves
            # the queue only after the handler has processed it and called ack()
            .with_message_client_acknowledgement()
            # request active/passive state changes: on an exclusive queue only one bound receiver
            # is ACTIVE; any other is PASSIVE (bound, not receiving) until the active one goes away
            .with_activation_passivation_support(ReceiverStateHandler())
        )
        trace(f"Attempting to bind to queue '{QUEUE_NAME}' on the broker.")
        self.receiver = receiver_builder.build(queue)
        # a broker-initiated termination (for example the queue was deleted or shut down)
        # surfaces here; the handler ends the main loop (see ReceiverTerminationHandler below)
        self.receiver.set_termination_notification_listener(ReceiverTerminationHandler(self))
        try:
            # start() provisions the queue plus its subscription (CREATE_ON_START) and binds
            # to the queue; the broker then starts sending messages on this receiver
            self.receiver.start()
        except PubSubPlusClientError as error:
            logger.error("Could not bind to queue '%s': %s. If this client may not create endpoints, "
                         "provision the queue and its topic subscription out-of-band or grant the "
                         "capability. Exiting.", QUEUE_NAME, error)
            return False  # teardown_solace() in main's finally disconnects
        # see bottom of file for QueueMessageHandler, which receives the messages from the queue
        self.receiver.receive_async(QueueMessageHandler(self, self.receiver))
        return True

    def await_messages(self) -> None:
        # async queue receive working now, so time to wait until done...
        trace(f"{APP_NAME} connected, and running. Press Ctrl-C to quit.")
        while not self.shutdown.wait(1.0):  # wait 1 second; the wait returns True once shutdown is requested
            trace(f"{API} {APP_NAME} Received msgs/s: {self.msg_recv_counter:,}")  # simple way of calculating message rates
            self.msg_recv_counter = 0
            if self.has_detected_redelivery:  # try shutting -> enabling the queue on the broker to see this
                trace("*** Redelivery detected ***")
                self.has_detected_redelivery = False  # only show the error once per second

    def teardown_solace(self) -> None:
        # Application cleanup belongs here: teardown_solace() runs in main's finally on
        # EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, receiver
        # termination, or an exception), so Solace teardown and application-side cleanup
        # are never skipped.
        self.shutdown.set()
        if self.receiver is not None and self.receiver.is_running():
            # gracefully stop delivery before exit: terminate(grace_period) stops the receiver
            # and gives the handler up to the grace period to drain (and ACK) the messages the
            # API has already received. The receiver turns TERMINATED once its buffer is empty,
            # while the handler may still hold the last message, so that message's ack() can
            # fail (the API logs a warning). A message received but not yet ACKed stays on the
            # queue and is redelivered. terminate() raises IncompleteMessageDeliveryError when
            # messages remain after the grace period; catch it so the disconnect below still runs
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
    logger or reporting system. The logger calls for API events (service, receiver
    state, termination) are a separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread:
    return promptly and do not block in them, since events and delivery stall while a
    callback runs (C API Best Practices)."""

    def __init__(self, app: GuaranteedSubscriber) -> None:
        self.app = app

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress
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
        # cleanup from here. This sample sets shutdown, so the main loop exits and
        # teardown_solace() runs in main's finally.
        self.app.exit_code = 1
        self.app.shutdown.set()


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

    def __init__(self, app: GuaranteedSubscriber) -> None:
        self.app = app

    def on_termination(self, event: TerminationEvent) -> None:
        # the receiver was terminated by the broker: delivery from the queue has stopped
        logger.error("Receiver terminated, delivery from queue %s has stopped: %s (%s)",
                     QUEUE_NAME, event.message, event.cause)
        # Application cleanup signal: decide here whether to recreate the receiver or shut
        # down. This sample shuts down: the main loop exits and teardown_solace() runs in
        # main's finally.
        self.app.exit_code = 1
        self.app.shutdown.set()


class QueueMessageHandler(MessageHandler):
    """Very simple class, used for receiving messages from the queue. on_message runs on an
    API-owned receiver thread, not the main thread: return promptly and do not block in it
    (hand heavy work to the application's own thread or queue), since delivery from the queue
    stalls while it runs (C API Best Practices)."""

    def __init__(self, app: GuaranteedSubscriber, receiver: PersistentMessageReceiver) -> None:
        self.app = app
        self.receiver = receiver

    def on_message(self, message: InboundMessage) -> None:
        self.app.msg_recv_counter += 1
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
                self.app.has_detected_redelivery = True
        except Exception:
            # best practice: handle an unexpected message format without crashing, log it, and
            # still ACK below (C API Best Practices). Without this, the API swallows the exception
            # with one warning and the message stays unacknowledged on the flow.
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
            self.receiver.ack(message)  # ACKs are asynchronous


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(GuaranteedSubscriber().main(sys.argv[1:]))
