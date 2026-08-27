# Solace Suggested Mode

The Solace Suggested overlay for Implement mode. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Solace Suggested path. It hardens whatever the design summary chose without re-describing how the chosen leaf is generated. Work through the steps in order. Read this file's grounding links only as each step needs them; link the canonical doc and state the decision in one line, never paraphrase doc content.

This overlay is TLS-secure by definition. It assumes a broker whose TLS server certificate is already configured; Solace Cloud, the primary broker default, has TLS out of the box and chains to a public CA. There is NO non-secure variant of Solace Suggested. When the target broker has no TLS configured, do not proceed on this path: state that Solace Suggested requires a TLS-capable broker and route back to the `implement-mode.md` Step 0 door, which offers Custom with the equivalent knobs minus the secure session, or Quickstart. The session is built from the connection details the developer pasted into a gitignored `config.json`. The overlay does NOT provision or configure any broker (no DMQ, no queues, no TLS certificates). It states the broker-side prerequisites; it never sets them up.

## This is a leaf-agnostic overlay

The Solace Suggested hardening is described ONCE here and applies on top of whatever `Pattern` leaf the design summary selected. It is not duplicated per leaf. The session-level deltas (TLS and HA failover) are identical code in every leaf, the message-level delta (DMQ eligibility) lands only on PERSISTENT sends, and the separate-projects layout applies to any two-role pattern (Publisher and Subscriber, or Requestor and Replier).

This overlay defers to `implement-mode.md` for all leaf and sample mechanics: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which leaf wiring file and sample pair each leaf reads, the near-verbatim adaptation discipline), and the fixed `VERIFY:` marker contract. It ADDS only the Solace Suggested deltas below. It adds NO verify stages and changes NO `VERIFY:` marker string. The marker contract, the leaf dispatch table, and the checklist write (Step 4) and report (Step 6) all stay in `implement-mode.md` and its leaf wiring files, and run unchanged on the Solace Suggested path.

Every generated class this overlay describes carries the AI-assisted disclaimer header (the exact `AI-assisted code. Review before production use.` line plus a pointer to the local `solace-verification-checklist.md`, as `implement-mode.md` Step 4 defines it) and keeps every import explicit. Never collapse imports to a wildcard such as `import com.solacesystems.jcsmp.*;`; explicit single-class imports only, in every generated class, on every leaf. This overlay names identifiers, constants, and method calls; it does not paste whole generated classes.

## Step 0: Carry over the config.json convention

The Solace Suggested path reads broker connection details from a `config.json` file. Use keys that match the `JCSMPProperties` property-name strings exactly (the values of the `HOST`, `VPN_NAME`, `USERNAME`, and `PASSWORD` constants) so every config key is traceable to the Javadoc: `host`, `vpn_name`, `username`, `password`. State plainly to the developer that `config.json` holds broker credentials, so it MUST be added to `.gitignore` and never committed, and that the credential VALUES MUST be placed directly into `config.json`, never pasted into the chat. Never inline a host, message VPN, username, or password into a generated class; the four values reach the apps only through `config.json`. The apps read those four keys through the shared `SolaceConnectionConfig` helper that `implement-mode.md` Step 4 generates into every project; on the Solace Suggested path there are no CLI args, so the helper reads `config.json` directly.

Generate the classes directly, with no per-stage confirmation gate: write the Publisher and the Subscriber (or the Requestor and the Replier) the same way Quickstart does, then let the developer review the written files. Do NOT preview sensitive excerpts and ask the developer to confirm before writing each stage. The credential VALUES live only in the gitignored `config.json`, never in a generated class or the chat, so there is nothing credential-bearing to gate on; writing the generated code does not need a confirmation.

## Step 1: Build the TLS secure session

The pasted Solace Cloud `host` carries a `tcps://` scheme. `tcps://` is the secure transport: it runs the session over TLS. If the developer pasted a `tcp://` (plaintext) host, upgrade it to `tcps://` and state the TLS-already-configured assumption to them; never open a plaintext session on the Solace Suggested path. The plaintext `tcp://` scheme must not survive into the generated `host` value. If the broker genuinely has no TLS configured, this overlay does not fit: route back to the `implement-mode.md` Step 0 door (Custom minus the secure-session knob, or Quickstart) rather than degrading the session here.

Set `JCSMPProperties.SSL_VALIDATE_CERTIFICATE = true` EXPLICITLY. The value is the JCSMP default, but setting it explicitly makes the secure posture auditable and visible in code review: a reviewer sees that server-certificate validation is on without having to know the default. For the same auditable reason, also set `JCSMPProperties.SSL_VALIDATE_CERTIFICATE_DATE = true` explicitly, so the not-expired check is equally visible. The two explicit lines together state the full validation posture.

Do NOT generate a custom trust store. The Solace Cloud public-CA happy path falls back to the JVM default trust store (`cacerts`), which already carries the public root CAs, so no `SSL_TRUST_STORE` or `SSL_TRUST_STORE_PASSWORD` is needed. NEVER set `SSL_VALIDATE_CERTIFICATE` to `false` anywhere in the session setup; disabling server-certificate validation defeats the secure session entirely.

Ground TLS in [Creating Secure Sessions](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Creating-Secure-Sessions.htm).

## Step 2: Ask the HA-failover question; upgrade the reconnect channel values on yes, keep the sample baseline on no

After broker confirmation and before generating the session properties, ask the developer plainly: is HA failover set up? Ask it as a direct question and wait for the answer.

The reference leaf already ships a reasonable reconnect baseline on its `JCSMPChannelProperties` (set on `JCSMPProperties.CLIENT_CHANNEL_PROPERTIES`): `reconnectRetries` 20 and `connectRetriesPerHost` 3, a few minutes of reconnect attempts, the recommended default when the design says nothing about high availability. When the answer is no, KEEP this baseline as-is; do not strip it back to the JCSMP channel-property defaults, and do not add the HA-only values.

ONLY when the answer is yes, upgrade that same `JCSMPChannelProperties` to the four HA-failover values: `connectRetries` 1, `reconnectRetries` 20, `reconnectRetryWaitInMillis` 3000, `connectRetriesPerHost` 5. Relative to the baseline this raises `connectRetriesPerHost` from 3 to 5 and adds `connectRetries` 1 and `reconnectRetryWaitInMillis` 3000. Ground the four values in [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md), which frames `reconnectRetries` 20 and `connectRetriesPerHost` 5 as the HA-failover reconnect tuning.

## Step 3: Set DMQ eligibility on every PERSISTENT publish (Solace Suggested path only)

On the Solace Suggested path, set `message.setDMQEligible(true)` next to `message.setDeliveryMode(DeliveryMode.PERSISTENT)` at EVERY PERSISTENT publish build step. This is the guaranteed pub/sub publisher, and BOTH the guaranteed request and the guaranteed reply on the Request-Reply (Guaranteed) leaf. Wherever the chosen leaf builds a PERSISTENT message, attach the flag at that build step.

The direct leaves are EXCLUDED. DMQ eligibility requires Guaranteed (PERSISTENT) delivery, so the flag on a direct message is a misleading no-op; do NOT set it on Direct Pub/Sub or Request-Reply (Direct). Quickstart and the reference samples are unchanged; `setDMQEligible(true)` is a Solace Suggested addition only.

At the call site, state the broker-side prerequisite as the developer's responsibility: a message moves to a Dead Message Queue only when a DMQ is provisioned on the broker AND a max-redelivery count or a TTL is configured. The API flag alone does not make DMQ work; it marks the message as eligible, and the broker config the developer owns decides the rest. Ground the PERSISTENT delivery in [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md), the DMQ-eligible and TTL framing in [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md), and the durable endpoint the developer provisions in [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md).

## Step 4: Generate two separate, independent Maven projects

The Solace Suggested path decouples the two roles. The DEFAULT shape is TWO standalone single-module Maven projects, one per role (Publisher and Subscriber, or Requestor and Replier), each in its own project root with its OWN `pom.xml`. Each pom resolves `sol-jcsmp` live at generation time (the no-hardcoded-versions invariant), resolves its logging backend the same way per `implement-mode.md` Step 3 (no pinned versions; the `2.17.1` Log4Shell floor is enforced as a check, not a pin), and declares exactly ONE `exec-maven-plugin` execution for that project's single main.

On this default shape there is NO parent POM and NO `<modules>` aggregator. Two independent projects, not a multi-module reactor: each project builds on its own. Recommend each project is self-contained, carrying its own `config.json` plus `.gitignore` (the same four lowercase `JCSMPProperties` property-name keys, the same gitignored-and-never-committed rule), consistent with two independent projects and no shared parent.

**When the developer's ask requires a single deliverable** (one command, one jar, one web app with one dashboard), the two-project default conflicts with the ask. Surface that conflict plainly and let the developer resolve it; NEVER merge silently. Offer three resolutions: (1) keep two independent projects plus a thin launcher script that starts both; (2) one Maven build with separate publisher and consumer modules under a parent pom, which keeps the decoupling and still yields one command; (3) drop the decoupled-projects shape with the developer's explicit consent. Whichever they choose is recorded in the emitted `solace-verification-checklist.md` — a consented drop lands under Developer-owned items, so the deviation is written down, not lost.

The Solace Suggested path adds no new Maven dependency. Every Solace Suggested surface in this overlay (TLS, DMQ, HA) lives in the same `com.solacesystems:sol-jcsmp` JAR Quickstart already uses, plus the logging backend Step 3 resolves; there is no new coordinate.

Solace Suggested verification uses the SAME bundled `scripts/verify.sh` as Quickstart, run in its two-project mode. `verify.sh` accepts per-role project directories, so it can drive the decoupled layout: it compiles each project, starts the long-running role first and the foreground role second, watches each process for the fixed `VERIFY:` markers, exercises the SIGINT graceful-shutdown hook, and classifies the outcome by the same 0/1/2 exit codes and bounded fix loop that `implement-mode.md` Step 5 defines. In two-project mode it passes NO connection details on the command line; each app reads its own gitignored `config.json`, so the broker credentials never appear in a process listing (the checklist's credential-handling item). Invoke it with the role-directory flags instead of the positional connection args:

- pub/sub leaves: `scripts/verify.sh roundtrip --subscriber-dir <subscriber-project> --publisher-dir <publisher-project>`
- request-reply leaves: `scripts/verify.sh guaranteed-request-reply --replier-dir <replier-project> --requestor-dir <requestor-project>` (use `direct-request-reply` for the direct leaf).

Run it for real under the SAME consent Quickstart uses: only when each project's `config.json` is present with REAL values (not the `config.example.json` placeholders). Immediately before any live run, print the one-line live-broker heads-up (SKILL.md Invariant 6): name the broker host and state the run's actual broker-side effects (the queues it provisions, the connections it opens, the messages it publishes); the real-values `config.json` is the consent, the heads-up is not a prompt. When any project's `config.json` is absent or still holds placeholders, do NOT fabricate a run: compile each project (`mvn -q compile` per root), report the compile result, and hand back the two-project `verify.sh` command for the developer to run themselves (the honesty rule). A live Solace Suggested round-trip needs a TLS-capable broker, since the `tcps://` Solace Suggested session validates the server certificate; a plaintext or unreachable broker surfaces as an environment failure (exit 2), which `verify.sh` classifies without entering the fix loop, so it never masquerades as a code bug.

## Step 5: Topic architecture

For how to structure, order, name, and place wildcards in the topic hierarchy, point the developer to the co-installed `solace-topic-best-practices` skill, which reads the canonical Topic Architecture Best Practices page live and applies it. Do not design the topic hierarchy here and do not restate that skill's guidance; the topic string the developer settles on is recorded in the design summary, but the hierarchy decision belongs to that skill.

## Step 6: Return to the implement-mode contracts

After the Solace Suggested deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's classes and writes the tailored `solace-verification-checklist.md` into the project as generation output (one copy per project root on this overlay's decoupled two-project layout), and Step 6 resolves each item to its state for the chosen mode, invites the developer's additions, and reports the same in chat, on the Solace Suggested path the same way it does on Quickstart. This overlay adds the Solace Suggested hardening; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md)
- [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md)

Live exception (the `.htm` page has no `docs.solace.com` `.md` form; use the live URL directly):

- [Creating Secure Sessions](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Creating-Secure-Sessions.htm)
