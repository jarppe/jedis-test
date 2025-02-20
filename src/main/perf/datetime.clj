(ns perf.datetime
  (:import (java.time ZonedDateTime
                      ZoneId)
           (java.time.format DateTimeFormatter)))


(set! *warn-on-reflection* true)


(def my-zone (ZoneId/systemDefault))
(def human-format (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss"))


(defn humanize-datetime ^String [^String datetime]
  (-> (ZonedDateTime/parse datetime DateTimeFormatter/ISO_DATE_TIME)
      (.withZoneSameInstant ^ZoneId my-zone)
      (.toLocalDateTime)
      (.format human-format)))
