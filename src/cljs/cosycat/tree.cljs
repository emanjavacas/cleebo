(ns cosycat.tree
  (:require [reagent.core :as reagent]))

(def label-style
  {:padding "0px 2px"
   :font-size "80%"
   :color "#c7254e"
   :max-height "5px"
   :margin-left "15px"
   :background-color "#f9f2f4"
   :font-family "Menlo,Monaco,Consolas,\"Courier New\",monospace"
   :border-radius "3px"})

(def nil-label-style
  (merge label-style {:color "1DC48D" :background-color "B4E7D6"}))

(defn child-label [data]
  (cond
    (nil? data)   [:label {:style nil-label-style} "null"]
    (empty? (str data)) [:label {:style label-style} "\"\""]
    :else [:label {:style label-style} (str data)]))

(defn is-last-child [children]
  (= :span (first children)))

(defn style [depth] {:margin-left (str (+ 10 depth) "px") :padding "2px 5px"})

(defn i [k v depth children & {:keys [tag init-open] :or {tag :div init-open true}}]
  (let [open (reagent/atom init-open)]
    (fn [k v depth children & {:keys [tag] :or {tag :div}}]
      [tag {:style (merge (style depth))}
       [:span ;triangle
        (if-not (is-last-child children)
          [:i.glyphicon
           {:class (if @open "glyphicon-triangle-bottom" "glyphicon-triangle-right")
            :on-click #(do (.stopPropagation %)  (swap! open not))
            :style {:cursor "pointer"}}]
          [:i.glyphicon
           {:class "glyphicon-chevron-right"
            :style {:margin-left "-1px" :padding-right "2px"}}])
        (str k)
        (cond @open                    children
              (is-last-child children) children
              (not @open)              [:span "..."])]])))

(defn recursive* [data depth init-open]
  (cond (map? data)                     ;map
        (into [:div {:style (merge (style depth) {})}]
              (mapv (fn [[k v]]
                      ^{:key (str depth "-" k)}
                      [i k v depth (recursive* v (inc depth))
                       :tag :div :init-open init-open])
                    data))
        (coll? data)              ;sequential
        (into [:ul {:style (merge (style depth) {:list-style-type "none"})}]
              (mapv (fn [[k v]]
                      ^{:key (str depth "-" k)}
                      [i k v depth (recursive* v (inc depth))
                       :tag :li :init-open init-open])
                    (map-indexed vector data)))
        :else [:span [child-label data]])) ;else

(defn recursive [data init-open] [recursive* data 0 init-open])

(defn data-tree [data & {:keys [init-open] :or {init-open true}}]
  (fn [data]
    [recursive data init-open]))
