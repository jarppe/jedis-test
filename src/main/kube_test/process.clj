(ns kube-test.process
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))


(set! *warn-on-reflection* true)


;;
;; =========================================================================
;; Utility to run local processes:
;; =========================================================================
;;


(defrecord ProcessInst [^Process process exit pid ^java.io.OutputStream in ^java.io.InputStream out ^java.io.InputStream err info]
  java.io.Closeable
  (close [_] (.destroy process))

  clojure.lang.IDeref
  (deref [_] (deref exit))

  clojure.lang.IBlockingDeref
  (deref [_ timeout timeout-value] (deref exit timeout timeout-value))

  java.lang.Object
  (toString [_] (.toString process)))


(defn process ^ProcessInst [{:keys [cmd env info]}]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str cmd))]
    (reduce-kv (fn [^java.util.Map e k v]
                 (.put e
                       (-> k (name) (str/replace "-" "_") (str/upper-case))
                       (-> v (str)))
                 e)
               (.environment builder)
               env)
    (let [proc      (.start builder)
          exit-code (promise)]
      (-> (.onExit proc)
          (.thenApply (fn [^Process p] (deliver exit-code (.exitValue p)))))
      (->ProcessInst proc
                     exit-code
                     (.pid proc)
                     (.getOutputStream proc)
                     (.getInputStream proc)
                     (.getErrorStream proc)
                     info))))


(defn alive? [proc]
  (when-let [^Process p (:process proc)]
    (.isAlive p)))


(defn destroy [proc]
  (when-let [^Process p (:process proc)]
    (.destroy p)))


(defn exit-code [proc]
  (when-let [^Process p (:process proc)]
    (when (not (.isAlive p))
      (.exitValue p))))


(defmethod print-method ProcessInst [v ^java.io.Writer w]
  (.write w (str v)))



(comment
  (with-open [p (process {:cmd ["base64" "-d"]})]
    (doto (-> p :in (io/writer) (java.io.PrintWriter.))
      (.println "aGVsbG8sIHdvcmxkCg==")
      (.close))
    (doseq [line (-> (:out p) (io/reader) (line-seq))]
      (println line))
    (println "exit:" (deref p 100 ::timeout)))
  ;; prints:
  ;; hello, world
  ;; exit: 0
  )

