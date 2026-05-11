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
          ];

          shellHook = ''
            echo "──────────────────────────────────────────────"
            echo " stube dev shell"
            echo "   $(java -version 2>&1 | head -n1)"
            echo "   Clojure CLI: $(clojure -Sdescribe 2>/dev/null | grep version | head -n1)"
            echo "   jj:          $(jj --version)"
            echo "──────────────────────────────────────────────"
          '';
        };

        formatter = pkgs.nixpkgs-fmt;
      });
}
