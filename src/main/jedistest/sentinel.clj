(ns jedistest.sentinel
  (:require [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [jsonista.core :as json])
  (:import (redis.clients.jedis Jedis)))


(def env "review-3789")


(set! *warn-on-reflection* true)


(defn pipe [^java.io.InputStream stream ^java.util.concurrent.Executor executor stream-name pod]
  (.execute executor (fn []
                       (doseq [line (->> stream
                                         (io/reader)
                                         (line-seq))]
                         (.println System/err (format "%s (%s): %s" pod (name stream-name) line))
                         (.flush System/err)))))

; redis-sentinel-node-0.redis-sentinel-headless.review-3789.svc.cluster.local:6379

(def port-forwards [["redis-sentinel-node-0" 26001 26002]
                    ["redis-sentinel-node-1" 26011 26012]
                    ["redis-sentinel-node-2" 26021 26022]])


(def port-mapping (->> port-forwards
                       (mapcat (fn [[pod sentinel-port redis-port]]
                                 [[(str pod ".redis-sentinel-headless." env ".svc.cluster.local:26379") sentinel-port]
                                  [(str pod ".redis-sentinel-headless." env ".svc.cluster.local:6379") redis-port]]))
                       (concat (->> port-forwards
                                    (map second)
                                    (map-indexed vector)))
                       (into {})))


(defn start-port-forwards []
  (let [exit      (promise)
        executor  (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
        processes (mapv (fn [[pod sentinel-port redis-port]]
                          (let [^java.util.List args ["kubectl" "port-forward"
                                                      "--address" "127.0.0.1"
                                                      pod
                                                      (format "%d:6379" redis-port)
                                                      (format "%d:26379" sentinel-port)]]
                            (doto (-> (ProcessBuilder. args)
                                      (.start))
                              (-> (.getInputStream) (pipe executor :out pod))
                              (-> (.getErrorStream) (pipe executor :err pod))
                              (-> (.onExit) (.thenApply (fn [_] (deliver exit pod)))))))
                        port-forwards)]
    (.execute executor (fn []
                         (let [reason @exit]
                           (.println System/out (str "exit reason: " reason))
                           (doseq [^Process p processes]
                             (.destroy p))
                           (.shutdownNow executor))))
    (fn []
      (deliver exit "normal"))))


(defonce redis-password (let [decoder (java.util.Base64/getDecoder)
                              decode  (fn [^String s] (.decode decoder s))]
                          (-> (sh "kubectl" "get" "secret" "redis-sentinel" "-o" "jsonpath={.data}")
                              :out
                              (json/read-value)
                              (get "redis-password")
                              (decode)
                              (str)
                              (String. java.nio.charset.StandardCharsets/UTF_8))))


(defn sentinel ^Jedis [host]
  (doto (Jedis. "localhost" (port-mapping host))
    (.auth redis-password)))


(defn redis ^Jedis [host]
  (doto (Jedis. "localhost" (port-mapping host))
    (.auth redis-password)))


(comment

  (def exit (start-port-forwards))

  (let [info (with-open [^Jedis c (sentinel 0)]
               (.sentinelMaster c "mymaster"))]
    (str (.get info "ip") ":" (.get info "port")))
  ;;=> "redis-sentinel-node-0.redis-sentinel-headless.review-3789.svc.cluster.local:6379"

  (let [master-info (with-open [^Jedis c (sentinel 0)]
                      (.sentinelMaster c "mymaster"))
        master-host (str (.get master-info "ip")
                         ":"
                         (.get master-info "port"))]
    (->> (with-open [^Jedis c (redis master-host)]
           (.info c "replication"))
         (str/split-lines)
         (keep (fn [line]
                 (when-let [[_ k v] (re-matches #"([^:]+):(.*)" line)]
                   [(-> k (str/replace "_" "-") (keyword)) v])))
         (into {})))
  ;;=> 
  {:role                           "master"
   :master-replid                  "cde148ea663cb1819423d58e212a04f7a479af3a"
   :connected-slaves               "2"
   :repl-backlog-size              "1048576"
   :second-repl-offset             "-1"
   :slave1                         "ip=redis-sentinel-node-2.redis-sentinel-headless.review-3789.svc.cluster.local,port=6379,state=online,offset=59209972,lag=1"
   :repl-backlog-histlen           "1058460"
   :master-replid2                 "0000000000000000000000000000000000000000"
   :repl-backlog-active            "1"
   :slave0                         "ip=redis-sentinel-node-1.redis-sentinel-headless.review-3789.svc.cluster.local,port=6379,state=online,offset=59210025,lag=1"
   :master-failover-state          "no-failover"
   :master-repl-offset             "59210260"
   :repl-backlog-first-byte-offset "58151801"}

  (exit)

  ;
  )


