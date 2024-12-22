(ns hft.xtdb
  (:require [xtdb.api :as xt]
            [clojure.string :as str]))

(def node (atom nil))

(defn init []
  (reset! node (xt/start-node {:xtdb/document-store {:xtdb/module 'xtdb.google.cloud-storage/->document-store
                                                     :project-id "neusa-a919b",
                                                     :bucket "neusa-datasets",
                                                     :prefix "xtdb/"}
                               :xtdb/index-store {:kv-store {:checkpointer {:store {:xtdb/module 'xtdb.google.cloud-storage/->checkpoint-store
                                                                                    :project-id "neusa-a919b",
                                                                                    :bucket "neusa-datasets",
                                                                                    :prefix "xtdb-indices/"}}}}})))

(defn gen-id [& parts]
  (str/join "-" (concat parts [(System/currentTimeMillis)])))

(defn put! [& docs]
  (when-not @node (init))
  (xt/submit-tx @node (for [doc docs]
                        [:xtdb.api/put doc]))
  (xt/sync @node))

(defn q [q & args]
  (when-not @node (init))
  (apply (partial xt/q (xt/db @node) q) args))