(ns perf.async)

(set! *warn-on-reflection* true)


(defonce ^java.util.concurrent.ExecutorService executor
  (doto (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
    (set-agent-send-executor!)
    (set-agent-send-off-executor!)))


(defrecord CloseableFuture [^java.util.concurrent.Future fut]
  java.util.concurrent.Future
  (get [_] (.get fut))
  (get [_ timeout unit] (.get fut timeout unit))
  (isCancelled [_] (.isCancelled fut))
  (isDone [_] (.isDone fut))
  (cancel [_ interrupt?] (.cancel fut interrupt?))

  java.io.Closeable
  (close [_] (.cancel fut true))

  clojure.lang.IDeref
  (deref [_] (deref fut))

  clojure.lang.IBlockingDeref
  (deref [_ timeout timeout-value] (deref fut timeout timeout-value))

  clojure.lang.IPending
  (isRealized [_] (realized? fut))

  java.lang.Object
  (toString [_] (str "CloseableFuture[state=" (.state fut) "]")))


(defn closeable-future-call [f]
  (let [callable (reify java.util.concurrent.Callable
                   (call [_] (f)))
        fut      (.submit executor callable)]
    (->CloseableFuture fut)))


(defmacro closeable-future [& body]
  `(closeable-future-call (^:once fn [] ~@body)))


(comment
  (let [f (closeable-future-call (fn [] (Thread/sleep 100) :ok))]
    @f)
  ;;=> :ok

  (let [f (closeable-future-call (fn [] (Thread/sleep 100) :ok))]
    (str f))
  ;;=> "CloseableFuture[state=RUNNING]"

  (let [f (closeable-future-call (fn [] (Thread/sleep 100) :ok))]
    (deref f 10 ::timeout))
  ;;=> :perf.async/timeout

  (let [f (closeable-future-call (fn [] (Thread/sleep 100) :ok))]
    (.close f)
    (str f))
  ;;=> "CloseableFuture[state=CANCELLED]"

  (let [f (closeable-future-call (fn [] (Thread/sleep 100) :ok))]
    (.close f)
    @f)
  ;;=> Execution error (CancellationException) at java.util.concurrent.FutureTask/report (FutureTask.java:121). 
  )
  