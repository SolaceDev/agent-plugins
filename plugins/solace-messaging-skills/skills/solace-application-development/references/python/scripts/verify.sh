#!/usr/bin/env bash
# verify.sh: run-and-observe verification for the generated Solace Python projects
# (guaranteed pub/sub, direct pub/sub, and direct request-reply).
#
# Usage (three modes):
#   Single project (both scripts under ./src, one .venv):
#     verify.sh <publisher|consumer|roundtrip|direct|direct-request-reply> [<host:port> <vpn> <user> [pass]]
#       With no connection arguments, each script reads ./config.json (the only way
#       to reach a tcps:// host, because the trust store path has no argument form).
#       Connection arguments are refused when ./config.json exists, because the
#       scripts prefer config.json and would connect to a different broker.
#   Separate projects (Solace Suggested, or the Custom decoupled-projects knob;
#   each project has its own .venv and reads its own gitignored config.json):
#     verify.sh <publisher|consumer|roundtrip|direct> --subscriber-dir <dir> --publisher-dir <dir>
#     verify.sh direct-request-reply --replier-dir <dir> --requestor-dir <dir>
#   Any other app shape (web app, embedded service; the app reads its own config):
#     verify.sh app
#       Sources ./verify-hooks.sh (generated with the project) for the ONLY
#       app-specific facts: START_CMD, TRIGGER_CMD, READY_MARKER, PASS_MARKER.
#
# What it does: checks every generated script with py_compile, starts the
# long-running role (subscriber or replier) FIRST with the project's own
# .venv interpreter, waits for its readiness marker, runs the foreground role
# (publisher or requestor) and lets it exit on its own, watches each process's
# OWN captured output for the fixed VERIFY: marker lines, sends SIGINT to the
# long-running role to exercise its graceful shutdown, and classifies the
# outcome by exit code:
#   0 = stage passed. For the fixed stages: the marker was seen in this run, the
#       foreground role exited with status 0 on its own, the shutdown line
#       printed, AND the long-running role exited with status 0. For the app
#       stage: READY_MARKER, then a new PASS_MARKER after the trigger, and a
#       bounded teardown (the app's shutdown is not checked).
#   1 = code failure  (py_compile failed, a marker never appeared, a process died
#       before its milestone, the foreground role exited non-zero, the shutdown
#       line never printed, or a run hung and had to be force-killed), OR a
#       precondition miss before any process starts (a usage error, or a missing
#       script, .venv, config.json, or verify-hooks.sh). stderr names which.
#   2 = environment failure (a Solace Python API connection, authentication, or
#       TLS signature appeared in a process log, or the long-running role was
#       still connecting at the readiness deadline with no VERIFY: CONNECTED; the
#       code is not the problem, so the agent must NOT enter the fix loop)
#
# The Python API has no guaranteed request-reply, so there is no
# guaranteed-request-reply stage.
#
# Script resolution: the script does NOT read a build file. It relies on the fixed
# script names that implement-mode.md Step 4 gives every generated project
# (guaranteed_subscriber.py, direct_replier.py, ...), so the same script works
# across generations whatever the function and constant names inside each file.
# Where a role has two fixed names (the pub/sub roles on the minimal-threading
# shape, guaranteed_publisher_blocking.py, guaranteed_subscriber_blocking.py, and
# direct_subscriber_blocking.py; the requestor on its non-blocking variant,
# direct_requestor.py), it runs the default name when present, else the other.
# On the fan-out leaf it drives the first subscriber script only.
#
# This is run-and-observe, NOT a test framework. It does not stand up a broker.
#
# Windows: run it under WSL or Git Bash; it also finds .venv/Scripts/python.exe.
#
# Credential handling (accepted Quickstart tradeoff): connection arguments are a
# pass-through, mirroring the generated scripts' own command-line fallback, so on a
# shared host the password may appear in process listings. With config.json (and
# always in separate-projects mode) no credential appears on the command line. The
# script never echoes the password, never enables shell command tracing, and never
# evals its arguments.

set -euo pipefail

# ── Arguments ──────────────────────────────────────────────────────────────────
# The --subscriber-dir/--replier-dir flag names the LONG-RUNNING role's project (the
# SIGINT target); --publisher-dir/--requestor-dir names the FOREGROUND role's
# project. Both directories default to "." so the single-project path needs no flag.
usage() {
    cat >&2 <<'EOF'
usage:
  # Single project (scripts read ./config.json; pass connection arguments only when there is no config.json):
  verify.sh <publisher|consumer|roundtrip|direct|direct-request-reply> [<host:port> <vpn> <user> [pass]]

  # Separate projects (each project reads its own gitignored config.json):
  verify.sh <publisher|consumer|roundtrip|direct> --subscriber-dir <dir> --publisher-dir <dir>
  verify.sh direct-request-reply --replier-dir <dir> --requestor-dir <dir>

  # Any other app shape (web app, embedded service; reads ./verify-hooks.sh):
  verify.sh app
EOF
    exit 1
}

STAGE="${1:-}"
[ -z "$STAGE" ] && usage
shift || true

SUB_DIR="."       # long-running role's project (subscriber, or replier for request-reply)
PUB_DIR="."       # foreground role's project (publisher, or requestor for request-reply)
TWO_PROJECT=0
SUB_SET=0; PUB_SET=0
CONN_ARGS=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        --subscriber-dir|--replier-dir)
            [ -n "${2:-}" ] || { echo "$1 needs a directory value" >&2; usage; }
            SUB_DIR="$2"; SUB_SET=1; TWO_PROJECT=1; shift 2 ;;
        --publisher-dir|--requestor-dir)
            [ -n "${2:-}" ] || { echo "$1 needs a directory value" >&2; usage; }
            PUB_DIR="$2"; PUB_SET=1; TWO_PROJECT=1; shift 2 ;;
        -*)
            echo "unknown flag: $1" >&2; usage ;;
        *)
            CONN_ARGS+=("$1"); shift ;;
    esac
done

case "$STAGE" in
    publisher|consumer|roundtrip) SUB_SCRIPT=guaranteed_subscriber.py; PUB_SCRIPT=guaranteed_publisher.py ;;
    direct)                       SUB_SCRIPT=direct_subscriber.py;     PUB_SCRIPT=direct_publisher.py ;;
    direct-request-reply)         SUB_SCRIPT=direct_replier.py;        PUB_SCRIPT=direct_requestor_blocking.py ;;
    app) ;;
    guaranteed-request-reply)
        echo "the Solace Python API has no guaranteed request-reply, so there is no $STAGE stage" >&2; usage ;;
    *) echo "unknown stage: $STAGE" >&2; usage ;;
esac

if [ "$STAGE" = "app" ]; then
    { [ "$TWO_PROJECT" -eq 0 ] && [ "${#CONN_ARGS[@]}" -eq 0 ]; } || {
        echo "the app stage takes no arguments; the app reads its own config" >&2; usage; }
elif [ "$TWO_PROJECT" -eq 1 ]; then
    { [ "$SUB_SET" -eq 1 ] && [ "$PUB_SET" -eq 1 ]; } || {
        echo "separate-projects mode needs BOTH role directories" >&2; usage; }
    [ "${#CONN_ARGS[@]}" -eq 0 ] || {
        echo "separate-projects mode takes no connection arguments; each project reads its own config.json" >&2; usage; }
    # Resolve to absolute paths so each role runs from INSIDE its own project
    # (its config.json is read relative to that directory) whatever the cwd.
    SUB_DIR="$(cd "$SUB_DIR" && pwd)"
    PUB_DIR="$(cd "$PUB_DIR" && pwd)"
else
    case "${#CONN_ARGS[@]}" in
        0) [ -f config.json ] || {
               echo "no connection details: pass <host:port> <vpn> <user> [pass], or create config.json from config.example.json" >&2; usage; } ;;
        3|4) [ ! -f config.json ] || {
               echo "config.json exists and the scripts prefer it over connection arguments; omit the arguments, or remove config.json" >&2; usage; } ;;
        *) echo "connection arguments are <host:port> <vpn> <user> [pass]" >&2; usage ;;
    esac
fi

# Every generated script lives under src/ at its project root (implement-mode.md
# Step 4). A role with two fixed names runs the default name when the project has it, else
# the alternate: the pub/sub leaves generate guaranteed_*_blocking.py and
# direct_subscriber_blocking.py on the minimal-threading shape (the design
# summary's Threading field), and the request-reply leaf generates
# direct_requestor.py on its non-blocking variant. The direct publisher has one
# name on both shapes.
resolve_script() {  # <dir> <default> <alternate>; prints the one to run
    if [ ! -f "$1/src/$2" ] && [ -f "$1/src/$3" ]; then echo "$3"; else echo "$2"; fi
}
case "$STAGE" in
    publisher|consumer|roundtrip)
        SUB_SCRIPT=$(resolve_script "$SUB_DIR" "$SUB_SCRIPT" guaranteed_subscriber_blocking.py)
        PUB_SCRIPT=$(resolve_script "$PUB_DIR" "$PUB_SCRIPT" guaranteed_publisher_blocking.py) ;;
    direct)
        SUB_SCRIPT=$(resolve_script "$SUB_DIR" "$SUB_SCRIPT" direct_subscriber_blocking.py) ;;
    direct-request-reply)
        PUB_SCRIPT=$(resolve_script "$PUB_DIR" "$PUB_SCRIPT" direct_requestor.py) ;;
esac
[ "$STAGE" = "app" ] || { SUB_SCRIPT="src/$SUB_SCRIPT"; PUB_SCRIPT="src/$PUB_SCRIPT"; }

# Each project runs with its own virtual environment's interpreter, never the
# system python3 (implement-mode.md Step 3).
venv_python() {
    if [ -x "$1/.venv/bin/python" ]; then
        echo "$1/.venv/bin/python"
    elif [ -x "$1/.venv/Scripts/python.exe" ]; then
        echo "$1/.venv/Scripts/python.exe"
    fi
}

if [ "$STAGE" != "app" ]; then
    for pair in "$SUB_DIR:$SUB_SCRIPT" "$PUB_DIR:$PUB_SCRIPT"; do
        d="${pair%:*}"; s="${pair##*:}"
        [ -f "$d/$s" ] || { echo "no $s found in: $d (implement-mode.md Step 4 fixes the script names)" >&2; exit 1; }
        [ -n "$(venv_python "$d")" ] || {
            echo "no virtual environment in: $d (create it: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt)" >&2; exit 1; }
    done
    SUB_PY="$(venv_python "$SUB_DIR")"
    PUB_PY="$(venv_python "$PUB_DIR")"
fi

# ── Preflight conformance warnings (warn-only; never changes the exit contract) ──
# Reports the generation-conformance misses that the verification checklist's
# "Generation conformance" group records: a missing tailored checklist, scripts
# without the AI-assisted disclaimer header, and a solace-pubsubplus pin that
# drifted from the release PyPI reports now.
preflight() {
    local d="$1" f missing="" pin rel
    if [ ! -f "$d/solace-verification-checklist.md" ]; then
        echo "PREFLIGHT WARN: $d/solace-verification-checklist.md is missing (every generation must emit it; implement-mode.md Step 4)" >&2
    fi
    for f in "$d"/src/*.py; do
        [ -f "$f" ] || continue
        head -n 1 "$f" | grep -qxF '# AI-assisted code. Review before production use.' || missing="$missing$f"$'\n'
    done
    if [ -n "$missing" ]; then
        echo "PREFLIGHT WARN: generated scripts missing the AI-assisted disclaimer header:" >&2
        printf '%s' "$missing" >&2
    fi
    if [ -f "$d/requirements.txt" ]; then
        pin=$(grep -oE '^solace-pubsubplus==[^[:space:]]+' "$d/requirements.txt" 2>/dev/null | sed 's/^solace-pubsubplus==//' | head -1) || true
        rel=$(curl -s --max-time 5 https://pypi.org/pypi/solace-pubsubplus/json 2>/dev/null \
            | python3 -c 'import json, sys; print(json.load(sys.stdin)["info"]["version"])' 2>/dev/null) || true
        if [ -z "${pin:-}" ]; then
            echo "PREFLIGHT WARN: $d/requirements.txt does not pin solace-pubsubplus==<version> (implement-mode.md Step 3)" >&2
        elif [ -n "${rel:-}" ] && [ "$pin" != "$rel" ]; then
            echo "PREFLIGHT WARN: requirements.txt pins solace-pubsubplus $pin in $d, but PyPI reports $rel as the latest release" >&2
        fi
    fi
}
if [ "$TWO_PROJECT" -eq 1 ]; then
    preflight "$SUB_DIR"
    preflight "$PUB_DIR"
else
    preflight "."
fi

# ── Live-run heads-up (SKILL.md Invariant 6): name the target and the side effects ─
# The host comes from the connection arguments, else from the long-running role's
# config.json (the app stage's own config is not read).
config_host() {
    [ -f "$1/config.json" ] || return 0
    grep -oE '"solace\.messaging\.transport\.host"[[:space:]]*:[[:space:]]*"[^"]*"' "$1/config.json" 2>/dev/null \
        | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/' || true
}
TARGET_HOST="${CONN_ARGS[0]:-}"
if [ -z "$TARGET_HOST" ] && [ "$STAGE" != "app" ]; then
    TARGET_HOST="$(config_host "$SUB_DIR")"
fi
echo "── Live-run heads-up: stage '$STAGE' starts processes that connect to broker ${TARGET_HOST:-<the host in the app config>}; broker-side effects can include provisioned durable queues, opened connections, and published messages ──"

# ── Environment-failure signatures (doc-traceable API symbols) ──────────────────
# The API's named error classes and the SOLCLIENT_SUBCODE_* names it logs when the
# broker or the transport rejects the session. Observed live with solace-pubsubplus:
#   wrong password    AuthenticationError, SOLCLIENT_SUBCODE_LOGIN_FAILURE
#   wrong VPN         SOLCLIENT_SUBCODE_MSG_VPN_NOT_ALLOWED
#   unresolvable host ServiceUnreachableError
#   unroutable host   Session connect timeout (SOLCLIENT_SUBCODE_TIMEOUT, also used by non-connect timeouts)
#   refused port      SOLCLIENT_SUBCODE_COMMUNICATION_ERROR
#   no trust store    SOLCLIENT_SUBCODE_FAILED_LOADING_TRUSTSTORE
#   wrong CA          SOLCLIENT_SUBCODE_UNTRUSTED_CERTIFICATE
# AuthorizationError, CLIENT_USERNAME_IS_SHUTDOWN, and INVALID_VIRTUAL_ADDRESS are
# the remaining broker rejections of a session. A match means the broker,
# credentials, or trust store are wrong, NOT the code, so exit 2 bypasses the fix loop.
ENV_SIGNATURES='AuthenticationError|AuthorizationError|ServiceUnreachableError|SOLCLIENT_SUBCODE_LOGIN_FAILURE|SOLCLIENT_SUBCODE_MSG_VPN_NOT_ALLOWED|SOLCLIENT_SUBCODE_CLIENT_USERNAME_IS_SHUTDOWN|SOLCLIENT_SUBCODE_INVALID_VIRTUAL_ADDRESS|SOLCLIENT_SUBCODE_COMMUNICATION_ERROR|SOLCLIENT_SUBCODE_FAILED_LOADING_TRUSTSTORE|SOLCLIENT_SUBCODE_UNTRUSTED_CERTIFICATE|Session connect timeout'

# Every baked sample's signal handler prints this line on SIGINT. Seeing it in the
# long-running role's log is the observable proof that the graceful shutdown ran.
SHUTDOWN_LINE='Shutdown signal received'

# The baked guaranteed subscriber logs this line and exits when receiver.start()
# fails to provision or bind the queue, which is what a client without the
# endpoint-create permission sees. The code is not the problem: exit 2.
ENDPOINT_DENIED='Could not bind to queue'

TIMEOUT_S=30        # marker-watch deadline
PUBLISH_WAIT_S=30   # foreground-role deadline before escalating to a code failure
SHUTDOWN_WAIT_S=15  # bounded post-SIGINT wait before escalating to SIGTERM/SIGKILL

# A long-running role that is still alive at the readiness deadline and never
# printed VERIFY: CONNECTED is still connecting: an unreachable host or a blocked
# port, where the connect attempt (and its retries) outlasts TIMEOUT_S without
# logging an error. That is the environment, not the code, so exit 2.
classify_stalled_connect() {  # <pid> <log> <ready 0|1>
    if [ "$status" -ne 2 ] && [ "$3" -eq 0 ] && kill -0 "$1" 2>/dev/null && ! grep -qF "VERIFY: CONNECTED" "$2"; then
        status=2
        CONNECT_STALLED=1
    fi
}

# Print the count of <marker> in <log> once it has stopped changing for 2 seconds
# (bounded by TIMEOUT_S), so messages an earlier run left on a durable queue, which
# arrive just after the bind, are all counted before the publisher starts.
settled_count() {  # <log> <marker>
    local n m settle_deadline=$(( SECONDS + TIMEOUT_S ))
    n=$(grep -cF "$2" "$1" || true)
    while [ "$SECONDS" -lt "$settle_deadline" ]; do
        sleep 2
        m=$(grep -cF "$2" "$1" || true)
        [ "$m" -eq "$n" ] && break
        n=$m
    done
    echo "$n"
}

# ── 1. py_compile first (a syntax error is a code failure, exit 1) ──────────────
compile_project() {
    local d="$1" py="$2" files=()
    local f
    for f in "$d"/src/*.py; do [ -f "$f" ] && files+=("$f"); done
    [ "${#files[@]}" -gt 0 ] || return 0
    echo "── Checking $d with py_compile ──"
    if ! "$py" -m py_compile "${files[@]}"; then
        echo "py_compile failed in $d (code failure, exit 1)" >&2
        exit 1
    fi
}
if [ "$STAGE" = "app" ]; then
    if [ -n "$(venv_python .)" ]; then
        compile_project . "$(venv_python .)"
    else
        echo "── No .venv in the working directory; skipping py_compile (the app stage runs through its own START_CMD) ──"
    fi
else
    compile_project "$SUB_DIR" "$SUB_PY"
    [ "$PUB_DIR" = "$SUB_DIR" ] || compile_project "$PUB_DIR" "$PUB_PY"
fi

# ── Per-process logs: each role writes its own log, because both print ─────────
# VERIFY: CONNECTED and a merged log could not say which process reached a marker.
SUB_LOG="$(mktemp)"
PUB_LOG="$(mktemp)"

# If the script itself dies (agent-harness timeout, Ctrl-C, CI cancellation, or a
# set -e exit), the backgrounded long-running role would keep running detached and
# hold its queue. The EXIT trap stops both roles and removes the logs. SIGKILL of
# this script is the one residual: `set -m` puts each child in its own process
# group, so a hard kill of this script's group cannot reach them.
cleanup() {
    if [ -n "${SUB_PID:-}" ]; then kill -TERM -- "-$SUB_PID" 2>/dev/null || true; fi
    if [ -n "${PUB_PID:-}" ]; then kill -TERM -- "-$PUB_PID" 2>/dev/null || true; fi
    rm -f "${SUB_LOG:-}" "${PUB_LOG:-}"
}
trap cleanup EXIT

status=1  # default to code failure until a stage milestone is confirmed
DIRECT_ZERO_RECEIPT=0  # set when the at-most-once burst was lost after a confirmed subscription
RR_NO_REPLY=0  # set when the replier subscribed but no reply came back
CONNECT_STALLED=0  # set when the long-running role was still connecting at the readiness deadline

# ── Launchers ──────────────────────────────────────────────────────────────────
# `set -m` starts each role in its own process group, so the group-directed kills
# below reach the whole role (and a `bash -c` wrapper's child on the app stage).
# In separate-projects mode each role runs from inside its own project, so it reads
# that project's config.json; in single-project mode the connection arguments, when
# given, pass through as the scripts' command-line fallback.
start_role() {  # <dir> <python> <script> <log>
    set -m
    if [ "$TWO_PROJECT" -eq 1 ]; then
        ( cd "$1" && exec "$2" "$3" ) >"$4" 2>&1 &
    else
        "$2" "$3" ${CONN_ARGS[@]+"${CONN_ARGS[@]}"} >"$4" 2>&1 &
    fi
    LAUNCHED_PID=$!
    set +m
}

# Send SIGINT to the process GROUP of $1, poll up to SHUTDOWN_WAIT_S for it to
# exit, then escalate SIGTERM then SIGKILL. Sets the global `rc` to the process's
# real exit code, or to the synthetic 124 when it hung and was force-killed.
shutdown_long_running() {
    kill -INT -- "-$1" 2>/dev/null || true
    rc=0
    local hung=0
    local shutdown_deadline=$(( SECONDS + SHUTDOWN_WAIT_S ))
    while kill -0 "$1" 2>/dev/null; do
        if [ "$SECONDS" -ge "$shutdown_deadline" ]; then
            hung=1
            break
        fi
        sleep 1
    done
    if [ "$hung" -eq 1 ]; then
        echo "── Process did not exit ${SHUTDOWN_WAIT_S}s after SIGINT; escalating (SIGTERM, then SIGKILL) ──" >&2
        kill -TERM -- "-$1" 2>/dev/null || true
        sleep 2
        kill -KILL -- "-$1" 2>/dev/null || true
        wait "$1" 2>/dev/null || true
        rc=124  # synthetic "timed out" code; never a clean exit
    else
        wait "$1" 2>/dev/null || rc=$?
        kill -KILL -- "-$1" 2>/dev/null || true  # sweep any group member that outlived the leader
    fi
}

# Wait for the foreground role ($1) to exit on its own, bounded by PUBLISH_WAIT_S.
# A role that hangs is escalated; its missing marker then classifies the run.
wait_foreground() {  # <pid> <label>; sets fg_rc to its exit code, or 124 when it hung
    local deadline=$(( SECONDS + PUBLISH_WAIT_S )) hung=0
    while kill -0 "$1" 2>/dev/null; do
        if [ "$SECONDS" -ge "$deadline" ]; then
            echo "── $2 did not exit within ${PUBLISH_WAIT_S}s; escalating (SIGTERM, then SIGKILL) ──" >&2
            kill -TERM -- "-$1" 2>/dev/null || true
            sleep 2
            kill -KILL -- "-$1" 2>/dev/null || true
            hung=1
            break
        fi
        sleep 1
    done
    fg_rc=0
    wait "$1" 2>/dev/null || fg_rc=$?
    [ "$hung" -eq 0 ] || fg_rc=124
}

# Watch the long-running role's log for <marker> within TIMEOUT_S. Returns 0 when
# the marker appears more often than <seen> times (default 0), so a caller can
# ignore marker lines already in the log. Sets status=2 on an environment signature
# (or the endpoint denial) and returns 1; returns 1 when the process dies or the
# deadline passes.
watch_marker() {  # <log> <pid> <marker> [<seen>]
    local deadline=$(( SECONDS + TIMEOUT_S ))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if grep -qE "$ENV_SIGNATURES" "$1" || grep -qF "$ENDPOINT_DENIED" "$1"; then
            status=2
            return 1
        fi
        [ "$(grep -cF "$3" "$1" || true)" -gt "${4:-0}" ] && return 0
        kill -0 "$2" 2>/dev/null || return 1
        sleep 1
    done
    return 1
}

# A stage passes only if its marker was seen AND the long-running role printed the
# shutdown line AND exited with status 0 (the samples' signal handler makes main()
# return normally, so a clean Python exit is 0).
require_clean_shutdown() {
    if [ "$status" -eq 0 ]; then
        if [ "$rc" -ne 0 ] || ! grep -qF "$SHUTDOWN_LINE" "$SUB_LOG"; then
            status=1
        fi
    fi
}

# ── run_guaranteed_stage: publisher | consumer | roundtrip ──────────────────────
# Start the subscriber FIRST so the durable queue is provisioned and bound before
# anything is published; a persistent message published to a topic with no queue
# subscription is dropped.
run_guaranteed_stage() {
    echo "── Starting subscriber ($SUB_SCRIPT), watching for: VERIFY: QUEUE_BOUND ──"
    start_role "$SUB_DIR" "$SUB_PY" "$SUB_SCRIPT" "$SUB_LOG"
    SUB_PID=$LAUNCHED_PID

    local bound=0
    watch_marker "$SUB_LOG" "$SUB_PID" "VERIFY: QUEUE_BOUND" && bound=1
    classify_stalled_connect "$SUB_PID" "$SUB_LOG" "$bound"
    if [ "$status" -eq 2 ] || [ "$bound" -eq 0 ]; then
        shutdown_long_running "$SUB_PID"
        return
    fi
    [ "$STAGE" = "consumer" ] && status=0

    local published=0 stale=0
    if [ "$STAGE" = "publisher" ] || [ "$STAGE" = "roundtrip" ]; then
        # A receipt already in the log is a message an earlier run left on the durable
        # queue, delivered at bind time; the round trip must see a receipt beyond these.
        stale=$(settled_count "$SUB_LOG" "VERIFY: MESSAGE_RECEIVED")
        [ "$stale" -eq 0 ] || echo "── $stale message(s) left on the queue by an earlier run arrived before the publisher started; they do not count ──" >&2
        echo "── Running publisher ($PUB_SCRIPT), watching for: VERIFY: PUBLISH_ACKED ──"
        start_role "$PUB_DIR" "$PUB_PY" "$PUB_SCRIPT" "$PUB_LOG"
        PUB_PID=$LAUNCHED_PID
        wait_foreground "$PUB_PID" "Publisher"
        if grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; then
            status=2
        elif [ "$fg_rc" -eq 0 ] && grep -qF "VERIFY: PUBLISH_ACKED" "$PUB_LOG"; then
            published=1
            if [ "$STAGE" = "publisher" ]; then status=0; fi
        fi
    fi

    # Confirm receipt in the SUBSCRIBER's log (roundtrip only): one receipt more than
    # before the publisher ran. PUBLISH_ACKED alone does not prove delivery, and a
    # publisher that never acked, exited non-zero, or hung cannot pass the round trip.
    if [ "$STAGE" = "roundtrip" ] && [ "$published" -eq 1 ]; then
        watch_marker "$SUB_LOG" "$SUB_PID" "VERIFY: MESSAGE_RECEIVED" "$stale" && status=0
    fi

    shutdown_long_running "$SUB_PID"
    require_clean_shutdown
}

# ── run_direct_stage: the direct (at-most-once) pub/sub flow ────────────────────
# Direct messages have no broker ACK and no redelivery, so a message published
# before the subscription is live is dropped. Subscriber first, the confirmed
# VERIFY: SUBSCRIBED readiness gate (start() blocks until the broker confirms the
# subscription), a settle window, then the publisher's short burst; the stage
# passes on AT LEAST ONE receipt.
run_direct_stage() {
    echo "── Starting direct subscriber ($SUB_SCRIPT), watching for: VERIFY: SUBSCRIBED ──"
    start_role "$SUB_DIR" "$SUB_PY" "$SUB_SCRIPT" "$SUB_LOG"
    SUB_PID=$LAUNCHED_PID

    local subscribed=0
    watch_marker "$SUB_LOG" "$SUB_PID" "VERIFY: SUBSCRIBED" && subscribed=1
    classify_stalled_connect "$SUB_PID" "$SUB_LOG" "$subscribed"
    if [ "$subscribed" -eq 0 ]; then
        shutdown_long_running "$SUB_PID"
        return
    fi
    sleep 2  # settle window

    echo "── Running direct publisher ($PUB_SCRIPT) ──"
    start_role "$PUB_DIR" "$PUB_PY" "$PUB_SCRIPT" "$PUB_LOG"
    PUB_PID=$LAUNCHED_PID
    wait_foreground "$PUB_PID" "Direct publisher"

    if grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; then
        status=2
    elif [ "$fg_rc" -eq 0 ] && watch_marker "$SUB_LOG" "$SUB_PID" "VERIFY: MESSAGE_RECEIVED"; then
        status=0
    fi
    if [ "$status" -ne 0 ] && [ "$status" -ne 2 ] && [ "$fg_rc" -eq 0 ]; then
        DIRECT_ZERO_RECEIPT=1
    fi

    shutdown_long_running "$SUB_PID"
    require_clean_shutdown
}

# ── run_request_reply_stage: the direct request-reply flow ──────────────────────
# Replier first (the readiness gate on VERIFY: SUBSCRIBED), then the requestor in
# the foreground; it sends, waits for the reply or its timeout, and exits. Pass on
# VERIFY: REPLY_RECEIVED in the requestor's log AND VERIFY: REQUEST_RECEIVED in the
# replier's log, which tells "request never arrived" apart from "reply lost".
run_request_reply_stage() {
    echo "── Starting replier ($SUB_SCRIPT), watching for: VERIFY: SUBSCRIBED ──"
    start_role "$SUB_DIR" "$SUB_PY" "$SUB_SCRIPT" "$SUB_LOG"
    SUB_PID=$LAUNCHED_PID

    local subscribed=0
    watch_marker "$SUB_LOG" "$SUB_PID" "VERIFY: SUBSCRIBED" && subscribed=1
    classify_stalled_connect "$SUB_PID" "$SUB_LOG" "$subscribed"
    if [ "$subscribed" -eq 0 ]; then
        shutdown_long_running "$SUB_PID"
        return
    fi
    sleep 2  # settle window

    echo "── Running requestor ($PUB_SCRIPT), watching for: VERIFY: REPLY_RECEIVED ──"
    start_role "$PUB_DIR" "$PUB_PY" "$PUB_SCRIPT" "$PUB_LOG"
    PUB_PID=$LAUNCHED_PID
    wait_foreground "$PUB_PID" "Requestor"

    if grep -qE "$ENV_SIGNATURES" "$SUB_LOG" || grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; then
        status=2
    elif [ "$fg_rc" -eq 0 ] && grep -qF "VERIFY: REPLY_RECEIVED" "$PUB_LOG" && grep -qF "VERIFY: REQUEST_RECEIVED" "$SUB_LOG"; then
        status=0
    fi
    if [ "$status" -ne 0 ] && [ "$status" -ne 2 ] && [ "$fg_rc" -ne 124 ] && ! grep -qF "VERIFY: REPLY_RECEIVED" "$PUB_LOG"; then
        RR_NO_REPLY=1
    fi

    shutdown_long_running "$SUB_PID"
    require_clean_shutdown
}

# ── run_app_stage: the generic single-app flow (web app / embedded service) ──────
# The generated ./verify-hooks.sh carries the ONLY app-specific facts; this
# function keeps the universal logic. The hooks are sourced in a CHILD bash and
# only the four contract values come back, so a stray line in the generated file
# cannot change this script's own state. TRIGGER_CMD may be a curl against the
# app's own API: the curl is the TRIGGER, the markers render the VERDICT. An
# embedded app may manage shutdown its own way, so a pass is READY_MARKER plus
# PASS_MARKER observed and a bounded teardown.
run_app_stage() {
    if [ ! -f ./verify-hooks.sh ]; then
        echo "app stage needs ./verify-hooks.sh (generated with the project; defines START_CMD, TRIGGER_CMD, READY_MARKER, PASS_MARKER)" >&2
        exit 1
    fi
    eval "$(bash -c '. ./verify-hooks.sh >&2; declare -p START_CMD TRIGGER_CMD READY_MARKER PASS_MARKER 2>/dev/null' || true)"
    local v
    for v in START_CMD TRIGGER_CMD READY_MARKER PASS_MARKER; do
        [ -n "${!v:-}" ] || { echo "verify-hooks.sh must set $v" >&2; exit 1; }
    done

    echo "── Starting app (START_CMD from verify-hooks.sh), watching for: $READY_MARKER ──"
    set -m
    bash -c "$START_CMD" >"$SUB_LOG" 2>&1 &
    SUB_PID=$!
    set +m

    if watch_marker "$SUB_LOG" "$SUB_PID" "$READY_MARKER"; then
        sleep 2  # settle window
        # A PASS_MARKER already in the log (a backlog the app consumed at startup)
        # does not count; the trigger must produce a new one.
        local seen
        seen=$(settled_count "$SUB_LOG" "$PASS_MARKER")
        echo "── Running trigger (TRIGGER_CMD from verify-hooks.sh), watching for: $PASS_MARKER ──"
        set -m
        bash -c "$TRIGGER_CMD" >"$PUB_LOG" 2>&1 &
        PUB_PID=$!
        set +m
        # The trigger's exit code is never part of the verdict; the markers are.
        wait_foreground "$PUB_PID" "Trigger"
        watch_marker "$SUB_LOG" "$SUB_PID" "$PASS_MARKER" "$seen" && status=0
    fi
    shutdown_long_running "$SUB_PID"
}

# ── Stage dispatch ───────────────────────────────────────────────────────────────
case "$STAGE" in
    publisher|consumer|roundtrip)  run_guaranteed_stage ;;
    direct)                        run_direct_stage ;;
    direct-request-reply)          run_request_reply_stage ;;
    app)                           run_app_stage ;;
esac

# A connection error can be logged only after the stage gave up (for example during
# the SIGINT teardown, while the API was still retrying the connect), so look for
# the environment signatures again before classifying a code failure.
if [ "$status" -eq 1 ] && { grep -qE "$ENV_SIGNATURES" "$SUB_LOG" || grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; }; then
    status=2
fi

# ── Print the captured evidence and exit classified (the EXIT trap removes the logs) ─
echo "── Captured subscriber output ──────────────────────────────────────"
cat "$SUB_LOG"
echo "── Captured publisher output ───────────────────────────────────────"
cat "$PUB_LOG"
echo "────────────────────────────────────────────────────────────────────"

case "$status" in
    0)
        if [ "$STAGE" = "app" ]; then
            echo "PASS (app): the readiness and pass markers were observed in the app's own captured output, and the app was stopped with a bounded teardown."
        else
            echo "PASS ($STAGE): the stage milestone was observed in the role's own log, the foreground role exited with status 0, the long-running role printed '$SHUTDOWN_LINE', and it exited with status 0."
        fi
        ;;
    2)
        if [ "$CONNECT_STALLED" -eq 1 ]; then
            echo "ENV FAILURE ($STAGE): the long-running role was still connecting to ${TARGET_HOST:-the broker} after ${TIMEOUT_S}s and never printed VERIFY: CONNECTED. The host is unreachable or a firewall blocks the port (connect retries can stretch this). Check the host and port. NOT entering the fix loop." >&2
        else
            echo "ENV FAILURE ($STAGE): a Solace Python API connection, authentication, or TLS signature (or the queue bind denial) matched in a process log; the broker, credentials, trust store, or client permissions are wrong, not the code. NOT entering the fix loop." >&2
        fi
        ;;
    *)
        if [ "$DIRECT_ZERO_RECEIPT" -eq 1 ]; then
            echo "CODE FAILURE ($STAGE): the subscription was confirmed (VERIFY: SUBSCRIBED) and the direct publisher ran, but not one message of the burst was received (VERIFY: MESSAGE_RECEIVED never appeared). Direct messaging is at-most-once, but a whole burst lost after a confirmed subscription points at the topic subscription or the topic string. Check that the subscriber's subscription matches the publisher's topic." >&2
        elif [ "$RR_NO_REPLY" -eq 1 ]; then
            echo "CODE FAILURE ($STAGE): the replier subscribed (VERIFY: SUBSCRIBED) and the requestor ran and exited, but VERIFY: REPLY_RECEIVED never appeared in the requestor log. If VERIFY: REQUEST_RECEIVED is missing from the replier log, the request never reached the replier: check the request topic against the replier's subscription. If it is present, the reply was lost: check that the replier answers with replier.reply()." >&2
        else
            fg_note=""
            [ "$STAGE" = "app" ] || [ "${fg_rc:-0}" -eq 0 ] || fg_note=" The foreground role exited with status $fg_rc (124: it did not exit on its own and was force-killed)."
            echo "CODE FAILURE ($STAGE): the stage milestone was not observed, a process died before its milestone, the foreground role exited non-zero, the shutdown line did not print, the long-running role exited non-zero, or a run hung and had to be force-killed.$fg_note" >&2
        fi
        ;;
esac

exit "$status"
