(ns hft.core
  (:gen-class)
  (:require [clojure.tools.cli :refer [parse-opts]]
            [hft.dataset :refer [pipeline]]
            [hft.model.lightgbm :as lightgbm]
            [hft.xtdb :as db]
            [hft.ea]))

(def cli-options
  [["-t" nil "Train"
    :id :train]
   ["-i" nil "UI"
    :id :ui]
   ;; A non-idempotent option (:default is applied first)
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc] ; Prior to 0.4.1, you would have to use:
                   ;; :assoc-fn (fn [m k _] (update-in m [k] inc))
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])

(defn -main [& args]
  (db/init)
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        market-state (or (System/getenv "MARKET_STATE") "range")]

    (cond
      (:train options) (do (lightgbm/train!)
                           (System/exit 0))
      :else
      (pipeline {}))))

;; GOOGLE_APPLICATION_CREDENTIALS=gcp.json clj -M -m hft.core