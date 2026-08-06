# AI Agent Plugins by Solace

A collection of Solace AI agent plugins that package skills for building event-driven messaging applications. These skills help developers using AI coding assistants (such as Claude Code) design and implement Solace messaging applications that follow Solace best practices out of the gate. The solace-application-development skill designs and generates Solace messaging applications (publishing to topics, consuming from queues, and guaranteed messaging), starting with the Solace Messaging API for JCSMP. A companion solace-topic-best-practices skill looks up the canonical Topic Architecture Best Practices page online and applies it to topic-hierarchy decisions.

> [!WARNING]
> These skills are powered by language models, which are nondeterministic and may change over time. Generated code can vary between runs and may be incorrect, insecure, incomplete, or unsuitable for your environment. You are responsible for reviewing, testing, and validating all generated code before use, especially before deploying to production.

## Installation

### Claude

```shell
/plugin marketplace add SolaceProducts/agent-plugins
/plugin install solace-messaging-skills@solace-agent-plugins
/plugin install solace-docs@solace-agent-plugins
```

### `skills` CLI

This lets you pick specific skills to install and supports most agents.

```shell
npx skills add SolaceProducts/agent-plugins
```

## Quick start

[Solace Cloud](https://docs.solace.com/Cloud/ggs_signup.htm) is the primary, recommended broker for the applications these skills generate. A self-hosted Software Broker or an existing Appliance work as alternatives.

1. Install the skills using one of the methods above
2. Open a project where you want to build a Solace messaging application
3. Ask your AI agent (for example, Claude) to help design or implement a Solace messaging application
4. The relevant skill will automatically activate based on your request

Example prompts:

- "Design a Solace application that publishes order events to a topic"
- "Build a Solace app that publishes to a topic and consumes from a durable queue"

## Skills

This repository includes the following skills:

| Skill | Description |
|-------|-------------|
| **solace-application-development** | Routes Solace messaging design and implementation requests to grounded Solace documentation. Full guidance for messaging pattern selection, runnable code generation, and message round trip verification arrives in upcoming releases. |
| **solace-topic-best-practices** | Navigation-only lookup skill that reads the canonical Topic Architecture Best Practices page online and applies it to topic-hierarchy decisions, without generating application code. |
| **solace-messaging-feedback** | Formats the current session into copy-paste-ready feedback about the skills, routed by support-contract status: contract holders get an email to Solace Support for bugs and a Solace Ideas portal submission for feature ideas, users without a contract get a Solace Community post for both. Formatter only: it produces the text for you to review and send or post, and never sends anything itself. |
| **solace-docs** | Navigation-only lookup skill (in the solace-docs plugin) that finds the right docs.solace.com page from a bundled sitemap and reads the current page online as Markdown, quoting or summarizing the live documentation instead of answering from memory. |

## Repository layout

```
agent-plugins/
├── plugins/                         # Claude plugins, one subdirectory per plugin
│   ├── solace-docs/                 # The documentation lookup plugin
│   │   ├── .claude-plugin/          # Plugin definition (plugin.json)
│   │   ├── evals/                   # Trigger eval corpus (trigger-evals.json) and its README
│   │   └── skills/
│   │       └── solace-docs/         # Online documentation lookup skill
│   │           ├── SKILL.md         # Lookup instructions
│   │           └── docs/            # Bundled sitemap (INDEX.md) of live docs.solace.com Markdown URLs
│   └── solace-messaging-skills/     # The messaging skills plugin
│       ├── .claude-plugin/          # Plugin definition (plugin.json)
│       ├── evals/                   # Trigger eval corpus (trigger-evals.json) and its README
│       └── skills/                  # Individual skill definitions (shared by all agents)
│           ├── solace-application-development/  # Solace application development umbrella skill
│           │   ├── SKILL.md         # Skill routing and instructions
│           │   └── references/      # Assets referenced by SKILL.md (per-API subfolders)
│           ├── solace-topic-best-practices/     # Online topic-architecture lookup skill
│           │   └── SKILL.md         # Navigation-only manifest; reads the live docs page
│           └── solace-messaging-feedback/       # Feedback formatter skill
│               └── SKILL.md         # Formats and routes session feedback (Support, Community, or Ideas portal)
├── tools/                           # Re-runnable scripts (check-links, run-trigger-evals)
├── README.md                        # This file
└── .claude-plugin/                  # Claude marketplace definition (marketplace.json)
```

## Trigger evals

Each plugin ships a trigger eval corpus under `plugins/<plugin>/evals/` that checks each skill fires on the prompts it should and stays silent on the prompts it should not. See the [trigger evals README](plugins/solace-messaging-skills/evals/README.md) for the corpus format, how to run the evals locally, and how they run in CI.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
