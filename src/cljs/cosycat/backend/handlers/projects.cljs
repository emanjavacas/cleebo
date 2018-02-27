(ns cosycat.backend.handlers.projects
  (:require [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [ajax.core :refer [POST GET]]
            [cosycat.utils :refer [format current-results now]]
            [cosycat.app-utils :refer [get-pending-users deep-merge update-coll]]
            [cosycat.routes :refer [nav!]]
            [cosycat.backend.middleware :refer [standard-middleware]]
            [cosycat.backend.db
             :refer [default-project-session default-project-history default-settings
                     get-project-settings]]
            [taoensso.timbre :as timbre]))

(defn normalize-projects
  "transforms server project to client project"
  [projects]
  (reduce
   (fn [acc {:keys [name issues events] :as project}]
     (assoc acc name (cond-> project
                       true (assoc :session (default-project-session project))
                       issues (assoc :issues (zipmap (map :id issues) issues))
                       events (assoc :events (zipmap (map :id events) events)))))
   {}
   projects))

;;; General
(re-frame/register-handler              ;add project to client-db
 :add-project
 standard-middleware
 (fn [db [_ project]]
   (update db :projects merge (normalize-projects [project]))))

(re-frame/register-handler              ;remove project from client-db
 :remove-project
 standard-middleware
 (fn [db [_ project-name]]
   (update db :projects dissoc project-name)))

(defn error-handler [{{:keys [message data code]} :response}]
  (re-frame/dispatch [:notify {:message message :meta data :status :error}]))

(defn new-project-handler [{project-name :name :as project}]
  (re-frame/dispatch [:add-project project])
  (nav! (str "/project/" project-name)))

(re-frame/register-handler
 :new-project
 standard-middleware
 (fn [db [_ {:keys [name description users] :as project}]]
   (POST "/project/new"
         {:params {:project-name name
                   :description description
                   :users users}
          :handler new-project-handler
          :error-handler error-handler})
   db))

;;; Issues
(re-frame/register-handler              ;add project update to client-db
 :update-project-issue
 standard-middleware
 (fn [db [_ project-name {id :id :as issue}]]
   (update-in db [:projects project-name :issues id] deep-merge issue)))

(re-frame/register-handler
 :add-issue-meta
 standard-middleware
 (fn [db [_ issue-id path value]]
   (let [active-project (get-in db [:session :active-project])]
     (assoc-in db (into [:projects active-project :issues issue-id :meta] path) value))))

(re-frame/register-handler
 :update-issue-meta
 standard-middleware
 (fn [db [_ issue-id path update-fn]]
   (let [active-project (get-in db [:session :active-project])]
     (update-in db (into [:projects active-project :issues issue-id :meta] path) update-fn))))

(defn project-add-issue-handler [project-name]
  (fn [issue]
    (re-frame/dispatch [:notify {:message "New issue was added to project"}])
    (re-frame/dispatch [:update-project-issue project-name issue])))

(re-frame/register-handler
 :project-issue
 standard-middleware
 (fn [db [_ {:keys [payload project-name]}]]
   (let [project-name (or project-name (get-in db [:session :active-project]))]
     (POST "/project/issues/new"
           {:params {:project-name project-name :payload payload}
            :handler (project-add-issue-handler project-name)
            :error-handler error-handler}))
   db))

(re-frame/register-handler
 :comment-on-issue
 (fn [db [_ {:keys [comment issue-id parent-id] :as params}]]
   (let [active-project (get-in db [:session :active-project])]
     (POST "/project/issues/comment/new"
           {:params (assoc params :project-name active-project)
            :handler #(re-frame/dispatch [:update-project-issue active-project %])
            :error-handler #(re-frame/dispatch
                             [:notify {:message "Couldn't store comment" :status :error}])})
     db)))

(re-frame/register-handler
 :delete-comment-on-issue
 (fn [db [_ {:keys [comment-id issue-id] :as params}]]
   (let [active-project (get-in db [:session :active-project])]
     (POST "/project/issues/comment/delete"
           {:params (assoc params :project-name active-project)
            :handler #(re-frame/dispatch [:update-project-issue active-project %])
            :error-handler #(re-frame/dispatch
                             [:notify {:message "Couldn't delete comment" :status :error}])})
     db)))

(defn make-annotation-issue-handler [issue-type]
  (fn [db [_ ann-data & {:keys [users] :or {users "all"}}]] ;default to users for now
    (let [active-project (get-in db [:session :active-project])
          path-to-results [:projects active-project :session :query :results]
          corpus (get-in db (into path-to-results [:results-summary :corpus]))
          query (get-in db (into path-to-results [:results-summary :query-str]))
          ann-data (assoc ann-data :corpus corpus :query query :timestamp (now))]
      (POST "/project/issues/annotation/open"
            {:params {:project-name active-project
                      :type issue-type
                      :users users
                      :ann-data ann-data}
             :handler (project-add-issue-handler active-project)
             :error-handler error-handler})
      db)))

(re-frame/register-handler
 :open-annotation-edit-issue
 (make-annotation-issue-handler "annotation-edit"))

(re-frame/register-handler
 :open-annotation-remove-issue
 (make-annotation-issue-handler "annotation-remove"))

(defn close-annotation-issue-handler [project-name issue-type]
  (fn [{:keys [status message data]}]
    (if (= status :error)
      (re-frame/dispatch [:notify {:message (format "Couldn't close issue. Reason [%s]" message) :status :error}])
      (let [{{{:keys [status]} :resolve :as issue-payload} :issue-payload ann-payload :ann-payload} data]
        (re-frame/dispatch [:update-project-issue project-name issue-payload])
        (when (and (= issue-type "annotation-edit") (= status "accepted"))
          (re-frame/dispatch
           [:add-annotation
            {:payload ann-payload
             ;; assuming all ws incoming annotations only go to query panel
             :db-path [:session :query :results :results-by-id]}]))
        (when (and (= issue-type "annotation-remove") (= status "accepted"))
          (re-frame/dispatch [:remove-annotation ann-payload]))
        (re-frame/dispatch [:notify {:message "Issue was succesfully closed"}])))))

(re-frame/register-handler
 :close-annotation-issue
 (fn [db [_ issue-id action & {:keys [comment]}]]
   (let [active-project (get-in db [:session :active-project])
         {:keys [type]} (get-in db [:projects active-project :issues issue-id])]
     (POST "/project/issues/annotation/close"
           {:params (cond-> {:project-name active-project
                             :issue-id issue-id
                             :action action}
                      comment (assoc :comment comment))
            :handler (close-annotation-issue-handler active-project type)
            :error-handler error-handler})
     db)))

;;; Users
(re-frame/register-handler              ;add user to project in client-db
 :add-project-user
 standard-middleware
 (fn [db [_ {:keys [user project-name]}]]
   (update-in db [:projects project-name :users] conj user)))

(defn project-add-user-handler [{:keys [user project-name] :as data}]
  (re-frame/dispatch [:add-project-user data]))

(re-frame/register-handler
 :project-add-user
 standard-middleware
 (fn [db [_ {:keys [username role]}]]
   (let [project-name (get-in db [:session :active-project])]
     (POST "/project/add-user"
           {:params {:username username :role role :project-name project-name}
            :handler project-add-user-handler
            :error-handler error-handler}))
   db))

(re-frame/register-handler              ;remove user from project in client-db
 :remove-project-user
 standard-middleware
 (fn [db [_ {:keys [username project-name]}]]
   (update-in
    db [:projects project-name :users]
    (fn [users] (vec (remove #(= (:username %) username) users))))))

(defn remove-user-handler [project-name]
  (fn []
    (nav! "/")
    (re-frame/dispatch [:remove-project project-name])
    (re-frame/dispatch [:notify {:message (str "Goodbye from project " project-name)}])))

(re-frame/register-handler
 :project-remove-user
 (fn [db [_ project-name]]
   (POST "/project/remove-user"
         {:params {:project-name project-name}
          :handler (remove-user-handler project-name)
          :error-handler error-handler})
   db))

(defn kick-user-handler [project-name username]
  (fn []
    (re-frame/dispatch
     [:notify {:message (str "Succesfully removed " username " from project " project-name)}])
    (re-frame/dispatch
     [:remove-project-user {:username username :project-name project-name}])))

(re-frame/register-handler
 :project-kick-user
 (fn [db [_ project-name username]]
   (POST "/project/kick-user"
         {:params {:project-name project-name :username username}
          :handler (kick-user-handler project-name username)
          :error-handler error-handler})
   db))

(defn parse-remove-project-payload [payload]
  (if (empty? payload)
    :project-removed
    :added-project-remove-agree))

(defn remove-project-handler [{project-name :name :as project}]
  (fn [{:keys [id] :as delete-issue}]
    (case (parse-remove-project-payload delete-issue)
      :project-removed
      (do (re-frame/dispatch [:remove-project project-name])
          (re-frame/dispatch [:notify {:message (str "Project " project-name " was deleted")}])
          (nav! "/"))
      :added-project-remove-agree
      (let [updated-project (update project :issues assoc id delete-issue)
            {:keys [pending-users]} (get-pending-users updated-project)] ;still users
        (re-frame/dispatch [:update-project-issue project-name delete-issue])
        (re-frame/dispatch
         [:notify {:message (str (count pending-users) " users pending to remove project")}]))
      (throw (js/Error. "Couldn't parse remove-project payload")))))

(re-frame/register-handler
 :project-remove
 (fn [db [_ {:keys [project-name]}]]
   (POST "/project/remove-project"
         {:params {:project-name project-name}
          :handler (remove-project-handler (get-in db [:projects project-name]))
          :error-handler error-handler})
   db))

(re-frame/register-handler
 :update-project-user-role
 standard-middleware
 (fn [db [_ project-name username new-role]]
   (let [pred #(= username (:username %))]
     (update-in db [:projects project-name :users] update-coll pred assoc :role new-role))))

(defn handle-new-user-role [project-name]
  (fn [{:keys [username role]}]
    ;; refresh project events
    (re-frame/dispatch
     [:notify {:message (format "Succesfully updated %s's role to \"%s\"" username role)}])
    (re-frame/dispatch [:update-project-user-role project-name username role])))

(re-frame/register-handler
 :user-role-update
 (fn [db [_ {:keys [username new-role]}]]
   (let [project-name (get-in db [:session :active-project])]
     (POST "/project/update-user-role"
           {:params {:project-name project-name
                     :username username
                     :new-role new-role}
            :handler (handle-new-user-role project-name)
            :error-handler error-handler})
     db)))

;;; Query metadata
(re-frame/register-handler
 :new-query-metadata
 standard-middleware
 (fn [db [_ {{:keys [id] :as query-metadata} :query project-name :project-name}]]
   (assoc-in db [:projects project-name :queries id] query-metadata)))

(re-frame/register-handler
 :update-query-metadata
 standard-middleware
 (fn [db [_ {project-name :project-name query-id :query-id {:keys [hit-id] :as query-hit} :query-hit}]]
   (update-in db [:projects project-name :queries query-id :hits] assoc hit-id query-hit)))

(re-frame/register-handler
 :add-query-annotation
 standard-middleware
 (fn [db [_ project-name query-id hits]]
   (update-in db [:projects project-name :queries query-id :hits] deep-merge hits)))

(defn fetch-query-annotation-handler [project-name query-id]
  (fn [hits-by-id-map]
    (re-frame/dispatch [:add-query-annotation project-name query-id hits-by-id-map])))

(re-frame/register-handler
 :fetch-query-annotation
 standard-middleware
 (fn [db [_ {:keys [project-name query-id hit-id]}]]
   (GET "/project/queries/fetch"
        {:params (cond-> {:project-name project-name :id query-id} hit-id (assoc :hit-id hit-id))
         :handler (fetch-query-annotation-handler project-name query-id)
         :error-handler #(timbre/error "Error while fetching annotation query")})
   db))

(defn query-new-metadata-handler [project-name]
  (fn [{:keys [id] :as query-metadata}]
    (re-frame/dispatch [:set-active-query id])
    (re-frame/dispatch [:new-query-metadata {:query query-metadata :project-name project-name}])))

(defn query-new-metadata-error-handler [{{:keys [message code data]} :response}]
  (re-frame/dispatch [:notify {:message message}]))

(re-frame/register-handler
 :query-new-metadata
 (fn [db [_ {:keys [id include-sort-opts? include-filter-opts? default description]}
          & {:keys [on-dispatch]}]]
   (let [active-project (get-in db [:session :active-project])
         {:keys [corpus filter-opts sort-opts]} (get-in db [:settings :query])
         {query-str :query-str} (current-results db)]
     (POST "/project/queries/new"
           {:params (cond-> {:project-name active-project
                             :id id
                             :description description
                             :query-data (cond-> {:query-str query-str :corpus corpus}
                                           include-sort-opts?   (assoc :sort-opts sort-opts)
                                           include-filter-opts? (assoc :filter-opts filter-opts))}
                      default (assoc :default default))
            :handler (query-new-metadata-handler active-project)
            :error-handler query-new-metadata-error-handler}))
   (when on-dispatch (on-dispatch))
   db))

(defn get-new-status [default previous-hit-status]
  (get-in {"unseen" {"unseen" "discarded"
                     "discarded" "kept"
                     "kept" "unseen"}
           "discarded" {"discarded" "kept"
                        "kept" "discarded"}
           "kept" {"discarded" "kept"
                   "kept" "discarded"}}
          [default previous-hit-status]))

(defn query-update-metadata-handler [project-name query-id]
  (fn [{:keys [hit-id status _version timestamp by] :as query-hit}]
    ;; unlock event
    (re-frame/dispatch [:stop-throbbing :query-update-metadata])
    (re-frame/dispatch
     [:update-query-metadata
      {:project-name project-name :query-id query-id :query-hit query-hit}])))

(defn query-update-metadata-error-handler [project-name query-id hit-id]
  (fn [{{:keys [message code]} :response}]
    ;; unlock event
    (re-frame/dispatch [:stop-throbbing :query-update-metadata])
    (re-frame/dispatch [:notify {:message message}])
    (case code
      :version-mismatch (re-frame/dispatch
                         [:fetch-query-annotation
                          {:project-name project-name query-id query-id :hit-id hit-id}])
      (re-frame/dispatch [:notify {:message (str "Unknown internal error " message)}]))))

(re-frame/register-handler
 :query-update-metadata
 (fn [db [_ hit-id previous-hit-status]]
   (if-let [_ (get-in db [:session :throbbing? :query-update-metadata])]
     ;; don't trigger (event is locked/running)
     db
     (let [project-name (get-in db [:session :active-project])
           query-id (get-in db [:projects project-name :session :components :active-query])
           {:keys [default]} (get-in db [:projects project-name :queries query-id])
           version (get-in db [:projects project-name :queries query-id :hits hit-id :_version])
           new-status (get-new-status default previous-hit-status)]
       (POST "/project/queries/update"
             {:params {:project-name project-name
                       :id query-id
                       :hit-id hit-id
                       :version version
                       :status new-status}
              :handler (query-update-metadata-handler project-name query-id)
              :error-handler (query-update-metadata-error-handler project-name query-id hit-id)})
       ;; unlock event
       (assoc-in db [:session :throbbing? :query-update-metadata] true)))))

(re-frame/register-handler
 :drop-query-metadata
 standard-middleware
 (fn [db [_ {:keys [id project-name]}]]
   (let [active-query (get-in db [:projects project-name :session :components :active-query])]
     (cond-> db
       ;; unset active-query if it's dropped query
       (= active-query id)
       (update-in [:projects project-name :session :components] dissoc :active-query)
       ;; remove query from db
       true (update-in [:projects project-name :queries] dissoc id)))))

(re-frame/register-handler
 :query-drop-metadata
 (fn [db [_ query-id]]
   (let [active-project (get-in db [:session :active-project])]
     (POST "/project/queries/drop"
           {:params {:project-name active-project :id query-id}
            :handler #(re-frame/dispatch
                       [:drop-query-metadata {:id query-id :project-name active-project}])
            :error-handler #(timbre/error "Error when droping query metadata")})
     db)))

(defn reset-query-str-input [new-query-str]
  (set! (.-value (.getElementById js/document "query-str")) new-query-str))

(re-frame/register-handler
 :launch-query-from-metadata
 (fn [db [_ query-id]]
   (let [active-project (get-in db [:session :active-project])
         query (get-in db [:projects active-project :queries query-id])]
     ;; ask for hits if query is launched for the first time
     (when-not (:hits query)
       (re-frame/dispatch [:fetch-query-annotation {:project-name active-project :query-id query-id}]))
     (if-let [{{:keys [query-str filter-opts sort-opts corpus]} :query-data} query]
       (do (reset-query-str-input query-str)
           (re-frame/dispatch [:query query-str :set-active query-id])
           (cond-> db
             sort-opts (assoc-in [:settings :query :sort-opts] sort-opts)
             filter-opts (assoc-in [:settings :query :filter-opts] filter-opts)
             true (assoc-in [:settings :query :corpus] corpus)))
       db))))
