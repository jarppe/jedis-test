(ns perf.chaos
  (:require [clojure.string :as str]
            [perf.jedis :as jedis]
            [perf.carmine :as car]
            [perf.docker :as docker]
            [perf.async :as async :refer [closeable-future]])
  (:import (java.time Duration)
           (java.util.concurrent CountDownLatch
                                 TimeUnit)))


(defn make-logger []
  (let [start (System/nanoTime)]
    (fn [logger-name]
      (fn [& message]
        (let [duration (-> (System/nanoTime)
                           (- start)
                           (Duration/ofNanos))]
          (.println System/err (format "%02d:%02d.%03d  %-10s : %s"
                                       (.toMinutes duration)
                                       (.toSecondsPart duration)
                                       (.toMillisPart duration)
                                       (name logger-name)
                                       (->> message
                                            (remove #(instance? Throwable %))
                                            (str/join " "))))
          (when-let [^Throwable e (some #(instance? Throwable %) message)]
            (.println System/err (format "           %s: %s" (.getClass e) (.getMessage e)))
            (.printStckTrace e System/err)))))))


(defn ensure-both-redis-containers-are-up []
  (-> (docker/find-container-by-id :redis-1)
      (docker/container-start))
  (-> (docker/find-container-by-id :redis-2)
      (docker/container-start))
  (let [redis-up?  (fn [redis-id]
                     (try
                       (with-open [client (jedis/client redis-id)]
                         (= (.ping client)
                            "PONG"))
                       (catch Exception _
                         false)))]
    (with-open [redis-1 (closeable-future
                         (while (not (redis-up? :redis-1))
                           (Thread/sleep 100))
                         true)
                redis-2 (closeable-future
                         (while (not (redis-up? :redis-2))
                           (Thread/sleep 100))
                         true)]
      (and (deref redis-1 2000 false)
           (deref redis-2 2000 false)))))


(defn chaos-monkey [{:keys [logger monkey-delay]
                     :or   {monkey-delay 5000}}]
  (closeable-future
   (let [log     (logger :monkey)
         redis-1 (docker/find-container-by-id :redis-1)
         redis-2 (docker/find-container-by-id :redis-2)]
     (try
       (doseq [redis (cycle [redis-1 redis-2])]
         (let [redis-name (docker/container-id redis)]
           (Thread/sleep monkey-delay)
           (log "stopping" redis-name "...")
           (docker/container-stop redis)
           (Thread/sleep monkey-delay)
           (log "starting" redis-name "...")
           (docker/container-start redis)))
       (catch InterruptedException _
         (log "DONE")
         :ok)
       (catch Exception e
         (log "FAIL" e)
         :fail)))))


(defn subscription-with-node-crash-jedis [{:keys [message-count
                                                  message-delay]
                                           :or   {message-count 30000
                                                  message-delay 10}
                                           :as   opts}]
  (closeable-future
   (ensure-both-redis-containers-are-up)
   (let [logger         (make-logger)
         opts           (assoc opts :logger logger)
         log            (logger :main)
         channel        (str (gensym "subscription-"))
         received-latch (CountDownLatch. message-count)]
     (with-open [pool          (jedis/create-sentinel-pool)
                 _monkey       (chaos-monkey opts)
                 _subscription (jedis/subscribe pool
                                                channel
                                                (fn [_] (.countDown received-latch)))
                 publisher     (closeable-future
                                (try
                                  (let [log (logger :publisher)]
                                    (doseq [n (range message-count)]
                                      (jedis/publish pool channel n)
                                      (when (zero? (mod n 1000)) (log "sent" n "messages..."))
                                      (Thread/sleep message-delay))
                                    (log "DONE")
                                    :done)
                                  (catch Exception e
                                    (log "FAIL" e)
                                    :fail)))]
       (log "waiting publisher...")
       (log "publisher:" (name (deref publisher)))
       ;; Wait for final messages:
       (if (.await received-latch 10 TimeUnit/SECONDS)
         :ok
         (.getCount received-latch))))))


(comment
  (def test-run (closeable-future
                 (let [message-count 10000
                       timeout       (-> (Duration/ofMinutes 10) (.toMillis))
                       result        (deref (subscription-with-node-crash-jedis {:logger        (make-logger)
                                                                                 :message-count message-count})
                                            timeout
                                            0)]
                   (println "test:" (if (= result :ok)
                                      "SUCCESS"
                                      (str "FAIL: " result " messages lost"))))))
  ;
  )


(defn chaos [args]
  (let [opts   (->> (partition 2 args)
                    (map (fn [[k v]] [(keyword k) (parse-long v)]))
                    (into {}))
        result (-> (subscription-with-node-crash-jedis opts)
                   (deref))]
    (println "\n\nResult:" (if (= result :ok)
                             "SUCCESS"
                             (str "FAIL: " result " messages lost")))
    (System/exit 0)))