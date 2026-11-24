# Implement: Guaranteed Pub/Sub leaves

The per-leaf wiring for the three Guaranteed Pub/Sub leaves of the Java API. Read this file from `implement-mode.md` Step 4 when the `Pattern` field is `Guaranteed Pub/Sub (single service, exclusive)`, `Guaranteed Pub/Sub (single service, non-exclusive)`, or `Guaranteed Pub/Sub (fan-out)`. The shared Step 4 rules in `implement-mode.md` (the AI-assisted disclaimer header, the shared `SolaceConnectionConfig` helper, the variant discipline, the demo-harness rule, the comments rule, the explicit-single-class-imports rule, and the Quickstart fail-fast rule) apply here unchanged.

## Guaranteed pub/sub flow

The skill ships two best-practices reference samples for this flow. Read them by their exact relative paths and adapt each into its own generated class. There is no merge and no third skeleton: the Publisher class is a near-verbatim adaptation of the publisher sample, and the Subscriber class is a near-verbatim adaptation of the subscriber sample.

- `java-messaging-guaranteed-publisher-sample.java`: basic-auth connect, PERSISTENT binary publish to a topic with the non-blocking `publish()`, publish receipt (ACK/NACK) handling in a `MessagePublishReceiptListener`, capacity-bounded back pressure, service event listeners registered before `connect()`, and a graceful SIGINT/SIGTERM shutdown that finishes outstanding ACKs.
- `java-messaging-guaranteed-subscriber-sample.java`: basic-auth connect, durable queue plus topic subscription provisioned at start, a CLIENT-ack receiver with `receiveAsync()` that ACKs only after processing, receiver state and termination handling, service event listeners registered before `connect()`, and a graceful SIGINT/SIGTERM shutdown.

This is the non-blocking shape, and it is the only shape this release generates. Do NOT read or adapt `java-messaging-guaranteed-publisher-blocking-sample.java` or `java-messaging-guaranteed-subscriber-blocking-sample.java`.

Each class builds its OWN `MessagingService` (two services, one per class, matching the samples). Keep the samples' structure: `setupSolace(...)`, the main loop, and `teardownSolace()` in `main`'s `finally`, with the shutdown hook that joins the main thread. Keep the three service event listeners, the publish receipt listener, the receiver state listener, and the receiver termination listener; they are part of the best-practices contract and must not be stripped during adaptation.

**Ground before you write.** Before writing either class, WebFetch [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md) and [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md) in this session (SKILL.md Invariant 2).

The three guaranteed pub/sub leaves generate the same Publisher and Subscriber. The exclusive versus non-exclusive choice is one queue-factory line (read the `Access type` summary field). The fan-out leaf reuses the single-service Subscriber once per consuming service (see Fan-out below).

## Subscriber class (near-verbatim `GuaranteedSubscriber`)

The Subscriber provisions the durable queue and the topic subscription, and it is the long-running process the developer runs FIRST. The Publisher does NOT provision anything. Start the file with the disclaimer header (the exact two lines `implement-mode.md` Step 4 defines), then wire the Subscriber in this sequence, each step grounded in the pages linked in `implement-mode.md`'s Grounding references:

1. Build the service `Properties` with `SolaceConnectionConfig.load(args, APP_NAME).toServiceProperties()`, then the service with `MessagingService.builder(ConfigurationProfile.V1).fromProperties(properties).build()` (on Quickstart without the two reconnect `putIfAbsent` lines, per the fail-fast rule). Register the three service event listeners BEFORE `connect()`, as the sample does.
2. `messagingService.connect()`. Immediately after `connect()` returns, emit the connect marker: `trace("VERIFY: CONNECTED");`.
3. Build the persistent receiver as the sample does: `createPersistentMessageReceiverBuilder()` with `withMissingResourcesCreationStrategy(MissingResourcesCreationStrategy.CREATE_ON_START)`, `withSubscriptions(TopicSubscription.of(topic))` for the design summary's topic, `withMessageClientAcknowledgement()`, and `withActivationPassivationSupport(...)`, then `build(queue)` with the queue from the design summary's `Consumption endpoint` queue name and `Access type` field (see the access-type snippet below). Keep the receiver termination listener. See [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md).
4. `receiver.start()` inside the sample's try/catch, then `receiver.receiveAsync(handler)`, in the order the sample has them. Keep the sample's catch branch around `start()`: a client that may not create endpoints fails there, and the error message tells the developer what to do. Immediately after `receiveAsync(...)` returns, emit the queue-bound marker: `trace("VERIFY: QUEUE_BOUND");`.
5. In the handler's `onMessage`, keep the sample's `processingLock` block, its shutdown check, and its two try/catch blocks, and call `receiver.ack(message)` only after processing. Immediately after `ack()` returns, inside the same `try`, emit the round-trip marker: `trace("VERIFY: MESSAGE_RECEIVED");`. Do not emit it from the `catch` of a failed `ack()`. See [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md).
6. KEEP the long-running wait loop, the shutdown hook, and `teardownSolace()` with `receiver.terminate(gracePeriod)` before `messagingService.disconnect()`. The Subscriber is the SIGINT target, and its shutdown line (`Shutdown signal received ...`) stays a `trace(...)` call.

**Access type (exclusive vs non-exclusive): one line, not a separate sample.** The `Access type` field picks the queue factory:

```java
Queue queue = Queue.durableExclusiveQueue(QUEUE_NAME);        // one active consumer / ordered failover
// Queue queue = Queue.durableNonExclusiveQueue(QUEUE_NAME);  // competing consumers share the load
```

When the `Access type` field reads exclusive, keep the `durableExclusiveQueue` line: only one bound receiver is active at a time, and the receiver state listener reports the passive standby. When it reads non-exclusive, use the `durableNonExclusiveQueue` line instead, so several Subscriber processes bound to the same queue share the messages; adapt the state listener's log text, which names an exclusive queue. Emit exactly one of the two lines. Ground the choice in [Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md).

**Fan-out.** On the fan-out leaf, every consuming service gets its own durable queue with the same topic subscription, so each service receives its own full copy of every event. Generate one Subscriber class per consuming service, each with its own queue name from the design summary and an exclusive durable queue. The first service's class is the `subscriber` execution (below); each further class gets its own execution with the id `subscriber-<service>`, where `<service>` is a short lowercase name (letters, digits, and hyphens). Hand back one run command per Subscriber in Step 5.

## Publisher class (near-verbatim `GuaranteedPublisher`)

The Publisher only connects and publishes; it does NOT provision the queue or the subscription. It must exit on its own after the broker acknowledges its messages, so the developer does not have to interrupt it. Start the file with the disclaimer header, then wire the Publisher in this sequence:

1. Build the service `Properties` and the service exactly as the Subscriber does (step 1 above), and register the three service event listeners BEFORE `connect()`.
2. `messagingService.connect()`. Immediately after `connect()` returns, emit the connect marker: `trace("VERIFY: CONNECTED");`.
3. Build the publisher as the sample does: `createPersistentMessagePublisherBuilder().onBackPressureWait(PUBLISH_BUFFER_CAPACITY).build()`, then `publisher.setMessagePublishReceiptListener(...)` BEFORE `publisher.start()`, as the sample orders them. In the receipt handler's ACK branch (`publishReceipt.getException() == null`), emit the publish-ACK marker: `trace("VERIFY: PUBLISH_ACKED");`. CRITICAL: the sample logs the ACK with `logger.debug`, which the default INFO-level logging never shows, so the marker would never appear. Emit it through `trace(...)`, not through the logger. In the NACK branch, keep the sample's warning and its Discard the Message comment, and also set `exitCode = 1`, so a rejected message makes the Publisher exit non-zero.
4. Publish PERSISTENT messages to the design summary's topic as the sample does: one reused `OutboundMessageBuilder`, `withApplicationMessageId(msgId)`, `build(payload)` with a `byte[]` payload, and `publisher.publish(message, Topic.of(topic), msgId)` with the message id as the user context. Keep the sample's two catch branches around `publish()`. See [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md).
5. EXIT on its own: replace the sample's continuous publish loop with the application's own messages, or with one message on a try-it run, then return from the publish step. `teardownSolace()` in `main`'s `finally` calls `publisher.terminate(gracePeriod)`, which waits for the broker's receipt of every published message, so the `VERIFY: PUBLISH_ACKED` marker prints before the JVM exits. Drop the stats thread with the loop, together with its `shutdown()` call in `teardownSolace()` and its imports. Keep the shutdown hook, so Ctrl-C during a back pressure wait still reaches teardown.

**Persisted-request variant (application correlation id).** When the `Consumption endpoint` field records an application correlation id (a persisted request that `design-mode.md` routed here), generate the request leg only. In step 4 the Publisher adds `.withProperty(MessageProperties.CORRELATION_ID, msgId)` to the builder chain (`com.solace.messaging.config.SolaceProperties.MessageProperties`), and the Subscriber stays unchanged. Do NOT generate an answer path: no reply topic, no reply subscription, no reply publish, and no `VERIFY:` marker beyond the four below. List the answer path under Developer-owned items in the checklist (`implement-mode.md` Step 6), and name it in the close-out as the developer's next step.

## The marker contract and the named exec executions

These four `VERIFY:` marker strings (`VERIFY: CONNECTED`, `VERIFY: PUBLISH_ACKED`, `VERIFY: QUEUE_BOUND`, `VERIFY: MESSAGE_RECEIVED`) are the same strings the JCSMP and the Python guaranteed pub/sub leaves emit, character for character. They are read PER PROCESS: the Subscriber output carries `QUEUE_BOUND` and `MESSAGE_RECEIVED`, the Publisher output carries `PUBLISH_ACKED`, and both print `CONNECTED`. The Java API has no verify script yet (`implement-mode.md` Step 5), but the markers are the contract that script will read, so never change one.

**Expose both mains through two named exec executions.** The generated pom MUST declare two `exec-maven-plugin` `<execution>` blocks, one with `<id>publisher</id>` and one with `<id>subscriber</id>`, each carrying its own `<configuration><mainClass>` set to that class's fully-qualified name. The run commands in Step 5 address them as `mvn exec:java@subscriber` and `mvn exec:java@publisher`, so the package and class names stay your discretion at generation time. Resolve the `exec-maven-plugin` version at generation time (no hardcoded version), the same way the `solace-messaging-client` coordinate is resolved.

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>RESOLVED_AT_GENERATION_TIME</version>
  <executions>
    <execution>
      <id>publisher</id>
      <configuration><mainClass>com.example.app.OrderPublisher</mainClass></configuration>
    </execution>
    <execution>
      <id>subscriber</id>
      <configuration><mainClass>com.example.app.OrderSubscriber</mainClass></configuration>
    </execution>
  </executions>
</plugin>
```
