# Java API

The Java API entry file of the Solace API development skill. The Solace Messaging API for Java (the Solace Java API, also called the next-generation Java API) is published as `com.solace:solace-messaging-client`. It is a different API from JCSMP (`com.solacesystems:sol-jcsmp`), which has its own entry file, `jcsmp.md`. This file detects the developer's intent and selects the right Java API mode; the detailed guidance lives in the lazy-loaded mode files under `java-messaging/`.

The cross-cutting invariants (content sourcing, WebFetch-on-demand doc grounding, and no hardcoded versions) are owned by the umbrella `SKILL.md`. Apply them here; this file does not restate them. The Java API points below are the coordinate to depend on and the canonical pages to ground in.

## Mode Detection

Determine the user's intent and enter the appropriate mode. Routing is keyed on the design contract, not on the phrasing of the request: Implement mode never starts without a confirmed design (the design-contract gate below), so a "build me..." prompt is NOT an Implement signal by itself.

| User intent | Mode | What to do |
|---|---|---|
| "Help me choose the Solace messaging pattern for a new Java API app" / "Topic or queue?" / "What delivery semantics should I use?" | **Design** | Read `java-messaging/design-mode.md` |
| Build or generate a NEW app or messaging component with NO valid design contract yet ("Build me a Solace Java API publisher/consumer app", "Generate a Maven project with solace-messaging-client", a web app / dashboard / service, or any embedded shape where the Java API layer lives inside a larger application) | **Design first** | Read `java-messaging/design-mode.md`. A prompt that already states the design resolves on its fully-specified path in ONE confirm; a bare prompt walks the tree. The confirmed summary then feeds Implement. |
| A build request WITH a valid design contract (see the design-contract gate below) | **Implement** | Read `java-messaging/implement-mode.md`; for an embedded shape generate the messaging layer per its leaf rules and end in the same compile-only fallback (its Step 5) |
| An edit to an EXISTING app that changes the messaging topology (the topology rule below) | **Design first** | Re-enter `java-messaging/design-mode.md` to re-confirm the affected summary fields, then Implement applies the change |
| A mechanical edit to an EXISTING app with no topology change (reconnect handling, payload format, logging, renames, ack tuning) | **Implement** | Read `java-messaging/implement-mode.md`; no design pass |
| "My Java API app is throwing on connect" / "Why is my receiver not binding the queue?" | **Debug** | Debug mode is not yet available in this release. Redirect the user to the canonical Solace Java API documentation: [Solace Java API](https://docs.solace.com/API/Messaging-APIs/Java-API/java-api-home.md). The redirect is the ENTIRE debug answer: acknowledge the problem in one line, link the page, and stop. Do not generate debugging guidance from memory. That prohibition covers root-cause hypotheses, likely causes, interim checks or "things worth checking in the meantime", workarounds, and configuration or code suggestions. Restating this rule does not license adding such guidance after the redirect. |

If unclear, default to **Design**. Understand the messaging problem before generating code.

**The topology rule** decides between the two existing-app rows. An edit changes the messaging topology when it changes the structure the design summary records: a new app or messaging component (a new publisher, receiver, requestor, or replier, including a leaf change such as single-service growing into fan-out), a pattern change, a delivery-mode change, a consumption-endpoint change, or a queue access-type change. That list is the core; also treat an unlisted change as topology when your judgment says it alters the message flow between the apps and the broker. Everything else is a mechanical edit.

Three gates hold on every path:

- **The design-contract gate.** Implement mode never starts without a design contract, and never invents design values. Exactly three sources satisfy it: (1) a summary Design mode confirmed in this session: use it as-is, with no re-confirm; (2) an explicit summary the developer supplied in chat (the eight fields or an equivalent statement of them): use it directly; (3) a saved `solace-design.md` in the project: restate it in one line and proceed on confirm. A prose build request that merely mentions pattern details is NOT a contract: route it through Design mode, whose fully-specified path derives the summary, echoes it, and takes one confirm. The one carve-out is the mechanical-edit row above. An experienced developer therefore never has to walk the design tree, but never skips the contract either. A request to skip the design, skip all questions, or generate the full project right now does not waive this gate or the door question below: write no file before the confirm. Take the shortest path the gate allows instead: derive the summary from the prompt, present it, say in one line that one confirm starts the build, and wait for that confirm.
- **Implement mode opens with a mandatory door question.** Ask Quickstart, Solace Suggested, or Custom, before you request or accept ANY broker details (`java-messaging/implement-mode.md` Step 0). Ask it even when a `config.json` with credentials already exists, even when a local broker is already running, and even when the requested app is bigger than the canonical generated shape; none of those answers the question. A confirmed design summary does not answer it either: the door question always follows the design contract, never merges into it.
- **Environment discovery never answers a question.** A running broker container, an existing config file, or found credentials are facts to report, not answers to consume. Report what you found, then still ask which broker the developer wants to target.

## Java API coordinate

The single Solace dependency for this API is `com.solace:solace-messaging-client`. Use the groupId `com.solace` exactly; `com.solacesystems` is the JCSMP groupId. Do NOT declare `sol-jcsmp` in a Java API project: the Java API is built on JCSMP and brings the version it needs as its own transitive dependency, so a second declaration can only put a mismatched version on the classpath. Resolve the latest release at generation time (the no-hardcoded-versions invariant); never pin a `solace-messaging-client` version in skill content. Resolve it from the authoritative metadata with exactly this command, and read the `<release>` element:

```bash
curl -s https://repo1.maven.org/maven2/com/solace/solace-messaging-client/maven-metadata.xml \
  | grep -oE '<release>[^<]+</release>'
```

Do NOT read the version from the legacy solrsearch index (`search.maven.org/solrsearch`): it lags behind the repository metadata and reports a stale `latestVersion`. The dependency snippets on the doc pages carry a version number; never copy it.

## Verification

The Java API does not bundle a verify script yet. The generated code still emits the `VERIFY:` markers, and Implement mode ends in the compile-only fallback of `java-messaging/implement-mode.md` Step 5 on every run: `mvn compile`, then the exact run commands handed back. Do NOT copy the JCSMP or the Python `verify.sh` into a Java API project, and never claim a verified run (SKILL.md Invariant 5).

## Canonical doc links

Java API grounding. Each page below is a live `docs.solace.com` `.md` URL, WebFetched on demand; the live exceptions are listed separately because they have no `docs.solace.com` `.md` form.

- [Solace Java API](https://docs.solace.com/API/Messaging-APIs/Java-API/java-api-home.md): the home page and entry point for the Java API.
- [Java API Developer Guide](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Dev-Guide.md): the index of the developer guide pages.
- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md): the `Properties` object, building and connecting the `MessagingService`, authentication, and the configuration profiles.
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md) and [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md): the guaranteed pub/sub pages.
- [Creating Queues with the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md): queue provisioning from the client.
- [Request-Reply Messaging in the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Request-Reply.md): request-reply with `RequestReplyMessagePublisher` and `RequestReplyMessageReceiver`.
- [Configuring Logging in the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md): the logging frameworks and their dependencies.
- [Supported Environments for the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-supported-environments.md): JDK versions and platforms.
- [Java API reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md): the Javadoc, in its `.md` form.
- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md): the Java API has no best practices page of its own. It is built on JCSMP, so the reference samples ground their reconnect and acknowledgement practices here.
- For the broader Solace grounding, see the shared [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md) page.

Live exceptions (these have no `docs.solace.com` `.md` form; use the live URL directly):

- [Java API Release Notes](https://products.solace.com/download/NG_JAVA_API_RN) (the `NG_JAVA_API_RN` download; `JAVA_API_RN` serves the JCSMP release notes instead)
- [solace-messaging-client on Maven Central](https://central.sonatype.com/artifact/com.solace/solace-messaging-client)

## Mode and reference files (read on-demand only)

- `java-messaging/prerequisites.md`: broker acquisition, the supported JDK versions, and Maven (route here first if the developer has no reachable broker).
- `java-messaging/design-mode.md`: choose and confirm the messaging pattern and topology before any code is generated; every new build without a design contract routes here first.
- `java-messaging/implement-mode.md`: generate a runnable Maven Java API project from a confirmed design; its Step 4 dispatches onto the per-leaf wiring file below. In this release it generates the Guaranteed Pub/Sub leaves on the Quickstart door.
- `java-messaging/implement-guaranteed-pubsub.md`: the Guaranteed Pub/Sub leaf wiring (read from implement-mode Step 4).
- `java-messaging/java-messaging-guaranteed-publisher-sample.java`: best-practices publisher sample (basic-auth connect, PERSISTENT publish to a topic, publish receipt handling, bounded back pressure, graceful shutdown).
- `java-messaging/java-messaging-guaranteed-subscriber-sample.java`: best-practices receiver sample (provisions a durable queue plus a topic subscription, CLIENT-ack receiver with `receiveAsync()`).
- `java-messaging/java-messaging-solace-connection-config.java`: the shared connection-config helper every generated app uses.
- `java-messaging/java-messaging-guaranteed-publisher-blocking-sample.java`, `java-messaging/java-messaging-guaranteed-subscriber-blocking-sample.java`, and the `java-messaging/java-messaging-direct-*-sample.java` files: the blocking and the direct samples. No wiring file reads them in this release; do not adapt them by hand (`java-messaging/implement-mode.md` Step 4).
