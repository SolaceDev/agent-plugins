# Trigger evals

Each plugin ships a trigger eval corpus at `plugins/<plugin>/evals/trigger-evals.json`, a flat list of cases shaped as `{"skill", "prompt", "should_trigger"}`. A case with `"should_trigger": true` expects the named skill to fire for that prompt, and a case with `"should_trigger": false` expects it to stay silent. The runner loads only the plugin under test, so a fired skill is attributable to that plugin.

## Running the evals locally

To run the evals manually, you need the `claude` CLI and `jq` on your PATH, plus an exported credential. An existing `claude` login does not satisfy this: the runner uses a throwaway config directory so that only the plugin under test is loaded, and that directory has no login, so `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` must be exported. Run the script from the repository root:

```shell
export ANTHROPIC_API_KEY=<your key>          # or CLAUDE_CODE_OAUTH_TOKEN
./tools/run-trigger-evals.sh                 # defaults to claude-haiku-4-5
./tools/run-trigger-evals.sh --model claude-sonnet-5
```

Each prompt runs three times and the verdict is the majority result. Set `TRIGGER_EVAL_RUNS=1` for a quicker, cheaper local check, and `TRIGGER_EVAL_MODEL` to change the default model. A run passes when at least 90% of cases match their expectation, and any infrastructure failure (such as a missing credential, a malformed corpus, or zero discovered cases) fails the run regardless of the rate. A case may also set `"must_pass": true`, and any must-pass failure fails the run regardless of the pooled rate. Note that a negative case asserts only that the named skill stays silent, so another skill in the plugin may legitimately fire on the same prompt.

## Continuous integration

In GitHub Actions, the `trigger-evals` job in `.github/workflows/ci.yml` runs this same script on every pull request as a two model matrix (`claude-haiku-4-5` and `claude-sonnet-5`), each model an independent check, and self-skips green when the `ANTHROPIC_API_KEY` secret is absent.

# Output evals

Trigger evals ask whether the right skill fires. Output evals ask the next question: does the skill's output honor the skill contract? The corpus at `plugins/<plugin>/evals/output-evals.json` holds the cases for the plugin's skills. Each case carries an `id`, the target `skill`, an ordered `user_turns` array of scripted user messages (multi-turn cases continue one session via `--resume`), an optional `max_agent_turns` budget (the model's replies and tool calls allowed per user turn), an optional `must_pass` flag, and a `graders` array. A case passes only when the target skill fired, every grader passed, and no user turn hit the `max_agent_turns` cap. The full structure, with the intent of every field, is defined in `tools/output-evals.schema.json` (JSON Schema, draft 2020-12). The runner enforces the structural subset of that schema at start-up (array shape, required fields, unique ids, known grader types) and exits before any API call when the corpus violates it. Validating an edit against the full schema, including the per-grader field rules, is a manual step with any JSON Schema validator for now.

For the application-development skill, the cases cover these contract points end to end: the broker-access question, design mode entered rather than skipped, `solace-design.md` saved on request by the end of design mode, the three-way door question (Quickstart, Solace Suggested, Custom), the Solace Suggested secure connection and HA failover question, the Quickstart posture (plaintext capable, single project, no HA question), and the AI-generated notice at the top of every generated file. The compile case also carries an opt-in live round trip: when the four `OUTPUT_EVAL_BROKER_*` variables are set, the runner executes the generated project's own `verify.sh` against that broker. Without them the grader skips, the run says so up front and in its summary, and no case ever requires broker credentials.

Two grader families exist. Deterministic graders (`assistant_grep`, `tool_use`, `file_exists`, `file_grep`, `java_disclaimer`, `compile`, `maven_release_match`, `live_verify`) check the transcript and the generated files mechanically, including a real `mvn compile` of the generated project, a match of the generated pom against the live sol-jcsmp `<release>` on Maven Central, and, when a broker is configured, a real `verify.sh roundtrip` of the generated project. The `llm_judge` grader sends the transcript to a fixed judge model for routing and grounding criteria that a grep cannot decide. Negative cases assert the absence of forbidden behavior: code before a design confirm, a support email before the support-contract confirmation, unscrubbed identifiers in a feedback draft, and memory-derived debug steps. Run `./tools/run-output-evals.sh --help` for the full grader reference.

## How the transcript maps onto the corpus

Each `turnN.jsonl` in the work directory is the raw `--output-format stream-json --verbose` stream of one `claude -p` invocation, one file per entry in `user_turns`. The runner reads only the fields in this table. The shapes were observed with Claude Code 2.1.266 on 2026-09-09. Re-check this table after a CLI upgrade, because a change to these fields breaks the graders silently.

| Corpus field or runner concept | Source in `turnN.jsonl` |
|---|---|
| `user_turns[i]` | Not in the file. Entry `i` is the `-p` prompt of invocation `i`, and the stream never echoes it. The only `user` events are tool results. Each entry produces its own `turn<i>.jsonl`. |
| `max_agent_turns` | Passed as `--max-turns`. The `result` event reports `num_turns` (agent turns used, one per assistant message cycle) and sets `subtype` to `error_max_turns` when the cap is hit. |
| Grader `turn` | Selects which `turn<N>.jsonl` file the grader reads. It is not a field inside the file. |
| `skill` (the implicit skill-fired gate) | An `assistant` event whose `message.content[]` holds a `tool_use` block with `name` `Skill`. Its `input.skill` is `plugin:skill-name`; the runner strips the prefix. |
| `assistant_grep` | `assistant` events, `message.content[]` blocks with `type` `text`. Thinking blocks and tool payloads are excluded. |
| `tool_use` | `assistant` events, blocks with `type` `tool_use`. The runner matches `name` and greps the compact JSON of `input`. |
| `llm_judge` with `webfetch_urls` or `webfetch_results` | WebFetch `tool_use` blocks supply the URLs. `user` events whose `tool_result.tool_use_id` matches a WebFetch block supply the fetched content. |
| Session continuity (`--resume`) | `session_id` on the `result` event, with the `init` event as the fallback. |
| INFRA verdict for a turn | A missing `result` event, or `result.is_error` true with a `subtype` other than `error_max_turns`. |

## Running the output evals locally

### Prerequisites

- The `claude` CLI (Claude Code) on your PATH.
- `jq` and `curl` on your PATH.
- `mvn` (Apache Maven) with a JDK 11 or newer on your PATH. Only the cases that compile generated code need it, and the runner checks for it only when such a case is selected.
- An exported credential, either `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`. An existing `claude` login does not satisfy this: the runner uses a throwaway config directory so that only the plugin under test is loaded, and that directory has no login. Claude seat holders can mint a token with `claude setup-token`.
- `timeout` from GNU coreutils (or Homebrew's `gtimeout`) on your PATH, to cap each `mvn compile` and `verify.sh` run at 600 seconds. Optional: without it those steps run unbounded, and the runner prints a warning. On macOS, install it with `brew install coreutils`.
- Network access to `repo1.maven.org` (Maven Central metadata and dependencies) and `docs.solace.com` (the skills fetch documentation pages).
- Optional: the four `OUTPUT_EVAL_BROKER_*` variables for the live round trip against a real broker (see below).
- On macOS, `caffeinate` to keep the machine awake for a full leg.

Run from the repository root:

```shell
export ANTHROPIC_API_KEY=<your key>            # or CLAUDE_CODE_OAUTH_TOKEN
./tools/run-output-evals.sh                    # defaults to claude-sonnet-5
./tools/run-output-evals.sh --model claude-opus-5
./tools/run-output-evals.sh --case appdev-quickstart-implement-full
```

Run the suite for both `claude-sonnet-5` and `claude-opus-5` before a PR that affects skill content, and record both results in the PR description, including whether live verify ran or skipped (the runner prints this in its summary). A full leg is a long run; keep the machine awake for it (on macOS, prefix the command with `caffeinate -i`), because a sleep mid-run surfaces as INFRA failures. Each case runs once by default (`OUTPUT_EVAL_RUNS=1`), because a full implement-flow case is expensive; raise it for a majority-vote stability study. `OUTPUT_EVAL_JUDGE_MODEL` (default `claude-sonnet-5`) stays fixed across subject models so leg differences are attributable to the subject. `OUTPUT_EVAL_WORKDIR` receives transcripts and generated projects, and the work directory is kept and printed when the run fails.

The gate matches the trigger evals: a run passes when at least 90% of cases pass, any infrastructure failure fails the run, and any `must_pass` failure fails the run regardless of the pooled rate. The forbidden-behavior negatives and the compile case are `must_pass`.

### Live verification against a real broker

The compile case's `live_verify` grader runs the generated project's `verify.sh roundtrip` against a broker you name. It is opt-in and needs all four of these variables exported in your shell:

```shell
export OUTPUT_EVAL_BROKER_HOST=tcp://<your-broker-host>:55555
export OUTPUT_EVAL_BROKER_VPN=<message VPN name>
export OUTPUT_EVAL_BROKER_USER=<client username>
export OUTPUT_EVAL_BROKER_PASSWORD=<client password>
```

Do not put these values in a file inside the repository; the repository is public. The runner strips the export attribute from the four variables so the values never reach the subject model, and hands them to `verify.sh` only as CLI arguments.

The runner prints the live-verify state before the first case starts and again in the summary. When none of the four variables is exported, the grader skips and the case line says so. When some but not all are exported, the runner exits with an error before any case runs. When `verify.sh` reports a broker or credential error (its exit code 2), the case is an INFRA failure, not a skill failure. Use a dedicated eval message VPN or service: the generated subscriber provisions a durable queue on every run, so queues accumulate, and the client username needs permission to create endpoints.
