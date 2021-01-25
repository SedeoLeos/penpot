;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.rpc.mutations.media
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
   [app.storage :as sto]
   [app.util.http :as http]
   [app.util.services :as sv]
   [app.util.storage :as ust]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]))

(def thumbnail-options
  {:width 100
   :height 100
   :quality 85
   :format :jpeg})

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)

;; --- Create File Media object (upload)

(declare create-file-media-object)
(declare select-file-for-update)

(s/def ::content ::media/upload)
(s/def ::is-local ::us/boolean)

(s/def ::upload-file-media-object
  (s/keys :req-un [::profile-id ::file-id ::is-local ::name ::content]
          :opt-un [::id]))

(sv/defmethod ::upload-file-media-object
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (-> (assoc cfg :conn conn)
          (create-file-media-object params)))))

(defn- big-enough-for-thumbnail?
  "Checks if the provided image info is big enough for
  create a separate thumbnail storage object."
  [info]
  (or (> (:width info) (:width thumbnail-options))
      (> (:height info) (:height thumbnail-options))))

(defn- svg-image?
  [info]
  (= (:mtype info) "image/svg+xml"))

;; TODO: we need to properly delete temporary files.
(defn- download-media
  [url]
  (let [result (http/get! url {:as :byte-array})
        data   (:body result)
        content-type (get (:headers result) "content-type")
        format (cm/mtype->format content-type)]
    (if (nil? format)
      (ex/raise :type :validation
                :code :media-type-not-allowed
                :hint "Seems like the url points to an invalid media object.")
      (let [tempfile  (fs/create-tempfile)
            filename  (fs/name tempfile)]
        (with-open [ostream (io/output-stream tempfile)]
          (.write ostream data))
        {:filename filename
         :size (count data)
         :tempfile tempfile
         :content-type content-type}))))


(defn create-file-media-object
  [{:keys [conn storage svgc] :as cfg} {:keys [id file-id is-local name content] :as params}]
  (media/validate-media-type (:content-type content))
  (let [storage      (assoc storage :conn conn)
        source-path  (fs/path (:tempfile content))
        source-mtype (:content-type content)

        source-info  (media/run cfg {:cmd :info :input {:path source-path :mtype source-mtype}})

        thumb        (when (and (not (svg-image? source-info))
                                (big-enough-for-thumbnail? source-info))
                       (media/run cfg (assoc thumbnail-options
                                             :cmd :generic-thumbnail
                                             :input {:mtype (:mtype source-info)
                                                     :path source-path})))

        image        (if (= (:mtype source-info) "image/svg+xml")
                       (let [data (svgc (slurp source-path))]
                         (sto/put-object storage {:content (sto/content data)
                                                  :content-type (:mtype source-info)}))
                       (sto/put-object storage {:content (sto/content source-path)
                                                :content-type (:mtype source-info)}))

        thumb        (when thumb
                       (sto/put-object storage {:content (sto/content (:data thumb) (:size thumb))
                                                :content-type (:mtype thumb)}))]
    (db/insert! conn :file-media-object
                {:id (uuid/next)
                 :file-id file-id
                 :is-local is-local
                 :name name
                 :media-id (:id image)
                 :thumbnail-id (:id thumb)
                 :width  (:width source-info)
                 :height (:height source-info)
                 :mtype  source-mtype})))


;; --- Create File Media Object (from URL)

(s/def ::create-file-media-object-from-url
  (s/keys :req-un [::profile-id ::file-id ::is-local ::url]
          :opt-un [::id ::name]))

(sv/defmethod ::create-file-media-object-from-url
  [{:keys [pool] :as cfg} {:keys [profile-id file-id url name] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (let [content (download-media url)
            params' (merge params {:content content
                                   :name (or name (:filename content))})]

        (-> (assoc cfg :conn conn)
            (create-file-media-object params'))))))


;; --- Clone File Media object (Upload and create from url)

(declare clone-file-media-object)

(s/def ::clone-file-media-object
  (s/keys :req-un [::profile-id ::file-id ::is-local ::id]))

(sv/defmethod ::clone-file-media-object
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))

      (-> (assoc cfg :conn conn)
          (clone-file-media-object params)))))

(defn clone-file-media-object
  [{:keys [conn storage] :as cfg} {:keys [id file-id is-local]}]
  (let [mobj    (db/get-by-id conn :file-media-object id)

        ;; This makes the storage participate in the same transaction.
        storage (assoc storage :conn conn)

        img-obj (sto/get-object storage (:media-id mobj))
        thm-obj (when (:thumbnail-id mobj)
                  (sto/get-object storage (:thumbnail-id mobj)))

        image   (sto/clone-object storage img-obj)
        thumb   (when thm-obj
                  (sto/clone-object storage thm-obj))]

    (db/insert! conn :file-media-object
                {:id (uuid/next)
                 :file-id file-id
                 :is-local is-local
                 :name (:name mobj)
                 :media-id (:id image)
                 :thumbnail-id (:id thumb)
                 :width  (:width mobj)
                 :height (:height mobj)
                 :mtype  (:mtype mobj)})))


;; --- HELPERS

(def ^:private sql:select-file-for-update
  "select file.*,
          project.team_id as team_id
     from file
    inner join project on (project.id = file.project_id)
    where file.id = ?
      for update of file")

(defn- select-file-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-file-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))