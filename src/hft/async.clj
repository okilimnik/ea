(ns hft.async
  (:require
   [clojure.core.async :refer [<!! >!! close! promise-chan]]
   [clojure.core.async.impl.concurrent :as conc])
  (:import
   [java.util.concurrent Executors ExecutorService ThreadFactory]))

(defn- deref-future
  "Copypaste of clojure.core/deref-future (as it's private)"
  ([^java.util.concurrent.Future fut]
   (.get fut))
  ([^java.util.concurrent.Future fut timeout-ms timeout-val]
   (try (.get fut timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (catch java.util.concurrent.TimeoutException _e
          timeout-val))))

(defn counted-vthread-factory
  "Create a ThreadFactory that maintains a counter for naming virtual Threads.
   name-format specifies thread names - use %d to include counter"
  [name-format]
  (let [counter (atom 0)]
    (reify
      ThreadFactory
      (newThread [_this runnable]
        (let [builder (-> (Thread/ofVirtual)
                          (.name (format name-format (swap! counter inc))))]
          (.unstarted builder ^Runnable runnable))))))


(defonce ^ExecutorService vthreads-executor (Executors/newThreadPerTaskExecutor (counted-vthread-factory "hft-vthread-pool-%d")))


(defn vthread-call
  "A combination of clojure future and clojure.async thread. 
   Works like a future but under the hood uses `promise-chan`,
   b/c the virtual thread's Future doesn't return the value, but always `nil`."
  [f]
  (let [c (promise-chan)
        binds (clojure.lang.Var/getThreadBindingFrame)
        fut (.submit vthreads-executor ^Callable (fn []
                                                   (clojure.lang.Var/resetThreadBindingFrame binds)
                                                   (try
                                                     (let [ret (f)]
                                                       (when-not (nil? ret)
                                                         (>!! c ret))
                                                       (close! c))
                                                     (catch Throwable e
                                                       (close! c)
                                                       (prn e)
                                                       (throw e)))))]
    c
    #_(reify
        clojure.lang.IDeref
        (deref [_]
          (deref-future fut)
          (<!! c))
        clojure.lang.IBlockingDeref
        (deref
          [_ timeout-ms timeout-val]
          (let [ret (deref-future fut timeout-ms timeout-val)]
            (or ret (<!! c))))
        clojure.lang.IPending
        (isRealized [_] (.isDone fut))
        java.util.concurrent.Future
        (get [_]
          (.get fut)
          (<!! c))
        (get [_ timeout unit]
          (.get fut timeout unit)
          (<!! c))
        (isCancelled [_] (.isCancelled fut))
        (isDone [_] (.isDone fut))
        (cancel [_ interrupt?] (.cancel fut interrupt?)))))


(defmacro vthread
  "Lightweight virtual thread: https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
   Its purpose is low-level IO tasks with minimum CPU load.
   Returns a future.
   Logs errors to Sentry."
  [& body]
  `(vthread-call (^:once fn* [] ~@body)))

(defonce ^ExecutorService threads-executor (Executors/newCachedThreadPool (conc/counted-thread-factory "vh-thread-pool-%d" true)))

(defn thread-call
  "Copypaste of clojure.core/future-call.
   Added Sentry reporting on error."
  [f]
  (let [binds (clojure.lang.Var/getThreadBindingFrame)
        fut (.submit threads-executor ^Callable (fn []
                                                  (clojure.lang.Var/resetThreadBindingFrame binds)
                                                  (try (f)
                                                       (catch Throwable e
                                                         (prn e)
                                                         (throw e)))))]
    (reify
      clojure.lang.IDeref
      (deref [_] (deref-future fut))
      clojure.lang.IBlockingDeref
      (deref
        [_ timeout-ms timeout-val]
        (deref-future fut timeout-ms timeout-val))
      clojure.lang.IPending
      (isRealized [_] (.isDone fut))
      java.util.concurrent.Future
      (get [_] (.get fut))
      (get [_ timeout unit] (.get fut timeout unit))
      (isCancelled [_] (.isCancelled fut))
      (isDone [_] (.isDone fut))
      (cancel [_ interrupt?] (.cancel fut interrupt?)))))

(defmacro thread
  "OS thread for high load / long run tasks.
   Returns a future.
   Logs errors to Sentry."
  [& body]
  `(thread-call (^:once fn* [] ~@body)))