(ns ea.ea
  (:require
   [ea.binance :as binance :refer [jread]]
   [ea.interop :refer [as-function]]
   [java-time.api :as jt]
   [ea.xtdb :as db])
  (:import
   [com.binance.connector.client.utils.websocketcallback WebSocketMessageCallback]
   [io.jenetics Genotype LongChromosome LongGene]
   [io.jenetics.engine Engine EvolutionResult]
   [io.jenetics.util BatchExecutor]))

(def SYMBOL "btcusdt")
(def POPULATION-SIZE 1000)
(def GENERATIONS 100)
(def TIMEFRAME->GENE
  {300 0 ;; 5min
   900 1 ;; 15min
   1800 2 ;; 30min
   3600 3 ;; 1h
   14400 4 ;; 4h
   86400 5 ;; 1d
   })
(def STRATEGY-COMPLEXITY (count (keys TIMEFRAME->GENE)))
(def PRICE-MAX-CHANGE 20)
(def INITIAL-BALANCE 1000)
(def PRICE-QUEUE-LENGTH 86400)

(def prices-queue
  (atom clojure.lang.PersistentQueue/EMPTY))

(def price-changes (atom {}))

(def algorithm-started? (atom false))

(defn price-changes->reality [changes]
  (->> changes
       (#(select-keys % (keys TIMEFRAME->GENE)))
       (into [])
       (map #(list ((first %) TIMEFRAME->GENE) (second %)))
       (sort-by first)))

(defn wait-close-possibilty! [strategy stop-loss]
  (when (seq @price-changes)
    (let [reality (->> @price-changes
                       price-changes->reality)
          price (:bid-price @price-changes)]
      (when (or (= strategy reality)
                (<= price stop-loss))
        price))))

(defn wait-open-possibility! [strategy]
  (when (seq @price-changes)
    (let [reality (->> @price-changes
                       price-changes->reality)
          price (:ask-price @price-changes)]
      (when (= strategy reality)
        price))))

(defn simulate-intraday-trade! [strategy]
  (let [[buy-strategy sell-strategy] (vec strategy)
        start-time (jt/local-date-time)
        end-time (jt/plus start-time (jt/days 1))
        order (atom nil)
        balance (atom INITIAL-BALANCE)
        number-of-trades (atom 0)
        stop-loss-interval 200]
    (loop []
      (when (jt/before? (jt/local-date-time) end-time)
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
    (when (> @number-of-trades 0)
      (Thread/sleep (* 100 (rand-int 10)))
      (println "balance left: " @balance)
      (println "number of trades: " @number-of-trades)
      (println "strategy: " strategy)
      (println "reality: " (->> @price-changes
                                price-changes->reality)))
    (long (+ (- @balance INITIAL-BALANCE) (if (zero? @number-of-trades)
                                            (- 1000)
                                            @number-of-trades)))))

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

(defn get-price-change-percent [k prices]
  (let [last-price (last prices)
        first-price (nth prices (- PRICE-QUEUE-LENGTH k))]
    (int (* 100 (/ (- last-price first-price)
                   first-price)))))

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
    (println result)
    (db/put! {:xt/id (random-uuid)
              :ea/timestamp (System/currentTimeMillis)
              :ea/result (prn-str result)})))

(defn start-prices-stream! []
  (let [depth-stream (str SYMBOL "@depth5")]
    (binance/subscribe [depth-stream]
                       (reify WebSocketMessageCallback
                         ^void (onMessage [_ event-str]
                                          (try
                                            (let [event (jread event-str)
                                                  data (:data event)]
                                              (when (= (:stream event) depth-stream)
                                                (swap! prices-queue #(as-> % $
                                                                       (conj $ (-> data :asks ffirst parse-double))
                                                                       (if (> (count $) PRICE-QUEUE-LENGTH)
                                                                         (pop $)
                                                                         $)))
                                                (swap! price-changes assoc :ask-price (-> data :asks ffirst parse-double))
                                                (swap! price-changes assoc :bid-price (-> data :bids ffirst parse-double))
                                                (doseq [k (keys TIMEFRAME->GENE)]
                                                  (let [price-change-percent (get-price-change-percent k @prices-queue)]
                                                    (swap! price-changes assoc k price-change-percent)))
                                                (when (and (not @algorithm-started?) (= (count @prices-queue) PRICE-QUEUE-LENGTH)) ;; warmed-up
                                                  (start-algorithm!))))
                                            (catch Exception e (prn e))))))))

(defn -main [& args]
  (start-prices-stream!))

;; clj -M -m ea.ea