(ns cleebo.query-backends.blacklab-server
  (:require [cleebo.ajax-jsonp :refer [jsonp]]
            [cleebo.query-backends.protocols :as p]
            [cleebo.utils :refer [->int]]))

(defn bl-server-url
  "builds the blacklab server url"
  [server web-service corpus-name & {:keys [resource] :or {resource "hits"}}]
  (str "http://" server "/" web-service "/" corpus-name "/" resource))

(defn join-params [& params]
  (apply str (interpose ":" (filter identity params))))

(defn parse-sort-opts
  "transforms internal app sort data into a single blacklab-server sort string"
  [ms]
  (map (fn [{:keys [position attribute facet]}]
         (if (= position "match")
           (join-params "hit" attribute facet)
           (join-params position attribute facet)))
       ms))

(defn parse-filter-opts
  "transforms internal app filter data into a single blacklab-server filter string"
  [ms]
  (map (fn [{:keys [attribute value]}]
         (join-params attribute value))
       ms))

(defn bl-server-sort-str
  "builds the blacklab sort string from param maps"
  [sort-opts filter-opts]
  (apply str (interpose "," (concat (parse-sort-opts sort-opts) (parse-filter-opts filter-opts)))))

(defn parse-hit-id
  [hit-id]
  (let [[doc-id hit-start hit-end] (clojure.string/split hit-id #"\.")]
    {:doc-id doc-id :hit-start hit-start :hit-end hit-end}))

(defn sub-hit
  [{:keys [punct id word]} & {:keys [is-match?]}]
  (mapv (fn [word id]
          (let [base {:word word :id id}]
            (if is-match?
              (assoc base :match true)
              base)))
        word id))

(defn normalize-meta [idx doc]
  (assoc doc :num idx))

(defn normalize-bl-hit
  [{left :left match :match right :right doc-id :docPid start :start end :end} idx doc]
  {:hit (concat (sub-hit left) (sub-hit match :is-match? true) (sub-hit right))
   :id (apply str (interpose "." [doc-id start end]))
   :meta (normalize-meta idx doc)})

(def bl-default-params
  {:maxcount 100000
   :waitfortotal "yes"})

(deftype BlacklabServerCorpus [corpus-name server web-service]
  p/Corpus
  (p/query [this query-str {:keys [context from page-size] :as query-opts}]
    (p/handle-query
     this (bl-server-url server web-service corpus-name)
     (merge {:patt query-str
             :wordsaroundhit context
             :first from
             :number page-size
             :jsonp "callback"}
            bl-default-params)
     :method jsonp))

  (p/query-sort [this query-str {:keys [context from page-size]} sort-opts filter-opts]
    (let [sort-str (bl-server-sort-str sort-opts filter-opts)]
      (p/handle-query
       this (bl-server-url server web-service corpus-name)
       (merge {:patt (js/encodeURIComponent query-str)
               :wordsaroundhit context
               :first from
               :number page-size
               :sort sort-str
               :jsonp "callback"}
              bl-default-params)
       :method jsonp)))

  (p/snippet [this query-str {:keys [snippet-size] :as query-opts} hit-id]
    (let [{:keys [doc-id hit-start hit-end]} (parse-hit-id hit-id)]
      (p/handle-query
       this (bl-server-url server web-service corpus-name :resource (str "docs/" doc-id "snippet"))
       (merge {:wordsaroundhit snippet-size
               :hitstart hit-start
               :hitend hit-end
               :jsonp "callback"}
              bl-default-params)
       :method jsonp)))
  
  (p/handler-data [corpus data]
    (let [{summary :summary hits :hits doc-infos :docInfos} (js->clj data :keywordize-keys true)
          {{from :first corpus :indexname query-str :patt} :searchParam
           num-hits :numberOfHitsRetrieved query-size :numberOfHits
           query-time :searchTime has-next :windowHasNext} summary
          results-summary {:page {:from (->int from) :to (+ (->int from) num-hits)}
                           :query-size query-size
                           :query-str query-str
                           :query-time query-time
                           :has-next has-next
                           :corpus corpus}
          results (map-indexed
                   (fn [idx {doc-id :docPid :as hit}]
                     (normalize-bl-hit hit (+ idx (->int from)) (get doc-infos (keyword doc-id))))
                   hits)]
      {:results-summary results-summary
       :results results
       :status {:status :ok :content "Hi"}}))
 
  (p/error-handler-data [corpus data]
    (identity data)))

(def corpus (BlacklabServerCorpus. "mbg-index-small" "localhost:8080" "blacklab-server-1.3.4"))
(p/query corpus "\"is\" \".*d\" \"at\"" {:context 5 :from 0 :page-size 5})

