(ns hft.ea
  (:require
   [clojure.string :as str]
   [hft.binance :as binance :refer [jread]]
   [hft.interop :refer [as-function]]
   [java-time.api :as jt])
  (:import
   [com.binance.connector.client.utils.websocketcallback WebSocketMessageCallback]
   [io.jenetics Genotype LongChromosome LongGene]
   [io.jenetics.engine Engine EvolutionResult]
   [io.jenetics.util BatchExecutor]))

(def SYMBOL "btcusdt")
(def POPULATION-SIZE 500)
(def GENERATIONS 10)
(def TIMEFRAME->GENE
  {:1hTicker 0
   :4hTicker 1
   :1dTicker 2})
(def STRATEGY-COMPLEXITY (count (keys TIMEFRAME->GENE)))
(def PRICE-MAX-CHANGE 1000)
(def INITIAL-BALANCE 1000)

(def price-changes (atom (transient {})))

(defn wait-close-possibilty! [strategy stop-loss]
  (let [reality (->> @price-changes)
        price (:bid-price @price-changes)]
    (when (or (= strategy reality)
              (<= price stop-loss))
      price)))

(defn wait-open-possibility! [strategy]
  (let [reality (->> @price-changes)
        price (:ask-price @price-changes)]
    (when (= strategy reality)
      price)))

(defn simulate-intraday-trade! [strategy]
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
            (when price
              (swap! number-of-trades inc)
              (swap! balance + (- price (:price @order)))
              (reset! order nil)))
          (let [price (wait-open-possibility! buy-strategy)]
            (when price
              (reset! order {:price price}))))
        (Thread/sleep 1000)
        (recur)))
    (+ (- @balance INITIAL-BALANCE) (if (zero? @number-of-trades)
                                      (- 1000)
                                      @number-of-trades))))

(defn eval! [gt]
  (let [strategy (->> (range (.length gt))
                      (mapv #(-> (.get gt %)
                                 (.as LongChromosome)
                                 (.toArray)
                                 first))
                      (partition 2)
                      (partition STRATEGY-COMPLEXITY))]
    (simulate-intraday-trade! strategy)))

(def timeframe-chromosome
  (let [values (vals TIMEFRAME->GENE)]
    (LongGene/of (apply min values) (inc (apply max values)))))

(def price-change-chromosome
  (LongGene/of (- PRICE-MAX-CHANGE) PRICE-MAX-CHANGE))

(defn start-prices-stream! []
  (let [streams (set [(str SYMBOL "@ticker_1h")
                      (str SYMBOL "@ticker_4h")
                      (str SYMBOL "@ticker_1d")])
        depth-stream (str SYMBOL "@depth5")]
    (binance/subscribe (conj streams depth-stream)
                       (reify WebSocketMessageCallback
                         ^void (onMessage [_ event-str]
                                          (try
                                            (let [event (jread event-str)
                                                  data (:data event)]
                                              (when (= (:stream event) depth-stream)
                                                (swap! price-changes assoc! :ask-price (-> data :asks ffirst parse-double))
                                                (swap! price-changes assoc! :bid-price (-> data :bids ffirst parse-double)))
                                              (when (contains? streams (:stream event))
                                                (let [price-change (:p data)]
                                                  (swap! price-changes assoc! (keyword (:e data)) price-change))))
                                            (catch Exception e (prn e))))))))

(defn start-algorithm! []
  (let [gtf (Genotype/of (->> [(LongChromosome/of [timeframe-chromosome])
                               (LongChromosome/of [price-change-chromosome])]
                              (repeat (* 2 STRATEGY-COMPLEXITY)) ;; 1 strategy to buy and 1 strategy to sell
                              (mapcat identity)))
        engine (-> (Engine/builder (as-function eval!) gtf)
                   (.populationSize POPULATION-SIZE)
                   (.fitnessExecutor (BatchExecutor/ofVirtualThreads))
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