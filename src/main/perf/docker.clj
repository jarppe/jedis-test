(ns perf.docker
  (:import (com.github.dockerjava.api DockerClient)
           (com.github.dockerjava.api.model Container
                                            ContainerNetwork)
           (com.github.dockerjava.core DockerClientImpl
                                       DefaultDockerClientConfig)
           (com.github.dockerjava.httpclient5 ApacheDockerHttpClient$Builder)))


(set! *warn-on-reflection* true)


(defonce ^DockerClient client (DockerClientImpl/getInstance (-> (DefaultDockerClientConfig/createDefaultConfigBuilder)
                                                                (.withDockerHost "unix:///var/run/docker.sock")
                                                                (.build))
                                                            (-> (ApacheDockerHttpClient$Builder.)
                                                                (.dockerHost (java.net.URI. "unix:///var/run/docker.sock"))
                                                                (.build))))


(defn containers []
  (let [all-containers (-> client
                           (.listContainersCmd)
                           (.withShowAll true)
                           (.exec))]
    (filter (fn [^Container container]
              (->> container
                   (.getNames)
                   (first)
                   (re-matches #"^\/jedis-test-(.*)-1$")))
            all-containers)))


(defn container-id [^Container container]
  (let [container-name (-> container
                           (.getNames)
                           (first))]
    (-> (subs container-name
              (count "/jedis-test-")
              (- (count container-name) 2))
        (keyword))))


(defn container-state [^Container container]
  (-> container
      (.getState)
      (keyword)))


(defn container-ip [^Container container]
  (-> container
      (.getNetworkSettings)
      (.getNetworks)
      (.values)
      (first)
      ContainerNetwork/.getIpAddress))


(defn find-container-by-id ^Container [cid]
  (some (fn [^Container container]
          (when (= (container-id container) cid)
            container))
        (containers)))


(defn find-container-by-ip ^Container [ip]
  (some (fn [^Container container]
          (when (= (container-ip container) ip)
            container))
        (containers)))


(defn container-start [^Container container]
  (try
    (-> (.startContainerCmd client (.getId container))
        (.exec))
    (catch com.github.dockerjava.api.exception.NotModifiedException _
      ;; Container is already started
      ))
  container)


(defn container-stop [^Container container]
  (try
    (-> (.stopContainerCmd client (.getId container))
        (.exec))
    (catch com.github.dockerjava.api.exception.NotModifiedException _
      ;; Container is already stopped
      ))
  container)


(comment
  (map (juxt container-id container-state container-ip)
       (containers))
  ;;=> ([:devcontainer :running "172.30.42.10"] 
  ;;    [:redis-2 :running "172.30.42.13"] 
  ;;    [:redis-sentinel-1 :running "172.30.42.12"] 
  ;;    [:redis-1 :running "172.30.42.11"] 
  ;;    [:redis-sentinel-2 :running "172.30.42.14"])

  (-> (find-container-by-id :redis-1)
      (container-state))
  ;;=> :running
  )