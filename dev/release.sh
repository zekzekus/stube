#!/usr/bin/env bash
# Release driver for stube.  Invoked from `make release VERSION=x.y.z`.
#
# Sequence (every step exits non-zero on first failure):
#   1. sanity-check args, env, working copy, CHANGELOG draft
#   2. run the full test suite
#   3. bump the version in build.clj, README.md, docs/tutorial.md;
#      rename `## Unreleased` in CHANGELOG.md to `## VERSION` and
#      restore a fresh `## Unreleased` placeholder above it
#   4. describe the commit, advance master, start a fresh working copy
#   5. clean + deploy the jar to Clojars
#   6. push master via `jj git push`
#   7. create an annotated git tag (not a jj bookmark) and push it

set -euo pipefail

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

if [[ -z "${CLOJARS_USERNAME:-}" || -z "${CLOJARS_PASSWORD:-}" ]]; then
  echo "CLOJARS_USERNAME and CLOJARS_PASSWORD must be set in the environment." >&2
  echo "Generate a deploy token at https://clojars.org/tokens" >&2
  exit 1
fi

echo "→ ensuring working copy is clean"
# Read the full output first so `grep -q` can't SIGPIPE jj and trip
# `set -o pipefail`.
jj_status=$(jj st)
if [[ "$jj_status" != *"The working copy has no changes."* ]]; then
  echo "working copy has uncommitted changes; commit or shelve first" >&2
  echo "$jj_status" >&2
  exit 1
fi

echo "→ checking CHANGELOG.md has a drafted ## Unreleased section"
# Pull the body between `## Unreleased` and the next `## ` heading,
# strip blank lines and the (No changes yet.) placeholder.  If
# nothing remains, the release is empty — refuse rather than mint a
# version section with no notes.
unreleased_body=$(awk '
  /^## Unreleased[[:space:]]*$/ { in_section = 1; next }
  /^## / && in_section          { in_section = 0 }
  in_section                    { print }
' CHANGELOG.md \
  | grep -vE '^[[:space:]]*$' \
  | grep -vE '^[[:space:]]*\(No changes yet\.\)[[:space:]]*$' \
  || true)

if [[ -z "$unreleased_body" ]]; then
  echo "CHANGELOG.md ## Unreleased is empty; add entries before releasing." >&2
  exit 1
fi

echo "→ running tests"
clojure -X:test

# Browser smoke suite gated to release time (and ad-hoc `make e2e`) so
# `make test` stays Clojure-only and fast.  The Playwright Chromium
# launch + per-test contexts add ~30s on a warm cache.
echo "→ running e2e (browser smoke) suite"
clojure -M:e2e

echo "→ bumping version to $VERSION"
# Capture-group form so the existing whitespace between `version` and
# the string literal is preserved verbatim (build.clj uses several
# spaces for alignment).
sed -i -E 's/(\(def version[[:space:]]+")[^"]+"/\1'"$VERSION"'"/' build.clj
sed -i -E 's/(\{:mvn\/version[[:space:]]+")[^"]+"/\1'"$VERSION"'"/' \
  README.md docs/tutorial.md

echo "→ promoting CHANGELOG.md ## Unreleased to ## $VERSION"
# Rewrite the first `## Unreleased` heading to `## VERSION` and
# re-insert a fresh empty `## Unreleased` above it.  Awk because sed
# replacement-with-embedded-newlines is awkward across implementations.
awk -v ver="$VERSION" '
  /^## Unreleased[[:space:]]*$/ && !done {
    print "## Unreleased"
    print ""
    print "(No changes yet.)"
    print ""
    print "## " ver
    done = 1
    next
  }
  { print }
' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md

echo "→ committing version bump"
jj describe -m "Release $VERSION"

# Capture the commit id *before* `jj new` so we can tag it later.
COMMIT_ID=$(jj log -r @ -T 'commit_id' --no-graph --no-pager | head -c 40)
echo "  commit: $COMMIT_ID"

jj bookmark move master --to @
jj new

echo "→ building + deploying to Clojars"
clojure -T:build clean
clojure -T:build deploy

echo "→ pushing master"
jj git push --bookmark master

echo "→ tagging v$VERSION (annotated git tag) and pushing"
git tag -a "v$VERSION" -m "Release $VERSION" "$COMMIT_ID"
git push origin "v$VERSION"

# Triggers cljdoc to fetch the freshly-pushed jar + tag and render
# the article tree from doc/cljdoc.edn.  Soft-fail: a flaky cljdoc.org
# shouldn't undo a successful Clojars push — the build can be re-run
# manually at https://cljdoc.org/d/dev.zeko/stube/$VERSION
echo "→ requesting cljdoc build"
if ! curl -fsS -X POST \
      -d "project=dev.zeko/stube" \
      -d "version=$VERSION" \
      https://cljdoc.org/api/request-build2 > /dev/null; then
  echo "  cljdoc build request failed; re-trigger at https://cljdoc.org/d/dev.zeko/stube/$VERSION" >&2
fi

echo "✔ released $VERSION"
