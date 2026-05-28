(ns build
  "Build the stube library jar + pom for Clojars.

  Usage:

      clojure -T:build clean
      clojure -T:build jar
      clojure -T:build install      ; install into local ~/.m2
      clojure -T:build deploy       ; push to Clojars (needs CLOJARS_* env)

  The deploy target shells out to slipset/deps-deploy via the `:deploy`
  alias and reads the credentials from `CLOJARS_USERNAME` and
  `CLOJARS_PASSWORD` (use a deploy token, not your Clojars password)."
  (:require [clojure.tools.build.api :as b]))

(def lib       'dev.zeko/stube)
(def version   "0.3.0")
(def class-dir "target/classes")
(def jar-file  (format "target/%s-%s.jar" (name lib) version))
(def basis     (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir     class-dir
                :lib           lib
                :version       version
                :basis         @basis
                :src-dirs      ["src"]
                :resource-dirs ["resources"]
                :scm           {:url                 "https://github.com/zekzekus/stube"
                                :connection          "scm:git:git://github.com/zekzekus/stube.git"
                                :developerConnection "scm:git:ssh://git@github.com/zekzekus/stube.git"
                                :tag                 (str "v" version)}
                :pom-data      [[:description
                                 "A Clojure component framework over Datastar — Seaside-style call/answer over server-rendered SSE patches."]
                                [:url "https://github.com/zekzekus/stube"]
                                [:licenses
                                 [:license
                                  [:name         "MIT"]
                                  [:url          "https://opensource.org/licenses/MIT"]
                                  [:distribution "repo"]]]
                                [:developers
                                 [:developer
                                  [:name  "Zekeriya Koc"]
                                  [:email "zekzekus@gmail.com"]]]]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Wrote" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Installed" lib version "into local Maven repo"))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer      :remote
    :sign-releases? false
    :artifact       jar-file
    :pom-file       (b/pom-path {:class-dir class-dir :lib lib})}))
