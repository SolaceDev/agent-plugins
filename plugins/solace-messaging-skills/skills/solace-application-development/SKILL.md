---
name: solace-application-development
description: "Use when the user mentions JCSMP, the com.solacesystems:sol-jcsmp library, JCSMPSession, publishing to a Solace topic, consuming from a Solace queue, or guaranteed messaging, OR asks broadly to build or design a Solace messaging app in Java (publisher, consumer, pub/sub). This is the single front door for Solace API development: it grounds the work in canonical Solace docs and routes the request to the right API (JCSMP today), helping choose a messaging pattern or generate a runnable Maven app. Do NOT trigger for an explicitly named non JCSMP API, which beats the generic Java signal: Solace JMS, or the Solace Python, Go, .NET, or JavaScript APIs. Also do NOT trigger for Apache Kafka, broker administration and configuration, Solace Event Portal event modeling, Schema Registry, OpenTelemetry tracing, or topic-architecture and topic-hierarchy design questions (use solace-topic-best-practices)."
allowed-tools: Read Write Edit Bash Grep Glob WebFetch
---

# Solace API Development

This skill is the front door for building Solace messaging applications grounded in canonical Solace documentation: identify the target API from the API Selection table below, then lazy-load that API's reference files for the detailed, mode-based guidance.

## IMPORTANT: Lazy-Load References Only

**Do NOT read all reference files upfront. Read ONLY what you need, when you need it.**

- User wants a JCSMP application (design help or code generation) then read `references/jcsmp.md`, which selects the right mode within the JCSMP API.
- Read a mode file only at the moment a step needs it.
- Most requests need the API's entry file plus one mode file, not the whole tree.

**Never read multiple reference files preemptively "just in case".**

## What is Solace

Ground the developer in the high-level model before discussing patterns or generating code. Solace is an event-driven messaging platform: an event broker carries messages between applications, brokers connect into an event mesh, publishers send to named topics, and consumers read either directly or from durable queues that subscribe to those topics (the publish and subscribe model). Solace Cloud is the primary broker this skill assumes for the work it generates; a self-hosted Software Broker or an existing Appliance are supported alternatives. Point the developer at the canonical [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md) page for the grounding, confirm they grasp the publish/subscribe and queue concepts, then continue. Do not paraphrase the page; link it.

For topic-hierarchy and topic-architecture design questions, the co-installed solace-topic-best-practices skill reads the canonical Topic Architecture Best Practices page online and applies it to those decisions.

## API Selection

Determine which Solace API the developer is building against, then read that API's entry file from the table.

| User intent | API | What to do |
|---|---|---|
| Anything about JCSMP, the `com.solacesystems:sol-jcsmp` library, a Java publisher or consumer, publishing to a topic, or consuming from a queue with guaranteed messaging | **JCSMP** | Read `references/jcsmp.md` |

If the API is unclear but the request is clearly about Solace messaging in Java, default to **JCSMP**. Mode detection (Design, Implement) then happens in that API's entry file.

## Invariants

Non-negotiable rules that apply across every API. Apply all.

1. **Content sourcing**: Every named entity (API, parameter, configuration value) must be traceable to a canonical Solace source. Do not assert behavior, defaults, or best practices that are not in the grounding documentation.
2. **WebFetch-on-demand doc grounding**: This skill bundles no documentation. When a step needs documentation content to ground a generation or design decision, WebFetch the live canonical page on demand (its `docs.solace.com` `.md` URL, the same link the reference files carry), then quote or summarize the fetched page. Do not answer from memory and do not paraphrase guidance the page does not contain. Do not restate doc content in the skill; link the live `.md` URL. A short list of references stays live for the same reason it always has (Javadoc HTML, the Java API Release Notes, Maven Central, the tutorials, and the GitHub samples) because those have no `docs.solace.com` `.md` form.
3. **No hardcoded versions**: Never pin a library version anywhere in skill content. The generated build resolves the latest release of the relevant Solace coordinate at generation time. Each API's entry file names the exact coordinate to use.

## Reference Files (read on-demand only)

- `references/jcsmp.md`: the JCSMP entry file (mode detection, the JCSMP coordinate, and the live JCSMP doc links); read this for any JCSMP request.
- `references/jcsmp/`: all JCSMP API content (mode files, the reference samples, the verify script, and the compile fixture), reached through `references/jcsmp.md`.
