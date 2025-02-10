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
(def DATASET-PRECISION-IN-SEC 5)
(def POPULATION-SIZE 500)
(def GENERATIONS 1000)
(def TRADE-AMOUNT-BTC 0.001)
(def COMISSION 0.001)
(def TIMEFRAME->GENE
  {15 {:index 0 :price-max-change 5 :price-change-divider 0.25}
   30 {:index 1 :price-max-change 5 :price-change-divider 0.5}
   60 {:index 2 :price-max-change 5 :price-change-divider 0.5}
   120 {:index 3 :price-max-change 5 :price-change-divider 0.8}
   300 {:index 4 :price-max-change 10  :price-change-divider 1}})
#_(def TIMEFRAME->GENE
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

(defn wait-close-possibilty! [price-changes take-profit-strategy stop-loss-strategy]
  (when (seq price-changes)
    (let [reality (->> price-changes
                       price-changes->reality)
          price (:bid-price price-changes)]
      (when (or (= stop-loss-strategy reality)
                (= take-profit-strategy reality))
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

(defn valid? [strategy]
  true
  #_(let [[open-strategy take-profit-strategy stop-loss-strategy] strategy]
    (and (= (vec (sort (map #(Math/abs %) open-strategy)))
            (mapv #(Math/abs %) open-strategy))
         (= (vec (sort (map #(Math/abs %) take-profit-strategy)))
            (mapv #(Math/abs %) take-profit-strategy))
         (= (vec (sort (map #(Math/abs %) stop-loss-strategy)))
            (mapv #(Math/abs %) stop-loss-strategy)))))

(defn simulate-intraday-trade! [strategy dataset]
    (if-not (valid? strategy)
      (float 0)
      (let [[open-strategy take-profit-strategy stop-loss-strategy] strategy
            start-time (jt/local-date-time)
            end-time (jt/plus start-time (jt/hours DATASET-LENGTH-IN-HOURS))
            current-time (atom (jt/local-date-time))
            order (atom nil)
            balance (atom INITIAL-BALANCE)
            evaluation (atom 0)
            prices-queue (atom clojure.lang.PersistentQueue/EMPTY)
            price-changes (atom {})
            number-of-trades (atom 0)
            price-queue-length (PRICE-QUEUE-LENGTH)
            reality-ranges (atom {15 {:min 0 :max 0}
                                  30 {:min 0 :max 0}
                                  60 {:min 0 :max 0}
                                  120 {:min 0 :max 0}
                                  300 {:min 0 :max 0}
                              ;900 {:min 0 :max 0}
                              ;1800 {:min 0 :max 0}
                              ;3600 {:min 0 :max 0}
                              ;14400 {:min 0 :max 0}
                              ;86400 {:min 0 :max 0}
                              ;
                                  })]
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
                                                           m))) ranges ranges))))
                (let [price (wait-close-possibilty! @price-changes take-profit-strategy stop-loss-strategy)]
                  (when price
                    (swap! evaluation + 0.0001)
                    (when @order
                      (swap! evaluation + 0.0002)
                      (let [comission (* COMISSION TRADE-AMOUNT-BTC price)]
                        (swap! number-of-trades inc)
                        (swap! balance + (- (* TRADE-AMOUNT-BTC price)
                                            comission))
                        (reset! order nil)))))
                (let [price (wait-open-possibility! @price-changes open-strategy)]
                  (when price
                    (swap! evaluation + 0.0001)
                    (when-not @order
                      (let [comission (* COMISSION TRADE-AMOUNT-BTC price)]
                        (swap! balance - (* TRADE-AMOUNT-BTC price) comission))
                      (reset! order {:price price})))))
              (swap! current-time jt/plus (jt/seconds DATASET-PRECISION-IN-SEC))
              (recur (drop DATASET-PRECISION-IN-SEC lines)))))
    ;(spit "./reality.edn" (with-out-str (pprint/pprint @reality-ranges)))
    ;; TODO: log to s3 bucket
    ;(when (> @balance INITIAL-BALANCE)
        (when (= @number-of-trades 0)
          (reset! balance INITIAL-BALANCE))
        (let [final-eval (float (+ @evaluation (/ (- @balance INITIAL-BALANCE) 1000)))]
          (Thread/sleep (* 300 (rand-int CONCURRENCY)))
          (println "balance left: " @balance)
          (println "number of trades: " @number-of-trades)
          (println "evaluation: " final-eval)
          (println "open-strategy: " open-strategy)
          (println "take-profit-strategy: " take-profit-strategy)
          (println "stop-loss-strategy: " stop-loss-strategy)
          (println "reality: " (price-changes->reality @price-changes))
      ;(println "reality: " (price-changes->reality @price-changes))
      ;)

          final-eval))))

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
             :let [change 5
                   gene (LongGene/of (- change) change)]]
         (LongChromosome/of [gene]))
       (repeat 3)
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