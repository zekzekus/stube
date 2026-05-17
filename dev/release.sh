#!/usr/bin/env bash
# Release driver for stube.  Invoked from `make release VERSION=x.y.z`.
#
# Sequence (every step exits non-zero on first failure):
#   1. sanity-check args, env, working copy
#   2. run the full test suite
#   3. bump the version in build.clj, README.md, docs/tutorial.md
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

echo "→ running tests"
clojure -X:test

echo "→ bumping version to $VERSION"
# Capture-group form so the existing whitespace between `version` and
# the string literal is preserved verbatim (build.clj uses several
# spaces for alignment).
sed -i -E 's/(\(def version[[:space:]]+")[^"]+"/\1'"$VERSION"'"/' build.clj
sed -i -E 's/(\{:mvn\/version[[:space:]]+")[^"]+"/\1'"$VERSION"'"/' \
  README.md docs/tutorial.md

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

echo "✔ released $VERSION"
