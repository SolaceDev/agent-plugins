# Implement Mode

Generate a complete, runnable Maven JCSMP project from a confirmed design summary. The canonical output is TWO classes (a Publisher and a Subscriber, or a Requestor and a Replier) in ONE Maven project, the canonical solace-samples shape (one project, multiple mains). Each class is a near-verbatim adaptation of its reference sample. Near-verbatim adaptation of two samples is more reliable than merging them into one program, and a real deployment lifts each class into its own service or project (see `verification-checklist.md`; Quickstart does not generate the decoupled two-project form).

Implement mode is NOT only for the canonical shape. A web app, a dashboard, a service, or any embedded shape where the JCSMP layer lives inside a larger application follows the SAME steps: the same door question, the same leaf wiring for the messaging layer, the same generation outputs, and the same verification (the `app` stage of `verify.sh`). Building an off-catalog shape never waives a step in this file.

Work through the steps in order. Read this file's grounding links only as each step needs them. Link the canonical doc and state the decision in one line, never paraphrase doc content. Resolve every dependency version at generation time; pin nothing (SKILL.md Invariant 3). Step 3 defines the logging backend's resolution and its security floor.

## Generation outputs: the file contract

Every Implement run writes ALL of these as generation output, on every door and every app shape. None of them is gated on consent, and none of them waits for the end of the session:

1. The generated classes for the chosen leaf, plus the shared `SolaceConnectionConfig` helper. Every source file starts with the AI-assisted disclaimer header (Step 4).
2. `pom.xml` (or the leaf poms on a decoupled layout) with versions resolved per Step 3.
3. The logging config resource for the resolved backend (Step 3).
4. On Quickstart: `config.example.json` (placeholders) and a `.gitignore` that ignores `config.json` (Step 4).
5. A tailored `solace-verification-checklist.md` at each project root (tailoring rules in Step 6; the WRITE happens here at generation time, like the pom and the classes).
6. A copy of the bundled `scripts/verify.sh` at the project root, plus a generated `verify-hooks.sh` beside it (Step 5), so the verification commands resolve from inside the project.

## Step 0: Ask Quickstart, Solace Suggested, or Custom, before any broker details

This is the FIRST question, and you ask it before you request or accept ANY broker connection details. Ask the developer plainly which of three doors they want: Quickstart, Solace Suggested, or Custom. Ask it as a direct question and wait for the answer. Frame the three doors honestly: Quickstart is a learning and try-it path to see a round-trip work, NOT a deployment target; Solace Suggested is the hardened baseline Solace suggests as a starting point, and Solace suggests it without guaranteeing it is ready for deployment; Custom lets the developer tick exactly which hardening knobs to include and generates that ticked subset and nothing more. Nothing has been generated yet and no credentials have been exchanged yet, so this is the up-front gate. The answer decides WHERE broker connection details belong, and it drives behavior for the rest of Implement mode. Every door generates the same classes (a Publisher and a Subscriber, plus the shared `SolaceConnectionConfig` helper that builds the connection `JCSMPProperties`). They differ in where the credential VALUES live: Solace Suggested puts them ONLY in a gitignored `config.json`, while Quickstart reads `config.json` when present and otherwise falls back to CLI args; Custom follows the Quickstart-shaped `config.json`-or-CLI convention by default, and `custom-mode.md` covers the decoupled-projects case where each project carries its own gitignored `config.json`.

Three things do NOT answer this question, and none of them waives it:

- **An existing `config.json`**, even one that already holds real credentials. It tells you where credentials sit today; it does not tell you which door the developer wants. Ask anyway.
- **A running local broker discovered in the environment.** A discovered container or endpoint is a fact to report, never an answer to consume, neither for the door nor for WHICH broker to target. Report what you found, then ask both questions.
- **An app shape larger than the generated two-class project** (a web app, a dashboard, a service). The doors still govern the messaging layer and the credential placement. Ask anyway.

- **Quickstart (default).** Each class builds its connection through the shared `SolaceConnectionConfig` helper, which reads host, message VPN, username, and password from a `config.json` in the working directory when present and otherwise falls back to CLI args (the upstream `patterns/` samples' CLI-args style). Simplest path to a running round-trip, framed honestly as a learning path rather than a deployment target. Quickstart adds no TLS hardening and has no TLS requirement: it connects with whatever scheme the developer's host carries, and a plaintext `tcp://` dev broker is a normal Quickstart target. The Solace Suggested door's TLS requirement never applies here; do not refuse a `tcp://` host on Quickstart. Generate the stages without per-stage preview gates. Quickstart output fails fast: generation omits the samples' reconnect-tuning block so connect and reconnect settings stay at the JCSMP defaults (the fail-fast rule in Step 4).
- **Solace Suggested.** On the Solace Suggested path, read `solace-suggested-mode.md` and follow the Solace Suggested overlay (TLS, DMQ on PERSISTENT, HA failover, separate projects, and the `config.json` convention); the leaf and sample mechanics, the Step 4 dispatch, and the fixed `VERIFY:` contract stay here in implement-mode.md and its leaf files. Solace Suggested is TLS-secure by definition: the session is `tcps://` with server-certificate validation, and it REQUIRES a broker that already has TLS configured (Solace Cloud has it out of the box). There is no non-secure variant of this door and no sub-question to ask; the door question stays a clean three-way choice. When the developer's target broker has no TLS configured (for example, a local dev container), say plainly that Solace Suggested requires a TLS-capable broker and offer the two honest alternatives: Custom with the equivalent knobs ticked minus the secure session (the same hardening, as the developer's explicit choice), or Quickstart for a learning run. Never generate a plaintext session under the Solace Suggested name. The overlay is leaf-agnostic: it rides on whatever `Pattern` leaf the design summary chose and only adds the Solace Suggested deltas. It is what Solace suggests as a hardened baseline, not a guarantee that the output is ready for deployment.
- **Custom.** On the Custom path, read `custom-mode.md` and follow it. Custom opens a SINGLE up-front multi-select checklist of the applicable hardening knobs, captured once, not a sequence of per-knob questions; generation then applies exactly the ticked subset and nothing more. Nothing ticked lands a Quickstart-shaped base app, everything ticked lands about the Solace Suggested shape, and each ticked knob layers one Suggested delta on top of that floor. `custom-mode.md` owns the leaf-aware checklist content and the knob application; the leaf and sample mechanics, the Step 4 dispatch, and the fixed `VERIFY:` contract stay here in implement-mode.md and its leaf files, exactly as they do on the Solace Suggested path.

State plainly, grounded in the docs, that a real deployment lifts each class into its own service or project. Every door generates the same two classes in one project, unless the developer opts into decoupled projects on Solace Suggested or Custom; lifting each into its own project is the developer's next step beyond this generated project. See [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md).

Confirm which door the developer wants before continuing. Basic username/password is the only auth this journey generates; ground it in [Defining Client Authentication](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Defining-Client-Authentication.md). On the Solace Suggested and Custom paths, the overlay file carries the `config.json` convention; no door gates code generation behind a per-stage confirmation.

## Step 1: Confirm broker access and grounding

With the door chosen, confirm the developer has access to a reachable broker, and confirm WHICH broker they want to target. Confirm that they HAVE one; do NOT ask them to paste connection details into the chat. When Design mode already confirmed broker access in this session (its Step 0), do not re-ask whether a broker exists; ask only WHICH broker to target. A broker discovered in the environment (a running container, an existing config file) is a fact to report while asking, never an answer. Where the credential VALUES live depends on the Step 0 door: for Solace Suggested they go ONLY into the gitignored `config.json`; for Quickstart they reach the app through a `config.json` when present, otherwise as CLI args at run time. The skill itself never needs the values typed into the chat (see the honesty rule in Step 5: if the values are placeholders or withheld, compile only and hand the developer the exact commands to run against their own broker).

If the developer has no reachable broker, route through `prerequisites.md` first (broker acquisition, Solace Cloud recommended), then return here.

Grounding catch-up: if the design summary names a page in its Grounding docs field that this session has not WebFetched, fetch it now before generating against it (SKILL.md Invariant 2; an unfetched citation is not grounding).

## Step 2: Establish the design as the input contract

Implement mode works from the unified design summary of eight fields: Pattern, Delivery, Access type, Topic, Consumption endpoint, Auth, Broker, Grounding docs. The `Pattern` field is the discriminated leaf string (one of the six Design-mode leaves), `Consumption endpoint` carries what used to be the fixed `Queue` field (for the guaranteed pub/sub journey this resolves to a durable queue plus topic subscription), and `Access type` is surfaced explicitly.

The summary is a HARD precondition (the design-contract gate in `jcsmp.md`): Implement mode never proceeds past this step without one, and never invents design values. Exactly three sources satisfy it:

- A summary Design mode confirmed in this session: use it as-is, with no re-confirm.
- An explicit summary the developer supplied in chat (the eight fields or an equivalent statement of them): use it directly. If it omits a derivable field, derive it rather than asking (the topic via the solace-topic-best-practices skill, the queue name via the `q.`-prefixed convention), the same way Design mode does; only the Pattern and payload genuinely need the developer's input. An experienced developer is not required to walk the design tree; supplying the summary satisfies the contract.
- A saved `solace-design.md` in the developer's project: read it, restate it in one line, and proceed on their confirm.

If none of the three exists, do NOT collect the fields piecemeal here and do NOT proceed: route through Design mode (`design-mode.md`) and return with its confirmed summary. This holds even when the request sounds like a build. A prose build request that mentions pattern details is not a contract; Design mode's fully-specified path resolves it in ONE confirm (derive the summary, echo it, confirm). The only carve-out is the topology rule in `jcsmp.md`: a mechanical edit to an existing app (no topology change) proceeds without a summary, while a topology-changing edit re-enters Design mode to re-confirm the affected fields first.

Treat the confirmed values (Pattern, topic string, `Consumption endpoint`, payload) as the requirements for everything that follows. The discriminated `Pattern` field carries the developer's chosen leaf, and Step 4 dispatches generation onto that leaf: it reads only the leaf file and sample pair the chosen leaf needs. Read the design as the input contract and do not reject a summary because its `Pattern` is not pub/sub.

## Step 3: Resolve the versions, then write the pom and Maven layout

### Resolve the latest sol-jcsmp version at generation time

Never hardcode a `sol-jcsmp` version in this skill or carry one in your memory. Resolve the latest General Availability release from the AUTHORITATIVE Maven Central metadata at generation time:

```bash
curl -s https://repo1.maven.org/maven2/com/solacesystems/sol-jcsmp/maven-metadata.xml \
  | grep -oE '<release>[^<]+</release>'
```

The `<release>` element is the authoritative latest GA version. Do NOT read the version from the legacy solrsearch index (`search.maven.org/solrsearch`), which lags behind the metadata and can report a stale `latestVersion`. As an alternative, `mvn versions:use-latest-releases` (or the agent's native Maven resolution) resolves the same coordinate.

If the network query fails (offline, blocked, or the metadata is unreachable), do not guess a version. Fall back gracefully: ask the developer for the version they want, or leave Maven to resolve it via its own dependency resolution, and tell the developer which path you took.

### Write pom.xml and the standard Maven layout by hand

Write `pom.xml` and the standard `src/main/java/<package>/` layout yourself, grounded in [Building Java Projects with Maven](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Building-Projects-With-Maven.md). There is no bundled pom template and no archetype.

- **Single Solace dependency: `com.solacesystems:sol-jcsmp`.** Use the groupId `com.solacesystems` exactly. The Maven doc shows the groupId as `com.solace` (without the `systems` suffix), which is WRONG and does not resolve on Maven Central; always use `com.solacesystems`. Confirm the artifact at [Maven Central](https://central.sonatype.com/artifact/com.solacesystems/sol-jcsmp).
- **No `solsuite` aggregate pom.** It was dropped at 10.29; depend on `sol-jcsmp` directly.
- **Logging: one working backend, resolved like every other dependency; never pinned; never silenced.** The reference samples log through log4j2 (`LogManager`, `Logger`), so the DEFAULT backend is log4j2 (`org.apache.logging.log4j:log4j-api` plus `log4j-core`), and neither generated class compiles without a backend wired. Four outcome rules, in force on every door and every app shape:
  1. **Resolve, do not pin.** Resolve the backend's version at generation time from the authoritative Maven metadata, the same `maven-metadata.xml` mechanism `sol-jcsmp` uses. One caution: the log4j metadata's `<release>` element can report a pre-release of the next major (a 3.x alpha or beta). The samples target the log4j2 2.x API, so resolve the HIGHEST STABLE 2.x from the metadata's `<versions>` list instead, excluding any alpha, beta, or RC: `curl -s https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/maven-metadata.xml | grep -oE '<version>2[^<]*</version>' | grep -viE 'alpha|beta|rc' | tail -1`.
  2. **Security floor, enforced as a check.** Any `log4j-core` on the classpath, including one dragged in transitively, MUST be at or above `2.17.1`: versions below it carry the critical Log4Shell vulnerabilities (CVE-2021-44228 family). The `verify.sh` preflight and the verification checklist test this floor; it is a check on the resolved result, never a pin.
  3. **A framework's own backend wins.** When the app is built on a framework that ships its own SLF4J backend (for example Spring Boot ships Logback), use the framework's backend instead of adding log4j2. NEVER put two logging backends on one classpath. Adapt the samples' log4j2 (`LogManager`/`Logger`) usage to the SLF4J API in that case, and hold the same floor discipline for that backend's own CVE floors.
  4. **Config file, visible Solace loggers.** Whatever the backend, generate its configuration resource on the classpath (`src/main/resources/log4j2.xml` for log4j2; the framework's equivalent otherwise) with a console appender, so diagnostics reach stdout. NEVER silence the Solace API's own logging: the `com.solacesystems` loggers stay at INFO or lower-threshold, because they carry the connect and reconnect diagnostics the developer needs most when something breaks.
- **Java target:** Java 11 by default. It is the minimum the samples support, and they compile on 11 and up. Set `maven-compiler-plugin` to `release` 11; use a higher release only if the developer's environment requires it. groupId, artifactId, and package naming are your discretion.

Keep this skill version-free. In any illustrative pom snippet, write every dependency version as a placeholder, never a concrete number:

```xml
<dependency>
  <groupId>com.solacesystems</groupId>
  <artifactId>sol-jcsmp</artifactId>
  <version>RESOLVED_AT_GENERATION_TIME</version>
</dependency>
```

## Step 4: Dispatch on the Pattern leaf, then generate the classes

Generation is keyed on the `Pattern` leaf read in Step 2. Each leaf has its own wiring file, which names its sample pair; read ONLY the leaf file the design chose, and do not read samples for a leaf the design did not choose. The six discriminated leaf strings (spelled exactly as Design mode emits them) dispatch as follows:

| `Pattern` leaf (verbatim) | Leaf wiring file | Sample pair it reads |
|---------------------------|------------------|----------------------|
| `Guaranteed Pub/Sub (single service, exclusive)` | `implement-guaranteed-pubsub.md` | `jcsmp-guaranteed-publisher-sample.java` + `jcsmp-guaranteed-subscriber-sample.java` |
| `Guaranteed Pub/Sub (single service, non-exclusive)` | `implement-guaranteed-pubsub.md` | `jcsmp-guaranteed-publisher-sample.java` + `jcsmp-guaranteed-subscriber-sample.java` |
| `Guaranteed Pub/Sub (fan-out)` | `implement-guaranteed-pubsub.md` | `jcsmp-guaranteed-publisher-sample.java` + `jcsmp-guaranteed-subscriber-sample.java` |
| `Direct Pub/Sub` | `implement-direct-pubsub.md` | `jcsmp-direct-publisher-sample.java` + `jcsmp-direct-subscriber-sample.java` |
| `Request-Reply (Direct)` | `implement-request-reply.md` | `jcsmp-direct-requestor-sample.java` + `jcsmp-direct-replier-sample.java` |
| `Request-Reply (Guaranteed)` | `implement-request-reply.md` | `jcsmp-guaranteed-requestor-sample.java` + `jcsmp-guaranteed-replier-sample.java` |

**Every leaf also generates the shared connection helper.** In addition to its pattern pair, each leaf reads `jcsmp-solace-connection-config.java` and generates it as a `SolaceConnectionConfig` class in the project. Each pattern class builds its basic-auth connection `JCSMPProperties` by calling `SolaceConnectionConfig.load(args, APP_NAME).toSessionProperties()` rather than setting `host`/`vpn_name`/`username`/`password` inline. The helper passes every flat string key in `config.json` through to `JCSMPProperties.setProperty` (host, vpn_name, and username are required; password is optional) and otherwise falls back to the CLI args, so it adds NO JSON-library dependency and keeps the single `sol-jcsmp` dependency intact. Like every generated class it carries the AI-assisted disclaimer header and keeps its imports explicit.

**Disclaimer header on EVERY generated source file.** Stamp the top of each generated source file (every class, in every shape; the pom is exempt as it is not source) with this exact line: `AI-assisted code. Review before production use.` Directly below it, add a pointer to the tailored verification checklist this generation writes into the project: `See the verification checklist: solace-verification-checklist.md`. That tailored `solace-verification-checklist.md` ships inside the generated project (this Step writes it as generation output), so the disclaimer points each generated file at the local checklist beside it rather than at a remote copy. Stamp the header AT WRITE TIME, as the first content of each new file, not as a later pass. The reference samples themselves carry NO disclaimer; that rule is the inverse. This rule is SKILL.md Invariant 4 and applies to every leaf's generated classes AND to custom or embedded builds that adapt the samples outside the canonical two-class shape.

**Embedded and web-app shapes.** When the JCSMP layer sits inside a larger application (a web dashboard, a REST service, a framework app), the chosen leaf's wiring still governs the messaging layer: the same connection helper, the same session/flow/reconnect/ACK handlers, the same `VERIFY:` markers emitted through a stdout-reaching `trace(...)`, and the disclaimer header on every generated file, messaging or not. The `VERIFY:` markers are NOT demo harness; they stay in the messaging layer whatever the shape, because the `app` verify stage (Step 5) greps for them in the app's captured output.

**Write the verification artifacts in this same step.** Alongside the classes and the pom, write: the tailored `solace-verification-checklist.md` at each project root (tailoring rules in Step 6; the write is generation output with NO consent gate), a copy of the bundled `scripts/verify.sh` at the project root, and the generated `verify-hooks.sh` (Step 5 defines its contract). A session that ends early still leaves the checklist and the verify entry points in the project.

### Adapting a sample to a variant of its leaf

The samples are grounded REFERENCE for the API idioms, not templates that must be reproduced line for line. Near-verbatim adaptation is the default WHEN the design summary matches the leaf's base shape: copying the vetted source shape is more reliable than inventing one. But a leaf is a pattern FAMILY, and Design mode may hand you a VARIANT of it, recorded in the summary's topology fields (for example a `Consumption endpoint` that reads `durable reply queue + FlowReceiver` instead of the default `temporary reply queue + FlowReceiver`). When the summary describes a variant, ADAPT the sample's idioms to honor the approved design. Do NOT treat the sample as a spec that overrides the approved design, do NOT snap the variant back to the sample's default shape, and do NOT frame the deviation as a "conflict" with the skill: the approved design is the contract, and the sample is the reference you build it from.

Hold every invariant while adapting: keep the shared connection helper, the session, flow, and reconnect handlers, the publish ACK/NACK handler, explicit single-class imports, the disclaimer header, and the leaf's `VERIFY:` markers. Any NEW named entity the variant introduces (an async reply listener, a durable reply endpoint, a redelivery setting) must be traceable to a canonical Solace source per Invariant 1: WebFetch the grounding page for it, the same way the base leaf grounds its idioms. A verified variant is fully first-class: it carries only the standard AI-assisted disclaimer, with no extra "hand-built" or "off-spec" caveat that would read as risky freelancing.

**Guardrail: variant versus off-catalog.** Adapt freely WITHIN the chosen pattern family when the change is a topology or reliability knob that the canonical docs ground and that still compiles, emits the leaf's `VERIFY:` markers, and is verifiable by a `verify.sh` stage (durable versus temporary reply queue, an async versus blocking reply listener, exclusive versus non-exclusive access, redelivery handling). If a requirement leaves the JCSMP topic/queue publish and subscribe model, or needs behavior the canonical docs cannot ground, STOP and tell the developer it is off-catalog rather than inventing it.

**Preserve the marker contract.** A variant adapts the SHAPE, never the marker strings. It MUST still emit its leaf's `VERIFY:` markers, character for character, so the fixed `verify.sh` stages drive it unchanged (this is exactly why a durable-reply-queue requestor still prints `VERIFY: REPLY_RECEIVED`). Never rename or drop a marker to fit a variant.

**Demo harness is not application logic.** The samples carry demo-harness elements that exist only to make a standalone run observable: the ENTER-to-quit `System.in` loop, the once-per-second stats-printing thread, the pacing `Thread.sleep` between sends, and the rotating example payload. When the developer's request describes a real application domain, do NOT carry these into the generated classes: replace the example payload and send cadence with the application's real messages and triggers, and keep only the lifecycle pieces the leaf's steps call for (the SIGINT hook on the long-running role, the self-exit on the foreground role). When the request IS a demo or a try-it run, keeping the harness is fine. Two things are NOT demo harness: the `trace(...)` narration method (keep it; applications replace its single body to route narration to their logging or reporting system) and the `VERIFY:` markers, which are emitted through `trace(...)` like every other status line. Because `verify.sh` greps captured stdout for the markers and for the shutdown hook's `Shutdown signal received` proof line (both flow through `trace(...)`), the generated `trace(...)` body MUST stay `System.out.println` through the Step 5 verification stages; rerouting that body to a logger or reporting system is the developer's step AFTER the verify stages pass, never part of generation. Never treat the logging backend (Step 3) as demo harness: the backend and its config file ship in every generated app.

**Comments follow their code.** The samples' comments are part of the reference, not decoration: they carry the documented best practices (the session-independent `createMessage`, the binary payload and the legacy `XML` naming, `getData()` versus `getBytes()`, registering a session event handler at session creation, the ACK/NACK handling options, the correlation-key rule) into the code a developer reads long after this session. Generation carries them under ONE contract, on every door and every app shape:

- A generated class that carries a construct from a sample (near-verbatim or adapted) carries that construct's comment too, with only the names adapted to the application. Never strip a comment from a construct you kept.
- A dropped construct (the demo harness above, or a part the leaf does not use) takes its comment with it. Never orphan a comment onto unrelated code.
- Fresh messaging code with no sample twin (an embedded shape's own wiring) that applies a documented practice gets a SHORT comment naming the practice, in the samples' comment style.
- The one-line comments this skill mandates elsewhere (the Quickstart fail-fast posture below, the `setCorrelationKey` distinction in `implement-request-reply.md`) always apply on their paths; this contract adds to them, it does not replace them.

The tailored checklist's Generation conformance group carries a binary item for this contract, so a run that strips the comments fails its own checklist.

### Quickstart fail-fast channel defaults

On the Quickstart door ONLY, do not carry the reference samples' reconnect-tuning block into the generated classes. The samples configure a `JCSMPChannelProperties` reconnect budget (`reconnectRetries` 20, `connectRetriesPerHost` 3); Quickstart generation OMITS that whole block (the `JCSMPChannelProperties` object and the `CLIENT_CHANNEL_PROPERTIES` setProperty call), leaving connect and reconnect retries and their timeouts at the JCSMP defaults, so a wrong host, port, or credential in this learning setup fails immediately instead of retrying for minutes. Drop ONE short comment at the session-properties build site in each generated class stating that posture: connect and reconnect settings are at the JCSMP defaults so failures surface immediately; see the [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md) page for reconnect tuning beyond Quickstart. The Solace Suggested door is unchanged: it keeps the samples' baseline or upgrades it per `solace-suggested-mode.md` Step 2. Custom follows its floor exception in `custom-mode.md`.

### Scaffold the opt-in autonomous-run config (Quickstart only)

On the Quickstart path only, alongside the pom and the two classes, also generate two small project files so the developer can opt into having the agent run verify.sh for them (Step 5):

- A committed `config.example.json` carrying placeholder values for the same four keys the Solace Suggested path already defines (`host`, `vpn_name`, `username`, `password`, matching the `JCSMPProperties` property-name strings, the constants' values, exactly). The placeholder values must be obviously fake so a committed example can never be mistaken for live credentials: `host` is `tcp://HOST:55555`, `vpn_name` is `YOUR_VPN`, `username` is `YOUR_USERNAME`, `password` is `YOUR_PASSWORD`. Tell the developer they opt in by copying `config.example.json` to `config.json` and filling in their real broker values.
- A `.gitignore` at the generated project root that ignores `config.json`, so the real credential-bearing file is never committed; only the placeholder `config.example.json` is.

This reuses the ONE config.json convention the skill already defines on the Solace Suggested path (Step 0): the same file name `config.json` and the same four keys (`host`, `vpn_name`, `username`, `password`), never a parallel file and never a divergent key set. Both doors read `config.json` through the shared `SolaceConnectionConfig` helper: Solace Suggested reads it directly (no CLI args), and Quickstart reads it when present and otherwise falls back to CLI args. On the Quickstart path a present `config.json` is therefore both the app's connection source and the agent's input for the Step 5 autonomous run.

## Step 5: Verify with verify.sh — on every shape

Verification is a `verify.sh` run, on every door and every app shape. Step 4 copied `scripts/verify.sh` into the project root and generated `verify-hooks.sh` beside it, so every command below resolves from inside the project. An improvised check (curl against the app's own HTTP API, a manual browser click) may TRIGGER traffic, but it NEVER renders the verdict: the verdict is the `VERIFY:` markers plus the script's exit code, which is what separates "your code is broken" (exit 1) from "your broker is unreachable" (exit 2).

**Live-broker heads-up (every run, every launcher).** Immediately before starting ANY process that will connect to a live broker — a verify.sh stage, `java -jar`, a `mvn` run, a run script — print ONE line that names the broker host and states the actual broker-side effects of this run: the queues it provisions (how many, durable or not), the connections it opens, and the messages it publishes, scaled to the real app (SKILL.md Invariant 6).

### The canonical two-class shape: the six fixed stages

Generate the two classes rather than emitting everything at once, and verify each against the developer's reachable broker. The run story (run the Subscriber first, then the Publisher): the durable queue makes a sequential publish-then-consume safe once the Subscriber has provisioned it. The script compiles the project, starts the Subscriber first via `mvn exec:java@subscriber` (long-running), runs the Publisher via `mvn exec:java@publisher` (which exits on its own after the ack), watches each process's captured output for that stage's `VERIFY:` marker, confirms receipt on the Subscriber, sends SIGINT to the Subscriber to exercise its graceful-shutdown hook, and returns an exit code that classifies the outcome. Each stage waits for a different marker in a different process.

Invoke the matching stage after generating each class, passing the connection params through as positional args (the same `<host:port> <message-vpn> <client-username> [password]` contract the apps use):

1. After generating the Subscriber: `./verify.sh consumer <host:port> <vpn> <user> <pass>`. It waits for `VERIFY: QUEUE_BOUND` in the Subscriber output (queue provisioned, subscribed, flow started).
2. After generating the Publisher: `./verify.sh publisher <host:port> <vpn> <user> <pass>`. NOTE: this stage starts the Subscriber first to bind the queue, so it requires BOTH classes; it waits for `VERIFY: PUBLISH_ACKED` in the Publisher's own output (connect, then publish ACK).
3. At the end: `./verify.sh roundtrip <host:port> <vpn> <user> <pass>`. It starts the Subscriber, runs the Publisher, and waits for `VERIFY: MESSAGE_RECEIVED` in the Subscriber output (a published PERSISTENT message lands on the queue and is consumed and ACKed).

The stages above (`consumer`, `publisher`, `roundtrip`) verify the guaranteed pub/sub leaves. `verify.sh` also accepts three further stage names for the other leaves: `direct` (the Direct Pub/Sub leaf), `direct-request-reply` (the Request-Reply (Direct) leaf), and `guaranteed-request-reply` (the Request-Reply (Guaranteed) leaf). All are runnable. The two request-reply stages are replier-first single round-trips: each starts the Replier first (the readiness gate on `VERIFY: SUBSCRIBED`), then runs the Requestor in the foreground and passes on `VERIFY: REPLY_RECEIVED` in the Requestor log (with `VERIFY: REQUEST_RECEIVED` in the Replier log as richer evidence). The `guaranteed-request-reply` stage differs only in that the Replier provisions a durable request queue (an endpoint-management denial classifies as an environment failure, exit 2) and the Requestor uses a temporary reply queue plus a blocking `flow.receive` rather than the direct `Requestor` convenience.

### Every other shape: the `app` stage and `verify-hooks.sh`

A web app, an embedded service, or any single-process shape that the six fixed stages cannot drive is verified with the `app` stage: `./verify.sh app`. The stage sources the generated `verify-hooks.sh`, which carries the ONLY app-specific facts — how to start the app and how to cause one publish — while the script keeps the universal logic: marker watching, timeouts, environment-signature classification, and the 0/1/2 exit contract. Generation writes `verify-hooks.sh` because the generator just wrote the app and knows both commands:

```bash
# verify-hooks.sh — generated with the project; verify.sh's app stage sources it.
START_CMD='java -jar target/the-app.jar'                                   # starts the app (long-running)
TRIGGER_CMD='curl -s --max-time 10 -X POST localhost:8081/api/orders -d "{\"demo\":1}"'  # causes exactly one publish
READY_MARKER='VERIFY: QUEUE_BOUND'          # marker proving the consuming side is live
PASS_MARKER='VERIFY: MESSAGE_RECEIVED'      # marker proving the round trip
```

Generate `verify-hooks.sh` with ONLY these four assignments. The app stage imports exactly these four names from a child shell and ignores everything else in the file, so an extra variable, a `cd`, or a function never reaches the script's own state; keep the file to the four lines all the same. Give a network trigger its own timeout (the `--max-time` above): the stage also bounds the trigger externally and force-kills one that never returns, but a self-timing trigger leaves cleaner evidence.

The `app` stage starts `START_CMD` in the background and captures its output, waits for `VERIFY: CONNECTED` and then `READY_MARKER` (an environment signature or a timeout before that classifies as exit 2 or exit 1), runs `TRIGGER_CMD` under a bounded deadline (a trigger that never returns is force-killed, and the marker watch still decides the verdict), waits for `PASS_MARKER` in the app's captured output, then stops the app with SIGINT and classifies. Set `READY_MARKER`/`PASS_MARKER` to the chosen leaf's own markers. A curl inside `TRIGGER_CMD` is exactly the right use of curl: it triggers, and the markers judge. On the canonical two-class shape, generate `verify-hooks.sh` too, carrying the classic commands (`START_CMD='mvn -q exec:java@subscriber'`, `TRIGGER_CMD='mvn -q exec:java@publisher'`) so the file documents the same contract everywhere; the fixed stages do not read it.

### Interpret the exit code

- **Exit 0 (stage passes).** The stage milestone appeared and the run tore down cleanly (for the fixed stages that includes the graceful-shutdown proof line and a clean JVM exit, 0 or 130). Advance to the next stage.
- **Exit 1 (code failure).** Compile failed, a marker never appeared, a process died before its milestone, or a run hung and had to be force-killed. Enter the bounded fix loop below.
- **Exit 2 (environment failure).** A doc-traceable JCSMP connection/auth signature appeared (the broker is unreachable or the credentials are wrong). STOP. Do NOT enter the fix loop. The code is not the problem.

**Bounded fix loop (exit 1 only).** Diagnose the captured output, fix the code, and re-run the same stage, up to 3 automatic attempts per stage. After the third failure, stop fixing: summarize exactly what you tried across the attempts and what the captured output shows, then hand the decision to the developer. Report the evidence, not just "it failed".

**Fixes apply without a confirmation gate (all doors).** During the fix loop, apply the fix and re-run the same stage; do not preview the changed lines and ask the developer to confirm before writing them. This holds on the Solace Suggested path too, consistent with Step 0 generating the classes without a per-stage gate.

**Environment-failure path (exit 2).** Report the environment problem with the matched signature line from the captured output as evidence, and do NOT modify the code (a working app against a bad broker or wrong credentials must not be "fixed"). The fix is the developer's broker or credentials.

**Autonomous run from config.json (Quickstart only).** This applies to Quickstart ONLY. On the Quickstart path, run verify.sh for real instead of handing the commands back ONLY when BOTH conditions hold: a `config.json` is present at the generated project root, AND its four values are real, meaning they are NOT the `config.example.json` placeholders (`tcp://HOST:55555`, `YOUR_VPN`, `YOUR_USERNAME`, `YOUR_PASSWORD`). The opt-in is filling in real values, not merely copying the example: a `config.json` that still carries any placeholder value is treated as not-yet-opted-in, so fall through to the honesty-rule path below (compile only, then hand back the stage commands). This keeps a half-finished `cp config.example.json config.json` from triggering a real run against placeholder credentials. When the values are real, read the four (`host`, `vpn_name`, `username`, `password`) from `config.json` and pass them to verify.sh as POSITIONAL CLI args, using the existing `verify.sh <stage> <host:port> <vpn> <user> [password]` contract. Run the three stages in this order: consumer first (it provisions the queue), then publisher, then roundtrip. Interpret each stage's exit code exactly as defined above. Immediately before the live run, print the ONE heads-up line this Step requires — name the host from `config.json` and state the ACTUAL broker-side effects of this run (the queues it provisions, the connections it opens, the messages it publishes) — then run immediately with NO confirmation prompt: a config.json with real values is the consent, so there is no gate. When `config.json` is absent or still holds placeholders, Quickstart keeps the existing behavior (compile only, then hand back the commands), and on that fall-through also point the developer at the autonomous option: tell them you generated a `config.example.json`, and that they can have you run all stages for them by copying it to `config.json`, filling in their real broker values, and asking you to verify. The Solace Suggested path ALSO runs verify.sh under the same real-values consent, but in its two-project mode (the per-role `--subscriber-dir`/`--publisher-dir` flags, each app reading its own `config.json` directly), as `solace-suggested-mode.md` Step 4 defines; the agent does not extract config values into positional args on the Solace Suggested path.

**Honesty rule (no fabricated runs).** If the developer's credentials are placeholders or not provided, do NOT fabricate a successful run. Compile only (for example `mvn -q compile`), report the compile result, and list the exact commands the developer must run against their broker (from the project root, where Step 4 placed the script):

```
./verify.sh publisher <host:port> <vpn> <user> <pass>
./verify.sh consumer  <host:port> <vpn> <user> <pass>
./verify.sh roundtrip <host:port> <vpn> <user> <pass>
```

For an `app`-stage shape, the handed-back command is `./verify.sh app` after the developer fills in `config.json`.

**Close every run with the next steps, stated plainly.** After generating the project, do NOT leave the developer guessing what to do next; end with a short, explicit next-steps message.

- **Quickstart.** Tell the developer, in order: (1) copy `config.example.json` to `config.json` and fill in their real broker values (`host`, `vpn_name`, `username`, `password`); (2) then just ask you to verify, and you will run `verify.sh` for them against their broker (naming the host and side effects first). If they would rather run it themselves, point them at the stage commands above. Make the fill-in-`config.json`-then-ask-me path the recommended one; it is the frictionless path to a verified round-trip. Step 4 already wrote the tailored `solace-verification-checklist.md` into the project root; when you deliver this close-of-run message, invite the developer to add their own items in its Your additional items section and to record any skill-undelivered concerns under Developer-owned items (not delivered by this skill).
- **Solace Suggested.** Same shape as Quickstart, via `verify.sh` two-project mode: fill in each project's gitignored `config.json` with real values, then just ask you to verify and you will run `verify.sh` against the broker with the per-role directory flags (`--subscriber-dir`/`--publisher-dir`, or `--replier-dir`/`--requestor-dir` for request-reply); it reads each `config.json` (no CLI-arg credentials) and needs a TLS-capable broker. If any `config.json` is still placeholder, compile each project and hand back the two-project `verify.sh` command instead. See `solace-suggested-mode.md` Step 4. Then report the verification checklist per Step 6; when you deliver this close-of-run message, invite the developer to add their own items in its Your additional items section and to record any skill-undelivered concerns under Developer-owned items (not delivered by this skill).

### Traps to avoid

- **Improvised verdicts.** Curl against the app's own API, or a browser click, proves the web layer answered; it does not prove the broker round trip, and it loses the exit-code triad. Trigger with whatever fits; judge ONLY by the markers and the verify.sh exit code.
- **Publishing direct-to-queue instead of pub/sub.** Publish to a topic; let the durable queue carry a topic subscription. See [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md).
- **XML or text payload instead of binary.** Default to a binary `BytesMessage` payload. The `XML` in JCSMP type names like `XMLMessage` and `BytesXMLMessage` is legacy API naming, not an XML payload format. Do not conflate them.
- **DIRECT delivery where Guaranteed is required.** Use PERSISTENT delivery for the guaranteed pub/sub journey. See [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md).
- **Hardcoded credentials.** Never inline host, VPN, username, or password. All doors build the connection `JCSMPProperties` through the shared `SolaceConnectionConfig` helper: Solace Suggested reads a gitignored `config.json`; Quickstart reads `config.json` when present and otherwise falls back to CLI args.
- **Hardcoded versions.** Resolve every dependency version at generation time (Step 3); never pin a number in the skill or the generated pom rationale. The log4j2 `2.17.1` Log4Shell floor is a CHECK on the resolved result, not a pin.
- **Wildcard imports.** When adapting the samples, keep every import explicit in ALL generated classes as the reference samples do; never collapse them to a wildcard like `import com.solacesystems.jcsmp.*;`. Wildcard imports are an anti-pattern: they hide which API types the code actually depends on and risk silent collisions as packages evolve.
- **Wrong groupId.** The Maven doc shows `com.solace`; the correct groupId is `com.solacesystems`. The shorter form does not resolve on Maven Central.
- **Running the Publisher before the Subscriber has provisioned the queue.** If the Publisher runs first, the PERSISTENT message is published to a topic the durable queue is not yet subscribed to, so it is silently dropped and never received. The default is NOT to add a Publisher fail-fast that probes for the queue (real producers do not check consumer queues, and it would dilute the near-verbatim shape). Instead, run the Subscriber first: `verify.sh` enforces that ordering, and the `VERIFY: MESSAGE_RECEIVED` marker is the real proof the round-trip worked.
- **Treating a sample as a spec that overrides the approved design.** The samples are grounded reference, not fixed templates. When the design summary records a variant of its leaf (a topology or reliability deviation from the base sample), adapt the sample to the design; do NOT snap the variant back to the sample's default shape, and do NOT report the deviation as a "conflict" with the skill. See "Adapting a sample to a variant of its leaf" above; hold the invariants and the marker contract while adapting, and treat a verified variant as fully first-class.

## Step 6: Tailor the checklist (written at Step 4) and report

Step 4 writes the tailored `solace-verification-checklist.md` into the generated project as generation output. This step defines the tailoring and adds the end-of-run report in chat. At the end of EVERY Implement run, on every door (Quickstart, Solace Suggested, and Custom) and every app shape, report the checklist resolution in chat; the emitted file and the report say the same thing.

**1. Resolve each item to its state for the chosen mode.** Read `verification-checklist.md` (the master template) and resolve each item to its actual state for the door the developer chose and the code that was generated, using the master template's per-mode branch notes. Sort each resolved item into one of the responsibility groups the master template defines:

- **Delivered by this generation**: the chosen mode generated this into the code.
- **Your responsibility (not delivered here)**: the chosen mode did not generate this, so it stays the developer's job.
- **Verified by the round-trip**: the conformance checks the publisher to consumer round-trip already exercises.
- **Generation conformance**: the conformance checks on the generated output itself: the mechanical ones the `verify.sh` preflight reports (the disclaimer header, the logging floor, the version freshness, the recorded verify stage and exit code), plus the comments-follow-their-code item, which is checked by reading the generated classes rather than by the preflight.

An item the chosen mode generated lands under Delivered by this generation; an item that mode did not generate lands under Your responsibility (not delivered here). For example the TLS secure-session item is Delivered by this generation on Solace Suggested and on Custom with the secure-session knob ticked, and it is Your responsibility on Quickstart and on Custom with that knob unticked. Resolve the admin-provisioned-queue item explicitly, not just the session-and-project items: it is Delivered by this generation ONLY on Custom with the admin-provisioned-queue knob ticked (the generated app binds a pre-existing admin-owned queue per `custom-mode.md`), and it is Your responsibility on Quickstart, on Solace Suggested (which gives admin-provisioned-queue guidance only and generates no binding code per `solace-suggested-mode.md`), and on Custom with that knob unticked.

**2. The emitted file's tailored shape.** The emitted file carries the responsibility groups above with each item pre-resolved to its state for this mode (not the master template's branch notes, which exist only for tailoring), then two more sections carried over from the master template:

- **Developer-owned items (not delivered by this skill)**: any item the skill does not deliver (non-Solace, out-of-scope, or otherwise), recorded as developer-owned rather than skill-satisfied, so at a glance a reader sees which items the skill stands behind and which the developer is tracking on their own. A consented deviation (for example dropping the decoupled-projects shape on a single-deliverable ask) is recorded here.
- **Your additional items**: open space for the developer to keep growing the list after the run.

On the decoupled two-project layout (Solace Suggested, and Custom with the decoupled-projects knob ticked) write ONE copy into EACH project root, consistent with each project already carrying its own `config.json` and `.gitignore`; single-project modes get one copy at the project root, parallel to the `solace-design.md` that Design mode writes, so the per-project Solace files read as a set. The emitted checklist is an artifact, not source, so it carries NO AI-assisted disclaimer header; that header is for generated source files, per Step 4.

**3. Report the same resolution in chat.** Tell the developer what landed under each responsibility group and that the tailored `solace-verification-checklist.md` was written into the project (into each project root on the decoupled layout). Record the verify.sh stage that ran and its exit code in the checklist's Generation conformance group; on the compile-only fallback, record that the run was handed back instead.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Building Java Projects with Maven](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Building-Projects-With-Maven.md)
- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [Provisioning a Durable Endpoint](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Provisioning-Durable-End.md)
- [Adding a Topic Subscription](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Adding-Topic-Subscriptio.md)
- [Acknowledging Messages](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Acknowledging-Messages.md)
- [Message Delivery Modes](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Message-Delivery-Modes.md)
- [Defining Client Authentication](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Defining-Client-Authentication.md)

Public URLs (not docs.solace.com doc pages; use the live URL directly):

- [JCSMP Javadoc](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/index.html)
- [JCSMP API Release Notes](https://products.solace.com/download/JAVA_API_RN)
- [sol-jcsmp on Maven Central](https://central.sonatype.com/artifact/com.solacesystems/sol-jcsmp)
