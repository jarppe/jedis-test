(ns kube-test.jedis
  (:import (redis.clients.jedis Jedis)) 
  (:require
    [clojure.string :as str]))


(set! *warn-on-reflection* true)


(def redis-port 6379)
(def sentinel-port 26379)
(def redis-pod-ports [redis-port sentinel-port])


(def redis-type-port {:redis    redis-port
                      :sentinel sentinel-port})


(defn client ^Jedis [port password]
  (doto (Jedis. "localhost" (int port))
    (.auth password)))


(defn client-factory [redis-pod-port-mappings redis-password]
  (fn [node-id type]
    (let [port (-> redis-pod-port-mappings
                   node-id
                   (get (redis-type-port type)))]
      (client port redis-password))))


(defmacro with-jedis
  [[bind form] & body]
  `(let [~(vary-meta bind assoc :tag `Jedis) ~form]
     (try
       ~@body
       (finally
         (.close ~bind)))))


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


(defn replication-info [^Jedis j]
  (->> (.info j "replication") 
       (parse-replication-info)))
