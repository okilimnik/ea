(ns hft.gcloud
  (:require [clojure.java.io :as io])
  (:import [com.google.cloud.storage BlobId BlobInfo StorageOptions Storage$BlobWriteOption]))

(def storage (atom nil))
(def bucket-name "neusa-datasets")

(defn init! []
  (reset! storage (.getService (StorageOptions/getDefaultInstance))))

(defn upload-file! [f]
  (try
    (when-not @storage (init!))
    (let [blob-id (BlobId/of bucket-name (str "1310/" (.getName f)))
          blob-info (.build (BlobInfo/newBuilder blob-id))]
      (.createFrom @storage blob-info (io/input-stream f) (into-array Storage$BlobWriteOption [])))
    (catch Exception e (prn e))))

(defn upload-model! [f]
  (try
    (when-not @storage (init!))
    (let [blob-id (BlobId/of bucket-name (str "models/resnet50/" (.getName f)))
          blob-info (.build (BlobInfo/newBuilder blob-id))]
      (.createFrom @storage blob-info (io/input-stream f) (into-array Storage$BlobWriteOption [])))
    (catch Exception e (prn e))))