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
         :unregister glasspane-unregister)))

(provide 'jetpacs-packaged-apps)
;;; jetpacs-packaged-apps.el ends here
