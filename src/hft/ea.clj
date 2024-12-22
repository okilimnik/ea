(ns hft.ea
  (:require
   [hft.binance :as binance :refer [jread]]
   [hft.interop :refer [as-function]]
   [java-time.api :as jt]
   [clojure.string :as str])
  (:import
   [com.binance.connector.client.utils.websocketcallback WebSocketMessageCallback]
   [io.jenetics Genotype LongChromosome LongGene]
   [io.jenetics.engine Engine EvolutionResult]))

(def SYMBOL "BTCUSDT")
(def POPULATION-SIZE 500)
(def GENERATIONS 10)
(def STRATEGY-COMPLEXITY 5)
(def PRICE-MAX-CHANGE 1000)
(def INITIAL-BALANCE 1000)
(def TIMEFRAMES
  {0  :1s
   1  :1m
   2  :3m
   3  :5m
   4  :15m
   5  :30m
   6  :1h
   7  :2h
   8  :4h
   9  :6h
   10 :8h
   11 :12h
   12 :1d})

(defn wait-close-possibilty! [strategy stop-loss])

(defn wait-open-possibility! [strategy])

(defn simulate-intraday-trade! [id strategy]
  (let [[buy-strategy sell-strategy] (vec strategy)
        start-time (jt/local-date)
        end-time (jt/plus start-time (jt/days 1))
        order (atom nil)
        balance (atom INITIAL-BALANCE)
        number-of-trades (atom 0)
        stop-loss-interval 200]
    (loop []
      (when (jt/before? (jt/local-date) end-time)
        (if @order
          (let [stop-loss (- (:price @order) stop-loss-interval)
                price (wait-close-possibilty! sell-strategy stop-loss)]
            (swap! number-of-trades inc)
            (swap! balance + (- price (:price @order)))
            (reset! order nil))
          (let [price (wait-open-possibility! buy-strategy)]
            (reset! order {:price price})))
        (recur)))
    (+ (- @balance INITIAL-BALANCE) @number-of-trades)))

(defn eval! [gt]
  (let [strategy (->> (range (.length gt))
                      (mapv #(-> (.get gt %)
                                 (.as LongChromosome)
                                 (.toArray)
                                 first))
                      (partition 2)
                      (partition STRATEGY-COMPLEXITY))
        strategy-id (random-uuid)]
    (simulate-intraday-trade! strategy-id strategy)))

(def timeframe-chromosome
  (let [values (keys TIMEFRAMES)]
    (LongGene/of (apply min values) (apply max values))))

(def price-change-chromosome
  (LongGene/of (- PRICE-MAX-CHANGE) PRICE-MAX-CHANGE))

(defn start-prices-stream! []
  (let [stream (str (str/lower-case SYMBOL) "@kline_1s")]
    (binance/subscribe [stream]
                       (reify WebSocketMessageCallback
                         ^void (onMessage [_ event-str]
                                          (try
                                            (let [event (jread event-str)
                                                  data (:data event)]
                                              (cond
                                                (and (= (:stream event) stream)
                                                     (= (:e data) "kline"))
                                                (let [price (-> data :k :c)])))
                                            (catch Exception e (prn e))))))))

(defn start-algorithm! []
  (let [gtf (Genotype/of (->> [(LongChromosome/of [timeframe-chromosome])
                               (LongChromosome/of [price-change-chromosome])]
                              (repeat (* 2 STRATEGY-COMPLEXITY)) ;; 1 strategy to buy and 1 strategy to sell
                              (mapcat identity)))
        engine (-> (Engine/builder (as-function eval!) gtf)
                   (.populationSize POPULATION-SIZE)
                   (.build))
        result (-> engine
                   (.stream)
                   (.limit GENERATIONS)
                   (.collect (EvolutionResult/toBestGenotype)))]
    (println result)))

(defn -main [& args]
  (start-prices-stream!)
  (start-algorithm!))

;; clj -M -m hft.model.ea