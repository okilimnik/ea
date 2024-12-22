(ns hft.interop
  (:import [java.util.function Function]))

(defn as-function [f]
  (reify Function
    (apply [_this arg]
      (f arg))))