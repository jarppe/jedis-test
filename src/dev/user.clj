(ns user
  (:require [clojure.tools.namespace.repl :as ctn]))


#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn reset []
  (println "user: Stopping..."))



#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn reloaded []
  (println "user: Starting..."))
