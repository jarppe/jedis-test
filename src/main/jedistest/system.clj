(ns jedistest.system
  (:require [clojure.string :as str])
  (:import (java.util.function Supplier)
           (java.util.concurrent Executor
                                 Executors
                                 CompletableFuture)
           (redis.clients.jedis Jedis 
                                JedisSentinelPool
                                JedisPubSub)
           (redis.clients.jedis.util Pool)
           (com.github.dockerjava.api DockerClient)
           (com.github.dockerjava.api.model Container
                                            ContainerNetwork)
           (com.github.dockerjava.core DockerClientImpl
                                       DefaultDockerClientConfig)
           (com.github.dockerjava.httpclient5 ApacheDockerHttpClient$Builder)))


(set! *warn-on-reflection* true)


(defonce ^DockerClient client (DockerClientImpl/getInstance (-> (DefaultDockerClientConfig/createDefaultConfigBuilder)
                                                                (.withDockerHost "unix:///var/run/docker.sock")
                                                                (.build))
                                                            (-> (ApacheDockerHttpClient$Builder.)
                                                                (.dockerHost (java.net.URI. "unix:///var/run/docker.sock"))
                                                                (.build))))


;; Open Jedis pool, re-create on every reload:

(def ^JedisSentinelPool pool nil)
(alter-var-root #'pool (fn [^JedisSentinelPool pool]
                         (when pool
                           (.close pool))
                         (JedisSentinelPool. "mymaster"
                                             ;;
                                             #{"redis-sentinel-1:26379"
                                               "redis-sentinel-2:26379"
                                               "redis-sentinel-3:26379"}
                                             ;; local setup
                                             #_
                                             #{"redis-sentinel-1:26379"
                                               "redis-sentinel-2:26379"
                                               "redis-sentinel-3:26379"})))


;;
;; Containers:
;;


(defn containers []
  (filter (fn [^Container container]
            (->> container
                 (.getNames)
                 (first)
                 (re-matches #"^\/jedis-test-(.*)-1$")))
          (-> client
              (.listContainersCmd)
              (.withShowAll true)
              (.exec))))


(defn container-id [^Container container]
  (-> container
      (.getNames)
      (first)
      (subs (count "/jedis-test-"))
      (keyword)))


(defn container-state [^Container container]
  (->> container
       (.getState)
       (keyword)))


(defn container-ip [^Container container]
  (->> container
       (.getNetworkSettings)
       (.getNetworks)
       (.values)
       (first)
       ContainerNetwork/.getIpAddress))


(comment
  (map (juxt container-id container-state container-ip) (containers))
  ;;=> ([:devcontainer-1      :running  "172.30.0.30"] 
  ;;    [:redis-1-1           :running  "172.30.0.11"] 
  ;;    [:redis-2-1           :exited   ""]
  ;;    [:redis-sentinel-3-1  :running  "172.30.0.23"] 
  ;;    [:redis-sentinel-1-1  :running  "172.30.0.21"] 
  ;;    [:redis-sentinel-2-1  :running  "172.30.0.22"]) 
  )

;;
;; Helpers:
;;


(defn replication-info [redis-id]
  (->> (with-open [^Jedis c (Jedis. (name redis-id) 6379)]
         (.info c "replication"))
       (str/split-lines)
       (keep (fn [line]
               (when-let [[_ k v] (re-matches #"([^:]+):(.*)" line)]
                 [(-> k (str/replace "_" "-") (keyword)) v])))
       (into {})))


(defn current-redis-master ^Container [^JedisSentinelPool pool]
  (let [master-ip (-> (.getCurrentHostMaster pool)
                      (.getHost))]
    (some (fn [^Container container]
            (when (= (container-ip container) master-ip)
              container))
          (containers))))


(defn kill-the-master! [^JedisSentinelPool pool]
  (let [^Container master-container (-> (current-redis-master pool)
                                        (containers))]
    (-> (.stopContainerCmd client (.getId master-container))
        (.exec))))

(kill-the-master! pool)
(-> client
    (.listContainersCmd)
    (.withShowAll true)
    (.exec)
    (->> (map (juxt (comp first Container/.getNames) Container/.getState)))
    #_(reduce (fn [acc ^Container container]
                (let [[_ cname] (->> container (.getNames) (first) (re-matches #"^\/jedis-test-(.*)-1$"))]
                  (if cname
                    (assoc acc (keyword cname) container)
                    acc)))
              {}))

(->> containers
     (vals)
     (map (fn [^Container container]
               (.getState container))))

(defn all-up []
  (doseq [^Container container (->> containers
                                    (vals)
                                    (filter (fn [^Container container]
                                              (.getStatus container))))]
    (-> (.startContainerCmd client (.getId container))
        (.exec))))


(comment
  (replication-info :redis-1)
  ;;=> {:role                           "master"
  ;;    :master-replid                  "5d50fc93ee2c0c114382c71eb75557c555158884"
  ;;    :connected-slaves               "1"
  ;;    :repl-backlog-size              "1048576"
  ;;    :second-repl-offset             "-1"
  ;;    :repl-backlog-histlen           "65806"
  ;;    :master-replid2                 "0000000000000000000000000000000000000000"
  ;;    :repl-backlog-active            "1"
  ;;    :slave0                         "ip=172.30.0.12,port=6379,state=online,offset=65669,lag=0"
  ;;    :master-failover-state          "no-failover"
  ;;    :master-repl-offset             "65806"
  ;;    :repl-backlog-first-byte-offset "1"}
  )


;;
;; Tests:
;;


(comment

  ;;
  ;; Check that all containers are up and running:
  ;;

  (->> [:redis-1 :redis-2 :redis-sentinel-1 :redis-sentinel-2 :redis-sentinel-3]
       (map containers)
       (map Container/.getState)
       (every? #{"running"}))
  ;;=> true

  ;; Make a pool:

  (def ^JedisSentinelPool pool (JedisSentinelPool. "mymaster" #{"redis-sentinel-1:26379"
                                                                "redis-sentinel-2:26379"
                                                                "redis-sentinel-3:26379"}))

  ;; We don't know which is master without asking:

  (def master (current-redis-master pool))
  (def slave ({:redis-1 :redis-2
               :redis-2 :redis-1}
              master))

  ;; Note that these might be different on your system. The important this is that one is
  ;; master, and another is slave. Which ever way around works for us.

  master
  ;;=> :redis-1
  slave
  ;;=> :redis-2

  ;;
  ;; Verify that redis-1 and redis-2 are in replication:
  ;;

  (-> (replication-info master)
      (select-keys [:role :connected-slaves])
      (= {:role             "master"
          :connected-slaves "1"}))
  ;;=> true

  (-> (replication-info slave)
      (select-keys [:role :connected-slaves])
      (= {:role             "slave"
          :connected-slaves "0"}))
  ;;=> true

  ;; Write to master, read from slave:
  (let [value  (str (gensym))]
    (with-open [^Jedis c (Jedis. (name master) 6379)]
      (.set c "foo" value))
    (= (with-open [^Jedis c (Jedis. (name slave) 6379)]
         (.get c "foo"))
       value))
  ;=> true

  ;;
  ;; Verify: pool works:
  ;; 

  (= (with-open [^Jedis c (.getResource pool)]
       (.ping c))
     "PONG")
  ;;=> true

  ;;
  ;; Stop master redis:
  ;;

  (let [master-container-id (-> (containers master) (.getId))]
    (-> (.stopContainerCmd client master-container-id)
        (.exec)))

  ;;
  ;; Verify: pool still works:
  ;;

  ;; old slave is now promoted as master:
  (= (-> (.getCurrentHostMaster pool)
         (.getHost)
         containers-by-ip)
     slave)

  ;; pool works with new master
  (= (with-open [^Jedis c (.getResource pool)]
       (.set c "foo" "baba"))
     "OK")
  ;;=> true

  ;; Slave replication info shows it as master and number of slaves is 0
  (-> (replication-info slave)
      (select-keys [:role :connected-slaves])
      (= {:role             "master"
          :connected-slaves "0"}))
  ;;=> true 

  ;; Start old master container:

  (-> client
      (.startContainerCmd (-> (containers master) (.getId)))
      (.exec))

  ;; Verify that it's listed in new master:
  ;; - note, it might take few seconds before "connected-slaves" becomes 1

  (-> (replication-info slave)
      (select-keys [:role :connected-slaves])
      (= {:role             "master"
          :connected-slaves "1"}))
  ;;=> true

  ;; Clenaup
  (.close pool)

  ;
  )

;;
;; How pub/sub works on DB failovers?
;; ==================================
;;


(defonce executor (Executors/newVirtualThreadPerTaskExecutor))


(defn async ^CompletableFuture [^Executor executor ^Supplier task]
  (CompletableFuture/supplyAsync task executor))


(defn debug [& message]
  (.println System/err (apply str message)))


(defn to-string-arr ^String/1  [v]
  (into-array String [(name v)]))


(defn process-subscribe [^Pool pool ^JedisPubSub pubsub ^String/1 channel]
  (try
    (debug "subscribe: connect...")
    (with-open [^Jedis client (.getResource pool)]
      (debug "subscribe: subscribe...")
      (.subscribe client pubsub channel))
    (debug "subscribe: closed")
    true
    (catch Exception e
      (debug "subscribe: failed: " (-> e (.getClass) (.getName)) ": " (-> e (.getMessage)))
      false)))


(defn subscribe ^java.io.Closeable [^Pool pool channel on-message]
  (let [pubsub (proxy [JedisPubSub] []
                 (onMessage [ch message]
                   (on-message message)))
        channel (to-string-arr channel)
        fut    (async executor (fn on-message [] 
                                 (debug "on-message: subscribing...")
                                 (loop [] 
                                   (when-not (process-subscribe pool pubsub channel)
                                     (debug "on-message: failed, retry in 1 sec...")
                                     (Thread/sleep 1000)
                                     (recur)))
                                 (debug "on-message: subscribing...")))]
     (reify java.io.Closeable
       (close [_]
         (.unsubscribe pubsub)
         (.get fut)))))


(defn publish [^Pool pool channel message]
  (with-open [^Jedis client (.getResource pool)]
    (.publish client (name channel) (str message))))


(comment

  (def s (subscribe pool :hello-world (fn [message] (debug "on-message: " (pr-str message)))))
  ;; stdout:
  ;; subscribe: open client
  ;; subscribe: subscribe

  (publish pool :hello-world "Hi")
  ;=> 1
  ;; stdout:
  ;; on-message: "Hi"

  (let [master-container-id (-> (current-redis-master pool)
                                (containers)
                                (.getId))]
    (-> (.stopContainerCmd client master-container-id)
        (.exec)))
  ;; stdout:
  ;;  subscribe: failed: redis.clients.jedis.exceptions.JedisConnectionException: Failed to connect to any host resolved for DNS name.
  ;;  on-message: failed, retry in 1 sec...
  ;;  subscribe: connect...
  ;;  subscribe: subscribe...
  
  (publish pool :hello-world "Hi")
  ;=> 1

  (all-up)

  ;; 
  (.close s)
  )