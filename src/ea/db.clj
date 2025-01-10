(ns ea.db)

(def file "btcusdt.db")

(defn insert! [doc]
  (spit file (prn-str doc) :append true))