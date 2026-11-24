# Implement Mode

Generate a complete, runnable Maven project for the Solace Java API from a confirmed design summary. The canonical output is TWO classes (a Publisher and a Subscriber) plus the shared `SolaceConnectionConfig` helper in ONE Maven project (one project, multiple mains). Each class is a near-verbatim adaptation of its reference sample. Near-verbatim adaptation of two samples is more reliable than merging them into one program, and a real deployment lifts each class into its own service or project.

Implement mode is NOT only for the canonical shape. A web app, a dashboard, a service, or any embedded shape where the Java API layer lives inside a larger application follows the SAME steps: the same door question, the same leaf wiring for the messaging layer, the same generation outputs, and the same verification fallback. Building an off-catalog shape never waives a step in this file.

Work through the steps in order. Read this file's grounding links only as each step needs them. Link the canonical doc and state the decision in one line, never paraphrase doc content. Resolve every dependency version at generation time; pin nothing in skill content (SKILL.md Invariant 3).

**Coverage in this release.** Implement mode generates the three Guaranteed Pub/Sub leaves on the Quickstart door, in the non-blocking shape (the non-blocking `publish()` with a publish receipt listener, and `receiveAsync()`). The Direct Pub/Sub and Request-Reply (Direct) leaves (Step 4) and the Solace Suggested and Custom doors (Step 0) are not yet available for the Java API. The Java API does not bundle a verify script yet, so every run ends in the compile-only fallback of Step 5.

## Generation outputs: the file contract

Every Implement run writes ALL of these as generation output, on every app shape. None of them is gated on consent, and none of them waits for the end of the session:

1. The generated classes for the chosen leaf, plus the shared `SolaceConnectionConfig` helper. Every source file starts with the AI-assisted disclaimer header (Step 4).
2. `pom.xml` with versions resolved per Step 3.
3. The logging config resource (Step 3).
4. `config.example.json` (placeholders) and a `.gitignore` that ignores `config.json` (Step 4).
5. A tailored `solace-verification-checklist.md` at the project root (tailoring rules in Step 6; the WRITE happens here at generation time, like the pom and the classes).

There is no `verify.sh` and no `verify-hooks.sh` in a Java API project yet. Do not copy the JCSMP `verify.sh` into a Java API project: its preflight and its failure signatures are written for JCSMP.

## Step 0: Ask Quickstart, Solace Suggested, or Custom, before any broker details

This is the FIRST question, and you ask it before you request or accept ANY broker connection details. Ask the developer plainly which of three doors they want: Quickstart, Solace Suggested, or Custom. Ask it as a direct question and wait for the answer. Frame the three doors honestly: Quickstart is a learning and try-it path to see a round-trip work, NOT a deployment target; Solace Suggested is the hardened baseline Solace suggests as a starting point (TLS, dead message queue eligibility, HA failover, separate projects), and Solace suggests it without guaranteeing it is ready for deployment; Custom lets the developer tick exactly which hardening knobs to include. State in the same question that Solace Suggested and Custom are not yet available for the Java API in this release.

Three things do NOT answer this question, and none of them waives it:

- **An existing `config.json`**, even one that already holds real credentials. Ask anyway.
- **A running local broker discovered in the environment.** Report what you found, then ask both this question and which broker to target.
- **An app shape larger than the generated two-class project** (a web app, a dashboard, a service). The doors still govern the messaging layer and the credential placement. Ask anyway.

The doors:

- **Quickstart.** Each class builds its connection through the shared `SolaceConnectionConfig` helper, which reads the connection properties from a `config.json` in the working directory when present and otherwise falls back to the command-line arguments (`<host:port> <message-vpn> <client-username> [password]`). Quickstart adds no TLS hardening and has no TLS requirement: it connects with whatever scheme the developer's host carries, and a plaintext `tcp://` dev broker is a normal Quickstart target. Generate the classes without per-stage preview gates. Quickstart output fails fast (the fail-fast rule in Step 4).
- **Solace Suggested or Custom.** Not yet available for the Java API. Say so plainly, and do not describe the hardened doors beyond the one-line framing in the Step 0 question. Offer the developer two honest choices: Quickstart now, framed as a learning run, or stop here with the design saved to `solace-design.md` for when the hardened doors are available. Never generate a Quickstart project under the Solace Suggested or Custom name.

State plainly that a real deployment lifts each class into its own service or project. Basic username/password is the only auth this journey generates; ground it in the authentication section of [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md).

## Step 1: Confirm broker access and grounding

With the door chosen, confirm the developer has access to a reachable broker, and confirm WHICH broker they want to target. Confirm that they HAVE one; do NOT ask them to paste connection details into the chat. When Design mode already confirmed broker access in this session (its Step 0), do not re-ask whether a broker exists; ask only WHICH broker to target. A broker discovered in the environment is a fact to report while asking, never an answer. The credential VALUES reach the app through a `config.json` when present, otherwise as command-line arguments at run time; the skill itself never needs the values typed into the chat.

If the developer has no reachable broker, route through `prerequisites.md` first, then return here.

Grounding catch-up: if the design summary names a page in its Grounding docs field that this session has not WebFetched, fetch it now before generating against it (SKILL.md Invariant 2).

## Step 2: Establish the design as the input contract

Implement mode works from the unified design summary of eight fields that `design-mode.md` defines: Pattern, Delivery, Access type, Topic, Consumption endpoint, Auth, Broker, Grounding docs. The `Pattern` field is one of the five Java API leaf strings.

The summary is a HARD precondition (the design-contract gate in `java-messaging.md`): Implement mode never proceeds past this step without one, and never invents design values. Exactly three sources satisfy it:

- A summary Design mode confirmed in this session: use it as-is, with no re-confirm.
- An explicit summary the developer supplied in chat (the eight fields or an equivalent statement of them): use it directly. If it omits a derivable field, derive it rather than asking (the topic via the solace-topic-best-practices skill, the queue name via the `q.`-prefixed convention), the same way Design mode does.
- A saved `solace-design.md` in the developer's project: read it, restate it in one line, and proceed on their confirm. A saved summary titled `Solace JCSMP Design Summary` belongs to the JCSMP API: say so and ask whether the developer wants a Java API project from it, then re-confirm it through Design mode, because a JCSMP leaf such as `Request-Reply (Guaranteed)` has no Java API leaf in this skill.

If none of the three exists, route through Design mode (`design-mode.md`) and return with its confirmed summary. The only carve-out is the topology rule in `java-messaging.md`: a mechanical edit to an existing app proceeds without a summary.

## Step 3: Resolve the versions, then write the pom and Maven layout

### Resolve the latest solace-messaging-client version at generation time

Never hardcode a `solace-messaging-client` version in this skill or carry one in your memory. Resolve the latest release from the AUTHORITATIVE Maven Central metadata at generation time:

```bash
curl -s https://repo1.maven.org/maven2/com/solace/solace-messaging-client/maven-metadata.xml \
  | grep -oE '<release>[^<]+</release>'
```

The `<release>` element is the authoritative latest release. Do NOT read the version from the legacy solrsearch index (`search.maven.org/solrsearch`), from a doc page's dependency snippet, or from the upstream samples repository.

If the network query fails (offline, blocked, or the metadata is unreachable), do not guess a version. Ask the developer for the version they want, and tell them that Maven Central was unreachable, so the version is their choice and not a resolved release.

### Write pom.xml and the standard Maven layout by hand

Write `pom.xml` and the standard `src/main/java/<package>/` layout yourself. There is no bundled pom template and no archetype.

- **Single Solace dependency: `com.solace:solace-messaging-client`.** Use the groupId `com.solace` exactly. Do NOT declare `com.solacesystems:sol-jcsmp`: the Java API brings the JCSMP version it needs as its own transitive dependency.
- **Logging: log4j2, resolved like every other dependency; never pinned; never silenced.** The reference samples log through log4j2 (`LogManager`, `Logger`), and the API itself logs through Jakarta Commons Logging. WebFetch [Configuring Logging in the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md) and follow its log4j2 dependency list: `org.apache.logging.log4j:log4j-api`, `log4j-core`, and `log4j-jcl`, all three at one version. Without `log4j-jcl` the API's own log lines do not reach log4j2. Four rules:
  1. **Resolve, do not pin.** The log4j metadata's `<release>` element can report a pre-release of the next major. Resolve the HIGHEST STABLE 2.x from the metadata's `<versions>` list instead: `curl -s https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/maven-metadata.xml | grep -oE '<version>2[^<]*</version>' | grep -viE 'alpha|beta|rc' | tail -1`. Never copy the version on the doc page.
  2. **Security floor, enforced as a check.** Any `log4j-core` on the classpath MUST be at or above `2.17.1`: versions below it carry the critical Log4Shell vulnerabilities (CVE-2021-44228 family). Step 5 checks this floor; it is a check on the resolved result, never a pin.
  3. **A framework's own backend wins.** When the app is built on a framework that ships its own logging backend, use that backend instead of adding log4j2, and adapt the samples' `LogManager`/`Logger` usage to it. NEVER put two logging backends on one classpath.
  4. **Config file, visible Solace loggers.** Generate `src/main/resources/log4j2.xml` with a console appender, so diagnostics reach stdout. NEVER silence the API's own logging: the `com.solace` and `com.solacesystems` loggers stay at INFO or a lower threshold, because they carry the connect and reconnect diagnostics the developer needs most when something breaks.
- **Java target.** Set `maven.compiler.release` to 11 by default; the samples compile on 11 and up. Check `java -version` first. Solace supports the JDK versions that are in active support (`prerequisites.md`): when the developer's JDK is out of support, tell them and link that file's Supported environments section; do not choose a JDK for them. groupId, artifactId, and package naming are your discretion.
- **Named exec executions.** The pom declares the `exec-maven-plugin` executions the leaf file defines, with the plugin version resolved from its own Maven metadata `<release>`.

Keep this skill version-free. In any illustrative pom snippet, write every dependency version as a placeholder, never a concrete number:

```xml
<dependency>
  <groupId>com.solace</groupId>
  <artifactId>solace-messaging-client</artifactId>
  <version>RESOLVED_AT_GENERATION_TIME</version>
</dependency>
```

## Step 4: Dispatch on the Pattern leaf, then generate the classes

Generation is keyed on the `Pattern` leaf read in Step 2. Read ONLY the leaf file the design chose, and do not read samples for a leaf the design did not choose.

| `Pattern` leaf (verbatim) | Leaf wiring file | Sample pair it reads |
|---------------------------|------------------|----------------------|
| `Guaranteed Pub/Sub (single service, exclusive)` | `implement-guaranteed-pubsub.md` | `java-messaging-guaranteed-publisher-sample.java` + `java-messaging-guaranteed-subscriber-sample.java` |
| `Guaranteed Pub/Sub (single service, non-exclusive)` | `implement-guaranteed-pubsub.md` | `java-messaging-guaranteed-publisher-sample.java` + `java-messaging-guaranteed-subscriber-sample.java` |
| `Guaranteed Pub/Sub (fan-out)` | `implement-guaranteed-pubsub.md` | `java-messaging-guaranteed-publisher-sample.java` + `java-messaging-guaranteed-subscriber-sample.java` |
| `Direct Pub/Sub` | not yet available | none |
| `Request-Reply (Direct)` | not yet available | none |

**The two direct leaves stop here.** Say in one line that generating a Java API project for this leaf is not yet available in this release. Offer to save the confirmed summary to `solace-design.md` if it is not saved yet, and stop. Do not read the direct samples and do not adapt them by hand: without a wiring file there is no marker contract and no conformance check for the result. Do not offer to write the code another way (from scratch, "outside the generator", with JCSMP instead, or as unverified code), and do not write it when the developer asks again: the stop is the ENTIRE answer, the same way the Debug redirect is. Point the developer at the saved design and at the [Java API tutorials](https://tutorials.solace.dev/java/) instead.

**The blocking samples are not a generated shape yet.** The folder also holds blocking samples (`*-blocking-sample.java`). No wiring file reads them in this release. When the developer asks for the blocking shape, say in one line that this release generates the non-blocking shape only, and offer that shape or a stop with the design saved.

**Every leaf also generates the shared connection helper.** Read `java-messaging-solace-connection-config.java` and generate it as a `SolaceConnectionConfig` class in the project. Each class builds its service `Properties` by calling `SolaceConnectionConfig.load(args, APP_NAME).toServiceProperties()` and passes it to `MessagingService.builder(ConfigurationProfile.V1).fromProperties(...)`, rather than setting the host, VPN, username, or password inline. The helper carries its own minimal `config.json` reader, so it adds NO JSON-library dependency.

**Disclaimer header on EVERY generated source file.** Stamp the top of each generated source file (every class, in every shape; the pom is exempt as it is not source) with this exact line: `AI-assisted code. Review before production use.` Directly below it: `See the verification checklist: solace-verification-checklist.md`. Write the two lines as a comment that is the first content of the file, before the `package` statement. Stamp the header AT WRITE TIME, not as a later pass. The reference samples themselves carry NO disclaimer; that rule is the inverse. This rule is SKILL.md Invariant 4.

**Embedded and web-app shapes.** When the Java API layer sits inside a larger application, the chosen leaf's wiring still governs the messaging layer: the same connection helper, the same service, receiver, and publisher listeners, the same `VERIFY:` markers emitted through a stdout-reaching `trace(...)`, and the disclaimer header on every generated file, messaging or not. The `VERIFY:` markers are NOT demo harness.

**Write the verification artifacts in this same step.** Alongside the classes and the pom, write the tailored `solace-verification-checklist.md` at the project root (Step 6), `config.example.json`, and `.gitignore`. A session that ends early still leaves the checklist in the project.

### Adapting a sample to a variant of its leaf

The samples are grounded REFERENCE for the API idioms, not templates that must be reproduced line for line. Near-verbatim adaptation is the default WHEN the design summary matches the leaf's base shape. When the summary describes a variant, ADAPT the sample's idioms to honor the approved design; do NOT snap the variant back to the sample's default shape, and do NOT frame the deviation as a "conflict" with the skill.

Hold every invariant while adapting: keep the shared connection helper, the service event listeners registered before `connect()`, the publish receipt, receiver state, and termination listeners, explicit single-class imports, the disclaimer header, and the leaf's `VERIFY:` markers. Any NEW named entity the variant introduces must be traceable to a canonical Solace source per Invariant 1: WebFetch the grounding page for it.

**Guardrail: variant versus off-catalog.** Adapt freely WITHIN the chosen pattern family when the change is a topology or reliability knob that the canonical docs ground and that still compiles and emits the leaf's `VERIFY:` markers. If a requirement leaves the Solace topic and queue publish and subscribe model, or needs behavior the canonical docs cannot ground, STOP and tell the developer it is off-catalog rather than inventing it.

**Preserve the marker contract.** A variant adapts the SHAPE, never the marker strings. It MUST still emit its leaf's `VERIFY:` markers, character for character. Never rename or drop a marker to fit a variant.

**Demo harness is not application logic.** The samples carry demo-harness elements that exist only to make a standalone run observable: the once-per-second stats thread or stats line, the continuous publish loop, and the rotating example payload. When the developer's request describes a real application domain, do NOT carry these into the generated classes: replace the example payload and the publish loop with the application's real messages and triggers, never add a delay or rate limit between sends that the application does not ask for, and keep only the lifecycle pieces the leaf's steps call for (the shutdown hook on both roles, the self-exit on the Publisher). When the request IS a demo or a try-it run, keeping the harness is fine. Two things are NOT demo harness: the `trace(...)` narration method (keep it; applications replace its single body to route narration to their logging or reporting system) and the `VERIFY:` markers, which are emitted through `trace(...)`. The generated `trace(...)` body MUST stay `System.out.println`, so the markers reach stdout; rerouting it is the developer's step after verification. Never treat the logging backend (Step 3) as demo harness.

**Comments follow their code.** The samples' comments carry the documented best practices into the code a developer reads long after this session. A generated class that carries a construct from a sample carries that construct's comment too, with only the names adapted. A dropped construct takes its comment with it. Fresh messaging code with no sample twin that applies a documented practice gets a SHORT comment naming the practice, in the samples' comment style. The one-line comment this skill mandates below (the Quickstart fail-fast posture) always applies on its path.

### Quickstart fail-fast reconnect defaults

On the Quickstart door, do not carry the samples' reconnect-tuning block into the generated classes. The samples raise the reconnect budget with two `properties.putIfAbsent(...)` lines (`TransportLayerProperties.RECONNECTION_ATTEMPTS` and `CONNECTION_RETRIES_PER_HOST`) and carry an HA failover comment block. Quickstart generation OMITS the two lines, their reconnect-budget comment, the HA failover comment lines, and the then-unused `TransportLayerProperties` import, leaving connect and reconnect behavior at the `ConfigurationProfile.V1` defaults, so a wrong host, port, or credential in this learning setup surfaces without a long retry budget. Keep the acknowledgement-window comment that follows the block. Drop ONE short comment at the `MessagingService.builder(...)` site in each generated class stating that posture: connect and reconnect settings are at the API defaults so failures surface quickly; see the [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md) page for reconnect tuning beyond Quickstart (the Java API is built on JCSMP).

### Scaffold the config files

Generate two small project files at the project root:

- A committed `config.example.json` that carries placeholder values for the four keys the shared helper reads, spelled as the Java API property names the helper documents. The placeholder values must be obviously fake:

  ```json
  {
    "solace.messaging.transport.host": "tcp://HOST:55555",
    "solace.messaging.service.vpn-name": "YOUR_VPN",
    "solace.messaging.authentication.basic.username": "YOUR_USERNAME",
    "solace.messaging.authentication.basic.password": "YOUR_PASSWORD"
  }
  ```

- A `.gitignore` at the project root that ignores `config.json` and `target/`, so the real credential-bearing file and the build output are never committed.

Tell the developer they opt in to the config file by copying `config.example.json` to `config.json` and filling in their real broker values. Both classes then read it from the working directory, so they run from the project root.

## Step 5: Compile the project, then hand back the run commands

The Java API does not bundle a verify script yet, so Implement mode ends in the compile-only fallback on every run, whatever the contents of `config.json` (SKILL.md Invariant 5). Never fabricate a run result and never call a check below a verified round trip.

1. **Compile check.** From the project root, run `mvn -q compile`. A failure is a code failure: fix it and re-run, up to 3 attempts, then report the evidence and hand the decision to the developer. A dependency that does not resolve because Maven Central is unreachable is an environment failure: report it and do not change the code.
2. **Conformance checks.** From the project root, run these checks and record each result in the checklist. A missing header, a missing checklist, or a `sol-jcsmp` declaration is a code failure under the same fix loop; a version mismatch is reported to the developer, not fixed silently.

   ```bash
   # every generated .java file carries the disclaimer header
   grep -rL 'AI-assisted code. Review before production use.' src --include='*.java' | sed 's/^/MISSING HEADER: /'
   # the tailored checklist exists
   test -f solace-verification-checklist.md || echo "MISSING CHECKLIST"
   # the pom does not declare sol-jcsmp (the Java API brings it transitively)
   grep -q '<artifactId>sol-jcsmp</artifactId>' pom.xml && echo "SOL-JCSMP DECLARED"
   # the pom carries the release Maven Central reports now (skip and record it when Maven Central is unreachable)
   grep -qF "<version>$(curl -s https://repo1.maven.org/maven2/com/solace/solace-messaging-client/maven-metadata.xml | grep -oE '<release>[^<]+</release>' | sed -E 's/<\/?release>//g')</version>" pom.xml || echo "VERSION DRIFT"
   # every log4j-core on the classpath is at or above the 2.17.1 floor (read the versions this prints)
   mvn -q dependency:list -DincludeArtifactIds=log4j-core -DoutputFile=/dev/stdout
   ```

3. **Report and hand back.** Report the compile and conformance results, then hand the developer the exact commands to run against their own broker from the project root, Subscriber first so the durable queue and its subscription exist before the first publish:

   ```
   # terminal 1: provisions the queue, binds, and waits (Ctrl-C stops it)
   mvn -q exec:java@subscriber [-Dexec.args="<host:port> <vpn> <user> [password]"]
   # terminal 2: publishes, waits for the broker's acknowledgement, and exits
   mvn -q exec:java@publisher [-Dexec.args="<host:port> <vpn> <user> [password]"]
   ```

   On the fan-out leaf, hand back one Subscriber command per `subscriber` execution (`exec:java@subscriber` and each `exec:java@subscriber-<service>`), each in its own terminal and all started before the Publisher; every Subscriber prints its own markers.

   The `-Dexec.args` value is needed only when there is no `config.json`. Tell the developer what a passing run prints: the Subscriber prints `VERIFY: CONNECTED` and then `VERIFY: QUEUE_BOUND`; the Publisher prints `VERIFY: CONNECTED` and `VERIFY: PUBLISH_ACKED` and exits with status 0; the Subscriber then prints `VERIFY: MESSAGE_RECEIVED`; Ctrl-C on the Subscriber prints `Shutdown signal received` and the JVM ends with its signal exit status (130 for SIGINT), which is a clean stop.

**When the developer asks you to run it.** If the developer explicitly asks you to run the two classes against their broker, print the live-broker heads-up first (SKILL.md Invariant 6: name the host, every durable queue the Subscribers provision, one connection per class, and the messages the Publisher sends, scaled to the generated classes). Then start each Subscriber in the background with its output captured, wait for `VERIFY: QUEUE_BOUND` from each, run the Publisher in the foreground, wait for `VERIFY: MESSAGE_RECEIVED` in each Subscriber's output, and stop the Subscribers with SIGINT. Report the markers you observed as evidence of a manual run. It is not a verify verdict: record it in the checklist as a manual run, and never describe it as a passed verify stage.

**Environment failure on a manual run: do NOT modify the code.** When the captured output shows that the broker is unreachable, the credentials or VPN are wrong, or the client may not create the queue (the Subscriber's `Could not bind to queue` error), the code is not the problem. Report the matched output line as evidence and STOP: do not enter the fix loop, and do not change the code. The fix is the developer's broker, credentials, or client permissions. Fix the code only when the output shows a code failure (an exception from the classes' own logic, or a marker that never appears after a clean connect and bind), up to 3 attempts, then hand the decision to the developer.

**Close every run with the next steps, stated plainly.** Tell the developer, in order: (1) copy `config.example.json` to `config.json` and fill in their real broker values; (2) run the Subscriber, then the Publisher, with the commands above, and check the markers. Step 4 already wrote the tailored `solace-verification-checklist.md`; invite the developer to add their own items in its Your additional items section and to record any skill-undelivered concerns under Developer-owned items.

### Traps to avoid

- **Improvised verdicts.** A connect that does not throw, or a `publish()` call that returns, proves nothing about the round trip. Judge only by the markers in each class's output.
- **JCSMP code in a Java API project.** Never import `com.solacesystems.jcsmp` types, never build a `JCSMPSession`, and never declare `sol-jcsmp`. The Java API classes live under `com.solace.messaging`.
- **Publishing direct-to-queue instead of pub/sub.** Publish to a topic; let the durable queue carry a topic subscription. See [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md).
- **DIRECT delivery where Guaranteed is required.** Use the persistent publisher for the guaranteed pub/sub journey. See [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md).
- **`disconnect()` before `terminate()`.** Terminate the publisher or the receiver with its grace period first, as the samples do; the samples' comments state that `disconnect()` stops them at once and discards their buffers.
- **Hardcoded credentials.** Never inline the host, VPN, username, or password. Every class builds its properties through the shared helper.
- **Hardcoded versions.** Resolve every dependency version at generation time (Step 3); never carry a version from memory, from a doc page snippet, or from the upstream samples repository. The log4j2 `2.17.1` floor is a CHECK on the resolved result, not a pin.
- **Wildcard imports.** Keep every import explicit, as the samples do; never write `import com.solace.messaging.*;`.
- **Running the Publisher before the Subscriber has provisioned the queue.** A persistent message published before the queue subscribes to its topic is dropped and never received. By default the broker still ACKs it, so the Publisher can print `VERIFY: PUBLISH_ACKED` for a message no Subscriber gets; the Publisher sees a NACK only when the client profile rejects messages that match no subscription. Judge the round trip by `VERIFY: MESSAGE_RECEIVED` in the Subscriber output, never by `PUBLISH_ACKED` alone. Run the Subscriber first.
- **Treating a sample as a spec that overrides the approved design.** See "Adapting a sample to a variant of its leaf" above.

## Step 6: Tailor the checklist (written at Step 4) and report

Step 4 writes the tailored `solace-verification-checklist.md` into the generated project as generation output; this step defines its content and adds the report in chat. The Java API has no master checklist template yet; until it lands, write the file from the list below. Resolve each item for the generated code: an item the generation did not deliver moves to Your responsibility. Write it as a Markdown task list (`- [ ]`), ticking only what this run actually checked. Each item in the first two groups carries one canonical doc link; the round-trip and conformance items carry none because they check skill conformance, not Solace guidance.

**Delivered by this generation**

- Basic-auth connection through the shared `SolaceConnectionConfig` helper; credentials live only in a gitignored `config.json`. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- Service reconnection and interruption listeners registered before `connect()`. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- Persistent publish to a topic with a publish receipt listener; a NACK is logged with the message id. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md)
- Capacity-bounded back pressure on the publisher (`onBackPressureWait`). [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md)
- Durable queue plus topic subscription provisioned at start (`CREATE_ON_START`), with the access type from the design summary (one durable queue per consuming service on fan-out). [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md)
- Client acknowledgement only after processing. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md)
- Graceful SIGINT/SIGTERM shutdown: `terminate(gracePeriod)` and then `disconnect()` on every exit path. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md)
- log4j2 logging with the `log4j-jcl` bridge and a console `log4j2.xml`; the API's own loggers are not silenced. [Configuring Logging in the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md)

**Your responsibility (not delivered here)**

- TLS secure session (`tcps://` with certificate validation); Quickstart does not generate it. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- Credentials handled safely: `config.json` is never committed, and credentials are not passed as command-line arguments, where they land in shell history and process listings (the handed-back run commands accept them only for convenience). [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- Authentication stronger than basic username/password (for example a client certificate, Kerberos, or OAuth) where the environment requires it; this journey generates basic username/password only. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- Handling of a failed message: the generated handler acknowledges a message whose processing failed, and the generated publisher discards a NACKed message (both carry the samples' comments with the alternatives). [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md)
- Dead message queue eligibility on published messages and a dead message queue on the broker. [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md)
- Reconnect tuning for HA failover; Quickstart leaves connect and reconnect behavior at the API defaults. [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- Separate projects or deployments per role (skill guidance: a real deployment lifts each class into its own service or project; no Solace doc page states it).
- An administrator-provisioned queue, or the endpoint-create permission for this client, in production. [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md)
- A JDK version in active support. [Supported Environments for the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-supported-environments.md)

**Verified by the round-trip** (developer-run until the Java API verify script lands)

- Every Subscriber prints `VERIFY: CONNECTED` and `VERIFY: QUEUE_BOUND`.
- The Publisher prints `VERIFY: CONNECTED` and `VERIFY: PUBLISH_ACKED`, then exits with status 0.
- Every Subscriber prints `VERIFY: MESSAGE_RECEIVED` (`PUBLISH_ACKED` alone does not prove delivery).
- Ctrl-C on each Subscriber prints `Shutdown signal received`.

**Generation conformance**

- The disclaimer header is the first content of every generated `.java` file (Step 5 header check).
- `pom.xml` declares `com.solace:solace-messaging-client` at the version Maven Central reported as `<release>` at generation time, or the developer's version when Maven Central was unreachable (Step 5 drift check), and does not declare `sol-jcsmp` (Step 5 check).
- `log4j-core` is at or above `2.17.1` (Step 5 floor check).
- This tailored checklist exists at the project root (Step 5 checklist check).
- `mvn -q compile` passed (Step 5), with the result recorded here.
- Comments follow their code (Step 4).
- Every page named in the design summary's Grounding docs field was WebFetched in this session (`none fetched` is an honest value; an unfetched citation is not).
- Verification: record `verify script not yet bundled for the Java API; round trip handed back`, or `manual run` with the observed markers when the developer asked you to run it.

**Developer-owned items (not delivered by this skill)**: any item the skill does not deliver, recorded as developer-owned rather than skill-satisfied. A consented deviation is recorded here.

**Your additional items**: open space for the developer to keep growing the list.

The emitted checklist is an artifact, not source, so it carries NO disclaimer header. Report the same resolution in chat: what landed under each group, the compile and conformance results, and that the round trip is handed back.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md)
- [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md)
- [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md)
- [Configuring Logging in the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md)
- [Supported Environments for the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-supported-environments.md)
- [Java API reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md)
- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)

Public URLs (not docs.solace.com doc pages; use the live URL directly):

- [Java API Release Notes](https://products.solace.com/download/NG_JAVA_API_RN)
- [solace-messaging-client on Maven Central](https://central.sonatype.com/artifact/com.solace/solace-messaging-client)
