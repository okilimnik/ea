(ns ea.binance
  (:require
   [jsonista.core :as j])
  (:import
   [com.binance.connector.client.impl SpotClientImpl WebSocketStreamClientImpl]
   [java.util ArrayList]))

(def trade-client (atom nil))
(def ws-client (atom nil))

(defn init-earning []
  (reset! trade-client (.createTrade (SpotClientImpl. (System/getenv "BINANCE_API_KEY")
                                                      (System/getenv "BINANCE_SECRET")))))

(defn subscribe [streams callback]
  (when-not @ws-client
    (reset! ws-client (WebSocketStreamClientImpl.)))
  (.combineStreams @ws-client (ArrayList. streams) callback))

(defn jread [v]
  (j/read-value v j/keyword-keys-object-mapper))

(defn open-order! [params]
  (when-not @trade-client (init-earning))
  (jread (.newOrder @trade-client params)))