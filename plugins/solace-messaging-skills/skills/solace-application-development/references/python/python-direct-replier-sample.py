"""
Derived from patterns/direct_replier.py
  SolaceSamples/solace-samples-python
  https://github.com/SolaceSamples/solace-samples-python

Elevated to documented best practices per the Python API developer guide:
  Messaging Service:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md
  Request-Reply Messaging:
    https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Request-Reply.md
  the Python API reference (units, defaults, listener contracts):
    https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html
  and the C API Best Practices (the Python API wraps the C API, so its callback,
  reconnect, acknowledgement, and time-to-live practices apply):
    https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md

Reference sample. Wired for a basic direct request-reply journey on the replier
side: basic-auth connect with a reconnection retry strategy, a
RequestReplyMessageReceiver built with the request topic subscription (no queue, no
provisioning), an async RequestMessageHandler that guards on a replier (a request
without a reply-to cannot be answered) and answers each request with
replier.reply(), which routes to the request's reply-to and copies the correlation,
reconnection and service interruption listeners registered BEFORE connect(), and a
graceful SIGINT/SIGTERM shutdown. Request-reply in the Python API is direct only, so
it is at-most-once on both legs. Answers both requestor samples,
python-direct-requestor-blocking-sample.py and python-direct-requestor-sample.py.
Structured as module-level functions, setup_solace(...), connect_solace(...),
await_requests(...), and teardown_solace(...), that share one ReplierState;
setup_solace() creates the service and the replier before any connection exists, and
main() runs teardown from its finally on every exit path after that.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as direct_replier.py, next to the shared helper
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
from solace.messaging.config.solace_properties import message_properties
from solace.messaging.errors.pubsubplus_client_error import IncompleteMessageDeliveryError, PubSubPlusClientError
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.receiver.inbound_message import InboundMessage
from solace.messaging.receiver.request_reply_message_receiver import (
    Replier,
    RequestMessageHandler,
    RequestReplyMessageReceiver,
)
from solace.messaging.resources.topic_subscription import TopicSubscription

from solace_connection_config import load_service_properties

APP_NAME = "DirectReplier"
# request topic to subscribe to; the requestor samples send to this topic
REQUEST_TOPIC = "solace/samples/python/direct/request"
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
# terminate(grace_period): how long to wait for the handler to answer the requests the API
# has already received before the replier stops; terminate() raises
# IncompleteMessageDeliveryError when requests remain after that. A bounded grace period
# keeps shutdown time predictable, for example within the stop timeout of a process
# supervisor, so the disconnect still runs. The API default is 600000 ms (10 minutes).
# Adjust the value to suit the application: longer lets the handler answer more of the
# received requests, shorter exits sooner.
TERMINATE_GRACE_PERIOD_MS = 10_000

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


@dataclass
class ReplierState:
    """What the main loop, the signal handler, and the API listener threads share: the
    service, the replier, the shutdown event, and the counters. The service and the
    replier are required, so every ReplierState holds both (built, though not necessarily
    connected or started). Use this type of app to answer direct (at-most-once) requests
    on a topic."""
    messaging_service: MessagingService
    receiver: RequestReplyMessageReceiver
    shutdown: threading.Event
    connect_attempted: bool = False
    exit_code: int = 0  # set to 1 on a failure exit (service interruption or a failed terminate())
    msg_recv_counter: int = 0  # num requests received


def main(args: list[str]) -> int:
    trace(f"{API} {APP_NAME} initializing...")
    shutdown = threading.Event()

    def on_shutdown_signal(signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping replier...")
        shutdown.set()

    # graceful shutdown: SIGINT (Ctrl-C) or SIGTERM sets the shutdown event, the main
    # loop exits, and teardown_solace() in the finally below stops the replier and
    # disconnects. Python runs signal handlers on the main thread, so the handler only
    # flags; the cleanup runs on the main thread's normal exit path. Registered before
    # connect, so a SIGTERM during the blocking connect() still reaches teardown.
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
        await_requests(state)
    finally:
        teardown_solace(state)
        trace("Main thread quitting.")
    return state.exit_code  # non-zero after a failure, so scripts and supervisors see it


def setup_solace(properties: dict, shutdown: threading.Event) -> ReplierState:
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
    # request-reply receiver: the request topic subscription goes to build() (no queue, no
    # provisioning). start() applies the subscription and blocks until the broker confirms
    # it, so the route is live before this replier reports ready. Its builder has no
    # back-pressure setting (unlike the direct receiver), and its from_properties() applies
    # no property, so there is nothing more to configure. To spread requests across several
    # replier instances, pass a share name: build(subscription, ShareName.of("name")), and
    # each request then goes to one member of the group. Building the receiver needs only
    # the built service; start() is what requires the connection.
    receiver = (
        messaging_service.request_reply()
        .create_request_reply_message_receiver_builder()
        .build(TopicSubscription.of(REQUEST_TOPIC))
    )
    state = ReplierState(messaging_service=messaging_service, receiver=receiver, shutdown=shutdown)
    # best practice: register the service event listeners BEFORE connect(), so no
    # reconnection or interruption event raised during or right after the connect is lost,
    # and handle each event appropriately rather than only logging it
    service_events = ServiceEventHandler(state)
    messaging_service.add_reconnection_attempt_listener(service_events)
    messaging_service.add_reconnection_listener(service_events)
    messaging_service.add_service_interruption_listener(service_events)
    return state


def connect_solace(state: ReplierState) -> None:
    state.connect_attempted = True
    state.messaging_service.connect()  # blocking connect
    # set the request handler BEFORE start(), so the handler is in place when delivery
    # begins and received requests do not wait in the API's buffer for it. receive_async()
    # needs a connected service, so it runs here after connect(), not in setup_solace().
    # see bottom of file for RequestHandler, which answers the requests from the topic
    state.receiver.receive_async(RequestHandler(state))
    trace(f"Adding direct request-topic subscription '{REQUEST_TOPIC}'.")
    state.receiver.start()
    trace(f"{APP_NAME} subscribed and ready to reply. Press Ctrl-C to quit.")


def await_requests(state: ReplierState) -> None:
    # async request receive working now, so time to wait until done...
    while not state.shutdown.wait(1.0):  # wait 1 second; the wait returns True once shutdown is requested
        trace(f"{API} {APP_NAME} Requests/s: {state.msg_recv_counter:,}")  # simple way of calculating request rates
        state.msg_recv_counter = 0


def teardown_solace(state: ReplierState) -> None:
    # Application cleanup belongs here: once setup_solace() returns, teardown_solace() runs
    # in main's finally on EVERY exit path (normal quit, SIGINT/SIGTERM, service
    # interruption, or an exception), so Solace teardown and application-side cleanup are
    # never skipped.
    state.shutdown.set()
    if state.receiver.is_running():
        # request-reply is at-most-once: there are no acknowledgements to drain before exit,
        # so terminate() stops delivery and gives the handler up to the grace period to answer
        # the requests the API has already received. It raises IncompleteMessageDeliveryError
        # when requests remain after that; catch it so the disconnect below still runs
        try:
            state.receiver.terminate(TERMINATE_GRACE_PERIOD_MS)
        except IncompleteMessageDeliveryError as error:
            logger.error("Replier stopped with requests still unanswered after %d ms: %s",
                         TERMINATE_GRACE_PERIOD_MS, error)
            state.exit_code = 1
        except PubSubPlusClientError as error:
            logger.error("Replier terminate() failed: %s", error)
            state.exit_code = 1
    if state.connect_attempted:
        # disconnect() raises IllegalStateError on a service that never attempted to connect,
        # hence the flag; on a service that is already down it returns quietly
        state.messaging_service.disconnect()  # will also release the replier


def trace(message: str) -> None:
    """Demo narration sink: every status line in this sample funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system. The logger calls for API events (service, reply)
    are a separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread:
    return promptly and do not block in them, since events and delivery stall while a
    callback runs (C API Best Practices)."""

    def __init__(self, state: ReplierState) -> None:
        self.state = state

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress: request delivery is
        # paused, and the broker does not store direct requests for this replier while it
        # is disconnected (at-most-once)
        logger.warning("Service reconnecting; request delivery is paused until re-established: %s (%s)",
                       event.get_message(), event.get_cause())

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded; the API re-applies the request topic subscription and
        # delivery resumes (unlike JCSMP, no reapply-subscriptions property is needed)
        logger.info("Service reconnected to %s; the subscription is restored and replies resume",
                    event.get_broker_uri())

    def on_service_interrupted(self, event: ServiceEvent) -> None:
        # the connection went down and cannot be restored (reconnect attempts exhausted)
        logger.error("Service interrupted and cannot be restored, quitting: %s (%s)",
                     event.get_message(), event.get_cause())
        # Application cleanup signal: the service will not recover. Trigger application-side
        # cleanup from here. This sample sets shutdown, so the main loop exits and
        # teardown_solace() runs in main's finally.
        self.state.exit_code = 1
        self.state.shutdown.set()


class RequestHandler(RequestMessageHandler):
    """Very simple class, used for answering the direct requests from the topic. on_message
    runs on an API-owned receiver thread, not the main thread: return promptly and do not
    block in it (hand heavy work to the application's own thread or queue), since delivery
    stalls while it runs (C API Best Practices)."""

    def __init__(self, state: ReplierState) -> None:
        self.state = state
        # one OutboundMessageBuilder for every reply; the per-reply difference goes in
        # build()'s additional_message_properties. Replier.reply() takes an OutboundMessage,
        # not a bare payload, so the builder is required here
        self.message_builder = state.messaging_service.message_builder()

    def on_message(self, message: InboundMessage, replier: Replier) -> None:
        self.state.msg_recv_counter += 1
        # guard on the replier: the API passes None when the request has no reply-to
        # destination, and such a message cannot be answered. The requestor samples set the
        # reply-to automatically; a message arriving here without one is not a request.
        if replier is None:
            logger.warning("Received a message on the request topic with no reply-to; ignoring.")
            return
        try:
            # the requestor samples send a bytearray payload (the binary attachment), read back
            # with get_payload_as_bytes(); a str payload arrives via get_payload_as_string()
            # instead. best practice: handle an unexpected message format without raising (C
            # API Best Practices); errors="replace" keeps a non-UTF-8 payload from raising
            payload = message.get_payload_as_bytes()
            text = payload.decode("utf-8", errors="replace") if payload is not None else message.get_payload_as_string()
            # echo the request's application message id onto the reply for traceability; the
            # API copies the correlation itself, so the requestor matches the reply without it
            msg_id = message.get_application_message_id()
            reply_properties = {message_properties.APPLICATION_MESSAGE_ID: msg_id} if msg_id is not None else None
            reply = self.message_builder.build(
                bytearray(f"Reply from {APP_NAME} to: {text}".encode("utf-8")),
                additional_message_properties=reply_properties)
            # reply() routes to the request's reply-to and copies the correlation id, so the
            # requestor's publish_await_response() or publish() Future receives it
            replier.reply(reply)
        except PubSubPlusClientError as error:
            # the reply could not be sent (for example the service is down); the requestor
            # times out. Without this catch, the API logs the exception as one warning.
            logger.warning("Could not send the reply for message id %s: %s",
                           message.get_application_message_id(), error)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(main(sys.argv[1:]))
