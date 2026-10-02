# Implement: Guaranteed Pub/Sub leaves

The per-leaf wiring for the three Guaranteed Pub/Sub leaves of the Python API. Read this file from `implement-mode.md` Step 4 when the `Pattern` field is `Guaranteed Pub/Sub (single service, exclusive)`, `Guaranteed Pub/Sub (single service, non-exclusive)`, or `Guaranteed Pub/Sub (fan-out)`. The shared Step 4 rules in `implement-mode.md` (the disclaimer header, the shared connection helper, the fixed file names, the concurrency rules, the variant discipline, the demo-harness rule, the comments rule, and the Quickstart fail-fast rule) apply here unchanged.

## Guaranteed pub/sub flow

The skill ships two best-practices reference samples for this flow. Read them by their exact relative paths and adapt each into its own generated script. There is no merge and no third skeleton: `guaranteed_publisher.py` is a near-verbatim adaptation of the publisher sample, and `guaranteed_subscriber.py` is a near-verbatim adaptation of the subscriber sample.

- `python-guaranteed-publisher-sample.py`: basic-auth connect, persistent binary publish to a topic from a message template that sets the time-to-live once, publish receipt (ACK/NACK) handling, capacity-bounded back pressure, service event listeners registered before `connect()`, and a graceful SIGINT/SIGTERM shutdown that drains outstanding receipts.
- `python-guaranteed-subscriber-sample.py`: basic-auth connect, durable queue plus topic subscription provisioned at start, a client-ack receiver that ACKs only after processing, receiver state and termination handling, service event listeners registered before `connect()`, and a graceful SIGINT/SIGTERM shutdown.

Each script builds its OWN `MessagingService` (two services, one per script, matching the samples). Keep the samples' structure: the shared state dataclass, `setup_solace(...)` that builds the service and the receiver or publisher before any connection exists, `connect_solace(...)`, the main loop, and `teardown_solace(...)` in `main()`'s `finally`. Keep every listener class; they are part of the best-practices contract and must not be stripped during adaptation.

**Ground before you write.** Before writing either script, WebFetch [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md) and [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md) in this session (SKILL.md Invariant 2). The second page also grounds the `receive_async()` and acknowledgement rules in `implement-mode.md` Step 4's concurrency section.

The three guaranteed pub/sub leaves generate the same publisher and subscriber. The exclusive versus non-exclusive choice is one queue-factory line (read the `Access type` summary field). The fan-out leaf reuses the single-service subscriber once per consuming service (see Fan-out below).

## Subscriber script (`guaranteed_subscriber.py`)

The subscriber provisions the durable queue and the topic subscription, and it is the long-running process the developer runs FIRST. The publisher does NOT provision anything. Start the file with the disclaimer header, then wire the subscriber in this sequence, each step grounded in the pages linked in `implement-mode.md`'s Grounding references:

1. Build the connection properties with `load_service_properties(args, APP_NAME)`, then the service with `MessagingService.builder().from_properties(properties).build()` (on Quickstart without the reconnection strategy call, per the fail-fast rule).
2. Build the queue from the design summary's `Consumption endpoint` queue name and `Access type` field (see the access-type snippet below).
3. Build the persistent receiver as the sample does: `create_persistent_message_receiver_builder()` with `with_missing_resources_creation_strategy(MissingResourcesCreationStrategy.CREATE_ON_START)`, `with_subscriptions([TopicSubscription.of(topic)])` for the design summary's topic, `with_message_client_acknowledgement()`, and `with_activation_passivation_support(...)`, then `build(queue)`. Keep the receiver termination listener and register the three service event listeners BEFORE `connect()`. See [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md).
4. `messaging_service.connect()`. Immediately after `connect()` returns, emit the connect marker: `trace("VERIFY: CONNECTED")`.
5. `receiver.receive_async(handler)` BEFORE `receiver.start()`, as the sample orders them, then `receiver.start()`. Immediately after `start()` returns without raising, emit the queue-bound marker: `trace("VERIFY: QUEUE_BOUND")`. Keep the sample's `PubSubPlusClientError` branch around `start()`: a client that may not create endpoints fails there, and the error message tells the developer what to do.
6. In the handler's `on_message`, keep the sample's try/except/finally and call `receiver.ack(message)` only after processing. Immediately after `ack()` in the `finally`, emit the round-trip marker: `trace("VERIFY: MESSAGE_RECEIVED")`. See [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md).
7. KEEP the long-running wait loop, the SIGINT/SIGTERM handler that only sets the shutdown event, and `teardown_solace(...)` with `terminate(grace_period)` and `disconnect()`. The subscriber is the SIGINT target, and its shutdown line (`Shutdown signal received ...`) stays a `trace(...)` call.

**Access type (exclusive vs non-exclusive): one line, not a separate sample.** The `Access type` field picks the queue factory:

```python
queue = Queue.durable_exclusive_queue(queue_name)        # one active consumer / ordered failover
# queue = Queue.durable_non_exclusive_queue(queue_name)  # competing consumers share the load
```

When the `Access type` field reads exclusive, keep the `durable_exclusive_queue` line: only one bound receiver is active at a time, and the receiver state listener reports the passive standby. When it reads non-exclusive, use the `durable_non_exclusive_queue` line instead, so several subscriber processes bound to the same queue share the messages. Emit exactly one of the two lines. Ground the choice in [Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md).

**Fan-out.** On the fan-out leaf, every consuming service gets its own durable queue with the same topic subscription, so each service receives its own full copy of every event. Generate one subscriber script per consuming service: `guaranteed_subscriber.py` for the first service, and `guaranteed_subscriber_<service>.py` for each further service, where `<service>` is a lowercase Python identifier (letters, digits, and underscores, for example `billing` or `audit_log`) so the import smoke can import the module, each with its own queue name from the design summary and an exclusive durable queue. Hand back one run command per subscriber in Step 5, and add each script to the import smoke.

## Publisher script (`guaranteed_publisher.py`)

The publisher only connects and publishes; it does NOT provision the queue or the subscription. It must exit on its own after the broker acknowledges its messages, so the developer does not have to interrupt it. Start the file with the disclaimer header, then wire the publisher in this sequence:

1. Build the connection properties and the service exactly as the subscriber does (step 1 above), then the publisher with `create_persistent_message_publisher_builder().on_back_pressure_wait(PUBLISH_BUFFER_CAPACITY).build()`. Register the three service event listeners BEFORE `connect()`.
2. `messaging_service.connect()`. Immediately after `connect()` returns, emit the connect marker: `trace("VERIFY: CONNECTED")`.
3. `publisher.start()`, then `publisher.set_message_publish_receipt_listener(...)` AFTER `start()`, as the sample orders them. In the receipt handler's ACK branch, emit the publish-ACK marker: `trace("VERIFY: PUBLISH_ACKED")`. CRITICAL: the sample logs the ACK with `logger.debug`, which the INFO-level logging never shows, so the marker would never appear. Emit it through `trace(...)`, not through the logger. In the NACK branch, keep the sample's warning and also set the shared state's `exit_code` to 1, so a rejected message makes the publisher exit non-zero. Pass the shared state to the handler to do this.
4. Publish persistent messages to the design summary's topic as the sample does: one `message_builder()` template with `PERSISTENT_TIME_TO_LIVE` set once, `build(payload, additional_message_properties={APPLICATION_MESSAGE_ID: msg_id})` per message, and `publish(message, topic, user_context=msg_id)`. See [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md).
5. EXIT on its own: replace the sample's continuous publish loop with the application's own messages, or with one message on a try-it run, then return from the publish step. `teardown_solace(...)` in `main()`'s `finally` calls `terminate(grace_period)`, which sends what is buffered and waits for the broker's receipt of every published message, so the `VERIFY: PUBLISH_ACKED` marker prints before the script exits. Drop the stats thread with the loop. Keep the SIGINT/SIGTERM handler, so Ctrl-C during a back pressure wait still reaches teardown.

## The marker contract

These four `VERIFY:` marker strings (`VERIFY: CONNECTED`, `VERIFY: PUBLISH_ACKED`, `VERIFY: QUEUE_BOUND`, `VERIFY: MESSAGE_RECEIVED`) are the same strings the JCSMP guaranteed pub/sub leaf emits, character for character. They are read PER PROCESS: the subscriber output carries `QUEUE_BOUND` and `MESSAGE_RECEIVED`, the publisher output carries `PUBLISH_ACKED`, and both print `CONNECTED`. Until the Python verify script lands, the developer reads them from the two terminals (`implement-mode.md` Step 5); the verify script will read the same strings, so never change one.
