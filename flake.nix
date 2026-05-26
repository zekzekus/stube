{
  description = "stube — a Clojure component framework over Datastar";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShells.default = pkgs.mkShell {
          name = "stube";

          packages = with pkgs; [
            # JVM + build tools
            jdk21
            clojure

            # Linting & utility
            clj-kondo
            babashka

            # VCS we use in this project
            jujutsu

            # Playwright-driven e2e smoke suite.  `playwright-driver.browsers`
            # is the Nix-packaged Chromium/Firefox/WebKit bundle that the
            # Playwright Java client can use; without it, the bundled binaries
            # fail to start on NixOS because they expect FHS-style /lib paths.
            playwright-driver.browsers
          ];

          shellHook = ''
            # Point Playwright Java at the Nix-managed browser bundle and
            # skip its own download attempt.  Without these, `clojure -X:e2e`
            # tries to launch a Chromium built for stock Linux and fails.
            export PLAYWRIGHT_BROWSERS_PATH=${pkgs.playwright-driver.browsers}
            export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
            export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1

            echo "──────────────────────────────────────────────"
            echo " stube dev shell"
            echo "   $(java -version 2>&1 | head -n1)"
            echo "   Clojure CLI: $(clojure -Sdescribe 2>/dev/null | grep version | head -n1)"
            echo "   jj:          $(jj --version)"
            echo "   Playwright browsers: $PLAYWRIGHT_BROWSERS_PATH"
            echo "──────────────────────────────────────────────"
          '';
        };

        formatter = pkgs.nixpkgs-fmt;
      });
}
