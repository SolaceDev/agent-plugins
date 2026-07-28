#!/usr/bin/env bash
#
# run-trigger-evals.sh: a lightweight skill-triggering check.
#
# For every case in each plugin's evals/trigger-evals.json, it runs the prompt
# through the Claude Code CLI with only that plugin loaded, then asks a single
# question: did the target skill fire? A case with "should_trigger": true passes
# when the skill fires; "should_trigger": false passes when it stays silent.
#
# WHY --plugin-dir AND A SCRATCH CONFIG DIR
#   --plugin-dir loads the plugin straight from the repo, with no marketplace
#   install. Pointing CLAUDE_CONFIG_DIR at a throwaway directory means the run
#   sees ONLY that plugin and none of the developer's ambient skills, so the
#   fired set is attributable to the plugin under test. The trade-off is that a
#   scratch config has no ambient login, so a credential must be exported.
#
# WHY THE Skill TOOL_USE, NOT A SKILL.md READ
#   A skill does not surface as a Read of SKILL.md. It fires as a `Skill`
#   tool_use event in the stream-json transcript, carrying the skill name in
#   .input.skill (as "<plugin>:<skill>", sometimes bare on a repeat call). That
#   event is the ground truth for "did the skill trigger", so detection reads it
#   and strips any "<plugin>:" prefix before comparing to the case's skill name.
#
# RUNS: triggering is stochastic, so each prompt runs RUNS times and the verdict
# is the majority result (best-of-3 by default, i.e. 2 of 3). Set
# TRIGGER_EVAL_RUNS=1 for a quick, cheap check when you don't need CI stability.
#
# GATE: the run passes when at least 90% of cases match their expectation.
# Triggering is stochastic even under the majority vote, so the gate absorbs a
# borderline case without letting a real regression through. A case may set
# "must_pass": true; any must-pass failure fails the run regardless of the
# pooled rate.
#
# Exit codes: 0 = pass rate >= 90% with no infrastructure failures and no
# must-pass failures; 1 = pass rate below the gate, a must-pass failure, or
# any infrastructure failure (a missing credential, a malformed corpus, or
# zero discovered cases); an unmeasured case is never absorbed by the gate.
#
# Usage: run-trigger-evals.sh [--model <id>]
#   --model <id>   Model to test triggering against. Defaults to
#                  ${TRIGGER_EVAL_MODEL:-claude-haiku-4-5}.
#   TRIGGER_EVAL_WORKDIR, when set, receives the transcripts and per-run
#   stderr captures in a unique run-XXXXXX subdirectory (CI points it at a
#   path it can upload on failure); cleanup only ever removes that
#   subdirectory, never the caller's directory. Otherwise a mktemp directory
#   is used.

set -uo pipefail
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RUNS="${TRIGGER_EVAL_RUNS:-3}"
MODEL="${TRIGGER_EVAL_MODEL:-claude-haiku-4-5}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model)
      [[ $# -ge 2 ]] || { echo "ERROR: --model requires a value." >&2; exit 1; }
      MODEL="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,/^[^#]/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)
      echo "ERROR: unknown argument '$1'." >&2; exit 1 ;;
  esac
done

# Dependency and auth pre-flight (fail closed). The scratch config dir below has
# no ambient login, so a credential must be exported; in CI that is the
# ANTHROPIC_API_KEY secret. The value is never echoed.
command -v claude >/dev/null 2>&1 || { echo "ERROR: the 'claude' CLI is not on PATH. Install @anthropic-ai/claude-code." >&2; exit 1; }
command -v jq     >/dev/null 2>&1 || { echo "ERROR: 'jq' is not on PATH." >&2; exit 1; }
if [[ -z "${ANTHROPIC_API_KEY:-}" && -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]]; then
  echo "ERROR: export ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN before running (a scratch CLAUDE_CONFIG_DIR has no ambient login)." >&2
  exit 1
fi

# detect_fired <transcript>: print the bare fired skill names (sorted, unique),
# one per line. Returns non-zero on an infrastructure failure (a truncated run
# with no result event, or an auth/API error that produced no real measurement),
# so the caller can tell "the skill stayed silent" apart from "the run never
# happened". A turn-cap kill (error_max_turns) is a valid measurement: the Skill
# routing decision lands on the first turn, so the fired set is already complete.
detect_fired() {
  local t="$1" subtype is_error
  jq -e 'select(.type=="result")' "$t" >/dev/null 2>&1 || return 1
  subtype="$(jq -r 'select(.type=="result")|.subtype'  "$t" 2>/dev/null)"
  is_error="$(jq -r 'select(.type=="result")|.is_error' "$t" 2>/dev/null)"
  [[ "$is_error" == "true" && "$subtype" != "error_max_turns" ]] && return 1
  jq -r 'select(.type=="assistant")
         | .message.content[]?
         | select(.type=="tool_use" and .name=="Skill")
         | .input.skill' "$t" 2>/dev/null \
    | sed 's/.*://' | sort -u
  return 0
}

# Scratch dirs (created only after the guards pass, so an early exit leaks
# nothing). Config dir isolates the plugin load; workdir holds the transcripts
# and per-run stderr captures (TRIGGER_EVAL_WORKDIR overrides it so CI can
# upload it as an artifact on failure). A caller-supplied dir is resolved to
# an absolute path (the run subshells cd into the workdir, so a relative path
# would split transcript writes and reads across directories) and the run
# works in a unique run-XXXXXX subdirectory of it, so cleanup never deletes
# content this run did not create.
export CLAUDE_CONFIG_DIR
CLAUDE_CONFIG_DIR="$(mktemp -d)"
if [[ -n "${TRIGGER_EVAL_WORKDIR:-}" ]]; then
  if ! mkdir -p "$TRIGGER_EVAL_WORKDIR" \
     || ! WORKDIR="$(cd "$TRIGGER_EVAL_WORKDIR" && pwd)" \
     || ! WORKDIR="$(mktemp -d "$WORKDIR/run-XXXXXX")"; then
    echo "ERROR: cannot prepare TRIGGER_EVAL_WORKDIR '$TRIGGER_EVAL_WORKDIR'." >&2
    rm -rf "$CLAUDE_CONFIG_DIR"
    exit 1
  fi
else
  WORKDIR="$(mktemp -d)"
fi

pass=0; fail=0; infra=0; must_fail=0; fired_seen=0; n=0

for evals_file in "$REPO_ROOT"/plugins/*/evals/trigger-evals.json; do
  [[ -e "$evals_file" ]] || continue
  # Fail closed on a malformed corpus: a jq parse error inside the process
  # substitution feeding the loop below is otherwise unobservable, so a broken
  # file would silently contribute zero cases.
  if ! jq -e 'type=="array" and length>0
              and all(.[]; (.skill|type=="string" and length>0)
                       and (.prompt|type=="string" and length>0)
                       and (.should_trigger|type=="boolean")
                       and ((.must_pass // false)|type=="boolean"))' "$evals_file" >/dev/null 2>&1; then
    echo "ERROR: $evals_file is not a non-empty array of {skill, prompt, should_trigger[, must_pass]} cases." >&2
    rm -rf "$CLAUDE_CONFIG_DIR" "$WORKDIR"
    exit 1
  fi
  plugin_dir="$(cd "$(dirname "$evals_file")/.." && pwd)"   # plugins/<plugin>

  while IFS= read -r row; do
    skill="$(jq -r '.skill'          <<<"$row")"
    prompt="$(jq -r '.prompt'        <<<"$row")"
    expected="$(jq -r '.should_trigger' <<<"$row")"
    must="$(jq -r '.must_pass // false' <<<"$row")"

    fired_count=0; infra_fail=0
    for ((k = 1; k <= RUNS; k++)); do
      transcript="$WORKDIR/case${n}-run${k}.jsonl"
      # One retry per run so a single transient API error cannot red the whole
      # leg; stderr lands beside the transcript for diagnosis.
      run_ok=0
      for _ in 1 2; do
        ( cd "$WORKDIR" && claude -p "$prompt" \
            --plugin-dir "$plugin_dir" \
            --model "$MODEL" \
            --max-turns 3 \
            --output-format stream-json --verbose \
            < /dev/null > "$transcript" 2>"$WORKDIR/case${n}-run${k}.err" )
        if fired="$(detect_fired "$transcript")"; then
          run_ok=1; break
        fi
      done
      if [[ "$run_ok" -ne 1 ]]; then
        infra_fail=1; break
      fi
      [[ -n "$fired" ]] && fired_seen=1
      grep -qxF "$skill" <<<"$fired" && fired_count=$((fired_count + 1))
    done

    n=$((n + 1))
    if [[ "$infra_fail" -eq 1 ]]; then
      echo "FAIL  [$skill] INFRA (no result event / auth failure) :: $prompt"
      fail=$((fail + 1)); infra=$((infra + 1)); continue
    fi

    # Majority vote. With RUNS=1 this is simply "fired at least once".
    if (( fired_count * 2 > RUNS )); then triggered=true; else triggered=false; fi
    if [[ "$triggered" == "$expected" ]]; then
      echo "PASS  [$skill] should_trigger=$expected :: $prompt"
      pass=$((pass + 1))
    else
      tag=""
      [[ "$must" == "true" ]] && { must_fail=$((must_fail + 1)); tag=" [must-pass]"; }
      echo "FAIL$tag  [$skill] expected=$expected got=$triggered ($fired_count/$RUNS fired) :: $prompt"
      fail=$((fail + 1))
    fi
  done < <(jq -c '.[]' "$evals_file")
done

total=$((pass + fail))
echo
if [[ "$total" -eq 0 ]]; then
  echo "ERROR: no trigger-eval cases discovered under plugins/*/evals/trigger-evals.json." >&2
  rm -rf "$CLAUDE_CONFIG_DIR" "$WORKDIR"
  exit 1
fi
echo "$pass passed, $fail failed ($((pass * 100 / total))% pass rate, gate is 90%)"
[[ "$must_fail" -gt 0 ]] && echo "$must_fail must-pass case(s) failed (any must-pass failure fails the run)"

rm -rf "$CLAUDE_CONFIG_DIR"
if [[ "$infra" -gt 0 || "$must_fail" -gt 0 ]] || (( pass * 100 < total * 90 )); then
  if [[ "$fired_seen" -eq 0 ]]; then
    echo "NOTE: no Skill tool_use event was observed in ANY run; if positives failed en masse, suspect a stream-json contract change in the CLI, not the skills." >&2
  fi
  echo "transcripts kept for inspection under: $WORKDIR" >&2
  exit 1
fi
rm -rf "$WORKDIR"
exit 0
