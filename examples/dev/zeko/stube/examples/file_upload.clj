(ns dev.zeko.stube.examples.file-upload
  "File upload — Seaside's `WAFileUploadExample`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/file-upload>.

  Uploads use a normal multipart POST to `/stube/upload/:cid/:iid`,
  targeted at a hidden iframe so the Datastar shell does not navigate.
  The HTTP layer converts the parsed upload into a regular
  `:upload-received` event for this component."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dev.zeko.stube.core :as s]))

(defn- textual? [{:keys [filename content-type]}]
  (or (str/starts-with? (str content-type) "text/")
      (some #(str/ends-with? (str/lower-case (str filename)) %)
            [".clj" ".cljs" ".edn" ".txt" ".md" ".csv"])))

(defn- preview [{:keys [tempfile] :as file}]
  (when (and tempfile (textual? file) (.exists (io/file tempfile)))
    (let [s (slurp tempfile)]
      (subs s 0 (min 500 (count s))))))

(defn- safe-summary [fields file]
  (let [preview (preview file)]
    (cond-> (-> file
                (dissoc :tempfile)
                (assoc :note (get fields :note "")))
      preview (assoc :preview preview))))

(defn- cleanup-effects [files]
  (mapv (fn [path]
          [:io #(io/delete-file path true)])
        (keep :tempfile files)))

(s/defcomponent :demo/file-upload
  :doc "WAFileUploadExample port: multipart POST routed back as :upload-received."

  :init (constantly {:uploads []})

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card"
               :style "max-width:42rem; margin:1rem; font-family:system-ui, sans-serif;"}
     [:h2 {:style "margin-top:0;"} "File upload"]
     [:p {:style "color:#555;"}
      "The form is plain HTML multipart.  Its response lands in a hidden "
      "iframe; this visible page updates over the existing SSE stream."]
     [:form (merge {:style "display:grid; gap:0.75rem;"}
                   (s/upload-attrs self))
      [:label
       [:span {:class "stube-label"} "File"]
       [:input {:type "file" :name "file" :class "stube-input"}]]
      [:label
       [:span {:class "stube-label"} "Note"]
       [:input {:type "text" :name "note" :class "stube-input"
                :placeholder "optional caption"}]]
      [:button {:type "submit" :class "stube-button stube-button--primary"}
       "Upload"]]
     (s/upload-frame self)
     [:h3 "Received"]
     (if (seq (:uploads self))
       [:ol {:style "padding-left:1.25rem;"}
        (for [{:keys [filename content-type size note preview]} (:uploads self)]
          [:li {:key (str filename size note) :style "margin-bottom:0.75rem;"}
           [:strong filename]
           [:div {:style "color:#666;"}
            content-type " · " size " bytes"
            (when (seq note) (str " · note: " note))]
           (when preview
             [:pre {:style "white-space:pre-wrap; max-height:10rem; overflow:auto;
                            background:#f6f6f8; padding:0.5rem;"}
              preview])])]
       [:p {:style "color:#666;"} "No files uploaded yet."])])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :upload-received
      (let [files   (:files payload)
            fields  (:fields payload)
            entries (mapv #(safe-summary fields %) files)]
        [(update self :uploads into entries)
         (cleanup-effects files)])
      [self []])))

(s/mount! "/file-upload" :demo/file-upload)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))
