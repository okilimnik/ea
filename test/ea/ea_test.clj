(ns ea.ea-test 
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest]]
   [ea.db :as db]
   [ea.ea :as bull]
   [ea.ea-bear :as bear]))

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
  (with-redefs [bull/POPULATION-SIZE 50
                bull/GENERATIONS 100
                ;bull/DATASET-LENGTH-IN-HOURS (* 24 2)
                bull/TIMEFRAME->GENE {15 {:index 0 :price-max-change 5 :price-change-divider 0.25}
                                      30 {:index 1 :price-max-change 5 :price-change-divider 0.5}
                                      60 {:index 2 :price-max-change 5 :price-change-divider 0.5}
                                      120 {:index 3 :price-max-change 5 :price-change-divider 0.8}
                                      300 {:index 4 :price-max-change 10  :price-change-divider 1}
                                    ;  900 {:index 1 :price-max-change 5  :price-change-divider 1}
                                     ; 1800 {:index 2  :price-max-change 7 :price-change-divider 1.5}
                                     ; 3600 {:index 3  :price-max-change 7 :price-change-divider 1.5}
                                      ;
                                      }
                bull/CONCURRENCY 5]
    (bull/start-algorithm!)))

#_(deftest bear-algorithm-test
  (with-redefs [bear/POPULATION-SIZE 10
                bear/GENERATIONS 100
                bear/TIMEFRAME->GENE {300 {:index 0
                                          :price-max-change 5
                                          :price-change-divider 1}
                                     900 {:index 1
                                          :price-max-change 5
                                          :price-change-divider 1}
                                     ;1800 {:index 2 :price-max-change 7 :price-change-divider 1.5}
                                     ;3600 {:index 3 :price-max-change 7 :price-change-divider 1.5}
                                      ;
                                      }
                bear/CONCURRENCY 1]
    (bear/start-algorithm!)))

;; clj -X:test