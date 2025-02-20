(ns perf.carmine
  (:refer-clojure :exclude [set get])
  (:require [taoensso.carmine :as car]
            [carmine-sentinel.core :as cs :refer [set-sentinel-groups!]]))


(set-sentinel-groups!
 {:mymaster {:specs [{:host "redis-sentinel-1"
                      :port 26379}
                     {:host "redis-sentinel-2"
                      :port 26379}]}})


(def conn-spec {:sentinel-group :mymaster
                :master-name    "mymaster"})


(defmacro wcar* [& body] `(cs/wcar conn-spec ~@body))


(defn set [k v]
  (wcar* (car/set k v)))


(defn get [k]
  (wcar* (car/get k)))


(comment
  (wcar* (car/ping))
  ;;=> "PONG"
  ;

  (set "foo" "bar")
  ;;=> "OK"

  (get "foo")
  ;;=> "bar"
  )