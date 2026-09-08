#!/usr/bin/env bash
#
# run-output-evals.sh: output-contract evals for the agent plugins.
#
# Trigger evals (run-trigger-evals.sh) ask "did the right skill fire?". Output
# evals ask the next question: did the skill's OUTPUT honor the skill contract?
# For every case in each plugin's evals/output-evals.json, the runner plays the
# case's scripted user turns through the Claude Code CLI with only that plugin
# loaded, then grades the transcript and any generated files against the case's
# grader list. A case passes only when the target skill fired AND every grader
# passed.
#
# WHY --plugin-dir AND A SCRATCH CONFIG DIR
#   Same isolation contract as the trigger runner: --plugin-dir loads the
#   plugin straight from the repo, and a throwaway CLAUDE_CONFIG_DIR hides the
#   developer's ambient skills, so the observed behavior is attributable to
#   the plugin under test. A scratch config has no ambient login, so a
#   credential must be exported.
#
# MULTI-TURN CASES
#   Several contracts only resolve across turns (a design confirm, the door
#   question, the support-contract checkpoint), so a case carries an ordered
#   "turns" array of user messages. Turn 1 starts a session; later turns
#   continue it via `claude -p --resume <session_id>`, re-extracting the
#   session id after every turn. Each turn writes its own turnN.jsonl
#   transcript so graders can scope assertions to a turn.
#
# GRADERS (closed set; the corpus schema is validated up front)
#   assistant_grep      grep over the assistant's TEXT blocks only (never the
#                       raw JSONL: tool payloads and user turns would
#                       false-positive). Fields: pattern, expect
#                       (present|absent), fixed (default false), turn
#                       (default all).
#   tool_use            match tool_use events by tool name (or "*") and an
#                       optional ERE over the compact input JSON. Fields:
#                       tool, input_pattern, exclude_pattern (drop matches of
#                       this ERE before counting), expect, count, turn.
#   file_exists         find generated files by basename glob under the case
#                       work dir (target/ excluded). Fields: glob, expect,
#                       identical_to (repo-relative path; every match must be
#                       byte-identical to it).
#   file_grep           grep files matched by basename glob. Fields: glob,
#                       pattern, expect (present|absent|all_files), fixed.
#   java_disclaimer     every generated .java starts with the AI-assisted
#                       disclaimer line and the checklist pointer; fails when
#                       no .java exists. No fields.
#   compile             `mvn -q -B compile` on the shallowest generated
#                       pom.xml; the exit code is the verdict. No fields.
#   maven_release_match the generated pom carries the live sol-jcsmp
#                       <release> from repo1.maven.org metadata. No fields.
#   llm_judge           one tool-free judge completion on
#                       OUTPUT_EVAL_JUDGE_MODEL returning a strict
#                       {"verdict","reason"} JSON. Fields: criteria, include
#                       (subset of assistant_text, webfetch_urls,
#                       webfetch_results; default assistant_text).
#   live_verify         run the generated project's own verify.sh (roundtrip
#                       stage, Quickstart single-project mode) against the
#                       broker named by the OUTPUT_EVAL_BROKER_* variables.
#                       verify.sh exit 0 passes; exit 2 (a doc-traceable
#                       broker or credential error) is INFRA; anything else
#                       fails. Skips, and says so, when no broker is
#                       configured. No fields.
#
# RUNS: output cases are expensive (a full Implement flow runs 30+ turns plus
# Maven), so OUTPUT_EVAL_RUNS defaults to 1. Set it higher for a majority-vote
# stability study; the verdict is then the majority result per case.
#
# GATE: the run passes when at least 90% of cases pass. A case may set
# "must_pass": true; any must-pass failure fails the run regardless of the
# pooled rate, and so does any infrastructure failure.
#
# Exit codes: 0 = pass rate >= 90% with no infrastructure failures and no
# must-pass failures; 1 = pass rate below the gate, a must-pass failure, or
# any infrastructure failure (a missing credential or tool, a malformed
# corpus, an unparseable judge verdict, or zero discovered cases); an
# unmeasured case is never absorbed by the gate.
#
# Usage: run-output-evals.sh [--model <id>] [--case <id>[,<id>...]]...
#   --model <id>   Subject model. Defaults to
#                  ${OUTPUT_EVAL_MODEL:-claude-sonnet-5}. Run the suite for
#                  BOTH claude-sonnet-5 and claude-opus-5 before a PR.
#   --case <id>    Run only the named case(s); repeatable or comma-separated.
#   OUTPUT_EVAL_JUDGE_MODEL   Judge model (default claude-sonnet-5). Keep it
#                             fixed across subject legs so leg diffs are
#                             attributable to the subject model.
#   OUTPUT_EVAL_WORKDIR, when set, receives the transcripts, generated
#   projects, and judge bookkeeping in a unique run-XXXXXX subdirectory;
#   cleanup only ever removes that subdirectory. Otherwise a mktemp
#   directory is used. Kept (and printed) whenever the run fails.
#   OUTPUT_EVAL_BROKER_HOST, OUTPUT_EVAL_BROKER_VPN, OUTPUT_EVAL_BROKER_USER,
#   OUTPUT_EVAL_BROKER_PASSWORD enable the live_verify grader (host as
#   tcp://<host>:55555). Set all four or none: a partial set is an error.
#   OUTPUT_EVAL_BROKER_ENV names a shell file of KEY=value lines that the
#   runner sources first when it exists (default
#   ~/.config/solace-evals/broker.env); keep it outside the repository. The
#   values reach only verify.sh, as CLI args, never the subject model.

set -uo pipefail
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RUNS="${OUTPUT_EVAL_RUNS:-1}"
MODEL="${OUTPUT_EVAL_MODEL:-claude-sonnet-5}"
JUDGE_MODEL="${OUTPUT_EVAL_JUDGE_MODEL:-claude-sonnet-5}"
GRADER_TYPES='["assistant_grep","tool_use","file_exists","file_grep","java_disclaimer","compile","maven_release_match","llm_judge","live_verify"]'
# Headless -p denies unapproved tools, which would distort the measured
# behavior, so the subject gets the full list the skills declare. Bash is
# unrestricted by design (agreed for this local-only suite); every invocation
# runs in a scratch work dir.
ALLOWED_TOOLS=(Skill Read Glob Grep Write Edit Bash WebFetch TodoWrite)

CASE_FILTER=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --model)
      [[ $# -ge 2 ]] || { echo "ERROR: --model requires a value." >&2; exit 1; }
      MODEL="$2"; shift 2 ;;
    --case)
      [[ $# -ge 2 ]] || { echo "ERROR: --case requires a value." >&2; exit 1; }
      IFS=',' read -r -a _ids <<<"$2"
      [[ ${#_ids[@]} -gt 0 ]] && CASE_FILTER+=("${_ids[@]}")
      shift 2 ;;
    -h|--help)
      sed -n '2,/^[^#]/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)
      echo "ERROR: unknown argument '$1'." >&2; exit 1 ;;
  esac
done

# Dependency and auth pre-flight (fail closed), before any mktemp so an early
# exit leaks nothing. The credential value is never echoed.
command -v claude >/dev/null 2>&1 || { echo "ERROR: the 'claude' CLI is not on PATH. Install @anthropic-ai/claude-code." >&2; exit 1; }
command -v jq     >/dev/null 2>&1 || { echo "ERROR: 'jq' is not on PATH." >&2; exit 1; }
command -v curl   >/dev/null 2>&1 || { echo "ERROR: 'curl' is not on PATH." >&2; exit 1; }
if [[ -z "${ANTHROPIC_API_KEY:-}" && -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]]; then
  echo "ERROR: export ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN before running (a scratch CLAUDE_CONFIG_DIR has no ambient login)." >&2
  exit 1
fi

# Live-verify broker (opt-in, tri-state). Source the env file when present, then
# count the four variables: none = skip loudly, all = run, a partial set = error,
# so a typo in one name can never degrade to a silent skip. The values must not
# reach the subject model (the compile anchor asserts compile-only behavior), so
# the export attribute is stripped: they stay shell variables handed to verify.sh.
BROKER_ENV="${OUTPUT_EVAL_BROKER_ENV:-$HOME/.config/solace-evals/broker.env}"
# shellcheck source=/dev/null
[[ -f "$BROKER_ENV" ]] && source "$BROKER_ENV"
BROKER_VARS=(OUTPUT_EVAL_BROKER_HOST OUTPUT_EVAL_BROKER_VPN OUTPUT_EVAL_BROKER_USER OUTPUT_EVAL_BROKER_PASSWORD)
missing=()
for v in "${BROKER_VARS[@]}"; do [[ -n "${!v:-}" ]] || missing+=("$v"); done
if [[ ${#missing[@]} -eq 0 ]]; then
  LIVE_VERIFY=run
elif [[ ${#missing[@]} -eq ${#BROKER_VARS[@]} ]]; then
  LIVE_VERIFY=skip
else
  echo "ERROR: partial broker configuration: set all four OUTPUT_EVAL_BROKER_* variables or none (missing: ${missing[*]})." >&2
  exit 1
fi
export -n "${BROKER_VARS[@]}"

# in_filter <id>: 0 when no filter is set or the id is listed.
in_filter() {
  [[ ${#CASE_FILTER[@]} -eq 0 ]] && return 0
  local want
  for want in "${CASE_FILTER[@]}"; do [[ "$want" == "$1" ]] && return 0; done
  return 1
}

# --- transcript extraction helpers -----------------------------------------
# All of them read turnN.jsonl files under $RUN_DIR and never the raw stream:
# assistant text and tool_use events are jq-extracted so grep patterns cannot
# false-positive on tool payloads or on the scripted user turns.

# turn_files <turn|all>: print the transcript paths for the scope, in order.
turn_files() {
  local t
  if [[ "$1" == "all" ]]; then
    for ((t = 1; t <= NTURNS; t++)); do
      [[ -e "$RUN_DIR/turn${t}.jsonl" ]] && echo "$RUN_DIR/turn${t}.jsonl"
    done
  else
    [[ -e "$RUN_DIR/turn$1.jsonl" ]] && echo "$RUN_DIR/turn$1.jsonl"
  fi
  return 0
}

assistant_text() { # <turn|all>
  local f
  while IFS= read -r f; do
    jq -r 'select(.type=="assistant") | .message.content[]? | select(.type=="text") | .text' "$f" 2>/dev/null
  done < <(turn_files "$1")
}

tool_events() { # <turn|all>: one "name<TAB>compact-input-json" line per tool_use
  local f
  while IFS= read -r f; do
    jq -r 'select(.type=="assistant") | .message.content[]? | select(.type=="tool_use")
           | [.name, (.input | tojson)] | @tsv' "$f" 2>/dev/null
  done < <(turn_files "$1")
}

webfetch_urls() {
  local f
  while IFS= read -r f; do
    jq -r 'select(.type=="assistant") | .message.content[]? | select(.type=="tool_use" and .name=="WebFetch")
           | .input.url // (.input | tojson)' "$f" 2>/dev/null
  done < <(turn_files all) | sort -u
}

webfetch_results() {
  local f
  while IFS= read -r f; do
    jq -rs '([.[] | select(.type=="assistant") | .message.content[]?
              | select(.type=="tool_use" and .name=="WebFetch") | .id]) as $ids
            | .[] | select(.type=="user") | .message.content[]?
            | select(.type=="tool_result" and ((.tool_use_id // "") as $t | $ids | index($t)))
            | .content
            | if type=="array" then (.[]? | select(.type=="text") | .text)
              elif type=="string" then . else tostring end' "$f" 2>/dev/null
  done < <(turn_files all)
}

# turn_ok <transcript>: 0 = a real measurement; 1 = infrastructure failure
# (no result event, or an error other than the turn cap). A turn-cap kill
# (error_max_turns) is a valid measurement, tagged so a budget problem is
# distinguishable from a behavior failure.
turn_ok() {
  local t="$1" subtype is_error
  jq -e 'select(.type=="result")' "$t" >/dev/null 2>&1 || return 1
  subtype="$(jq -r 'select(.type=="result") | .subtype' "$t" 2>/dev/null)"
  is_error="$(jq -r 'select(.type=="result") | .is_error' "$t" 2>/dev/null)"
  [[ "$subtype" == "error_max_turns" ]] && MAXTURNS_HIT=1
  [[ "$is_error" == "true" && "$subtype" != "error_max_turns" ]] && return 1
  return 0
}

# find_files <basename-glob>: generated files under the work dir, excluding
# Maven build output. "*" means any file.
find_files() {
  find "$WORK" -type f -name "$1" -not -path '*/target/*' 2>/dev/null
}

shallowest_pom() {
  find_files pom.xml | awk -F/ '{print NF, $0}' | sort -n | head -1 | cut -d' ' -f2-
}

resolve_sol_jcsmp_release() {
  curl -s --max-time 20 https://repo1.maven.org/maven2/com/solacesystems/sol-jcsmp/maven-metadata.xml \
    | grep -oE '<release>[^<]+</release>' | sed -E 's/<\/?release>//g'
}

# --- graders ----------------------------------------------------------------
# grade_one <grader-json>: 0 pass, 1 fail (a measurement), 2 infrastructure.
# Failure/infra explanation lands in GRADER_DETAIL.
GRADER_DETAIL=""
grade_one() {
  local g="$1" gtype
  gtype="$(jq -r '.type' <<<"$g")"
  GRADER_DETAIL=""

  case "$gtype" in
    assistant_grep)
      local pattern expect fixed turn text found=0 gopts=(-q)
      pattern="$(jq -r '.pattern' <<<"$g")"
      expect="$(jq -r '.expect // "present"' <<<"$g")"
      fixed="$(jq -r '.fixed // false' <<<"$g")"
      turn="$(jq -r '.turn // "all"' <<<"$g")"
      [[ "$fixed" == "true" ]] && gopts+=(-F) || gopts+=(-E)
      text="$(assistant_text "$turn")"
      grep "${gopts[@]}" -- "$pattern" <<<"$text" && found=1
      if [[ "$expect" == "present" && "$found" -eq 0 ]]; then
        GRADER_DETAIL="assistant text (turn $turn) lacks: $pattern"; return 1
      elif [[ "$expect" == "absent" && "$found" -eq 1 ]]; then
        GRADER_DETAIL="assistant text (turn $turn) contains forbidden: $pattern"; return 1
      fi
      return 0 ;;

    tool_use)
      local tool ipat xpat expect count turn events matches m
      tool="$(jq -r '.tool' <<<"$g")"
      ipat="$(jq -r '.input_pattern // ""' <<<"$g")"
      xpat="$(jq -r '.exclude_pattern // ""' <<<"$g")"
      expect="$(jq -r '.expect // "present"' <<<"$g")"
      count="$(jq -r '.count // ""' <<<"$g")"
      turn="$(jq -r '.turn // "all"' <<<"$g")"
      events="$(tool_events "$turn")"
      if [[ "$tool" == "*" ]]; then matches="$events"
      else matches="$(awk -F'\t' -v t="$tool" '$1 == t' <<<"$events")"; fi
      if [[ -n "$ipat" ]]; then matches="$(grep -E -- "$ipat" <<<"$matches" || true)"; fi
      if [[ -n "$xpat" ]]; then matches="$(grep -vE -- "$xpat" <<<"$matches" || true)"; fi
      m="$(grep -c . <<<"$matches" || true)"
      if [[ "$expect" == "present" ]]; then
        if [[ -n "$count" ]]; then
          [[ "$m" -eq "$count" ]] && return 0
          GRADER_DETAIL="tool $tool${ipat:+ ~ $ipat} used $m time(s), expected $count"; return 1
        fi
        [[ "$m" -gt 0 ]] && return 0
        GRADER_DETAIL="tool $tool${ipat:+ ~ $ipat} never used"; return 1
      else
        [[ "$m" -eq 0 ]] && return 0
        GRADER_DETAIL="forbidden tool use: $tool${ipat:+ ~ $ipat} ($m match(es))"; return 1
      fi ;;

    file_exists)
      local glob expect ident files f ok=1
      glob="$(jq -r '.glob' <<<"$g")"
      expect="$(jq -r '.expect // "present"' <<<"$g")"
      ident="$(jq -r '.identical_to // ""' <<<"$g")"
      files="$(find_files "$glob")"
      if [[ "$expect" == "absent" ]]; then
        [[ -z "$files" ]] && return 0
        GRADER_DETAIL="forbidden file(s) on disk matching $glob: $(head -3 <<<"$files" | tr '\n' ' ')"; return 1
      fi
      [[ -z "$files" ]] && { GRADER_DETAIL="no file matching $glob was generated"; return 1; }
      if [[ -n "$ident" ]]; then
        while IFS= read -r f; do
          cmp -s "$f" "$REPO_ROOT/$ident" || { ok=0; GRADER_DETAIL="$f is not byte-identical to $ident"; }
        done <<<"$files"
        [[ "$ok" -eq 1 ]] || return 1
      fi
      return 0 ;;

    file_grep)
      local glob pattern expect fixed files f gopts=(-q) matched=0 total=0 miss=""
      glob="$(jq -r '.glob' <<<"$g")"
      pattern="$(jq -r '.pattern' <<<"$g")"
      expect="$(jq -r '.expect // "present"' <<<"$g")"
      fixed="$(jq -r '.fixed // false' <<<"$g")"
      [[ "$fixed" == "true" ]] && gopts+=(-F) || gopts+=(-E)
      files="$(find_files "$glob")"
      if [[ -z "$files" ]]; then
        [[ "$expect" == "absent" ]] && return 0
        GRADER_DETAIL="no file matching $glob to grep for: $pattern"; return 1
      fi
      while IFS= read -r f; do
        total=$((total + 1))
        if grep "${gopts[@]}" -- "$pattern" "$f"; then matched=$((matched + 1)); else miss="$f"; fi
      done <<<"$files"
      case "$expect" in
        present)
          [[ "$matched" -gt 0 ]] && return 0
          GRADER_DETAIL="$glob file(s) lack: $pattern"; return 1 ;;
        absent)
          [[ "$matched" -eq 0 ]] && return 0
          GRADER_DETAIL="$glob file(s) contain forbidden: $pattern"; return 1 ;;
        all_files)
          [[ "$matched" -eq "$total" ]] && return 0
          GRADER_DETAIL="$miss lacks: $pattern"; return 1 ;;
      esac ;;

    java_disclaimer)
      local files f bad=""
      files="$(find_files '*.java')"
      [[ -z "$files" ]] && { GRADER_DETAIL="no .java files were generated"; return 1; }
      while IFS= read -r f; do
        head -3 "$f" | grep -qF 'AI-assisted code. Review before production use.' || bad="$f (missing disclaimer line)"
        head -4 "$f" | grep -qF 'See the verification checklist: solace-verification-checklist.md' || bad="$f (missing checklist pointer)"
      done <<<"$files"
      [[ -z "$bad" ]] && return 0
      GRADER_DETAIL="$bad"; return 1 ;;

    compile)
      local pom rc
      pom="$(shallowest_pom)"
      [[ -z "$pom" ]] && { GRADER_DETAIL="no generated pom.xml to compile"; return 1; }
      if command -v timeout >/dev/null 2>&1; then
        timeout 600 mvn -q -B -f "$pom" compile > "$RUN_DIR/mvn-compile.log" 2>&1; rc=$?
      else
        mvn -q -B -f "$pom" compile > "$RUN_DIR/mvn-compile.log" 2>&1; rc=$?
      fi
      [[ "$rc" -eq 0 ]] && return 0
      GRADER_DETAIL="mvn compile failed (exit $rc), log: $RUN_DIR/mvn-compile.log"; return 1 ;;

    maven_release_match)
      local rel pom
      rel="$(resolve_sol_jcsmp_release)"
      [[ -z "$rel" ]] && { GRADER_DETAIL="could not resolve sol-jcsmp <release> from repo1.maven.org"; return 2; }
      pom="$(shallowest_pom)"
      [[ -z "$pom" ]] && { GRADER_DETAIL="no generated pom.xml to check against release $rel"; return 1; }
      grep -qF "$rel" "$pom" && return 0
      GRADER_DETAIL="pom does not carry the live sol-jcsmp release $rel"; return 1 ;;

    llm_judge)
      local criteria includes pfile jfile attempt raw verdict reason
      criteria="$(jq -r '.criteria' <<<"$g")"
      includes="$(jq -r '(.include // ["assistant_text"]) | join(" ")' <<<"$g")"
      JUDGE_N=$((JUDGE_N + 1))
      pfile="$RUN_DIR/judge${JUDGE_N}.prompt"
      jfile="$RUN_DIR/judge${JUDGE_N}.json"
      {
        echo 'You are grading an AI assistant transcript against one criterion.'
        echo 'Respond with ONLY a JSON object: {"verdict":"pass"|"fail","reason":"<one line>"}.'
        echo 'The verdict is "fail" when the criterion is violated, "pass" otherwise.'
        echo
        echo "CRITERION: $criteria"
        if grep -qw assistant_text <<<"$includes"; then
          echo; echo '--- ASSISTANT TEXT (all turns, in order) ---'
          assistant_text all | head -c 51200
        fi
        if grep -qw webfetch_urls <<<"$includes"; then
          echo; echo '--- URLS FETCHED VIA WebFetch (deduplicated; empty means none) ---'
          webfetch_urls
        fi
        if grep -qw webfetch_results <<<"$includes"; then
          echo; echo '--- WebFetch RESULT CONTENT (truncated) ---'
          webfetch_results | head -c 30720
        fi
      } > "$pfile"
      for attempt in 1 2; do
        claude -p "$(cat "$pfile")" --model "$JUDGE_MODEL" --max-turns 1 \
          --output-format json < /dev/null > "$jfile" 2>"$RUN_DIR/judge${JUDGE_N}.err"
        raw="$(jq -r '.result // empty' "$jfile" 2>/dev/null | tr '\n' ' ' | grep -oE '\{[^{}]*"verdict"[^{}]*\}' | head -1)"
        verdict="$(jq -r '.verdict // empty' <<<"$raw" 2>/dev/null)"
        reason="$(jq -r '.reason // empty' <<<"$raw" 2>/dev/null)"
        if [[ "$verdict" == "pass" ]]; then return 0; fi
        if [[ "$verdict" == "fail" ]]; then GRADER_DETAIL="judge: ${reason:-no reason given}"; return 1; fi
      done
      GRADER_DETAIL="judge verdict unparseable after 2 attempts ($jfile)"; return 2 ;;

    live_verify)
      local pom proj log rc
      if [[ "$LIVE_VERIFY" != "run" ]]; then LIVE_SKIPPED=1; return 0; fi
      pom="$(shallowest_pom)"
      [[ -z "$pom" ]] && { GRADER_DETAIL="no generated pom.xml to verify against the broker"; return 1; }
      proj="$(dirname "$pom")"
      [[ -f "$proj/verify.sh" ]] || { GRADER_DETAIL="no verify.sh beside $pom"; return 1; }
      log="$RUN_DIR/live-verify.log"
      if command -v timeout >/dev/null 2>&1; then
        ( cd "$proj" && timeout 600 bash ./verify.sh roundtrip "$OUTPUT_EVAL_BROKER_HOST" "$OUTPUT_EVAL_BROKER_VPN" "$OUTPUT_EVAL_BROKER_USER" "$OUTPUT_EVAL_BROKER_PASSWORD" ) > "$log" 2>&1; rc=$?
      else
        ( cd "$proj" && bash ./verify.sh roundtrip "$OUTPUT_EVAL_BROKER_HOST" "$OUTPUT_EVAL_BROKER_VPN" "$OUTPUT_EVAL_BROKER_USER" "$OUTPUT_EVAL_BROKER_PASSWORD" ) > "$log" 2>&1; rc=$?
      fi
      case "$rc" in
        0) return 0 ;;
        2) GRADER_DETAIL="verify.sh roundtrip hit a broker or credential error (exit 2), log: $log"; return 2 ;;
        *) GRADER_DETAIL="verify.sh roundtrip failed (exit $rc), log: $log"; return 1 ;;
      esac ;;

    *)
      GRADER_DETAIL="unknown grader type '$gtype'"; return 2 ;;
  esac
}

# --- scratch dirs (created only after the guards pass) -----------------------
export CLAUDE_CONFIG_DIR
CLAUDE_CONFIG_DIR="$(mktemp -d)"
if [[ -n "${OUTPUT_EVAL_WORKDIR:-}" ]]; then
  if ! mkdir -p "$OUTPUT_EVAL_WORKDIR" \
     || ! WORKDIR="$(cd "$OUTPUT_EVAL_WORKDIR" && pwd)" \
     || ! WORKDIR="$(mktemp -d "$WORKDIR/run-XXXXXX")"; then
    echo "ERROR: cannot prepare OUTPUT_EVAL_WORKDIR '$OUTPUT_EVAL_WORKDIR'." >&2
    rm -rf "$CLAUDE_CONFIG_DIR"
    exit 1
  fi
else
  WORKDIR="$(mktemp -d)"
fi

pass=0; fail=0; infra=0; must_fail=0; LIVE_NEEDED=0

for evals_file in "$REPO_ROOT"/plugins/*/evals/output-evals.json; do
  [[ -e "$evals_file" ]] || continue
  # Fail closed on a malformed corpus: a jq parse error inside the process
  # substitution feeding the loop below is otherwise unobservable, so a broken
  # file would silently contribute zero cases.
  if ! jq -e --argjson types "$GRADER_TYPES" '
        type=="array" and length>0
        and ([.[].id] | length == (unique | length))
        and all(.[];
              (.id    | type=="string" and length>0)
          and (.skill | type=="string" and length>0)
          and (.turns | type=="array" and length>0 and all(.[]; type=="string" and length>0))
          and ((.must_pass // false) | type=="boolean")
          and ((.max_turns // 25)    | type=="number")
          and (.graders | type=="array" and length>0
               and all(.[]; type=="object" and (.type as $t | $types | index($t)))))' \
        "$evals_file" >/dev/null 2>&1; then
    echo "ERROR: $evals_file is not a non-empty array of {id, skill, turns, graders[, max_turns, must_pass]} cases with unique ids and known grader types." >&2
    rm -rf "$CLAUDE_CONFIG_DIR" "$WORKDIR"
    exit 1
  fi
  plugin_dir="$(cd "$(dirname "$evals_file")/.." && pwd)"   # plugins/<plugin>

  # Unknown --case ids are an error, not a silent zero-case run.
  if [[ ${#CASE_FILTER[@]} -gt 0 ]]; then
    for want in "${CASE_FILTER[@]}"; do
      jq -e --arg id "$want" 'any(.[]; .id == $id)' "$evals_file" >/dev/null 2>&1 \
        || { echo "ERROR: --case '$want' not found in $evals_file." >&2; rm -rf "$CLAUDE_CONFIG_DIR" "$WORKDIR"; exit 1; }
    done
  fi

  # Maven is only required when a selected case compiles, checks the pom, or
  # runs verify.sh. The live-verify banner prints before any case starts so the
  # operator sees the broker state in the first seconds, not after an hour.
  needs_mvn=0; needs_live=0
  while IFS= read -r row; do
    cid="$(jq -r '.id' <<<"$row")"
    in_filter "$cid" || continue
    jq -e '.graders | any(.type=="compile" or .type=="maven_release_match" or .type=="live_verify")' <<<"$row" >/dev/null 2>&1 && needs_mvn=1
    jq -e '.graders | any(.type=="live_verify")' <<<"$row" >/dev/null 2>&1 && needs_live=1
  done < <(jq -c '.[]' "$evals_file")
  if [[ "$needs_mvn" -eq 1 ]] && ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: 'mvn' is not on PATH and a selected case carries a compile, maven_release_match, or live_verify grader." >&2
    rm -rf "$CLAUDE_CONFIG_DIR" "$WORKDIR"
    exit 1
  fi
  if [[ "$needs_live" -eq 1 ]]; then
    LIVE_NEEDED=1
    if [[ "$LIVE_VERIFY" == "run" ]]; then
      echo "live verify: ENABLED against $OUTPUT_EVAL_BROKER_HOST"
    else
      echo "live verify: SKIPPED (set the four OUTPUT_EVAL_BROKER_* variables, or fill $BROKER_ENV, to enable)"
    fi
  fi

  while IFS= read -r case_json; do
    case_id="$(jq -r '.id' <<<"$case_json")"
    in_filter "$case_id" || continue
    skill="$(jq -r '.skill' <<<"$case_json")"
    must="$(jq -r '.must_pass // false' <<<"$case_json")"
    max_turns="$(jq -r '.max_turns // 25' <<<"$case_json")"
    NTURNS="$(jq -r '.turns | length' <<<"$case_json")"

    run_pass=0; case_infra=0; fail_details=()
    for ((k = 1; k <= RUNS; k++)); do
      # One whole-case retry on an infrastructure failure, each attempt from a
      # fresh work dir (no mid-conversation resume of a failed turn).
      attempt_ok=0
      for attempt in 1 2; do
        RUN_DIR="$WORKDIR/${case_id}/run${k}-try${attempt}"
        WORK="$RUN_DIR/work"
        mkdir -p "$WORK"
        MAXTURNS_HIT=0
        sid=""
        turn_infra=0
        for ((t = 1; t <= NTURNS; t++)); do
          turn_prompt="$(jq -r ".turns[$((t - 1))]" <<<"$case_json")"
          args=(-p "$turn_prompt" --plugin-dir "$plugin_dir" --model "$MODEL"
                --max-turns "$max_turns" --allowedTools "${ALLOWED_TOOLS[@]}"
                --output-format stream-json --verbose)
          [[ -n "$sid" ]] && args+=(--resume "$sid")
          ( cd "$WORK" && claude "${args[@]}" \
              < /dev/null > "$RUN_DIR/turn${t}.jsonl" 2>"$RUN_DIR/turn${t}.err" )
          if ! turn_ok "$RUN_DIR/turn${t}.jsonl"; then turn_infra=1; break; fi
          # Re-extract after every turn: a print-mode resume can mint a new id.
          new_sid="$(jq -r 'select(.type=="result") | .session_id // empty' "$RUN_DIR/turn${t}.jsonl" | tail -1)"
          [[ -z "$new_sid" ]] && new_sid="$(jq -r 'select(.type=="system" and .subtype=="init") | .session_id // empty' "$RUN_DIR/turn${t}.jsonl" | head -1)"
          [[ -n "$new_sid" ]] && sid="$new_sid"
        done
        if [[ "$turn_infra" -eq 0 ]]; then attempt_ok=1; break; fi
      done
      if [[ "$attempt_ok" -ne 1 ]]; then case_infra=1; break; fi

      # Implicit gate: the target skill must have fired, or the output is not
      # attributable to it and every absence grader would pass vacuously.
      fired="$(turn_files all | while IFS= read -r f; do
                 jq -r 'select(.type=="assistant") | .message.content[]?
                        | select(.type=="tool_use" and .name=="Skill") | .input.skill' "$f" 2>/dev/null
               done | sed 's/.*://' | sort -u)"
      this_run_details=()
      if ! grep -qxF "$skill" <<<"$fired"; then
        this_run_details+=("the '$skill' skill never fired (fired: ${fired:-none})")
      fi

      JUDGE_N=0
      LIVE_SKIPPED=0
      gi=0
      while IFS= read -r grader; do
        gi=$((gi + 1))
        gtype="$(jq -r '.type' <<<"$grader")"
        grade_one "$grader"; rc=$?
        if [[ "$rc" -eq 2 ]]; then case_infra=1; this_run_details+=("grader#$gi $gtype INFRA :: $GRADER_DETAIL"); break
        elif [[ "$rc" -eq 1 ]]; then this_run_details+=("grader#$gi $gtype :: $GRADER_DETAIL"); fi
      done < <(jq -c '.graders[]' <<<"$case_json")
      [[ "$case_infra" -eq 1 ]] && { fail_details=("${this_run_details[@]}"); break; }

      [[ "$MAXTURNS_HIT" -eq 1 ]] && this_run_details+=("[max-turns] a turn hit the $max_turns-turn cap")
      if [[ ${#this_run_details[@]} -eq 0 ]]; then
        run_pass=$((run_pass + 1))
      else
        fail_details=("${this_run_details[@]}")
      fi
    done

    if [[ "$case_infra" -eq 1 ]]; then
      echo "FAIL  [$case_id] ($skill) INFRA${fail_details[0]:+ :: ${fail_details[0]}}"
      fail=$((fail + 1)); infra=$((infra + 1)); continue
    fi

    # Majority vote; with RUNS=1 this is simply "the single run passed".
    if (( run_pass * 2 > RUNS )); then
      live_tag=""
      [[ "${LIVE_SKIPPED:-0}" -eq 1 ]] && live_tag=" [live verify skipped: no broker configured]"
      echo "PASS  [$case_id] ($skill)$live_tag"
      pass=$((pass + 1))
    else
      tag=""
      [[ "$must" == "true" ]] && { must_fail=$((must_fail + 1)); tag=" [must-pass]"; }
      echo "FAIL$tag  [$case_id] ($skill) ($run_pass/$RUNS runs passed)"
      for d in "${fail_details[@]}"; do echo "      - $d"; done
      fail=$((fail + 1))
    fi
  done < <(jq -c '.[]' "$evals_file")
done

total=$((pass + fail))
echo
if [[ "$total" -eq 0 ]]; then
  echo "ERROR: no output-eval cases discovered under plugins/*/evals/output-evals.json." >&2
  rm -rf "$CLAUDE_CONFIG_DIR" "$WORKDIR"
  exit 1
fi
echo "$pass passed, $fail failed ($((pass * 100 / total))% pass rate, gate is 90%)"
[[ "$must_fail" -gt 0 ]] && echo "$must_fail must-pass case(s) failed (any must-pass failure fails the run)"
if [[ "$LIVE_NEEDED" -eq 1 ]]; then
  if [[ "$LIVE_VERIFY" == "run" ]]; then
    echo "live verify: ran against $OUTPUT_EVAL_BROKER_HOST"
  else
    echo "live verify: skipped (no broker configured); say so in the PR record"
  fi
fi

rm -rf "$CLAUDE_CONFIG_DIR"
if [[ "$infra" -gt 0 || "$must_fail" -gt 0 ]] || (( pass * 100 < total * 90 )); then
  echo "transcripts and generated projects kept for inspection under: $WORKDIR" >&2
  exit 1
fi
rm -rf "$WORKDIR"
exit 0
