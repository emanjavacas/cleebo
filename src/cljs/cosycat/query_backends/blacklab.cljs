(ns cosycat.query-backends.blacklab
  (:require [cosycat.query-backends.protocols :as p]))

(defn bl-parse-sort-opts [sort-opts]
  (reduce (fn [acc {:keys [position attribute facet]}]
            (if (= "match" position)
              (assoc acc :criterion position :attribute (or attribute "word"))))
          {}
          sort-opts))

(deftype BlacklabCorpus [corpus-name]
  p/Corpus
  (p/query [this query-str {:keys [context from page-size] :as query-opts}]
    (p/handle-query
     this "/blacklab"
     {:corpus corpus-name
      :query-str (js/encodeURIComponent query-str)
      :context context
      :from from
      :size (+ from page-size)
      :route :query}))

  (p/query-sort [this query-str {:keys [context from page-size]} sort-opts filter-opts]
    (p/handle-query
     this "/blacklab"
     {:corpus corpus-name
      :context context
      :from from
      :size (+ from page-size)
      :route :sort-query
      :sort-map (bl-parse-sort-opts sort-opts)}))

  (p/transform-query-data [corpus data opts] (identity data))

  (p/transform-query-error-data [corpus data] (identity data))

  (p/snippet [this {:keys [snippet-size] :as snippet-map} hit-id dir]
    (p/handle-query
     this "/blacklab"
     {:corpus corpus-name
      :snippet-size snippet-size
      :route :snippet})))

(defn make-blacklab-corpus [{:keys [corpus-name]}]
  (->BlacklabCorpus corpus-name))

;; (def new-corpus (->BlacklabCorpus "mbg-small"))
;; (query new-corpus "\"a\"" {:context 10 :from 0 :page-size 5})
