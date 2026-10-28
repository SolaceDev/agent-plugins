"""
Derived from patterns/guaranteed_publisher.py and howtos/pubsub/how_to_publish_persistent_message.py
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

Reference sample. Wired for a basic publish-subscribe journey, BLOCKING variant:
basic-auth connect with a reconnection retry strategy, PERSISTENT binary publish to a
topic from a templated message builder that sets the time-to-live once, one blocking
publish_await_acknowledgement() per message with a POSITIVE acknowledgement timeout,
reconnection and service interruption listeners registered BEFORE connect(), and a
graceful SIGINT/SIGTERM shutdown. There is no publish receipt listener: the call
returns once the broker ACKs the message and raises on a NACK or a timeout. A timeout,
a NACK, or a buffer overflow discards that message and the publisher continues; any
other publish failure stops the publisher with exit code 1. The non-blocking variant is
python-guaranteed-publisher-sample.py. Structured as module-level functions,
setup_solace(...), connect_solace(...), run_publish_loop(...), and teardown_solace(...),
that share one PublisherState; setup_solace() creates the service and the publisher
before any connection exists, and main() runs teardown from its finally on every exit
path after that.

Why blocking: the application's own thread is the single thread of control for the
messaging data path. The main thread publishes each message and handles its ACK or
NACK itself, so no receipt callback runs on an API thread and no application state is
shared with one. In Python the GIL lets only one thread run Python code at a time, so
the receipt-listener design gains less from its extra thread than it does in other
languages. This sample makes no throughput claim. The cost: only one persistent
message is in flight at a time, so each message waits a full broker round trip before
the next one goes out. The service event listeners still run on API threads.

Only practices documented in canonical Solace sources are encoded here.

Copied into a generated project as guaranteed_publisher_blocking.py, next to the shared
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
import uuid
from dataclasses import dataclass

from solace.messaging.config.retry_strategy import RetryStrategy
from solace.messaging.config.solace_properties import message_properties
from solace.messaging.errors.pubsubplus_client_error import (
    IncompleteMessageDeliveryError,
    MessageDestinationDoesNotExistError,
    MessageNotAcknowledgedByBrokerError,
    MessageRejectedByBrokerError,
    PublisherOverflowError,
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
from solace.messaging.publisher.persistent_message_publisher import PersistentMessagePublisher
from solace.messaging.resources.topic import Topic

from solace_connection_config import load_service_properties

APP_NAME = "GuaranteedPublisherBlocking"
TOPIC_PREFIX = "solace/samples/"  # used as the topic "root"
API = "Python"
PAYLOAD_SIZE = 512
# POSITIVE acknowledgement timeout in MILLISECONDS: publish_await_acknowledgement() blocks
# up to this long for the broker's ACK or NACK, then raises PubSubTimeoutError. Never pass
# None: the API then waits for the ACK without a limit, so a lost ACK hangs the publisher
# and a shutdown signal never takes effect. The C API resends an unACKed message every
# 2000 ms (its publish ACK timer), so 5000 ms allows three attempts. Keep the value a little
# above a multiple of 2000 ms.
PUBLISH_ACK_TIMEOUT_MS = 5000
# how often the publish loop reports its message rate, in seconds
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
# terminate(grace_period): how long to wait for a message that the API has not yet handed
# to the transport (still in its publish buffer) before the publisher stops; terminate()
# raises IncompleteMessageDeliveryError when that message is still unsent after that. It
# does not wait for, or report, a sent message whose ACK is late: a blocking publish tracks
# its own ACK, and after an ACK timeout the message is normally already with the transport.
# A bounded grace period keeps shutdown time predictable, for example within the stop
# timeout of a process supervisor, so the disconnect still runs. The API default is
# 600000 ms (10 minutes). Adjust the value to suit the application: longer gives an unsent
# message more time to go out, shorter exits sooner.
TERMINATE_GRACE_PERIOD_MS = 10_000
# best practice for Guaranteed publishing (C API Best Practices): set a time-to-live so an
# unconsumed message does not sit on a queue forever. In MILLISECONDS; 0, the API default,
# never expires. The queue must have respect-ttl enabled (off by default), or the broker
# ignores the TTL. Adjust the value to how long a message stays useful to its consumers.
# For most applications, make it at least the longest time a consumer can take to come
# back (restart plus reconnect), or a multiple of that time for a safety margin, so a
# message does not expire while its consumer recovers; use a shorter or longer TTL only
# for a specific need.
MESSAGE_TTL_MS = 60_000

# API events go through standard logging; the trace() narration below is a separate channel
logger = logging.getLogger(APP_NAME)


@dataclass
class PublisherState:
    """What the publish loop, the signal handler, and the API listener threads share: the
    service, the publisher, the shutdown event, and the counters. The service and the
    publisher are required, so every PublisherState holds both (built, though not
    necessarily connected or started)."""
    messaging_service: MessagingService
    publisher: PersistentMessagePublisher
    shutdown: threading.Event
    connect_attempted: bool = False
    exit_code: int = 0  # set to 1 on a failure exit (service interruption, a publish failure, or a failed terminate())
    msg_sent_counter: int = 0  # num messages ACKed since the last rate report


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
        run_publish_loop(state)
    finally:
        teardown_solace(state)
        trace("Main thread quitting.")
    return state.exit_code  # non-zero after a failure, so scripts and supervisors see it


def setup_solace(properties: dict, shutdown: threading.Event) -> PublisherState:
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
    # PERSISTENT publisher with no back-pressure setting: publish_await_acknowledgement()
    # returns only after the broker's receipt, so the publish buffer never holds more than
    # the one message in flight, and the API default (on_back_pressure_elastic) cannot grow.
    # The non-blocking sample bounds the buffer with on_back_pressure_wait() because its
    # publish() returns before the receipt. Building the publisher needs only the built
    # service; start() is what requires the connection. One service can serve several
    # publishers.
    publisher = messaging_service.create_persistent_message_publisher_builder().build()
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
    # no publish receipt listener: each publish_await_acknowledgement() call returns the
    # broker's ACK as its normal return and the NACK as an exception, on the main thread


def run_publish_loop(state: PublisherState) -> None:
    # one OutboundMessageBuilder as a message "template", reused for every message: set the
    # headers that are the same on every message (here the TTL) once on the builder.
    # build() starts each message as a copy of the template, so there is no set call per
    # message for these headers
    message_builder = (
        state.messaging_service.message_builder()
        .with_property(message_properties.PERSISTENT_TIME_TO_LIVE, MESSAGE_TTL_MS)
    )

    trace(f"{API} {APP_NAME} connected, and running. Press Ctrl-C to quit.")
    trace(f"Publishing to topic '{TOPIC_PREFIX}{API.lower()}/pers/pub/...', "
          "please ensure queue has matching subscription.")
    # the rate report runs inline on this thread, not on a stats thread, so the main
    # thread stays the only application thread
    next_report = time.monotonic() + STATS_INTERVAL_S
    msg_count = 0
    while not state.shutdown.is_set():
        # each loop, change the payload as an example
        chosen_character = chr(msg_count % 26 + 65)  # choose a "random" letter [A-Z]
        # a bytearray payload travels as the binary attachment; consumers read it back with
        # get_payload_as_bytes() (a str payload would arrive via get_payload_as_string())
        payload = bytearray(chosen_character.encode("ascii") * PAYLOAD_SIZE)
        msg_id = str(uuid.uuid4())
        # the per-message difference goes in build()'s additional_message_properties, which
        # applies to this message only; a with_*() call on the builder would change the
        # template, so every later message would carry it too
        message = message_builder.build(
            payload, additional_message_properties={message_properties.APPLICATION_MESSAGE_ID: msg_id})
        # NOTE: publishing to topic, so make sure the consumer's queue is subscribed to the same topic,
        #       or enable "Reject Message to Sender on No Subscription Match" in the client-profile
        topic = Topic.of(f"{TOPIC_PREFIX}{API.lower()}/pers/pub/{chosen_character}")
        try:
            # blocks until the broker ACKs this message or PUBLISH_ACK_TIMEOUT_MS passes: a
            # normal return IS the ACK, so the message id needs no receipt correlation.
            # During an automatic reconnect the message waits for the transport and its ACK
            # comes after the reconnect. A shutdown signal that arrives during the wait
            # takes effect once the call returns, within PUBLISH_ACK_TIMEOUT_MS.
            state.publisher.publish_await_acknowledgement(message, topic, PUBLISH_ACK_TIMEOUT_MS)
            state.msg_sent_counter += 1
        # A timeout, a NACK, or an overflow is handled with the "Discard the Message" strategy
        # from the Python developer guide ("Strategies for Handling Publish Receipt Errors"):
        # log it and go on to the next message, so these are INFO, not errors. Do not use this
        # strategy if the application cannot tolerate message loss; the guide's other
        # strategies are to wait and retry, and to retry a predefined number of times.
        except PubSubTimeoutError:
            # no ACK or NACK inside the timeout. The broker can still have the message (the
            # ACK can be late, for example after a long reconnect), so a discarded message can
            # still be delivered
            logger.info("No ACK for Message ID %s within %d ms; discarding it", msg_id, PUBLISH_ACK_TIMEOUT_MS)
        except (MessageRejectedByBrokerError, MessageNotAcknowledgedByBrokerError,
                MessageDestinationDoesNotExistError) as error:
            # NACK: the broker did not persist the message, and the exception says why (for
            # example MessageDestinationDoesNotExistError when no queue subscribes to the
            # topic and the client-profile rejects on no subscription match)
            logger.info("NACK for Message ID %s; discarding it: %s", msg_id, error)
        except PublisherOverflowError as error:
            # the API reference lists it for this call; with one message in flight and the
            # default back-pressure setting, the publish buffer does not overflow
            logger.info("Publish buffer overflow for Message ID %s; discarding it: %s", msg_id, error)
        except PubSubPlusClientError as error:
            # the message cannot be sent (for example the service is down or the publisher
            # terminated) and retrying would not help
            logger.error("publish_await_acknowledgement() failed, quitting: %s", error)
            state.exit_code = 1
            break  # let's quit; or, could initiate a new connection attempt
        msg_count += 1
        # simple way of calculating message rates
        now = time.monotonic()
        if now >= next_report:
            trace(f"{API} {APP_NAME} Published msgs/s: {state.msg_sent_counter:,}")
            state.msg_sent_counter = 0
            next_report = now + STATS_INTERVAL_S
        # no delay between messages: the next message goes out as soon as the previous one is
        # ACKed. Note: STANDARD Edition Solace broker is limited to 10k msg/s max ingress
    state.shutdown.set()


def teardown_solace(state: PublisherState) -> None:
    # Application cleanup belongs here: once setup_solace() returns, teardown_solace() runs
    # in main's finally on EVERY exit path (normal quit, SIGINT/SIGTERM, a publish failure,
    # service interruption, or an exception), so Solace teardown and application-side
    # cleanup are never skipped.
    state.shutdown.set()
    if state.publisher.is_running():
        # after a normal quit, an ACK timeout, or a NACK, the publish buffer is empty, so
        # terminate() returns at once. It waits up to the grace period only for a message the
        # API has not yet handed to the transport, and raises IncompleteMessageDeliveryError
        # when that message is still unsent after the grace period; catch it so the disconnect
        # below still runs
        try:
            state.publisher.terminate(TERMINATE_GRACE_PERIOD_MS)
        except IncompleteMessageDeliveryError as error:
            logger.error("Publisher stopped with a message still unsent after %d ms: %s",
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
    """The three service event listeners in one class. Callbacks run on an API thread, even
    in this blocking sample: return promptly and do not block in them, since events and
    delivery stall while a callback runs (C API Best Practices)."""

    def __init__(self, state: PublisherState) -> None:
        self.state = state

    def on_reconnecting(self, event: ServiceEvent) -> None:
        # connection lost, automatic reconnect attempt in progress: the message in flight
        # waits for the transport, and publish_await_acknowledgement() keeps waiting for its
        # ACK up to PUBLISH_ACK_TIMEOUT_MS
        logger.warning("Service reconnecting; the message in flight waits for its ACK: %s (%s)",
                       event.get_message(), event.get_cause())

    def on_reconnected(self, event: ServiceEvent) -> None:
        # automatic reconnect succeeded: the waiting message goes out
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


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    sys.exit(main(sys.argv[1:]))
