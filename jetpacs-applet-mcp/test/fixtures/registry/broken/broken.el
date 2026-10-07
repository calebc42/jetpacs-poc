;;; broken.el --- Broken fixture applet -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A registry fixture: depends on the beta item.

;;; Code:

(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst broken-owner "broken"
  "Stable owner and app identity on the EBP wire.")

(defun broken--view ()
  "Build the applet's root screen without side effects."
  (jetpacs-chrome-screen
   "Broken"
   (jetpacs-column
    (jetpacs-text "Broken" :style "headline")
    (jetpacs-button "Refresh" (jetpacs-action "broken.refresh"))
    :spacing 16)))

(defun broken--on-refresh (_args params)
  "Queue a refresh using PARAMS, then acknowledge the action immediately."
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun broken-register ()
  "Register this applet idempotently."
  (with-jetpacs-owner broken-owner
    (jetpacs-chrome-define-root broken-owner "home" (lambda (_back) (broken--view)))
    (jetpacs-defaction "broken.refresh" #'broken--on-refresh
                       :doc "Refresh the applet's root surface"))
  (jetpacs-defapp broken-owner :label "Broken" :icon "extension"
                  :surfaces (list broken-owner)))

(defun broken-unregister ()
  "Remove this applet's live registrations."
  (jetpacs-undefaction "broken.refresh")
  (jetpacs-apps-unregister broken-owner)
  (jetpacs-chrome-remove broken-owner))

(broken-register)

;;; broken.el ends here
