# Custom Mode

The Custom overlay for Implement mode of the Python API. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Custom door. Custom is a la carte: the developer ticks which hardening knobs to include, and generation applies exactly the ticked subset and nothing more. It is a thin overlay. It does NOT re-describe how each knob works. For the four knobs that map to a `solace-suggested-mode.md` step, it points at that step and reuses it, so `solace-suggested-mode.md` stays the single source of truth for each hardening delta.

This overlay defers to `implement-mode.md` for all leaf and sample mechanics, exactly the way `solace-suggested-mode.md` does: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which leaf wiring file and samples each leaf reads, and the near-verbatim adaptation discipline), the AI-assisted disclaimer header on every generated script, explicit imports (never `from solace.messaging... import *`), and the fixed `VERIFY:` marker contract. Custom changes NO `VERIFY:` marker string. Any live run on the Custom path prints the same one-line live-broker heads-up (SKILL.md Invariant 6) immediately before it starts: name the broker host and the run's actual broker-side effects.

## The checklist model: one up-front multi-select

When the developer picks Custom, establish the design summary first (per `implement-mode.md` Step 2, or reuse one already in hand), THEN present a SINGLE up-front multi-select checklist of the knobs that apply to the resulting `Pattern` leaf, capture the ticked subset once, and generate exactly that subset. Do NOT walk the knobs one at a time as sequential per-knob questions. One checklist, one capture, one generation pass. The property that defines Custom is "exactly the ticked subset, nothing more", so a knob that was not ticked leaves the generated output at its base shape for that concern.

## The five knobs

Custom offers FIVE knobs, and only these five. There is no sixth knob and no authentication knob; basic username/password is the only auth this journey generates, exactly as on Quickstart and Solace Suggested, so it is never a Custom checklist item. Four of the five knobs reuse a `solace-suggested-mode.md` step; the fifth is defined in this file:

1. **Secure session (TLS).** Apply `solace-suggested-mode.md` Step 1: a `tcps://` host, explicit certificate validation, and the developer's trust store directory in `config.json`. Do not restate that logic here; the overlay step is the source of truth.
2. **HA reconnect values.** Apply `solace-suggested-mode.md` Step 2: the HA-failover question and the retry strategies and retries-per-host value it gates.
3. **DMQ eligibility.** Apply `solace-suggested-mode.md` Step 3: `PERSISTENT_DMQ_ELIGIBLE` set explicitly on the persistent publisher's message template, with the broker-side prerequisite stated as the developer's responsibility.
4. **Decoupled projects.** Apply `solace-suggested-mode.md` Step 4: separate, independent Python projects, one per role.
5. **Admin-provisioned queue.** The one knob that is a real code change rather than a reused overlay step. It is defined in full in the "Admin-provisioned-queue knob" section below.

For knobs 1 through 4, point at the named overlay step and reuse it. Do NOT duplicate the knob logic in this file.

## The Quickstart-shaped floor

Zero knobs ticked yields a Quickstart-shaped base app: one Python project with both scripts, a self-provisioned durable queue on the guaranteed leaves, plaintext `tcp://` transport, and the `config.json`-or-command-line connection convention. Each ticked knob layers its Suggested delta on top of that floor. There is NO minimum-one-knob guard: zero ticks is a valid result, and it is the Quickstart-shaped base app. One exception to the Quickstart shape: the floor KEEPS the reference samples' reconnect baseline (20 retries 3000 ms apart); the fail-fast API-default posture is the Quickstart door's alone, and on Custom the HA knob decides between that baseline (unticked) and the HA values (ticked). Custom therefore spans from about Quickstart (nothing ticked) to about Solace Suggested (everything ticked).

When the secure-session knob is unticked, the base stays plaintext `tcp://` (the same transport Quickstart uses) and carries an honest plaintext caveat: unencrypted transport, suitable for a dev or test broker with no TLS, not for real credentials or real data over untrusted networks. When the secure-session knob is ticked, the session upgrades to the `solace-suggested-mode.md` Step 1 `tcps://` path.

The command-line fallback of the shared helper follows the knobs: it stays when both the secure-session and the decoupled-projects knobs are unticked (the Quickstart convention), and it is dropped, as on Solace Suggested, when either one is ticked, so the trust store path and the per-project credentials come only from each project's gitignored `config.json`. The helper requires the trust store key only when the secure-session knob is ticked.

## Leaf-aware offering

Only offer knobs that apply to the design summary's chosen `Pattern` leaf. Read the `Pattern` field the same way `implement-mode.md` Step 2 does, then filter the checklist:

- Secure session, HA reconnect values, and decoupled projects apply on EVERY leaf.
- DMQ eligibility and the admin-provisioned queue apply ONLY on the leaves that carry persistent delivery and a durable queue: the three Guaranteed Pub/Sub leaves. The Python API has no guaranteed request-reply leaf.
- HIDE both DMQ eligibility and the admin-provisioned queue on the direct at-most-once leaves (Direct Pub/Sub and Request-Reply (Direct)). Those leaves have no queue, so both knobs would be a silent no-op there; a developer must not be able to tick a knob that does nothing.

## Admin-provisioned-queue knob (the one real code change)

Ticking the admin-provisioned-queue knob changes the generated code so the subscriber binds a pre-existing, administrator-owned queue instead of provisioning its own. The administrator owns the queue lifecycle AND its topic subscription on the broker; the subscriber only binds a persistent receiver to that already-provisioned, already-subscribed endpoint. Ground it in [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md) (`CREATE_ON_START` creates the queue given to `build()`, and the default, `DO_NOT_CREATE`, disables any attempt to create missing resources) and [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md). WebFetch these live pages on demand; do not paste doc content.

This is a generation-time OMISSION from the generated output. It is NEVER an edit to any `.py` reference sample and never touches any `VERIFY:` marker string.

On **Guaranteed Pub/Sub**, ticking the knob edits the subscriber's generation. DROP these two from the generated subscriber's receiver builder:

- `with_missing_resources_creation_strategy(MissingResourcesCreationStrategy.CREATE_ON_START)` and its then-unused import (the in-process queue creation).
- `with_subscriptions([TopicSubscription.of(topic)])` and its then-unused import (the client-side topic-to-queue mapping; the administrator configured this subscription on the broker).

KEEP the rest of the subscriber unchanged:

- The local `Queue.durable_exclusive_queue(queue_name)` or `Queue.durable_non_exclusive_queue(queue_name)` handle from the `Access type` field (the receiver still needs a queue object to bind to; the access type itself is now the administrator's broker setting, so state in a comment that it must match).
- `with_message_client_acknowledgement()`, `with_activation_passivation_support(...)`, `receive_async(...)`, `start()`, and `ack()` after processing.
- The sample's `PubSubPlusClientError` branch around `start()`: it now reports a queue the administrator has not provisioned.

All of the Guaranteed Pub/Sub leaf's `VERIFY:` markers still emit, unchanged: `VERIFY: CONNECTED` after connect, `VERIFY: QUEUE_BOUND` after `start()`, and `VERIFY: MESSAGE_RECEIVED` after `ack()`. The publisher is untouched, so its `VERIFY: PUBLISH_ACKED` is unaffected. On the fan-out leaf, the drop applies to every subscriber script.

Because the ticked app no longer provisions anything, a round trip needs the administrator to have provisioned the queue and its subscription first. Say so in the handed-back next steps and record it in the checklist under Your responsibility.

## Return to the implement-mode contracts

After capturing the ticked subset and applying the deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's scripts and writes the tailored `solace-verification-checklist.md` into each project as generation output (one copy per project when the decoupled-projects knob is ticked, otherwise one at the project root), and Step 6 resolves each item to its state for the Custom ticked subset, invites the developer's additions, and reports the same in chat. This overlay selects which hardening deltas to layer; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)
