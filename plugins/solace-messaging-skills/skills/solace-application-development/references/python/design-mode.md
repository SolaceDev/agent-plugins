# Design Mode

Help the developer choose the right Solace messaging pattern and topology for a Python application before any code is generated, grounded in canonical Solace docs. Work through the steps in order. Read this file's grounding links only as each step needs them; link the canonical doc and state the decision in one line, never paraphrase doc content.

The output of this mode is a filled-in design summary the developer can carry into Implement mode.

## Step 0: Confirm broker access and grounding

Before triaging anything, confirm the developer has access to a reachable broker. Confirm that they HAVE one; Design mode only records the broker TYPE (for example, Solace Cloud) in the summary, so do NOT ask them to paste connection details (host, message VPN, client username, password) into the chat. Those values are handled later in Implement mode, where they go into a gitignored `config.json` or, on Quickstart, the command line. A broker discovered running in the environment (a local container, an existing config file) is a fact to report, never an answer: still confirm which broker the developer wants to target.

If the developer has no reachable broker, route through `prerequisites.md` first (broker acquisition, Solace Cloud recommended), then return here.

## Step 1: Read the prompt for stated intent before asking anything

Design mode is ask-or-recommend, not interrogate-first. Before walking the tree, read what the developer already told you and try to resolve a pattern up front. Match the prompt against this signal table first; it is the deterministic path.

| Stated intent in the prompt | Recommended leaf (or branch to lock if partial) |
|---|---|
| "request a reply", "needs a correlated response", "ask and wait for an answer" | Request-Reply (Direct) (the delivery question still applies; see the no-guaranteed-request-reply rule below) |
| "the request must not be lost", "the request must survive the responder being down", "persistent request" | a Guaranteed Pub/Sub leaf with an application correlation id (the no-guaranteed-request-reply rule below; never Request-Reply (Direct)) |
| "fan out to many services", "broadcast to several independent apps", "every team gets its own copy" | Guaranteed Pub/Sub (fan-out) |
| "competing workers", "share the load across consumers", "scale out processing of one stream" | Guaranteed Pub/Sub (single service, non-exclusive) |
| "one active consumer", "strict ordering", "only one worker at a time" | Guaranteed Pub/Sub (single service, exclusive) |
| "fire-and-forget telemetry", "high-rate metrics, occasional loss is fine", "live ticks, no persistence" | Direct Pub/Sub |
| "must not lose messages", "survive consumer downtime", "persistent delivery" | a Guaranteed leaf (let the remaining questions pick which) |
| "keep it single-threaded", "no callbacks on the data path", "minimal threading", "poll for messages", "pull each message and ack it myself", "one thread of control" | lock the `Threading` field to `Minimal (blocking publish and receive)` (the pattern questions still apply; Request-Reply (Direct) records `n/a (the requestor blocks on each reply; the replier answers from a receive_async() callback)` instead, so the developer sees that the leaf meets the preference on the requestor side only). A prompt that states callbacks, handler threads, or a multi-threaded app locks it to `Async` the same way. |

If a row matches, or if your own judgment maps an unmatched prompt cleanly to one of the five leaves, take the recommend path. Do not default to a full interrogation just because the prompt is not a verbatim table row; fall back to your own judgment to pick the closest leaf, then confirm.

**Recommend path (intent is clear).** Recommend the leaf in ONE line and confirm, for example "Sounds like Guaranteed Pub/Sub (fan-out), confirm?", then proceed on the developer's confirm. One line plus one confirm, not a rationale paragraph and not a silent zero-confirm jump.

**Threading is never inferred.** Question 3 (Step 2) applies to every Direct Pub/Sub and guaranteed publish/subscribe design. Only an explicit statement in the prompt (the threading row above) answers it; a use case, a rate, or a resolved leaf does not. A prompt that settles everything except threading is on the partial-intent path, so ask question 3 even when the leaf is clear: ask it in ONE message that also names the resolved leaf in one line, and take the developer's answer as the leaf confirm too, then present the summary (Step 4). The one exception is a request to skip all questions (the design-contract gate in `python.md`): record `Threading: Async` and say in the summary that it is the default.

**Fully-specified path (the prompt answers every applicable tree question).** When the prompt resolves the leaf AND answers every Step 2 question that applies to that leaf, including the threading question, skip the separate leaf confirm. Derive the full summary, present it (Step 4), and let the close-out question carry the single confirm: one confirm covers the leaf and the design. Do not ask a leaf confirm and then a close-out confirm; that is two questions where the prompt earned one.

**Partial-intent path (intent is partial).** When the prompt answers some branches but not all, lock the branches the prompt already answers and ask ONLY the remaining tree questions, in the Step 2 order, skipping every question the prompt already settled. Reach a leaf, then confirm it the same one-line way.

**Unclear path (no stated intent).** When the prompt states no usable intent, walk all five tree questions in Step 2 order to reach a leaf.

**Variant of a leaf (the prompt states a requirement the base shape does not meet).** A leaf is a pattern FAMILY, not a fixed topology. Alongside the intent match, read the prompt for reliability or topology requirements that the chosen leaf's DEFAULT shape does not satisfy (for example "the consumer must dedupe redeliveries"). When you find one, the leaf still resolves to one of the five strings verbatim, but the design is a VARIANT of that leaf: its default sample shape is adapted to meet the requirement. Name the deviation in the same one-line confirm and carry the actual topology into the Step 4 summary. Keep a variant within the same pattern family and to topology or reliability knobs the canonical docs ground; if a requirement leaves the Solace topic and queue publish and subscribe model, or needs behavior the docs cannot ground, say so and treat it as off-catalog rather than inventing it.

## Step 2: Walk the decision tree to a leaf

Ask only the questions the prompt left open, in this order. Before you answer each question you do walk, WebFetch its grounding page first (even when the prompt pre-answers the question), then link the canonical doc and state the decision in one line, quoting one line from the fetched page; never paraphrase guidance the page does not contain. A page you did not fetch in this session must not appear in the summary's Grounding docs field.

1. **Interaction shape: request-reply or publish/subscribe?** Grounded in [Message Exchange Patterns](https://docs.solace.com/Get-Started/message-exchange-patterns.md). Decision: a request that expects a correlated reply is Request-Reply; an event fanned out to one or many independent consumers is publish/subscribe.
2. **Delivery guarantee: direct or guaranteed?** This ONE question applies to both interaction shapes, so ask it next regardless of the answer to question 1. Grounded in [Message Delivery Modes](https://docs.solace.com/Get-Started/message-delivery-modes.md). Decision: direct is fire-and-forget with no broker-side persistence, for high-rate flows that tolerate occasional loss; guaranteed (persistent) is persisted and survives consumer downtime.
3. **Threading on the messaging data path: multi-threaded, or minimal threading? (Direct Pub/Sub and guaranteed publish/subscribe branches only.)** Grounded in [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md) on the guaranteed branch and in [Consuming Direct Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Receive.md) on the direct branch; both pages state that `receive_message(timeout)` blocks the routine until a message arrives, the timeout passes, or a service interruption occurs, and the first states that `receive_async()` invokes the callback on a new Python thread for every message. Frame the choice as WHERE the application's thread of control sits on the messaging data path, never as speed: make no throughput claim for either answer. Name the two answers with the labels written here, multi-threaded and minimal threading, whatever words the framing adds: the developer's reply maps onto those labels. The reason the question exists, stated in one line when you ask it: in Python the GIL lets only one thread run Python code at a time, so the extra API threads of the callback shape buy less than they do in other languages, and a developer who wants one thread of control can have it. Three answers:
   - **Multi-threaded app** (the push model): the API pushes each received message, and on the guaranteed branch each publish receipt, to an application callback on an API thread, so the application manages the concurrency between its own threads and the API's threads. This is the async shape and the current default. Record `Threading: Async`.
   - **Minimal threading preferred** (the pull model): the application's own thread pulls each message with a blocking `receive_message(timeout)` call, and on the guaranteed branch publishes each message with a blocking `publish_await_acknowledgement()` call, so no application callback runs on the data path and no application state is shared with an API thread; only the service event listeners still run on API threads. The cost is one message in flight at a time on each side. This is the blocking shape. Record `Threading: Minimal (blocking publish and receive)`.
   - **Unsure**: the async shape. Record `Threading: Async`.

   A prompt that already states either answer (the threading row in the Step 1 table) skips this question. Otherwise ALWAYS ask it, with the one skip-all-questions exception in Step 1: never infer the answer from the use case and never record `Async` without asking. Request-Reply (Direct) never reaches it: its base shape already blocks on the reply, and its summary records `Threading: n/a`. When the prompt stated a minimal-threading preference, the line reads `Threading: n/a (the requestor blocks on each reply; the replier answers from a receive_async() callback)`, so the developer sees that the leaf does not meet the preference on the replier side; a blocking replier is not a shape this skill generates.
4. **Number of consuming services (guaranteed publish/subscribe branch only).** Decision: a single consuming service is one logical consumer of the stream; fan-out is several independent services that each need their own full copy of every event.
5. **Competing consumers (guaranteed publish/subscribe, single service branch only).** Grounded in [Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md). Decision: exclusive gives one active consumer at a time (strict ordering, hot standby); non-exclusive lets multiple competing workers share the queue and split the load.

Question 3 applies on the Direct Pub/Sub and the guaranteed publish/subscribe branches. Questions 4 and 5 apply ONLY on the guaranteed publish/subscribe branch. Request-reply resolves at the delivery-guarantee answer and never reaches questions 3 to 5; direct publish/subscribe resolves after question 3.

**No guaranteed request-reply in the Python API.** When question 1 resolves to Request-Reply and question 2 resolves to guaranteed (the request must be persisted), there is no Python request-reply leaf for it. WebFetch [Request-Reply Messaging in the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Request-Reply.md) and quote its note that request-reply messaging can only be used with direct messages in the Python API. Then route to Guaranteed Pub/Sub, never to Request-Reply (Direct): the request travels as a persistent message to a durable queue, and the application sets a correlation id on it and matches the answer by that id. Continue the tree at question 3 (the threading question, then the two consumer questions) to pick the Guaranteed Pub/Sub leaf, then record the design as a VARIANT of that leaf (Step 1): name the application correlation id in the `Consumption endpoint` field with a short inline reason, for example `durable queue q.acme.orders + topic subscription; application correlation id (persisted request, no Python guaranteed request-reply)`. Tell the developer that Implement generates the request leg only: the answer path (how the consumer returns the answer and how the requestor matches it by the correlation id) is theirs to build, and the checklist records it as developer-owned. Never offer Request-Reply (Direct) for a request that must be persisted, never invent a guaranteed request-reply leaf, and never record `Request-Reply (Guaranteed)` in a Python summary.

## Step 3: Resolve to one of the five leaves

The tree resolves to exactly five leaves. Spell the chosen leaf in the summary exactly as written here; these strings are the contract Implement mode reads.

- **Request-Reply (Direct)** GENERATED. Request-reply over direct messaging.
- **Direct Pub/Sub** GENERATED. Publish/subscribe over direct messaging, fire-and-forget.
- **Guaranteed Pub/Sub (single service, exclusive)** GENERATED. One durable queue, exclusive access, one active consumer at a time.
- **Guaranteed Pub/Sub (single service, non-exclusive)** GENERATED. One durable queue, non-exclusive access, competing workers share the load.
- **Guaranteed Pub/Sub (fan-out)** SNIPPET. One durable queue plus a topic subscription per independent consuming service. Implement mode reuses the single-service guaranteed generator, so there is no dedicated fan-out sample; the leaf is realized by repeating the single-service queue-and-subscription setup once per consuming service.

Inside the guaranteed publish/subscribe single-service leaf, the exclusive versus non-exclusive choice is realized as a queue-factory SNIPPET, not a separate sample: the same generated app builds an exclusive or a non-exclusive durable queue based on the developer's answer to question 4.

**A leaf can carry a variant.** The five strings name the pattern family; a design may adapt a leaf's default topology to a stated requirement (the variant path in Step 1). In every case the `Pattern` string stays one of the five verbatim, and the deviation is recorded in the topology fields of the Step 4 summary (for example the `Consumption endpoint`), never by inventing a sixth leaf string.

**Topic hierarchy and queue name.** Always publish to topics; never address a queue directly. Do NOT ask the developer to choose or design the topic string. Instead, apply the co-installed solace-topic-best-practices skill (which reads the canonical Topic Architecture Best Practices page live) to the developer's use case, derive the recommended topic hierarchy, and recommend it in ONE line; use that recommended topic without making the developer pick it. Do not design the hierarchy here by hand and do not restate that skill's guidance. Likewise, for any leaf that consumes from a durable queue, derive the queue name automatically rather than asking: follow the `q.`-prefixed dotted convention (for example the topic `acme/orders/new` maps to the queue `q.acme.orders`), recommend it in one line, and record it. The developer overrides the recommended topic or queue name ONLY if they explicitly ask to.

**Traps to avoid:**

- **Publishing direct-to-queue instead of pub/sub.** Publish to a topic; let the durable queue carry a topic subscription. ([Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md).)
- **Text payload instead of binary.** Default to a binary (`bytearray`) payload; the receiver reads it back with `get_payload_as_bytes()`.
- **Direct where guaranteed is required.** Use persistent delivery whenever the design cannot tolerate message loss or must survive consumer downtime. ([Message Delivery Modes](https://docs.solace.com/Get-Started/message-delivery-modes.md).)
- **A topic endpoint instead of a queue-with-topic-subscription.** Promote the durable queue. ([Topic Endpoints and Queues](https://docs.solace.com/Get-Started/topic-endpoints-queues.md).)
- **A guaranteed request-reply leaf.** The Python API has none; follow the rule at the end of Step 2.

## Step 4: Emit the design summary

Fill in this ONE unified design summary every run, in chat, with the confirmed values. The same fields appear every run regardless of which leaf the tree reached, so Implement mode reads one stable structure. Use explicit `n/a` for any field the chosen pattern does not apply. The title names the API, so a saved `solace-design.md` routes back to the Python entry file (the saved-design exception in SKILL.md API Selection).

```
Solace Python Design Summary
- Pattern:             <one of the five leaf strings, verbatim>
- Delivery:            <Direct | Guaranteed (persistent)>
- Access type:         <Exclusive | Non-exclusive | n/a>
- Topic:               <recommended topic string / hierarchy (derived, not interrogated)>
- Consumption endpoint: <direct topic subscription | durable queue + topic subscription | API-managed reply (RequestReplyMessagePublisher) | n/a>
- Threading:           <Async | Minimal (blocking publish and receive) | n/a>
- Auth:                Basic username/password
- Broker:              Solace Cloud
- Grounding docs:      <ONLY the canonical pages actually WebFetched in this session; `none fetched` when none was>
```

The fields are fixed: Pattern, Delivery, Access type, Topic, Consumption endpoint, Threading, Auth, Broker, Grounding docs. Formatting is at your discretion, but always present every field in that list, every run, with explicit `n/a` where a field does not apply. The JCSMP summary keeps its own, shorter field list; the two summaries route by their title and are no longer the same shape. The `Pattern` value stays one of the five leaf strings verbatim even for a variant; the deviation lives in the topology fields, never in a new leaf string.

Field semantics:

- **Pattern.** Exactly one of the five leaf strings, spelled verbatim: `Request-Reply (Direct)`, `Direct Pub/Sub`, `Guaranteed Pub/Sub (single service, exclusive)`, `Guaranteed Pub/Sub (single service, non-exclusive)`, `Guaranteed Pub/Sub (fan-out)`. This is the contract Implement mode reads, so the spelling is not cosmetic.
- **Delivery.** `Direct` for the two direct leaves; `Guaranteed (persistent)` for the three guaranteed leaves.
- **Access type.** `Exclusive` or `Non-exclusive` for the two guaranteed single-service leaves; `n/a` for every other leaf. Surface it explicitly even though it is also encoded in the Pattern string.
- **Consumption endpoint.** The pattern-specific place the consuming side reads from. Record the ACTUAL topology the design requires; the defaults are `direct topic subscription` for Direct Pub/Sub, `durable queue + topic subscription` for the three guaranteed leaves, and `API-managed reply (RequestReplyMessagePublisher)` for Request-Reply (Direct). For the durable-queue leaves, include the auto-derived queue name (for example `durable queue q.acme.orders + topic subscription`) so Implement mode reads the concrete queue name here. For a variant, record the variant topology here with a short inline reason.
- **Topic.** The recommended topic string or hierarchy, derived by applying the solace-topic-best-practices skill to the use case.
- **Threading.** `Async` or `Minimal (blocking publish and receive)` on the Direct Pub/Sub and Guaranteed Pub/Sub leaves, from question 3; `n/a` for Request-Reply (Direct). Spell the value verbatim: it is the contract Implement mode reads to pick the sample pair. `Async` selects the receive-callback and publish-receipt samples. `Minimal (blocking publish and receive)` selects the blocking samples: `receive_message()` on both pub/sub branches, plus `publish_await_acknowledgement()` on the guaranteed branch (the direct publisher has no receipt to await, so it is the same script on both answers). A summary with no `Threading` line reads as `Async` (a summary typed in chat, or a `solace-design.md` saved before the field existed).
- **Auth.** Basic username/password.
- **Broker.** Default to `Solace Cloud` and record it without asking. Switch to another type (Software Broker or Appliance) ONLY if the developer explicitly insists.
- **Grounding docs.** ONLY the pages this session actually WebFetched. Never list a page you did not fetch. Write `none fetched` when no page was fetched.

After presenting the summary in chat, close Design mode explicitly so the developer knows exactly what happens next; do NOT just display the summary and stop. On the fully-specified path (Step 1) this close-out is the ONLY confirm of the run; it carries the leaf confirmation too. If the design is a variant of its leaf, state the deviation and its rationale in one line here too. Ask them directly, in one step, both whether they are happy with this design AND whether to save it to `solace-design.md` in their project (for example: "Happy with this design? If so, I can save it to `solace-design.md` and move into Implement mode to generate the runnable Python project."). Write the file only on their OK; never write it unprompted. Once they confirm, state the next step plainly: the work moves into Implement mode, which generates the runnable Python project from this summary. Implement mode treats this summary, whether it lives in the chat or in the saved `solace-design.md`, as its input contract. Implement mode OPENS with its own Step 0 door question (Quickstart, Solace Suggested, or Custom), asked before any broker details are requested or accepted; an existing `config.json` or a running broker does not answer it, so ask it on the way in.
