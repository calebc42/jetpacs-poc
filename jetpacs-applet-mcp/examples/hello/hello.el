;;; hello.el --- Hello for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A minimal Tier-1 Jetpacs applet and the reference output shape of
;; `jetpacs_applet_template'.

;;; Code:

(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-shell)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst hello-owner "hello"
  "Stable owner and app identity on the EBP wire.")

(defun hello--view ()
  "Build the applet's root screen without side effects."
  (jetpacs-chrome-screen
   "Hello"
   (jetpacs-column
    (jetpacs-text "Hello from Hello" :style "headline")
    (jetpacs-button
     "Refresh"
     (jetpacs-action "hello.refresh"))
    :spacing 16)))

(defun hello--on-refresh (_args params)
  "Queue a refresh using PARAMS, then acknowledge the action immediately."
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun hello-register ()
  "Register this applet idempotently."
  (with-jetpacs-owner hello-owner
    (jetpacs-chrome-define-root hello-owner "home"
                                (lambda (_back) (hello--view)))
    (jetpacs-defaction "hello.refresh" #'hello--on-refresh
                       :doc "Refresh the applet's root surface"))
  (jetpacs-defapp hello-owner
                  :label "Hello"
                  :icon "extension"
                  :surfaces (list hello-owner)))

(defun hello-unregister ()
  "Remove this applet's live registrations."
  (jetpacs-undefaction "hello.refresh")
  (jetpacs-apps-unregister hello-owner)
  (jetpacs-chrome-remove hello-owner))

(hello-register)

(provide 'hello)
;;; hello.el ends here
