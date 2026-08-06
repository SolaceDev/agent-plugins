# Trigger evals

This plugin ships a trigger eval corpus at `trigger-evals.json`, a flat list of cases shaped as `{"skill", "prompt", "should_trigger"}`. A case with `"should_trigger": true` expects the named skill to fire for that prompt, and a case with `"should_trigger": false` expects it to stay silent. A case may also set `"must_pass": true`, and any must-pass failure fails the run regardless of the pooled pass rate. The runner loads only this plugin, so a fired skill is attributable to it.

## Running the evals locally

You need the `claude` CLI and `jq` on your PATH, plus an exported credential (the runner uses a throwaway config directory with no ambient login). Run the script from the repository root:

```shell
export ANTHROPIC_API_KEY=<your key>          # or CLAUDE_CODE_OAUTH_TOKEN
./tools/run-trigger-evals.sh                 # defaults to claude-haiku-4-5
./tools/run-trigger-evals.sh --model claude-sonnet-5
```

The runner discovers every corpus under `plugins/*/evals/trigger-evals.json`, so a local run covers this plugin together with the others. Each prompt runs three times and the verdict is the majority result. Set `TRIGGER_EVAL_RUNS=1` for a quicker, cheaper local check. A run passes when at least 90% of cases match their expectation and no must-pass case fails.

## Continuous integration

In GitHub Actions, the `trigger-evals` job in `.github/workflows/ci.yml` runs this same script on every pull request as a two model matrix (`claude-haiku-4-5` and `claude-sonnet-5`), each model an independent check. It self-skips green when the `ANTHROPIC_API_KEY` secret is absent, which is why local eval results are recorded in the pull request description.
