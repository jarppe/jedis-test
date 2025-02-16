(ns kube-test.http
  (:require [org.httpkit.client :as http]
            [jsonista.core :as json]))


(defn- check-status [resp]
  (when-not (<= 200 (:status resp) 299)
    (throw (ex-info (format "API call failed: status=%d" (:status resp)) {:resp resp})))
  resp)


(defn request
  ([method url] (request method url nil))
  ([method url opts]
   (-> (http/request {:method       (or method :get)
                      :url          url
                      :query-params (:query opts)
                      :headers      {"content-type" "application/json"
                                     "accept"       "application/json"}
                      :as           :stream})
       (deref)
       (check-status)
       :body
       (json/read-value json/keyword-keys-object-mapper))))