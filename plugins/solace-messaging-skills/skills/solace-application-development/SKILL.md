---
name: solace-application-development
description: "Use when the user mentions JCSMP, the com.solacesystems:sol-jcsmp library, JCSMPSession, the Solace Python API (solace-pubsubplus), MessagingService, PersistentMessageReceiver, publishing to a Solace topic, consuming from a Solace queue, or guaranteed messaging, OR asks broadly to build or design a Solace messaging app in Java or Python (publisher, consumer, pub/sub). This is the single front door for Solace API development: it grounds the work in canonical Solace docs and routes to the right API, helping choose a messaging pattern or generate a runnable app. Do NOT trigger for an explicitly named other Solace API, which beats the generic Java signal: Solace JMS, the Solace Messaging API for Java, or the Solace Go, .NET, or JavaScript APIs. Also do NOT trigger for Apache Kafka (any language), broker administration and configuration, Solace Event Portal event modeling, Schema Registry, OpenTelemetry tracing, or topic-architecture and topic-hierarchy design questions (use solace-topic-best-practices)."
allowed-tools: Read Write Edit Bash Grep Glob WebFetch
---

# Solace API Development

This skill is the front door for building Solace messaging applications grounded in canonical Solace documentation: identify the target API from the API Selection table below, then lazy-load that API's reference files for the detailed, mode-based guidance.

## IMPORTANT: Lazy-Load References Only

**Do NOT read all reference files upfront. Read ONLY what you need, when you need it.**

- User wants a JCSMP application (design help or code generation) then read `references/jcsmp.md`, which selects the right mode within the JCSMP API.
- User wants a Solace Python API application (design help or code generation) then read `references/python.md`, which selects the right mode within the Python API.
- Read a mode file only at the moment a step needs it.
- Most requests need the API's entry file plus one mode file, not the whole tree.

**Never read multiple reference files preemptively "just in case".**

## What is Solace

Solace is an event-driven messaging platform: an event broker carries messages between applications, brokers connect into an event mesh, publishers send to named topics, and consumers read either directly or from durable queues that subscribe to those topics (the publish and subscribe model). Solace Cloud is the primary broker this skill assumes for the work it generates; a self-hosted Software Broker or an existing Appliance are supported alternatives. The canonical [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md) page is the doc grounding for this model.

For topic-hierarchy and topic-architecture design questions, the co-installed solace-topic-best-practices skill reads the canonical Topic Architecture Best Practices page online and applies it to those decisions.

## API Selection

Determine which Solace API the developer is building against, then read that API's entry file from the table.

| User intent | API | What to do |
|---|---|---|
| Anything about JCSMP, the `com.solacesystems:sol-jcsmp` library, `JCSMPSession`, or a Java publisher or consumer (publishing to a topic, or consuming from a queue with guaranteed messaging) | **JCSMP** | Read `references/jcsmp.md` |
| Anything about the Solace Python API, the `solace-pubsubplus` library, `MessagingService`, `PersistentMessageReceiver`, or a Python publisher or consumer | **Python** | Read `references/python.md` |

If the API is unclear but the request is clearly about Solace messaging in Java, default to **JCSMP**. If the API is unclear but the request is clearly about Solace messaging in Python, default to **Python**. If the request names no language at all (for example "build me a Solace publisher"), ask one question before anything else: JCSMP (Java) or Python? Files in the workspace (a `pom.xml`, `.py` files, a `requirements.txt`) are facts to report while you ask, never the answer. The one exception is a saved `solace-design.md` that the developer asks you to build from: its title (`Solace JCSMP Design Summary` or `Solace Python Design Summary`) names the API, so read that API's entry file, whose design-contract gate restates the summary and takes the developer's confirm. The Solace Messaging API for Java (`com.solace:solace-messaging-client`) shares class names such as `MessagingService` and `PersistentMessageReceiver` with the Python API, but it is neither JCSMP nor Python: when a Java request names that API or its `com.solace` package, say in one line that this skill does not cover it yet, and stop. Mode detection (Design, Implement) then happens in that API's entry file.

## Invariants

Non-negotiable rules that apply across every API. Apply all.

1. **Content sourcing**: Every named entity (API, parameter, configuration value) must be traceable to a canonical Solace source. Do not assert behavior, defaults, or best practices that are not in the grounding documentation.
2. **WebFetch-on-demand doc grounding**: This skill bundles no documentation. When a step needs documentation content to ground a generation or design decision, WebFetch the live canonical page on demand (its `docs.solace.com` `.md` URL, the same link the reference files carry), then quote or summarize the fetched page. Do not answer from memory and do not paraphrase guidance the page does not contain. Do not restate doc content in the skill; link the live `.md` URL. Never substitute another channel for that WebFetch: grounding taken from a docs chatbot, a search tool, or decompiling a jar does not satisfy this invariant, and a page never fetched in the session must never be cited as grounding. A short list of references stays live for the same reason it always has (the JCSMP Javadoc and the Python API reference, the JCSMP and Python API Release Notes, Maven Central and PyPI, the tutorials, and the GitHub samples) because those have no `docs.solace.com` `.md` form. Each API's entry file lists its own live exceptions.
3. **No hardcoded versions**: Never pin a library version anywhere in skill content. The generated build resolves the latest release of every dependency at generation time from the API's AUTHORITATIVE registry: the Maven repository metadata for JCSMP, the PyPI JSON `info.version` for Python. Each API's entry file names the exact coordinate and carries the exact lookup command. Never read a JCSMP version from the legacy `search.maven.org/solrsearch` index: it lags the repository metadata and reports stale versions. Security floors (a minimum version below which known CVEs live) are enforced as checks on the resolved result, never as pins.
4. **AI-assisted disclaimer header**: Every source file generated under this skill starts with this exact line: `AI-assisted code. Review before production use.` Directly below it: `See the verification checklist: solace-verification-checklist.md`. This applies to EVERY generated source file, including custom builds, web apps, and embedded shapes that adapt the reference samples outside the canonical generator. Build files (the pom, `requirements.txt`) and other non-source artifacts are exempt; the bundled reference samples themselves carry no header.
5. **Verification artifacts, always**: Any session that generates Solace code from this skill writes a tailored `solace-verification-checklist.md` beside the code as generation output, copies the API's own bundled verify script (plus its generated `verify-hooks.sh`) into the project, and ends in a real verify run or the compile-only fallback with the exact handed-back commands. The API's entry file names its verify script. An API that does not bundle a verify script yet (Python, until its `verify.sh` lands) ends in the compile-only fallback every time, and its implement-mode file defines that fallback; never claim a verified run without the script. The `VERIFY:` markers are non-optional in generated Solace messaging code, whatever the app shape and whatever the API. An improvised check (for example curl against the app's own HTTP API) may TRIGGER traffic but never renders the verdict; the verdict comes from the markers and the verify exit code.
6. **Live-broker heads-up**: Immediately before starting ANY process that will connect to a live broker — verify.sh, `java -jar`, a `mvn` run, a `python` run, a run script, anything — print one line that names the broker host and states the actual broker-side effects of this run (the queues it provisions, the connections it opens, the messages it publishes), scaled to the real app. Environment discovery is never consent and never an answer: a running local broker, an existing config file, or found credentials are facts to report, not answers to consume; report them, then still ask which broker the developer wants to target.

## Reference Files (read on-demand only)

- `references/jcsmp.md`: the JCSMP entry file (mode detection, the JCSMP coordinate, and the live JCSMP doc links); read this for any JCSMP request.
- `references/jcsmp/`: all JCSMP API content (mode files, the reference samples, the verify script, and the compile fixture), reached through `references/jcsmp.md`.
- `references/python.md`: the Python entry file (mode detection, the `solace-pubsubplus` coordinate, and the live Python doc links); read this for any Solace Python API request.
- `references/python/`: all Python API content (mode files and the reference samples), reached through `references/python.md`.
