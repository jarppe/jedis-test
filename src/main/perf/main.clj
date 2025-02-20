(ns perf.main
  (:gen-class :name perf.Main)
  (:require [perf.set-get-perf]
            [perf.chaos]))


(set! *warn-on-reflection* true)


(defn -main [& [test & args]]
  (java.util.Locale/setDefault (java.util.Locale/of "en" "US"))
  (case test
    "set-get" (perf.set-get-perf/bench args)
    "chaos" (perf.chaos/chaos args)
    (println "rtfm"))
  (System/exit 0))



