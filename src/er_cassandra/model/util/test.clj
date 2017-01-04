(ns er-cassandra.model.util.test
  (:require
   [clojure.test :as t]
   [taoensso.timbre :refer [trace debug info warn error]]
   [deferst :refer [defsystem]]
   [deferst.system :as sys]
   [slf4j-timbre.configure :as logconf]
   [er-cassandra.record :as r]
   [er-cassandra.session :as s]
   [er-cassandra.model :as m]
   [er-cassandra.model.model-session :as ms]
   [er-cassandra.model.alia.model-session :as ams]))

(def ^:dynamic *model-session* nil)

(def alia-test-model-session-config
  {:timbre {:level :warn}
   :config {:alia-session
            {:keyspace "er_cassandra_test"}}})

(def alia-test-model-session-system-def
  [[:logging logconf/configure-timbre [:timbre]]
   [:cassandra ams/create-test-session [:config :alia-session]]])

(defn with-model-session-fixture
  []
  (fn [f]

    (let [sb (sys/system-builder alia-test-model-session-system-def)
          sys (sys/start-system! sb alia-test-model-session-config)]
      (try
        (let [system @(sys/system-map sys)]
          (binding [*model-session* (:cassandra system)]
            (f)))
        (finally
          (try
            @(sys/stop-system! sys)
            (catch Exception e
              (error e "error during test stop-system!"))))))))

(defn create-table
  "creates a table for test - drops any existing version of the table first"
  [table-name table-def]
  @(s/execute
    *model-session*
    (str "drop table if exists " (name table-name)))
  @(s/execute
    *model-session*
    (str "create table " (name table-name) " " table-def)))

(defn fetch-record
  [table key key-value]
  @(r/select-one *model-session* table key key-value))

(defn insert-record
  [table record]
  @(r/insert *model-session* table record))

(defn upsert-instance
  [entity record]
  @(m/upsert *model-session* entity record))
