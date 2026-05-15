(ns dev.zeko.stube.examples.kasten.markdown
  "Tiny markdown renderer for the kasten example. Handles the subset
  used by the mock data: paragraphs, blockquotes, and `[[slug]]` wiki
  links. Wiki links resolve via the catalog and emit click events
  routed to the desk via the `wiki-link` callback supplied by the
  parent."
  (:require [clojure.string :as str]))

(def ^:private wiki-pat #"\[\[([^\]]+)\]\]")

(defn- inline-segments
  "Split `text` into hiccup segments, replacing `[[slug]]` with calls to
  `wiki-link` (a 1-arg fn taking the raw slug, returning a hiccup vector
  or string)."
  [text wiki-link]
  (let [m (re-matcher wiki-pat text)]
    (loop [cursor 0 out []]
      (if (.find m)
        (let [start (.start m)
              end (.end m)
              slug (.group m 1)
              out (cond-> out
                    (< cursor start) (conj (subs text cursor start))
                    true (conj (wiki-link slug)))]
          (recur end out))
        (cond-> out
          (< cursor (count text)) (conj (subs text cursor)))))))

(defn- block->hiccup
  [block wiki-link]
  (cond
    (str/starts-with? block "> ")
    [:blockquote.note-column__quote
     (into [:p.note-column__quote-paragraph]
           (inline-segments (subs block 2) wiki-link))]

    :else
    (into [:p.note-column__paragraph]
          (inline-segments block wiki-link))))

(defn render-body
  "Returns a vector of hiccup blocks for the note's markdown body.
  `wiki-link` is `(fn [raw-slug] hiccup-or-string)` used to render
  `[[slug]]` references."
  [markdown wiki-link]
  (->> (str/split (or markdown "") #"\n\n+")
       (map str/trim)
       (remove str/blank?)
       (mapv #(block->hiccup % wiki-link))))
