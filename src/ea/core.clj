(ns ea.core
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.tools.cli :refer [parse-opts]]
   [ea.db :as db]
   [ea.ds :as ds]
   [ea.ea :as ea]
   [ea.gcloud :refer [download-file!]]
   [ea.earn :as earn]))

(set! *warn-on-reflection* true)

(def cli-options
  [["-t" nil "Train"
    :id :train]
   ["-d" nil "Collect dataset"
    :id :dataset]
   ["-e" nil "Earn"
    :id :earn]])

(defn -main [& args]
  (let [{:keys [options]} (parse-opts args cli-options)]
    (when-not (.exists (io/file db/file))
      (download-file! db/file))
    (cond
      (:dataset options) (do
                           (prn "collecting data")
                           (ds/-main "btcusdt"))
      (:train options) (do
                         (prn "training")
                         (ea/start-algorithm!))
      (:earn options) (do
                        (prn "earning")
                        (earn/start!))
      :else
      "do nothing"))
  (System/exit 0))

;; GOOGLE_APPLICATION_CREDENTIALS=gcp.json clj -M -m ea.core -e