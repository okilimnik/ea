(ns ea.ds
  (:require
   [clojure.java.io :as io]
   [ea.async :refer [vthread]]
   [ea.binance :as binance :refer [jread]]
   [ea.db :as db]
   [ea.gcloud :as gcloud])
  (:import
   [com.binance.connector.client.utils.websocketcallback WebSocketMessageCallback]
   [java.util ArrayList List]))

(defn start-file-uploader! []
  (vthread
   (loop []
     (Thread/sleep (* 1000 60 60))
     (gcloud/upload-file! (io/file db/file))
     (recur))))

(defn start-prices-stream! [symbol!]
  (let [depth-stream (str symbol! "@depth5")
        ;; otherwise it causes problems in native build
        streams (ArrayList. (List/of depth-stream))]
    (binance/subscribe streams
                       (reify WebSocketMessageCallback
                         ^void (onMessage [_ event-str]
                                          (try
                                            (let [event (jread event-str)
                                                  data (:data event)]
                                              (when (= (:stream event) depth-stream)
                                                (db/insert! data)))
                                            (catch Exception e (prn e))))))))

(defn -main [& args]
  (println "symbol: " (first args))
  (start-file-uploader!)
  (start-prices-stream! (first args)))

;; clj -M -m ea.dataset btcusdt