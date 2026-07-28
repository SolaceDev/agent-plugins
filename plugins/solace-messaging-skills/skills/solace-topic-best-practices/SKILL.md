---
name: solace-topic-best-practices
description: "Use when the user asks how to structure, design, name, or organize a Solace topic hierarchy: choosing topic levels, ordering levels from general to specific, topic taxonomy or naming conventions, where to place wildcards for subscriptions, or topic-architecture best practices in general. This skill covers how topics are named and organized, not how messages are delivered. Do NOT trigger to build, design, generate, or implement a Solace messaging application, or to choose messaging patterns or delivery modes (use solace-application-development); that skill owns publisher, consumer, and pub/sub code generation and messaging-pattern selection. Do NOT trigger for general Solace documentation lookup unrelated to topic architecture."
allowed-tools: Read Grep WebFetch
---

# Solace Topic Architecture Best Practices (online lookup)

This skill answers topic-hierarchy and topic-architecture questions by reading the canonical Solace Topic Architecture Best Practices page live. It bundles no documentation. It fetches the current page on demand and applies it to the user's topic-design decision.

## How to answer a topic-architecture question

1. WebFetch the canonical page live: `https://docs.solace.com/Messaging/Topic-Architecture-Best-Practices.md`. This is the authoritative source for topic-level ordering, naming conventions, wildcard placement, and taxonomy guidance.
2. Apply the fetched guidance to the user's specific topic-design question (the events they publish, the consumers that subscribe, the levels they need, and where wildcards belong).
3. Quote or summarize the fetched page. Do not answer from memory and do not paraphrase guidance the page does not contain.

Fetch the single page above, then ground every recommendation in it. Do not WebFetch other pages blindly.

## What this skill is not

Do not use this skill to design, build, generate, or implement a Solace messaging application (publisher, consumer, or pub/sub). That is the solace-application-development skill.
