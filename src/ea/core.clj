(ns ea.core
  (:gen-class)
  (:require
   [ea.ea :as ea]
   [ea.ds :as ds]
   [ea.xtdb :as db]))

(defn -main [& args]
  (db/init)
  (ds/-main "btcusdt")
  #_(ea/-main args))

;; GOOGLE_APPLICATION_CREDENTIALS=gcp.json clj -M -m ea.core