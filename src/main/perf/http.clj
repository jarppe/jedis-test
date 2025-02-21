(ns perf.http
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [perf.async :as async :refer [then]])
  (:import (java.net URLEncoder)
           (java.net.http HttpClient
                          HttpClient$Version
                          HttpClient$Redirect
                          HttpRequest
                          HttpRequest$Builder
                          HttpRequest$BodyPublisher
                          HttpRequest$BodyPublishers
                          HttpResponse
                          HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.nio.charset StandardCharsets)
           (clojure.lang IFn
                         IPersistentMap
                         MapEntry
                         MapEquivalence)))


(set! *warn-on-reflection* true)


(defn http-client ^HttpClient [opts]
  (let [builder (HttpClient/newBuilder)] 
    (.version builder (-> opts :http-version (or :http-1-1) (name) (str/upper-case) (str/replace #"-" "_") (HttpClient$Version/valueOf)))
    (.connectTimeout builder (-> opts :connect-timeout (or 1000) (Duration/ofMillis)))
    (.executor builder (-> opts :executor (or async/executor)))
    (.followRedirects builder (-> opts :follow-redirects (or :normal) (name) (str/upper-case) (HttpClient$Redirect/valueOf))) 
    (when-let [ssl-context (-> opts :ssl-context)]
      (.sslContext builder ssl-context))
    (.build builder)))


(defonce ^HttpClient default-http-client (http-client nil))


(def ^:const content-type "content-type")
(def ^:const accept "accept")
(def ^:const mime-json "application/json")
(def ^:const mime-ndjson "application/x-ndjson")


(defonce json-mapper (json/object-mapper {:decode-key-fn true}))


(defn- set-headers ^HttpRequest$Builder [^HttpRequest$Builder builder headers]
  (doseq [[header-name header-value] headers]
    (when header-value
      (.setHeader builder header-name header-value)))
  builder)


(def ^:private ^:const NL (int \newline))


(defn- content-type-matches? [exppected-content-type actual-content-type]
  (when actual-content-type
    (or (identical? actual-content-type exppected-content-type)
        (str/starts-with? actual-content-type exppected-content-type))))


(defn body->json-bytes ^byte/1 [req]
  (let [baos (java.io.ByteArrayOutputStream. 1024)]
    (condp content-type-matches? (or (-> req :headers (get content-type))
                                     (-> req :base-headers (get content-type)))
      mime-json (json/write-value baos (:body req) json-mapper)
      mime-ndjson (do (json/write-values baos (:body req) json-mapper)
                      (.write baos NL))
      (spit baos (:body req)))
    (.toByteArray baos)))


(defn- body-publisher ^HttpRequest$BodyPublisher [req]
  (let [body (:body req)]
    (cond
      (nil? body) (HttpRequest$BodyPublishers/noBody)
      (string? body) (HttpRequest$BodyPublishers/ofString body)
      (bytes? body) (HttpRequest$BodyPublishers/ofByteArray body)
      (instance? java.io.InputStream body) (HttpRequest$BodyPublishers/ofInputStream (fn [] body))
      :else (HttpRequest$BodyPublishers/ofByteArray (body->json-bytes req)))))


(defn- parse-resp-body [^java.io.InputStream body content-type]
  (cond
    (nil? body) nil
    (nil? content-type) body
    (str/starts-with? content-type mime-json) (json/read-value body json-mapper)
    (str/starts-with? content-type mime-ndjson) (json/read-values body json-mapper)
    :else body))


(defn- add-query [^String url query]
  (if (->> query (vals) (some some?))
    (-> (reduce-kv (fn [^StringBuilder acc k v]
                     (if (some? v)
                       (doto acc
                         (.append (URLEncoder/encode (name k) StandardCharsets/UTF_8))
                         (.append "=")
                         (.append (URLEncoder/encode (str v) StandardCharsets/UTF_8))
                         (.append "&"))
                       acc))
                   (-> (StringBuilder. url)
                       (.append "?"))
                   query)
        (str))
    url))


(defn- make-uri ^java.net.URI [{:keys [base-url url query]}]
  (-> (if (sequential? url)
        (str/join "/" (cons base-url url))
        (str base-url url))
      (add-query query)
      (java.net.URI.)))

(deftype ResponseHeadersAdapter [headers]
  java.util.Map
  (size [_] (let [m ^java.util.Map (headers)] (.size m)))
  (get [_ k] (headers k nil))
  (getOrDefault [_ k not-found] (headers k not-found))
  (containsKey [_ k] (not= (headers k ::missing) ::missing)) 

  MapEquivalence

  IFn
  (invoke [_ k] (headers k nil))
  (invoke [_ k not-found] (headers k not-found))

  IPersistentMap
  (valAt [_ k] (headers k nil))
  (valAt [_ k not-found] (headers k not-found))
  (entryAt [_ k] (when-let [v (headers k nil)] (MapEntry/create k v)))
  (empty [_] {})
  (count [_] (let [m ^java.util.Map (headers)] (.size m)))
  (seq [_] (let [m ^java.util.Map (headers)] 
             (->> m 
                  (.entrySet) 
                  (.iterator) 
                  (iterator-seq) 
                  (map (fn [[k ^java.util.List v]] [k (.getFirst v)])))))
  (equiv [this other] (identical? this other))

  Object
  (equals [this other] (identical? this other))
  (toString [this] (->> (seq this)
                        (map (fn [[k v]] (str k ": " v))) 
                        (str/join "\n"))))


(defn- http-headers-adapter [^HttpResponse response]
  (let [headers (-> response (.headers))]
    (ResponseHeadersAdapter. (fn
                               ([] (-> headers (.map)))
                               ([k not-found] (-> headers (.firstValue k) (.orElse not-found)))))))


(defn- handle-response [^HttpResponse response req]
  (let [status (-> response (.statusCode))
        resp   {:status  status
                :headers (http-headers-adapter response)
                :body    (when (-> req :method (not= :head))
                           (parse-resp-body (-> response (.body))
                                           (-> response (.headers) (.firstValue content-type) (.orElse nil))))}]
    (when-let [accepted-status? (-> req :accepted-status?)]
      (when (accepted-status? status)
        (throw (ex-info (str "HTTP error: " (:method req) " " (:url req) " => " status)
                        {:req  req
                         :resp resp}))))
    resp))


(defn make-http-request ^HttpRequest [req]
  (-> (HttpRequest/newBuilder)
      (.uri (make-uri req))
      (.method (-> req :method (or :get) (name) (str/upper-case))
               (-> req (body-publisher)))
      (set-headers (-> req :base-headers))
      (set-headers (-> req :headers))
      (.build)))


(defn get-client ^HttpClient [req]
  (-> req :http-client (or default-http-client)))


(defn request [req]
  (let [client       (get-client req)
        http-req     (make-http-request req)
        body-handler (HttpResponse$BodyHandlers/ofInputStream)]
    (if (-> req :async)
      (-> (.sendAsync client http-req body-handler)
          (then handle-response req))
      (-> (.send client http-req body-handler)
          (handle-response req)))))


(def json-headers {content-type mime-json
                   accept       (str mime-json ", " mime-ndjson)})


(defn ok? [status] (<= 200 status 299))


(comment
  (-> (request {:url "https://metosin.fi"})
      :headers) 

  (-> (request {:url   "https://metosin.fi"
                :async true})
      (then (fn [resp] (-> resp :headers)))
      (deref))
  ;
  )


#_
(ns perf.http
  (:require [org.httpkit.client :as http]
            [jsonista.core :as json]))

#_
(defn status-ok? [resp]
  (<= 200 (:status resp) 299))

#_
(defn- check-status [resp opts]
  (let [accept-resp? (-> opts :accept-resp? (or status-ok?))]
    (when-not (accept-resp? resp)
      (println "HTTP error: status" (:status resp))
      (-> resp :body (slurp) (println))
      (throw (ex-info (format "HTTP request failed: status=%d" (:status resp)) {:resp resp}))))
  resp)
