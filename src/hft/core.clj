(ns hft.core
  (:gen-class)
  (:require
   [hft.ea :as ea]
   [hft.xtdb :as db]))

(defn -main [& args]
  (db/init)
  (ea/-main args))

;; GOOGLE_APPLICATION_CREDENTIALS=gcp.json clj -M -m hft.core