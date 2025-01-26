(ns ea.ea
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ea.db :as db]
   [ea.interop :refer [as-function]]
   [java-time.api :as jt])
  (:import
   [io.jenetics Genotype LongChromosome LongGene]
   [io.jenetics.engine Engine EvolutionResult]
   [java.util.concurrent Executors ThreadPoolExecutor]))

(def CONCURRENCY 5)
(def DATASET-LENGTH-IN-HOURS (* 24 5))
(def DATASET-PRECISION-IN-SEC 60)
(def POPULATION-SIZE 500)
(def GENERATIONS 200)
(def TIMEFRAME->GENE
  {300 0 ;; 5min
   900 1 ;; 15min
   1800 2 ;; 30min
   3600 3 ;; 1h
   14400 4 ;; 4h
   86400 5 ;; 1d
   })
(def STRATEGY-COMPLEXITY (count (keys TIMEFRAME->GENE)))
(def PRICE-MAX-CHANGE 10)
(def INITIAL-BALANCE 1000)
(defn PRICE-QUEUE-LENGTH []
  (/ (apply max (keys TIMEFRAME->GENE)) DATASET-PRECISION-IN-SEC))

(defn price-changes->reality [changes]
  (->> changes
       (#(select-keys % (keys TIMEFRAME->GENE)))
       (into [])
       (map #(list (get TIMEFRAME->GENE (first %)) (second %)))
       (sort-by first)
       (map second)
       vec))

(defn wait-close-possibilty! [price-changes strategy]
  (when (seq price-changes)
    (let [reality (->> price-changes
                       price-changes->reality)
          price (:bid-price price-changes)]
      (when (= strategy reality)
        price))))

(defn wait-open-possibility! [price-changes strategy]
  (when (seq price-changes)
    (let [reality (->> price-changes
                       price-changes->reality)
          price (:ask-price price-changes)]
      (when (= strategy reality)
        price))))

(defn get-price-change-percent [k prices price-queue-length]
  (let [last-price (last prices)
        first-price (nth prices (- price-queue-length (/ k DATASET-PRECISION-IN-SEC)))]
    (int (* 100 (/ (- last-price first-price)
                   first-price)))))

(defn simulate-intraday-trade! [strategy dataset]
  (let [strategy (first strategy)
        [buy-strategy sell-strategy] (vec strategy)
        buy-strategy (vec buy-strategy)
        sell-strategy (vec sell-strategy)
        start-time (jt/local-date-time)
        end-time (jt/plus start-time (jt/hours DATASET-LENGTH-IN-HOURS))
        current-time (atom (jt/local-date-time))
        order (atom nil)
        balance (atom INITIAL-BALANCE)
        number-of-trades (atom 0)
        prices-queue (atom clojure.lang.PersistentQueue/EMPTY)
        price-changes (atom {})
        price-queue-length (PRICE-QUEUE-LENGTH)]
    (loop [lines dataset]
      (let [data (edn/read-string (first lines))
            ready? (jt/after? @current-time end-time)]
        (when (and data (not ready?))
          (swap! prices-queue #(as-> % $
                                 (conj $ (-> data :asks ffirst parse-double))
                                 (if (> (count $) price-queue-length)
                                   (pop $)
                                   $)))
          (when (= (count @prices-queue) price-queue-length)
            (swap! price-changes assoc :ask-price (-> data :asks ffirst parse-double))
            (swap! price-changes assoc :bid-price (-> data :bids ffirst parse-double))
            (doseq [k (keys TIMEFRAME->GENE)]
              (let [price-change-percent (get-price-change-percent k @prices-queue price-queue-length)]
                (swap! price-changes assoc k (cond
                                               (pos? price-change-percent)
                                               (min PRICE-MAX-CHANGE price-change-percent)
                                               (neg? price-change-percent)
                                               (max (- PRICE-MAX-CHANGE) price-change-percent)
                                               :else 0))))
            (if @order
              (let [price (wait-close-possibilty! @price-changes sell-strategy)]
                (when price
                  (swap! number-of-trades inc)
                  (swap! balance + (- price (:price @order)))
                  (reset! order nil)))
              (let [price (wait-open-possibility! @price-changes buy-strategy)]
                (when price
                  (reset! order {:price price})))))
          (swap! current-time jt/plus (jt/seconds DATASET-PRECISION-IN-SEC))
          (recur (drop DATASET-PRECISION-IN-SEC lines)))))
    (Thread/sleep (* 300 (rand-int CONCURRENCY)))
    (println "balance left: " @balance)
    (println "number of trades: " @number-of-trades)
    (println "buy-strategy: " buy-strategy)
    (println "sell-strategy: " sell-strategy)
    (println "reality: " (->> @price-changes
                              price-changes->reality))
    (long (+ (- @balance INITIAL-BALANCE) (if (zero? @number-of-trades)
                                            (- 1000)
                                            @number-of-trades)))))

(defn eval! [dataset gt]
  (let [strategy (->> (range (.length gt))
                      (mapv #(-> (.get gt %)
                                 (.as LongChromosome)
                                 (.toArray)
                                 first))
                      (partition 2)
                      (partition STRATEGY-COMPLEXITY))]
    (simulate-intraday-trade! strategy dataset)))

(defn price-change-chromosome []
  (LongGene/of (- PRICE-MAX-CHANGE) PRICE-MAX-CHANGE))

(defn start-algorithm! []
  (with-open [rdr (io/reader db/file)]
    (let [dataset (line-seq rdr)
          ^ThreadPoolExecutor executor (Executors/newFixedThreadPool CONCURRENCY)
          gtf (Genotype/of (repeat (* 2 STRATEGY-COMPLEXITY) (LongChromosome/of [(price-change-chromosome)]))) ;; 1 strategy to buy and 1 strategy to sell             
          engine (-> (Engine/builder (as-function (partial eval! dataset)) gtf)
                     (.populationSize POPULATION-SIZE)
                     (.executor executor)
                     (.build))
          result (-> engine
                     (.stream)
                     (.limit GENERATIONS)
                     (.collect (EvolutionResult/toBestGenotype)))]
      (println result))))

(defn -main [& args]
  (start-algorithm!))

;; clj -M -m ea.ea