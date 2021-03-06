(ns er-cassandra.model.alia.relationship
  (:require
   [clojure.set :as set]
   [cats.core :refer [mlet return >>=]]
   [cats.context :refer [with-context]]
   [cats.labs.manifold :refer [deferred-context]]
   [manifold.deferred :as d]
   [manifold.stream :as st]
   [clj-uuid :as uuid]
   [schema.core :as s]
   [er-cassandra.session]
   [er-cassandra.key :as k]
   [er-cassandra.record :as r]
   [er-cassandra.record.schema :as rs]
   [er-cassandra.util.stream :as stu]
   [er-cassandra.model :as m]
   [er-cassandra.model.types :as t]
   [er-cassandra.model.error :as e]
   [er-cassandra.model.alia.fn-schema :as fns]
   [er-cassandra.model.util :refer [combine-responses create-lookup-record]]
   [taoensso.timbre :refer [info warn]]
   [prpr.promise :as pr :refer [ddo]])
  (:import
   [er_cassandra.model.types Entity]
   [er_cassandra.model.model_session ModelSession]))

(s/defschema DenormalizeOp
  (s/enum :upsert :delete))

(defn- deref-target-entity
  "given a namespace qualififed symbol or keyword referring to a
   var with an Entity, return the Entity... otherwise return whatever
   is given"
  [entity-var-ref]
  (if (or (keyword? entity-var-ref)
          (symbol? entity-var-ref))
    (deref
     (ns-resolve (namespace entity-var-ref) (name entity-var-ref)))

    entity-var-ref))

(s/defn foreign-key-val
  "returns [fk fk-val] for the denorm relationship"
  [source-entity :- Entity
   source-record :- t/RecordSchema
   denorm-rel :- t/DenormalizationRelationshipSchema]
  (let [uk-val (t/extract-uber-key-value source-entity source-record)]
    [(:foreign-key denorm-rel) uk-val]))

(s/defn extract-denorm-vals
  "extract the values to be denormalized from a source-record,
   according to the given denorm-rel"
  [denorm-rel source-record]
  (->> (:denormalize denorm-rel)
       (map (fn [[tcol scol-or-fn]]
              [tcol (scol-or-fn source-record)]))
       (into {})))

(s/defn denormalize-fields
  "denormalize fields from source-record according to :denormalize
   of denorm-rel, returning an updated target-record"
  [source-entity :- Entity
   target-entity :- Entity
   denorm-rel :- t/DenormalizationRelationshipSchema
   source-record :- t/RecordSchema
   target-record :- t/RecordSchema]
  (let [target-uberkey (-> target-entity :primary-table :key flatten)
        target-uberkey-value (t/extract-uber-key-value
                              target-entity
                              target-record)
        target-uberkey-map (into {} (map vector
                                         target-uberkey
                                         target-uberkey-value))

        [fk fk-val] (foreign-key-val source-entity source-record denorm-rel)
        fk-map (into {} (map vector fk fk-val))

        denorm-vals (extract-denorm-vals denorm-rel
                                         source-record)]

    ;; the merge ensures that a bad denorm spec can't
    ;; change the PK/FK of the target
    (merge target-record
           denorm-vals
           fk-map
           target-uberkey-map)))

(s/defn matching-rels
  "given a source-record and a target-record and their entities, return
   any denorm-rels from the source-entity which are applicable to the pair,
   by matching the target-entity and the foreign keys"
  [source-entity :- Entity
   target-entity :- Entity
   source-record :- t/RecordSchema
   target-record :- t/RecordSchema]
  (->> (for [[rel-kw {rel-target-ref :target :as rel}]
             (:denorm-targets source-entity)]
         (let [rel-target-entity (deref-target-entity rel-target-ref)]
           (assoc rel :target rel-target-entity)))

       (filter (fn [{rel-target :target}]
                 (= rel-target target-entity)))

       (filter (fn [rel]
                 (let [[fk fk-val] (foreign-key-val source-entity
                                                    source-record
                                                    rel)]
                   (= ((apply juxt fk) target-record)
                      fk-val))))))

(s/defn denormalize-to-target-record
  "denormalize to a single target record"
  [session :- ModelSession
   source-entity :- Entity
   target-entity :- Entity
   old-source-record :- t/MaybeRecordSchema
   source-record :- t/MaybeRecordSchema
   denorm-rel :- t/DenormalizationRelationshipSchema
   target-record :- t/RecordSchema
   opts :- fns/DenormalizeOptsSchema]
  (assert (or old-source-record source-record))
  (let [denorm-op (cond
                    (nil? source-record) :delete
                    :else :upsert)

        target-uberkey (-> target-entity :primary-table :key flatten)
        target-uberkey-value (t/extract-uber-key-value
                              target-entity
                              target-record)
        target-uberkey-map (into {} (map vector
                                         target-uberkey
                                         target-uberkey-value))

        [fk fk-val :as fk-vals] (foreign-key-val
                                 source-entity
                                 (or source-record old-source-record)
                                 denorm-rel)
        fk-map (into {} (map vector fk fk-val))

        denorm-vals (->> (:denormalize denorm-rel)
                         (map (fn [[tcol scol-or-fn]]
                                [tcol (scol-or-fn source-record)]))
                         (into {}))]
    ;; (warn "denorm-op" denorm-op)
    (case denorm-op

      ;; we only upsert the uberkey, fk and denormalized cols
      :upsert
      (let [new-target-record (merge denorm-vals
                                     fk-map
                                     target-uberkey-map)
            otr (select-keys
                 target-record
                 (keys new-target-record))]
        ;; change only changes if necessary
        (m/change session
                  target-entity
                  otr
                  new-target-record
                  (fns/denormalize-opts->upsert-opts opts)))

      :delete
      (let [cascade (:cascade denorm-rel)]
        (case cascade

          :none
          (return deferred-context true)

          ;; we only upsert the uberkey, fk and denormalized cols
          :null
          (let [null-denorm-vals (->> denorm-vals
                                      (map (fn [[k v]] [k nil]))
                                      (into {}))
                new-target-record (merge null-denorm-vals
                                         fk-map
                                         target-uberkey-map)
                otr (select-keys
                     target-record
                     (keys new-target-record))]
            (m/change session
                      target-entity
                      otr
                      new-target-record
                      (fns/denormalize-opts->upsert-opts opts)))

          :delete
          (m/delete session
                    target-entity
                    (-> target-entity :primary-table :key)
                    target-record
                    (fns/denormalize-opts->delete-opts opts)))))))

(s/defn target-record-stream
  "returns a Deferred<Stream<record>> of target records"
  [session :- ModelSession
   source-entity :- Entity
   target-entity :- Entity
   old-source-record :- t/MaybeRecordSchema
   source-record :- t/MaybeRecordSchema
   denorm-rel :- t/DenormalizationRelationshipSchema
   opts :- rs/SelectBufferedOptsSchema]
  (with-context deferred-context
    (mlet [:let [[fk fk-val] (foreign-key-val source-entity
                                              (or source-record
                                                  old-source-record)
                                              denorm-rel)]

          trs (m/select-buffered
               session
               target-entity
               fk
               fk-val
               opts)]

      (return deferred-context trs))))

(defn only-error
  "given a Deferred keep only errors, otherwise returning Deferred<nil>"
  [dv]
  (-> dv
      (d/chain (fn [v] (when (instance? Throwable v) v)))))

(s/defn denormalize-rel
  "denormalizes a single relationship"
  [session :- ModelSession
   source-entity :- Entity
   target-entity :- Entity
   old-source-record :- t/MaybeRecordSchema
   source-record :- t/MaybeRecordSchema
   denorm-rel-kw :- s/Keyword
   denorm-rel :- t/DenormalizationRelationshipSchema
   opts :- fns/DenormalizeOptsSchema]
  ;; don't do anything if we don't need to
  (let [odvs (extract-denorm-vals denorm-rel old-source-record)
        dvs  (extract-denorm-vals denorm-rel source-record)]
    (if (= dvs odvs)
      (return [denorm-rel-kw :noop])

      (with-context deferred-context
        (mlet [trs (target-record-stream session
                                         source-entity
                                         target-entity
                                         old-source-record
                                         source-record
                                         denorm-rel
                                         (select-keys opts [:fetch-size]))

               ;; a (hopefully empty) stream of any errors from denormalization
               :let [denorms (->> trs
                                  (st/buffer (or (:buffer-size opts) 25))
                                  (st/map #(denormalize-to-target-record
                                            session
                                            source-entity
                                            target-entity
                                            old-source-record
                                            source-record
                                            denorm-rel
                                            %
                                            opts)))]

               ;; consumes the whole stream, returns the first error
               ;; or nil if no errors
               maybe-err (stu/keep-stream-error denorms)]

          ;; if there are errors, return the first as an exemplar
          (if (nil? maybe-err)
            (return [denorm-rel-kw [:ok]])
            (return [denorm-rel-kw [:fail maybe-err]])))))))

(s/defn denormalize-fields-to-target
  "if you have a source *and* a target record and you want to denormalize
   any fields that should be denormalised from that source to that target, then
   this is your fn. it will consider all denorm relationships and apply
   denormalize-fields for each relationship where the target entity and
   the source-pk/target-fk values match. only updates in-memory - doesn't
   do any persistence"
  [source-entity :- Entity
   target-entity :- Entity
   source-record :- t/MaybeRecordSchema
   target-record :- t/RecordSchema]
  (let [denorm-rels (matching-rels
                     source-entity
                     target-entity
                     source-record
                     target-record)]
    (reduce (fn [tr rel]
              (denormalize-fields
               source-entity
               target-entity
               rel
               source-record
               tr))
            target-record
            denorm-rels)))

(s/defn denormalize
  "denormalize all relationships for a given source record
   returns Deferred<[[denorm-rel-kw [status maybe-err]]*]>"
  [session :- ModelSession
   source-entity :- Entity
   old-source-record :- t/MaybeRecordSchema
   source-record :- t/MaybeRecordSchema
   {skip-denorm-rels ::t/skip-denormalize
    :as opts} :- fns/DenormalizeOptsSchema]
  (let [opts (dissoc opts ::t/skip-denormalize)
        targets (:denorm-targets source-entity)

          mfs (->> targets
                   (map (fn [[rel-kw rel]]
                          (fn [resps]
                            (if (contains? skip-denorm-rels rel-kw)
                              (return
                               deferred-context
                               (conj resps [rel-kw [:skip]]))
                              (ddo [resp (denormalize-rel
                                          session
                                          source-entity
                                          (deref-target-entity (:target rel))
                                          old-source-record
                                          source-record
                                          rel-kw
                                          rel
                                          opts)]
                                (return (conj resps resp))))))))]

      ;; process one relationship at a time, otherwise the buffer-size is
      ;; uncontrolled
    (apply >>= (return deferred-context []) mfs)))

(s/defn denormalize-callback
  "creates a denormalize callback for :after-save and/or :after-delete"
  ([] (denormalize-callback {}))
  ([denorm-opts :- fns/DenormalizeCallbackOptsSchema]
   (reify
     t/ICallback
     (-after-save [_ session entity old-record record opts]
       (denormalize
        session
        entity
        old-record
        record
        (merge opts denorm-opts)))

     (-after-delete [_ session entity record opts]
       (denormalize
        session
        entity
        record
        nil
        (merge opts denorm-opts))))))
