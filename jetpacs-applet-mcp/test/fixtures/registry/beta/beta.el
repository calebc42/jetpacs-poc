;;; beta.el --- Beta fixture applet -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A registry fixture: declares an extension requirement.

;;; Code:

(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst beta-owner "beta"
  "Stable owner and app identity on the EBP wire.")

(defun beta--view ()
  "Build the applet's root screen without side effects."
  (jetpacs-chrome-screen
   "Beta"
   (jetpacs-column
    (jetpacs-text "Beta" :style "headline")
    (jetpacs-button "Refresh" (jetpacs-action "beta.refresh"))
    :spacing 16)))

(defun beta--on-refresh (_args params)
  "Queue a refresh using PARAMS, then acknowledge the action immediately."
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun beta-register ()
  "Register this applet idempotently."
  (with-jetpacs-owner beta-owner
    (jetpacs-chrome-define-root beta-owner "home" (lambda (_back) (beta--view)))
    (jetpacs-defaction "beta.refresh" #'beta--on-refresh
                       :doc "Refresh the applet's root surface"))
  (jetpacs-defapp beta-owner :label "Beta" :icon "extension"
                  :surfaces (list beta-owner)
                  :requires-extensions (quote ("jetpacs.components"))))

(defun beta-unregister ()
  "Remove this applet's live registrations."
  (jetpacs-undefaction "beta.refresh")
  (jetpacs-apps-unregister beta-owner)
  (jetpacs-chrome-remove beta-owner))

(beta-register)

(provide 'beta)
;;; beta.el ends here
