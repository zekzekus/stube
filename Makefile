# stube — task targets for local development and the release workflow.
#
# All targets assume the dev shell from `flake.nix` is active
# (`nix develop`).  `make release VERSION=x.y.z` is the only one that
# touches Clojars / git; everything else is local.

.PHONY: help lint test clean jar install release

help:
	@echo "Targets:"
	@echo "  make lint                       run clj-kondo across src/ and test/"
	@echo "  make test                       lint, then run the full Clojure test suite"
	@echo "  make jar                        build target/stube-<v>.jar"
	@echo "  make install                    install jar into ~/.m2"
	@echo "  make clean                      remove target/"
	@echo "  make release VERSION=x.y.z      bump, test, deploy to Clojars, tag, push"

# `clj-kondo` returns exit code 2 on warnings and 3 on errors, so any
# accumulated lint regressions fail this target (and, transitively, `test`).
# Suppress an unactionable warning by adding `#_:clj-kondo/ignore` with a
# one-line comment explaining why kondo is wrong; never silence it globally.
lint:
	clj-kondo --lint src test

test: lint
	clojure -X:test

clean:
	clojure -T:build clean

jar:
	clojure -T:build jar

install:
	clojure -T:build install

# Delegates to dev/release.sh so the multi-step shell logic lives in one
# script instead of being fragmented across Makefile recipe lines.
release:
	@if [ -z "$(VERSION)" ]; then \
	  echo "Usage: make release VERSION=x.y.z" >&2; exit 1; \
	fi
	@dev/release.sh "$(VERSION)"
