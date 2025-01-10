(ns ea.db)

(def file "btcusdt.db")

(defn insert! [doc]
  (spit file (str (prn-str doc) "\n") :append true))