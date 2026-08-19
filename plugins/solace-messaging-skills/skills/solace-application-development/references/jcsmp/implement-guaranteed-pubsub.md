# Implement: Guaranteed Pub/Sub leaves

The per-leaf wiring for the three Guaranteed Pub/Sub leaves. Read this file from `implement-mode.md` Step 4 when the `Pattern` field is `Guaranteed Pub/Sub (single service, exclusive)`, `Guaranteed Pub/Sub (single service, non-exclusive)`, or `Guaranteed Pub/Sub (fan-out)`. The shared Step 4 rules in `implement-mode.md` (the AI-assisted disclaimer header, the shared `SolaceConnectionConfig` helper, the variant discipline, the demo-harness rule, and the explicit-single-class-imports rule) apply here unchanged.

## Guaranteed pub/sub flow

The skill ships two best-practices reference samples for this flow. Read them by their exact relative paths and adapt each into its own generated class. There is no merge and no third skeleton: the Publisher class is a near-verbatim adaptation of the publisher sample, and the Subscriber class is a near-verbatim adaptation of the consumer sample. Generating two near-verbatim classes is more reliable than synthesizing a single program from both, because the source shape is copied, not invented.

- `jcsmp-guaranteed-publisher-sample.java`: basic-auth connect, PERSISTENT BytesMessage publish to a topic, publish ACK/NACK handler, session-event and reconnect-event handling that pauses publishing while the transport is down, SIGINT graceful shutdown that finishes outstanding ACKs.
- `jcsmp-guaranteed-subscriber-sample.java`: basic-auth connect, in-process durable-queue provisioning plus topic subscription, CLIENT-ack flow that ACKs only after processing, session-event and flow-event handling.

Each class builds its OWN `JCSMPSession` (two sessions, one per class, matching the reference samples; there is no shared-session plumbing). Preserve the session-event and reconnect-event and publish ACK/NACK handlers in the Publisher, and the session-event and flow-event handlers in the Subscriber; they are part of the best-practices contract and must not be stripped during adaptation.

The three guaranteed pub/sub leaves all generate the same two-class Publisher and Subscriber described below. The exclusive versus non-exclusive choice is realized inside the flow as a `setAccessType` snippet (read the `Access type` summary field), not a separate sample; the fan-out leaf reuses the single-service generator, repeating the queue-and-subscription setup once per consuming service.

## Subscriber class (near-verbatim `GuaranteedSubscriber`)

The Subscriber provisions the durable queue and the topic subscription, and it is the long-running process the developer runs FIRST. The Publisher does NOT provision anything. Start the file with the disclaimer header (the exact two lines `implement-mode.md` Step 4 defines), then wire the Subscriber in this canonical sequence, each step grounded in the docs linked in `implement-mode.md`'s Grounding references:

1. Build basic-auth `JCSMPProperties` (`host`, `vpn_name`, `username`, `password`) via the shared `SolaceConnectionConfig` helper (`config.json` if present, else CLI args). Set `JCSMPProperties.IGNORE_DUPLICATE_SUBSCRIPTION_ERROR` to `true` BEFORE `createSession` (it defaults to false); otherwise `addSubscription` throws Subscription Already Exists on every run after the first (sample line 87).
2. `JCSMPFactory.onlyInstance().createSession(...)`, passing the consumer sample's `SessionEventHandler`, then `session.connect()`. Immediately after `connect()` returns, emit the connect marker: `trace("VERIFY: CONNECTED");`.
3. Best practice: check `session.isCapable(CapabilityType.ENDPOINT_MANAGEMENT)` before provisioning; exit cleanly if the broker disallows client-side endpoint management (sample lines 120-125).
4. `JCSMPFactory.onlyInstance().createQueue(queueName)` for the queue from the design summary.
5. Build the `EndpointProperties` and set the queue access type from the `Access type` summary field (see the access-type snippet below), then `session.provision(queue, endpointProps, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS)` so a re-run is idempotent. See [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md).
6. `session.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM)` to map the topic onto the queue. See [Adding a Topic Subscription](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Adding-Topic-Subscriptio.md).
7. Build a `ConsumerFlowProperties` flow with `setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT)`, create it with the consumer sample's `FlowEventHandler`, and `flow.start()`. Immediately after `flow.start()` returns, emit the queue-bound marker: `trace("VERIFY: QUEUE_BOUND");`. See [Acknowledging Messages](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Acknowledging-Messages.md).
8. In the flow's `onReceive`, call `msg.ackMessage()` only after processing is complete; immediately after `ackMessage()`, emit the round-trip marker: `trace("VERIFY: MESSAGE_RECEIVED");`.
9. KEEP the long-running loop and the SIGINT graceful-shutdown hook from the consumer sample (sample lines 179-207). The Subscriber is the SIGINT target: on SIGINT it stops the flow, finishes outstanding ACKs, and calls `session.closeSession()`.

**Access type (exclusive vs non-exclusive): a `setAccessType` snippet, not a separate sample.** The `Access type` field in the design summary picks one line. Set the queue access type on the `EndpointProperties` before provisioning (item 5 above):

```java
EndpointProperties endpointProps = new EndpointProperties();
endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);      // one active consumer / ordered failover
// endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_NONEXCLUSIVE); // competing consumers / round-robin load balance
endpointProps.setPermission(EndpointProperties.PERMISSION_CONSUME);
```

When the `Access type` field reads exclusive, keep the `ACCESSTYPE_EXCLUSIVE` line: only one active consumer receives at a time, and on its disconnect a standby flow takes over in order (ordered failover). When it reads non-exclusive, use the `ACCESSTYPE_NONEXCLUSIVE` line instead: bound flows compete and receive in round-robin, so the load is shared across workers. Read the `Access type` field and choose the one line; do not emit both active. This is grounded in [Receiving Guaranteed Messages](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Receiving-Guaranteed-Messages.md), which states an exclusive queue delivers in order with one active consumer and failover to the next flow, and a non-exclusive queue delivers round-robin across bound flows.

## Publisher class (near-verbatim `GuaranteedPublisher`)

The Publisher only connects and publishes; it does NOT provision the queue or the subscription (the Subscriber owns that, and starts first). It must exit on its own after the ack so the developer does not have to interrupt it. Start the file with the disclaimer header (the exact two lines `implement-mode.md` Step 4 defines), then wire the Publisher in this canonical sequence:

1. Build basic-auth `JCSMPProperties` (`host`, `vpn_name`, `username`, `password`) via the shared `SolaceConnectionConfig` helper (`config.json` if present, else CLI args).
2. `JCSMPFactory.onlyInstance().createSession(...)`, passing the publisher sample's `SessionEventHandler`, then `session.connect()`. Immediately after `connect()` returns, emit the connect marker: `trace("VERIFY: CONNECTED");`.
3. Acquire the `XMLMessageProducer` with the publisher sample's publish ACK/NACK callback handler (`PublishCallbackHandler`), then register the publisher sample's `JCSMPReconnectEventHandler` through an empty never-started consumer (`session.getMessageConsumer(reconnectHandler, null)`). The docs ground this idiom for publish-only applications; keep it because it surfaces transport events that pause the send loop. In the ACK callback (`responseReceivedEx`), emit the publish-ACK marker: `trace("VERIFY: PUBLISH_ACKED");`. CRITICAL: the reference publisher sample logs the ACK at `logger.debug` (sample line 229), which is invisible in default output, so the verify script would never see it and would report a false code failure. The marker MUST reach stdout: emit it via `trace(...)`, whose generated body prints with `System.out.println`, NOT via `logger.debug`. This is the single highest silent-failure risk in the whole verify flow.
4. Publish a PERSISTENT `BytesMessage` to the topic (`DeliveryMode.PERSISTENT`). See [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md).
5. EXIT on its own: after observing the ACK (the `VERIFY: PUBLISH_ACKED` marker), drain outstanding ACKs (the sample's `Thread.sleep(1500)` idiom at sample line 216), call `session.closeSession()`, and return from `main`. REMOVE the sample's ENTER/SIGINT loop (sample line 168): the Publisher must terminate by itself, not wait for a key press or a signal. The SIGINT shutdown hook belongs to the Subscriber, not the Publisher.

## The marker contract and the named exec executions

These four `VERIFY:` marker strings (`VERIFY: CONNECTED`, `VERIFY: PUBLISH_ACKED`, `VERIFY: QUEUE_BOUND`, `VERIFY: MESSAGE_RECEIVED`) are a fixed contract with `scripts/verify.sh`, which greps captured output PER PROCESS (the Subscriber log for `QUEUE_BOUND` and `MESSAGE_RECEIVED`, the Publisher log for `PUBLISH_ACKED`). Both classes connect, so both print `VERIFY: CONNECTED`; the script reads markers per-process for that reason. The strings here and the strings the script greps for MUST stay in sync character-for-character; if you change one marker string, change it in `scripts/verify.sh` in the same effort.

**Expose both mains to the verify script via two named exec executions.** The fixed `scripts/verify.sh` runs each main without knowing the per-generation package, so the generated pom MUST declare two `exec-maven-plugin` `<execution>` blocks, one with `<id>publisher</id>` and one with `<id>subscriber</id>`, each carrying its own `<configuration><mainClass>` set to that class's fully-qualified name. The script then addresses them as `mvn exec:java@publisher` and `mvn exec:java@subscriber`. The script references the fixed execution ids, never the generated FQCN, so the package and class names stay your discretion at generation time. Keep the `exec-maven-plugin` version resolved at generation time (no hardcoded version), the same way the `sol-jcsmp` coordinate is resolved.

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
