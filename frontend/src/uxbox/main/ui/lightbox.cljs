(ns uxbox.main.ui.lightbox
  (:require
   [goog.events :as events]
   [lentes.core :as l]
   [rumext.core :as mx]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as k]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

;; --- Refs

(def ^:private lightbox-ref
  (-> (l/key :lightbox)
      (l/derive st/state)))

;; --- Lightbox (Component)

(defmulti render-lightbox :name)
(defmethod render-lightbox :default [_] nil)

(defn- on-esc-clicked
  [event]
  (when (k/esc? event)
    (udl/close!)
    (dom/stop-propagation event)))

(defn- on-out-clicked
  [own event]
  (let [parent (mx/ref-node (::parent own))
        current (dom/get-target event)]
    (when (dom/equals? parent current)
      (st/emit! (udl/hide-lightbox)))))

(mx/def lightbox
  :mixins #{mx/reactive}

  :init
  (fn [own props]
    (let [key (events/listen js/document EventType.KEYDOWN on-esc-clicked)]
      (assoc own
             ::key-listener key
             ::parent (mx/create-ref))))

  :will-unmount
  (fn [own]
    (events/unlistenByKey (::key-listener own))
    (dissoc own ::key-listener))

  :render
  (fn [own props]
    (let [data (mx/react lightbox-ref)
          classes (classnames
                   :hide (nil? data)
                   :transparent (:transparent? data))]
      [:div.lightbox {:class classes
                      :ref (::parent own)
                      :on-click (partial on-out-clicked own)}
       (render-lightbox data)])))
