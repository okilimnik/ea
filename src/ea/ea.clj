(ns ea.ea
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.math :as math]
   [ea.db :as db]
   [ea.interop :refer [as-function]]
   [java-time.api :as jt])
  (:import
   [io.jenetics Genotype LongChromosome LongGene]
   [io.jenetics.engine Engine EvolutionResult]
   [java.util.concurrent Executors ThreadPoolExecutor]))

(def CONCURRENCY 5)
(def DATASET-LENGTH-IN-HOURS (* 24 5)) ;; 14 days available
(def DATASET-PRECISION-IN-SEC 60)
(def POPULATION-SIZE 500)
(def GENERATIONS 1000)
(def PRICE-MIN-CHANGE 2) ;; means 0.2%
(def TIMEFRAME->GENE
  {300 0 ;; 5min
   900 1 ;; 15min
   1800 2 ;; 30min
   3600 3 ;; 1h
   14400 4 ;; 4h
   86400 5 ;; 1d
   })
(defn STRATEGY-COMPLEXITY []
  (count (keys TIMEFRAME->GENE)))
(def INITIAL-BALANCE 1000)
(defn PRICE-QUEUE-LENGTH []
  (/ (apply max (keys TIMEFRAME->GENE)) DATASET-PRECISION-IN-SEC))
(defn PRICE-MAX-CHANGE []
  (* PRICE-MIN-CHANGE (STRATEGY-COMPLEXITY)))

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
    (-> last-price
        (- first-price)
        (/ first-price)
        (* 1000)
        math/round)))

(defn simulate-intraday-trade! [strategy dataset]
  (let [[buy-strategy sell-strategy] strategy
        buy-strategy buy-strategy
        sell-strategy sell-strategy
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
                                               (min (PRICE-MAX-CHANGE) price-change-percent)
                                               (neg? price-change-percent)
                                               (max (- (PRICE-MAX-CHANGE)) price-change-percent)
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
    ;(when (> @number-of-trades 0)
    (Thread/sleep (* 300 (rand-int CONCURRENCY)))
    (println "balance left: " @balance)
    (println "number of trades: " @number-of-trades)
    (println "buy-strategy: " buy-strategy)
    (println "sell-strategy: " sell-strategy)
    (println "reality: " (->> @price-changes
                              price-changes->reality))
                                ;)
    (long (+ (- @balance INITIAL-BALANCE) (if (zero? @number-of-trades)
                                            (- 1000)
                                            @number-of-trades)))))

(defn eval! [dataset gt]
  (let [strategy (->> (range (.length gt))
                      (mapv #(-> (.get gt %)
                                 (.as LongChromosome)
                                 (.toArray)
                                 first))
                      (partition (STRATEGY-COMPLEXITY))
                      (mapv vec))]
    (simulate-intraday-trade! strategy dataset)))

(defn price-change-genotype []
  (->> (for [i (range (STRATEGY-COMPLEXITY))
             :let [change (* PRICE-MIN-CHANGE (inc i))
                   gene (LongGene/of (- change) change)]]
         (LongChromosome/of [gene]))
       (repeat 2)
       (mapcat identity)
       Genotype/of))

(defn start-algorithm! []
  (with-open [rdr (io/reader db/file)]
    (let [dataset (line-seq rdr)
          ^ThreadPoolExecutor executor (Executors/newFixedThreadPool CONCURRENCY)
          engine (-> (Engine/builder (as-function (partial eval! dataset)) (price-change-genotype))
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