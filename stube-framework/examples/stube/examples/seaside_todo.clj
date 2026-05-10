(ns stube.examples.seaside-todo
  "A fuller port of the HPI *An Introduction to Seaside* ToDo application.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/seaside-todo>.

  ──────────────────────────────────────────────────────────────────────
  Seaside book → stube map
  ──────────────────────────────────────────────────────────────────────

  The tutorial grows one ToDo application through the chapters:

      StUser / StTask       plain EDN maps + pure model helpers
      StRootTask            :demo/seaside-todo-root task component
      StLoginComponent      :demo/seaside-todo-login
      StRegisterComponent   :demo/seaside-todo-register
      StLoggedInComponent   :demo/seaside-todo-logged-in
      StMenuComponent       :demo/seaside-todo-menu
      StTaskEditor          :demo/seaside-todo-task-editor
      StImageDatabase       :db value kept by the root task
      Magritte descriptions `task-descriptions` data driving form/report
      Ajax/script.aculo.us  already implicit: every stube event is an
                            SSE morph patch over Datastar

  Two differences are intentional and visible in the example's notes:

  * The image database is conversation state instead of a process
    global.  That keeps handlers pure and EDN-persistable, but it is
    not a shared multi-browser app database.
  * The Atom feed chapter wants a custom XML route.  stube currently
    mounts component shells, not arbitrary response handlers, so this
    example shows the feed-shaped data in the notes rather than serving
    `/atomTasks`."
  (:require [clojure.string :as str]
            [stube.core :as s])
  (:import (java.security MessageDigest)
           (java.time LocalDate)))

;; ---------------------------------------------------------------------------
;; Pure model + "image database" helpers
;; ---------------------------------------------------------------------------

(defn- today []
  (LocalDate/now))

(defn- date+ [days]
  (str (.plusDays (today) days)))

(defn- normalize-email [email]
  (str/lower-case (str/trim (str email))))

(defn- sha1 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1")
                        (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- hash-password [password]
  (if (str/blank? (str password)) "" (sha1 password)))

(defn- parse-date [s]
  (try
    (LocalDate/parse (str s))
    (catch Throwable _ nil)))

(defn- valid-date? [s]
  (boolean (parse-date s)))

(defn- ->bool [x]
  (cond
    (true? x) true
    (false? x) false
    (string? x) (= "true" (str/lower-case x))
    :else (boolean x)))

(defn- new-task [id attrs]
  (merge {:id               id
          :task-name        "New Task"
          :task-description ""
          :deadline         (date+ 1)
          :completed        false}
         attrs))

(defn- new-user [{:keys [user-name email password tasks]}]
  {:user-name     (str/trim (str user-name))
   :email         (normalize-email email)
   :password-hash (hash-password password)
   :tasks         (vec tasks)})

(defn- seed-db []
  (let [peter "peter@example.com"
        ada   "ada@example.com"]
    {:next-task-id 8
     :users
     {peter (new-user
              {:user-name "Peter"
               :email     peter
               :password  "seaside"
               :tasks     [(new-task 1 {:task-name        "Implement an atom feed"
                                         :task-description "Chapter 12 renders completed tasks as Atom entries."
                                         :deadline         (date+ -3)
                                         :completed        true})
                           (new-task 2 {:task-name        "Get Milk"
                                         :task-description "Get some milk for breakfast tomorrow."
                                         :deadline         (date+ -2)
                                         :completed        true})
                           (new-task 3 {:task-name        "Missed task"
                                         :task-description "A task whose deadline has already passed."
                                         :deadline         (date+ -1)
                                         :completed        false})
                           (new-task 4 {:task-name        "Pending task"
                                         :task-description "A future task that still needs work."
                                         :deadline         (date+ 1)
                                         :completed        false})]})
      ada   (new-user
              {:user-name "Ada"
               :email     ada
               :password  "ada"
               :tasks     [(new-task 5 {:task-name        "Read the Seaside tutorial"
                                         :task-description "Compare call/answer to stube effects."
                                         :deadline         (date+ 2)})
                           (new-task 6 {:task-name        "Try the stube port"
                                         :task-description "Use menu filters, edit a task, and log out."
                                         :deadline         (date+ 3)})
                           (new-task 7 {:task-name        "Celebrate"
                                         :task-description "This one is already done."
                                         :deadline         (date+ 4)
                                         :completed        true})]})}}))

(defn- db-user [db email]
  (get-in db [:users (normalize-email email)]))

(defn- db-add-user [db user]
  (assoc-in db [:users (:email user)] (assoc user :tasks [])))

(defn- db-add-task [db email task]
  (let [id    (:next-task-id db)
        task' (assoc task :id id)]
    (-> db
        (update :next-task-id inc)
        (update-in [:users (normalize-email email) :tasks] conj task'))))

(defn- db-update-task [db email id f]
  (update-in db [:users (normalize-email email) :tasks]
             (fn [tasks]
               (mapv #(if (= (:id %) id) (f %) %) tasks))))

(defn- task-by-id [db email id]
  (some #(when (= (:id %) id) %)
        (get-in db [:users (normalize-email email) :tasks])))

(defn- pending? [task]
  (and (not (:completed task))
       (not (neg? (compare (:deadline task) (str (today)))))))

(defn- missed? [task]
  (and (not (:completed task))
       (neg? (compare (:deadline task) (str (today))))))

(defn- task-status [task]
  (cond
    (:completed task) :completed
    (missed? task)    :missed
    (pending? task)   :pending
    :else             :open))

(def ^:private status-label
  {:completed "completed"
   :missed    "missed"
   :pending   "pending"
   :open      "open"})

;; ---------------------------------------------------------------------------
;; Magritte-like descriptions: one data source for the form and report
;; ---------------------------------------------------------------------------

(def ^:private task-descriptions
  [{:key      :task-name
    :label    "Task Name"
    :kind     :text
    :priority 10
    :default  "New Task"}
   {:key      :deadline
    :label    "Deadline"
    :kind     :date
    :priority 20
    :default  #(str (today))}
   {:key      :completed
    :label    "Completed?"
    :kind     :boolean
    :priority 30
    :default  false}
   {:key      :task-description
    :label    "Description"
    :kind     :memo
    :priority 40
    :default  ""}])

(defn- description-default [{:keys [default]}]
  (if (fn? default) (default) default))

(defn- task-defaults []
  (into {}
        (map (juxt :key description-default))
        task-descriptions))

(defn- validate-task [task]
  (cond
    (str/blank? (:task-name task))
    "Please enter a task name."

    (< 50 (count (:task-name task)))
    "Task name is invalid or too long."

    (not (valid-date? (:deadline task)))
    "Please choose a valid deadline."

    :else nil))

;; ---------------------------------------------------------------------------
;; Small render helpers
;; ---------------------------------------------------------------------------

(def ^:private page-style
  "max-width:58rem; margin:1rem auto; padding:1rem;
   font-family:system-ui, sans-serif; color:#222;")

(def ^:private two-col-style
  "display:grid; grid-template-columns:12rem minmax(0, 1fr); gap:1rem;")

(defn- message [type text]
  {:type type :text text})

(defn- render-message [{:keys [type text]}]
  (when (seq text)
    [:div {:style (str "margin:0.5rem 0 0.75rem; padding:0.5rem 0.65rem; "
                       "border-radius:0.25rem; border:1px solid; "
                       (case type
                         :error "background:#fff2f2; border-color:#d99; color:#8a1f1f;"
                         :info  "background:#eef6ff; border-color:#9bc; color:#234;"
                         "background:#f7f7f7; border-color:#ccc; color:#333;"))}
     text]))

(defn- field-row [label control]
  [:label {:style "display:grid; grid-template-columns:9rem minmax(0,1fr);
                   gap:0.5rem; align-items:start; margin:0.45rem 0;"}
   [:span {:style "font-weight:600; padding-top:0.35rem;"} label]
   control])

(defn- input-style []
  "box-sizing:border-box; width:100%; padding:0.4rem; font:inherit;")

(defn- plain-button [self label route]
  [:button (merge {:type "button" :class "stube-button"}
                  (s/on self :click :as route))
   label])

;; ---------------------------------------------------------------------------
;; Login and registration components
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/seaside-todo-login
  :init (fn [{:keys [db]}]
          {:db       db
           :email    ""
           :password ""
           :message  nil})

  :keep #{:email :password}

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card stube-modal"
               :style "font-family:system-ui, sans-serif;"}
     [:h1 {:style "margin-top:0;"} "ToDo Application"]
     [:p "Please login with e-mail and password:"]
     (render-message (:message self))
     [:form (merge {:style "display:flex; flex-direction:column; gap:0.55rem;"}
                   (s/on self :submit))
      [:input (merge {:name        "email"
                      :type        "email"
                      :placeholder "peter@example.com"
                      :value       (:email self)
                      :style       (input-style)
                      :autofocus   true}
                     (s/local-bind self :email))]
      [:input (merge {:name        "password"
                      :type        "password"
                      :placeholder "seaside"
                      :value       ""
                      :style       (input-style)}
                     (s/local-bind self :password))]
      [:div {:class "stube-actions"}
       [:button {:type "submit" :class "stube-button stube-button--primary"}
        "Login"]
       (plain-button self "Sign up" :register)]]
     [:details {:style "margin-top:1rem; color:#666; font-size:0.9rem;"}
      [:summary "Demo users"]
      [:ul
       [:li [:code "peter@example.com"] " / " [:code "seaside"]]
       [:li [:code "ada@example.com"] " / " [:code "ada"]]]]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :submit
      (let [email (normalize-email (:email self))
            user  (db-user (:db self) email)]
        (if (and user (= (:password-hash user) (hash-password (:password self))))
          [self [[:answer {:op :login :email email}]]]
          [(assoc self
                  :password ""
                  :message  (message :error "Login failed."))
           [[:patch-signals {(s/local-signal self :password) ""}]]]))

      :register
      [self [[:answer {:op :register}]]]

      [self []])))

(s/defcomponent :demo/seaside-todo-register
  :init (fn [{:keys [db]}]
          {:db                db
           :user-name         ""
           :email             ""
           :password          ""
           :repeated-password ""
           :message           nil})

  :keep #{:user-name :email :password :repeated-password}

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card stube-modal"
               :style "font-family:system-ui, sans-serif;"}
     [:h1 {:style "margin-top:0;"} "Register"]
     (render-message (:message self))
     [:form (merge {:style "margin-top:0.75rem;"}
                   (s/on self :submit))
      (field-row "Username"
                 [:input (merge {:name      "user-name"
                                 :value     (:user-name self)
                                 :style     (input-style)
                                 :autofocus true}
                                (s/local-bind self :user-name))])
      (field-row "E-mail"
                 [:input (merge {:name  "email"
                                 :type  "email"
                                 :value (:email self)
                                 :style (input-style)}
                                (s/local-bind self :email))])
      (field-row "Password"
                 [:input (merge {:name  "password"
                                 :type  "password"
                                 :value ""
                                 :style (input-style)}
                                (s/local-bind self :password))])
      (field-row "Repeat password"
                 [:input (merge {:name  "repeated-password"
                                 :type  "password"
                                 :value ""
                                 :style (input-style)}
                                (s/local-bind self :repeated-password))])
      [:div {:class "stube-actions"}
       [:button {:type "submit" :class "stube-button stube-button--primary"}
        "Register"]
       (plain-button self "Cancel" :cancel)]]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :submit
      (let [email (normalize-email (:email self))]
        (cond
          (str/blank? (:user-name self))
          [(assoc self :message (message :info "Please choose a username!")) []]

          (str/blank? email)
          [(assoc self :message (message :info "Please enter your e-mail address!")) []]

          (db-user (:db self) email)
          [(assoc self :message (message :error "The e-mail address is already registered!")) []]

          (str/blank? (:password self))
          [(assoc self :message (message :info "Please choose a password!")) []]

          (not= (:password self) (:repeated-password self))
          [(assoc self :message (message :info "Your repeated password does not match!")) []]

          :else
          [self [[:answer {:op   :registered
                           :user (new-user {:user-name (:user-name self)
                                            :email     email
                                            :password  (:password self)
                                            :tasks     []})}]]]))

      :cancel
      [self [[:answer s/cancel]]]

      [self []])))

;; ---------------------------------------------------------------------------
;; Menu component: Seaside-style callback entries as parent events
;; ---------------------------------------------------------------------------

(def ^:private menu-entries
  [["All"       [:filter :all]]
   ["Completed" [:filter :completed]]
   ["Pending"   [:filter :pending]]
   ["Missed"    [:filter :missed]]
   ["New Task"  :new-task]
   ["Logout"    :logout]])

(s/defcomponent :demo/seaside-todo-menu
  :render
  (fn [self]
    (let [parent {:instance/id (:instance/parent self)}]
      [:nav {:id    (:instance/id self)
             :style "display:flex; flex-direction:column; gap:0.35rem;"}
       (for [[label route] menu-entries]
         [:button (merge {:key   label
                          :type  "button"
                          :class "stube-button stube-button--block"}
                         (s/on parent :click :as route))
          label])
       [:p {:style "font-size:0.8rem; color:#777; line-height:1.35;"}
        "Like " [:code "StMenuComponent>>#addEntry:withAction:"]
        ", but the callback is an EDN event routed to the parent."]])))

;; ---------------------------------------------------------------------------
;; Task editor, generated from descriptions
;; ---------------------------------------------------------------------------

(defn- editor-state [{:keys [mode task]}]
  (let [task (merge (task-defaults) task)]
    {:mode             (or mode :edit)
     :id               (:id task)
     :task-name        (:task-name task)
     :task-description (:task-description task)
     :deadline         (:deadline task)
     :completed        (if (->bool (:completed task)) "true" "false")
     :message          nil}))

(defn- editor-task [self]
  (cond-> {:task-name        (str/trim (str (:task-name self)))
           :task-description (str (:task-description self))
           :deadline         (str (:deadline self))
           :completed        (->bool (:completed self))}
    (:id self) (assoc :id (:id self))))

(defn- render-described-field [self {:keys [key label kind]}]
  (let [value (get self key)]
    (field-row
      label
      (case kind
        :memo
        [:textarea (merge {:name  (name key)
                           :style (str (input-style) " min-height:5rem;")}
                          (s/local-bind self key))
         value]

        :date
        [:input (merge {:name  (name key)
                        :type  "date"
                        :value value
                        :style (input-style)}
                       (s/local-bind self key))]

        :boolean
        [:select (merge {:name  (name key)
                         :style (input-style)}
                        (s/local-bind self key))
         [:option {:value "false" :selected (not (->bool value))} "no"]
         [:option {:value "true"  :selected (->bool value)} "yes"]]

        [:input (merge {:name      (name key)
                        :value     value
                        :style     (input-style)
                        :autofocus (= key :task-name)}
                       (s/local-bind self key))]))))

(s/defcomponent :demo/seaside-todo-task-editor
  :init editor-state

  :keep #{:task-name :task-description :deadline :completed}

  :render
  (fn [self]
    [:div {:id    (:instance/id self)
           :style "position:fixed; inset:0; background:rgb(0 0 0 / 22%);
                   display:flex; align-items:flex-start; justify-content:center;
                   padding:3rem 1rem; z-index:10;"}
     [:section {:class "stube-card"
                :style "width:min(40rem, 100%);"}
      [:h2 {:style "margin-top:0;"}
       (if (= :new (:mode self)) "New task" "Editing task")]
      [:p {:style "color:#666; margin-top:-0.35rem;"}
       "This form is rendered from " [:code "task-descriptions"]
       ", the local Magritte analogue."]
      (render-message (:message self))
      [:form (merge {:style "margin-top:0.75rem;"}
                    (s/on self :submit))
       (for [desc (sort-by :priority task-descriptions)]
         (render-described-field self desc))
       [:div {:class "stube-actions stube-actions--end"}
        [:button {:type "submit" :class "stube-button stube-button--primary"}
         "Save"]
        (plain-button self "Cancel" :cancel)]]]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :submit
      (let [task (editor-task self)]
        (if-let [problem (validate-task task)]
          [(assoc self :message (message :error problem)) []]
          [self [[:answer {:op   (:mode self)
                           :task task}]]]))

      :cancel
      [self [[:answer s/cancel]]]

      [self []])))

;; ---------------------------------------------------------------------------
;; Logged-in application component
;; ---------------------------------------------------------------------------

(def ^:private filter-label
  {:all       "All"
   :completed "Completed"
   :pending   "Pending"
   :missed    "Missed"})

(defn- visible-tasks [filter-key tasks]
  (case filter-key
    :all       tasks
    :completed (filter :completed tasks)
    :pending   (filter pending? tasks)
    :missed    (filter missed? tasks)
    tasks))

(defn- sort-value [sort-key task]
  (case sort-key
    :task-name (str/lower-case (:task-name task))
    :deadline  (:deadline task)
    :completed (if (:completed task) 1 0)
    :status    (name (task-status task))
    (:deadline task)))

(defn- sorted-visible-tasks [self]
  (let [tasks  (get-in self [:db :users (:email self) :tasks])
        sorted (sort-by #(sort-value (:sort-key self) %)
                        (visible-tasks (:filter self) tasks))]
    (vec (if (= :desc (:sort-dir self)) (reverse sorted) sorted))))

(defn- sort-header [self sort-key label]
  (let [active? (= (:sort-key self) sort-key)
        arrow   (when active? (if (= :asc (:sort-dir self)) " ↑" " ↓"))]
    [:button (merge {:type  "button"
                     :style "border:0; background:transparent; padding:0;
                             font:inherit; font-weight:700; cursor:pointer;"}
                    (s/on self :click :as [:sort sort-key]))
     label arrow]))

(defn- render-task-row [self task]
  [:tr {:key (:id task)}
   [:td {:style "padding:0.35rem; text-align:center;"}
    [:input (merge {:type    "checkbox"
                    :checked (boolean (:completed task))}
                   (s/on self :change :as [:toggle (:id task)]))]]
   [:td {:style "padding:0.35rem; white-space:nowrap;"}
    (:deadline task)]
   [:td {:style "padding:0.35rem; font-weight:600;"}
    (:task-name task)]
   [:td {:style "padding:0.35rem;"}
    (:task-description task)]
   [:td {:style "padding:0.35rem;"}
    [:span {:style (str "display:inline-block; padding:0.1rem 0.35rem; "
                        "border-radius:999px; font-size:0.78rem; "
                        (case (task-status task)
                          :completed "background:#e9f8ec; color:#17692a;"
                          :missed    "background:#fff0f0; color:#8a1f1f;"
                          :pending   "background:#eef6ff; color:#234;"
                          "background:#eee; color:#555;"))}
     (status-label (task-status task))]]
   [:td {:style "padding:0.35rem; text-align:right;"}
    (plain-button self "edit" [:edit (:id task)])]])

(defn- render-report [self]
  (let [tasks (sorted-visible-tasks self)]
    [:section
     [:div {:style "display:flex; align-items:baseline; gap:0.75rem;
                    flex-wrap:wrap; margin-bottom:0.5rem;"}
      [:h2 {:style "margin:0;"}
       (get filter-label (:filter self) "Tasks") " tasks"]
      [:span {:style "color:#777; font-size:0.9rem;"}
       (count tasks) " shown"]
      [:span {:style "color:#777; font-size:0.9rem; margin-left:auto;"}
       "Report columns mirror Magritte's " [:code "MAReport"] "."]]
     [:table {:style "width:100%; border-collapse:collapse; font-size:0.95rem;"}
      [:thead
       [:tr {:style "border-bottom:2px solid #ccc; text-align:left;"}
        [:th {:style "padding:0.35rem; width:3rem;"}
         (sort-header self :completed "Done")]
        [:th {:style "padding:0.35rem;"}
         (sort-header self :deadline "Deadline")]
        [:th {:style "padding:0.35rem;"}
         (sort-header self :task-name "Task Name")]
        [:th {:style "padding:0.35rem;"} "Description"]
        [:th {:style "padding:0.35rem;"}
         (sort-header self :status "Status")]
        [:th {:style "padding:0.35rem;"} ""]]]
      [:tbody
       (if (seq tasks)
         (for [task tasks]
           (render-task-row self task))
         [[:tr
           [:td {:colspan 6
                 :style   "padding:1rem; color:#777; text-align:center;"}
            "No tasks match this filter."]]])]]]))

(defn- render-notes []
  [:details {:style "margin-top:1rem; font-size:0.85rem; color:#555;"}
   [:summary {:style "cursor:pointer; font-weight:600;"}
    "Mirror notes / missing pieces"]
   [:ul {:style "padding-left:1.2rem; line-height:1.45;"}
    [:li "Ajax: no special section here — Datastar already makes each "
     "event an incremental SSE morph patch."]
    [:li "Persistence: the database is a plain value carried by the root "
     "task.  Use stube's file store to persist the conversation, but a "
     "shared application database is outside the current example API."]
    [:li "Magritte: represented as description maps that generate the "
     "editor and report; there is no framework-level description system."]
    [:li "Atom feed: would need a custom non-component XML route like "
     [:code "/atomTasks"] "."]]])

(s/defcomponent :demo/seaside-todo-logged-in
  :init (fn [{:keys [db email]}]
          {:db           db
           :email        email
           :filter       :pending
           :sort-key     :deadline
           :sort-dir     :asc
           :dialog-open? false
           :message      (message :info "Welcome — this is the Seaside book ToDo app in stube.")})

  :children {:slot/menu (s/embed :demo/seaside-todo-menu)}

  :render
  (fn [self]
    (let [user (db-user (:db self) (:email self))]
      [:section {:id    (:instance/id self)
                 :style page-style}
       [:header {:style "display:flex; align-items:baseline; gap:0.75rem;
                         flex-wrap:wrap; margin-bottom:1rem;"}
        [:h1 {:style "margin:0;"} "ToDo-List of " (:user-name user)]
        [:code {:style "color:#777;"} (:email user)]]
       (render-message (:message self))
       [:div {:style two-col-style}
        [:aside
         (s/render-slot self :slot/menu)
         (render-notes)]
        [:main
         (render-report self)]]
       (when (:dialog-open? self)
         (s/render-slot self :slot/dialog))]))

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :filter
      [(assoc self :filter payload :message nil) []]

      :sort
      [(if (= (:sort-key self) payload)
         (update self :sort-dir {:asc :desc :desc :asc})
         (assoc self :sort-key payload :sort-dir :asc))
       []]

      :toggle
      [(-> self
           (update :db db-update-task (:email self) payload
                   #(update % :completed not))
           (assoc :message nil))
       []]

      :new-task
      [(assoc self :dialog-open? true :message nil)
       [[:call-in-slot :slot/dialog
         (s/embed :demo/seaside-todo-task-editor {:mode :new})
         :resume :on-editor]]]

      :edit
      (if-let [task (task-by-id (:db self) (:email self) payload)]
        [(assoc self :dialog-open? true :message nil)
         [[:call-in-slot :slot/dialog
           (s/embed :demo/seaside-todo-task-editor {:mode :edit :task task})
           :resume :on-editor]]]
        [(assoc self :message (message :error "That task no longer exists.")) []])

      :logout
      [self [[:answer {:op :logout :db (:db self)}]]]

      [self []]))

  :on-editor
  (fn [self answer]
    (cond
      (= answer s/cancel)
      [(assoc self :dialog-open? false :message nil) []]

      (= :new (:op answer))
      [(-> self
           (update :db db-add-task (:email self) (:task answer))
           (assoc :dialog-open? false
                  :filter :all
                  :message (message :info "Task created.")))
       []]

      (= :edit (:op answer))
      (let [task (:task answer)]
        [(-> self
             (update :db db-update-task (:email self) (:id task) merge task)
             (assoc :dialog-open? false
                    :message (message :info "Task saved.")))
         []])

      :else
      [(assoc self :dialog-open? false) []])))

;; ---------------------------------------------------------------------------
;; Root task: login/register/logged-in flow
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/seaside-todo-root
  :init (constantly {:db (seed-db)})

  :start
  (fn [self]
    [self [[:call (s/embed :demo/seaside-todo-login {:db (:db self)})
            :resume :on-login]]])

  :on-login
  (fn [self answer]
    (case (:op answer)
      :register
      [self [[:call (s/embed :demo/seaside-todo-register {:db (:db self)})
              :resume :on-register]]]

      :login
      [(assoc self :email (:email answer))
       [[:call (s/embed :demo/seaside-todo-logged-in
                        {:db (:db self) :email (:email answer)})
         :resume :on-logged-in]]]

      [self [[:call (s/embed :demo/seaside-todo-login {:db (:db self)})
              :resume :on-login]]]))

  :on-register
  (fn [self answer]
    (if (= answer s/cancel)
      [self [[:call (s/embed :demo/seaside-todo-login {:db (:db self)})
              :resume :on-login]]]
      (let [db'   (db-add-user (:db self) (:user answer))
            email (get-in answer [:user :email])]
        [(assoc self :db db' :email email)
         [[:call (s/embed :demo/seaside-todo-logged-in {:db db' :email email})
           :resume :on-logged-in]]])))

  :on-logged-in
  (fn [self answer]
    (let [db' (or (:db answer) (:db self))]
      [(assoc self :db db' :email nil)
       [[:call (s/embed :demo/seaside-todo-login {:db db'})
         :resume :on-login]]])))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/seaside-todo" :demo/seaside-todo-root)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))
