(ns santa-cruz-covid.mastodon
  (:require [clojure.data.json :as json]
            [cognitect.anomalies :as anomalies])
  (:import (com.github.mizosoft.methanol MediaType MoreBodyHandlers MultipartBodyPublisher)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)))

(def http-client (HttpClient/newHttpClient))

(defn status->category
  [status]
  (cond
    (= 400 status)      ::anomalies/incorrect
    (#{401 403} status) ::anomalies/forbidden
    (= 404 status)      ::anomalies/not-found
    (= 409 status)      ::anomalies/conflict
    :else               ::anomalies/fault))

(defn response->anomaly
  [^HttpResponse response]
  {::anomalies/category (status->category (.statusCode response))
   ::anomalies/message (str "request failed with status " (.statusCode response))
   :status-code (.statusCode response)
   :response-body (.body response)})

(defn upload-png-image
  [base-url access-token path description]
  (let [boundary (str "------JavaHttp_" (System/currentTimeMillis))
        request (.. (HttpRequest/newBuilder)
                    (uri (.. (URI. base-url) (resolve "/api/v2/media")))
                    (header "Authorization" (str "Bearer " access-token))
                    (header "Content-type" (str "multipart/form-data; boundary=" boundary))
                    (POST (.. (MultipartBodyPublisher/newBuilder)
                              (boundary boundary)
                              (filePart "file" path MediaType/IMAGE_PNG)
                              (formPart "description" (HttpRequest$BodyPublishers/ofString description))
                              (build)))
                    (build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    (cond
      (#{200 202} (.statusCode response)) (json/read-str (.body response) {:key-fn keyword})
      :else (response->anomaly response))))

(defn post-media-status
  [base-url access-token text media-ids]
  (let [body (json/write-str {:status text
                              :media_ids media-ids})
        request (.. (HttpRequest/newBuilder)
                    (uri (.. (URI. base-url) (resolve "/api/v1/statuses")))
                    (header "Authorization" (str "Bearer " access-token))
                    (header "Content-type" "application/json")
                    (POST (HttpRequest$BodyPublishers/ofString body))
                    (build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    (cond
      (= 200 (.statusCode response)) (json/read-str (.body response) {:key-fn keyword})
      :else (response->anomaly response))))