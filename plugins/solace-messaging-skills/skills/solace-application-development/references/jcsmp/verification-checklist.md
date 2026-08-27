# Verification Checklist

This checklist records what the chosen mode delivered and what remains the developer's responsibility. Each item is one binary (yes/no) check with one canonical Solace doc link. Read the linked doc to decide; the check is not a substitute for the doc.

This file is the master template. Every Implement run emits a per-project copy into the generated project at generation time (implement-mode.md Step 4), tailored to the chosen mode and the code that was generated. Nothing here implies the generated output is ready to carry real traffic; each mode delivers a baseline, and the items below name what it delivered and what stays the developer's job. The responsibility groups make that split explicit. "Delivered by this generation" names what the chosen mode generated into the code, so on that mode those items report as delivered; each carries a per-mode branch note because the same item is the developer's responsibility on a mode that did not generate it (for example, the secure-session item on Quickstart, which connects over plaintext). "Your responsibility (not delivered here)" names what no generation satisfies for the chosen mode, so those items stay the developer's responsibility. "Verified by the round-trip" names the conformance checks the publisher to consumer round-trip already exercises.

## Delivered by this generation

- [ ] The session is a `tcps://` secure TLS session with server-certificate validation on (`SSL_VALIDATE_CERTIFICATE = true` set explicitly), no custom trust store on the Solace Cloud public-CA happy path. Branch note: Delivered by this generation on Solace Suggested (always TLS; it has no non-secure variant) and on Custom with the secure-session knob ticked. Your responsibility on Quickstart and on Custom with the secure-session knob unticked. [Creating Secure Sessions](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Creating-Secure-Sessions.htm)
- [ ] Each role is its own independent Maven project with its own pom and NO parent POM (Publisher and Subscriber, or Requestor and Replier, are decoupled into separate projects). Branch note: Delivered by this generation on Solace Suggested and on Custom with the decoupled-projects knob ticked. Your responsibility on Quickstart, which ships both classes in one project with two mains, and on Custom with the decoupled-projects knob unticked. [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [ ] Every PERSISTENT publish calls `setDMQEligible(true)` so an undeliverable message can move to the broker dead message queue. Branch note: Delivered by this generation on Solace Suggested (the guaranteed leaves that carry PERSISTENT sends) and on Custom with the DMQ-eligibility knob ticked. Your responsibility otherwise: on Quickstart, on the direct at-most-once leaves where the flag does not apply, and on Custom with the DMQ-eligibility knob unticked. [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md)
- [ ] The four HA-failover channel values are set (`connectRetries` 1, `reconnectRetries` 20, `reconnectRetryWaitInMillis` 3000, `connectRetriesPerHost` 5). Branch note: Delivered by this generation on Solace Suggested when the HA-failover question is answered yes, and on Custom with the HA-channel-values knob ticked and that question answered yes. Otherwise the sample keeps its reconnect baseline (`reconnectRetries` 20, `connectRetriesPerHost` 3) and tuning the four HA values is Your responsibility. [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)

## Your responsibility (not delivered here)

- [ ] A dead message queue is provisioned on the broker with a max-redelivery count and/or a TTL (the `setDMQEligible` flag alone does nothing without a broker-side DMQ; the skill never provisions one). [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [ ] The durable queue is admin-provisioned rather than provisioned by the application at startup. Branch note: Delivered by this generation only on Custom with the admin-provisioned-queue knob ticked, where the generated app binds a pre-existing admin-owned queue instead of self-provisioning. Your responsibility on Quickstart, on Solace Suggested (where the app self-provisions its own queue and the overlay gives admin-provisioned-queue guidance only, generating no admin-queue binding code), and on Custom with the admin-provisioned-queue knob unticked. [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md)
- [ ] Credentials are handled safely (each `config.json` is gitignored and never committed; credentials are not passed as command-line arguments where they land in shell history or a process listing, as the Quickstart verify flow does for convenience). [Defining Client Authentication](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Defining-Client-Authentication.md)
- [ ] Authentication stronger than basic username/password (for example a client certificate or Kerberos) is set up where the environment requires it; this journey generates basic username/password only. [Defining Client Authentication](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Defining-Client-Authentication.md)
- [ ] The consumer calls `ackMessage()` only after the message is fully processed (CLIENT acknowledgement, not auto-ack). [Acknowledging Messages](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Acknowledging-Messages.md)

## Verified by the round-trip

- [ ] The session, flow, reconnect, and publish ACK/NACK event handlers from the reference samples are preserved across the generated classes (not stripped during generation). [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [ ] Messages are published with PERSISTENT (Guaranteed) delivery for the guaranteed journey. [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md)
- [ ] The publisher to consumer round-trip is verified against a reachable broker: a PERSISTENT message published to the topic lands on the subscribed durable queue and is consumed and acknowledged. [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md)

## Generation conformance (checked by the verify.sh preflight)

These are mechanical conformance checks on the generated output itself; the copied `verify.sh` reports the first four in its preflight, so a miss is visible on every run. They carry no doc link because they check skill conformance, not Solace guidance.

- [ ] Every generated source file starts with the exact line `AI-assisted code. Review before production use.` followed by the checklist pointer line.
- [ ] Exactly one logging backend is configured with a config resource on the classpath; any `log4j-core` on the classpath (including transitive) is at or above `2.17.1`; the `com.solacesystems` loggers are not silenced below INFO.
- [ ] The `sol-jcsmp` version in the pom matches the authoritative `<release>` from the `repo1.maven.org` Maven metadata at generation time (never the solrsearch index).
- [ ] This tailored checklist file exists at every generated project root.
- [ ] Every construct carried from a reference sample keeps that construct's comment (names adapted); dropped demo-harness code took its comments with it; fresh messaging code that applies a documented practice carries a short comment naming it (the comments-follow-their-code contract, implement-mode.md Step 4).
- [ ] A `verify.sh` stage ran and its stage name and exit code are recorded here: `<stage> → exit <code>` (or: the compile-only fallback was taken and the exact commands were handed back).
- [ ] Every page named in the design summary's Grounding docs field was WebFetched in this session (`none fetched` is an honest value; an unfetched citation is not).

## Developer-owned items (not delivered by this skill)

Record here any item the skill does not deliver: non-Solace concerns, out-of-scope concerns, or anything you are tracking on your own. Each is marked developer-owned rather than skill-satisfied, so at a glance a reader sees which items the skill stands behind and which you own. In this master template the section is a skeleton; the per-project copy emitted into a generated project is where concrete developer-owned items are recorded during a run.

## Your additional items

This is open space for you to add your own checklist items after the run, so the emitted checklist keeps growing with the project alongside the seeded items above.
