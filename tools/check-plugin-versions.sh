#!/usr/bin/env bash
#
# check-plugin-versions.sh — enforce the plugin release versioning rules and
# keep marketplace.json in sync with the plugins/ tree.
#
# Claude Code caches an installed plugin by the "version" string in its
# plugin.json: pushing new commits alone is NOT enough for users to receive
# changes, the version must be bumped. Version bumps happen only in
# release PRs (PRs targeting main), so CI runs the version check on those PRs
# and the sync check on every run.
#
# Two checks:
#   * Version bump — for every plugin whose content changed relative to the
#     base (anything under plugins/<name>/ except the top-level evals/ corpus,
#     which does not ship functionality), plugin.json "version" must be strict
#     semver MAJOR.MINOR.PATCH (no "v" prefix, no prerelease) and strictly
#     greater than the version at the base. A plugin that is new relative to
#     the base only needs a valid semver version.
#   * Marketplace sync — every .claude-plugin/marketplace.json entry must
#     resolve to an existing plugin directory whose plugin.json "name" matches
#     the entry, and every plugins/*/ directory must be listed as an entry.
#
# Usage: tools/check-plugin-versions.sh [base-ref]   (default base: origin/main)
#        tools/check-plugin-versions.sh --sync-only  (marketplace sync only)
#
# The version check diffs merge-base(base-ref, HEAD)..HEAD, so only this
# branch's own committed changes count; commit local changes before running.
# In CI the base is the pull request base SHA and checkout uses fetch-depth: 0
# so the merge base and the base-side plugin.json are reachable.
#
# Exit codes: 0 = all checks pass, 1 = violation(s) or unresolvable base.
# Requires jq (preinstalled on ubuntu-latest runners).

set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

MARKETPLACE=".claude-plugin/marketplace.json"

command -v jq >/dev/null 2>&1 || { echo "jq is required but not found" >&2; exit 1; }

SYNC_ONLY=0
BASE_REF="origin/main"
if [[ "${1:-}" == "--sync-only" ]]; then
  SYNC_ONLY=1
elif [[ -n "${1:-}" ]]; then
  BASE_REF="$1"
fi

errors=()

# fail <file> <message> — collect the failure and annotate <file> so the
# message lands on the manifest in the PR Files view.
fail() {
  echo "::error file=$1::$2"
  errors+=("$2")
}

# Strict MAJOR.MINOR.PATCH: no "v" prefix, no prerelease/build, no leading zeros.
is_semver() {
  local re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
  [[ "$1" =~ $re ]]
}

# semver_gt <a> <b> — true only when a > b (equality fails; both pre-validated).
semver_gt() {
  local a1 a2 a3 b1 b2 b3
  IFS=. read -r a1 a2 a3 <<<"$1"
  IFS=. read -r b1 b2 b3 <<<"$2"
  if [[ "$a1" -ne "$b1" ]]; then [[ "$a1" -gt "$b1" ]]; return; fi
  if [[ "$a2" -ne "$b2" ]]; then [[ "$a2" -gt "$b2" ]]; return; fi
  [[ "$a3" -gt "$b3" ]]
}

# --- Part A: version bumps for changed plugin content -----------------------

if [[ "$SYNC_ONLY" -eq 0 ]]; then
  if ! git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null; then
    echo "::error::cannot resolve base ref '${BASE_REF}'"
    echo "Cannot resolve base ref '${BASE_REF}'." >&2
    echo "Locally: git fetch origin main (or pass a base ref). In CI: checkout needs fetch-depth: 0." >&2
    exit 1
  fi
  merge_base=$(git merge-base "$BASE_REF" HEAD)

  if [[ "$merge_base" == "$(git rev-parse HEAD)" ]]; then
    echo "No commits beyond ${BASE_REF}; nothing to version-check."
  else
    changed=$(git diff --name-only "$merge_base" HEAD -- plugins/)
    plugin_names=$(printf '%s\n' "$changed" | sed -n 's|^plugins/\([^/]*\)/.*|\1|p' | sort -u)

    for name in $plugin_names; do
      manifest="plugins/${name}/.claude-plugin/plugin.json"

      # Deleted plugin: nothing to version; Part B flags the stale marketplace entry.
      if [[ ! -d "plugins/${name}" ]]; then
        echo "plugins/${name}: directory removed, skipping version check."
        continue
      fi

      # Content = changed files under the plugin minus its top-level evals/
      # corpus (the corpus does not ship functionality to users).
      content=""
      while IFS= read -r f; do
        case "$f" in
          "plugins/${name}/evals/"*) ;;
          "plugins/${name}/"*) content+="${f}"$'\n' ;;
        esac
      done <<<"$changed"

      if [[ -z "$content" ]]; then
        echo "plugins/${name}: only evals/ changed, no version bump required."
        continue
      fi
      content_count=$(printf '%s' "$content" | grep -c .)

      if [[ ! -f "$manifest" ]]; then
        fail "$manifest" "plugins/${name}: ${manifest} is missing"
        continue
      fi
      new_ver=$(jq -r '.version // empty' "$manifest" 2>/dev/null)
      if [[ -z "$new_ver" ]]; then
        fail "$manifest" "plugins/${name}: plugin.json has no \"version\" field; set a semver version (new plugins start at 0.1.0)"
        continue
      fi
      if ! is_semver "$new_ver"; then
        fail "$manifest" "plugins/${name}: version '${new_ver}' is not strict semver MAJOR.MINOR.PATCH (no 'v' prefix, no prerelease)"
        continue
      fi

      old_manifest=$(git show "${merge_base}:${manifest}" 2>/dev/null) || old_manifest=""
      if [[ -z "$old_manifest" ]]; then
        echo "plugins/${name}: new plugin at ${new_ver}, OK."
        continue
      fi
      old_ver=$(printf '%s' "$old_manifest" | jq -r '.version // empty' 2>/dev/null)
      if [[ -z "$old_ver" ]] || ! is_semver "$old_ver"; then
        echo "::warning file=${manifest}::plugins/${name}: base version '${old_ver}' is not strict semver; accepting ${new_ver} without comparison"
        continue
      fi

      if semver_gt "$new_ver" "$old_ver"; then
        echo "plugins/${name}: ${old_ver} -> ${new_ver} covers ${content_count} changed content file(s), OK."
      else
        fail "$manifest" "plugins/${name}: ${content_count} content file(s) changed but version did not increase (${old_ver} -> ${new_ver}); bump \"version\" in ${manifest} so users receive the changes"
        echo "  changed content files (first 10):"
        printf '%s' "$content" | head -n 10 | sed 's/^/    x /'
      fi
    done
  fi
fi

# --- Part B: marketplace.json <-> plugins/ sync ------------------------------

if [[ ! -f "$MARKETPLACE" ]]; then
  fail "$MARKETPLACE" "marketplace manifest ${MARKETPLACE} is missing"
else
  # Newline-separated source list for the reverse (directory -> entry) check.
  sources=""
  while IFS=$'\t' read -r ename etype esrc; do
    if [[ "$etype" != "string" ]]; then
      fail "$MARKETPLACE" "marketplace entry '${ename}': non-string source is not supported by this check; update tools/check-plugin-versions.sh if the format changed"
      continue
    fi
    src="${esrc#./}"; src="${src%/}"
    sources+="${src}"$'\n'
    if [[ ! -d "$src" ]]; then
      fail "$MARKETPLACE" "marketplace entry '${ename}': source '${esrc}' does not exist"
      continue
    fi
    pmanifest="${src}/.claude-plugin/plugin.json"
    if [[ ! -f "$pmanifest" ]]; then
      fail "$MARKETPLACE" "marketplace entry '${ename}': ${pmanifest} is missing"
      continue
    fi
    pname=$(jq -r '.name // empty' "$pmanifest" 2>/dev/null)
    if [[ "$pname" != "$ename" ]]; then
      fail "$pmanifest" "marketplace entry name '${ename}' does not match plugin.json name '${pname}'"
    fi
  done < <(jq -r '.plugins[] | [.name, (.source | type), (.source | tostring)] | @tsv' "$MARKETPLACE")

  # Duplicate entry names or sources hide one plugin behind another.
  dup_names=$(jq -r '[.plugins[].name] | group_by(.)[] | select(length > 1)[0]' "$MARKETPLACE")
  for d in $dup_names; do
    fail "$MARKETPLACE" "marketplace has duplicate entries named '${d}'"
  done
  dup_sources=$(jq -r '[.plugins[].source | tostring] | group_by(.)[] | select(length > 1)[0]' "$MARKETPLACE")
  for d in $dup_sources; do
    fail "$MARKETPLACE" "marketplace has duplicate entries with source '${d}'"
  done

  # Reverse direction: every plugin directory must be listed.
  for dir in plugins/*/; do
    [[ -d "$dir" ]] || continue    # unexpanded glob when plugins/ is empty
    dir="${dir%/}"
    if ! printf '%s' "$sources" | grep -qxF "$dir"; then
      fail "$MARKETPLACE" "plugin directory '${dir}' has no entry in ${MARKETPLACE}"
    fi
  done
fi

# --- Summary -----------------------------------------------------------------

if [[ ${#errors[@]} -gt 0 ]]; then
  echo ""
  echo "Plugin version/marketplace violations found:"
  printf '  x %s\n' "${errors[@]}"
  exit 1
fi

if [[ "$SYNC_ONLY" -eq 1 ]]; then
  echo "marketplace.json is in sync with plugins/."
else
  echo "All changed plugins version-checked against ${BASE_REF}; marketplace.json is in sync with plugins/."
fi
