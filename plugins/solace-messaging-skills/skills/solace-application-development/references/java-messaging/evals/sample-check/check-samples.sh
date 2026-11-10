#!/usr/bin/env bash
#
# Sample check for the baked Solace Messaging API for Java samples: compile every
# baked java-messaging reference sample VERBATIM against the latest
# com.solace:solace-messaging-client resolved live from Maven Central, then run a
# short SolaceConnectionConfig smoke test so the shared connection helper's
# config.json parser and fail-fast paths are exercised at run time, not just
# compiled. The script's exit code IS the gate: exit 0 only if every sample
# compiles and every smoke case passes. No broker and no API key.
#
# Runnable locally with one command from any working directory. Resolves the
# solace-messaging-client version dynamically at run time, so no version number of
# the API is committed anywhere (Invariant 3). log4j2 is pinned in the pom
# (fixture-only tooling).
#
# Mechanism: the fixture pom declares `<solace.messaging.client.version>` as a
# NON-NUMERIC placeholder property; this script overrides it via
# `-Dsolace.messaging.client.version=...` on the mvn command line (no sed
# substitution of the pom). All work happens in a throwaway temp dir, so the
# repository tree is left unmodified.

set -euo pipefail

# Resolve this script's own directory so sample/pom paths are independent of CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POM="${SCRIPT_DIR}/pom.xml"
SMOKE_SRC="${SCRIPT_DIR}/ConfigSmokeTest.java"
# references/java-messaging/ lives two levels up from sample-check/ (evals/ -> java-messaging/).
REFERENCES_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Sample roster: each baked sample and the class name inside it (`public class <Name>`).
# A new baked sample is a one-line append here. The shared connection helper is not a
# role sample, but every sample calls SolaceConnectionConfig.load(...), so it compiles
# with them.
SAMPLES=(
  "java-messaging-solace-connection-config.java:SolaceConnectionConfig"
  "java-messaging-guaranteed-publisher-sample.java:GuaranteedPublisher"
  "java-messaging-guaranteed-publisher-blocking-sample.java:GuaranteedPublisherBlocking"
  "java-messaging-guaranteed-subscriber-sample.java:GuaranteedSubscriber"
  "java-messaging-guaranteed-subscriber-blocking-sample.java:GuaranteedSubscriberBlocking"
  "java-messaging-direct-publisher-sample.java:DirectPublisher"
  "java-messaging-direct-subscriber-sample.java:DirectSubscriber"
  "java-messaging-direct-subscriber-blocking-sample.java:DirectSubscriberBlocking"
)

# Existence guard: fail early (and clearly) if any required input is missing,
# including a new roster entry whose sample is not yet committed.
for f in "$POM" "$SMOKE_SRC"; do
  [[ -f "$f" ]] || { echo "ERROR: required input not found: $f" >&2; exit 1; }
done
for entry in "${SAMPLES[@]}"; do
  src="${REFERENCES_DIR}/${entry%%:*}"
  [[ -f "$src" ]] || { echo "ERROR: required input not found: $src" >&2; exit 1; }
done

# (1) Resolve the live solace-messaging-client version from the AUTHORITATIVE Maven
#     Central metadata <release> element (the legacy search index lags and is not
#     used here). On any network failure, exit non-zero rather than guessing a version.
echo "Resolving latest solace-messaging-client version from Maven Central..."
VERSION="$(curl -s --max-time 20 \
  https://repo1.maven.org/maven2/com/solace/solace-messaging-client/maven-metadata.xml \
  | grep -oE '<release>[^<]+</release>' | sed -E 's/<\/?release>//g' || true)"

if [[ -z "${VERSION// /}" ]]; then
  echo "ERROR: could not resolve the latest solace-messaging-client version from Maven Central" >&2
  echo "       (network failure or unreachable metadata). Not guessing a version." >&2
  exit 1
fi
echo "Resolved solace-messaging-client version: ${VERSION}"

# (2) Build a throwaway working tree so the repo stays clean and re-runs are
#     idempotent. Clean it up on any exit.
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

SRC_PKG_DIR="${WORK_DIR}/src/main/java/com/solace/samples/java"
mkdir -p "$SRC_PKG_DIR"
cp "$POM" "${WORK_DIR}/pom.xml"

# (3) Copy the baked samples under their REAL class names (rename on copy). Content
#     is verbatim; only the filename changes so javac/Maven accept them. All samples
#     share the com.solace.samples.java package, so the one fixture pom and the one
#     mvn compile below build the whole roster.
for entry in "${SAMPLES[@]}"; do
  cp "${REFERENCES_DIR}/${entry%%:*}" "${SRC_PKG_DIR}/${entry##*:}.java"
done

# (4) Compile in the working tree. The exit code of this command is the gate.
echo "Compiling baked samples against solace-messaging-client ${VERSION}..."
mvn -q -B -f "${WORK_DIR}/pom.xml" \
  -Dsolace.messaging.client.version="${VERSION}" \
  compile

echo "PASS: baked samples compile against solace-messaging-client ${VERSION}"

# (5) Runtime smoke test of the shared connection helper. Compiling the helper never
#     executes its config.json parser or CLI-args fallback, so compile the tiny
#     ConfigSmokeTest driver (committed next to this script) against the classes just
#     built in step (4) and RUN it across the parser's real behaviors: config.json
#     round-trip (plus a MessagingService build from the result), JSON unescaping, the
#     generic extra-key pass-through, the CLI-args fallback, and the three fail-fast
#     paths (missing key, blank config value, blank CLI arg). Each case runs in its own
#     working dir (the parser reads a CWD-relative config.json) and any case exiting
#     non-zero fails this gate under `set -e`.
echo "Running the SolaceConnectionConfig smoke test..."
# Resolve the full runtime classpath (solace-messaging-client + pinned log4j) from the
# fixture pom, then compile the driver into the already-built classes dir and run cases.
mvn -q -B -f "${WORK_DIR}/pom.xml" \
  -Dsolace.messaging.client.version="${VERSION}" \
  dependency:build-classpath -Dmdep.outputFile="${WORK_DIR}/smoke-cp.txt"
SMOKE_CP="${WORK_DIR}/target/classes:$(cat "${WORK_DIR}/smoke-cp.txt")"
javac -cp "$SMOKE_CP" -d "${WORK_DIR}/target/classes" "$SMOKE_SRC"

SMOKE_FILE_DIR="${WORK_DIR}/smoke-file"; mkdir -p "$SMOKE_FILE_DIR"
SMOKE_EMPTY_DIR="${WORK_DIR}/smoke-empty"; mkdir -p "$SMOKE_EMPTY_DIR"
run_smoke() {  # <case> <cwd>
  ( cd "$2" && java -cp "$SMOKE_CP" ConfigSmokeTest "$1" )
}

# 1) a valid config.json round-trips all four connection values. build() resolves the
#    host without connecting, so this case uses a resolvable host.
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{
  "solace.messaging.transport.host": "tcps://localhost:55443",
  "solace.messaging.service.vpn-name": "smoke-vpn",
  "solace.messaging.authentication.basic.username": "smoke-user",
  "solace.messaging.authentication.basic.password": "smoke-pass"
}
JSON
run_smoke file-roundtrip "$SMOKE_FILE_DIR"

# 2) JSON string escapes are unescaped (us\"er\\name -> us"er\name)
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{
  "solace.messaging.transport.host": "tcps://smoke.example:55443",
  "solace.messaging.service.vpn-name": "smoke-vpn",
  "solace.messaging.authentication.basic.username": "us\"er\\name",
  "solace.messaging.authentication.basic.password": "smoke-pass"
}
JSON
run_smoke file-escaped "$SMOKE_FILE_DIR"

# 3) a missing required key fails fast
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{
  "solace.messaging.service.vpn-name": "smoke-vpn",
  "solace.messaging.authentication.basic.username": "smoke-user"
}
JSON
run_smoke missing-key "$SMOKE_FILE_DIR"

# 4) a present-but-blank required value fails fast
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{
  "solace.messaging.transport.host": "",
  "solace.messaging.service.vpn-name": "smoke-vpn",
  "solace.messaging.authentication.basic.username": "smoke-user"
}
JSON
run_smoke blank-value "$SMOKE_FILE_DIR"

# 5) with no config.json present, the CLI args are the fallback source
run_smoke args-fallback "$SMOKE_EMPTY_DIR"

# 6) a blank CLI arg fails fast (never connects with a blank host)
run_smoke args-blank "$SMOKE_EMPTY_DIR"

# 7) an extra flat string key (the client name) passes through to the Properties
cat > "${SMOKE_FILE_DIR}/config.json" <<'JSON'
{
  "solace.messaging.transport.host": "tcps://smoke.example:55443",
  "solace.messaging.service.vpn-name": "smoke-vpn",
  "solace.messaging.authentication.basic.username": "smoke-user",
  "solace.messaging.authentication.basic.password": "smoke-pass",
  "solace.messaging.client.name": "smoke-client"
}
JSON
run_smoke extra-key-passthrough "$SMOKE_FILE_DIR"

echo "PASS: SolaceConnectionConfig smoke test (parser, fallback, and fail-fast paths)"
