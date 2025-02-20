(ns perf.jedis
  (:refer-clojure :exclude [set get])
  (:require [clojure.string :as str]
            [perf.retry :refer [with-retries]])
  (:import (java.time Duration)
           (java.util.concurrent Future) 
           (redis.clients.jedis Jedis 
                                JedisSentinelPool
                                JedisPubSub)
           (redis.clients.jedis.util Pool)))


(set! *warn-on-reflection* true)


;;
;; Common knowledge:
;;


(def redis-port 6379)
(def sentinel-port 26379)


;;
;; Client:
;;


(defn client
  (^Jedis [host] (client host redis-port))
  (^Jedis [host port]
   (Jedis. (name host) (int port))))


;;
;; Sentinel pool:
;;


(defn create-sentinel-pool ^Pool []
  (JedisSentinelPool. "mymaster"
                      #{"redis-sentinel-1:26379"
                        "redis-sentinel-2:26379"}))


(defn borrow-client
  (^Jedis [^Pool pool] (borrow-client pool nil))
  (^Jedis [^Pool pool retty-opts]
   (if retty-opts
     (with-retries retty-opts
       (.getResource pool))
     (.getResource pool))))



;;
;; with-jedis helper:
;;


(def default-pooled-client-retry-opts {:retry-timeout (-> (Duration/ofSeconds 5) (.toMillis))
                                       :retry-delay   (fn [retries previous-retry-delay]
                                                        (if (zero? retries)
                                                          10
                                                          (* previous-retry-delay 2)))})


(defmacro with-pooled-client
  [[bind pool] & body]
  (let [[opts body] (if (-> body (first) (map?))
                      [(first body) 
                       (rest body)]
                      [default-pooled-client-retry-opts 
                       body])]
    `(let [~(vary-meta bind assoc :tag `Jedis) (borrow-client ~pool ~opts)]
       (try
         ~@body
         (finally
           (.close ~bind))))))


;;
;; set/get
;;


(defn set [^Jedis client ^String k ^String v]
  (.set client k v))


(defn get [^Jedis client ^String k]
  (.get client k))


(comment 
  (with-open [pool  (create-sentinel-pool)
              jedis (borrow-client pool)]
    (.ping jedis))
  ;;=> "PONG"

  (with-open [jedis (client :redis-1)]
    (.ping jedis))
  ;;=> "PONG"

  (with-open [pool  (create-sentinel-pool)]
    (with-pooled-client [jedis pool]
      (set jedis "foo" "fofo")
      (get jedis "foo")))
  ;;=> "fofo"
  ;
  )

;;
;; Pub/sub:
;;


(defrecord SubscriptionInst [^JedisPubSub pubsub channel-name ^Future fut] 
  java.io.Closeable
  (close [_]
    (try (.unsubscribe pubsub) (catch Exception _))
    (.cancel fut true))

  clojure.lang.IDeref
  (deref [_] (deref fut))

  clojure.lang.IBlockingDeref
  (deref [_ timeout timeout-value] (deref fut timeout timeout-value))

  clojure.lang.IPending
  (isRealized [_] (realized? fut))
  
  java.lang.Object
  (toString [_] (str "SubscriptionInst[channel=" channel-name ",active=" (not (.isDone fut)) "]")))


(defn- subscription-handler [^Pool pool channel ^JedisPubSub pubsub]
  (let [^String/1 channel (into-array String [(name channel)])]
    (fn []
      (try
        (with-pooled-client [client pool]
          (.subscribe client pubsub channel))
        true
        (catch Exception _
          false)))))


(defn subscribe
  (^SubscriptionInst [^Pool pool channel on-message] (subscribe pool channel on-message nil))
  (^SubscriptionInst [^Pool pool channel on-message {:keys [subscription-retry-delay]
                                                     :or {subscription-retry-delay 1000}}]
   (let [pubsub                (proxy [JedisPubSub] []
                                 (onMessage [ch message]
                                   (on-message message)))
         handle-subscription (subscription-handler pool channel pubsub)
         fut (future
               (while (not (or (.isClosed pool)
                               (Thread/interrupted)
                               (handle-subscription)))
                 (Thread/sleep ^long subscription-retry-delay)))]
     (->SubscriptionInst pubsub
                         channel
                         fut))))


(defn publish [^Pool pool channel message]
  (with-retries
    {:retry-timeout 5000
     :retry-delay (fn [_ retry-delay] (* 4 (inc retry-delay)))}
    (with-pooled-client [client pool]
      (.publish client
                (name channel)
                (str message)))))


;;
;; Redis returns information fields, like replication info in strings. These parse them to
;; better format:
;;


(defn- parse-replication-info [info]
  (->> (str/split-lines info)
       (keep (partial re-matches #"^([a-z0-9_]+):(\S+)"))
       (reduce (fn [acc [_ k v]]
                 (if-let [[ks v] (condp re-matches k
                                   #"role" [[:role] (keyword v)]
                                   #"connected_slaves" [[:connected-slaves] (parse-long v)]
                                   #"slave(\d+)" :>> (fn [[_ slave-num]]
                                                       [[:slave (parse-long slave-num)]
                                                        (->> (str/split v #",")
                                                             (map #(str/split % #"="))
                                                             (keep (fn [[k v]]
                                                                     (case k
                                                                       "ip" [:host v]
                                                                       "port" [:port (parse-long v)]
                                                                       "state" [:state (keyword v)]
                                                                       nil)))
                                                             (into {}))])
                                   #"master_host" [[:master-host] v]
                                   #"master_port" [[:master-port] (parse-long v)]
                                   #"master_link_status" [[:master-link-status] (keyword v)]
                                   #"slave_read_only" [[:read-only] (= v "1")]
                                   nil)]
                   (assoc-in acc ks v)
                   acc))
               {})))


(defn replication-info [^Jedis jedis]
  (->> (.info jedis "replication")
       (parse-replication-info)))


(comment 
  (with-open [c (client :redis-1)]
    (replication-info c))
  ;;=> {:role             :master
  ;;    :connected-slaves 1
  ;;    :slave            {0 {:host  "172.30.42.13"
  ;;                          :port  6379
  ;;                          :state :online}}}

  (with-open [c (client :redis-2)]
    (replication-info c))
  ;;=> {:role               :slave
  ;;    :master-host        "172.30.42.11"
  ;;    :master-port        6379
  ;;    :master-link-status :up
  ;;    :read-only          true
  ;;    :connected-slaves   0} 
  )


;;
;; Locating master:
;;


(defn current-redis-master []
  (->> [:redis-1 :redis-2]
       (some (fn [id]
               (with-open [^Jedis c (client id)]
                 (when (-> (replication-info c) :role (= :master))
                   id))))))

(comment
  (current-redis-master)
  ;;=> :redis-1
  ;
  )

