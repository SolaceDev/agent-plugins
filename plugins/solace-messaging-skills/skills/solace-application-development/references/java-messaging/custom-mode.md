# Custom Mode

The Custom overlay for Implement mode of the Java API. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Custom door. Custom is a la carte: the developer ticks which knobs to include, and generation applies exactly the ticked subset and nothing more. It is a thin overlay. It does NOT re-describe how each knob works. For the four knobs that map to a `solace-suggested-mode.md` step, it points at that step and reuses it, so `solace-suggested-mode.md` stays the single source of truth for each hardening delta. For the two blocking-shape knobs, it points at the leaf wiring file, which owns the blocking wiring.

This overlay defers to `implement-mode.md` for all leaf and sample mechanics, exactly the way `solace-suggested-mode.md` does: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which leaf wiring file each leaf reads, and the near-verbatim adaptation discipline), the AI-assisted disclaimer header on every generated class, explicit single-class imports (never `import com.solace.messaging.*;`), and the fixed `VERIFY:` marker contract. Custom changes NO `VERIFY:` marker string. Any live run on the Custom path prints the same one-line live-broker heads-up (SKILL.md Invariant 6) immediately before it starts: name the broker host and the run's actual broker-side effects.

## The checklist model: one up-front multi-select

When the developer picks Custom, establish the design summary first (per `implement-mode.md` Step 2, or reuse one already in hand), THEN present a SINGLE up-front multi-select checklist of the knobs that apply to the resulting `Pattern` leaf, capture the ticked subset once, and generate exactly that subset. Do NOT walk the knobs one at a time as sequential per-knob questions. One checklist, one capture, one generation pass. The property that defines Custom is "exactly the ticked subset, nothing more", so a knob that was not ticked leaves the generated output at its base shape for that concern.

## The seven knobs

Custom offers SEVEN knobs, and only these seven. There is no authentication knob; basic username/password is the only auth this journey generates, exactly as on Quickstart and Solace Suggested, so it is never a Custom checklist item. There is no back pressure knob either: every door keeps the samples' bounded publisher buffers (`solace-suggested-mode.md` Step 4), so no tick can produce an unbounded buffer.

1. **Secure session (TLS).** Apply `solace-suggested-mode.md` Step 1: a `tcps://` host, the explicit TLS strategy with certificate and host validation, the JVM default trust store, and the version floor check. Do not restate that logic here; the overlay step is the source of truth.
2. **HA reconnect values.** Apply `solace-suggested-mode.md` Step 2: the HA-failover question and the four reconnect properties it gates. Unticked keeps the samples' reconnect baseline (`RECONNECTION_ATTEMPTS` 20, `CONNECTION_RETRIES_PER_HOST` 3), not the fail-fast `ConfigurationProfile.V1` defaults; ticked applies the HA values when the HA-failover question is answered yes.
3. **DMQ eligibility.** Apply `solace-suggested-mode.md` Step 3: `PERSISTENT_DMQ_ELIGIBLE` set explicitly on every persistent publish, with the broker-side prerequisite stated as the developer's responsibility.
4. **Decoupled projects.** Apply `solace-suggested-mode.md` Step 5: two separate, independent single-module Maven projects, one per role.
5. **Admin-provisioned queue.** A real code change rather than a reused overlay step. It is defined in full in the "Admin-provisioned-queue knob" section below.
6. **Blocking publish.** The persistent Publisher sends each message with `publishAwaitAcknowledgement()` instead of the non-blocking `publish()` with a receipt listener. Defined in the "Blocking-shape knobs" section below.
7. **Blocking receive.** The Subscriber (or the Replier) pulls each message with `receiveMessage()` on its main loop instead of a `receiveAsync()` handler. Defined in the "Blocking-shape knobs" section below.

For knobs 1 through 4, point at the named overlay step and reuse it. Do NOT duplicate the knob logic in this file.

## The Quickstart-shaped floor

Zero knobs ticked yields a Quickstart-shaped base app: a single Maven project with two mains, the non-blocking shape, a self-provisioned durable queue on the guaranteed leaves, the transport scheme the developer's host carries, and the `config.json`-or-command-line connection convention. Each ticked knob layers its delta on top of that floor. There is NO minimum-one-knob guard: zero ticks is a valid result, and it is the Quickstart-shaped base app. One exception to the Quickstart shape: the floor KEEPS the reference samples' reconnect baseline (`RECONNECTION_ATTEMPTS` 20, `CONNECTION_RETRIES_PER_HOST` 3); the fail-fast posture is the Quickstart door's alone, and on Custom the HA knob decides between that baseline (unticked) and the HA values (ticked). Custom therefore spans from about Quickstart (nothing ticked) to about Solace Suggested plus the blocking shapes (everything ticked).

When the secure-session knob is unticked, the base connects with whatever scheme the developer's host carries, as on Quickstart, and adds no TLS strategy. On a plaintext `tcp://` host the base carries an honest plaintext caveat: unencrypted transport, suitable for a dev or test broker with no TLS, not for real credentials or real data over untrusted networks. Never downgrade a `tcps://` host to `tcp://`. When the secure-session knob is ticked, the session upgrades to the `solace-suggested-mode.md` Step 1 `tcps://` path.

The command-line fallback of the shared helper follows the knobs: it stays when both the secure-session and the decoupled-projects knobs are unticked (the Quickstart convention), and it is dropped, as on Solace Suggested, when either one is ticked, so the per-project credentials come only from each project's gitignored `config.json`.

## Leaf-aware offering

Only offer knobs that apply to the design summary's chosen `Pattern` leaf. Read the `Pattern` field the same way `implement-mode.md` Step 2 does, then filter the checklist:

- Secure session, HA reconnect values, and decoupled projects apply on EVERY leaf.
- DMQ eligibility, the admin-provisioned queue, and blocking publish apply ONLY on the three Guaranteed Pub/Sub leaves. The Java API has no guaranteed request-reply leaf in this skill.
- HIDE DMQ eligibility and the admin-provisioned queue on the direct at-most-once leaves (Direct Pub/Sub and Request-Reply (Direct)). Those leaves have no queue, so both knobs would be a silent no-op there; a developer must not be able to tick a knob that does nothing.
- HIDE blocking publish on the two direct leaves. The direct publisher has no blocking variant (direct has no receipt to await), and the Request-Reply (Direct) requestor is already blocking by default: its non-blocking shape is a design variant (`implement-request-reply.md`), not a knob.
- Blocking receive applies on every leaf. Name the role it changes in the checklist text: the Subscriber on the four pub/sub leaves, the Replier on Request-Reply (Direct).

## Blocking-shape knobs

The base shape is non-blocking. Each blocking knob swaps ONE sample for its blocking twin; the leaf wiring file owns the wiring, so read its blocking section and do not restate it here:

| Knob | Leaf | Sample it reads instead | Wiring |
|------|------|-------------------------|--------|
| Blocking publish | Guaranteed Pub/Sub (all three) | `java-messaging-guaranteed-publisher-blocking-sample.java` | `implement-guaranteed-pubsub.md`, Blocking shapes |
| Blocking receive | Guaranteed Pub/Sub (all three) | `java-messaging-guaranteed-subscriber-blocking-sample.java` | `implement-guaranteed-pubsub.md`, Blocking shapes |
| Blocking receive | Direct Pub/Sub | `java-messaging-direct-subscriber-blocking-sample.java` | `implement-direct-pubsub.md`, Subscriber class |
| Blocking receive | Request-Reply (Direct) | `java-messaging-direct-replier-blocking-sample.java` | `implement-request-reply.md`, Replier class |

The two knobs are independent: a developer can tick one, both, or neither, and each role reads only the sample its knob selects. The generated class for a ticked role calls `publishAwaitAcknowledgement()` or `receiveMessage()` and never registers a publish receipt listener or a `receiveAsync()` handler on that publisher or receiver. On the fan-out leaf, blocking receive applies to every Subscriber class. The blocking shapes change no `VERIFY:` marker string and no exec execution id.

## Admin-provisioned-queue knob

Ticking the admin-provisioned-queue knob changes the generated code so the Subscriber binds a pre-existing, administrator-owned queue instead of provisioning its own. The administrator owns the queue lifecycle AND its topic subscription on the broker; the Subscriber only binds a persistent receiver to that already-provisioned, already-subscribed endpoint. Ground it in [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md) (`CREATE_ON_START` creates the queue given to `build()`, and the default, `DO_NOT_CREATE`, disables any attempt to create missing resources) and [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md). WebFetch these live pages on demand; do not paste doc content.

This is a generation-time OMISSION from the generated output. It is NEVER an edit to any `.java` reference sample and never touches any `VERIFY:` marker string.

On **Guaranteed Pub/Sub**, ticking the knob edits the Subscriber's generation. DROP these two from the generated Subscriber's receiver builder:

- `withMissingResourcesCreationStrategy(MissingResourcesCreationStrategy.CREATE_ON_START)` and its then-unused import (the in-process queue creation).
- `withSubscriptions(TopicSubscription.of(topic))` and its then-unused import (the client-side topic-to-queue mapping; the administrator configured this subscription on the broker).

KEEP the rest of the Subscriber unchanged:

- The local `Queue.durableExclusiveQueue(name)` or `Queue.durableNonExclusiveQueue(name)` handle from the `Access type` field (the receiver still needs a queue object to bind to; the access type itself is now the administrator's broker setting, so state in a comment that it must match).
- `withMessageClientAcknowledgement()`, `withActivationPassivationSupport(...)`, `receiveAsync(...)` (or the `receiveMessage(...)` loop when blocking receive is ticked), `start()`, and `ack()` after processing.
- The sample's catch branch around `start()`: adapt its message, because it now reports a queue the administrator has not provisioned.

All of the Guaranteed Pub/Sub leaf's `VERIFY:` markers still emit, unchanged. The Publisher is untouched. On the fan-out leaf, the drop applies to every Subscriber class.

Because the ticked app no longer provisions anything, a round trip needs the administrator to have provisioned the queue and its subscription first. Say so in the handed-back next steps and record it in the checklist under Your responsibility.

## Return to the implement-mode contracts

After capturing the ticked subset and applying the deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's classes and writes the tailored `solace-verification-checklist.md` into the project as generation output (one copy per project root when the decoupled-projects knob is ticked, otherwise one at the project root), and Step 6 resolves each item to its state for the Custom ticked subset, invites the developer's additions, and reports the same in chat. This overlay selects which deltas to layer; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md)
- [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md)
