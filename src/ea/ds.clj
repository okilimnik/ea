(ns ea.ds
  (:require
   [ea.binance :as binance :refer [jread]]
   [ea.xtdb :as db]) 
  (:import
   [com.binance.connector.client.utils.websocketcallback WebSocketMessageCallback]))

(def cache (atom '()))

(defn start-prices-stream! [symbol!]
  (let [depth-stream (str symbol! "@depth5")]
    (binance/subscribe [depth-stream]
                       (reify WebSocketMessageCallback
                         ^void (onMessage [_ event-str]
                                          (try
                                            (let [event (jread event-str)
                                                  data (:data event)]
                                              (when (= (:stream event) depth-stream)
                                                (swap! cache (fn [old-cache]
                                                               (as-> old-cache %
                                                                 (conj % (merge {:xt/id (db/gen-id [symbol!])
                                                                                 :order-book/last-update-id (:lastUpdateId data)
                                                                                 :order-book/bids (:bids data)
                                                                                 :order-book/asks (:asks data)}))
                                                                 (if (= (count %) 50)
                                                                   (do (db/put! %)
                                                                       '())
                                                                   %))))))
                                            (catch Exception e (prn e))))))))

(defn -main [& args]
  (println "symbol: " (first args))
  (start-prices-stream! (first args)))

;; clj -M -m ea.dataset btcusdt