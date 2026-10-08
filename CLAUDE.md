# CLAUDE.md

Orientation for AI coding agents in this repository. For humans, the [README](README.md) covers installation, development, CI, and releases, and the [evals README](plugins/solace-messaging-skills/evals/README.md) covers the eval corpora and runners.

This repository is a Claude Code plugin marketplace (`solace-agent-plugins`) of Agent Skills for building Solace messaging applications. The product is Markdown skill files, reference samples, and eval corpora. Nothing is compiled or packaged.

## Where things live

| Path | What it is |
|------|------------|
| `.claude-plugin/marketplace.json` | The marketplace. One entry per plugin directory. |
| `plugins/<plugin>/.claude-plugin/plugin.json` | Plugin manifest, including the plugin's version. |
| `plugins/<plugin>/skills/<skill>/SKILL.md` | A skill. The frontmatter `description` alone decides when the skill fires. |
| `plugins/solace-messaging-skills/skills/solace-application-development/references/<api>.md` | Per-API entry file (`jcsmp.md`, `python.md`). Mode files, baked samples, and scripts sit in the matching `references/<api>/` folder. |
| `plugins/<plugin>/evals/` | Trigger and output eval corpora. Maintainer tooling, not shipped functionality. |
| `tools/` | CI check scripts, the two eval runners, and `output-evals.schema.json`. |
| `.github/workflows/ci.yml` | The only CI workflow. |

Nothing in the repository is generated or vendored. The Eclipse files under `references/jcsmp/evals/compile-fixture/` are IDE metadata for the compile fixture; leave them alone.

## Commands

Run from the repository root. These match the README.

```shell
gh skill publish --dry-run                       # skills against the Agent Skills spec (gh 2.90 or later)
claude plugin validate . --strict                # marketplace and plugin manifests
./tools/check-plugin-versions.sh --sync-only     # marketplace.json matches plugins/
./tools/check-links.sh plugins                   # broken and soft-404 doc links
./plugins/solace-messaging-skills/skills/solace-application-development/references/jcsmp/evals/compile-fixture/compile.sh   # compile the baked JCSMP samples
claude --plugin-dir plugins/solace-messaging-skills   # load the plugin from the clone
```

The commands above need no broker and no API key. The evals need an exported `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`; an existing `claude` login does not work, because the runners use a scratch config directory.

```shell
./tools/run-trigger-evals.sh --model haiku       # then --model sonnet
./tools/run-output-evals.sh --model sonnet       # then --model opus; a full leg takes about an hour
./tools/run-output-evals.sh --case <case-id>     # one output eval case
```

The trigger runner has no single-case mode; set `TRIGGER_EVAL_RUNS=1` for a quicker check. Only the output evals can touch a broker, and only when all four `OUTPUT_EVAL_BROKER_*` variables are exported (see the evals README).

## Conventions

- Never hardcode a library version in skill content. Generated builds resolve the latest release at generation time (Invariant 3 in the application-development `SKILL.md`).
- Link documentation to the live `docs.solace.com` `.md` URL. Do not copy doc content into a skill. Every link under `plugins/` must pass `check-links.sh`.
- A new skill needs cases in both `trigger-evals.json` (positive and near-miss negative prompts) and `output-evals.json` (validate the latter against `tools/output-evals.schema.json`).
- A new, removed, or renamed plugin directory needs the matching `marketplace.json` change in the same pull request.
- A new baked JCSMP sample must be added to the sample roster in `compile.sh`, or the compile check does not cover it.
- Do not put exact counts (of cases, skills, or graders) in READMEs or skill docs. They go stale.

## Gotchas / do not

- **Do not bump a `plugin.json` version in a feature pull request.** Versions change only when a release is prepared. Claude Code caches installed plugins by version, so the bump is what ships a release, and a stray bump confuses what users have. Changes under `plugins/<plugin>/evals/`, `tools/`, `.github/`, or the repository root never need a bump.
- **Both repositories are public.** Never write internal links, internal system names, credentials, API keys, or broker details into files, commit messages, branch names, or pull request text.
- **Run `gh repo set-default SolaceDev/agent-plugins` in a fresh clone.** With the `upstream` remote present, a bare `gh pr` command otherwise targets `SolaceProducts/agent-plugins`.
- **A green `trigger-evals` check asserts nothing.** The job is disabled (`if: false`) because the public repositories hold no API key. Run the evals locally and record the results in the pull request description.
- **A trigger eval leg can go red on one `must_pass` case from model variance.** If the prompt and skill descriptions did not change, re-run the full leg before you treat it as a regression.
- **Keep shell scripts on LF line endings.** `.gitattributes` enforces it. Bash rejects CRLF scripts, and an output eval grader compares the generated `verify.sh` byte for byte with the bundled copy in `references/jcsmp/scripts/`.
- **Keep the pins in `ci.yml`.** Every `uses:` step is pinned to a commit SHA with the tag in a trailing comment, and the Claude Code CLI version is pinned in `CLAUDE_CODE_VERSION`. Update a SHA and its comment together.
- **`check-links.sh` sweeps `plugins/` only.** It does not check links in the root README or this file.
- **A tool permission denial during an output eval is an infrastructure failure, not a skill failure.** Managed settings, hooks, or local command shims can deny a command. Fix the environment, then re-run.

## Agent workflow

- Open pull requests as drafts. Watch the checks with `gh pr checks <number> --watch`, fix failures, and mark the pull request ready only when all checks are green.
- Print a failed CI job's log with `gh run view <run-id> --log-failed`, then reproduce it with the matching command above.
- For a change to skill content or skill descriptions, run the trigger evals on Haiku and Sonnet and the output evals on Sonnet and Opus, and record the results in the pull request description.
- Do not pick reviewers. Ask the user who to tag.
- Never merge a release pull request. A human reviewer merges it.
