;;; atload.el --- Atload for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A fixture applet that wrongly registers when its file loads: the
;; static validator must report `applet.register-at-load' and the trusted
;; launcher must refuse it.

;;; Code:

(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-shell)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst atload-owner "atload"
  "Stable owner and app identity on the EBP wire.")

(defun atload--view ()
  "Build the applet's root screen without side effects."
  (jetpacs-chrome-screen
   "Atload"
   (jetpacs-column
    (jetpacs-text "Atload from Atload" :style "headline")
    (jetpacs-button
     "Refresh"
     (jetpacs-action "atload.refresh"))
    :spacing 16)))

(defun atload--on-refresh (_args params)
  "Queue a refresh using PARAMS, then acknowledge the action immediately."
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun atload-register ()
  "Register this applet idempotently."
  (with-jetpacs-owner atload-owner
    (jetpacs-chrome-define-root atload-owner "home"
                                (lambda (_back) (atload--view)))
    (jetpacs-defaction "atload.refresh" #'atload--on-refresh
                       :doc "Refresh the applet's root surface"))
  (jetpacs-defapp atload-owner
                  :label "Atload"
                  :icon "extension"
                  :surfaces (list atload-owner)))

(defun atload-unregister ()
  "Remove this applet's live registrations."
  (jetpacs-undefaction "atload.refresh")
  (jetpacs-apps-unregister atload-owner)
  (jetpacs-chrome-remove atload-owner))

(atload-register)

(provide 'atload)
;;; atload.el ends here
