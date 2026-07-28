# Solace Suggested Mode

The Solace Suggested overlay for Implement mode. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Solace Suggested path. It hardens whatever the design summary chose without re-describing how the chosen leaf is generated. Work through the steps in order. Read this file's grounding links only as each step needs them; link the canonical doc and state the decision in one line, never paraphrase doc content.

This overlay's Secure sub-mode assumes a Solace Cloud broker whose TLS server certificate is already configured and chains to a public CA; its Non-Secure sub-mode targets a dev or test broker that has no TLS configured. Solace Cloud is the primary broker default; the session is built from the connection details the developer pasted into a gitignored `config.json`. Step 1 forks that session build into Secure (a `tcps://` host with explicit certificate validation) and Non-Secure (a plaintext `tcp://` host with no certificate validation). The overlay does NOT provision or configure any broker (no DMQ, no queues, no TLS certificates). It states the broker-side prerequisites; it never sets them up.

## This is a leaf-agnostic overlay

The Solace Suggested hardening is described ONCE here and applies on top of whatever `Pattern` leaf the design summary selected. It is not duplicated per leaf. The session-level deltas (TLS and HA failover) are identical code in every leaf, the message-level delta (DMQ eligibility) lands only on PERSISTENT sends, and the separate-projects layout applies to any two-role pattern (Publisher and Subscriber, or Requestor and Replier).

This overlay defers to `implement-mode.md` for all leaf and sample mechanics: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which sample pair each leaf reads, the near-verbatim adaptation discipline), and the four-file `VERIFY:` marker contract. It ADDS only the Solace Suggested deltas below. It adds NO verify stages and changes NO `VERIFY:` marker string. The marker contract, the leaf dispatch table, and the Step 6 checklist emit and report all stay in `implement-mode.md` and run unchanged on the Solace Suggested path.

Every generated class this overlay describes carries the AI-assisted disclaimer header (the exact `AI-assisted code. Review before production use.` line plus a pointer to the local `solace-verification-checklist.md`, as `implement-mode.md` Step 4 defines it) and keeps every import explicit. Never collapse imports to a wildcard such as `import com.solacesystems.jcsmp.*;`; explicit single-class imports only, in every generated class, on every leaf. This overlay names identifiers, constants, and method calls; it does not paste whole generated classes.

## Step 0: Carry over the config.json convention

The Solace Suggested path reads broker connection details from a `config.json` file. Use keys that match the `JCSMPProperties` property-name strings exactly (the values of the `HOST`, `VPN_NAME`, `USERNAME`, and `PASSWORD` constants) so every config key is traceable to the Javadoc: `host`, `vpn_name`, `username`, `password`. State plainly to the developer that `config.json` holds broker credentials, so it MUST be added to `.gitignore` and never committed, and that the credential VALUES MUST be placed directly into `config.json`, never pasted into the chat. Never inline a host, message VPN, username, or password into a generated class; the four values reach the apps only through `config.json`. The apps read those four keys through the shared `SolaceConnectionConfig` helper that `implement-mode.md` Step 4 generates into every project; on the Solace Suggested path there are no CLI args, so the helper reads `config.json` directly.

Generate the classes directly, with no per-stage confirmation gate: write the Publisher and the Subscriber (or the Requestor and the Replier) the same way Quickstart does, then let the developer review the written files. Do NOT preview sensitive excerpts and ask the developer to confirm before writing each stage. The credential VALUES live only in the gitignored `config.json`, never in a generated class or the chat, so there is nothing credential-bearing to gate on; writing the generated code does not need a confirmation.

## Step 1: Build the session (Secure or Non-Secure)

This is the ONLY step that forks. When the developer chose Solace Suggested at `implement-mode.md` Step 0, Step 0 also asked whether they want Secure or Non-Secure. This overlay reads that Secure or Non-Secure flag and forks the session build here, and ONLY here. Every other step in this overlay is identical on both sub-modes: DMQ eligibility, HA failover, the two decoupled projects, the admin-provisioned-queue guidance, and the return to the implement-mode contracts all run the same way whichever sub-mode the developer picked. Non-Secure inverts exactly one thing versus Secure: it keeps the plaintext `tcp://` scheme and sets no certificate-validation property.

### Secure: build the TLS secure session

The pasted Solace Cloud `host` carries a `tcps://` scheme. `tcps://` is the secure transport: it runs the session over TLS. If the developer pasted a `tcp://` (plaintext) host, upgrade it to `tcps://` and state the TLS-already-configured assumption to them; never open a plaintext session on the Secure sub-mode. The plaintext `tcp://` scheme must not survive into the generated `host` value.

Set `JCSMPProperties.SSL_VALIDATE_CERTIFICATE = true` EXPLICITLY. The value is the JCSMP default, but setting it explicitly makes the secure posture auditable and visible in code review: a reviewer sees that server-certificate validation is on without having to know the default. For the same auditable reason, also set `JCSMPProperties.SSL_VALIDATE_CERTIFICATE_DATE = true` explicitly, so the not-expired check is equally visible. The two explicit lines together state the full validation posture.

Do NOT generate a custom trust store. The Solace Cloud public-CA happy path falls back to the JVM default trust store (`cacerts`), which already carries the public root CAs, so no `SSL_TRUST_STORE` or `SSL_TRUST_STORE_PASSWORD` is needed. NEVER set `SSL_VALIDATE_CERTIFICATE` to `false` anywhere in the session setup; disabling server-certificate validation defeats the secure session entirely.

Ground TLS in [Creating Secure Sessions](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Creating-Secure-Sessions.htm).

### Non-Secure: build a plaintext session

Keep the plaintext `tcp://` scheme the developer pasted and build a plaintext session. Whatever `tcp://` host they pasted survives verbatim into the generated `host` value; do NOT upgrade it to `tcps://`. This is the sub-mode for a dev or test broker that has no TLS configured.

Omit the certificate-validation properties ENTIRELY. A plaintext session has no server certificate to validate, so set NO `SSL_` property at all: no `SSL_VALIDATE_CERTIFICATE`, no `SSL_VALIDATE_CERTIFICATE_DATE`, and no trust store. The properties are simply absent. NEVER emit `SSL_VALIDATE_CERTIFICATE` set to `false`: the correct posture is absence, and a `false` value would be a misleading artifact that also reads as a disabled-validation anti-pattern. Setting the property to `false` is never the Non-Secure instruction; the property does not appear at all.

State the caveat plainly to the developer: the Non-Secure sub-mode uses unencrypted transport, it fits a dev or test broker that has no TLS configured, and it is not appropriate for real credentials or real data over untrusted networks. Then drop ONE short comment at the session-build site in the generated class conveying the same: a single line noting the session is plaintext with no TLS and is intended for a dev or test broker only. Keep it to that one comment; do not scatter the caveat through the class.

## Step 2: Ask the HA-failover question; upgrade the reconnect channel values on yes, keep the sample baseline on no

After broker confirmation and before generating the session properties, ask the developer plainly: is HA failover set up? Ask it as a direct question and wait for the answer.

The reference leaf already ships a reasonable reconnect baseline on its `JCSMPChannelProperties` (set on `JCSMPProperties.CLIENT_CHANNEL_PROPERTIES`): `reconnectRetries` 20 and `connectRetriesPerHost` 3, a few minutes of reconnect attempts, the recommended default when the design says nothing about high availability. When the answer is no, KEEP this baseline as-is; do not strip it back to the JCSMP channel-property defaults, and do not add the HA-only values.

ONLY when the answer is yes, upgrade that same `JCSMPChannelProperties` to the four HA-failover values: `connectRetries` 1, `reconnectRetries` 20, `reconnectRetryWaitInMillis` 3000, `connectRetriesPerHost` 5. Relative to the baseline this raises `connectRetriesPerHost` from 3 to 5 and adds `connectRetries` 1 and `reconnectRetryWaitInMillis` 3000. Ground the four values in [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md), which frames `reconnectRetries` 20 and `connectRetriesPerHost` 5 as the HA-failover reconnect tuning.

## Step 3: Set DMQ eligibility on every PERSISTENT publish (Solace Suggested path only)

On the Solace Suggested path, set `message.setDMQEligible(true)` next to `message.setDeliveryMode(DeliveryMode.PERSISTENT)` at EVERY PERSISTENT publish build step. This is the guaranteed pub/sub publisher, and BOTH the guaranteed request and the guaranteed reply on the Request-Reply (Guaranteed) leaf. Wherever the chosen leaf builds a PERSISTENT message, attach the flag at that build step.

The direct leaves are EXCLUDED. DMQ eligibility requires Guaranteed (PERSISTENT) delivery, so the flag on a direct message is a misleading no-op; do NOT set it on Direct Pub/Sub or Request-Reply (Direct). Quickstart and the reference samples are unchanged; `setDMQEligible(true)` is a Solace Suggested addition only.

At the call site, state the broker-side prerequisite as the developer's responsibility: a message moves to a Dead Message Queue only when a DMQ is provisioned on the broker AND a max-redelivery count or a TTL is configured. The API flag alone does not make DMQ work; it marks the message as eligible, and the broker config the developer owns decides the rest. Ground the PERSISTENT delivery in [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md), the DMQ-eligible and TTL framing in [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md), and the durable endpoint the developer provisions in [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md).

## Step 4: Generate two separate, independent Maven projects

The Solace Suggested path decouples the two roles. Generate TWO standalone single-module Maven projects, one per role (Publisher and Subscriber, or Requestor and Replier), each in its own project root with its OWN `pom.xml`. Each pom resolves `sol-jcsmp` live at generation time (the no-hardcoded-versions invariant), pins log4j2 to `2.26.0` (the one allowed pin, at or above the `2.17.1` Log4Shell floor), and declares exactly ONE `exec-maven-plugin` execution for that project's single main.

There is NO parent POM and NO `<modules>` aggregator. Two independent projects, not a multi-module reactor: each project builds on its own. Recommend each project is self-contained, carrying its own `config.json` plus `.gitignore` (the same four lowercase `JCSMPProperties` property-name keys, the same gitignored-and-never-committed rule), consistent with two independent projects and no shared parent.

The Solace Suggested path adds no new Maven dependency. Every Solace Suggested surface in this overlay (TLS, DMQ, HA) lives in the same `com.solacesystems:sol-jcsmp` JAR Quickstart already uses, plus the already-pinned log4j2; there is no new coordinate.

Solace Suggested verification uses the SAME bundled `scripts/verify.sh` as Quickstart, run in its two-project mode. `verify.sh` accepts per-role project directories, so it can drive the decoupled layout: it compiles each project, starts the long-running role first and the foreground role second, watches each process for the fixed `VERIFY:` markers, exercises the SIGINT graceful-shutdown hook, and classifies the outcome by the same 0/1/2 exit codes and bounded fix loop that `implement-mode.md` Step 5 defines. In two-project mode it passes NO connection details on the command line; each app reads its own gitignored `config.json`, so the broker credentials never appear in a process listing (the checklist's credential-handling item). Invoke it with the role-directory flags instead of the positional connection args:

- pub/sub leaves: `scripts/verify.sh roundtrip --subscriber-dir <subscriber-project> --publisher-dir <publisher-project>`
- request-reply leaves: `scripts/verify.sh guaranteed-request-reply --replier-dir <replier-project> --requestor-dir <requestor-project>` (use `direct-request-reply` for the direct leaf).

Run it for real under the SAME consent Quickstart uses: only when each project's `config.json` is present with REAL values (not the `config.example.json` placeholders). When any project's `config.json` is absent or still holds placeholders, do NOT fabricate a run: compile each project (`mvn -q compile` per root), report the compile result, and hand back the two-project `verify.sh` command for the developer to run themselves (the honesty rule). A live Solace Suggested round-trip needs a TLS-capable broker, since the `tcps://` Solace Suggested session validates the server certificate; a plaintext or unreachable broker surfaces as an environment failure (exit 2), which `verify.sh` classifies without entering the fix loop, so it never masquerades as a code bug.

## Step 5: Topic architecture

For how to structure, order, name, and place wildcards in the topic hierarchy, point the developer to the co-installed `solace-topic-best-practices` skill, which reads the canonical Topic Architecture Best Practices page live and applies it. Do not design the topic hierarchy here and do not restate that skill's guidance; the topic string the developer settles on is recorded in the design summary, but the hierarchy decision belongs to that skill.

## Step 6: Return to the implement-mode contracts

After the Solace Suggested deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's classes, and Step 6 resolves each `verification-checklist.md` item to its state for the chosen mode, writes the tailored `solace-verification-checklist.md` into the project (one copy per project root on this overlay's decoupled two-project layout), invites the developer's additions, and reports the same in chat, on the Solace Suggested path the same way it does on Quickstart. This overlay adds the Solace Suggested hardening; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md)
- [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md)

Live exception (the `.htm` page has no `docs.solace.com` `.md` form; use the live URL directly):

- [Creating Secure Sessions](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Creating-Secure-Sessions.htm)
