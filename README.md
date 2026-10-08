# AI Agent Plugins by Solace

A collection of Solace AI agent plugins that package skills for building event-driven messaging applications. These skills help developers using AI coding assistants (such as Claude Code) design and implement Solace messaging applications that follow Solace best practices out of the gate. The solace-application-development skill designs and generates Solace messaging applications (publishing to topics, consuming from queues, and guaranteed messaging), with the Solace JCSMP API and the Solace Python API. A companion solace-topic-best-practices skill looks up the canonical Topic Architecture Best Practices page online and applies it to topic-hierarchy decisions.

> [!WARNING]
> These skills are powered by language models, which are nondeterministic and may change over time. Generated code can vary between runs and may be incorrect, insecure, incomplete, or unsuitable for your environment. You are responsible for reviewing, testing, and validating all generated code before use, especially before deploying to production.

## Installation

### Claude

```shell
/plugin marketplace add SolaceProducts/agent-plugins
/plugin install solace-messaging-skills@solace-agent-plugins
```

### `skills` CLI

This lets you pick specific skills to install. The skills in this repository follow the open [Agent Skills](https://agentskills.io) standard, and the [`skills` CLI](https://github.com/vercel-labs/skills) discovers skills in this format from any GitHub repository and installs them into a wide range of agents, including Claude Code, Cursor, Codex, GitHub Copilot, and Windsurf.

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
| **solace-application-development** | Front door for Solace API development (JCSMP and Python). Routes each request to the right API, helps choose a messaging pattern, and generates a runnable application from reference samples, grounded in canonical Solace documentation. |
| **solace-topic-best-practices** | Navigation-only lookup skill that reads the canonical Topic Architecture Best Practices page online and applies it to topic-hierarchy decisions, without generating application code. |
| **solace-messaging-feedback** | Formats the current session into copy-paste-ready feedback about the skills, routed by support-contract status: contract holders get an email to Solace Support for bugs and a Solace Ideas portal submission for feature ideas, users without a contract get a Solace Community post for both. Formatter only: it produces the text for you to review and send or post, and never sends anything itself. |

## Repository layout

```
agent-plugins/
├── plugins/                         # Claude plugins, one subdirectory per plugin
│   └── solace-messaging-skills/     # The messaging skills plugin
│       ├── .claude-plugin/          # Plugin definition (plugin.json)
│       ├── evals/                   # Trigger and output eval corpora and their README
│       └── skills/                  # Individual skill definitions (shared by all agents)
│           ├── solace-application-development/  # Solace application development umbrella skill
│           │   ├── SKILL.md         # Skill routing and instructions
│           │   └── references/      # Assets referenced by SKILL.md (per-API subfolders)
│           ├── solace-topic-best-practices/     # Online topic-architecture lookup skill
│           │   └── SKILL.md         # Navigation-only manifest; reads the live docs page
│           └── solace-messaging-feedback/       # Feedback formatter skill
│               └── SKILL.md         # Formats and routes session feedback (Support, Community, or Ideas portal)
├── tools/                           # CI check scripts and the trigger and output eval runners
├── README.md                        # This file
├── CLAUDE.md                        # Orientation for AI coding agents that work in this repository
└── .claude-plugin/                  # Claude marketplace definition (marketplace.json)
```

## Trigger and output evals

Each plugin ships a trigger eval corpus under `plugins/<plugin>/evals/` that checks each skill fires on the prompts it should and stays silent on the prompts it should not. See the [trigger evals README](plugins/solace-messaging-skills/evals/README.md) for the corpus format, how to run the evals locally, and how they run in CI.

Each plugin also ships an output eval corpus in the same directory that checks a skill's output honors the skill contract (design before code, the door question, scrubbed feedback drafts, grounded answers). Output evals run locally only. See the [output evals section](plugins/solace-messaging-skills/evals/README.md#output-evals) of the same README for the corpus format, the graders, and how to run them.

## Contributing

This repository does not accept external pull requests or GitHub issues. If you have a Solace support contract, report bugs to Solace Support and suggest features on the [Solace Ideas portal](https://ideas.solace.com/ideas). Otherwise, post both on the [Solace Community](https://community.solace.com/). The `solace-messaging-feedback` skill formats a session into a ready-to-send report for each channel.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
