(ns dev.zeko.stube.examples.kasten.mock
  "Mock notes data for the kasten UI port. The shape mirrors the keys
  used by kasten's components — `:xt/id`, `:note/title`, `:note/slug`,
  `:note/markdown`, etc. — so the same render code reads them.")

(def notes
  [{:xt/id "note-atlas"
    :note/title "Atlas of Loose Threads"
    :note/slug "desk / atlas"
    :note/date "12 Apr 2026"
    :note/tag "index"
    :note/kicker "Index Note"
    :note/summary "A desk index for the themes that keep resurfacing in the notebook."
    :note/word-count 286
    :note/markdown (str "Every notebook needs one page that does not try to conclude anything. "
                        "It simply names the motifs that keep resurfacing so the rest of the desk "
                        "stays easy to re-enter.\n\n"
                        "See also [[field / thresholds]] and [[detours / index]].\n\n"
                        "> An index is useful when it feels like an invitation, not a filing cabinet.")
    :note/footnote "A desk atlas should point outward without pretending to be complete."}

   {:xt/id "note-thresholds"
    :note/title "Threshold Rituals"
    :note/slug "field / thresholds"
    :note/date "13 Apr 2026"
    :note/tag "focus"
    :note/kicker "Reading Practice"
    :note/summary "A note about the moment just before work becomes legible."
    :note/word-count 214
    :note/markdown (str "Threshold notes are useful because they describe the step into a task "
                        "rather than the whole task. They hold the air just before attention settles "
                        "and a page becomes workable.\n\n"
                        "See also [[light / before dawn]].")
    :note/footnote "Thresholds matter because they mark the shift from browsing to commitment."}

   {:xt/id "note-bridges"
    :note/title "Bridges Between Margins"
    :note/slug "structure / bridges"
    :note/date "15 Apr 2026"
    :note/tag "links"
    :note/kicker "Cross Reference"
    :note/summary "A reminder that links can feel editorial instead of app-like."
    :note/word-count 242
    :note/markdown (str "A good link does not eject you from the sentence. It keeps you in the "
                        "sentence and lets the next sheet arrive beside it. That is the exact motion "
                        "this horizontal stack is trying to preserve.\n\n"
                        "See also [[field / thresholds]].\n\n"
                        "> A good cross-reference leaves the current page open in your peripheral vision.")
    :note/footnote "Links work best when they feel like margin notes with momentum."}

   {:xt/id "note-lamp"
    :note/title "Lamp Before Dawn"
    :note/slug "light / before dawn"
    :note/date "17 Apr 2026"
    :note/tag "tempo"
    :note/kicker "Early Hours"
    :note/summary "The tiny pool of clarity that appears before the day gets noisy."
    :note/word-count 205
    :note/markdown (str "Some notes only need to hold a single working condition: the table was "
                        "quiet, the light was narrow, and the next thing became readable. A page "
                        "like that is less about output than about the tone that made work possible.\n\n"
                        "See also [[desk / atlas]].")
    :note/footnote "The best early-hour notes remember the light as clearly as the thought."}

   {:xt/id "note-detours"
    :note/title "Index of Useful Detours"
    :note/slug "detours / index"
    :note/date "29 Apr 2026"
    :note/tag "navigation"
    :note/kicker "Side Paths"
    :note/summary "An index for routes that are not shortest but are consistently better."
    :note/word-count 219
    :note/markdown (str "Detours are not always mistakes. Sometimes they are the route that "
                        "preserves energy, timing, or attention. A note stack should be able to "
                        "represent that kind of sideways usefulness without looking lost.\n\n"
                        "See also [[structure / bridges]].")
    :note/footnote "Useful detours earn their keep by arriving better, not faster."}

   {:xt/id "note-rooms"
    :note/title "Rooms for Half-Finished Plans"
    :note/slug "rooms / half finished plans"
    :note/date "19 Apr 2026"
    :note/tag "planning"
    :note/kicker "Unfinished Work"
    :note/summary "A place for plans that should stay open without demanding closure."
    :note/word-count 220
    :note/markdown (str "Not every plan wants a checklist immediately. Some plans just need a room "
                        "where they can stay partially assembled. A stacked notes interface is good "
                        "at that because incompletion can remain visible instead of being overwritten.\n\n"
                        "Adjacent: [[promises / ledger]] and [[tools / anvil]].")
    :note/footnote "Note 6 of 6. No collapsing, no replacing, just accumulation."}

   {:xt/id "note-tide"
    :note/title "Tide Tables for Attention"
    :note/slug "rhythm / tide"
    :note/date "20 Apr 2026"
    :note/tag "rhythm"
    :note/kicker "Daily Cycle"
    :note/summary "Attention has a tide; planning against it is easier than fighting it."
    :note/word-count 196
    :note/markdown (str "Concentration arrives in waves rather than as a steady supply. A tide chart "
                        "for the working day is more honest than a calendar grid: it tells you when "
                        "to push out and when to wait for the water to come back in.\n\n"
                        "Pairs naturally with [[field / thresholds]] and [[light / before dawn]]. "
                        "Compare with [[weather / fog]] for the days the tide just doesn't show up.")
    :note/footnote "Tides are predictable enough to plan around, irregular enough to keep humble."}

   {:xt/id "note-corners"
    :note/title "Corners That Hold Memory"
    :note/slug "memory / corners"
    :note/date "21 Apr 2026"
    :note/tag "place"
    :note/kicker "Spatial Index"
    :note/summary "Specific corners of a room remember specific kinds of work."
    :note/word-count 188
    :note/markdown (str "A particular chair, a particular angle of light, a particular surface — these "
                        "build up a kind of spatial index for the work that happened there. Returning "
                        "to the corner returns the thinking, even before the page does.\n\n"
                        "See [[desk / atlas]] for the broader index, and [[rooms / half finished plans]] "
                        "for the rooms those corners sit inside.")
    :note/footnote "A room is easier to re-enter when its corners have stable jobs."}

   {:xt/id "note-ledger"
    :note/title "Soft Ledger of Promises"
    :note/slug "promises / ledger"
    :note/date "22 Apr 2026"
    :note/tag "commitment"
    :note/kicker "Quiet Accountability"
    :note/summary "An informal ledger captures what was meant, not just what was scheduled."
    :note/word-count 207
    :note/markdown (str "Most obligations don't deserve a ticket and a queue. They want to live in a "
                        "soft ledger that remembers context: who, when, in what mood, expecting what. "
                        "A note system can hold that without forcing the promise into a kanban column.\n\n"
                        "Connects to [[structure / bridges]] (because the references are the ledger), "
                        "[[rooms / half finished plans]] (because some promises are themselves rooms), "
                        "and [[tools / anvil]] for shaping them into something workable.")
    :note/footnote "Soft accountability is still accountability; it just refuses to bark."}

   {:xt/id "note-fog"
    :note/title "Working Through Fog"
    :note/slug "weather / fog"
    :note/date "23 Apr 2026"
    :note/tag "weather"
    :note/kicker "Low Visibility"
    :note/summary "Some days the desk is fogged in. The notes still help; the goal just changes."
    :note/word-count 215
    :note/markdown (str "On a foggy day the goal is to stay near the desk and let small things accrete. "
                        "The horizontal stack helps here too: opening one thin column at a time is more "
                        "honest than pretending the whole project is in view.\n\n"
                        "See [[light / before dawn]] and [[rhythm / tide]] for the days the fog lifts. "
                        "And [[detours / index]] for the route around it when it doesn't.")
    :note/footnote "Fog is a working condition, not a failure to plan."}

   {:xt/id "note-anvil"
    :note/title "Anvil for Working Material"
    :note/slug "tools / anvil"
    :note/date "24 Apr 2026"
    :note/tag "craft"
    :note/kicker "Shaping Work"
    :note/summary "Some notes are anvils — surfaces you bring other notes onto for shaping."
    :note/word-count 232
    :note/markdown (str "Anvil notes don't try to be finished. They are work surfaces: somewhere a "
                        "rough idea can be hammered against earlier notes until it takes a workable "
                        "shape. They earn their keep through what gets made on them, not through what "
                        "they say.\n\n"
                        "Pulls in [[memory / corners]] for the spatial side, [[promises / ledger]] for "
                        "the obligations being shaped, [[desk / atlas]] for orientation, and "
                        "[[weather / fog]] for the days even the anvil disappears.")
    :note/footnote "An anvil is judged by what leaves it, not by what stays."}])

(def notes-by-id
  (into {} (map (juxt :xt/id identity)) notes))

(def notes-by-slug
  (into {} (map (juxt :note/slug identity)) notes))

(defn enrich
  "Compute :note/forward-link-count and :note/backlink-count by scanning
  markdown for `[[slug]]` references."
  [notes]
  (let [pat #"\[\[([^\]]+)\]\]"
        forward (into {}
                      (map (fn [n]
                             [(:xt/id n)
                              (->> (re-seq pat (or (:note/markdown n) ""))
                                   (map second)
                                   (keep #(get-in notes-by-slug [% :xt/id]))
                                   distinct
                                   vec)]))
                      notes)
        back (reduce (fn [acc [src targets]]
                       (reduce (fn [a t] (update a t (fnil conj #{}) src))
                               acc targets))
                     {}
                     forward)]
    (mapv (fn [n]
            (let [fid (:xt/id n)
                  fwd (get forward fid [])
                  bk (get back fid #{})]
              (assoc n
                     :note/forward-link-count (count fwd)
                     :note/backlink-count (count bk)
                     :note/forward-links (mapv notes-by-id fwd)
                     :note/backlinks (mapv notes-by-id bk))))
          notes)))

(def catalog
  (let [enriched (enrich notes)]
    {:notes enriched
     :notes-by-id (into {} (map (juxt :xt/id identity)) enriched)
     :notes-by-slug (into {} (map (juxt :note/slug identity)) enriched)}))
