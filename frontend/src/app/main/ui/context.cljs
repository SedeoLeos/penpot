;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.context
  (:require
   [rumext.alpha :as mf]))

(def render-ctx (mf/create-context nil))
(def def-ctx (mf/create-context false))

(def current-route      (mf/create-context nil))
(def current-profile    (mf/create-context nil))
(def current-team-id    (mf/create-context nil))
(def current-project-id (mf/create-context nil))
(def current-page-id    (mf/create-context nil))
(def current-file-id    (mf/create-context nil))
(def current-scroll     (mf/create-context nil))
(def current-zoom       (mf/create-context nil))

(def active-frames-ctx  (mf/create-context nil))
(def render-thumbnails  (mf/create-context nil))
