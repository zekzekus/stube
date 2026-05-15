(ns dev.zeko.stube.examples.calc
  "A four-function calculator, lifted from the Seaside book.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/calc>.

  ──────────────────────────────────────────────────────────────────────
  What this exercises
  ──────────────────────────────────────────────────────────────────────

  * One component, **lots** of events.  Twenty buttons all funnel into a
    single `:handle` `case`.  This is the densest practical test of the
    `(s/on self :click :as :foo)` ergonomics shipped in slice 0.
  * Pure-state UI: no children, no `:call`, no `:await`.  Every click
    transitions a small state map and the kernel auto-re-renders the
    same instance with the new state via morph-by-id.
  * Numeric display state: the calculator behaves like a real pocket
    calculator (`9 + 3 * 2 =` evaluates left-to-right to `24`,
    overflowing entries are clamped, `C` clears, `±` toggles sign).

  ──────────────────────────────────────────────────────────────────────
  State shape
  ──────────────────────────────────────────────────────────────────────

      {:display    \"42\"   ; the digits currently shown
       :acc        7        ; left operand of the pending op (or nil)
       :op         :plus    ; pending op (or nil)
       :fresh?     true}    ; if true, next digit replaces :display

  After `=` we set `:fresh? true` so the next digit starts a new entry.

  ──────────────────────────────────────────────────────────────────────
  DX note
  ──────────────────────────────────────────────────────────────────────
  Digit and operator buttons use structured event payloads, so the
  handler has one `:digit` clause and one `:op` clause instead of a
  route keyword per key."
  (:require [clojure.string :as str]
            [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Pure helpers (testable from a REPL with no server)
;; ---------------------------------------------------------------------------

(defn- ->num [s]
  ;; Display strings are short and well-formed; parseDouble accepts both
  ;; integers and decimals.  An empty display reads as zero.
  (if (or (nil? s) (= "" s)) 0 (Double/parseDouble s)))

(defn- ->display [n]
  ;; Show integers without the trailing ".0" so the calculator looks
  ;; like a calculator and not like a Java REPL.
  (let [d (double n)]
    (if (== d (long d))
      (str (long d))
      (str d))))

(defn- apply-op [op a b]
  (case op
    :plus  (+ a b)
    :minus (- a b)
    :times (* a b)
    :div   (if (zero? b) "Err" (/ a b))
    b))

(defn- press-digit [self d]
  (let [cur (if (:fresh? self) "" (:display self))]
    (assoc self
           :display (cond
                      (and (= cur "0") (not= d ".")) (str d)
                      (and (= d ".") (str/includes? cur ".")) cur
                      :else (str cur d))
           :fresh?  false)))

(defn- press-op [self op]
  (let [n (->num (:display self))]
    (cond
      (= "Err" (:display self)) self
      (and (:op self) (not (:fresh? self)))
      (let [r (apply-op (:op self) (:acc self) n)]
        (assoc self :display (->display r) :acc (if (number? r) r 0)
                    :op op :fresh? true))
      :else
      (assoc self :acc n :op op :fresh? true))))

(defn- press-eq [self]
  (if (:op self)
    (let [r (apply-op (:op self) (:acc self) (->num (:display self)))]
      (assoc self
             :display (if (number? r) (->display r) r)
             :acc     (if (number? r) r 0)
             :op      nil
             :fresh?  true))
    self))

(defn- press-clear [_self]
  {:display "0" :acc 0 :op nil :fresh? true})

(defn- press-neg [self]
  (let [d (:display self)]
    (assoc self
           :display (cond
                      (= "Err" d) d
                      (str/starts-with? d "-") (subs d 1)
                      (= "0" d) d
                      :else (str "-" d)))))

;; ---------------------------------------------------------------------------
;; The component
;; ---------------------------------------------------------------------------

(defn- key-button [self label route-event]
  ;; Common button styling so the calculator looks vaguely like one.
  ;; `route-event` may carry structured payloads such as `[:digit "7"]`.
  [:button (merge {:type  "button"
                   :style "padding:0.6rem 0; font-size:1.1rem;
                           border:1px solid #bbb; border-radius:0.25rem;
                           background:#f4f4f4; cursor:pointer;"}
                  (s/on self :click :as route-event))
   label])

(s/defcomponent :demo/calc
  :init (constantly {:display "0" :acc 0 :op nil :fresh? true})

  :render
  (fn [self]
    [:section (s/root-attrs self {:style "display:inline-block; padding:1rem; margin:1rem;
                                          font-family:system-ui, sans-serif;
                                          border:1px solid #ccc; border-radius:0.5rem;
                                          background:#fff; max-width:18rem;"})
     [:h2 {:style "margin-top:0;"} "Calculator"]
     [:div {:style "background:#222; color:#0f0; font-family:monospace;
                    font-size:1.6rem; padding:0.5rem 0.75rem;
                    text-align:right; border-radius:0.25rem;
                    margin-bottom:0.75rem; min-height:2rem;
                    overflow:hidden;"}
      (:display self)]
     [:div {:style "display:grid; grid-template-columns:repeat(4, 1fr);
                    gap:0.4rem;"}
      ;; Row 1
      (key-button self "C"  :clear)
      (key-button self "±"  :neg)
      (key-button self "÷"  [:op :div])
      (key-button self "×"  [:op :times])
      ;; Row 2
      (key-button self "7"  [:digit "7"])
      (key-button self "8"  [:digit "8"])
      (key-button self "9"  [:digit "9"])
      (key-button self "−"  [:op :minus])
      ;; Row 3
      (key-button self "4"  [:digit "4"])
      (key-button self "5"  [:digit "5"])
      (key-button self "6"  [:digit "6"])
      (key-button self "+"  [:op :plus])
      ;; Row 4
      (key-button self "1"  [:digit "1"])
      (key-button self "2"  [:digit "2"])
      (key-button self "3"  [:digit "3"])
      ;; `=` is taller; rendered with grid-row span so we stay on a 4-col grid.
      [:button (merge {:type  "button"
                       :style "grid-row: span 2;
                               padding:0.6rem 0; font-size:1.1rem;
                               border:1px solid #bbb; border-radius:0.25rem;
                               background:#fa3; cursor:pointer; color:white;"}
                      (s/on self :click :as :eq))
       "="]
      ;; Row 5 (final three cells, `=` already spans here)
      (key-button self "0"  [:digit "0"])
      (key-button self "."  [:digit "."])
      ;; Skip one cell so `=` sits flush right.
      [:span]]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :digit (press-digit self payload)
      :op    (press-op self payload)
      :eq    (press-eq self)
      :clear (press-clear self)
      :neg   (press-neg self)
      self)))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/calc" :demo/calc)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))
