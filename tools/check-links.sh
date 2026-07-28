#!/usr/bin/env bash
#
# check-links.sh — sweep every documentation link under the skills/ tree for
# broken external links (CI-02).
#
# Complements the CI validate job (gh skill publish --dry-run), which checks the
# Agent Skills spec but not links.
# This sweep additionally:
#   * covers the WHOLE skills/ tree — SKILL.md, references/*.md, the .java sample
#     headers, and scripts/*.sh (not just *.md) — while excluding the repo-root
#     README (D-11: the sweep globs the skills/ tree only, never the repo root),
#   * detects Solace "soft 404s" — pages that return HTTP 200/30x but actually land
#     on a not-found page (docs.solace.com redirects bad paths to Not-Found.htm;
#     products.solace.com renders the "Login to Solace Products" gate) (D-10), and
#   * skips the repo's own blob/main self-link, which 404s until the PKG-06 release
#     makes the repo public (live post-release — Pitfall 2).
#
# Doc links now resolve to public https://docs.solace.com/...md URLs and are checked
# like any other https link (including the docs.solace.com soft-404 detection below).
# During the PoC these were local ~/Downloads/site-only-markdown/ snapshot paths that
# the grep -oE 'https?://...' extraction dropped (D-09); that no longer applies.
#
# Exit codes: 0 = no broken links, 1 = broken link(s) found.
# Unverifiable responses (401/403/405/429/5xx, timeouts) are reported as warnings
# and do NOT fail the run (bot-blocked sites cannot be verified from CI).
#
# Usage: tools/check-links.sh [root-dir]   (default root: plugins)

set -uo pipefail

ROOT="${1:-plugins}"
UA='Mozilla/5.0 (compatible; agent-plugins-linkcheck/1.0)'

# Solace soft-404 body markers (small, maintainable list — D-10). Verified by
# curl-ing a deliberately-bad path on each live host:
#   * products.solace.com/<bad> -> 200 with "<title>Login to Solace Products</title>"
#     (the auth gate the not-found path falls through to).
#   * tutorials.solace.dev/<bad> -> a real HTTP 404 (caught by status code), whose
#     static body also renders "Page Not Found" — kept here as a defensive backstop
#     in case the static host ever starts serving the not-found page with a 200.
# docs.solace.com is detected by effective-URL redirect (see SOFT_404_EFFECTIVE_URL
# below), not a body marker: its bad paths 30x to a fixed Not-Found.htm landing page
# and its SPA body is JS-rendered, so a body-text grep is unreliable.
SOFT_404_MARKERS=(
  '<title>Login to Solace Products</title>'
  'Page Not Found'
)

# docs.solace.com redirects any bad path to this fixed not-found landing page. A
# 200/30x whose EFFECTIVE url ends here is a soft-404 (the homepage/not-found
# redirect heuristic D-10 calls for). Good pages keep their own effective URL.
SOFT_404_EFFECTIVE_URL='docs.solace.com/Not-Found.htm'

# Hosts we apply soft-404 detection to. Other hosts (GitHub, Maven Central,
# Sonatype) are trusted to return honest status codes, so only status drives them.
is_soft404_host() {
  case "$1" in
    https://docs.solace.com/*|https://products.solace.com/*|https://tutorials.solace.dev/*) return 0 ;;
  esac
  return 1
}

# Links we can't (or shouldn't) resolve over the network: RFC 6570 templates and
# angle-bracket placeholders (a harmless template-guard), plus
# the repo's own blob/main self-link.
should_skip() {
  case "$1" in
    *'{'*|*'}'*|*'<'*|*'>'*) return 0 ;;
    # The disclaimer + SKILL.md reference the checklist via the repo's own
    # blob/main URL. It 404s until PKG-06 makes the repo public — live post-release
    # (Pitfall 2). Re-enable deliberately once the repo is public.
    https://github.com/SolaceProducts/agent-plugins/blob/main/*) return 0 ;;
  esac
  return 1
}

# Collect unique http(s) links from the whole skills/ tree (*.md, *.java, *.sh).
# README at the repo root is excluded because ROOT is the skills/ tree, never the
# repo root (D-11). Doc links are now public docs.solace.com .md URLs, checked inline.
#
# A directory exclusion keeps the sweep fast and authored-only (CI-03):
#   * docs  the baked docs.solace.com copies under each skill (the umbrella's
#     references/jcsmp/docs and references/docs). Those are verbatim doc snapshots,
#     so their thousands of embedded URLs are an accepted dangling artifact, not
#     links this sweep owns. Curling them would turn the link-check job from seconds
#     into many minutes and flake on every transient failure.
links=()
while IFS= read -r line; do
  links+=("$line")
done < <(
  grep -rhoE 'https?://[^ )"`'"'"'>]+' "$ROOT" \
    --include='*.md' --include='*.java' --include='*.sh' \
    --exclude-dir='docs' \
    | sed -E 's/[.,;:*]+$//' \
    | sort -u
)

broken=()
warned=()

# Guard the expansion: under bash 3.2 (the macOS system bash) expanding an empty
# array with set -u raises "links[@]: unbound variable". The warned/broken/
# local_broken arrays below are already guarded the same way.
if [[ ${#links[@]} -gt 0 ]]; then
for url in "${links[@]}"; do
  should_skip "$url" && continue

  # Capture body + final status + effective (post-redirect) URL in one request.
  resp="$(curl -sSL --max-time 20 -A "$UA" \
    -w '\n__HTTP_STATUS__%{http_code}__EFFURL__%{url_effective}' "$url" 2>/dev/null)"
  meta="${resp##*__HTTP_STATUS__}"
  body="${resp%__HTTP_STATUS__*}"
  code="${meta%%__EFFURL__*}"
  effurl="${meta##*__EFFURL__}"

  case "$code" in
    404|410)
      broken+=("[$code] $url")
      ;;
    200|301|302|303|307|308)
      # Soft-404 detection for Solace doc hosts only.
      if is_soft404_host "$url"; then
        # (1) Redirect to a known not-found landing page (docs.solace.com).
        if [[ "$effurl" == *"$SOFT_404_EFFECTIVE_URL"* ]]; then
          broken+=("[soft-404 redirect->Not-Found] $url")
          continue
        fi
        # (2) A not-found / auth-gate body marker on an otherwise-200 page.
        for marker in "${SOFT_404_MARKERS[@]}"; do
          if grep -qiF "$marker" <<<"$body"; then
            broken+=("[soft-404] $url")
            continue 2
          fi
        done
      fi
      ;;
    *)
      # 401/403/405/429/5xx and timeouts (000) — warning, does NOT fail the run.
      warned+=("[$code] $url")
      ;;
  esac
done
fi

if [[ ${#warned[@]} -gt 0 ]]; then
  echo "::group::Unverifiable links (not failing the run)"
  printf '  ! %s\n' "${warned[@]}"
  echo "::endgroup::"
fi

if [[ ${#broken[@]} -gt 0 ]]; then
  echo "Broken links found:"
  printf '  x %s\n' "${broken[@]}"
  exit 1
fi

echo "All ${#links[@]} external links resolved (no 404s or soft-404s)."

# ---------------------------------------------------------------------------
# Local .md resolution pass (CI-03 second half).
#
# Every relative .md reference in an AUTHORED skill file (SKILL.md,
# references/*.md, INDEX.md) must resolve to a file on disk. This catches the
# Phase 7 WR-01 regression: a backtick-wrapped peer reference (for example
# `references/jcsmp.md`) that was missed because an earlier resolver only
# matched Markdown ](...) links. The reference matcher here is form-agnostic:
# it captures a .md path token regardless of whether it is wrapped in a
# Markdown link ](path.md), a backtick code span `path.md`, or sits bare in
# prose, so no reference form slips through.
#
# Scope: authored files only. The baked docs/ copies are excluded; their verbatim
# cross-links are an accepted dangling artifact.
#
# Skips (documented, parallel to should_skip for URLs):
#   * the bare ".md" token (a prose mention of the extension, no file stem),
#   * anything that is part of an http(s) URL (a "://" anywhere, or a leading
#     "//" left over from a stripped scheme), checked by the sweep above,
#   * solace-design.md (which the skill OFFERS to create, written only on the
#     developer's OK) and solace-verification-checklist.md (which the skill
#     always writes), both landing in the developer's own project at
#     generation time. They are intentionally not bundled files, so they never
#     resolve on disk and are not this pass's concern.
#
# Resolution checks the literal referenced path first, so a wrong-directory
# reference cannot be masked by an unrelated same-named file elsewhere:
#   1. relative to the referencing file's own directory, or
#   2. relative to the skill root (the ROOT argument).
# Only a BARE filename (no directory component) falls back to a basename-anywhere
# search, which tolerates a correct ref written at a different path depth without
# letting a typo'd directory slip through (WR-02). A ref that names a directory
# must resolve at that exact path.
local_broken=()
while IFS=: read -r srcfile lineno ref; do
  [[ -z "$ref" ]] && continue
  # Skip the bare extension mention (no file stem).
  [[ "$ref" == ".md" ]] && continue
  # Skip URL fragments (full scheme, or a scheme-stripped leading //).
  case "$ref" in
    *'://'*|//*) continue ;;
  esac
  # Skip the agent-generated project artifacts (never bundled files by design).
  [[ "$(basename "$ref")" == "solace-design.md" ]] && continue
  [[ "$(basename "$ref")" == "solace-verification-checklist.md" ]] && continue

  srcdir="$(dirname "$srcfile")"
  base="$(basename "$ref")"
  # Literal-path checks: relative to the referencing file, then to the skill root.
  if [[ -f "$srcdir/$ref" ]] || [[ -f "$ROOT/$ref" ]]; then
    continue
  fi
  # Basename-anywhere fallback ONLY for a bare filename (no directory component).
  # A directory-bearing ref must match a literal path above; accepting any
  # same-named file elsewhere would mask a wrong-directory reference (WR-02).
  if [[ "$ref" != */* ]] \
     && find "$ROOT" -name "$base" -not -path '*/docs/*' \
          -print -quit | grep -q .; then
    continue
  fi
  local_broken+=("BROKEN LOCAL: $srcfile:$lineno -> $ref")
done < <(
  grep -rnoE '[A-Za-z0-9_./-]+\.md' "$ROOT" \
    --include='*.md' \
    --exclude-dir='docs'
)

if [[ ${#local_broken[@]} -gt 0 ]]; then
  echo "Broken local .md references found:"
  printf '  x %s\n' "${local_broken[@]}"
  exit 1
fi

echo "All authored local .md references resolved (no BROKEN LOCAL)."
