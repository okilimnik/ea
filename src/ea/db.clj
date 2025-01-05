(ns ea.db
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]))

(def file "btcusdt.db")
(def spec {:dbtype "duckdb" :dbname file})
(def db (atom nil))

(def table-price-levels
  (str "CREATE TABLE IF NOT EXISTS price_levels ("
       "id UUID PRIMARY KEY, "
       "price FLOAT, "
       "volume FLOAT)"))
(def table-order-books
  (str "CREATE TABLE IF NOT EXISTS order_books ("
       "id UUID PRIMARY KEY, "
       "last_update_id BIGINT, "
       "bid_0 UUID, "
       "bid_1 UUID, "
       "bid_2 UUID, "
       "ask_0 UUID, "
       "ask_1 UUID, "
       "ask_2 UUID, "
       "timestamp TIMESTAMP_MS, "
       "FOREIGN KEY (bid_0) REFERENCES price_levels (id), "
       "FOREIGN KEY (bid_1) REFERENCES price_levels (id), "
       "FOREIGN KEY (bid_2) REFERENCES price_levels (id), "
       "FOREIGN KEY (ask_0) REFERENCES price_levels (id), "
       "FOREIGN KEY (ask_1) REFERENCES price_levels (id), "
       "FOREIGN KEY (ask_2) REFERENCES price_levels (id)"
       ")"))

(defn init []
  (reset! db (jdbc/get-datasource spec))
  (jdbc/execute! @db [table-price-levels])
  (jdbc/execute! @db [table-order-books]))

(defn now []
  (java.sql.Timestamp. (System/currentTimeMillis)))

(defn get-price-level [prices level]
  (let [price-level (nth prices level)]
    {:id (random-uuid)
     :price (parse-double (first price-level))
     :volume (parse-double (second price-level))}))

(defn unrwap-price-levels [doc]
  {:bid_0 (get-price-level (:bids doc) 0)
   :bid_1 (get-price-level (:bids doc) 1)
   :bid_2 (get-price-level (:bids doc) 2)
   :ask_0 (get-price-level (:asks doc) 0)
   :ask_1 (get-price-level (:asks doc) 1)
   :ask_2 (get-price-level (:asks doc) 2)})

(defn insert! [doc]
  (when-not @db (init))
  (let [cmds (let [price-levels (unrwap-price-levels doc)]
               [{:insert-into [:price_levels]
                 :values      (vals price-levels)}
                {:insert-into [:order_books]
                 :values [(reduce-kv (fn [m k v] (assoc m k (:id v)))
                                     {:id (random-uuid) :timestamp (now) :last_update_id (:lastUpdateId doc)}
                                     price-levels)]}])]
    (doseq [cmd cmds]
      (jdbc/execute! @db (sql/format cmd)))))

(defn -main [& args]
  (init)
  (let [ask-id (random-uuid)
        bid-id (random-uuid)
        cmds [{:insert-into [:price_levels]
               :values [{:id ask-id
                         :price 1.0
                         :volume 2.0}
                        {:id bid-id
                         :price 1.0
                         :volume 2.0}]}
              {:insert-into [:order_books]
               :values [{:id (random-uuid)
                         :last_update_id 2
                         :bid_0 bid-id
                         :ask_0 ask-id
                         :timestamp (now)}]}]]
    (doseq [cmd cmds]
      (jdbc/execute! @db (sql/format cmd))))

  (prn (jdbc/execute! @db (sql/format {:select [:*]
                                       :from   [:order_books]
                                       :where  [:= :last_update_id 2]})))
  (System/exit 0))

;; clj -M -m ea.db