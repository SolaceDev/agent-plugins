# Custom Mode

The Custom overlay for Implement mode. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Custom door. Custom is a la carte: the developer ticks which hardening knobs to include, and generation applies exactly the ticked subset and nothing more. It is a thin overlay. It does NOT re-describe how each knob works. For the four knobs that map to a `solace-suggested-mode.md` step, it points at that step and reuses it, so `solace-suggested-mode.md` stays the single source of truth for each hardening delta.

This overlay defers to `implement-mode.md` for all leaf and sample mechanics, exactly the way `solace-suggested-mode.md` does: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which sample pair each leaf reads, and the near-verbatim adaptation discipline), the AI-assisted disclaimer header on every generated class, the explicit single-class imports invariant (keep every import explicit, per `implement-mode.md`; never collapse to a wildcard), and the four-file `VERIFY:` marker contract. Custom adds NO verify stage and changes NO `VERIFY:` marker string.

## The checklist model: one up-front multi-select

When the developer picks Custom, establish the design summary first (per `implement-mode.md` Step 2, or reuse one already in hand), THEN present a SINGLE up-front multi-select checklist of the knobs that apply to the resulting `Pattern` leaf, capture the ticked subset once, and generate exactly that subset. Do NOT walk the knobs one at a time as sequential per-knob questions. One checklist, one capture, one generation pass. The property that defines Custom is "exactly the ticked subset, nothing more", so a knob that was not ticked leaves the generated output at its base shape for that concern.

## The five knobs

Custom offers FIVE knobs, and only these five. There is no sixth knob and no authentication knob; Basic username/password is the only auth this journey generates, exactly as on Quickstart and Solace Suggested, so it is never a Custom checklist item. Four of the five knobs reuse a `solace-suggested-mode.md` step; the fifth is defined in this file:

1. **Secure session (TLS).** Apply `solace-suggested-mode.md` Step 1, the Secure branch: a `tcps://` host with explicit certificate validation. Do not restate that logic here; the overlay step is the source of truth.
2. **HA channel values.** Apply `solace-suggested-mode.md` Step 2: the HA-failover question and the four reconnect channel values it gates.
3. **DMQ eligibility.** Apply `solace-suggested-mode.md` Step 3: `setDMQEligible(true)` on every PERSISTENT publish, with the broker-side prerequisite stated as the developer's responsibility.
4. **Decoupled projects.** Apply `solace-suggested-mode.md` Step 4: two separate, independent single-module Maven projects, one per role.
5. **Admin-provisioned queue.** The one knob that is a real code change rather than a reused overlay step. It is defined in full in the "Admin-provisioned-queue knob" section below.

For knobs 1 through 4, point at the named overlay step and reuse it. Do NOT duplicate the knob logic in this file.

## The Quickstart-shaped floor

Zero knobs ticked yields a Quickstart-shaped base app: a single Maven project with two mains, a self-provisioned durable queue, plaintext `tcp://` transport, and the `config.json`-or-CLI connection convention. Each ticked knob layers its Suggested delta on top of that floor. There is NO minimum-one-knob guard: zero ticks is a valid result, and it is the Quickstart-shaped base app. One exception to the Quickstart shape: the floor KEEPS the reference samples' reconnect baseline (`reconnectRetries` 20, `connectRetriesPerHost` 3); the fail-fast default-channel posture is the Quickstart door's alone, and on Custom the HA knob decides between that baseline (unticked) and the four HA values (ticked). Custom therefore spans cleanly from about Quickstart (nothing ticked) to about Solace Suggested Secure (everything ticked).

When the secure-session knob is unticked, the base stays plaintext `tcp://` (the same transport Quickstart uses) and carries an honest plaintext caveat: unencrypted transport, suitable for a dev or test broker with no TLS, not for real credentials or real data over untrusted networks. When the secure-session knob is ticked, the session upgrades to the `solace-suggested-mode.md` Step 1 Secure `tcps://` path with explicit certificate validation.

## Leaf-aware offering

Only offer knobs that apply to the design summary's chosen `Pattern` leaf. Read the `Pattern` field the same way `implement-mode.md` Step 2 does, then filter the checklist:

- Secure session, HA channel values, and decoupled projects apply on EVERY leaf.
- DMQ eligibility and the admin-provisioned queue apply ONLY on the leaves that carry PERSISTENT delivery and a durable queue: Guaranteed Pub/Sub and Request-Reply (Guaranteed).
- HIDE both DMQ eligibility and the admin-provisioned queue on the direct at-most-once leaves (Direct Pub/Sub and Request-Reply (Direct)). Those leaves have no queue, so both knobs would be a silent no-op there; a developer must not be able to tick a knob that does nothing.

## Admin-provisioned-queue knob (the one real code change)

Ticking the admin-provisioned-queue knob changes the generated code so the app binds a pre-existing, admin-owned queue instead of provisioning its own. The administrator owns the queue lifecycle AND its topic subscription on the broker; the app only binds a consumer flow to that already-provisioned, already-subscribed endpoint. This is grounded in [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md) (the object-oriented JCSMP API still needs a local logical queue instance to build the consumer flow, even for an administrator-provisioned endpoint) and [Adding a Topic Subscription](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Adding-Topic-Subscriptio.md) (subscription management and queue provisioning are separate operations, an administrator adds the subscription directly on the broker, and a client cannot remove an administrator-added subscription). WebFetch these live pages on demand; do not paste doc content.

This is a generation-time OMISSION from the generated output. It is NEVER an edit to any `.java` reference sample and never touches any `VERIFY:` marker string (QA-02 byte-stability).

On **Guaranteed Pub/Sub**, ticking the knob edits the Subscriber's generation. DROP these five from the generated Subscriber:

- `session.provision(...)` (the in-process durable-queue provisioning call).
- The `EndpointProperties` construction and its `setAccessType(...)`/`setPermission(...)` calls (they exist solely to configure the now-dropped `session.provision(...)` call and have no other consumer).
- `session.addSubscription(queue, topic, ...)` (the client-side topic-to-queue mapping; the administrator configured this subscription on the broker).
- The `session.isCapable(CapabilityType.ENDPOINT_MANAGEMENT)` provisioning gate (the isCapable(ENDPOINT_MANAGEMENT) capability check that guarded the client-side provisioning).
- The `IGNORE_DUPLICATE_SUBSCRIPTION_ERROR` session property (its sole purpose was surviving re-runs of `addSubscription`, which is now gone).

KEEP the rest of the Subscriber unchanged:

- The local `JCSMPFactory.onlyInstance().createQueue(name)` handle (the object-oriented API still needs a logical queue instance to build the flow).
- The CLIENT-ack `ConsumerFlowProperties` flow and `flow.start()`.
- The `onReceive` handler and `msg.ackMessage()`.

All of the Guaranteed Pub/Sub leaf's `VERIFY:` markers still emit, unchanged: `VERIFY: CONNECTED` after connect, `VERIFY: QUEUE_BOUND` after `flow.start()` (which remains), and `VERIFY: MESSAGE_RECEIVED` after `ackMessage()`. The Publisher is untouched, so its `VERIFY: PUBLISH_ACKED` is unaffected.

On **Request-Reply (Guaranteed)**, the same drop applies to the Replier's request-queue provisioning: drop `session.provision(...)`, the `EndpointProperties`/`setAccessType(...)`/`setPermission(...)` block that only fed it, `session.addSubscription(requestQueue, requestTopic, ...)`, the `session.isCapable(CapabilityType.ENDPOINT_MANAGEMENT)` gate, and the `IGNORE_DUPLICATE_SUBSCRIPTION_ERROR` property; keep the local `createQueue(name)` handle, the flow and `flow.start()` (which still emits `VERIFY: SUBSCRIBED`), and the `ackMessage()` on each handled request. The Replier now binds the administrator-provisioned request queue whose request-topic subscription the administrator configured on the broker.

Because the ticked app no longer provisions anything, an autonomous round-trip would need the administrator to have pre-provisioned the queue and its subscription first. That is why the admin-queue Custom subset is compile-only verified rather than live round-tripped.

## Return to the implement-mode contracts

After capturing the ticked subset and applying the deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's classes, and Step 6 resolves each `verification-checklist.md` item to its state for the Custom ticked subset, writes the tailored `solace-verification-checklist.md` into the project (one copy per project root when the decoupled-projects knob is ticked, otherwise one at the project root), invites the developer's additions, and reports the same in chat, the same way it does on Quickstart and Solace Suggested. This overlay selects which hardening deltas to layer; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md)
- [Adding a Topic Subscription](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Adding-Topic-Subscriptio.md)
