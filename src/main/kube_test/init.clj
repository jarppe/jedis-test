(ns kube-test.init)


(set! *warn-on-reflection* true)


(defonce _init (do (java.util.Locale/setDefault (java.util.Locale/of "en" "US"))
                   (doto (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                     (set-agent-send-executor!)
                     (set-agent-send-off-executor!))))
