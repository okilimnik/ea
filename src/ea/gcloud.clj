(ns ea.gcloud
  (:require
   [clojure.java.io :as io])
  (:import
   [com.google.cloud.storage
    BlobId
    BlobInfo
    Storage$BlobWriteOption
    StorageOptions]
   [java.net URI]
   [java.nio.file Paths]))

(def storage (atom nil))
(def bucket-name "neusa-datasets")
(def prefix "0901/")

(defn init! []
  (reset! storage (.getService (StorageOptions/getDefaultInstance))))

(defn upload-file! [f]
  (try
    (when-not @storage (init!))
    (let [blob-id (BlobId/of bucket-name (str prefix (.getName f)))
          blob-info (.build (BlobInfo/newBuilder blob-id))]
      (.createFrom @storage blob-info (io/input-stream f) (into-array Storage$BlobWriteOption [])))
    (catch Exception e (prn e))))

(defn download-file! [filename]
  (try
    (when-not @storage (init!))
    (let [blob-id (BlobId/of bucket-name (str prefix filename))
          blob (.get @storage blob-id)]
      (.downloadTo blob (Paths/get (.toURI (io/file filename)))))
    (catch Exception e (prn e))))