(ns dev.zeko.stube.core-test
  "Documentation invariants for the public surface.

  The point of `dev.zeko.stube.core` is to be a stable, ergonomic
  re-export shell; a re-export that silently drops `:doc` or
  `:arglists` regresses `(doc s/foo)` and CIDER's eldoc popup.  This
  test catches that the moment it happens."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.core]))

(deftest every-public-name-has-doc-and-arglists
  (doseq [[sym v] (ns-publics 'dev.zeko.stube.core)
          :let [m (meta v)]
          :when (not (:no-doc m))]
    (testing (str 'dev.zeko.stube.core "/" sym)
      (is (string? (:doc m))
          "every public name carries a docstring")
      (when (fn? @v)
        ;; Macros carry `:arglists` automatically; plain fns/aliases
        ;; must opt in via `defalias` or a normal `defn` signature.
        ;; Non-fn values (e.g. the `cancel` sentinel) are exempt.
        (is (or (:macro m) (some? (:arglists m)))
            "every public function carries :arglists")))))
