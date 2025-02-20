(ns perf.http
  (:require [org.httpkit.client :as http]
            [jsonista.core :as json]))


(defn status-ok? [resp]
  (<= 200 (:status resp) 299))


(defn- check-status [resp opts]
  (let [accept-resp? (-> opts :accept-resp? (or status-ok?))]
    (when-not (accept-resp? resp)
      (println "HTTP error: status" (:status resp))
      (-> resp :body (slurp) (println))
      (throw (ex-info (format "HTTP request failed: status=%d" (:status resp)) {:resp resp}))))
  resp)


(defn request [req]
  (-> (http/request (assoc req :as :stream))
      (deref)
      #_#_#_(check-status opts)
          :body
        (json/read-value json/keyword-keys-object-mapper)))
