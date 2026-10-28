"""
Derived from patterns/direct_requestor_blocking.py
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

Reference sample. Wired for a basic direct request-reply journey on the requestor
side, BLOCKING variant: basic-auth connect with a reconnection retry strategy, a
RequestReplyMessagePublisher, one blocking publish_await_response() with a POSITIVE
reply timeout, a PubSubTimeoutError catch with a single retry to absorb a cold-start
race, reconnection and service interruption listeners registered BEFORE connect(),
and a graceful SIGINT/SIGTERM shutdown. The blocking request IS the synchronization
point: the requestor sends one request, waits for the correlated reply, and exits
(0 on a reply or a shutdown signal, 1 without a reply). Request-reply in the Python API is direct only, so
it is at-most-once on both legs. The non-blocking variant is
python-direct-requestor-sample.py. Structured as module-level functions,
setup_solace(...), connect_solace(...), send_request(...), and teardown_solace(...),
that share one RequestorState; setup_solace() creates the service and the requestor
before any connection exists, and main() runs teardown from its finally on every
exit path after that.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as direct_requestor_blocking.py, next to the shared
helper python-solace-connection-config.py copied as solace_connection_config.py.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

import logging
import signal
import sys
import threading
import uuid
from dataclasses import dataclass

from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.config.solace_properties import message_properties
from solace.messaging.errors.pubsubplus_client_error import (
    IncompleteMessageDeliveryError,
    PubSubPlusClientError,
    PubSubTimeoutError,
)
from solace.messaging.messaging_service import (
    MessagingService,
    ReconnectionAttemptListener,
    ReconnectionListener,
    ServiceEvent,
    ServiceInterruptionListener,
)
from solace.messaging.publisher.request_reply_message_publisher import RequestReplyMessagePublisher
from solace.messaging.resources.topic import Topic

from solace_connection_config import load_service_properties

APP_NAME = "DirectRequestorBlocking"
# request topic the replier subscribes to; matches the direct replier sample
REQUEST_TOPIC = "solace/samples/python/direct/request"
API = "Python"
# POSITIVE reply timeout in MILLISECONDS: publish_await_response() blocks up to this long
# for the correlated reply, then raises PubSubTimeoutError. The API has no default and
# requires a positive value.
REQUEST_TIMEOUT_MS = 3000
# one retry after a timeout absorbs a cold-start race: the replier may not yet have its
# subscription in place when the first request goes out
REQUEST_ATTEMPTS = 2
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
# terminate(grace_period): how long to wait for a request still waiting on its reply before
# the requestor stops; terminate() raises IncompleteMessageDeliveryError when a request is
# still outstanding after that. A bounded grace period keeps shutdown time predictable, for
# example within the stop timeout of a process supervisor, so the disconnect still runs.
# The API default is 600000 ms (10 minutes). Adjust the value to suit the application:
# longer gives an outstanding request more time to get its reply, shorter exits sooner.
TERMINATE_GRACE_PERIOD_MS = 10_000

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


@dataclass
class RequestorState:
    """What the main thread, the signal handler, and the API listener threads share: the
    service, the requestor, and the shutdown event. The service and the requestor are
    required, so every RequestorState holds both (built, though not necessarily connected
    or started)."""
    messaging_service: MessagingService
    requestor: RequestReplyMessagePublisher
    shutdown: threading.Event
    connect_attempted: bool = False
    exit_code: int = 0  # set to 1 on a failure exit (no reply, service interruption, or a failed terminate())


def main(args: list[str]) -> int:
    trace(f"{API} {APP_NAME} initializing...")
    shutdown = threading.Event()

    def on_shutdown_signal(signum: int, _frame) -> None:
        trace(f"Shutdown signal received ({signal.Signals(signum).name}), stopping requestor...")
        shutdown.set()

    # graceful shutdown: SIGINT (Ctrl-C) or SIGTERM sets the shutdown event, no further
    # request goes out, and teardown_solace() in the finally below stops the requestor and
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
        send_request(state)
    finally:
        teardown_solace(state)
        trace("Main thread quitting.")
    return state.exit_code  # non-zero after a failure, so scripts and supervisors see it


def setup_solace(properties: dict, shutdown: threading.Event) -> RequestorState:
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
    # request-reply publisher: the API sets a unique reply-to topic on each request and
    # correlates the reply, so the requestor needs no reply subscription and no correlation
    # id of its own. Its builder has no back-pressure setting (unlike the direct publisher),
    # and its from_properties() applies no property, so there is nothing more to configure.
    # Building the requestor needs only the built service; start() is what requires the
    # connection.
    requestor = (
        messaging_service.request_reply()
        .create_request_reply_message_publisher_builder()
        .build()
    )
    state = RequestorState(messaging_service=messaging_service, requestor=requestor, shutdown=shutdown)
    # best practice: register the service event listeners BEFORE connect(), so no
    # reconnection or interruption event raised during or right after the connect is lost,
    # and handle each event appropriately rather than only logging it
    service_events = ServiceEventHandler(state)
    messaging_service.add_reconnection_attempt_listener(service_events)
    messaging_service.add_reconnection_listener(service_events)
    messaging_service.add_service_interruption_listener(service_events)
    return state


def connect_solace(state: RequestorState) -> None:
    state.connect_attempted = True
    state.messaging_service.connect()  # blocking connect
    state.requestor.start()  # raises IllegalStateError unless the service is connected


def send_request(state: RequestorState) -> None:
    # the request is an OutboundMessage: publish_await_response() takes no bare payload, so
    # the message builder is required here. A bytearray payload travels as the binary
    # attachment; the replier reads it back with get_payload_as_bytes()
    request = state.messaging_service.message_builder().build(
        bytearray(f"Request from {APP_NAME}".encode("utf-8")))
    # an application message id lets the replier echo it onto the reply for traceability;
    # the API does the request-reply correlation itself and does not need it
    msg_id = str(uuid.uuid4())
    request_topic = Topic.of(REQUEST_TOPIC)
    trace(f"{APP_NAME} sending a direct request to topic '{REQUEST_TOPIC}' (timeout {REQUEST_TIMEOUT_MS} ms).")
    reply = None
    for attempt in range(1, REQUEST_ATTEMPTS + 1):
        if state.shutdown.is_set():
            break  # a shutdown signal or a service interruption: send no further request
        try:
            # blocks until the correlated reply arrives or REQUEST_TIMEOUT_MS passes. A
            # shutdown signal that arrives during the wait takes effect once the call
            # returns, within REQUEST_TIMEOUT_MS.
            reply = state.requestor.publish_await_response(
                request_message=request,
                request_destination=request_topic,
                reply_timeout=REQUEST_TIMEOUT_MS,
                additional_message_properties={message_properties.APPLICATION_MESSAGE_ID: msg_id},
            )
            break
        except PubSubTimeoutError:
            if attempt < REQUEST_ATTEMPTS:
                logger.warning("Request timed out after %d ms; retrying once.", REQUEST_TIMEOUT_MS)
            else:
                logger.warning("Request timed out after %d ms on the retry; giving up.", REQUEST_TIMEOUT_MS)
        except PubSubPlusClientError as error:
            # the request could not be sent (for example the service is down); retrying would
            # not help
            logger.error("Request failed, quitting: %s", error)
            break

    if reply is None:
        if state.shutdown.is_set():
            # a shutdown signal is a clean stop (exit 0); a service interruption has already
            # set exit_code
            trace(f"{API} {APP_NAME} stopped before a reply arrived.")
        else:
            trace(f"{API} {APP_NAME} received no reply.")
            state.exit_code = 1
        return
    # the replier sample sends a bytearray payload (the binary attachment), read back with
    # get_payload_as_bytes(); a str payload arrives via get_payload_as_string() instead.
    # errors="replace" keeps a non-UTF-8 payload from raising
    payload = reply.get_payload_as_bytes()
    text = payload.decode("utf-8", errors="replace") if payload is not None else reply.get_payload_as_string()
    trace(f"{API} {APP_NAME} received a correlated reply (message id {reply.get_application_message_id()}): {text}")


def teardown_solace(state: RequestorState) -> None:
    # Application cleanup belongs here: once setup_solace() returns, teardown_solace() runs
    # in main's finally on EVERY exit path (a reply, no reply, SIGINT/SIGTERM, service
    # interruption, or an exception), so Solace teardown and application-side cleanup are
    # never skipped.
    state.shutdown.set()
    if state.requestor.is_running():
        # terminate() gives a request still waiting on its reply up to the grace period. It
        # raises IncompleteMessageDeliveryError when a request is still outstanding after
        # that; catch it so the disconnect below still runs
        try:
            state.requestor.terminate(TERMINATE_GRACE_PERIOD_MS)
        except IncompleteMessageDeliveryError as error:
            logger.error("Requestor stopped with a request still outstanding after %d ms: %s",
                         TERMINATE_GRACE_PERIOD_MS, error)
            state.exit_code = 1
        except PubSubPlusClientError as error:
            logger.error("Requestor terminate() failed: %s", error)
            state.exit_code = 1
    if state.connect_attempted:
        # disconnect() raises IllegalStateError on a service that never attempted to connect,
        # hence the flag; on a service that is already down it returns quietly
        state.messaging_service.disconnect()


def trace(message: str) -> None:
    """Demo narration sink: every status line in this sample funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system. The logger calls for API events (service, request)
    are a separate channel and stay as they are."""
    print(message, flush=True)


############################################################################


class ServiceEventHandler(ReconnectionAttemptListener, ReconnectionListener, ServiceInterruptionListener):
    """The three service event listeners in one class. Callbacks run on an API thread:
    return promptly and do not block in them, since events and delivery stall while a
    callback runs (C API Best Practices)."""

    def __init__(self, state: RequestorState) -> None:
        self.state = state

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress. Request-reply is direct
        # (at-most-once): a request or reply in flight during the outage can be lost, and the
        # request then times out
        logger.warning("Service reconnecting; a request in flight can time out: %s (%s)",
                       event.get_message(), event.get_cause())

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded: requests can go out again
        logger.info("Service reconnected to %s", event.get_broker_uri())

    def on_service_interrupted(self, event: ServiceEvent) -> None:
        # the connection went down and cannot be restored (reconnect attempts exhausted)
        logger.error("Service interrupted and cannot be restored, quitting: %s (%s)",
                     event.get_message(), event.get_cause())
        # Application cleanup signal: the service will not recover. Trigger application-side
        # cleanup from here. This sample sets shutdown, so no further request goes out, the
        # request in flight fails or times out, and teardown_solace() runs in main's finally.
        self.state.exit_code = 1
        self.state.shutdown.set()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(main(sys.argv[1:]))
