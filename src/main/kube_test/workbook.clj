(ns kube-test.workbook
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [kube-test.datetime :refer [humanize-datetime]]
            [kube-test.http :as http]
            [kube-test.kube :as k]
            [kube-test.process :as p]
            [kube-test.jedis :as jedis :refer [with-jedis]])
  (:import (redis.clients.jedis Jedis)))


(set! *warn-on-reflection* true)


(comment
  (def ctx {:context   "test:eu-west-1"
            :namespace "review-3789"})
  
  (def api (k/kube-api ctx)) 
  )


(defn redis-sentinel-pods [api]
  (k/pods api (->> (k/statefulset api "redis-sentinel-node")
                   :spec
                   :selector
                   :matchLabels)))


(comment
  (->> (redis-sentinel-pods api)
       (map (comp :name :metadata)))
  ;;=> ("redis-sentinel-node-0" "redis-sentinel-node-1" "redis-sentinel-node-2")
  )


(defn redis-sentinel-pods-states [api]
  (->> (redis-sentinel-pods api) 
       (map (fn [pod]
              {:name       (-> pod :metadata :name)
               :phase      (-> pod :status :phase)
               :conditions (->> pod :status :conditions
                                (sort-by :lastTransitionTime)
                                (mapv (juxt :type (comp humanize-datetime :lastTransitionTime))))}))))


(comment
  (redis-sentinel-pods-states api)
  ;;=> ({:name       "redis-sentinel-node-0"
  ;;     :phase      "Running"
  ;;     :conditions [["Initialized" "2025/02/10 17:57:38"] 
  ;;                  ["PodScheduled" "2025/02/10 17:57:38"] 
  ;;                  ["Ready" "2025/02/10 17:58:00"] 
  ;;                  ["ContainersReady" "2025/02/10 17:58:00"]]}
  ;;    {:name       "redis-sentinel-node-1"
  ;;     :phase      "Running"
  ;;     :conditions [["Initialized" "2025/02/14 16:17:42"] 
  ;;                  ["PodScheduled" "2025/02/14 16:17:42"] 
  ;;                  ["Ready" "2025/02/14 16:34:37"] 
  ;;                  ["ContainersReady" "2025/02/14 16:34:37"]]}
  ;;    {:name       "redis-sentinel-node-2"
  ;;     :phase      "Running"
  ;;     :conditions [["Initialized" "2025/02/10 20:21:32"] 
  ;;                  ["PodScheduled" "2025/02/10 20:21:32"] 
  ;;                  ["Ready" "2025/02/10 20:36:36"] 
  ;;                  ["ContainersReady" "2025/02/10 20:36:36"]]})
  )


(comment
  (def redis-password (-> (k/secret api :redis-sentinel)
                          :redis-password))
  ;
  )


(defn simplify-sentinel-pod-name [pod-name]
  (-> (re-matches #"redis-sentinel-(node-\d+)" pod-name)
      (second)
      (keyword)))


(defn start-redis-port-mappings [api]
  (->> (redis-sentinel-pods api)
       (map (comp :name :metadata))
       (reduce (fn [mappings pod-name]
                 (let [port-forward  (k/kube-port-forward ctx pod-name jedis/redis-pod-ports)
                       port-mappings (k/get-local-ports port-forward)]
                   (assoc mappings
                          (simplify-sentinel-pod-name pod-name)
                          (assoc port-mappings :port-forward port-forward))))
               {})))


(defn close-redis-port-mappings [port-mappings]
  (doseq [port-forward (->> port-mappings
                            (vals)
                            (map :port-forward))]
    (p/destroy port-forward)))


(comment
  (def redis-port-mappings (start-redis-port-mappings api))

  redis-port-mappings

  (def jedis-factory (jedis/client-factory redis-port-mappings 
                                           redis-password))

  (with-jedis [j (jedis-factory :node-0 :redis)]
    (.ping j))
  ;;=> "PONG"
  

  (with-jedis [j (jedis-factory :node-0 :redis)]
    (jedis/replication-info j))
  ;;=> {:role             :master
  ;;    :connected-slaves 2
  ;;    :slave            {0 {:host  "redis-sentinel-node-2.redis-sentinel-headless.review-3789.svc.cluster.local"
  ;;                          :port  6379
  ;;                          :state :online}
  ;;                       1 {:host  "redis-sentinel-node-1.redis-sentinel-headless.review-3789.svc.cluster.local"
  ;;                          :port  6379
  ;;                          :state :online}}}

  
  (with-jedis [j (jedis-factory :node-1 :redis)]
    (jedis/replication-info j))
  ;;=> {:role               :slave
  ;;    :master-host        "redis-sentinel-node-0.redis-sentinel-headless.review-3789.svc.cluster.local"
  ;;    :master-port        6379
  ;;    :master-link-status :up
  ;;    :read-only          true
  ;;    :connected-slaves   0}
  
  (with-jedis [j (jedis-factory :node-0 :redis)]
    (.shutdown j))
  )


(comment 

  ;;
  ;; ===============================================================
  ;; Cleanup:
  ;; ===============================================================
  ;;
  
  ;; Remember to close port forwards:
  (close-redis-port-mappings redis-port-mappings) 
  
  ;; Close the API proxy
  (k/close-kube-api api)
  ;
  )
