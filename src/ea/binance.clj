(ns ea.binance
  (:require [jsonista.core :as j])
  (:import [com.binance.connector.client.impl SpotClientImpl WebSocketStreamClientImpl]))

(def trade-client (atom nil))
(def market-client (atom nil))
(def ws-client (atom nil))

(defn init []
  #_(reset! trade-client (.createTrade (SpotClientImpl. (System/getenv "BINANCE_API_KEY")
                                                        (System/getenv "BINANCE_SECRET"))))
  (reset! market-client (.createMarket (SpotClientImpl.))))

(defn subscribe [streams callback]
  (when-not @ws-client
    (reset! ws-client (WebSocketStreamClientImpl.)))
  (.combineStreams @ws-client streams callback))

(defn unsubscribe [stream]
  (when-not @ws-client
    (reset! ws-client (WebSocketStreamClientImpl.)))
  (.closeAllConnections @ws-client))

(defn jread [v]
  (j/read-value v j/keyword-keys-object-mapper))

(defn open-order! [params]
  (when-not @market-client (init))
  (jread (.newOrder @trade-client params)))

(defn opened-orders! [symbol]
  (when-not @market-client (init))
  (jread (.getOpenOrders @trade-client {"symbol" symbol
                                        "timestamp" (System/currentTimeMillis)})))

(defn cancel-order! [symbol id]
  (when-not @market-client (init))
  (-> (.cancelOrder @trade-client {"symbol" symbol
                                   "orderId" id
                                   "timestamp" (System/currentTimeMillis)})
      jread))

(defn depth! [symbol limit]
  (when-not @market-client (init))
  (jread (.depth @market-client {"symbol" symbol "limit" limit})))

(defn mini-ticker! [symbol window]
  (when-not @market-client (init))
  (jread (.ticker @market-client {"symbol" symbol "windowSize" window "type" "FULL"})))