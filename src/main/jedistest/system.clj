(ns jedistest.system
  (:require [clojure.string :as str])
  (:import (redis.clients.jedis Jedis
                                JedisSentinelPool)
           (com.github.dockerjava.api DockerClient)
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


;;
;; Containers:
;;


(def containers (->> client
                     (.listContainersCmd)
                     (.exec)
                     (reduce (fn [acc ^Container container]
                               (let [[_ cname] (->> container (.getNames) (first) (re-matches #"^\/jedis-test-(.*)-1$"))]
                                 (if cname
                                   (assoc acc (keyword cname) container)
                                   acc)))
                             {})))


(comment
  containers
  ;;=> {:devcontainer     #object[com.github.dockerjava.api.model.Container 0x1a54f7c0 "Container(command=entrypoint sleep infinity, created=1738239721, id=cd75ee89b7c150802313a4f5008378ccec38dc85c20e93b7d9dbec6a11e05ad6, image=jedis-test:dev, imageId=sha256:86b9ad7005f60fb0775bae797cbcd2c5d048925c53cc577f7cfc3c8249c49490, names=[/jedis-test-devcontainer-1], ports=[], labels={com.docker.compose.config-hash=2b575ab657486734d703495a0e1a998eed5ea55f51d733018359db4b3dea1537, com.docker.compose.container-number=1, com.docker.compose.depends_on=, com.docker.compose.image=sha256:86b9ad7005f60fb0775bae797cbcd2c5d048925c53cc577f7cfc3c8249c49490, com.docker.compose.oneoff=False, com.docker.compose.project=jedis-test, com.docker.compose.project.config_files=/Users/jarppe/swd/cc/jedis-test/docker-compose.yml, com.docker.compose.project.working_dir=/Users/jarppe/swd/cc/jedis-test, com.docker.compose.service=devcontainer, com.docker.compose.version=2.31.0, desktop.docker.io/binds/0/Source=/var/run/docker.sock, desktop.docker.io/binds/0/SourceKind=dockerSocketProxied, desktop.docker.io/binds/0/Target=/var/run/docker.sock, desktop.docker.io/binds/1/Source=/Users/jarppe/swd/cc/jedis-test, desktop.docker.io/binds/1/SourceKind=hostFile, desktop.docker.io/binds/1/Target=/workspace}, status=Up 2 minutes, state=running, sizeRw=null, sizeRootFs=null, hostConfig=ContainerHostConfig(networkMode=jedis-test_jedis), networkSettings=ContainerNetworkSettings(networks={jedis-test_jedis=ContainerNetwork(ipamConfig=ContainerNetwork.Ipam(ipv4Address=172.20.3.1, ipv6Address=null), links=[], aliases=null, networkID=6ad420151a938045f3d92d867c81d14652f0c52f8c58c18c0b063ce8d5e51055, endpointId=458708bce530daf7739da7c607c30d709cb9a287ce176cdf8d589fa4546baa45, gateway=172.20.0.1, ipAddress=172.20.3.1, ipPrefixLen=16, ipV6Gateway=, globalIPv6Address=, globalIPv6PrefixLen=0, macAddress=02:42:ac:14:03:01)}), mounts=[ContainerMount(name=null, source=/run/host-services/docker.proxy.sock, destination=/var/run/docker.sock, driver=null, mode=rw, rw=true, propagation=rprivate), ContainerMount(name=null, source=/host_mnt/Users/jarppe/swd/cc/jedis-test, destination=/workspace, driver=null, mode=rw, rw=true, propagation=rprivate)])"]
  ;;    :redis-2          #object[com.github.dockerjava.api.model.Container 0x5a49cd83 "Container(command=/opt/bitnami/scripts/redis/entrypoint.sh /opt/bitnami/scripts/redis/run.sh, created=1738239721, id=fa685d6ce8cec64618f9864753d0fb9772a4b634ed6e97bc7c889c35ec1fdd6a, image=bitnami/redis:6.2.13, imageId=sha256:2bcbb03c31349fed1c2fef8828b1a5971c544bcf07a2710e043578359e7fe34c, names=[/jedis-test-redis-2-1], ports=[ContainerPort(ip=null, privatePort=6379, publicPort=null, type=tcp)], labels={com.docker.compose.config-hash=23df5fdd24506964e60dd75b2eaa3b3b3e6ddc23aec732d97028fe9e27e61491, com.docker.compose.container-number=1, com.docker.compose.depends_on=, com.docker.compose.image=sha256:2bcbb03c31349fed1c2fef8828b1a5971c544bcf07a2710e043578359e7fe34c, com.docker.compose.oneoff=False, com.docker.compose.project=jedis-test, com.docker.compose.project.config_files=/Users/jarppe/swd/cc/jedis-test/docker-compose.yml, com.docker.compose.project.working_dir=/Users/jarppe/swd/cc/jedis-test, com.docker.compose.service=redis-2, com.docker.compose.version=2.31.0, com.vmware.cp.artifact.flavor=sha256:1e1b4657a77f0d47e9220f0c37b9bf7802581b93214fff7d1bd2364c8bf22e8e, org.opencontainers.image.base.name=docker.io/bitnami/minideb:bullseye, org.opencontainers.image.created=2023-10-08T22:37:41Z, org.opencontainers.image.description=Application packaged by VMware, Inc, org.opencontainers.image.licenses=Apache-2.0, org.opencontainers.image.ref.name=6.2.13-debian-11-r74, org.opencontainers.image.title=redis, org.opencontainers.image.vendor=VMware, Inc., org.opencontainers.image.version=6.2.13}, status=Up 2 minutes, state=running, sizeRw=null, sizeRootFs=null, hostConfig=ContainerHostConfig(networkMode=jedis-test_jedis), networkSettings=ContainerNetworkSettings(networks={jedis-test_jedis=ContainerNetwork(ipamConfig=ContainerNetwork.Ipam(ipv4Address=172.20.1.2, ipv6Address=null), links=[], aliases=null, networkID=6ad420151a938045f3d92d867c81d14652f0c52f8c58c18c0b063ce8d5e51055, endpointId=39049806f73e1aa6893375e39fa7aebdfd0e70cc5e427ded667c264329267de8, gateway=172.20.0.1, ipAddress=172.20.1.2, ipPrefixLen=16, ipV6Gateway=, globalIPv6Address=, globalIPv6PrefixLen=0, macAddress=02:42:ac:14:01:02)}), mounts=[])"]
  ;;    :redis-1          #object[com.github.dockerjava.api.model.Container 0x440264c2 "Container(command=/opt/bitnami/scripts/redis/entrypoint.sh /opt/bitnami/scripts/redis/run.sh, created=1738239721, id=12f890f472e90994187a6fbb5730522c8a8abf9fd027abf76110aedf3bded091, image=bitnami/redis:6.2.13, imageId=sha256:2bcbb03c31349fed1c2fef8828b1a5971c544bcf07a2710e043578359e7fe34c, names=[/jedis-test-redis-1-1], ports=[ContainerPort(ip=null, privatePort=6379, publicPort=null, type=tcp)], labels={com.docker.compose.config-hash=81fe4e2efd560d8880f1717203829e1a4d961cc6eddef3bcba5afae028f7068e, com.docker.compose.container-number=1, com.docker.compose.depends_on=, com.docker.compose.image=sha256:2bcbb03c31349fed1c2fef8828b1a5971c544bcf07a2710e043578359e7fe34c, com.docker.compose.oneoff=False, com.docker.compose.project=jedis-test, com.docker.compose.project.config_files=/Users/jarppe/swd/cc/jedis-test/docker-compose.yml, com.docker.compose.project.working_dir=/Users/jarppe/swd/cc/jedis-test, com.docker.compose.service=redis-1, com.docker.compose.version=2.31.0, com.vmware.cp.artifact.flavor=sha256:1e1b4657a77f0d47e9220f0c37b9bf7802581b93214fff7d1bd2364c8bf22e8e, org.opencontainers.image.base.name=docker.io/bitnami/minideb:bullseye, org.opencontainers.image.created=2023-10-08T22:37:41Z, org.opencontainers.image.description=Application packaged by VMware, Inc, org.opencontainers.image.licenses=Apache-2.0, org.opencontainers.image.ref.name=6.2.13-debian-11-r74, org.opencontainers.image.title=redis, org.opencontainers.image.vendor=VMware, Inc., org.opencontainers.image.version=6.2.13}, status=Up 2 minutes, state=running, sizeRw=null, sizeRootFs=null, hostConfig=ContainerHostConfig(networkMode=jedis-test_jedis), networkSettings=ContainerNetworkSettings(networks={jedis-test_jedis=ContainerNetwork(ipamConfig=ContainerNetwork.Ipam(ipv4Address=172.20.1.1, ipv6Address=null), links=[], aliases=null, networkID=6ad420151a938045f3d92d867c81d14652f0c52f8c58c18c0b063ce8d5e51055, endpointId=0bd1fb58c5bef85b0774b796e6a007fd0b39150944c9b42bb08878070f226407, gateway=172.20.0.1, ipAddress=172.20.1.1, ipPrefixLen=16, ipV6Gateway=, globalIPv6Address=, globalIPv6PrefixLen=0, macAddress=02:42:ac:14:01:01)}), mounts=[])"]
  ;;    :redis-sentinel-1 #object[com.github.dockerjava.api.model.Container 0x3bb2a781 "Container(command=/opt/bitnami/scripts/redis-sentinel/entrypoint.sh /opt/bitnami/scripts/redis-sentinel/run.sh, created=1738239721, id=44e9fe9ccb025132e9848d4b72d9a874e74d72cad8e794a65e6f5063821a5a7f, image=bitnami/redis-sentinel:6.2.13, imageId=sha256:12f9de8aa4e1b225754fd7f0581128aa29e746fe5592a11916ec6a6aaaed5c24, names=[/jedis-test-redis-sentinel-1-1], ports=[ContainerPort(ip=null, privatePort=26379, publicPort=null, type=tcp)], labels={com.docker.compose.config-hash=470ed8e7aeda292c545a68cc4d7515dbac957b7fadf187e6faae5d3200ff6172, com.docker.compose.container-number=1, com.docker.compose.depends_on=, com.docker.compose.image=sha256:12f9de8aa4e1b225754fd7f0581128aa29e746fe5592a11916ec6a6aaaed5c24, com.docker.compose.oneoff=False, com.docker.compose.project=jedis-test, com.docker.compose.project.config_files=/Users/jarppe/swd/cc/jedis-test/docker-compose.yml, com.docker.compose.project.working_dir=/Users/jarppe/swd/cc/jedis-test, com.docker.compose.service=redis-sentinel-1, com.docker.compose.version=2.31.0, com.vmware.cp.artifact.flavor=sha256:1e1b4657a77f0d47e9220f0c37b9bf7802581b93214fff7d1bd2364c8bf22e8e, org.opencontainers.image.base.name=docker.io/bitnami/minideb:bullseye, org.opencontainers.image.created=2023-10-09T18:58:42Z, org.opencontainers.image.description=Application packaged by VMware, Inc, org.opencontainers.image.licenses=Apache-2.0, org.opencontainers.image.ref.name=6.2.13-debian-11-r76, org.opencontainers.image.title=redis-sentinel, org.opencontainers.image.vendor=VMware, Inc., org.opencontainers.image.version=6.2.13}, status=Up 2 minutes, state=running, sizeRw=null, sizeRootFs=null, hostConfig=ContainerHostConfig(networkMode=jedis-test_jedis), networkSettings=ContainerNetworkSettings(networks={jedis-test_jedis=ContainerNetwork(ipamConfig=ContainerNetwork.Ipam(ipv4Address=172.20.2.1, ipv6Address=null), links=[], aliases=null, networkID=6ad420151a938045f3d92d867c81d14652f0c52f8c58c18c0b063ce8d5e51055, endpointId=a9c04b2a730cf5ce28bf2072834ff2bda84bd40dd9a3dd1e0c2216efb0a6ef43, gateway=172.20.0.1, ipAddress=172.20.2.1, ipPrefixLen=16, ipV6Gateway=, globalIPv6Address=, globalIPv6PrefixLen=0, macAddress=02:42:ac:14:02:01)}), mounts=[])"]
  ;;    :redis-sentinel-3 #object[com.github.dockerjava.api.model.Container 0x7ec54722 "Container(command=/opt/bitnami/scripts/redis-sentinel/entrypoint.sh /opt/bitnami/scripts/redis-sentinel/run.sh, created=1738239721, id=77f16bc16ddb907b47abb7c27aecf8c6271e550f43922203244812b54031cbec, image=bitnami/redis-sentinel:6.2.13, imageId=sha256:12f9de8aa4e1b225754fd7f0581128aa29e746fe5592a11916ec6a6aaaed5c24, names=[/jedis-test-redis-sentinel-3-1], ports=[ContainerPort(ip=null, privatePort=26379, publicPort=null, type=tcp)], labels={com.docker.compose.config-hash=204f347f298428fb53b6502e1d52b6a3e9c3e3d9ef2b1b540ece6485b69605d1, com.docker.compose.container-number=1, com.docker.compose.depends_on=, com.docker.compose.image=sha256:12f9de8aa4e1b225754fd7f0581128aa29e746fe5592a11916ec6a6aaaed5c24, com.docker.compose.oneoff=False, com.docker.compose.project=jedis-test, com.docker.compose.project.config_files=/Users/jarppe/swd/cc/jedis-test/docker-compose.yml, com.docker.compose.project.working_dir=/Users/jarppe/swd/cc/jedis-test, com.docker.compose.service=redis-sentinel-3, com.docker.compose.version=2.31.0, com.vmware.cp.artifact.flavor=sha256:1e1b4657a77f0d47e9220f0c37b9bf7802581b93214fff7d1bd2364c8bf22e8e, org.opencontainers.image.base.name=docker.io/bitnami/minideb:bullseye, org.opencontainers.image.created=2023-10-09T18:58:42Z, org.opencontainers.image.description=Application packaged by VMware, Inc, org.opencontainers.image.licenses=Apache-2.0, org.opencontainers.image.ref.name=6.2.13-debian-11-r76, org.opencontainers.image.title=redis-sentinel, org.opencontainers.image.vendor=VMware, Inc., org.opencontainers.image.version=6.2.13}, status=Up 2 minutes, state=running, sizeRw=null, sizeRootFs=null, hostConfig=ContainerHostConfig(networkMode=jedis-test_jedis), networkSettings=ContainerNetworkSettings(networks={jedis-test_jedis=ContainerNetwork(ipamConfig=ContainerNetwork.Ipam(ipv4Address=172.20.2.3, ipv6Address=null), links=[], aliases=null, networkID=6ad420151a938045f3d92d867c81d14652f0c52f8c58c18c0b063ce8d5e51055, endpointId=de6ae8edd217a09795c364dc79b7a1e9e0f5e394f74ef08edff9e15230af1798, gateway=172.20.0.1, ipAddress=172.20.2.3, ipPrefixLen=16, ipV6Gateway=, globalIPv6Address=, globalIPv6PrefixLen=0, macAddress=02:42:ac:14:02:03)}), mounts=[])"]
  ;;    :redis-sentinel-2 #object[com.github.dockerjava.api.model.Container 0x67588de2 "Container(command=/opt/bitnami/scripts/redis-sentinel/entrypoint.sh /opt/bitnami/scripts/redis-sentinel/run.sh, created=1738239721, id=c78fe3d65729d4ba510193ec1efbba3033281fb92e462e892a627292257110c6, image=bitnami/redis-sentinel:6.2.13, imageId=sha256:12f9de8aa4e1b225754fd7f0581128aa29e746fe5592a11916ec6a6aaaed5c24, names=[/jedis-test-redis-sentinel-2-1], ports=[ContainerPort(ip=null, privatePort=26379, publicPort=null, type=tcp)], labels={com.docker.compose.config-hash=847eeeabce8514177a8b751ac0829829d45f31ed33af227ff58a2dd4b876b4ec, com.docker.compose.container-number=1, com.docker.compose.depends_on=, com.docker.compose.image=sha256:12f9de8aa4e1b225754fd7f0581128aa29e746fe5592a11916ec6a6aaaed5c24, com.docker.compose.oneoff=False, com.docker.compose.project=jedis-test, com.docker.compose.project.config_files=/Users/jarppe/swd/cc/jedis-test/docker-compose.yml, com.docker.compose.project.working_dir=/Users/jarppe/swd/cc/jedis-test, com.docker.compose.service=redis-sentinel-2, com.docker.compose.version=2.31.0, com.vmware.cp.artifact.flavor=sha256:1e1b4657a77f0d47e9220f0c37b9bf7802581b93214fff7d1bd2364c8bf22e8e, org.opencontainers.image.base.name=docker.io/bitnami/minideb:bullseye, org.opencontainers.image.created=2023-10-09T18:58:42Z, org.opencontainers.image.description=Application packaged by VMware, Inc, org.opencontainers.image.licenses=Apache-2.0, org.opencontainers.image.ref.name=6.2.13-debian-11-r76, org.opencontainers.image.title=redis-sentinel, org.opencontainers.image.vendor=VMware, Inc., org.opencontainers.image.version=6.2.13}, status=Up 2 minutes, state=running, sizeRw=null, sizeRootFs=null, hostConfig=ContainerHostConfig(networkMode=jedis-test_jedis), networkSettings=ContainerNetworkSettings(networks={jedis-test_jedis=ContainerNetwork(ipamConfig=ContainerNetwork.Ipam(ipv4Address=172.20.2.2, ipv6Address=null), links=[], aliases=null, networkID=6ad420151a938045f3d92d867c81d14652f0c52f8c58c18c0b063ce8d5e51055, endpointId=92a6f420f5e86cf22f6397e18e64586a920af042e32b5e9b2d27f002307af302, gateway=172.20.0.1, ipAddress=172.20.2.2, ipPrefixLen=16, ipV6Gateway=, globalIPv6Address=, globalIPv6PrefixLen=0, macAddress=02:42:ac:14:02:02)}), mounts=[])"]}
  )


(def containers-by-ip (->> containers
                           (mapcat (fn [[cid ^Container container]]
                                     (map vector
                                          (->> container
                                               (.getNetworkSettings)
                                               (.getNetworks)
                                               (.values)
                                               (map ContainerNetwork/.getIpAddress))
                                          (repeat cid))))
                           (into {})))


(comment
  containers-by-ip
  ;;=> {"172.20.3.1" :devcontainer
  ;;    "172.20.1.2" :redis-2
  ;;    "172.20.1.1" :redis-1
  ;;    "172.20.2.1" :redis-sentinel-1
  ;;    "172.20.2.3" :redis-sentinel-3
  ;;    "172.20.2.2" :redis-sentinel-2}
  )


;;
;; Helpers:
;;


(defn replication-info [redis-id]
  (->> (with-open [^Jedis c (Jedis. (name redis-id) 6379)]
         (.info c "replication"))
       (str/split-lines)
       (keep (fn [line]
               (when-let [[_ k v] (re-matches #"([^:]+):(.*)" line)]
                 [(-> k (str/replace "_" "-") (keyword)) v])))
       (into {})))


(comment
  (replication-info :redis-1)
  ;;=> {:role                           "master"
  ;;    :master-replid                  "33ae692ae07517c4b074d393adc05494792a7b04"
  ;;    :connected-slaves               "1"
  ;;    :repl-backlog-size              "1048576"
  ;;    :second-repl-offset             "-1"
  ;;    :repl-backlog-histlen           "56696"
  ;;    :master-replid2                 "0000000000000000000000000000000000000000"
  ;;    :repl-backlog-active            "1"
  ;;    :slave0                         "ip=172.20.1.2,port=6379,state=online,offset=56696,lag=1"
  ;;    :master-failover-state          "no-failover"
  ;;    :master-repl-offset             "56696"
  ;;    :repl-backlog-first-byte-offset "1"}
  )


;;
;; Tests:
;;


(comment

  ;;
  ;; Check that all containers are up and running:
  ;;

  (->> [:redis-1 :redis-2 :redis-sentinel-1 :redis-sentinel-2 :redis-sentinel-3]
       (map containers)
       (map Container/.getState)
       (every? #{"running"}))
  ;;=> true

  ;; Make a pool:

  (def ^JedisSentinelPool pool (JedisSentinelPool. "mymaster" #{"redis-sentinel-1:26379"
                                                                "redis-sentinel-2:26379"
                                                                "redis-sentinel-3:26379"}))

  ;; We don't know which is master without asking:

  (def master (-> (.getCurrentHostMaster pool)
                  (.getHost)
                  containers-by-ip))
  (def slave ({:redis-1 :redis-2
               :redis-2 :redis-1}
              master))

  ;; Note that these might be different on your system. The important this is that one is
  ;; master, and another is slave. Which ever way around works for us.

  master
  ;;=> :redis-1
  slave
  ;;=> :redis-2

  ;;
  ;; Verify that redis-1 and redis-2 are in replication:
  ;;

  (-> (replication-info master)
      (select-keys [:role :connected-slaves])
      (= {:role             "master"
          :connected-slaves "1"}))
  ;;=> true

  (-> (replication-info slave)
      (select-keys [:role :connected-slaves])
      (= {:role             "slave"
          :connected-slaves "0"}))
  ;;=> true

  ;; Write to master, read from slave:
  (let [value  (str (gensym))]
    (with-open [^Jedis c (Jedis. (name master) 6379)]
      (.set c "foo" value))
    (= (with-open [^Jedis c (Jedis. (name slave) 6379)]
         (.get c "foo"))
       value))
  ;=> true

  ;;
  ;; Verify: pool works:
  ;; 

  (= (with-open [^Jedis c (.getResource pool)]
       (.ping c))
     "PONG")
  ;;=> true

  ;;
  ;; Stop master redis:
  ;;

  (let [master-container-id (-> (containers master) (.getId))]
    (-> (.stopContainerCmd client master-container-id)
        (.exec)))

  ;;
  ;; Verify: pool still works:
  ;;

  ;; old slave is now promoted as master:
  (= (-> (.getCurrentHostMaster pool)
         (.getHost)
         containers-by-ip)
     slave)

  ;; pool works with new master
  (= (with-open [^Jedis c (.getResource pool)]
       (.set c "foo" "baba"))
     "OK")
  ;;=> true

  ;; Slave replication info shows it as master and number of slaves is 0
  (-> (replication-info slave)
      (select-keys [:role :connected-slaves])
      (= {:role             "master"
          :connected-slaves "0"}))
  ;;=> true 

  ;; Start old master container:

  (-> client
      (.startContainerCmd (-> (containers master) (.getId)))
      (.exec))

  ;; Verify that it's listed in new master:
  ;; - note, it might take few seconds before "connected-slaves" becomes 1

  (-> (replication-info slave)
      (select-keys [:role :connected-slaves])
      (= {:role             "master"
          :connected-slaves "1"}))
  ;;=> true

  ;; Clenaup
  (.close pool)

  ;
  )
