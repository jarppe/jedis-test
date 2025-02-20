(ns perf.workbook
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [perf.datetime :refer [humanize-datetime]]
            [perf.http :as http]
            [perf.kube :as k]
            [perf.process :as p]
            [perf.jedis :as j]
            [perf.retry :refer [with-retries]])
  (:import (redis.clients.jedis Jedis)))


(set! *warn-on-reflection* true)
(set! *print-namespace-maps* false)


;; ctx-1 is the Carmine -> Jedis MR

(def ctx-1 {:context   "test:eu-west-1"
            :namespace "review-3789"})


(comment
  (def api-1 (k/kube-api ctx-1))
  ;
  )


;;
;; Sentinel stateful set:
;;


(comment
  (->> (k/statefulset api-1 "redis-sentinel-node")
       :metadata
       :name)
  ;;=> "redis-sentinel-node" 

  (->> (k/statefulset api-1 "redis-sentinel-node")
       :spec
       :selector
       :matchLabels)
  ;;=> {:app.kubernetes.io/instance  "redis-sentinel"
  ;;    :app.kubernetes.io/component "node"
  ;;    :app.kubernetes.io/name      "redis"}
  )

;;
;; Sentinel pods:
;;

(defn redis-sentinel-pods [api]
  (k/pods api (->> (k/statefulset api "redis-sentinel-node")
                   :spec
                   :selector
                   :matchLabels)))


(comment
  (->> (redis-sentinel-pods api-1)
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
  (redis-sentinel-pods-states api-1)
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
  (def redis-password (-> (k/secret api-1 :redis-sentinel)
                          :redis-password))
  ;
  )


;;
;; Return a map from Redis pod name to port mappings, where port mappings is a map of remote port 
;; to local port.
;;
;; The port mappings map also contains a key :port-forward with value do port forward kubectl
;; process. We use this to shutdown port mappings.
;;
;; The Redis instances are located in same pod as the sentinels, so we search for sentinel pods and
;; open port mappings into those pods but using Redis port.
;;


(defn start-redis-port-mappings [api]
  (->> (redis-sentinel-pods api)
       (map (comp :name :metadata))
       (reduce (fn [mappings pod-name]
                 (let [port-forward  (k/kube-port-forward (:ctx api) pod-name [j/redis-port])
                       port-mappings (k/get-local-ports port-forward)]
                   (assoc mappings
                          pod-name
                          (assoc port-mappings :port-forward port-forward))))
               {})))


(defn close-redis-port-mappings [port-mappings]
  (doseq [port-forward (->> port-mappings
                            (vals)
                            (map :port-forward))]
    (p/destroy port-forward)))


(comment
  (def redis-port-mappings (start-redis-port-mappings api-1))

  redis-port-mappings
  ;;=> {"redis-sentinel-node-0" {6379 65453
  ;;                             :port-forward Process[pid=71004, exitValue="not exited"]}
  ;;    "redis-sentinel-node-1" {6379 65458
  ;;                             :port-forward Process[pid=71015, exitValue="not exited"]}
  ;;    "redis-sentinel-node-2" {6379 65463
  ;;                             :port-forward Process[pid=71017, exitValue="not exited"]}}
  )


;;
;; Connecting to Redis DB's using the port mappings.
;;


(comment
  (defn client-factory [port-mappings redis-password]
    (fn [pod-name]
      (let [local-port (-> port-mappings (get pod-name) (get j/redis-port))]
        (with-retries
          {:retry-timeout 2000
           :retry-delay   100}
          (doto (j/client "localhost" local-port)
            (.auth redis-password))))))

  (def jedis-factory (client-factory redis-port-mappings
                                     redis-password))


  (with-open [^Jedis j (jedis-factory "redis-sentinel-node-0")]
    (.ping j))
  ;;=> "PONG"

  (with-open [^Jedis j (jedis-factory "redis-sentinel-node-0")]
    (println (.info j)))

  (with-open [^Jedis j (jedis-factory "redis-sentinel-node-0")]
    (j/replication-info j))
  ;;=> {:role               :slave
  ;;    :master-host        "redis-sentinel-node-1.redis-sentinel-headless.review-3789.svc.cluster.local"
  ;;    :master-port        6379
  ;;    :master-link-status :up
  ;;    :read-only          true
  ;;    :connected-slaves   0}

  (with-open [^Jedis j (jedis-factory "redis-sentinel-node-1")]
    (j/replication-info j))
  ;;=> {:role             :master
  ;;    :connected-slaves 2
  ;;    :slave            {0 {:host  "redis-sentinel-node-2.redis-sentinel-headless.review-3789.svc.cluster.local"
  ;;                          :port  6379
  ;;                          :state :online}
  ;;                       1 {:host  "redis-sentinel-node-0.redis-sentinel-headless.review-3789.svc.cluster.local"
  ;;                          :port  6379
  ;;                          :state :online}}}
  )


;;
;; Find the pod name of the current master:
;;


(defn get-redis-master-pod-name []
  (->> (range 3)
       (map (fn [i] (str "redis-sentinel-node-" i)))
       (some (fn [pod-name]
               (try
                 (with-open [^Jedis client (jedis-factory pod-name)]
                   (when (-> (j/replication-info client) :role (= :master))
                     pod-name))
                 (catch Exception _
                   nil))))))


(comment
  (get-redis-master-pod-name)
  ;;=> "redis-sentinel-node-1"
  )






(comment
  (require '[org.httpkit.client :as httpkit])
  (def resp (let [agency-id   "480"
                  campagin-id "4443"
                  v           "29743"]
              (-> (httpkit/request {:method       :post
                                    :url          (format "https://content.test.lemonpi.io/v1/a/%s/c/%s/d/v/%s/w/300/h/600"
                                                          agency-id
                                                          campagin-id
                                                          v)
                                    :query-params {"dsp-signal.creative-id" "REPLACEWITHYOURMACRO"
                                                   "dsp-signal.line-id"     "SW1"
                                                   "dsp-signal.site-url"    "REPLACEWITHYOURMACRO"
                                                   "dsp-signal.postcode"    "SN1"
                                                   "impression-id"          "918fe5d3-abf8-400e-938d-3b9abe898a02"}
                                    :headers      {"origin"             "https://testatag.com"
                                                   "priority"           "u=1, i"
                                                   "sec-fetch-site"     "cross-site"
                                                   "sec-ch-ua-mobile"   "?0"
                                                   "content-type"       "application/json"
                                                   "sec-ch-ua"          "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\""
                                                   "sec-ch-ua-platform" "\"Linux\""
                                                   "referer"            "https://testatag.com/"
                                                   "pragma"             "no-cache"
                                                   "accept"             "*/*"
                                                   "accept-language"    "en-US,en;q=0.9"
                                                   "sec-fetch-dest"     "empty"
                                                   "sec-fetch-mode"     "cors"
                                                   "cache-control"      "no-cache"}
                                    :body         (json/write-value-as-string {"context" {"$request"   {"timestamp"          "2025-02-04T10:33:56+02:00"
                                                                                                        "request-cookies"    "1y8mH1fIgQ4QBtrxEJueA1Ce8N1fW+fBQpzd4qiH4CU="
                                                                                                        "source-url"         "https://www.tomshardware.com/tech-industry/artificial-intelligence/nvidia-counters-amd-deepseek-benchmarks-claims-rtx-4090-is-nearly-50-percent-faster-than-7900-xtx"
                                                                                                        "environment"        "web"
                                                                                                        "dsp"                nil
                                                                                                        "adserver"           "lemonpi"
                                                                                                        "encoded-user-agent" "\"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36\""}
                                                                                          "dsp-signal" {"creative-id" "REPLACEWITHYOURMACRO"
                                                                                                        "line-id"     "REPLACEWITHYOURMACRO"
                                                                                                        "site-url"    "REPLACEWITHYOURMACRO"
                                                                                                        "postcode"    "SW1"}}})
                                    :as           :stream})
                  (deref))))
  (-> resp :status)
  ;;=> 200
  (-> resp :headers :content-type)
  ;;=> "application/json; charset=utf-8"
  (-> resp :body (json/read-value json/keyword-keys-object-mapper))
  ;;=> {:content  {:title         {:value "حصان"
  ;;                               :type  "text"}
  ;;               :landing_click {:value "https://www.google.com"
  ;;                               :type  "click"}
  ;;               :leader        {:value "من الأندلس"
  ;;                               :type  "text"}
  ;;               :more_info     {:value "توني المهر"
  ;;                               :type  "text"}
  ;;               :product_image {:value "https://assets-test.lemonpi.io/a/361/5ea2128b725a824b1d8a3ed6214594cc.jpg"
  ;;                               :type  "image"}}
  ;;    :template {:base-url    "https://templates.test.lemonpi.io/a480/4096/1/b743d00034d64103a1039cca144ff018"
  ;;               :id          6777
  ;;               :template-id 4096}}
  ;
  )


(comment
  (->> "
--header 'accept: */*'
--header 'accept-language: en-US,en;q=0.9'
--header 'cache-control: no-cache'
--header 'content-type: application/json'
--header 'origin: https://testatag.com'
--header 'pragma: no-cache'
--header 'priority: u=1, i'
--header 'referer: https://testatag.com/'
--header 'sec-ch-ua: \"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\"'
--header 'sec-ch-ua-mobile: ?0'
--header 'sec-ch-ua-platform: \"Linux\"'
--header 'sec-fetch-dest: empty'
--header 'sec-fetch-mode: cors'
--header 'sec-fetch-site: cross-site'
--header 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36' 
   "
       (str/split-lines)
       (keep (fn [s] (re-matches #"\s*--header\s+'([^:]+):\s+([^']+)'" s)))
       (reduce (fn [acc [_ k v]]
                 (assoc acc k v))
               {})))

;;
;; https://content.test.lemonpi.io/v1/a/480/c/4443/d/v/29743/w/300/h/600
;;  dsp-signal.creative-id==REPLACEWITHYOURMACRO 
;;  dsp-signal.line-id==SW1 
;;  dsp-signal.site-url==REPLACEWITHYOURMACRO 
;;  dsp-signal.postcode==SN1  
;;  impression-id=918fe5d3-abf8-400e-938d-3b9abe898a02
;;
;; this will exercise (at least) the following caches:
;;  * feching adset info from interaction handler
;;  * ias caching (this should work as otherwise this may have a bill impact :scream: - we would know by now)
;;  * fetching [cached] feed in content lookup
;;  * fetching [cached] content in content lookup
;;  * there is some dynamic feed caching which (I think) we are also using
;;



;;
;; ===============================================================
;; Cleanup:
;; ===============================================================
;;

(comment
  ;; Remember to close port forwards:
  (close-redis-port-mappings redis-port-mappings)

  ;; Close the API proxy
  (k/close-kube-api api-1)
  ;
  )
