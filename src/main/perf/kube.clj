(ns perf.kube
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [perf.http :as http]
            [perf.process :as p]))


(set! *warn-on-reflection* true)


;;
;; =========================================================================
;; Utility to create "kubectl" processes cmd:
;; =========================================================================
;;


(defn- kubectl-cmd
  ([kubectl-command kubectl-opts] (kubectl-cmd kubectl-command kubectl-opts nil))
  ([kubectl-command kubectl-opts kubectl-args]
   (concat ["kubectl" (name kubectl-command)]
           (->> kubectl-opts
                (map (fn [[param-name param-value]]
                       (let [param-name (str "--" (name param-name))]
                         (cond
                          ;; Flag: e.g. [:quiet true] => --quiet
                           (true? param-value) param-name
                          ;; Map: e.g. [:labels {:foo 1 :bar 2}] => --labels=foo=1,bar=2
                           (map? param-value) (str param-name "=" (->> param-value
                                                                       (map (fn [[k v]]
                                                                              (str (name k) "=" v)))
                                                                       (str/join ",")))
                          ;; Others: e.g. [:restart "Never"] => --restart=Never
                           :else (str param-name "=" param-value))))))
           kubectl-args)))


;;
;; Helpers to extract kubectl output and handle errors:
;;


(defn- input-stream-line-seq [^java.io.InputStream in]
  (lazy-seq
   (let [[eof line] (loop [line (StringBuilder.)]
                      (let [c   (.read in)
                            eof (= c -1)
                            nl  (= c (int \newline))]
                        (if (or eof nl)
                          [eof (.toString line)]
                          (-> line
                              (.append (Character/toString c))
                              (recur)))))]
     (cons line
           (when-not eof
             (input-stream-line-seq in))))))


(defn- on-exit
  ([proc command] (on-exit proc command nil))
  ([proc command on-exit-callback]
   (future
     (let [error-output (->> proc
                             :err
                             (input-stream-line-seq)
                             (str/join "\n"))
           exit-code    @proc]
       (when-not (zero? exit-code)
         (.println System/err (format "\nERROR: kubectl %s: exit = %d" (name command) exit-code))
         (.println System/err error-output)
         (.println System/err))
       (when on-exit-callback
         (on-exit-callback exit-code))))
   proc))


;;
;; =========================================================================
;; Run process in cluster with kubectl run:
;; =========================================================================
;;


(def ^:private simple-timestamp
  (let [formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")]
    (fn []
      (-> (java.time.LocalDateTime/now)
          (.format formatter)))))


(defn- temp-pod-name []
  (str "testing"
       "-"
       (System/getProperty "user.name")
       "-"
       (simple-timestamp)))


(def kubectl-run-defaults {:rm        true
                           :stdin     true
                           :quiet     true
                           :restart   "Never"
                           :labels    {:app "testing"}
                           :image     "633143897132.dkr.ecr.eu-west-1.amazonaws.com/tools:d52626b2"
                           :namespace "review-3789"})


(defn kubectl-run
  "Behave like `kube-test.process/process` executes the command in cluster 
   by wrapping the command execution into `kubectl run` command. Returns a 
   process, just like the `kube-test.process/process`."
  [opts]
  (let [pod-name (temp-pod-name)]
    (-> opts
        ;; Prefix cmd with "kubectl run ..."
        (update :cmd (fn [cmd]
                       (kubectl-cmd :run
                                    (-> (merge kubectl-run-defaults opts)
                                        (assoc :labels (merge (-> kubectl-run-defaults :labels)
                                                              (-> opts :labels)))
                                        (select-keys (keys kubectl-run-defaults)))
                                    (concat [pod-name "--command" "--"]
                                            cmd))))
        ;; Env moved to kubectl params, don't include it to kubectl process itself:
        (dissoc :env)
        ;; Add pod-name to process info:
        (update :info assoc :pod-name pod-name)
        ;; Start command with klubectl:  
        (p/process)
        ;; Print errors to stderr
        (on-exit :run))))


(comment
  (with-open [p (kubectl-run {:context   "test:eu-west-1"
                              :namespace "review-3789"
                              :cmd       ["curl" "-s" "http://device:5000/live"]})]
    (println "container-name:" (-> p :info :container-name))
    (doseq [line (-> p :out (io/reader) (line-seq))]
      (println "stdout:" line))
    (println "exit:" (deref p 30000 ::timeout)))
  ; container-name: jedis-test-jarppe-20250216-064631
  ; stdout: live
  ; exit: 0
  )


;;
;; =========================================================================
;; Kube port forwardning utility. Runs a "kubectl port-forward" command to 
;; create port forwards for requested target and ports.
;; 
;; Accepts a target and a list of port numbers.
;;
;; The target is for example "pod/device-5887dff964-nwnjd" or "svc/device".
;;
;; Returns a process instance with a promise of port mappings in processs 
;; `:info` field. The port mapping is a map of target-port -> local-port.
;; =========================================================================
;;


(defn kube-port-forward [opts target-name ports]
  (let [ports        (if (sequential? ports)
                       ports
                       [ports])
        port-mapping (promise)
        proc         (p/process {:cmd  (kubectl-cmd :port-forward
                                                    (update opts :address (fnil identity "127.0.0.1"))
                                                    (cons target-name
                                                          (map (partial str ":")
                                                               ports)))
                                 :info {:target-name  target-name
                                        :port-mapping port-mapping}})]
    (on-exit proc :port-forward (fn [_] (deliver port-mapping nil)))
    (future
      (->> proc
           :out
           (input-stream-line-seq)
           (keep (partial re-matches #"Forwarding from [^:]+:(\d+)\s+->\s+(\d+)"))
           (map (fn [[_ local-port remote-port]]
                  [(parse-long remote-port)
                   (parse-long local-port)]))
           (take (count ports))
           (into {})
           (deliver port-mapping)))
    proc))


(defn get-local-ports [port-forward]
  (-> port-forward :info :port-mapping (deref)))


(defn get-local-port [port-forward remote-port]
  (-> port-forward (get-local-ports) (get remote-port)))


(comment
  (with-open [port-fwd (kube-port-forward {:context   "test:eu-west-1"
                                           :namespace "review-3789"}
                                          "svc/device"
                                          [5000])]
    (get-local-port port-fwd 5000))
  ;; 63382
  )


;;
;; =========================================================================
;; Kube proxy utility. Runs a "kubectl proxy" so that we can access the
;; Kubernetes API:
;; =========================================================================
;;


(defn kube-proxy
  ([opts] (kube-proxy opts 0))
  ([opts port]
   (let [used-port (promise)
         proc      (p/process {:cmd  (kubectl-cmd :proxy (-> opts
                                                             (dissoc :namespace)
                                                             (assoc :port port)))
                               :info {:proxy-port used-port}})]
     (on-exit proc :proxy (fn [_] (deliver used-port nil)))
     (future
       (->> proc
            :out
            (input-stream-line-seq)
            (some (partial re-matches #"Starting to serve on\s+[^:]+:(\d+)"))
            (second)
            (parse-long)
            (deliver used-port)))
     proc)))


(defn kube-proxy-port [proxy]
  (-> proxy :info :proxy-port (deref)))


(comment
  (with-open [proxy (kube-proxy {:context "test:eu-west-1"})]
    (kube-proxy-port proxy))
  ;; 63382
  )


;;
;; =========================================================================
;; Kube API utils:
;; =========================================================================
;;


(defrecord KubeApiInst [proxy ctx proxy-port]
  java.io.Closeable
  (close [_] (p/destroy proxy))

  clojure.lang.IDeref
  (deref [_] (deref proxy))

  clojure.lang.IBlockingDeref
  (deref [_ timeout timeout-value] (deref proxy timeout timeout-value))

  java.lang.Object
  (toString [_] (format "KubeApi[context=%s,namespace=%s,%s]"
                        (-> ctx :context (pr-str))
                        (if-let [namespace (-> ctx :namespace)]
                          (pr-str namespace)
                          "-")
                        (if (p/alive? proxy)
                          (str "proxy-port=" proxy-port)
                          (str "proxy-terminated,exit-code:" (p/exit-code proxy))))))


(defmethod print-method KubeApiInst [v ^java.io.Writer w]
  (.write w (str v)))


;; FIXME: This is the old way, fix this:
#_(defn kube-api [ctx]
    (let [proxy      (kube-proxy ctx)
          proxy-port (kube-proxy-port proxy)]
      (->KubeApiInst proxy ctx proxy-port)))


(defn get-ssl-engine [cacert]
  (let [cert (with-open [in (io/input-stream cacert)]
               (-> (java.security.cert.CertificateFactory/getInstance "X.509")
                   (.generateCertificate in)))
        ks   (doto (-> (java.security.KeyStore/getDefaultType)
                       (java.security.KeyStore/getInstance))
               (.load nil)
               (.setCertificateEntry "cacert" cert))
        tm   (-> (doto (-> (javax.net.ssl.TrustManagerFactory/getDefaultAlgorithm)
                           (javax.net.ssl.TrustManagerFactory/getInstance))
                   (.init ks))
                 (.getTrustManagers))]
    (-> (doto (javax.net.ssl.SSLContext/getInstance "TLS")
          (.init nil tm nil))
        (.createSSLEngine #_#_"kubernetes.default.svc" 443))))



(defn kube-pod-api
  ([] (kube-pod-api nil))
  ([ctx]
   (let [api-server "https://kubernetes.default.svc"
         sa         (io/file "/var/run/secrets/kubernetes.io/serviceaccount")
         token      (-> (io/file sa "token") (slurp) (str/trim))
         ssl-engine (-> (io/file sa "ca.crt") (get-ssl-engine))
         namespace  (-> (io/file sa "namespace") (slurp) (str/trim))]
     (-> ctx
         (update :namespace  (fnil identity namespace))
         (merge {:api-server (str api-server ":443")
                 :sslengine  ssl-engine})
         (update :headers merge {"authorization" (str "Bearer " token)
                                 "content-type"  "application/json"
                                 "accept"        "application/json"})))))


(defn- path-template-value-not-found! [k]
  (throw (ex-info (str "url template value not found: " (pr-str k)) {:k k})))


(defn- apply-path-template [req]
  (let [api-url (-> req :api-url)]
    (update req :url str/replace #"\{([^}]+)\}" (fn [[_ k]] (-> k (keyword) req (or (path-template-value-not-found! k)))))))


(defn- apply-api-server [req]
  (let [api-server (-> req :api-server)]
    (update req :url (fn [url] (str api-server url)))))


(defn kube-api-request
  ([api method url] (kube-api-request api method url nil))
  ([api method url opts]
   (-> (merge-with (fn [left right]
                     (if (map? left)
                       (merge left right)
                       right))
                   api
                   {:method method
                    :url    url}
                   opts)
       (apply-path-template)
       (apply-api-server)
       (org.httpkit.client/request)
       #_(deref))))


(comment
  (def api (kube-pod-api))

  (def resp (org.httpkit.client/request (kube-api-request api :get "/api")))
  (type resp)
  (keys @resp)
  ;;=> 


  (let []
    (kube-api-request api :get "/apis/apps/v1/namespaces/{namespace}/controllerrevisions")
    #_(->> (kube-api-request api :get "/apis/apps/v1/namespaces/{namespace}/controllerrevisions")
           :items
           (map (comp :name :metadata))))
  ;;=> ("elasticsearch-master-6775cdf686" 
  ;;    "kafka-d69cd87f8" 
  ;;    "kafka-zookeeper-6f8bbd74d8" 
  ;;    "postgres-695b7fb84d" 
  ;;    "redis-master-59d9795f85" 
  ;;    "redis-sentinel-node-6789d465fd")
  )


;;
;; =========================================================================
;; Kube API:
;; =========================================================================
;;


(defn- format-label-selector [selectors]
  (if (map? selectors)
    (->> selectors
         (map (fn [[k v]]
                (str (namespace k) "/" (name k) "=" v)))
         (str/join ","))
    selectors))


(defn pods
  ([api] (pods api nil))
  ([api label-selector]
   (-> (kube-api-request api
                         :get
                         "/api/v1/namespaces/{namespace}/pods"
                         {:query {:labelSelector (format-label-selector label-selector)}})
       :items)))


(defn pod [api pod-name]
  (kube-api-request api
                    :get
                    "/api/v1/namespaces/{namespace}/pods/{pod-name}"
                    {:pod-name pod-name}))


(comment
  (with-open [api (kube-api {:context   "test:eu-west-1"
                             :namespace "review-3789"})]
    (->> (pods api)
         (map (comp :name :metadata))
         (take 5)))
  ;;=> ("advertiser-api-84ddc6bd5c-jf5qp" 
  ;;    "api-gateway-6958477bd5-w4rvs" 
  ;;    "assets-69447f94c7-xvxrr" 
  ;;    "auth-774fdd7c9d-sth7m" 
  ;;    "content-api-64989c7cb7-9nx9n")

  (with-open [api (kube-api {:context   "test:eu-west-1"
                             :namespace "review-3789"})]
    (->> (pod api "advertiser-api-84ddc6bd5c-jf5qp")
         :status
         :conditions
         (sort-by :lastTransitionTime)
         (map (juxt :lastTransitionTime :type))))
  ;;=> (["2025-02-15T08:58:43Z" "PodScheduled"] 
  ;;    ["2025-02-15T09:00:27Z" "Initialized"] 
  ;;    ["2025-02-15T09:01:13Z" "Ready"] 
  ;;    ["2025-02-15T09:01:13Z" "ContainersReady"]) 
  )


(defn services
  ([api] (services api nil))
  ([api label-selector]
   (-> (kube-api-request api
                         :get
                         "/api/v1/namespaces/{namespace}/services"
                         {:query {:labelSelector {:query {:labelSelector (format-label-selector label-selector)}}}})
       :items)))


(defn service [api svc-name]
  (kube-api-request api
                    :get
                    "/api/v1/namespaces/{namespace}/services/{svc-name}"
                    {:svc-name svc-name}))


(comment
  (with-open [api (kube-api {:context   "test:eu-west-1"
                             :namespace "review-3789"})]
    (->> (services api)
         (map (comp :name :metadata))
         (take 5)))
  ;;=> ("advertiser-api" "api-gateway" "assets" "auth" "content-api")

  (with-open [api (kube-api {:context   "test:eu-west-1"
                             :namespace "review-3789"})]
    (->> (service api "advertiser-api")
         :metadata
         :name))
  ;;=> "advertiser-api"
  )


(defn delete-pod [api pod-name]
  (kube-api-request api
                    :delete
                    "/api/v1/namespaces/{namespace}/pods/{pod-name}"
                    {:pod-name pod-name}))


(defn deployments
  ([api] (deployments api nil))
  ([api label-selector]
   (-> (kube-api-request api
                         :get
                         "/apis/apps/v1/namespaces/{namespace}/deployments"
                         {:query {:labelSelector (format-label-selector label-selector)}})
       :items)))


(defn deployment [api deployment-name]
  (kube-api-request api
                    :get
                    "/apis/apps/v1/namespaces/{namespace}/deployments/{deployment-name}"
                    {:deployment-name deployment-name}))


(defn statefulsets
  ([api] (statefulsets api nil))
  ([api label-selector]
   (-> (kube-api-request api
                         :get
                         "/apis/apps/v1/namespaces/{namespace}/statefulsets"
                         {:query {:labelSelector (format-label-selector label-selector)}})
       :items)))


(defn statefulset [api statefulset-name]
  (kube-api-request api
                    :get
                    "/apis/apps/v1/namespaces/{namespace}/statefulsets/{statefulset-name}"
                    {:statefulset-name statefulset-name}))


(defn secret [api secret-name]
  (let [decoder (java.util.Base64/getDecoder)]
    (-> (kube-api-request api
                          :get
                          "/api/v1/namespaces/{namespace}/secrets/{secret-name}"
                          {:secret-name (name secret-name)})
        :data
        (update-vals (fn [^String v]
                       (-> (.decode decoder v)
                           (String. java.nio.charset.StandardCharsets/UTF_8)))))))


(comment
  (def api (kube-api {:context   "test:eu-west-1"
                      :namespace "review-3789"}))

  (->> (statefulsets api)
       (map (comp :name :metadata)))
  ;;=> ("elasticsearch-master" "kafka" "kafka-zookeeper" "postgres" "redis-master" "redis-sentinel-node")

  (->> (statefulset api "redis-sentinel-node")
       :status
       ((juxt :replicas :currentReplicas)))
  ;;=> [3 3] 

  (->> (pods api (->> (statefulset api "redis-sentinel-node")
                      :spec
                      :selector
                      :matchLabels))
       (map (comp :name :metadata)))
  ;;=> ("redis-sentinel-node-0" "redis-sentinel-node-1" "redis-sentinel-node-2")
  ;

  (secret api :redis-sentinel)
  ;;=> {:redis-password "**************"}

  (.close api))
