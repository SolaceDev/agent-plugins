# JCSMP API

The JCSMP entry file of the Solace API development skill. The Solace Messaging API for JCSMP is published as `com.solacesystems:sol-jcsmp`. This file detects the developer's intent and selects the right JCSMP mode; the detailed guidance lives in the lazy-loaded mode files under `jcsmp/`.

The cross-cutting invariants (content sourcing, WebFetch-on-demand doc grounding, and no hardcoded versions) are owned by the umbrella `SKILL.md`. Apply them here; this file does not restate them. The JCSMP-specific points below are the coordinate to depend on and the canonical pages to ground in.

## Mode Detection

Determine the user's intent and enter the appropriate mode:

| User intent | Mode | What to do |
|---|---|---|
| "Help me choose the Solace JCSMP messaging pattern for a new app" / "Topic or queue?" / "What delivery semantics should I use?" | **Design** | Read `jcsmp/design-mode.md` |
| "Build me a Solace JCSMP publisher/consumer app" / "Generate a Maven JCSMP project" / "Publish to a topic and consume from a queue" | **Implement** | Read `jcsmp/implement-mode.md` |
| "Build a web app / dashboard / service that connects to Solace", Spring Boot, or any embedded shape where the JCSMP layer lives inside a larger application | **Implement** | Read `jcsmp/implement-mode.md`; generate the messaging layer per its leaf rules and verify with the `app` stage of `verify.sh` (its Step 5) |
| "My JCSMP app is throwing on connect" / "Why is my consumer not binding the queue?" | **Debug** | Debug mode is not yet available in this release. Redirect the user to the canonical Solace JCSMP troubleshooting documentation: [JCSMP API Home](https://docs.solace.com/API/Messaging-APIs/JCSMP-API/jcsmp-api-home.md). Do not generate debugging guidance from memory. |

If unclear, default to **Design**. Understand the messaging problem before generating code.

Two gates hold on every path:

- **Implement mode opens with a mandatory door question.** Ask Quickstart, Solace Suggested, or Custom — before you request or accept ANY broker details (`jcsmp/implement-mode.md` Step 0). Ask it even when a `config.json` with credentials already exists, even when a local broker is already running, and even when the requested app is bigger than the canonical generated shape; none of those answers the question.
- **Environment discovery never answers a question.** A running broker container, an existing config file, or found credentials are facts to report, not answers to consume. Report what you found, then still ask which broker the developer wants to target.

## JCSMP coordinate

The single Solace dependency for this API is `com.solacesystems:sol-jcsmp`. Use the groupId `com.solacesystems` exactly; the shorter `com.solace` form does not resolve on Maven Central. Resolve the latest release at generation time (the no-hardcoded-versions invariant); never pin a `sol-jcsmp` version in skill content. Resolve it from the authoritative metadata with exactly this command, and read the `<release>` element:

```bash
curl -s https://repo1.maven.org/maven2/com/solacesystems/sol-jcsmp/maven-metadata.xml \
  | grep -oE '<release>[^<]+</release>'
```

Do NOT read the version from the legacy solrsearch index (`search.maven.org/solrsearch`): it lags behind the repository metadata and reports a stale `latestVersion`.

## Canonical doc links

JCSMP-specific grounding. Each page below is a live `docs.solace.com` `.md` URL, WebFetched on demand; the live exceptions are listed separately because they have no `docs.solace.com` `.md` form.

- [JCSMP API Home](https://docs.solace.com/API/Messaging-APIs/JCSMP-API/jcsmp-api-home.md): entry point for the JCSMP API.
- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md): documented best practices for JCSMP applications.
- For the broader Solace grounding, see the shared [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md) page.

Live exceptions (these have no `docs.solace.com` `.md` form; use the live URL directly):

- [JCSMP Javadoc](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/index.html)
- [JCSMP API Release Notes](https://products.solace.com/download/JAVA_API_RN) (the `JAVA_API_RN` download serves the JCSMP release notes; it is NOT the newer Solace Messaging API for Java)
- [sol-jcsmp on Maven Central](https://central.sonatype.com/artifact/com.solacesystems/sol-jcsmp)

## Mode and reference files (read on-demand only)

- `jcsmp/prerequisites.md`: broker acquisition (route here first if the developer has no reachable broker).
- `jcsmp/design-mode.md`: choose the messaging pattern/topology before generating code.
- `jcsmp/implement-mode.md`: generate a runnable Maven JCSMP project (any leaf, any app shape); its Step 4 dispatches onto the per-leaf wiring files below.
- `jcsmp/implement-guaranteed-pubsub.md`: the Guaranteed Pub/Sub leaf wiring (read from implement-mode Step 4).
- `jcsmp/implement-direct-pubsub.md`: the Direct Pub/Sub leaf wiring (read from implement-mode Step 4).
- `jcsmp/implement-request-reply.md`: both Request-Reply leaf wirings (read from implement-mode Step 4).
- `jcsmp/solace-suggested-mode.md`: the leaf-agnostic Solace Suggested overlay (TLS secure session, DMQ on PERSISTENT, HA failover, separate Maven projects) reached from implement-mode Step 0 on the Solace Suggested path.
- `jcsmp/custom-mode.md`: the thin a la carte Custom overlay reached from implement-mode Step 0 on the Custom path; a leaf-aware checklist of the hardening knobs (secure session, DMQ, HA, decoupled projects, admin-provisioned queue) that generates exactly the ticked subset, reusing the Solace Suggested overlay steps for the mapped knobs.
- `jcsmp/verification-checklist.md`: the master template of binary verification checks organized into responsibility groups (delivered by this generation, the developer's responsibility, verified by the round-trip, and generation conformance); Implement mode Step 4 writes a tailored `solace-verification-checklist.md` into each generated project as generation output, and Step 6 reports the same resolution in chat.
- `jcsmp/scripts/verify.sh`: the bundled stage-dispatched run-and-observe verify script (`verify.sh <publisher|consumer|roundtrip|direct|direct-request-reply|guaranteed-request-reply|app> ...`) that Implement mode copies into every generated project and runs against a reachable broker; the `app` stage drives web and embedded shapes through a generated `verify-hooks.sh`.
- `jcsmp/jcsmp-guaranteed-publisher-sample.java`: best-practices publisher sample (basic-auth connect, PERSISTENT publish to a topic, graceful shutdown).
- `jcsmp/jcsmp-guaranteed-subscriber-sample.java`: best-practices consumer sample (provisions a durable queue + topic subscription, CLIENT-ack flow).
