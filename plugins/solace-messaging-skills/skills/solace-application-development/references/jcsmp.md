# JCSMP API

The JCSMP entry file of the Solace API development skill. The Solace Messaging API for JCSMP is published as `com.solacesystems:sol-jcsmp`. This file detects the developer's intent and selects the right JCSMP mode; the detailed guidance lives in the lazy-loaded mode files under `jcsmp/`.

The cross-cutting invariants (content sourcing, WebFetch-on-demand doc grounding, and no hardcoded versions) are owned by the umbrella `SKILL.md`. Apply them here; this file does not restate them. The JCSMP-specific points below are the coordinate to depend on and the canonical pages to ground in.

## Mode Detection

Determine the user's intent and enter the appropriate mode:

| User intent | Mode | What to do |
|---|---|---|
| "Help me choose the Solace JCSMP messaging pattern for a new app" / "Topic or queue?" / "What delivery semantics should I use?" | **Design** | Read `jcsmp/design-mode.md` |
| "Build me a Solace JCSMP publisher/consumer app" / "Generate a Maven JCSMP project" / "Publish to a topic and consume from a queue" | **Implement** | Read `jcsmp/implement-mode.md` |
| "My JCSMP app is throwing on connect" / "Why is my consumer not binding the queue?" | **Debug** | Debug mode is not yet available in this release. Redirect the user to the canonical Solace JCSMP troubleshooting documentation: [JCSMP API Home](https://docs.solace.com/API/Messaging-APIs/JCSMP-API/jcsmp-api-home.md). Do not generate debugging guidance from memory. |

If unclear, default to **Design**. Understand the messaging problem before generating code.

## JCSMP coordinate

The single Solace dependency for this API is `com.solacesystems:sol-jcsmp`. Use the groupId `com.solacesystems` exactly; the shorter `com.solace` form does not resolve on Maven Central. Resolve the latest release at generation time (the no-hardcoded-versions invariant); never pin a `sol-jcsmp` version in skill content.

## Canonical doc links

JCSMP-specific grounding. Each page below is a live `docs.solace.com` `.md` URL, WebFetched on demand; the live exceptions are listed separately because they have no `docs.solace.com` `.md` form.

- [JCSMP API Home](https://docs.solace.com/API/Messaging-APIs/JCSMP-API/jcsmp-api-home.md): entry point for the JCSMP API.
- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md): documented best practices for JCSMP applications.
- For the broader Solace grounding, see the shared [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md) page.

Live exceptions (these have no `docs.solace.com` `.md` form; use the live URL directly):

- [JCSMP Javadoc](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/index.html)
- [Java API Release Notes](https://products.solace.com/download/JAVA_API_RN)
- [sol-jcsmp on Maven Central](https://central.sonatype.com/artifact/com.solacesystems/sol-jcsmp)

## Mode and reference files (read on-demand only)

- `jcsmp/prerequisites.md`: Solace core-concepts grounding and broker acquisition (route here first if the developer has no reachable broker).
- `jcsmp/design-mode.md`: choose the messaging pattern/topology before generating code.
- `jcsmp/implement-mode.md`: generate a runnable Maven JCSMP pub/sub project.
- `jcsmp/solace-suggested-mode.md`: the leaf-agnostic Solace Suggested overlay (TLS secure session, DMQ on PERSISTENT, HA failover, separate Maven projects) reached from implement-mode Step 0 on the Solace Suggested path.
- `jcsmp/custom-mode.md`: the thin a la carte Custom overlay reached from implement-mode Step 0 on the Custom path; a leaf-aware checklist of the hardening knobs (secure session, DMQ, HA, decoupled projects, admin-provisioned queue) that generates exactly the ticked subset, reusing the Solace Suggested overlay steps for the mapped knobs.
- `jcsmp/verification-checklist.md`: the master template of binary verification checks organized into three responsibility groups (delivered by this generation, the developer's responsibility, and verified by the round-trip); Implement mode Step 6 emits a tailored `solace-verification-checklist.md` into each generated project seeded for the chosen mode and reports the same in chat.
- `jcsmp/scripts/verify.sh`: the bundled stage-dispatched run-and-observe verify script (`verify.sh <publisher|consumer|roundtrip|direct|direct-request-reply|guaranteed-request-reply> <host:port> <vpn> <user> [pass]`) that Implement mode runs per generation stage against a reachable broker.
- `jcsmp/jcsmp-guaranteed-publisher-sample.java`: best-practices publisher sample (basic-auth connect, PERSISTENT publish to a topic, graceful shutdown).
- `jcsmp/jcsmp-guaranteed-subscriber-sample.java`: best-practices consumer sample (provisions a durable queue + topic subscription, CLIENT-ack flow).
