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
                sut/GENERATIONS 100
                sut/TIMEFRAME->GENE {300 {:index 0
                                          :price-max-change 5
                                          :price-change-divider 1}
                                     900 {:index 1
                                          :price-max-change 5
                                          :price-change-divider 1}}
                sut/CONCURRENCY 1]
    (sut/start-algorithm!)))

;; clj -X:test