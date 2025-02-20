(ns perf.set-get-perf
  (:require [perf.jedis :as jedis :refer [with-pooled-client]]
            [perf.carmine :as car]
            [criterium.core :as crit]))


(set! *warn-on-reflection* true)


(def random-str-chars (->> (range (int \space) (inc (int \~)))
                           (mapv (fn [^long i] (Character/toString i)))))


(defn random-str []
  (-> (reduce (fn [^StringBuilder acc ^String s]
                (.append acc s))
              (StringBuilder.)
              (repeatedly 32 (partial rand-nth random-str-chars)))
      (str)))


(defn random-kvs [size]
  (mapv vector
        (repeatedly size random-str)
        (repeatedly random-str)))


(defn test-with-jedis-pool [pool kvs]
  (doseq [[k v] kvs]
    (with-pooled-client [jedis pool]
      (jedis/set jedis k v)))
  (doseq [[k] kvs]
    (with-pooled-client [jedis pool]
      (jedis/get jedis k))))


(defn test-with-jedis-client [pool kvs]
  (with-pooled-client [jedis pool]
    (doseq [[k v] kvs]
      (jedis/set jedis k v))
    (doseq [[k] kvs]
      (jedis/get jedis k))))


(defn test-with-carmine [kvs]
  (doseq [[k v] kvs]
    (car/set k v))
  (doseq [[k] kvs]
    (car/get k)))


(defn bench [[size]]
  (let [size (-> size (or "100") (parse-long))
        kvs  (random-kvs size)]
    (println "== warmup: == size: " size)
    (test-with-carmine kvs)
    (with-open [pool (jedis/create-sentinel-pool)]
      (test-with-jedis-pool pool kvs)
      (test-with-jedis-client pool kvs))

    (println "===========================================================================")
    (println "== test-with-carmine: =====================================================")
    (println "===========================================================================")
    (crit/quick-bench
     (test-with-carmine kvs))

    (with-open [pool (jedis/create-sentinel-pool)]
      (println "===========================================================================")
      (println "== test-with-jedis-pool: ==================================================")
      (println "===========================================================================")
      (crit/quick-bench
       (test-with-jedis-pool pool kvs))

      (println "===========================================================================")
      (println "== test-with-jedis-client: ================================================")
      (println "===========================================================================")
      (crit/quick-bench
       (test-with-jedis-client pool kvs)))

    (println "===========================================================================")
    (println "== all tests done: ========================================================")
    (println "===========================================================================")))


(comment

  (def kvs (random-kvs 10))
  (def pool (jedis/create-sentinel-pool))

  (crit/bench
   (test-with-jedis-pool pool kvs))

  (crit/bench
   (test-with-jedis-client pool kvs))

  (crit/bench
   (test-with-carmine kvs))

  (.close pool)
  ;
  )


