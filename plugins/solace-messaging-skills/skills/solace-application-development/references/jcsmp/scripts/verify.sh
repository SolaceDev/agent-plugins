#!/usr/bin/env bash
# verify.sh: run-and-observe verification for the generated JCSMP samples (guaranteed and direct pub/sub, and direct and guaranteed request-reply).
#
# Usage (two modes):
#   Quickstart single-project (apps read CLI args):
#     verify.sh <publisher|consumer|roundtrip|direct|direct-request-reply|guaranteed-request-reply> <host:port> <vpn> <user> [pass]
#   Solace Suggested two-project (each app reads its own gitignored config.json):
#     verify.sh <stage> --subscriber-dir <dir> --publisher-dir <dir>
#     verify.sh <rr-stage> --replier-dir <dir> --requestor-dir <dir>
#   Any other app shape (web app / embedded service; the app reads its own config):
#     verify.sh app
#       Sources ./verify-hooks.sh (generated with the project) for the ONLY
#       app-specific facts: START_CMD (starts the app), TRIGGER_CMD (causes one
#       publish; curl is fine HERE, as the trigger), READY_MARKER, PASS_MARKER.
#       The observer logic (marker watching, timeouts, env classification, the
#       0/1/2 exit contract) stays in this script, identical for every shape.
#
# What it does (guaranteed pub/sub stage; the direct and request-reply stages are
# documented at their own functions below): compiles the generated Maven project, then runs the subscriber
# (long-running) and the publisher (exits on its own) via two named exec
# executions (mvn exec:java@subscriber / mvn exec:java@publisher) without knowing
# the per-generation package. It starts the subscriber FIRST so the durable queue
# is provisioned and bound before anything is published, runs the publisher in the
# foreground and lets it exit on its own, watches each process's OWN captured
# output for a fixed VERIFY: marker line, sends SIGINT to the subscriber to
# exercise its graceful-shutdown hook, and classifies the outcome by exit code:
#   0 = stage passed  (marker seen, the shutdown hook ran, AND the subscriber
#       exited cleanly. A clean exit is exit code 0 OR 130: the JVM reports 130
#       (128+SIGINT) when it terminates AFTER running its shutdown hooks, which
#       is the expected, graceful outcome here)
#   1 = code failure  (compile failed, a marker never appeared, a process died
#       before its milestone, the shutdown hook did not run, or a run hung and
#       had to be force-killed)
#   2 = environment failure (broker/credentials: a doc-traceable JCSMP error
#       signature appeared in either process log; the code is not the problem,
#       so the agent must NOT enter the fix loop)
#
# SIGINT delivery under `mvn exec:java` (the D-06 mechanism, why `set -m`):
# in a NON-interactive shell without job control, a command started in the
# background with `&` inherits SIGINT/SIGQUIT set to SIG_IGN. The HotSpot JVM
# checks signal dispositions at startup and does NOT install its SIGINT
# termination handler when SIGINT is already ignored, so `kill -INT` would be
# silently discarded and the shutdown hook would never run. Enabling job control
# (`set -m`) before backgrounding the run gives the child a DEFAULT SIGINT
# disposition, so `kill -INT` actually reaches the JVM and fires the hook. This
# was confirmed against a live broker (plan 03-01 Task 3): without `set -m`,
# `kill -INT` was a complete no-op and the run hung; with it, the hook ran and
# the JVM exited 130. In the two-process design this applies to the SUBSCRIBER
# launch: the subscriber is the long-running process and the SIGINT target
# (D-07-R8); the publisher exits on its own after the ack and is never SIGINTed.
#
# This is run-and-observe, NOT a test framework. It does not introduce JUnit,
# Testcontainers, integration tests, or assertions, and it does not stand up a
# broker. It is a smoke check that the generated app connects, publishes/acks,
# binds the queue, and round-trips a message against a reachable broker.
#
# Windows: this is bash wrapping `mvn`, not strict POSIX. Windows developers must
# run it under WSL or Git-Bash; there is no native cmd/PowerShell path.
#
# Credential handling (accepted Quickstart tradeoff): in single-project mode the password
# is a pass-through CLI arg, mirroring the generated app's own Quickstart CLI contract
# (<host:port> <message-vpn> <client-username> [password]). On a shared host it
# may appear in process listings. This is flagged in the
# verification checklist's credential-handling item. In two-project (Solace Suggested)
# mode NO connection details are passed on the command line at all: each app reads
# its own gitignored config.json, so no credential appears in a process listing,
# which addresses that checklist item rather than accepting the Quickstart tradeoff. In
# BOTH modes the script never echoes the password, never enables shell
# command-tracing, and never `eval`s its arguments.
#
# Main-class resolution: the script does NOT hardcode a Java package or class.
# It relies on the generated pom exposing each main via a fixed named exec
# execution (set per implement-mode.md Step 4), so `mvn exec:java@subscriber` and
# `mvn exec:java@publisher` resolve their classes from the pom and the script
# passes only `-Dexec.args`. This keeps the baked script genuinely fixed across
# generations, regardless of the package/class names the agent picks.

set -euo pipefail

# ── Arguments ──────────────────────────────────────────────────────────────────
# Two modes. Single-project (Quickstart) passes connection details as positional args and
# runs in the cwd. Two-project (Solace Suggested) passes per-role project directories via
# flags and passes NO connection details: each app reads its own config.json. The
# --subscriber-dir/--replier-dir flag names the LONG-RUNNING role's project (the
# SIGINT target); --publisher-dir/--requestor-dir names the FOREGROUND role's
# project. Both directories default to "." so the single-project path is unchanged.
usage() {
    cat >&2 <<'EOF'
usage:
  # Quickstart single-project (apps read CLI args):
  verify.sh <publisher|consumer|roundtrip|direct|direct-request-reply|guaranteed-request-reply> <host:port> <vpn> <user> [pass]

  # Solace Suggested two-project (each app reads its own gitignored config.json):
  verify.sh <publisher|consumer|roundtrip|direct> --subscriber-dir <dir> --publisher-dir <dir>
  verify.sh <direct-request-reply|guaranteed-request-reply> --replier-dir <dir> --requestor-dir <dir>

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
HOST=""; VPN=""; USER_NAME=""; PASS=""
pos=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --subscriber-dir|--replier-dir)
            [ -n "${2:-}" ] || { echo "$1 needs a directory value" >&2; usage; }
            SUB_DIR="$2"; TWO_PROJECT=1; shift 2 ;;
        --publisher-dir|--requestor-dir)
            [ -n "${2:-}" ] || { echo "$1 needs a directory value" >&2; usage; }
            PUB_DIR="$2"; TWO_PROJECT=1; shift 2 ;;
        -*)
            echo "unknown flag: $1" >&2; usage ;;
        *)
            case "$pos" in
                0) HOST="$1" ;;
                1) VPN="$1" ;;
                2) USER_NAME="$1" ;;
                3) PASS="$1" ;;
                *) echo "too many positional arguments: $1" >&2; usage ;;
            esac
            pos=$(( pos + 1 )); shift ;;
    esac
done

if [ "$TWO_PROJECT" -eq 1 ]; then
    # Both role directories are required, and each must hold a pom.xml. Connection
    # details come from each project's own config.json, not the command line.
    { [ "$SUB_DIR" != "." ] && [ "$PUB_DIR" != "." ]; } || {
        echo "two-project mode needs BOTH role directories" >&2; usage; }
    for d in "$SUB_DIR" "$PUB_DIR"; do
        [ -f "$d/pom.xml" ] || { echo "no pom.xml found in: $d" >&2; exit 1; }
    done
    # Resolve to absolute paths so each role can be launched from INSIDE its own
    # project dir (its config.json is read relative to that dir) regardless of cwd.
    SUB_DIR="$(cd "$SUB_DIR" && pwd)"
    PUB_DIR="$(cd "$PUB_DIR" && pwd)"
elif [ "$STAGE" != "app" ]; then
    [ -z "$HOST" ] && usage
    [ -z "$VPN" ] && usage
    [ -z "$USER_NAME" ] && usage
fi

# ── Preflight conformance warnings (warn-only; never changes the exit contract) ──
# Reports the generation-conformance misses that the verification checklist's
# "Generation conformance" group records: a missing tailored checklist, source
# files without the AI-assisted disclaimer header, a log4j-core below the 2.17.1
# Log4Shell floor, and a sol-jcsmp version that drifted from the authoritative
# repo1.maven.org metadata. Warnings only: the 0/1/2 contract is untouched.
preflight() {
    local d="$1"
    if [ ! -f "$d/solace-verification-checklist.md" ]; then
        echo "PREFLIGHT WARN: $d/solace-verification-checklist.md is missing (every generation must emit it; implement-mode.md Step 4)" >&2
    fi
    if [ -d "$d/src/main/java" ]; then
        local missing
        missing=$(grep -rL --include='*.java' 'AI-assisted code. Review before production use.' "$d/src/main/java" 2>/dev/null || true)
        if [ -n "$missing" ]; then
            echo "PREFLIGHT WARN: generated source files missing the AI-assisted disclaimer header:" >&2
            echo "$missing" >&2
        fi
    fi
    if [ -f "$d/pom.xml" ]; then
        local l4j lo pomv relv
        l4j=$(grep -A2 '<artifactId>log4j-core</artifactId>' "$d/pom.xml" 2>/dev/null | grep -oE '<version>[^<]+' | head -1 | sed 's/<version>//') || true
        case "${l4j:-}" in
            ''|*'${'*) : ;;  # absent or a Maven property; nothing to compare
            *)
                lo=$(printf '%s\n' "$l4j" '2.17.1' | sort -V 2>/dev/null | head -1) || true
                if [ -n "${lo:-}" ] && [ "$lo" != "2.17.1" ]; then
                    echo "PREFLIGHT WARN: log4j-core $l4j in $d/pom.xml is below the 2.17.1 Log4Shell floor (CVE-2021-44228 family)" >&2
                fi
                ;;
        esac
        pomv=$(grep -A2 '<artifactId>sol-jcsmp</artifactId>' "$d/pom.xml" 2>/dev/null | grep -oE '<version>[^<]+' | head -1 | sed 's/<version>//') || true
        case "${pomv:-}" in
            ''|*'${'*) : ;;  # absent or a Maven property; nothing to compare
            *)
                relv=$(curl -s --max-time 5 https://repo1.maven.org/maven2/com/solacesystems/sol-jcsmp/maven-metadata.xml 2>/dev/null | grep -oE '<release>[^<]+</release>' | sed -E 's/<\/?release>//g') || true
                if [ -n "${relv:-}" ] && [ "$pomv" != "$relv" ]; then
                    echo "PREFLIGHT WARN: pom sol-jcsmp $pomv in $d differs from the authoritative latest GA $relv (resolve from repo1.maven.org metadata, never solrsearch)" >&2
                fi
                ;;
        esac
    fi
}
if [ "$TWO_PROJECT" -eq 1 ]; then
    preflight "$SUB_DIR"
    preflight "$PUB_DIR"
else
    preflight "."
fi

# ── Live-run heads-up (SKILL.md Invariant 6): name the target and the side effects ─
echo "── Live-run heads-up: stage '$STAGE' starts processes that connect to broker ${HOST:-<the host in each per-project config.json / app config>}; broker-side effects can include provisioned durable queues, opened connections, and published messages ──"

# ── Stage → marker mapping ──────────────────────────────────────────────────────
# The generated app emits four milestone markers across its two classes
# (implement-mode.md Step 4):
#   VERIFY: CONNECTED          after session.connect() returns (BOTH classes)
#   VERIFY: PUBLISH_ACKED      in the Publisher's publish ACK callback (PUB_LOG)
#   VERIFY: QUEUE_BOUND        after the Subscriber's flow.start() bound the queue (SUB_LOG)
#   VERIFY: MESSAGE_RECEIVED   in the Subscriber's onReceive after the ACK (SUB_LOG)
# Both processes connect, so both print VERIFY: CONNECTED. It is therefore NOT
# grepped on its own (a merged grep could not say which process connected). Each
# stage waits for a role-specific milestone in that role's OWN log:
#   publisher → VERIFY: PUBLISH_ACKED   in PUB_LOG (the publish ACK callback fired)
#   consumer  → VERIFY: QUEUE_BOUND     in SUB_LOG (flow.start() bound the durable queue)
#   roundtrip → VERIFY: MESSAGE_RECEIVED in SUB_LOG (a published message was consumed + ACKed)
# Reaching any stage marker already proves the connect happened for that process.
# These strings form a fixed contract with the VERIFY: markers that
# implement-mode.md Step 4 instructs the generated app to println. The two MUST
# stay in sync character-for-character.
case "$STAGE" in
    publisher) ;;  # waits for VERIFY: PUBLISH_ACKED in PUB_LOG
    consumer)  ;;  # waits for VERIFY: QUEUE_BOUND in SUB_LOG
    roundtrip) ;;  # waits for VERIFY: MESSAGE_RECEIVED in SUB_LOG
    direct) ;;                    # NEW (waves 2-4): direct pub/sub stage
    direct-request-reply) ;;      # NEW (waves 2-4): direct request-reply stage
    guaranteed-request-reply) ;;  # NEW (waves 2-4): guaranteed request-reply stage
    app) ;;                       # generic single-app stage driven by ./verify-hooks.sh
    *) echo "unknown stage: $STAGE" >&2; usage ;;
esac

# ── Environment-failure signatures (doc-traceable, SAFE-05) ─────────────────────
# Two complementary, doc-traceable signal layers, all API symbols, never
# invented human-readable phrases:
#
#   1. The named API exception CLASSES the broker/transport throw on rejection:
#        - JCSMPErrorResponseException: broker-side rejection that carries a
#          subcode (bad VPN, bad username, login failure). Both baked samples
#          import and handle it (publisher L34/L245, consumer L31/L236). This is
#          the PRIMARY connect-failure anchor: a rejection at session.connect()
#          throws this class and unwinds BEFORE any app handler can render the
#          symbolic subcode name, so the class name is what actually appears in
#          the captured output (verified live: a wrong-VPN run logs
#          "JCSMPErrorResponseException: 403: Message VPN Not Allowed [Subcode:3]"
#          but NOT the symbolic token MSG_VPN_NOT_ALLOWED).
#        - JCSMPTransportException: transport failure with no subcode (host
#          unreachable / DNS / refused). Both samples import it (publisher L43,
#          consumer L37).
#   2. The symbolic JCSMPErrorResponseSubcodeEx subcode names, which appear when
#      a POST-connect app handler renders them via getSubcodeAsString(...)
#      (publisher L247, consumer L238). Kept so a runtime auth/permission NACK
#      after a successful connect is still classified as an environment failure.
#
# These are matched verbatim against BOTH process logs; no message substrings are
# invented. A match means the broker or credentials are wrong, NOT the code, so
# exit 2 bypasses the fix loop.
ENV_SIGNATURES='JCSMPErrorResponseException|JCSMPTransportException|LOGIN_FAILURE|MSG_VPN_NOT_ALLOWED|INVALID_VIRTUAL_ADDRESS|CLIENT_USERNAME_IS_SHUTDOWN'

# The shutdown hook in BOTH baked samples prints this line when SIGINT fires.
# Seeing it in the SUBSCRIBER log is the observable proof the graceful-shutdown
# hook actually ran (the whole point of the D-06 SIGINT exercise, now aimed at
# the subscriber per D-07-R8); a clean exit code alone is not enough.
SHUTDOWN_LINE='Shutdown signal received'

# The baked consumer sample prints this line and exits CLEANLY when the broker
# disallows client-side endpoint management (session.isCapable(ENDPOINT_MANAGEMENT)
# is false, consumer sample lines 119-125; implement-mode.md Step 4 Subscriber
# item 3 keeps that behavior in the generated class). On that path the queue can
# never bind, but the code is NOT the problem: a working app against a
# restrictively configured broker must classify as an environment failure
# (exit 2), never enter the fix loop. Like SHUTDOWN_LINE, this literal is the
# baked sample's own output line, not an invented phrase.
ENDPOINT_DENIED='does not allow client-side endpoint management'

TIMEOUT_S=30        # marker-watch deadline (Claude's discretion per CONTEXT)
PUBLISH_WAIT_S=30   # foreground-publisher deadline before escalating to a code failure
SHUTDOWN_WAIT_S=15  # bounded post-SIGINT wait before escalating to SIGTERM/SIGKILL

# ── Bounded long-running shutdown (shared by the env-failure teardown and STAGE D) ─
# Send SIGINT to the process GROUP of the PID passed as $1, poll up to
# SHUTDOWN_WAIT_S for the JVM to exit on its own, then escalate SIGTERM then
# SIGKILL (also group-directed). A JVM that hangs after SIGINT is an anticipated
# failure mode, so NO teardown path may use a bare unbounded `wait`. Sets the
# global `rc` to the process's real exit code, or to the synthetic 124 when the
# run hung and had to be force-killed (124 is never a clean exit). The PID is a
# parameter so every long-running stage (the guaranteed subscriber, the
# request/reply repliers, and the app stage) can reuse it.
#
# Why the kills target "-$1" (the group), not "$1": every launch site
# backgrounds its process under `set -m`, so each recorded PID leads its own
# process group. For the mvn stages the group is just the JVM, so this is
# equivalent to a PID kill. For the app stage the target may be a `bash -c`
# wrapper: macOS's system bash 3.2 keeps the wrapper alive for a compound
# START_CMD (bash 4.4+ execs the final command), and a PID-directed kill would
# then stop only the wrapper and orphan the app underneath it. Group-directed
# signals reach both.
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
        # The process did not honour SIGINT within the deadline; escalate and fail.
        echo "── Process did not exit ${SHUTDOWN_WAIT_S}s after SIGINT; escalating (SIGTERM, then SIGKILL) ──" >&2
        kill -TERM -- "-$1" 2>/dev/null || true
        sleep 2
        kill -KILL -- "-$1" 2>/dev/null || true
        wait "$1" 2>/dev/null || true
        rc=124  # synthetic "timed out" code; never a clean exit
    else
        # The process exited within the deadline; capture its real exit code.
        wait "$1" 2>/dev/null || rc=$?
        # Sweep any group member that outlived the leader (a wrapper's app child);
        # once the whole group is gone this is a no-op.
        kill -KILL -- "-$1" 2>/dev/null || true
    fi
}

# ── 1. Compile first (a compile failure is a code failure, exit 1) ──────────────
# Single-project mode compiles the cwd project; two-project mode compiles each
# role's project root (a compile failure in either is a code failure, exit 1).
if [ "$TWO_PROJECT" -eq 1 ]; then
    for d in "$SUB_DIR" "$PUB_DIR"; do
        echo "── Compiling $d (mvn -q -f \"$d/pom.xml\" compile) ──"
        if ! mvn -q -f "$d/pom.xml" compile; then
            echo "compile failed in $d (code failure, exit 1)" >&2
            exit 1
        fi
    done
elif [ "$STAGE" = "app" ] && [ ! -f pom.xml ]; then
    echo "── No pom.xml in the working directory; skipping the compile step (the app stage builds through its own START_CMD) ──"
else
    echo "── Compiling (mvn -q compile) ─────────────────────────────────────"
    if ! mvn -q compile; then
        echo "compile failed (code failure, exit 1)" >&2
        exit 1
    fi
fi

# ── Per-process logs (D-07-R9): the subscriber and publisher each write their ───
# own log so every grep targets the role-specific stream. Both processes print
# VERIFY: CONNECTED, so a single merged log could not disambiguate which one
# connected, bound, or acked. The publish-ack marker is read from PUB_LOG; the
# queue-bound and message-received markers from SUB_LOG.
SUB_LOG="$(mktemp)"
PUB_LOG="$(mktemp)"

# If the script itself dies (agent-harness timeout, Ctrl-C, CI cancellation, or
# a set -e exit), the backgrounded subscriber JVM would otherwise keep running
# detached. It holds the EXCLUSIVE queue flow, so an orphan breaks every later
# verify.sh run until manually killed, and both mktemp logs would leak. The
# EXIT trap covers every graceful exit path: bash runs it on normal exit, on
# set -e exits, and on untrapped SIGINT/SIGTERM (verified on bash 3.2 through
# 5.2). SIGKILL is the one residual: no trap can run, and `set -m` has put each
# child in its own process group, so a hard kill of this script's group cannot
# reach them. That orphan risk is inherent to the set -m SIGINT-delivery
# mechanism (header note) and cannot be closed from inside the script. The
# kills are group-directed for the same reason shutdown_long_running's are (see
# its note). For processes already waited on and logs already removed the trap
# is a harmless no-op.
cleanup() {
    if [ -n "${SUB_PID:-}" ]; then kill -TERM -- "-$SUB_PID" 2>/dev/null || true; fi
    if [ -n "${PUB_PID:-}" ]; then kill -TERM -- "-$PUB_PID" 2>/dev/null || true; fi
    rm -f "${SUB_LOG:-}" "${PUB_LOG:-}"
}
trap cleanup EXIT

status=1  # default to code failure until a stage milestone is confirmed
DIRECT_ZERO_RECEIPT=0  # set by run_direct_stage when the at-most-once message dropped post-gate (D-05)
RR_NO_REPLY=0  # set by run_request_reply_stage when the replier subscribed but no reply came back

# ── run_guaranteed_stage: the guaranteed pub/sub flow (publisher|consumer|roundtrip) ─
# The entire STAGE A..D body is lifted here VERBATIM (GEN-06 byte-stability): no
# literal, timeout, grep expression, stage-name spelling, or orchestration order
# inside it changed from the prior linear script. It reads the shared top-level
# SUB_LOG/PUB_LOG and sets the shared top-level status/rc that the classification
# and the final print below consume. Only the wrapping function braces and the
# shutdown_subscriber -> shutdown_long_running "$SUB_PID" rename are new.
run_guaranteed_stage() {
# ── STAGE A: start the SUBSCRIBER first (long-running, the SIGINT target) ────────
# Enable job control (`set -m`) BEFORE backgrounding so the child JVM inherits a
# DEFAULT SIGINT disposition (see the header note): without this, `&` in a
# non-interactive shell sets SIGINT to SIG_IGN, the JVM never installs its
# handler, and the later `kill -INT` is silently discarded. With job control on,
# bash prints harmless job-status lines (e.g. "[1]+  Interrupt") to stderr; we
# tolerate them rather than suppress them. Starting the subscriber first
# provisions the durable queue and binds it before anything is published, so no
# message is silently lost to an unsubscribed topic (D-07-R11).
echo "── Starting subscriber (mvn -q exec:java@subscriber), watching for: VERIFY: QUEUE_BOUND ──"
set -m
if [ "$TWO_PROJECT" -eq 1 ]; then
    ( cd "$SUB_DIR" && exec mvn -q exec:java@subscriber ) >"$SUB_LOG" 2>&1 &
else
    # shellcheck disable=SC2086  # $PASS is intentionally word-split: empty when omitted
    mvn -q exec:java@subscriber -Dexec.args="$HOST $VPN $USER_NAME $PASS" >"$SUB_LOG" 2>&1 &
fi
SUB_PID=$!
set +m

bound=0
deadline=$(( SECONDS + TIMEOUT_S ))
while [ "$SECONDS" -lt "$deadline" ]; do
    if grep -qE "$ENV_SIGNATURES" "$SUB_LOG"; then
        status=2  # environment failure; bypass the fix loop
        break
    fi
    if grep -qF "$ENDPOINT_DENIED" "$SUB_LOG"; then
        status=2  # broker disallows client-side provisioning; environment, not code
        break
    fi
    if grep -qF "VERIFY: QUEUE_BOUND" "$SUB_LOG"; then
        bound=1
        break
    fi
    if ! kill -0 "$SUB_PID" 2>/dev/null; then
        break  # subscriber exited before binding (code failure, status stays 1)
    fi
    sleep 1
done

# If the broker/credentials are wrong the subscriber already proved it; tear it
# down (bounded, same SIGINT/SIGTERM/SIGKILL escalation as STAGE D) and classify
# immediately without running the publisher.
if [ "$status" -eq 2 ]; then
    shutdown_long_running "$SUB_PID"
else
    # The consumer stage passes as soon as the queue is bound.
    if [ "$STAGE" = "consumer" ] && [ "$bound" -eq 1 ]; then
        status=0
    fi

    # ── STAGE B: run the PUBLISHER in the foreground (publisher + roundtrip) ─────
    # The publisher exits on its own after publishing and draining the ack (Plan
    # 01 replaced the ENTER/SIGINT loop with a bounded publish-then-close path),
    # so it is run in the FOREGROUND and waited on; no SIGINT is sent to it. Only
    # run it once the queue is bound, so the message lands on a subscribed queue
    # (subscriber-first ordering, D-07-R11). The wait is BOUNDED: a publisher that
    # hangs past PUBLISH_WAIT_S is escalated and classified as a code failure so
    # the unattended script NEVER hangs.
    if { [ "$STAGE" = "publisher" ] || [ "$STAGE" = "roundtrip" ]; } && [ "$bound" -eq 1 ]; then
        echo "── Running publisher (mvn -q exec:java@publisher), watching for: VERIFY: PUBLISH_ACKED ──"
        set -m
        if [ "$TWO_PROJECT" -eq 1 ]; then
            ( cd "$PUB_DIR" && exec mvn -q exec:java@publisher ) >"$PUB_LOG" 2>&1 &
        else
            # shellcheck disable=SC2086  # $PASS is intentionally word-split: empty when omitted
            mvn -q exec:java@publisher -Dexec.args="$HOST $VPN $USER_NAME $PASS" >"$PUB_LOG" 2>&1 &
        fi
        PUB_PID=$!
        set +m

        pub_hung=0
        pub_deadline=$(( SECONDS + PUBLISH_WAIT_S ))
        # Do NOT leave this bounded loop early on an ENV_SIGNATURES match: the
        # publisher's reconnect-event handling can keep the JVM alive retrying
        # after a transport error is logged, and breaking out while the process
        # is still running would fall through to an unbounded `wait` (a hang the
        # script's own invariant forbids). The deadline plus the SIGTERM/SIGKILL
        # escalation below governs the process lifetime; the ENV_SIGNATURES grep
        # after the process is gone still classifies the env failure (status=2).
        while kill -0 "$PUB_PID" 2>/dev/null; do
            if [ "$SECONDS" -ge "$pub_deadline" ]; then
                pub_hung=1
                break
            fi
            sleep 1
        done

        if [ "$pub_hung" -eq 1 ]; then
            # The publisher did not exit on its own within the deadline; escalate
            # and treat the hang as a code failure (status stays 1).
            echo "── Publisher did not exit within ${PUBLISH_WAIT_S}s; escalating (SIGTERM, then SIGKILL) ──" >&2
            kill -TERM "$PUB_PID" 2>/dev/null || true
            sleep 2
            kill -KILL "$PUB_PID" 2>/dev/null || true
            wait "$PUB_PID" 2>/dev/null || true
        else
            wait "$PUB_PID" 2>/dev/null || true
        fi

        if grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; then
            status=2  # environment failure (broker/credentials), bypass the fix loop
        elif [ "$STAGE" = "publisher" ] && grep -qF "VERIFY: PUBLISH_ACKED" "$PUB_LOG"; then
            status=0  # the publish ACK callback fired
        fi
    fi

    # ── STAGE C: confirm receipt on the SUBSCRIBER's log (roundtrip only) ────────
    if [ "$STAGE" = "roundtrip" ] && [ "$status" -ne 2 ]; then
        rt_deadline=$(( SECONDS + TIMEOUT_S ))
        while [ "$SECONDS" -lt "$rt_deadline" ]; do
            if grep -qE "$ENV_SIGNATURES" "$SUB_LOG"; then
                status=2
                break
            fi
            if grep -qF "VERIFY: MESSAGE_RECEIVED" "$SUB_LOG"; then
                status=0  # a published message was consumed + ACKed
                break
            fi
            if ! kill -0 "$SUB_PID" 2>/dev/null; then
                break  # subscriber died before the message arrived (code failure)
            fi
            sleep 1
        done
    fi

    # ── STAGE D: always SIGINT the SUBSCRIBER to exercise its shutdown hook ──────
    # This runs regardless of outcome so every verification also confirms the baked
    # SIGINT shutdown idiom (D-07-R8: the hook test belongs to the subscriber, the
    # long-running process). The "Shutdown signal received..." line in SUB_LOG is
    # the observable proof the hook ran. The bounded SIGINT-poll-escalate sequence
    # lives in shutdown_subscriber (shared with the env-failure teardown) so the
    # unattended script NEVER hangs; the global rc it sets feeds the clean-exit
    # classification below.
    shutdown_long_running "$SUB_PID"

    # Clean-exit classification (D-06). A stage passes (status 0) only if its marker
    # was seen AND the subscriber's graceful-shutdown hook actually ran AND the
    # subscriber exited cleanly.
    #   - "exited cleanly" = exit code 0 OR 130 (128+SIGINT): a JVM that terminates
    #     after running its shutdown hooks reports 130, which is the expected outcome.
    #   - the shutdown-hook line in SUB_LOG is the observable proof the hook ran (the
    #     whole point of the SIGINT exercise), required for a pass.
    if [ "$status" -eq 0 ]; then
        clean_rc=0
        { [ "$rc" -eq 0 ] || [ "$rc" -eq 130 ]; } && clean_rc=1
        if [ "$clean_rc" -ne 1 ] || ! grep -qF "$SHUTDOWN_LINE" "$SUB_LOG"; then
            # Marker hit, but the hook did not run or the exit was unclean/hung.
            status=1
        fi
    fi
fi
}  # end run_guaranteed_stage

# ── Placeholder stages (NOT implemented in this plan; filled by waves 2-4) ───────
# The dispatch wiring is in place so a later per-pattern increment drops a real
# body here without touching the dispatch front or the byte-stable guaranteed
# flow. Until then each stub echoes that it is not implemented and exits non-zero
# so no new pattern behavior is claimed.
# ── run_direct_stage: the direct (at-most-once) pub/sub flow (D-04/D-05) ──────────
# Direct messaging is at-most-once: no broker ACK, no redelivery, so a message
# published to a not-yet-propagated subscription is simply dropped. This stage drives
# that incidental loss to near-zero with the Pitfall 7 mitigation: subscriber-first +
# a confirmed-subscription readiness gate (the subscriber uses WAIT_FOR_CONFIRM, so
# VERIFY: SUBSCRIBED means the route is live) + a settle window, then a short burst,
# passing on AT LEAST ONE receipt.
#
# It reuses the shared machinery verbatim: ENV_SIGNATURES / ENDPOINT_DENIED
# (the direct subscriber never provisions, so ENDPOINT_DENIED simply never matches,
# harmless), shutdown_long_running for the bounded SIGINT teardown, SHUTDOWN_LINE
# as the hook proof, the clean-exit 0-or-130 gate, and the shared 0/1/2 print. The
# direct subscriber + publisher reuse the <id>subscriber</id> / <id>publisher</id>
# exec ids (same roles as the guaranteed flow). The 0/1/2 contract is preserved
# with NO new outcome class: a post-gate zero-receipt is status 1 (a code failure),
# annotated with a Direct at-most-once caveat in the case-1 message; an
# ENV_SIGNATURES match is still status 2.
run_direct_stage() {
    # ── Start the direct SUBSCRIBER first (long-running, the SIGINT target) ──────
    # Enable job control (set -m) BEFORE backgrounding so the child JVM inherits a
    # DEFAULT SIGINT disposition (see the header note); without it kill -INT is a
    # no-op and the shutdown hook never runs. Subscriber-first means the topic
    # subscription is confirmed before the publish, so the at-most-once message lands
    # on a bound subscription (the D-04 readiness gate).
    echo "── Starting direct subscriber (mvn -q exec:java@subscriber), watching for: VERIFY: SUBSCRIBED ──"
    set -m
    if [ "$TWO_PROJECT" -eq 1 ]; then
        ( cd "$SUB_DIR" && exec mvn -q exec:java@subscriber ) >"$SUB_LOG" 2>&1 &
    else
        # shellcheck disable=SC2086  # $PASS is intentionally word-split: empty when omitted
        mvn -q exec:java@subscriber -Dexec.args="$HOST $VPN $USER_NAME $PASS" >"$SUB_LOG" 2>&1 &
    fi
    SUB_PID=$!
    set +m

    subscribed=0
    deadline=$(( SECONDS + TIMEOUT_S ))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if grep -qE "$ENV_SIGNATURES" "$SUB_LOG"; then
            status=2  # environment failure; bypass the fix loop
            break
        fi
        if grep -qF "$ENDPOINT_DENIED" "$SUB_LOG"; then
            status=2  # broker disallows client-side endpoint management; environment, not code
            break
        fi
        if grep -qF "VERIFY: SUBSCRIBED" "$SUB_LOG"; then
            subscribed=1
            break
        fi
        if ! kill -0 "$SUB_PID" 2>/dev/null; then
            break  # subscriber exited before subscribing (code failure, status stays 1)
        fi
        sleep 1
    done

    if [ "$status" -eq 2 ]; then
        # Broker/credentials are wrong; tear the subscriber down (bounded) and
        # classify immediately without running the publisher.
        shutdown_long_running "$SUB_PID"
    elif [ "$subscribed" -eq 1 ]; then
        # ── Settle window: let the confirmed subscription fully propagate before ─
        # the publish, so the at-most-once message is not lost to a stale route.
        sleep 2

        # ── Run the direct PUBLISHER in the foreground (waited, not SIGINTed) ───────
        # It publishes a short burst of DIRECT messages and self-exits, classified like
        # the guaranteed publisher. The wait is BOUNDED so the unattended script NEVER
        # hangs: a publisher past PUBLISH_WAIT_S is escalated.
        echo "── Running direct publisher (mvn -q exec:java@publisher) ──"
        set -m
        if [ "$TWO_PROJECT" -eq 1 ]; then
            ( cd "$PUB_DIR" && exec mvn -q exec:java@publisher ) >"$PUB_LOG" 2>&1 &
        else
            # shellcheck disable=SC2086  # $PASS is intentionally word-split: empty when omitted
            mvn -q exec:java@publisher -Dexec.args="$HOST $VPN $USER_NAME $PASS" >"$PUB_LOG" 2>&1 &
        fi
        PUB_PID=$!
        set +m

        pub_hung=0
        pub_deadline=$(( SECONDS + PUBLISH_WAIT_S ))
        while kill -0 "$PUB_PID" 2>/dev/null; do
            if [ "$SECONDS" -ge "$pub_deadline" ]; then
                pub_hung=1
                break
            fi
            sleep 1
        done

        if [ "$pub_hung" -eq 1 ]; then
            echo "── Direct publisher did not exit within ${PUBLISH_WAIT_S}s; escalating (SIGTERM, then SIGKILL) ──" >&2
            kill -TERM "$PUB_PID" 2>/dev/null || true
            sleep 2
            kill -KILL "$PUB_PID" 2>/dev/null || true
            wait "$PUB_PID" 2>/dev/null || true
        else
            wait "$PUB_PID" 2>/dev/null || true
        fi

        # ── Pass condition: at least one VERIFY: MESSAGE_RECEIVED in the subscriber ─
        # log within TIMEOUT_S. The direct subscriber receives the burst,
        # so poll the subscriber log after the publisher has finished.
        rt_deadline=$(( SECONDS + TIMEOUT_S ))
        while [ "$SECONDS" -lt "$rt_deadline" ]; do
            if grep -qE "$ENV_SIGNATURES" "$SUB_LOG" || grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; then
                status=2  # environment failure (broker/credentials), bypass the fix loop
                break
            fi
            if grep -qF "VERIFY: MESSAGE_RECEIVED" "$SUB_LOG"; then
                status=0  # at least one direct message was received (D-04 pass)
                break
            fi
            if ! kill -0 "$SUB_PID" 2>/dev/null; then
                break  # subscriber died before any receipt (code failure)
            fi
            sleep 1
        done

        # A post-gate zero-receipt (subscription was confirmed and the publisher ran
        # clean, but not one message of the burst arrived) stays status 1; the case-1 print is
        # annotated with the Direct at-most-once caveat below (D-05). An env match
        # already set status 2 above.
        if [ "$status" -ne 0 ] && [ "$status" -ne 2 ]; then
            DIRECT_ZERO_RECEIPT=1
        fi

        # ── SIGINT the direct subscriber to exercise its graceful-shutdown hook ──
        # Shared bounded SIGINT-poll-escalate; sets the global rc for the clean-exit
        # gate below. The "Shutdown signal received" line in SUB_LOG is the proof.
        shutdown_long_running "$SUB_PID"

        # Clean-exit classification (D-06), shared with the guaranteed stage: a pass
        # (status 0) requires the marker AND the shutdown hook running AND a clean
        # exit (0 or 130).
        if [ "$status" -eq 0 ]; then
            clean_rc=0
            { [ "$rc" -eq 0 ] || [ "$rc" -eq 130 ]; } && clean_rc=1
            if [ "$clean_rc" -ne 1 ] || ! grep -qF "$SHUTDOWN_LINE" "$SUB_LOG"; then
                status=1  # marker hit, but the hook did not run or the exit was unclean/hung
            fi
        fi
    else
        # The subscriber never reached VERIFY: SUBSCRIBED (and it was not an env
        # match): tear it down bounded and leave status at the default code failure.
        shutdown_long_running "$SUB_PID"
    fi
}

# ── run_request_reply_stage <direct|guaranteed>: the request-reply flow ───────────
# Replier-first orchestration (the natural subscriber-first analog): the REPLIER is
# the long-running SIGINT target, the REQUESTOR runs foreground and self-exits after
# the reply. The requestor's blocking request IS the synchronization point.
#
#   1. Start the REPLIER first (backgrounded under set -m), watch its log for
#      VERIFY: SUBSCRIBED (replier readiness, after addSubscription + consumer.start /
#      flow.start) as the readiness gate, reusing the bounded-wait ENV_SIGNATURES +
#      ENDPOINT_DENIED grep loop. The direct replier never provisions, so ENDPOINT_DENIED
#      simply never matches (harmless); the guaranteed replier (13-04) provisions a
#      request endpoint, so ENDPOINT_DENIED classifies a provisioning denial as env (2).
#   2. Run the REQUESTOR foreground via mvn exec:java@requestor; it blocks on the
#      request and self-exits. The wait is BOUNDED so the unattended script NEVER hangs.
#      The direct requestor optionally retries once on a request timeout (D-06).
#   3. Pass condition: VERIFY: REPLY_RECEIVED in the REQUESTOR's own log; also assert
#      VERIFY: REQUEST_RECEIVED in the REPLIER's log (richer evidence: it distinguishes
#      "requestor never sent" from "replier never replied" from "reply lost").
#   4. An ENV_SIGNATURES match maps to status 2; a clean requestor run with no reply
#      maps to status 1 (RR_NO_REPLY annotates the case-1 message).
#   5. SIGINT the replier (shared shutdown_long_running), confirm the shutdown line,
#      classify with the shared 0-or-130 gate; reuse the 0/1/2 print.
#
# The roles use NEW exec ids @requestor / @replier (NOT publisher/subscriber) so the
# guaranteed pub/sub byte-stability (GEN-06) is untouched. The REPLIER's log is
# captured in SUB_LOG (the long-running role) and the REQUESTOR's in PUB_LOG (the
# foreground role), reusing the shared logs and the cleanup trap's TERM-kill.
#
# Both arms share this one function: 13-03 wired the `direct` arm, 13-04 added the
# `guaranteed` arm. The orchestration is IDENTICAL for both modes (replier-first,
# the VERIFY: SUBSCRIBED readiness gate, a foreground requestor, pass on
# VERIFY: REPLY_RECEIVED in the requestor log AND VERIFY: REQUEST_RECEIVED in the
# replier log, a single round-trip with NO burst and NO retry). They differ only in
# the per-process echo labels below. The guaranteed replier PROVISIONS a durable
# request queue, so an ENDPOINT_DENIED match maps to status 2 (env failure, no fix
# loop), inherited for free from the shared literal; the direct replier never
# provisions, so ENDPOINT_DENIED simply never matches (harmless). A single PERSISTENT
# request plus a temp-queue reply is reliable, so the guaranteed arm needs no retry
# either (D-06).
run_request_reply_stage() {
    local rr_mode="${1:-}"
    if [ "$rr_mode" != "direct" ] && [ "$rr_mode" != "guaranteed" ]; then
        echo "unknown request-reply mode: ${rr_mode} (expected direct or guaranteed)" >&2
        exit 1
    fi

    # ── Start the REPLIER first (long-running, the SIGINT target) ────────────────
    # Enable job control (set -m) BEFORE backgrounding so the child JVM inherits a
    # DEFAULT SIGINT disposition (see the header note); without it kill -INT is a
    # no-op and the shutdown hook never runs. Replier-first means the request-topic
    # subscription (direct) or the durable request queue plus its topic subscription
    # (guaranteed) is confirmed before the requestor sends, so the request lands on a
    # bound subscription (the D-04 readiness gate, the request-reply analog).
    echo "── Starting ${rr_mode} replier (mvn -q exec:java@replier), watching for: VERIFY: SUBSCRIBED ──"
    set -m
    if [ "$TWO_PROJECT" -eq 1 ]; then
        ( cd "$SUB_DIR" && exec mvn -q exec:java@replier ) >"$SUB_LOG" 2>&1 &
    else
        # shellcheck disable=SC2086  # $PASS is intentionally word-split: empty when omitted
        mvn -q exec:java@replier -Dexec.args="$HOST $VPN $USER_NAME $PASS" >"$SUB_LOG" 2>&1 &
    fi
    SUB_PID=$!
    set +m

    subscribed=0
    deadline=$(( SECONDS + TIMEOUT_S ))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if grep -qE "$ENV_SIGNATURES" "$SUB_LOG"; then
            status=2  # environment failure; bypass the fix loop
            break
        fi
        if grep -qF "$ENDPOINT_DENIED" "$SUB_LOG"; then
            status=2  # broker disallows client-side endpoint management; environment, not code
            break
        fi
        if grep -qF "VERIFY: SUBSCRIBED" "$SUB_LOG"; then
            subscribed=1
            break
        fi
        if ! kill -0 "$SUB_PID" 2>/dev/null; then
            break  # replier exited before subscribing (code failure, status stays 1)
        fi
        sleep 1
    done

    if [ "$status" -eq 2 ]; then
        # Broker/credentials are wrong; tear the replier down (bounded) and classify
        # immediately without running the requestor.
        shutdown_long_running "$SUB_PID"
    elif [ "$subscribed" -eq 1 ]; then
        # ── Settle window: let the confirmed subscription fully propagate before ─
        # the requestor sends, so the request is not lost to a stale route.
        sleep 2

        # ── Run the REQUESTOR in the foreground (waited, not SIGINTed) ───────────
        # It blocks on the request (the synchronization point) and self-exits after
        # the reply or the timeout. The direct requestor blocks on the Requestor
        # convenience timeout; the guaranteed requestor blocks on a temp-queue
        # flow.receive. Either way the wait is BOUNDED so the unattended script NEVER
        # hangs: a requestor past PUBLISH_WAIT_S is escalated. Single round-trip, NO
        # burst, NO retry (D-06).
        echo "── Running ${rr_mode} requestor (mvn -q exec:java@requestor), watching for: VERIFY: REPLY_RECEIVED ──"
        set -m
        if [ "$TWO_PROJECT" -eq 1 ]; then
            ( cd "$PUB_DIR" && exec mvn -q exec:java@requestor ) >"$PUB_LOG" 2>&1 &
        else
            # shellcheck disable=SC2086  # $PASS is intentionally word-split: empty when omitted
            mvn -q exec:java@requestor -Dexec.args="$HOST $VPN $USER_NAME $PASS" >"$PUB_LOG" 2>&1 &
        fi
        PUB_PID=$!
        set +m

        pub_hung=0
        pub_deadline=$(( SECONDS + PUBLISH_WAIT_S ))
        while kill -0 "$PUB_PID" 2>/dev/null; do
            if [ "$SECONDS" -ge "$pub_deadline" ]; then
                pub_hung=1
                break
            fi
            sleep 1
        done

        if [ "$pub_hung" -eq 1 ]; then
            echo "── ${rr_mode} requestor did not exit within ${PUBLISH_WAIT_S}s; escalating (SIGTERM, then SIGKILL) ──" >&2
            kill -TERM "$PUB_PID" 2>/dev/null || true
            sleep 2
            kill -KILL "$PUB_PID" 2>/dev/null || true
            wait "$PUB_PID" 2>/dev/null || true
        else
            wait "$PUB_PID" 2>/dev/null || true
        fi

        # ── Pass condition: VERIFY: REPLY_RECEIVED in the requestor's OWN log, AND ─
        # the richer VERIFY: REQUEST_RECEIVED in the replier's log. An ENV_SIGNATURES
        # match in either log maps to status 2.
        if grep -qE "$ENV_SIGNATURES" "$SUB_LOG" || grep -qE "$ENV_SIGNATURES" "$PUB_LOG"; then
            status=2  # environment failure (broker/credentials), bypass the fix loop
        elif grep -qF "VERIFY: REPLY_RECEIVED" "$PUB_LOG" && grep -qF "VERIFY: REQUEST_RECEIVED" "$SUB_LOG"; then
            status=0  # the request landed on the replier and the correlated reply came back
        fi

        # A clean requestor run with no reply (subscription was confirmed, the requestor
        # ran and self-exited, but no VERIFY: REPLY_RECEIVED) stays status 1; the case-1
        # print is annotated with the no-reply caveat below. An env match already set 2.
        if [ "$status" -ne 0 ] && [ "$status" -ne 2 ]; then
            RR_NO_REPLY=1
        fi

        # ── SIGINT the replier to exercise its graceful-shutdown hook ────────────
        # Shared bounded SIGINT-poll-escalate; sets the global rc for the clean-exit
        # gate below. The "Shutdown signal received" line in SUB_LOG is the proof.
        shutdown_long_running "$SUB_PID"

        # Clean-exit classification (D-06), shared with the other stages: a pass
        # (status 0) requires the markers AND the replier's shutdown hook running AND a
        # clean exit (0 or 130).
        if [ "$status" -eq 0 ]; then
            clean_rc=0
            { [ "$rc" -eq 0 ] || [ "$rc" -eq 130 ]; } && clean_rc=1
            if [ "$clean_rc" -ne 1 ] || ! grep -qF "$SHUTDOWN_LINE" "$SUB_LOG"; then
                status=1  # markers hit, but the hook did not run or the exit was unclean/hung
            fi
        fi
    else
        # The replier never reached VERIFY: SUBSCRIBED (and it was not an env match):
        # tear it down bounded and leave status at the default code failure.
        shutdown_long_running "$SUB_PID"
    fi
}

# ── run_app_stage: the generic single-app flow (web app / embedded service) ──────
# The shape-agnostic observer. The generated ./verify-hooks.sh carries the ONLY
# app-specific facts (how to start the app, how to cause one publish, which leaf
# markers gate readiness and prove the pass); this function keeps the universal
# logic: marker watching in the app's own captured output, bounded waits, the
# ENV_SIGNATURES classification, and the shared 0/1/2 exit contract. Sourcing the
# generated hooks file runs generated shell by design: the hooks are generation
# output, exactly like the app they drive. TRIGGER_CMD may be a curl against the
# app's own API — the curl is the TRIGGER; the markers render the VERDICT. The
# app stage does not require the samples' shutdown-hook proof line: an embedded
# app may manage shutdown its own way, so a pass is READY_MARKER + PASS_MARKER
# observed and a bounded teardown.
run_app_stage() {
    if [ ! -f ./verify-hooks.sh ]; then
        echo "app stage needs ./verify-hooks.sh (generated with the project; defines START_CMD, TRIGGER_CMD, READY_MARKER, PASS_MARKER)" >&2
        exit 1
    fi
    # shellcheck source=/dev/null  # generated at runtime with the project; nothing to lint statically
    . ./verify-hooks.sh
    local v
    for v in START_CMD TRIGGER_CMD READY_MARKER PASS_MARKER; do
        [ -n "${!v:-}" ] || { echo "verify-hooks.sh must set $v" >&2; exit 1; }
    done

    # Start the app (long-running, the SIGINT target); job control per the header note.
    echo "── Starting app (START_CMD from verify-hooks.sh), watching for: $READY_MARKER ──"
    set -m
    bash -c "$START_CMD" >"$SUB_LOG" 2>&1 &
    SUB_PID=$!
    set +m

    local ready=0
    local deadline=$(( SECONDS + TIMEOUT_S ))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if grep -qE "$ENV_SIGNATURES" "$SUB_LOG"; then
            status=2  # environment failure; bypass the fix loop
            break
        fi
        if grep -qF "$ENDPOINT_DENIED" "$SUB_LOG"; then
            status=2  # broker disallows client-side endpoint management; environment, not code
            break
        fi
        if grep -qF "$READY_MARKER" "$SUB_LOG"; then
            ready=1
            break
        fi
        if ! kill -0 "$SUB_PID" 2>/dev/null; then
            break  # app exited before readiness (code failure, status stays 1)
        fi
        sleep 1
    done

    if [ "$status" -eq 2 ]; then
        shutdown_long_running "$SUB_PID"
    elif [ "$ready" -eq 1 ]; then
        # Settle window, then the trigger (foreground; must return on its own).
        sleep 2
        echo "── Running trigger (TRIGGER_CMD from verify-hooks.sh), watching for: $PASS_MARKER ──"
        bash -c "$TRIGGER_CMD" >"$PUB_LOG" 2>&1 || true

        local pass_deadline=$(( SECONDS + TIMEOUT_S ))
        while [ "$SECONDS" -lt "$pass_deadline" ]; do
            if grep -qE "$ENV_SIGNATURES" "$SUB_LOG"; then
                status=2
                break
            fi
            if grep -qF "$PASS_MARKER" "$SUB_LOG"; then
                status=0  # the pass marker appeared in the app's own captured output
                break
            fi
            if ! kill -0 "$SUB_PID" 2>/dev/null; then
                break  # app died before the pass marker (code failure)
            fi
            sleep 1
        done

        # Bounded teardown (SIGINT, then escalate); no shutdown-line requirement here.
        shutdown_long_running "$SUB_PID"
    else
        shutdown_long_running "$SUB_PID"
    fi
}

# ── Stage-dispatch front ─────────────────────────────────────────────────────────
# Route the validated stage to its per-pattern function. The guaranteed arm holds
# the byte-stable run_guaranteed_stage; the three new arms route to placeholder
# functions until waves 2-4 fill them. publisher|consumer|roundtrip stay spelled
# exactly (referenced by implement-mode.md Step 5).
case "$STAGE" in
    publisher|consumer|roundtrip)  run_guaranteed_stage ;;
    direct)                        run_direct_stage ;;
    direct-request-reply)          run_request_reply_stage direct ;;
    guaranteed-request-reply)      run_request_reply_stage guaranteed ;;
    app)                           run_app_stage ;;
    *) echo "unknown stage: $STAGE" >&2; usage ;;
esac

# ── Print the captured evidence, exit classified (the EXIT trap removes the ────
# temp logs, V8) ─────────────────────────────────────────────────────────────────
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
            echo "PASS ($STAGE): the stage milestone was observed in the role's own log, the subscriber's shutdown hook ran ('$SHUTDOWN_LINE'), and the subscriber exited cleanly (exit 0 or 130)."
        fi
        ;;
    2) echo "ENV FAILURE ($STAGE): a JCSMP connection/auth signature matched in a process log (broker or credentials are wrong, not the code). NOT entering the fix loop." >&2 ;;
    *)
        if [ "$DIRECT_ZERO_RECEIPT" -eq 1 ]; then
            echo "CODE FAILURE ($STAGE): the subscription was confirmed (VERIFY: SUBSCRIBED) and the direct publisher ran clean, but not one message of the burst was received (VERIFY: MESSAGE_RECEIVED never appeared). Direct messaging is at-most-once: a whole burst dropped after a confirmed subscription points at the topic subscription, the topic string, or a slow-consumer egress discard, not a transient race. Check that the subscriber's subscription matches the publisher's topic." >&2
        elif [ "$RR_NO_REPLY" -eq 1 ]; then
            echo "CODE FAILURE ($STAGE): the replier subscribed (VERIFY: SUBSCRIBED) and the requestor ran and self-exited, but no correlated reply came back (VERIFY: REPLY_RECEIVED never appeared in the requestor log). Check the requestor's request topic against the replier's subscription, the reply-to handling (the requestor's createRequestor / the replier's sendReply onto getReplyTo), and whether VERIFY: REQUEST_RECEIVED appeared in the replier log: its absence means the request never reached the replier, its presence means the reply was lost on the way back." >&2
        else
            echo "CODE FAILURE ($STAGE): the stage milestone was not observed, a process died before its milestone, the shutdown hook did not run, or a run hung and had to be force-killed." >&2
        fi
        ;;
esac

exit "$status"
