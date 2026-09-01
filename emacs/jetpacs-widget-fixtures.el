;;; jetpacs-widget-fixtures.el --- Visual acceptance widgets -*- lexical-binding: t; coding: utf-8 -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Deterministic widget documents used to judge Jetpacs' generic Glance
;; rendering against Grove's compact Capture and Agenda presentation.  They
;; deliberately contain no Android-side Grove semantics: every tap remains an
;; opaque EBP ActionDescriptor that an Emacs applet may register or replace.

;;; Code:

(require 'jetpacs-widgets)

(defun jetpacs-grove-capture-widget-fixture ()
  "Return the deterministic Grove Capture visual-acceptance SurfaceSpec."
  (jetpacs-widget-surface
   "Capture"
   (jetpacs-box
    (jetpacs-text "\u2731  Capture")
    :on-tap (jetpacs-action "grove.capture" :ttl-s 86400 :when-offline 'wake))
   :empty (jetpacs-text "Capture unavailable")))

(defun jetpacs-grove-agenda-widget-fixture ()
  "Return the deterministic Grove Agenda visual-acceptance SurfaceSpec."
  (jetpacs-widget-surface
   "Agenda"
   (jetpacs-lazy-column
    (jetpacs-column
     (jetpacs-text "TODAY  2")
     (jetpacs-divider)
     (jetpacs-box
      (jetpacs-text "TODO Write project brief")
      (jetpacs-text "09:00 \u00b7 work.org")
      :on-tap (jetpacs-action "grove.agenda.open"))))
   :empty (jetpacs-text "Nothing scheduled")
   :header-action
   (jetpacs-action "grove.capture" :ttl-s 86400 :when-offline 'wake)
   :size-variants
   (list
    (jetpacs-widget-size-variant
     280 160
     (jetpacs-column
      (jetpacs-text "Agenda")
      (jetpacs-text "2 today \u00b7 5 in 7 days")
      (jetpacs-divider)
      (jetpacs-text "TODO Write project brief")))
    (jetpacs-widget-size-variant
     180 80
     (jetpacs-column
      (jetpacs-text "Agenda \u00b7 2 today")
      (jetpacs-divider)
      (jetpacs-text "TODO Write project brief"))))))

(provide 'jetpacs-widget-fixtures)
;;; jetpacs-widget-fixtures.el ends here
