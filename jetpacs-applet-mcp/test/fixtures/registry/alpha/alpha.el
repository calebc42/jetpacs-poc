;;; alpha.el --- Alpha fixture applet -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A registry fixture: depends on the beta item.

;;; Code:

(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst alpha-owner "alpha"
  "Stable owner and app identity on the EBP wire.")

(defun alpha--view ()
  "Build the applet's root screen without side effects."
  (jetpacs-chrome-screen
   "Alpha"
   (jetpacs-column
    (jetpacs-text "Alpha" :style "headline")
    (jetpacs-button "Refresh" (jetpacs-action "alpha.refresh"))
    :spacing 16)))

(defun alpha--on-refresh (_args params)
  "Queue a refresh using PARAMS, then acknowledge the action immediately."
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun alpha-register ()
  "Register this applet idempotently."
  (with-jetpacs-owner alpha-owner
    (jetpacs-chrome-define-root alpha-owner "home" (lambda (_back) (alpha--view)))
    (jetpacs-defaction "alpha.refresh" #'alpha--on-refresh
                       :doc "Refresh the applet's root surface"))
  (jetpacs-defapp alpha-owner :label "Alpha" :icon "extension"
                  :surfaces (list alpha-owner)))

(defun alpha-unregister ()
  "Remove this applet's live registrations."
  (jetpacs-undefaction "alpha.refresh")
  (jetpacs-apps-unregister alpha-owner)
  (jetpacs-chrome-remove alpha-owner))

(alpha-register)

(provide 'alpha)
;;; alpha.el ends here
