(ns hft.scheduler
  (:require [clojure.core.async :refer [go-loop timeout <!]]))

(defn start! [interval f]
  (go-loop [scheduled-t (System/currentTimeMillis)]
    (let [current-t (System/currentTimeMillis)]
      (if (>= current-t scheduled-t)
        (do (f)
            (recur (+ scheduled-t interval)))
        (do (<! (timeout (- scheduled-t current-t)))
            (recur scheduled-t))))))