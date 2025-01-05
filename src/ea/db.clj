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
       "last_update_id INTEGER, "
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

(defn insert! [docs]
  )

(defn -main [& args]
  (init)
  (let [ask-id (random-uuid)
        bid-id (random-uuid)
        cmd1 {:insert-into [:price_levels]
              :values [{:id ask-id
                        :price 1.0
                        :volume 2.0}
                       {:id bid-id
                        :price 1.0
                        :volume 2.0}]}
        cmd2 {:insert-into [:order_books]
              :values [{:id (random-uuid)
                        :last_update_id 2
                        :bid_0 bid-id
                        :ask_0 ask-id
                        :timestamp (now)}]}]
    (jdbc/execute! @db (sql/format cmd1))
    (jdbc/execute! @db (sql/format cmd2)))

  (prn (jdbc/execute! @db (sql/format {:select [:*]
                                       :from   [:order_books]
                                       :where  [:= :last_update_id 2]})))
  (System/exit 0))

;; clj -M -m ea.db