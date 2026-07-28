# Trigger evals

Each plugin ships a trigger eval corpus at `plugins/<plugin>/evals/trigger-evals.json`, a flat list of cases shaped as `{"skill", "prompt", "should_trigger"}`. A case with `"should_trigger": true` expects the named skill to fire for that prompt, and a case with `"should_trigger": false` expects it to stay silent. The runner loads only the plugin under test, so a fired skill is attributable to that plugin.

## Running the evals locally

To run the evals manually, you need the `claude` CLI and `jq` on your PATH, plus an exported credential (the runner uses a throwaway config directory with no ambient login). Run the script from the repository root:

```shell
export ANTHROPIC_API_KEY=<your key>          # or CLAUDE_CODE_OAUTH_TOKEN
./tools/run-trigger-evals.sh                 # defaults to claude-haiku-4-5
./tools/run-trigger-evals.sh --model claude-sonnet-5
```

Each prompt runs three times and the verdict is the majority result. Set `TRIGGER_EVAL_RUNS=1` for a quicker, cheaper local check, and `TRIGGER_EVAL_MODEL` to change the default model. A run passes when at least 90% of cases match their expectation, and any infrastructure failure (such as a missing credential, a malformed corpus, or zero discovered cases) fails the run regardless of the rate. A case may also set `"must_pass": true`, and any must-pass failure fails the run regardless of the pooled rate. Note that a negative case asserts only that the named skill stays silent, so another skill in the plugin may legitimately fire on the same prompt.

## Continuous integration

In GitHub Actions, the `trigger-evals` job in `.github/workflows/ci.yml` runs this same script on every pull request as a two model matrix (`claude-haiku-4-5` and `claude-sonnet-5`), each model an independent check, and self-skips green when the `ANTHROPIC_API_KEY` secret is absent.
