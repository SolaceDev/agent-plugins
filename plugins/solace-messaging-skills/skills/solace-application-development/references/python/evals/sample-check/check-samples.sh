#!/usr/bin/env bash
#
# Sample check for the baked Solace Python samples. Python has no compile step, so the
# gate installs the latest solace-pubsubplus, runs py_compile on every baked sample,
# and runs check_samples.py, which imports each sample, builds its API objects without a
# broker, and exercises the shared connection helper. The script's exit code IS the
# gate: exit 0 only if every check passes.
#
# Runnable locally with one command from any working directory. Resolves the
# solace-pubsubplus version from PyPI at run time, so no version number is committed
# anywhere (SKILL.md Invariant 3). All work happens in a throwaway temp directory, so
# the repository tree is left unmodified.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# references/python/ lives two levels up from sample-check/ (evals/ -> python/).
REFERENCES_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PYTHON="${PYTHON:-python3}"

# Sample roster: each baked sample and the name a generated project gives it
# (implement-mode.md Step 4). A new baked sample is a one-line append here.
SAMPLES=(
  "python-solace-connection-config.py:solace_connection_config.py"
  "python-guaranteed-publisher-sample.py:guaranteed_publisher.py"
  "python-guaranteed-subscriber-sample.py:guaranteed_subscriber.py"
  "python-guaranteed-publisher-blocking-sample.py:guaranteed_publisher_blocking.py"
  "python-guaranteed-subscriber-blocking-sample.py:guaranteed_subscriber_blocking.py"
  "python-direct-publisher-sample.py:direct_publisher.py"
  "python-direct-subscriber-sample.py:direct_subscriber.py"
  "python-direct-subscriber-blocking-sample.py:direct_subscriber_blocking.py"
  "python-direct-requestor-blocking-sample.py:direct_requestor_blocking.py"
  "python-direct-requestor-sample.py:direct_requestor.py"
  "python-direct-replier-sample.py:direct_replier.py"
)

# Existence guard: fail early if any input is missing, including a new roster entry
# whose sample is not yet committed.
for entry in "${SAMPLES[@]}"; do
  src="${REFERENCES_DIR}/${entry%%:*}"
  [[ -f "$src" ]] || { echo "ERROR: required input not found: $src" >&2; exit 1; }
done
for f in "${SCRIPT_DIR}/check_samples.py" "${REFERENCES_DIR}/scripts/verify.sh"; do
  [[ -f "$f" ]] || { echo "ERROR: required input not found: $f" >&2; exit 1; }
done

# (1) Resolve the latest solace-pubsubplus release from the PyPI JSON API, the same
#     source implement-mode.md Step 3 uses. On any failure, exit non-zero rather than
#     guessing a version.
echo "Resolving the latest solace-pubsubplus version from PyPI..."
VERSION="$(curl -s --max-time 20 https://pypi.org/pypi/solace-pubsubplus/json \
  | "$PYTHON" -c 'import json, sys; print(json.load(sys.stdin)["info"]["version"])' 2>/dev/null || true)"
if [[ -z "${VERSION// /}" ]]; then
  echo "ERROR: could not resolve the latest solace-pubsubplus version from PyPI" >&2
  echo "       (network failure or unreachable API). Not guessing a version." >&2
  exit 1
fi
echo "Resolved solace-pubsubplus version: ${VERSION}"

# (2) Build a throwaway working tree and virtual environment.
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

"$PYTHON" -m venv "${WORK_DIR}/.venv"
VENV_PY="${WORK_DIR}/.venv/bin/python"
echo "Installing solace-pubsubplus==${VERSION} with $("$VENV_PY" --version)..."
"$VENV_PY" -m pip install -q --disable-pip-version-check "solace-pubsubplus==${VERSION}"

# (3) Copy the samples under their generated names. Content is verbatim; only the
#     file name changes, so the samples import each other as a generated project does.
for entry in "${SAMPLES[@]}"; do
  cp "${REFERENCES_DIR}/${entry%%:*}" "${WORK_DIR}/${entry##*:}"
done
cp "${SCRIPT_DIR}/check_samples.py" "${WORK_DIR}/check_samples.py"

# (4) py_compile every sample.
echo "Running py_compile on every baked sample..."
( cd "$WORK_DIR" && "$VENV_PY" -m py_compile ./*.py )
echo "PASS: py_compile"

# (5) Import each sample and build its API objects without a broker, then exercise
#     the connection helper.
echo "Building the API objects against tcp://localhost:55555 without a broker..."
( cd "$WORK_DIR" && "$VENV_PY" check_samples.py )

# (6) The bundled verify script must at least parse.
bash -n "${REFERENCES_DIR}/scripts/verify.sh"
echo "PASS: scripts/verify.sh parses"

# (7) The bundled verify script must resolve the fixed script names of both threading
#     shapes (implement-mode.md Step 4) without a broker. Single-project mode stops at the
#     long-running role's missing .venv before it looks at the foreground role, so the
#     fixture uses separate-projects mode with a stub interpreter in the long-running
#     role's directory: with both scripts present under src/ the stage must get past both
#     names to the foreground role's virtual-environment check, and with either script
#     missing it must name that script in that directory.
echo "Checking that scripts/verify.sh resolves the fixed script names..."
verify_stderr() {  # <stage> <sub-dir> <pub-dir>: verify.sh's stderr in separate-projects mode, no broker
  ( bash "${REFERENCES_DIR}/scripts/verify.sh" "$1" --subscriber-dir "$2" --publisher-dir "$3" 2>&1 >/dev/null ) || true
}
make_roles() {  # sets SUB and PUB to fresh role directories; SUB carries a stub interpreter
  SUB="$(mktemp -d "${WORK_DIR}/sub.XXXXXX")"; PUB="$(mktemp -d "${WORK_DIR}/pub.XXXXXX")"
  mkdir -p "${SUB}/src" "${SUB}/.venv/bin" "${PUB}/src"
  : > "${SUB}/.venv/bin/python"; chmod +x "${SUB}/.venv/bin/python"
}
expect_resolved() {  # <stage> <sub-script> <pub-script>: the stage resolves both names
  make_roles; : > "${SUB}/src/$2"; : > "${PUB}/src/$3"
  grep -qF "no virtual environment in: $(cd "${PUB}" && pwd)" <<<"$(verify_stderr "$1" "${SUB}" "${PUB}")" \
    || { echo "ERROR: verify.sh $1 did not resolve: $2 $3" >&2; exit 1; }
}
expect_missing() {  # <stage> <sub-script|-> <pub-script|-> <sub|pub> <default>: the stage names the absent role's default
  make_roles; [ "$2" = - ] || : > "${SUB}/src/$2"; [ "$3" = - ] || : > "${PUB}/src/$3"
  local dir; if [ "$4" = sub ]; then dir="${SUB}"; else dir="${PUB}"; fi
  grep -qF "no src/$5 found in: $(cd "${dir}" && pwd)" <<<"$(verify_stderr "$1" "${SUB}" "${PUB}")" \
    || { echo "ERROR: verify.sh $1 did not report src/$5 as missing in the $4 directory" >&2; exit 1; }
}
expect_resolved consumer  guaranteed_subscriber.py guaranteed_publisher.py
expect_resolved roundtrip guaranteed_subscriber_blocking.py guaranteed_publisher_blocking.py
expect_resolved direct    direct_subscriber.py direct_publisher.py
expect_resolved direct    direct_subscriber_blocking.py direct_publisher.py
expect_resolved direct-request-reply direct_replier.py direct_requestor_blocking.py
expect_resolved direct-request-reply direct_replier.py direct_requestor.py
expect_missing  consumer  - guaranteed_publisher.py sub guaranteed_subscriber.py
expect_missing  roundtrip guaranteed_subscriber.py - pub guaranteed_publisher.py
expect_missing  direct    - direct_publisher.py sub direct_subscriber.py
expect_missing  direct-request-reply direct_replier.py - pub direct_requestor_blocking.py
echo "PASS: scripts/verify.sh resolves the fixed script names of both threading shapes"

echo "PASS: the baked Python samples against solace-pubsubplus ${VERSION}"
