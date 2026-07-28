# Design Mode

Help the developer choose the right Solace JCSMP messaging pattern and topology before any code is generated, grounded in canonical Solace docs. Work through the steps in order. Read this file's grounding links only as each step needs them; link the canonical doc and state the decision in one line, never paraphrase doc content.

The output of this mode is a filled-in design summary the developer can carry into Implement mode.

## Step 0: Confirm broker access and grounding

Before triaging anything, confirm two things (skip whichever the developer already has):

- The developer has access to a reachable broker. Confirm that they HAVE one; Design mode only records the broker TYPE (for example, Solace Cloud) in the summary, so do NOT ask them to paste connection details (host, message VPN, client username, password) into the chat. Those values are handled later in Implement mode, where Solace Suggested credentials go only into a gitignored `config.json` (Quickstart falls back to CLI args when it is absent).
- The developer understands the basic Solace model (broker, topics, queues, publish/subscribe).

If either is missing, route through `prerequisites.md` first (Core Concepts grounding and broker acquisition, Solace Cloud recommended), then return here.

## Step 1: Read the prompt for stated intent before asking anything

Design mode is ask-or-recommend, not interrogate-first. Before walking the tree, read what the developer already told you and try to resolve a pattern up front. Match the prompt against this signal table first; it is the deterministic path.

| Stated intent in the prompt | Recommended leaf (or branch to lock if partial) |
|---|---|
| "request a reply", "needs a correlated response", "ask and wait for an answer" | Request-Reply (then ask delivery to pick the leaf) |
| "fan out to many services", "broadcast to several independent apps", "every team gets its own copy" | Guaranteed Pub/Sub (fan-out) |
| "competing workers", "share the load across consumers", "scale out processing of one stream" | Guaranteed Pub/Sub (single service, non-exclusive) |
| "one active consumer", "strict ordering", "only one worker at a time" | Guaranteed Pub/Sub (single service, exclusive) |
| "fire-and-forget telemetry", "high-rate metrics, occasional loss is fine", "live ticks, no persistence" | Direct Pub/Sub |
| "must not lose messages", "survive consumer downtime", "persistent delivery" | a Guaranteed leaf (let the remaining questions pick which) |

If a row matches, or if your own judgment maps an unmatched prompt cleanly to one of the six leaves, take the recommend path. Do not default to a full interrogation just because the prompt is not a verbatim table row; fall back to your own judgment to pick the closest leaf, then confirm.

**Recommend path (intent is clear).** Recommend the leaf in ONE line and confirm, for example "Sounds like Guaranteed Pub/Sub (fan-out), confirm?", then proceed on the developer's confirm. One line plus one confirm, not a rationale paragraph and not a silent zero-confirm jump.

**Partial-intent path (intent is partial).** When the prompt answers some branches but not all (for example it says "request a reply" but not direct vs guaranteed), lock the branches the prompt already answers and ask ONLY the remaining tree questions, in the Step 2 order, skipping every question the prompt already settled. Reach a leaf, then confirm it the same one-line way.

**Unclear path (no stated intent).** When the prompt states no usable intent, walk all four tree questions in Step 2 order to reach a leaf.

**Variant of a leaf (the prompt states a requirement the base shape does not meet).** A leaf is a pattern FAMILY, not a fixed topology. Alongside the intent match, read the prompt for reliability or topology requirements that the chosen leaf's DEFAULT shape does not satisfy (for example "the reply must survive the requestor restarting", "many requests are in flight at once", "the consumer must dedupe redeliveries"). When you find one, the leaf still resolves to one of the six strings verbatim, but the design is a VARIANT of that leaf: its default sample shape is adapted to meet the requirement. Do not discard the requirement to fit the default shape, and do not treat a legitimate variant as off-catalog. Name the deviation in the same one-line confirm (for example "Sounds like Request-Reply (Guaranteed), with a durable reply queue instead of the default temporary reply queue, because your reply must survive a requestor restart, confirm?"), and carry the actual topology into the Step 4 summary. Keep a variant within the same pattern family and to topology or reliability knobs the canonical docs ground; if a requirement leaves the JCSMP topic/queue publish and subscribe model, or needs behavior the docs cannot ground, say so and treat it as off-catalog rather than inventing it.

## Step 2: Walk the decision tree to a leaf

Ask only the questions the prompt left open, in this order. Read each grounding link only when that question comes up; link the canonical doc and state the decision in one line, never paraphrase the doc.

1. **Interaction shape: request-reply or publish/subscribe?** Grounded in [Message Exchange Patterns](https://docs.solace.com/Get-Started/message-exchange-patterns.md). Decision: a request that expects a correlated reply is Request-Reply; an event fanned out to one or many independent consumers is publish/subscribe.
2. **Delivery guarantee: direct or guaranteed?** This ONE question applies to both interaction shapes, so ask it next regardless of the answer to question 1. Grounded in [Message Delivery Modes](https://docs.solace.com/Get-Started/message-delivery-modes.md). Decision: direct is fire-and-forget with no broker-side persistence, for high-rate flows that tolerate occasional loss; guaranteed (PERSISTENT) is persisted and survives consumer downtime.
3. **Number of consuming services (guaranteed publish/subscribe branch only).** Decision: a single consuming service is one logical consumer of the stream; fan-out is several independent services that each need their own full copy of every event.
4. **Competing consumers (guaranteed publish/subscribe, single service branch only).** Grounded in [Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md). Decision: exclusive gives one active consumer at a time (strict ordering, hot standby); non-exclusive lets multiple competing workers share the queue and split the load.

Questions 3 and 4 apply ONLY on the guaranteed publish/subscribe branch. Request-reply and direct publish/subscribe resolve at the delivery-guarantee answer and never reach them.

## Step 3: Resolve to one of the six leaves

The tree resolves to exactly six leaves. Spell the chosen leaf in the summary exactly as written here; these strings are the contract Implement mode reads.

- **Request-Reply (Direct)** GENERATED. Request-reply over direct messaging.
- **Request-Reply (Guaranteed)** GENERATED. Request-reply over guaranteed messaging.
- **Direct Pub/Sub** GENERATED. Publish/subscribe over direct messaging, fire-and-forget.
- **Guaranteed Pub/Sub (single service, exclusive)** GENERATED. One durable queue, exclusive access, one active consumer at a time.
- **Guaranteed Pub/Sub (single service, non-exclusive)** GENERATED. One durable queue, non-exclusive access, competing workers share the load.
- **Guaranteed Pub/Sub (fan-out)** SNIPPET. One durable queue plus a topic subscription per independent consuming service. Implement mode reuses the single-service guaranteed generator, so there is no dedicated fan-out sample; the leaf is realized by repeating the single-service queue-and-subscription setup once per consuming service.

Inside the guaranteed publish/subscribe single-service leaf, the exclusive versus non-exclusive choice is realized as a `setAccessType` SNIPPET, not a separate sample: the same generated app sets the queue access type to exclusive or non-exclusive based on the developer's answer to question 4.

**A leaf can carry a variant.** The six strings name the pattern family; a design may adapt a leaf's default topology to a stated requirement (the variant path in Step 1). In every case the `Pattern` string stays one of the six verbatim, and the deviation is recorded in the topology fields of the Step 4 summary (for example the `Consumption endpoint`), never by inventing a seventh leaf string. Keep the variant within the same pattern family and grounded in the canonical docs; anything beyond that is off-catalog, and you say so rather than inventing it.

**Topic hierarchy and queue name.** Always publish to topics; never address a queue directly. Do NOT ask the developer to choose or design the topic string. Instead, apply the co-installed solace-topic-best-practices skill (which reads the canonical Topic Architecture Best Practices page live) to the developer's use case, derive the recommended topic hierarchy, and recommend it in ONE line; use that recommended topic without making the developer pick it. Do not design the hierarchy here by hand and do not restate that skill's guidance; the recommended topic string is recorded in the summary, but the hierarchy reasoning belongs to that skill. Likewise, for any leaf that consumes from a durable queue, derive the queue name automatically rather than asking: follow the `q.`-prefixed dotted convention the reference samples use (for example the topic `acme/orders/new` maps to the queue `q.acme.orders`), recommend it in one line, and record it. The developer overrides the recommended topic or queue name ONLY if they explicitly ask to; the default is to use the recommendation and move on.

**Traps to avoid:**

- **Publishing direct-to-queue instead of pub/sub.** Publish to a topic; let the durable queue carry a topic subscription. ([Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md).)
- **XML or text payload instead of binary.** Default to a binary payload. The `XML` in JCSMP type names like `XMLMessage` is legacy API naming, not an XML payload format. Do not conflate them.
- **Direct where guaranteed is required.** Use PERSISTENT delivery whenever the design cannot tolerate message loss or must survive consumer downtime. ([Message Delivery Modes](https://docs.solace.com/Get-Started/message-delivery-modes.md).)
- **A topic endpoint instead of a queue-with-topic-subscription.** Promote the durable queue; topic endpoints are largely JMS-durable-subscriber constructs. ([Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md).)

## Step 4: Emit the design summary

Fill in this ONE unified design summary every run, in chat, with the confirmed values. The same eight fields appear every run regardless of which leaf the tree reached, so Implement mode reads one stable structure. Use explicit `n/a` for any field the chosen pattern does not apply.

```
Solace JCSMP Design Summary
- Pattern:             <one of the six leaf strings, verbatim>
- Delivery:            <Direct | Guaranteed (PERSISTENT)>
- Access type:         <Exclusive | Non-exclusive | n/a>
- Topic:               <recommended topic string / hierarchy (derived, not interrogated)>
- Consumption endpoint: <direct topic subscription | durable queue + topic subscription | temporary reply queue + FlowReceiver | n/a>
- Auth:                Basic username/password
- Broker:              Solace Cloud
- Grounding docs:      <the canonical pages this design referenced>
```

The eight fields are fixed: Pattern, Delivery, Access type, Topic, Consumption endpoint, Auth, Broker, Grounding docs. Formatting is at your discretion, but always present all eight, every run, with explicit `n/a` where a field does not apply. The eight field NAMES are fixed; the field VALUES record the design's actual choices, defaulting to the base-leaf shape and holding the variant topology when the design deviates (the variant path in Step 1). The `Pattern` value stays one of the six leaf strings verbatim even for a variant; the deviation lives in the topology fields (`Consumption endpoint`, and `Delivery` or `Access type` where relevant), never in a new leaf string.

Field semantics:

- **Pattern.** Exactly one of the six leaf strings, spelled verbatim: `Request-Reply (Direct)`, `Request-Reply (Guaranteed)`, `Direct Pub/Sub`, `Guaranteed Pub/Sub (single service, exclusive)`, `Guaranteed Pub/Sub (single service, non-exclusive)`, `Guaranteed Pub/Sub (fan-out)`. This is the contract Implement mode reads, so the spelling is not cosmetic.
- **Delivery.** `Direct` for the two direct-delivery leaves (Request-Reply (Direct) and Direct Pub/Sub); `Guaranteed (PERSISTENT)` for the four guaranteed leaves.
- **Access type.** `Exclusive` or `Non-exclusive` for the two guaranteed single-service leaves; `n/a` for every other leaf (Direct Pub/Sub, both request-reply leaves, and fan-out). Surface it explicitly even though it is also encoded in the Pattern string.
- **Consumption endpoint.** The pattern-specific place the consuming side reads from. Record the ACTUAL topology the design requires; the values below are the DEFAULT base-leaf shapes, not a closed list. The defaults are `direct topic subscription` for Direct Pub/Sub, `durable queue + topic subscription` for the three guaranteed pub/sub leaves, `temporary reply queue + FlowReceiver` for both request-reply leaves (the requestor's reply reception), `n/a` where none applies. For the durable-queue leaves, include the auto-derived queue name in this field (for example `durable queue q.acme.orders + topic subscription`) so Implement mode reads the concrete queue name here. When the design is a variant of the leaf (Step 1), record the variant topology here instead of the default, with a short inline reason (for example `durable reply queue + FlowReceiver (reply must survive a requestor restart)`). Implement mode reads this field as the topology to build.
- **Topic.** The recommended topic string or hierarchy, derived by applying the solace-topic-best-practices skill to the use case (not chosen by interrogating the developer). Always publish to topics; never address a queue directly.
- **Auth.** Basic username/password.
- **Broker.** Default to `Solace Cloud` and record it without asking. Switch to another type (Software Broker or Appliance) ONLY if the developer explicitly insists; otherwise leave it as `Solace Cloud`.
- **Grounding docs.** The canonical pages this design referenced.

After presenting the summary in chat, close Design mode explicitly so the developer knows exactly what happens next; do NOT just display the summary and stop. If the design is a variant of its leaf, state the deviation and its rationale in one line here too, so the developer approves the actual topology (not just the leaf name) before it is built. Ask them directly, in one step, both whether they are happy with this design AND whether to save it to `solace-design.md` in their project (for example: "Happy with this design? If so, I can save it to `solace-design.md` and move into Implement mode to generate the runnable Maven project."). Write the file only on their OK; never write it unprompted. Once they confirm, state the next step plainly: the work moves into Implement mode, which generates the runnable Maven project from this summary. Implement mode treats this summary, whether it lives in the chat or in the saved `solace-design.md`, as its input contract.
