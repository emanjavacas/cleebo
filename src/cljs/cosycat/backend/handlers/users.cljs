(ns cosycat.backend.handlers.users
  (:require [re-frame.core :as re-frame]
            [ajax.core :refer [GET POST]]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [cosycat.backend.middleware :refer [standard-middleware]]
            [cosycat.utils :refer [format current-results]]
            [cosycat.app-utils :refer [update-coll deep-merge dekeyword]]))

(re-frame/register-handler              ;set client user info
 :set-user
 standard-middleware
 (fn [db [_ path value]]
   (assoc-in db (into [:me] path) value)))

(re-frame/register-handler
 :update-user
 standard-middleware
 (fn [db [_ update-map]]
   (update db :me deep-merge update-map)))

(defn set-users
  [db [name path value]]
  (if-let [user (get-in db [:users name])]
    (assoc-in db (into [:users name] path) value)
    db))

(defn update-users
  [db name update-map]
  (if-let [user (get-in db [:users name])]
    (update-in db [:users name] deep-merge update-map)
    db))

(re-frame/register-handler              ;set other users info
 :set-users
 standard-middleware
 (fn [db [_ name path value]]
   (set-users db [name path value])))

(re-frame/register-handler
 :update-users
 standard-middleware
 (fn [db [_ name update-map]]
   (update-users db name update-map)))

(re-frame/register-handler              ;add user to client (after new signup)
 :add-user
 standard-middleware
 (fn [db [_ {:keys [username] :as user}]]
   (let [user (update user :roles (partial apply hash-set))]
     (assoc-in db [:users username] user))))

(re-frame/register-handler
 :new-user-avatar
 (fn [db [_ {:keys [username avatar]}]]
   (set-users db [username [:avatar] avatar])))

(re-frame/register-handler
 :update-user-active
 standard-middleware
 (fn [db [_ username status]]
   (set-users db [username [:active] status])))

(re-frame/register-handler
 :fetch-user-info
 (fn [db [_ username]]
   (GET "users/user-info"
        {:params {:username username}
         :handler #(re-frame/dispatch [:add-user %])
         :error-handler #(timbre/error "Couldn't fetch user")})
   db))

(defn avatar-error-handler [& args]
  (re-frame/dispatch [:notify {:message "Couldn't update avatar" :status "danger"}]))

(re-frame/register-handler
 :regenerate-avatar
 (fn [db _]
   (POST "users/new-avatar"
         {:params {}
          :handler #(re-frame/dispatch [:set-user [:avatar] %])
          :error-handler avatar-error-handler})
   db))

(defn update-user-profile-error-handler
  [{{:keys [code data]} :response}]
  (case code
    :user-exists (let [msg (format "%s already exists" (-> data dekeyword str/capitalize))]
                   (re-frame/dispatch [:notify {:message msg :status "danger"}]))
    (re-frame/dispatch
     [:notify {:message "Couldn't update profile" :status "danger"}])))

(re-frame/register-handler
 :update-user-profile
 (fn [db [_ update-map]]
   (POST "users/update-profile"
         {:params {:update-map update-map}
          :handler #(re-frame/dispatch [:update-user %])
          :error-handler update-user-profile-error-handler})
   db))

(re-frame/register-handler
 :query-users
 (fn [db [_ value users-atom & {:keys [remove-project-users] :or {remove-project-users true}}]]
   (let [active-project (get-in db [:session :active-project])
         project-users (if-not remove-project-users
                         []
                         (get-in db [:projects active-project :users]))]
     (GET "users/query-users"
          {:params {:value value :project-users project-users}
           :handler #(reset! users-atom %)
           :error-handler #(.log js/console "Error" %)}))
   db))
