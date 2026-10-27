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
#     shapes (implement-mode.md Step 4) without a broker: with only the named scripts
#     present and no .venv, each stage must get past script resolution to its
#     virtual-environment check, and with no script present it must name the default.
echo "Checking that scripts/verify.sh resolves the fixed script names..."
verify_stderr() {  # <dir> <stage>: verify.sh's stderr with connection arguments and no .venv
  ( cd "$1" && bash "${REFERENCES_DIR}/scripts/verify.sh" "$2" tcp://localhost:55555 vpn user 2>&1 >/dev/null ) || true
}
expect_resolved() {  # <stage> <script>...: the stage resolves these scripts
  local stage="$1"; shift
  local dir; dir="$(mktemp -d "${WORK_DIR}/resolve.XXXXXX")"
  local script; for script in "$@"; do : > "${dir}/${script}"; done
  grep -q "no virtual environment" <<<"$(verify_stderr "$dir" "$stage")" \
    || { echo "ERROR: verify.sh ${stage} did not resolve: $*" >&2; exit 1; }
}
expect_missing() {  # <stage> <script>: with no script present, the stage names this default
  local dir; dir="$(mktemp -d "${WORK_DIR}/resolve.XXXXXX")"
  grep -q "no $2 found" <<<"$(verify_stderr "$dir" "$1")" \
    || { echo "ERROR: verify.sh $1 did not report $2 as missing" >&2; exit 1; }
}
expect_resolved consumer  guaranteed_subscriber.py guaranteed_publisher.py
expect_resolved roundtrip guaranteed_subscriber_blocking.py guaranteed_publisher_blocking.py
expect_resolved direct    direct_subscriber.py direct_publisher.py
expect_resolved direct    direct_subscriber_blocking.py direct_publisher.py
expect_resolved direct-request-reply direct_replier.py direct_requestor_blocking.py
expect_resolved direct-request-reply direct_replier.py direct_requestor.py
expect_missing  consumer  guaranteed_subscriber.py
expect_missing  direct    direct_subscriber.py
echo "PASS: scripts/verify.sh resolves the fixed script names of both threading shapes"

echo "PASS: the baked Python samples against solace-pubsubplus ${VERSION}"
