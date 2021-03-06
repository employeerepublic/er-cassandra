(ns er-cassandra.model.alia.minimal-change
  (:require
   [clojure.set :as set]
   [prpr.promise :as pr]
   [er-cassandra.model.types :as t]
   [er-cassandra.model.alia.lookup :as lookup]
   [taoensso.timbre :refer [info warn]])
  (:import
   [er_cassandra.model.types Entity]))

(defn change-cols
  "returns non-key columns which have changes between
   old-record and record"
  [key-col-set
   old-record
   record]
  (let [record-col-set (->> record keys set)
        other-col-set (set/difference
                       record-col-set
                       key-col-set)]

    (->> other-col-set
         (filter
          #(not= (get old-record %) (get record %))))))

(defn minimal-change-for-table
  "return a minimal change record for a table - removing columns
   that do not need to be written. if nil is returned then nothing
   needs to be written.
   - if there are changes it contains the key cols and changed cols
   - if there are no changes and there was an old record it is nil
   - if there are no changes and there was no old record it contains
     just the key cols"
  [{t-k :key
    :as table}
   old-record
   record]
  (let [key-col-set (->> t-k flatten set)
        ch-cols (change-cols key-col-set old-record record)]

    (if (and (not-empty old-record)
             (empty? ch-cols))
      nil
      (select-keys record (concat key-col-set ch-cols)))))

(defn avoid-tombstone-change-for-table
  [{t-k :key
    :as table}
   old-record
   record]
  (let [;; find columns which will create tombstones
        tombstone-cols (->> (keys record)
                            (filter
                             (fn [k]
                               (let [ov (get old-record k)
                                     nv (get record k)]
                                 (or
                                  ;; nil -> nil
                                  (and (nil? ov) (nil? nv))

                                  ;; unmodified collection
                                  (and (coll? ov)
                                       (coll? nv)
                                       (= ov nv)))))))]
    (apply dissoc record tombstone-cols)))


;; TODO
;; change the secondary and lookup maintenance to not use
;; minimal-change-for-table... instead to always write
;; and use avoid-tombstone-change-for-table
