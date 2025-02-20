(ns perf.retry
  (:import (java.time Duration)))


(set! *warn-on-reflection* true)


;;
;; Retry helper:
;;


(defn retry-delay-doubling [_retries previous-retry-delay]
  (* 2 (max previous-retry-delay 1)))


(defn retry-call
  ([f] (retry-call f nil))
  ([f {:keys [retry-count retry-timeout retry-delay accept-value? on-fail]
       :or   {retry-count   Long/MAX_VALUE
              retry-delay   retry-delay-doubling
              accept-value? (fn [v] (not (instance? Exception v)))
              on-fail       (fn [fail-info]
                              (throw (if (->> fail-info :last-result (instance? Exception))
                                       (-> fail-info :last-result)
                                       (ex-info (str "retry failed, reason " (-> fail-info :reason (name))) (assoc fail-info :f f)))))}}]
   (let [deadline         (if retry-timeout
                            (-> (Duration/ofMillis retry-timeout)
                                (.toNanos)
                                (+ (System/nanoTime)))
                            Long/MAX_VALUE)
         next-retry-delay (if (fn? retry-delay)
                            retry-delay
                            (constantly retry-delay))]
     (loop [retries     0
            retry-delay (next-retry-delay 0 0)]
       (let [result (try
                      (f)
                      (catch Exception e
                        e))]
         (cond
           (accept-value? result)
           result

           (>= retries retry-count)
           (on-fail {:last-result result
                     :retries     retries
                     :reason      :retry-count-exeeded})

           (> (System/nanoTime) deadline)
           (on-fail {:last-result result
                     :retries     retries
                     :reason      :retry-timeout-reached})

           (or (instance? java.lang.InterruptedException result)
               (Thread/interrupted))
           (on-fail {:last-result result
                     :retries     retries
                     :reason      :interrupted})

           :else (let [retry-delay  (next-retry-delay retries retry-delay)
                       interrupted? (try
                                      (Thread/sleep (long retry-delay))
                                      false
                                      (catch java.lang.InterruptedException _
                                        true))]
                   (if interrupted?
                     (on-fail {:last-result result
                               :retries     retries
                               :reason      :interrupted})
                     (recur (inc retries)
                            retry-delay)))))))))


(defmacro with-retries [& body]
  (let [[opts & body] (if (-> body (first) (map?))
                        body
                        (cons nil body))]
    `(retry-call (fn [] ~@body)
                 ~opts)))


(comment
  (let [c (volatile! 0)]
    (with-retries
      (when (< (vswap! c inc) 10)
        (throw (ex-info "no" {})))
      :ok))

  (let [c (volatile! 0)]
    (with-retries
      {:retry-count 5
       :retry-delay 1}
      (when (< (vswap! c inc) 10)
        (throw (ex-info "no" {})))
      :ok))

  (let [c (volatile! 0)
        r (with-retries
            {:retry-timeout 100
             :retry-delay   10
             :on-fail       identity}
            (throw (ex-info (str "no: " (vswap! c inc)) {})))]
    {:c @c
     :r (dissoc r :last-result)})
  ;
  )
