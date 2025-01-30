(ns ea.ea
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.math :as math]
   [clojure.pprint :as pprint]
   [ea.db :as db]
   [ea.interop :refer [as-function]]
   [java-time.api :as jt])
  (:import
   [io.jenetics Genotype LongChromosome LongGene]
   [io.jenetics.engine Engine EvolutionResult]
   [java.util.concurrent Executors ThreadPoolExecutor]))

(def CONCURRENCY 8)
(def DATASET-LENGTH-IN-HOURS (* 24 14)) ;; 14 days available
(def DATASET-PRECISION-IN-SEC 60)
(def POPULATION-SIZE 500)
(def GENERATIONS 1000)
(def TIMEFRAME->GENE
  {300 {:index 0
        :price-max-change 5
        :price-change-divider 1} ;; 5min
   900 {:index 1
        :price-max-change 5
        :price-change-divider 1} ;; 15min
   1800 {:index 2
         :price-max-change 7
         :price-change-divider 1.5} ;; 30min
   3600 {:index 3
         :price-max-change 7
         :price-change-divider 1.5} ;; 1h
   14400 {:index 4
          :price-max-change 9
          :price-change-divider 2} ;; 4h
   86400 {:index 5
          :price-max-change 9
          :price-change-divider 2} ;; 1d
   })
(defn STRATEGY-COMPLEXITY []
  (count (keys TIMEFRAME->GENE)))
(def INITIAL-BALANCE 1000)
(defn PRICE-QUEUE-LENGTH []
  (/ (apply max (keys TIMEFRAME->GENE)) DATASET-PRECISION-IN-SEC))

(defn price-changes->reality [changes]
  (->> changes
       (#(select-keys % (keys TIMEFRAME->GENE)))
       (into [])
       (map #(list (:index (get TIMEFRAME->GENE (first %))) (second %)))
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
        (* 100))))

(defn simulate-intraday-trade! [strategy dataset]
  (let [[buy-strategy sell-strategy] strategy
        buy-strategy buy-strategy
        sell-strategy sell-strategy
        start-time (jt/local-date-time)
        end-time (jt/plus start-time (jt/hours DATASET-LENGTH-IN-HOURS))
        current-time (atom (jt/local-date-time))
        order (atom nil)
        balance (atom INITIAL-BALANCE)
        evaluation (atom 0)
        prices-queue (atom clojure.lang.PersistentQueue/EMPTY)
        price-changes (atom {})
        price-queue-length (PRICE-QUEUE-LENGTH)
        #_reality-ranges #_(atom {300 {:min 0 :max 0}
                                  900 {:min 0 :max 0}
                                  1800 {:min 0 :max 0}
                                  3600 {:min 0 :max 0}
                                  14400 {:min 0 :max 0}
                                  86400 {:min 0 :max 0}})]
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
                (swap! price-changes assoc k (-> (cond
                                                   (pos? price-change-percent)
                                                   (min (:price-max-change (get TIMEFRAME->GENE k)) price-change-percent)
                                                   (neg? price-change-percent)
                                                   (max (- (:price-max-change (get TIMEFRAME->GENE k))) price-change-percent)
                                                   :else 0)
                                                 (/ (:price-change-divider (get TIMEFRAME->GENE k)))
                                                 math/round))))
            #_(let [reality (price-changes->reality @price-changes)]
                (swap! reality-ranges (fn [ranges]
                                        (reduce-kv (fn [m k v]
                                                     (let [rv (nth reality (:index (get TIMEFRAME->GENE k)))]
                                                       (cond
                                                         (< rv (:min v))
                                                         (assoc-in m [k :min] rv)
                                                         (> rv (:max v))
                                                         (assoc-in m [k :max] rv)
                                                         :else
                                                         m))) ranges ranges)))
                (spit "./reality.edn" (with-out-str (pprint/pprint @reality-ranges))))
            (let [price (wait-close-possibilty! @price-changes sell-strategy)]
              (when price
                (if @order
                  (swap! evaluation + 0.99)
                  (swap! evaluation + 0.01))
                (swap! balance + (- price (or (:price @order) price)))
                (reset! order nil)))
            (let [price (wait-open-possibility! @price-changes buy-strategy)]
              (when price
                (swap! evaluation + 0.01)
                (reset! order {:price price}))))
          (swap! current-time jt/plus (jt/seconds DATASET-PRECISION-IN-SEC))
          (recur (drop DATASET-PRECISION-IN-SEC lines)))))
    (when (> @balance INITIAL-BALANCE)
      (Thread/sleep (* 300 (rand-int CONCURRENCY)))
      (println "balance left: " @balance)
      (println "evaluation: " @evaluation)
      (println "buy-strategy: " buy-strategy)
      (println "sell-strategy: " sell-strategy)
      (println "reality: " (price-changes->reality @price-changes)))

    (long (+ (- @balance INITIAL-BALANCE) @evaluation))))

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
  (->> (for [k (sort (keys TIMEFRAME->GENE))
             :let [change (-> (:price-max-change (get TIMEFRAME->GENE k))
                              (/ (:price-change-divider (get TIMEFRAME->GENE k)))
                              math/round)
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