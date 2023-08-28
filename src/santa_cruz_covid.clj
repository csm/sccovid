(ns santa-cruz-covid
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as spec]
            [hickory.core :as h]
            [hickory.select :as s]
            [santa-cruz-covid.mastodon :as masto])
  (:import (java.io Closeable)
           (java.net URI URL)
           (java.net.http HttpRequest HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDateTime)))

(def corona-url "https://santacruzhealth.org/HSAHome/HSADivisions/PublicHealth/CommunicableDiseaseControl/CoronavirusHome.aspx")

(defrecord closable-process [proc]
  Closeable
  (close [_] (.destroy ^Process proc)))

(defn resolve-url
  [uri]
  (str (.. (URI. corona-url) (resolve uri))))

(defn read-urls
  [data]
  (let [tree (h/as-hickory (h/parse data))
        get-url (fn [id] (-> (s/select (s/child (s/id id)
                                                (s/tag :div)
                                                (s/tag :a)
                                                (s/tag :img))
                                       tree)
                             first
                             :attrs
                             :src
                             resolve-url))
        rt-uri (get-url "rtnumber")
        hosp-uri (get-url "hospitalizations")
        ww-url (get-url "wastewatermodeling")]
    {:rt-number rt-uri
     :hospitalizations hosp-uri
     :wastewater ww-url}))

(def downloaded-files (atom []))

(defn cleanup []
  (doseq [path @downloaded-files]
    (try (Files/delete path)
         (catch Exception e
           (prn "couldn't delete " (str path) " -- " e)))))

(defn die
  [& args]
  (cleanup)
  (apply prn args)
  (System/exit 1))

(defn remove-alpha-channel
  [path]
  (let [proc (.. (ProcessBuilder. ["/usr/bin/mogrify"
                                   "-background" "white"
                                   "-flatten"
                                   (str path)])
                 (start))
        exit-code (.waitFor proc)]
    (when (not (zero? exit-code))
      (die "Removing alpha channel from " (str path) " failed with exit code " exit-code))
    path))

(defn get-latest-models
  [& {:keys [http-client] :or {http-client masto/http-client}}]
  (let [request (.. (HttpRequest/newBuilder (URI. corona-url))
                    (GET)
                    (build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    (if (not= 200 (.statusCode response))
      (die "Failed to fetch COVID-19 info page, status: " (.statusCode response) " body: " (.body response))
      (read-urls (.body response)))))

(defn download
  [url & {:keys [http-client] :or {http-client masto/http-client}}]
  (let [path (Files/createTempFile "santa-cruz-corona" ".pdf" (into-array FileAttribute []))
        request (.. (HttpRequest/newBuilder (URI. url))
                    (GET)
                    (build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofFile path))]
    (if (> (.statusCode response) 299)
      (die "Downloading " url " failed with status " (.statusCode response))
      (do
        (swap! downloaded-files conj path)
        path))))

(defn die-if-anomaly
  [result]
  (if (spec/valid? :cognitect.anomalies/anomaly result)
    (die "Failure, anomaly: " result)
    result))

(defn -main
  [& args]
  (let [creds (edn/read-string (slurp "creds.edn"))
        model-urls (get-latest-models)
        previous-urls (try (edn/read-string (slurp "state.edn"))
                           (catch Exception _ nil))]
    (if (= model-urls (dissoc previous-urls :updated))
      (print "no change in PDF URLs. Not downloading or posting anything.")
      (let [rt-png (remove-alpha-channel (download (:rt-number model-urls)))
            ww-png (remove-alpha-channel (download (:wastewater model-urls)))
            hosp-png (remove-alpha-channel (download (:hospitalizations model-urls)))
            rt-info (die-if-anomaly
                      (masto/upload-png-image (:base-url creds) (:access-token creds) rt-png
                                              "Santa Cruz County COVID-19 Effective Reproductive Number Rt"))
            ww-info (die-if-anomaly
                      (masto/upload-png-image (:base-url creds) (:access-token creds) ww-png
                                              "Santa Cruz County COVID-19 Wastewater Projections"))
            hosp-info (die-if-anomaly
                        (masto/upload-png-image (:base-url creds) (:access-token creds) hosp-png
                                                "Santa Cruz County COVID-19 Hospitalization Projections"))
            updated (LocalDateTime/now)]
        (die-if-anomaly
          (masto/post-media-status (:base-url creds) (:access-token creds)
                                   (str "Santa Cruz County COVID-19 projections as of "
                                        updated)
                                   [(:id rt-info) (:id ww-info) (:id hosp-info)]))
        (print "Successfully posted Santa Cruz County COVID-19 projections at " updated)
        (spit "state.edn" (assoc model-urls :updated (str updated)))
        (cleanup)))))
