(ns ea.ea-test 
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest]]
   [ea.db :as db]
   [ea.ea :as sut]))

#_(with-open [rdr (io/reader "btcusdt.db")]
  (loop [i 0
         data (line-seq rdr)]
    (when (<= i (+ 86400 900))
      (spit "test.db" (str (first data) "\n") :append true)
      (recur (inc i) (rest data)))))

(deftest algorithm-test
  (with-redefs [sut/POPULATION-SIZE 10
                sut/GENERATIONS 10
                sut/TIMEFRAME->GENE {300 0
                                     900 1}
                sut/STRATEGY-COMPLEXITY 2
                sut/PRICE-MAX-CHANGE 2
                sut/DATASET-LENGTH-IN-HOURS 1
                sut/PRICE-QUEUE-LENGTH 900
                db/file "test.db"]
    (sut/start-algorithm!)))

;; clj -X:test