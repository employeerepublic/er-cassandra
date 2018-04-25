(ns er-cassandra.model.alia.upsert
  (:require
   [cats
    [context :refer [with-context]]
    [core :as monad :refer [mlet return]]
    [data :refer [pair]]]
   [cats.labs.manifold :refer [deferred-context]]
   [clojure.set :as set]
   [clojure.pprint :refer [pprint]]
   [er-cassandra
    [key :as k]
    [record :as r]]
   [er-cassandra.model
    [types :as t]
    [util :as util :refer [combine-responses create-lookup-record]]]
   [er-cassandra.model.alia
    [fn-schema :as fns]
    [lookup :as l]
    [unique-key :as unique-key]]
   [er-cassandra.model.util.timestamp :as ts]
   [er-cassandra.model.alia.delete :as alia.delete]
   [er-cassandra.model.types.change :as t.change]
   [schema.core :as s]
   [prpr.promise :as pr :refer [ddo]]
   [taoensso.timbre :refer [warn error]]
   [prpr.promise :as prpr])
  (:import
   [er_cassandra.model.types Entity]
   [er_cassandra.model.model_session ModelSession]))

(s/defn insert-index-record
  "insert an index record - doesn't support LWTs, :where etc
   which only apply to the primary record

   weirdly, if a record which was created with update is
   later updated with all null non-pk cols then that record will be
   deleted

   https://ajayaa.github.io/cassandra-difference-between-insert-update/

   since this is undesirable for secondary tables, we use insert instead,
   and since we don't want any :where or LWTs they are forbidden by schema"
  [session :- ModelSession
   entity :- Entity
   table :- t/TableSchema
   record :- t/RecordSchema
   opts :- fns/UpsertUsingOnlyOptsWithTimestampSchema]
  (with-context deferred-context
    (mlet [insert-result (r/insert session
                                   (:name table)
                                   record
                                   opts)]
      (return
       [:ok record :upserted]))))

(s/defn upsert-secondaries
  [session :- ModelSession
   entity :- Entity
   old-record :- t/MaybeRecordSchema
   record :- t/MaybeRecordSchema
   opts :- fns/UpsertUsingOnlyOptsWithTimestampSchema]
  (combine-responses
   (for [{k :key
          :as t} (t/mutable-secondary-tables entity)]
     (when (and
            (k/has-key? k record)
            (k/extract-key-value k record)
            ;; decided that secondaries should get upserted even if unchanged
            ;; so that the index-tables are somewhat self-healing
            ;; (not= record old-record)
            )
       (insert-index-record session entity t record opts)))))

(s/defn upsert-lookups-for-table
  [session :- ModelSession
   entity :- Entity
   table :- t/LookupTableSchema
   old-record :- t/MaybeRecordSchema
   record :- t/MaybeRecordSchema
   opts :- fns/UpsertUsingOnlyOptsWithTimestampSchema]
  (with-context deferred-context
    (mlet
      [:let [uber-key (t/uber-key entity)
             uber-key-value (t/extract-uber-key-value entity record)]
       lookup-records (->> (l/generate-lookup-records-for-table
                            session entity table old-record record)
                           combine-responses)
       acquire-responses (->> (for [lr lookup-records]
                                (insert-index-record
                                 session
                                 entity
                                 table
                                 lr
                                 opts))
                              combine-responses)]
      (return acquire-responses))))

(s/defn upsert-lookups
  [session :- ModelSession
   entity :- Entity
   old-record :- t/MaybeRecordSchema
   record :- t/MaybeRecordSchema
   opts :- fns/UpsertUsingOnlyOptsWithTimestampSchema]
  (with-context deferred-context
    (mlet [all-acquire-responses (->> (for [t (t/mutable-lookup-tables entity)]
                                        (upsert-lookups-for-table
                                         session
                                         entity
                                         t
                                         old-record
                                         record
                                         opts))
                                      combine-responses)]
      (return
       (apply concat all-acquire-responses)))))

(s/defn copy-unique-keys
  [entity :- Entity
   from :- t/MaybeRecordSchema
   to :- t/MaybeRecordSchema]
  (let [unique-key-tables (:unique-key-tables entity)]
    (reduce (fn [r t]
              (let [key-col (last (:key t))]
                (assoc r key-col (get from key-col))))
            to
            unique-key-tables)))

(s/defn update-secondaries-and-lookups
  "update non-LWT secondary and lookup entries"

  [session :- ModelSession
   entity :- Entity
   old-record :- t/MaybeRecordSchema
   updated-record-with-keys :- t/MaybeRecordSchema
   opts :- fns/UpsertOptsWithTimestampSchema]
  (with-context deferred-context
    (mlet [:let [index-delete-opts (-> opts
                                       fns/upsert-opts->using-only
                                       fns/upsert-opts->delete-opts)
                 index-insert-opts (-> opts
                                       fns/upsert-opts->using-only)]

           stale-secondary-responses (alia.delete/delete-stale-secondaries
                                      session
                                      entity
                                      old-record
                                      updated-record-with-keys
                                      index-delete-opts)

           stale-lookup-responses (alia.delete/delete-stale-lookups
                                   session
                                   entity
                                   old-record
                                   updated-record-with-keys
                                   index-delete-opts)

           secondary-reponses (upsert-secondaries
                               session
                               entity
                               old-record
                               updated-record-with-keys
                               index-insert-opts)

           lookup-responses (upsert-lookups
                             session
                             entity
                             old-record
                             updated-record-with-keys
                             index-insert-opts)]

      (return updated-record-with-keys))))

(s/defn cassandra-column-name?
  [k]
  (->> k
       name
       (re-matches #"\p{Alpha}[_\p{Alnum}]+")
       boolean))

(s/defn ^:deprecated upsert-minimal-changes
  "after callbacks have been run, upsert a minimal change"
  [session :- ModelSession
   entity :- Entity
   old-record :- t/MaybeRecordSchema
   record :- t/RecordSchema
   opts :- fns/UpsertOptsSchema]
  (let [;; remove any columns which would do nothing but
        ;; create tombstones or other garbage
        min-change (t.change/minimal-change
                    entity
                    old-record
                    record)]

    (if min-change
      (ddo [:let [;; keep the removed columns to add back to the response
                  ;; record, since they are valid columns and the
                  ;; general contract is that the response record
                  ;; will resemble the request record excepting
                  ;; necessary changes (such as removal of failed key acquisitions)
                  removed-cols (filter
                                (->> min-change keys set complement)
                                (keys record))
                  removed-record (select-keys record removed-cols)]

            [updated-record-with-keys
             acquire-failures] (unique-key/upsert-primary-record-and-update-unique-keys
                                session
                                entity
                                old-record
                                min-change
                                opts)

            _ (monad/when updated-record-with-keys
                (update-secondaries-and-lookups session
                                                entity
                                                old-record
                                                min-change
                                                opts))

            ;; add columns which weren't needed for the upsert back in to the response
            :let [updated-record-with-keys (merge updated-record-with-keys
                                                  removed-record)]]

        (return
         (pair updated-record-with-keys
               acquire-failures)))

      ;; no upsert required
      (return deferred-context
              (pair record nil)))))


(s/defn upsert-changes*
  "upsert a single instance given the previous value of the instance. if the
   previous value is nil then it's an insert. if the new value is nil then
   it's a delete. otherwise key changes will be computed using the old-record
   and without requiring any select

   returns a Deferred<Pair[record key-failures]> where key-failures describes
   unique keys which were requested but could not be acquired"
  [session :- ModelSession
   entity :- Entity
   old-record :- t/MaybeRecordSchema
   record :- t/RecordSchema
   opts :- fns/UpsertOptsSchema]

  (assert (or (nil? old-record)
              (= (t/extract-uber-key-value entity old-record)
                 (t/extract-uber-key-value entity record))))

  (ddo [:let [opts (ts/default-timestamp-opt opts)

              ;; separate the tru cassandra columns from non-cassandra
              ;; columns which will be removed by callbacks
              {cassandra-cols true
               non-cassandra-cols false} (->> record
                                              keys
                                              (group-by cassandra-column-name?))
              non-cassandra-record (select-keys record non-cassandra-cols)

              old-record (not-empty old-record)]

        old-record-ser (when old-record
                         (t/run-save-callbacks
                          session
                          entity
                          :serialize
                          old-record
                          old-record
                          opts))

        record-ser (t/chain-save-callbacks
                    session
                    entity
                    [:before-save :serialize]
                    old-record
                    record
                    opts)

        :let [record-keys (-> record keys set)
              record-ser-keys (-> record-ser keys set)
              removed-keys (set/difference record-keys record-ser-keys)

              ;; if the op is an insert, then old-record will be nil,
              ;; and we will need to return nil values for any removed keys
              ;; to preserve schema
              nil-removed (->> removed-keys
                               (filter cassandra-column-name?)
                               (map (fn [k]
                                      [k nil]))
                               (into {}))]

        [updated-record-with-keys-ser
         acquire-failures] (unique-key/upsert-primary-record-and-update-unique-keys
                            session
                            entity
                            old-record-ser
                            record-ser
                            opts)

        _ (monad/when updated-record-with-keys-ser
            (update-secondaries-and-lookups session
                                            entity
                                            old-record-ser
                                            updated-record-with-keys-ser
                                            opts))

        ;; [updated-record-with-keys-ser
        ;;  acquire-failures] (upsert-minimal-changes
        ;;                     session
        ;;                     entity
        ;;                     old-record-ser
        ;;                     record-ser
        ;;                     opts)

        ;; construct the response and deserialise
        response-record-raw (merge nil-removed
                                   old-record
                                   updated-record-with-keys-ser)

        response-record (t/chain-callbacks
                         session
                         entity
                         [:deserialize :after-load]
                         response-record-raw
                         opts)

        _ (t/run-save-callbacks
           session
           entity
           :after-save
           old-record
           response-record
           opts)]

    (return
     (pair response-record
           acquire-failures))))

(s/defn upsert*
  "upsert a single instance

   convenience fn - if the entity has any maintained foreign keys it borks"
  [session :- ModelSession
   entity :- Entity
   record :- t/MaybeRecordSchema
   opts :- fns/UpsertOptsSchema]
  (ddo [:let [has-maintained-foreign-keys?
              (not-empty
               (t/all-maintained-foreign-key-cols entity))]

        _ (monad/when has-maintained-foreign-keys?
            (throw
             (pr/error-ex
              :upsert/require-explicit-select-upsert
              {:message (str "this entity has foreign keys, "
                             "so requires the previous version "
                             "to upsert. either use select-upsert "
                             "or change")
               :entity (with-out-str (pprint entity))
               :record record
               :opts opts})))]

    (upsert-changes*
     session
     entity
     nil
     record
     opts)))

(s/defn select-upsert*
  "upsert a single instance

   convenience fn - if the entity has any maintained foreign keys it first
                    selects the instance from the db,
                    then calls upsert-changes*"
  [session :- ModelSession
   entity :- Entity
   record :- t/MaybeRecordSchema
   opts :- fns/UpsertOptsSchema]
  (ddo [:let [has-maintained-foreign-keys?
              (not-empty
               (t/all-maintained-foreign-key-cols entity))]

        ;; need to run :before-save on the record in case
        ;; it defaults something in the uberkey
        record-ser (t/run-save-callbacks
                    session
                    entity
                    :before-save
                    record
                    record
                    opts)

        raw-old-record (r/select-one
                        session
                        (get-in entity [:primary-table :name])
                        (get-in entity [:primary-table :key])
                        (t/extract-uber-key-value
                         entity
                         record-ser))

        old-record (monad/when raw-old-record
                     (t/chain-callbacks
                      session
                      entity
                      [:deserialize :after-load]
                      raw-old-record
                      opts))]

    (upsert-changes*
     session
     entity
     old-record
     record
     opts)))

(s/defn change*
  "change a single instance.
   if old-record and record are identical - it's a no-op,
   if record is nil - it's a delete,
   otherwise it's an upsert-changes*

   returns Deferred<[record]>"
  [session :- ModelSession
   entity :- Entity
   old-record :- t/MaybeRecordSchema
   record :- t/MaybeRecordSchema
   opts :- fns/UpsertOptsSchema]
  (cond
    (nil? record)
    (ddo [dr (alia.delete/delete* session
                                  entity
                                  (t/uber-key entity)
                                  old-record
                                  opts)]
      (return [:delete old-record]))

    (= old-record record)
    (return deferred-context [:noop record])

    :else
    (ddo [[ur acquire-failures] (upsert-changes*
                session
                entity
                old-record
                record
                opts)]
      (return [:upsert ur acquire-failures]))))
