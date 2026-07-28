#!/usr/bin/env bash
#
# EVAL-02 hard gate: compile the two baked JCSMP reference samples VERBATIM
# against the latest com.solacesystems:sol-jcsmp resolved live from Maven
# Central. The script's exit code IS the gate: exit 0 only if both samples
# compile. A non-compiling sample (or an upstream sol-jcsmp release that breaks
# the compile) is a hard block with no override/skip machinery (D-07, D-08).
#
# Runnable locally with one command from any CWD; doubles as the dev loop (D-06).
# It also runs a short SolaceConnectionConfig smoke test (step 4.5) so the shared
# connection helper's config.json parser and fail-fast paths are exercised at
# run time, not just compiled.
# Resolves the sol-jcsmp version dynamically at run time, so no sol-jcsmp version
# number is committed anywhere (Invariant 3). log4j2 is pinned in the pom.
#
# Mechanism: the fixture pom declares `<sol.jcsmp.version>` as a NON-NUMERIC
# placeholder property; this script overrides it via `-Dsol.jcsmp.version=...`
# on the mvn command line (no sed substitution of the pom). All work happens in
# a throwaway temp dir, so the repo tree is left unmodified.

set -euo pipefail

# Resolve this script's own directory so sample/pom paths are independent of CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POM="${SCRIPT_DIR}/pom.xml"
# references/jcsmp/ lives two levels up from compile-fixture/ (evals/ -> jcsmp/).
REFERENCES_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# Per-sample source paths. Each new baked sample a later per-pattern plan adds is
# a ONE-LINE append here, mirroring this shape:
#   <ROLE>_SRC="${REFERENCES_DIR}/jcsmp-<role>-sample.java"
# then add the same var to the existence-guard loop and the copy roster below.
PUBLISHER_SRC="${REFERENCES_DIR}/jcsmp-guaranteed-publisher-sample.java"
CONSUMER_SRC="${REFERENCES_DIR}/jcsmp-guaranteed-subscriber-sample.java"
DIRECT_PUB_SRC="${REFERENCES_DIR}/jcsmp-direct-publisher-sample.java"
DIRECT_SUB_SRC="${REFERENCES_DIR}/jcsmp-direct-subscriber-sample.java"
DIRECT_REQ_SRC="${REFERENCES_DIR}/jcsmp-direct-requestor-sample.java"
DIRECT_REPLIER_SRC="${REFERENCES_DIR}/jcsmp-direct-replier-sample.java"
GUARANTEED_REQ_SRC="${REFERENCES_DIR}/jcsmp-guaranteed-requestor-sample.java"
GUARANTEED_REPLIER_SRC="${REFERENCES_DIR}/jcsmp-guaranteed-replier-sample.java"
# Shared connection helper (class SolaceConnectionConfig): NOT a role sample, but
# every sample calls SolaceConnectionConfig.load(...), so it must be copied into
# each compile tree below or the samples fail with "cannot find symbol".
CONFIG_SRC="${REFERENCES_DIR}/jcsmp-solace-connection-config.java"

# Existence guard: fail early (and clearly) if any required input is missing,
# including a new sample whose *_SRC var was added above but not yet committed.
# Add each new *_SRC var to this list when extending the roster.
for f in "$POM" "$PUBLISHER_SRC" "$CONSUMER_SRC" "$DIRECT_PUB_SRC" "$DIRECT_SUB_SRC" "$DIRECT_REQ_SRC" "$DIRECT_REPLIER_SRC" "$GUARANTEED_REQ_SRC" "$GUARANTEED_REPLIER_SRC" "$CONFIG_SRC"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: required input not found: $f" >&2
    exit 1
  fi
done

# (1) Resolve the live sol-jcsmp version from the AUTHORITATIVE Maven Central
#     metadata <release> element (the legacy search index lags and is not used
#     here). On any network failure, exit non-zero rather than guessing a version.
echo "Resolving latest sol-jcsmp version from Maven Central..."
SOL_JCSMP_VERSION="$(curl -s --max-time 20 \
  https://repo1.maven.org/maven2/com/solacesystems/sol-jcsmp/maven-metadata.xml \
  | grep -oE '<release>[^<]+</release>' | sed -E 's/<\/?release>//g')"

if [[ -z "${SOL_JCSMP_VERSION// /}" ]]; then
  echo "ERROR: could not resolve the latest sol-jcsmp version from Maven Central" >&2
  echo "       (network failure or unreachable metadata). Not guessing a version." >&2
  exit 1
fi
echo "Resolved sol-jcsmp version: ${SOL_JCSMP_VERSION}"

# (2) Build a throwaway working tree so the repo stays clean and re-runs are
#     idempotent. Clean it up on any exit.
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

SRC_PKG_DIR="${WORK_DIR}/src/main/java/com/solace/samples/jcsmp"
mkdir -p "$SRC_PKG_DIR"
cp "$POM" "${WORK_DIR}/pom.xml"

# (3) Copy the baked samples under their REAL class names (rename on copy).
#     Content is verbatim; only the filename changes so javac/Maven accept them.
#       jcsmp-guaranteed-publisher-sample.java  -> GuaranteedPublisher.java
#       jcsmp-guaranteed-subscriber-sample.java -> GuaranteedSubscriber.java
#     Roster: one cp line per sample, the destination matching the sample's
#     `public class <Name>`. A later per-pattern plan adds its samples here with
#     one cp line each, e.g.
#       cp "$DIRECT_PUB_SRC" "${SRC_PKG_DIR}/DirectPublisher.java"
#     All samples share the com.solace.samples.jcsmp package, so the single
#     fixture pom and the one mvn compile below build the whole roster unchanged.
cp "$PUBLISHER_SRC" "${SRC_PKG_DIR}/GuaranteedPublisher.java"
cp "$CONSUMER_SRC"  "${SRC_PKG_DIR}/GuaranteedSubscriber.java"
cp "$DIRECT_PUB_SRC" "${SRC_PKG_DIR}/DirectPublisher.java"
cp "$DIRECT_SUB_SRC" "${SRC_PKG_DIR}/DirectSubscriber.java"
cp "$DIRECT_REQ_SRC" "${SRC_PKG_DIR}/DirectRequestor.java"
cp "$DIRECT_REPLIER_SRC" "${SRC_PKG_DIR}/DirectReplier.java"
cp "$GUARANTEED_REQ_SRC" "${SRC_PKG_DIR}/GuaranteedRequestor.java"
cp "$GUARANTEED_REPLIER_SRC" "${SRC_PKG_DIR}/GuaranteedReplier.java"
# shared connection helper every sample depends on (one copy for the shared package)
cp "$CONFIG_SRC" "${SRC_PKG_DIR}/SolaceConnectionConfig.java"

# (4) Compile in the working tree. The exit code of this command is the gate.
echo "Compiling baked samples against sol-jcsmp ${SOL_JCSMP_VERSION}..."
mvn -q -B -f "${WORK_DIR}/pom.xml" \
  -Dsol.jcsmp.version="${SOL_JCSMP_VERSION}" \
  compile

echo "PASS: baked samples compile against sol-jcsmp ${SOL_JCSMP_VERSION}"

# (4.5) Runtime smoke test of the shared connection helper. Compiling the helper
#       never executes its config.json parser or CLI-args fallback, so compile the
#       tiny ConfigSmokeTest driver (committed next to this script) against the
#       classes just built in step (4) and RUN it across the parser's real
#       behaviors: config.json round-trip, JSON unescaping, the generic extra-key
#       pass-through, the CLI-args fallback, and the three fail-fast paths (missing
#       key, blank config value, blank CLI arg). Each case runs in its own working
#       dir (the parser reads a CWD-relative config.json) and any case exiting
#       non-zero fails this gate under `set -e`.
SMOKE_SRC="${SCRIPT_DIR}/ConfigSmokeTest.java"
if [[ ! -f "$SMOKE_SRC" ]]; then
  echo "ERROR: required input not found: $SMOKE_SRC" >&2
  exit 1
fi
echo "Running the SolaceConnectionConfig smoke test..."
# Resolve the full runtime classpath (sol-jcsmp + pinned log4j) from the fixture
# pom, then compile the driver into the already-built classes dir and run cases.
mvn -q -B -f "${WORK_DIR}/pom.xml" \
  -Dsol.jcsmp.version="${SOL_JCSMP_VERSION}" \
  dependency:build-classpath -Dmdep.outputFile="${WORK_DIR}/smoke-cp.txt"
SMOKE_CP="${WORK_DIR}/target/classes:$(cat "${WORK_DIR}/smoke-cp.txt")"
javac -cp "$SMOKE_CP" -d "${WORK_DIR}/target/classes" "$SMOKE_SRC"

SMOKE_FILE_DIR="${WORK_DIR}/smoke-file"; mkdir -p "$SMOKE_FILE_DIR"
SMOKE_EMPTY_DIR="${WORK_DIR}/smoke-empty"; mkdir -p "$SMOKE_EMPTY_DIR"
run_smoke() {  # <case> <cwd>
  ( cd "$2" && java -cp "$SMOKE_CP" ConfigSmokeTest "$1" )
}

# 1) a valid config.json round-trips all four connection values
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{ "host": "tcps://smoke.example:55443", "vpn_name": "smoke-vpn", "username": "smoke-user", "password": "smoke-pass" }
JSON
run_smoke file-roundtrip "$SMOKE_FILE_DIR"

# 2) JSON string escapes are unescaped (us\"er\\name -> us"er\name)
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{ "host": "tcps://smoke.example:55443", "vpn_name": "smoke-vpn", "username": "us\"er\\name", "password": "smoke-pass" }
JSON
run_smoke file-escaped "$SMOKE_FILE_DIR"

# 3) a missing required key fails fast
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{ "vpn_name": "smoke-vpn", "username": "smoke-user" }
JSON
run_smoke missing-key "$SMOKE_FILE_DIR"

# 4) a present-but-blank required value fails fast
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{ "host": "", "vpn_name": "smoke-vpn", "username": "smoke-user" }
JSON
run_smoke blank-value "$SMOKE_FILE_DIR"

# 5) with no config.json present, the CLI args are the fallback source
run_smoke args-fallback "$SMOKE_EMPTY_DIR"

# 6) a blank CLI arg fails fast (never connects with a blank host)
run_smoke args-blank "$SMOKE_EMPTY_DIR"

# 7) an extra flat string key (client_name) passes through to JCSMPProperties
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{ "host": "tcps://smoke.example:55443", "vpn_name": "smoke-vpn", "username": "smoke-user", "password": "smoke-pass", "client_name": "smoke-client" }
JSON
run_smoke extra-key-passthrough "$SMOKE_FILE_DIR"

echo "PASS: SolaceConnectionConfig smoke test (parser, fallback, and fail-fast paths)"

# (5) Solace Suggested two-project gate (D-06): prove the SEPARATE-projects Solace Suggested
#     layout builds. Build TWO independent project roots under the same mktemp
#     tree, each with its OWN pom.xml copy and its OWN package dir holding ONE
#     Solace-Suggested-shaped class. There is NO parent POM and NO modules aggregator:
#     each root is a standalone single-module project that compiles on its own.
#     Both roots reuse the ONE live-resolved ${SOL_JCSMP_VERSION} from step (1)
#     via the same -Dsol.jcsmp.version override, and the same version-free pom
#     placeholder mechanism (no sed, no committed version). The guaranteed pub/sub
#     samples are the cleanest two-class Solace Suggested target: the publisher root
#     gets the publisher sample, the subscriber root gets the consumer sample.
#     Add each new Solace Suggested root below as a one-line PROD_ROOTS entry plus its
#     own copy line, mirroring the single-project roster convention above.
PROD_PUBLISHER_ROOT="${WORK_DIR}/prod-publisher"
PROD_SUBSCRIBER_ROOT="${WORK_DIR}/prod-subscriber"
PROD_PKG_REL="src/main/java/com/solace/samples/jcsmp"

mkdir -p "${PROD_PUBLISHER_ROOT}/${PROD_PKG_REL}"
mkdir -p "${PROD_SUBSCRIBER_ROOT}/${PROD_PKG_REL}"
cp "$POM" "${PROD_PUBLISHER_ROOT}/pom.xml"
cp "$POM" "${PROD_SUBSCRIBER_ROOT}/pom.xml"
cp "$PUBLISHER_SRC" "${PROD_PUBLISHER_ROOT}/${PROD_PKG_REL}/GuaranteedPublisher.java"
cp "$CONSUMER_SRC"  "${PROD_SUBSCRIBER_ROOT}/${PROD_PKG_REL}/GuaranteedSubscriber.java"
# each standalone root needs its OWN copy of the shared connection helper
cp "$CONFIG_SRC" "${PROD_PUBLISHER_ROOT}/${PROD_PKG_REL}/SolaceConnectionConfig.java"
cp "$CONFIG_SRC" "${PROD_SUBSCRIBER_ROOT}/${PROD_PKG_REL}/SolaceConnectionConfig.java"

# Compile EACH root on its own. Either non-zero fails the run under set -e, so
# the exit code of both compiles is the two-project gate.
echo "Compiling the two Solace Suggested project roots against sol-jcsmp ${SOL_JCSMP_VERSION}..."
for root in "$PROD_PUBLISHER_ROOT" "$PROD_SUBSCRIBER_ROOT"; do
  echo "  compiling ${root}..."
  mvn -q -B -f "${root}/pom.xml" \
    -Dsol.jcsmp.version="${SOL_JCSMP_VERSION}" \
    compile
done

echo "PASS: both Solace Suggested project roots compile against sol-jcsmp ${SOL_JCSMP_VERSION}"
