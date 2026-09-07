;;; jetpacs-packaged-apps.el --- APK optional-app manifest -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Distribution composition, not activation.  This manifest is shipped in the
;; APK beside the app source and tells the generic Apps screen what it may
;; enable.  It intentionally names functions as symbols and requires no app
;; feature, so loading Jetpacs on a fresh install leaves every entry inactive.

;;; Code:

(defvar jetpacs-app-store-packaged-apps)

(setq jetpacs-app-store-packaged-apps
      '((:name "glasspane.el"
         :label "Glasspane"
         :icon "menu_book"
         :summary "Org-powered projects, areas, resources, and archives — bundled with Jetpacs"
         :feature glasspane
         :app-id "glasspane"
         :register glasspane-register
         :unregister glasspane-unregister)
        (:name "grove.el"
         :label "Grove"
         :icon "forest"
         :summary "Notebooks, editing, search, agenda, capture, and widgets powered by Emacs Org"
         :feature grove
         :app-id "grove"
         :register grove-register
         :unregister grove-unregister)
        (:name "orgzly.el"
         :label "Orgzly"
         :icon "book"
         :summary "Org notebooks, outlines, search, agenda and editing with Foundation styles"
         :feature orgzly
         :app-id "orgzly"
         :register orgzly-register
         :unregister orgzly-unregister)
        (:name "harp.el"
         :label "Harp"
         :icon "favorite"
         :summary "Org-backed health journals, metrics, documents and medication records"
         :feature harp
         :app-id "harp"
         :register harp-register
         :unregister harp-unregister)))

(provide 'jetpacs-packaged-apps)
;;; jetpacs-packaged-apps.el ends here
