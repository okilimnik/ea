(ns ea.earn
  (:require
   [clojure.core.async :refer [thread]]
   [clojure.math :as math]
   [ea.binance :as binance :refer [jread]]
   [ea.scheduler :as scheduler])
  (:import
   [com.binance.connector.client.utils.websocketcallback WebSocketMessageCallback]))

(def TIMEFRAME->GENE
  {300 {:index 0
        :price-max-change 5
        :price-change-divider 1}
   900 {:index 1
        :price-max-change 5
        :price-change-divider 1}})
(def DATASET-PRECISION-IN-SEC 60)
(def PRICE-QUEUE-LENGTH
  (/ (apply max (keys TIMEFRAME->GENE)) DATASET-PRECISION-IN-SEC))
(def STOP-LOSS-1 0)
(def STOP-LOSS-2 1000)
(def buy-strategy [0 0])
(def sell-strategy [0 -2])
(def binance-stream "btcusdt@depth5")
(def prices-queue (atom clojure.lang.PersistentQueue/EMPTY))
(def price-changes (atom {}))
(def order (atom nil))
(def TRADE-AMOUNT-BTC 0.001)

(defn price-changes->reality [changes]
  (->> changes
       (#(select-keys % (keys TIMEFRAME->GENE)))
       (into [])
       (map #(list (:index (get TIMEFRAME->GENE (first %))) (second %)))
       (sort-by first)
       (map second)
       vec))

(defn wait-close-possibilty! [price-changes strategy order-price]
  (when (seq price-changes)
    (let [reality (->> price-changes
                       price-changes->reality)
          price (:bid-price price-changes)]
      (when (or (= strategy reality)
                (and order-price
                     ;(<= (+ order-price STOP-PROFIT) price)
                     (or (<= (- order-price STOP-LOSS-1) price)
                         (>= (- order-price STOP-LOSS-2) price))))
        price))))

(defn wait-open-possibility! [price-changes strategy]
  (when (seq price-changes)
    (let [reality (->> price-changes
                       price-changes->reality)
          price (:ask-price price-changes)]
      (when (= strategy reality)
        price))))

(defn get-price-change-percent [k prices]
  (let [last-price (last prices)
        first-price (nth prices (- PRICE-QUEUE-LENGTH (/ k DATASET-PRECISION-IN-SEC)))]
    (-> last-price
        (- first-price)
        (/ first-price)
        (* 100))))

(defn on-data [data]
  (swap! prices-queue #(as-> % $
                         (conj $ (-> data :asks ffirst parse-double))
                         (if (> (count $) PRICE-QUEUE-LENGTH)
                           (pop $)
                           $)))
  (when (= (count @prices-queue) PRICE-QUEUE-LENGTH)
    (swap! price-changes assoc :ask-price (-> data :asks ffirst parse-double))
    (swap! price-changes assoc :bid-price (-> data :bids ffirst parse-double))
    (doseq [k (keys TIMEFRAME->GENE)]
      (let [price-change-percent (get-price-change-percent k @prices-queue)]
        (swap! price-changes assoc k (-> (cond
                                           (pos? price-change-percent)
                                           (min (:price-max-change (get TIMEFRAME->GENE k)) price-change-percent)
                                           (neg? price-change-percent)
                                           (max (- (:price-max-change (get TIMEFRAME->GENE k))) price-change-percent)
                                           :else 0)
                                         (/ (:price-change-divider (get TIMEFRAME->GENE k)))
                                         math/round))))))

(defn create-buy-params [symbol]
  {"symbol" symbol
   "side" "BUY"
   "type" "MARKET"
   "timeInForce" "GTC"
   "quantity" TRADE-AMOUNT-BTC})

(defn create-sell-params [symbol]
  {"symbol" symbol
   "side" "SELL"
   "type" "MARKET"
   "timeInForce" "GTC"
   "quantity" TRADE-AMOUNT-BTC})

(defn start-earning! []
  (thread
    (scheduler/start!
     60000
     (fn []
       (when (= (count @prices-queue) PRICE-QUEUE-LENGTH)
         (when @order
           (let [price (wait-close-possibilty! @price-changes sell-strategy (:price @order))]
             (when price
               (binance/open-order! (create-sell-params "BTCUSDT"))
               (reset! order nil))))
         (let [price (wait-open-possibility! @price-changes buy-strategy)]
           (when price
             (binance/open-order! (create-buy-params "BTCUSDT"))
             (reset! order {:price price}))))))))

(defn start! []
  (let [data-counter (atom 0)]
    (binance/init-earning)
    (binance/subscribe [binance-stream]
                       (reify WebSocketMessageCallback
                         ^void (onMessage [_ event-str]
                                          (try
                                            (let [event (jread event-str)
                                                  data (:data event)]
                                              (when (= (:stream event) binance-stream)
                                                (when (= @data-counter 0)
                                                  (on-data data))
                                                (swap! data-counter (fn [v]
                                                                      (if (= (inc v) DATASET-PRECISION-IN-SEC)
                                                                        0
                                                                        (inc v))))))
                                            (catch Exception e (prn e)))))))
  (start-earning!))