(ns build
  (:require [clojure.tools.build.api :as b]))


(def compile-ns 'perf.main)
(def main-class 'perf.Main)


(def target "./target")
(def classes (str target "/classes"))
(def jar-file (str target "/perf-test.jar"))


(defn clean [_]
  (b/delete {:path classes})
  (b/delete {:path jar-file}))


(defn compile-classes [basis]
  (b/compile-clj {:basis        basis
                  :ns-compile   [compile-ns]
                  :class-dir    classes
                  :compile-opts {:elide-meta     [:doc :file :line :added]
                                 :direct-linking true}
                  :bindings     {#'clojure.core/*assert* false}}))


(defn build-uber [basis]
  (b/uber {:basis     basis
           :uber-file jar-file
           :class-dir classes
           :main      main-class}))


;;
;; Tools API:
;;


#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn build-all [_]
  (doto (b/create-basis)
    (clean)
    (compile-classes)
    (build-uber))
  nil)


(comment
  (build-all nil)
  ;
  )