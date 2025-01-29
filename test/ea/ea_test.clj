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

#_(deftest ranges-test
  (with-redefs [sut/POPULATION-SIZE 1
                sut/GENERATIONS 1
                sut/CONCURRENCY 1]
    (sut/start-algorithm!)))

(deftest algorithm-test
  (with-redefs [sut/POPULATION-SIZE 10
                sut/GENERATIONS 50
                sut/TIMEFRAME->GENE {300 0
                                     900 1}
                sut/DATASET-LENGTH-IN-HOURS 24
                sut/CONCURRENCY 1
                db/file "test.db"]
    (sut/start-algorithm!)))

;; clj -X:test