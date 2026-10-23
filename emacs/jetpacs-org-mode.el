;;; jetpacs-org-mode.el --- Org Mode app for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The composition root for Org Mode as a Jetpacs App.  The reusable
;; reader/editor hosts know nothing about Org; the two Org adapters rent
;; built-in Org behavior; this file registers them together, gives the
;; experience app identity and navigation, and composes the existing
;; Files and Habits surfaces.  Capture, recurring agenda reminders and
;; the table/Babel verbs live here because they belong to the Org app as
;; a whole, not to one document presentation.  Org itself resolves
;; document links; the Org
;; reader then reuses Files' existing `edit' screen for same-file targets
;; and routes other files back through Files.  Link navigation therefore
;; retains the document's top bar, FAB, path policy, and synchronized editor
;; identity instead of inventing an "Org link" screen.  This is the precedent for a future
;; `jetpacs-elisp-mode.el': a mode app supplies adapters and a thin
;; composition root, not another editor protocol or file browser.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'calendar)
(require 'org-agenda)
(require 'ebp-org)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-device)
(require 'jetpacs-apps)
(require 'jetpacs-files)
(require 'jetpacs-reader)
(require 'jetpacs-editor)
(require 'jetpacs-reader-org)
(require 'jetpacs-editor-org)
(require 'jetpacs-org-habits)
(require 'jetpacs-org-reminders)
(require 'jetpacs-org-capture)
(require 'jetpacs-org-clock)
(require 'jetpacs-org-table)

(defconst jetpacs-org-mode-owner "org-mode"
  "Owner and app id for the Org Mode app.")

(defconst jetpacs-org-mode-title "Org Mode"
  "Human-facing title of the Org Mode app.")

(defconst jetpacs-org-mode-icon "description"
  "Material icon used for the Org Mode app.")

(defvar jetpacs-org-mode--registered nil
  "Non-nil while the Org Mode app is registered.")

(defconst jetpacs-org-mode--asset-directory
  (let* ((library (or load-file-name (locate-library "jetpacs-org-mode")))
         (base (and library (file-name-directory library))))
    (and base
         (cl-find-if
          #'file-directory-p
          (list (expand-file-name "org" base)
                (expand-file-name "../org" base)))))
  "Distribution directory containing the Org starter and manual bundle.
The first location is the flattened device install; the second is the
source-tree layout.")

(defun jetpacs-org-mode-seed-paths ()
  "Return destination paths for the starter inbox and bundled manual."
  (let* ((root (file-name-as-directory (expand-file-name org-directory)))
         (manual-dir (expand-file-name "org-mode-walkthrough" root)))
    (list :root root
          :inbox (expand-file-name "inbox.org" root)
          :manual-directory manual-dir
          :manual (expand-file-name "orgro-manual.org" manual-dir))))

(defun jetpacs-org-mode--copy-missing (source destination)
  "Recursively copy SOURCE to DESTINATION without overwriting anything."
  (cond
   ((file-directory-p source)
    (make-directory destination t)
    (dolist (entry (directory-files
                    source t directory-files-no-dot-files-regexp))
      (jetpacs-org-mode--copy-missing
       entry (expand-file-name (file-name-nondirectory entry) destination))))
   ((and (file-regular-p source) (not (file-exists-p destination)))
    (make-directory (file-name-directory destination) t)
    (copy-file source destination nil t))))

(defun jetpacs-org-mode-seed ()
  "Seed the starter inbox and complete Orgro manual bundle.
Files land below `org-directory'. Existing destinations are never
overwritten, making this safe and idempotent. Remote Org directories
and installations missing their distributed assets are left untouched.
Return the paths plist from `jetpacs-org-mode-seed-paths', or nil."
  (let* ((paths (jetpacs-org-mode-seed-paths))
         (root (plist-get paths :root)))
    (cond
     ((file-remote-p root)
      (message "jetpacs-org-mode: refusing to seed a remote Org directory")
      nil)
     ((not (and jetpacs-org-mode--asset-directory
                (file-directory-p jetpacs-org-mode--asset-directory)))
      (message "jetpacs-org-mode: seed assets are unavailable")
      nil)
     (t
      (make-directory root t)
      (jetpacs-org-mode--copy-missing
       (expand-file-name "inbox.org" jetpacs-org-mode--asset-directory)
       (plist-get paths :inbox))
      (jetpacs-org-mode--copy-missing
       (expand-file-name
        "org-mode-walkthrough" jetpacs-org-mode--asset-directory)
       (plist-get paths :manual-directory))
      (ebp-org-cache-invalidate)
      paths))))

(defun jetpacs-org-mode--seed-safely ()
  "Seed app content while keeping an asset failure local to the app."
  (condition-case err
      (jetpacs-org-mode-seed)
    (error
     (message "jetpacs-org-mode: seed failed: %s"
              (jetpacs-error-label err))
     nil)))

(defun jetpacs-org-mode--surface (owner)
  "Return OWNER's negotiated surface name."
  (jetpacs-shell-surface-for owner))

(defun jetpacs-org-mode--open-seed-action (document)
  "Build the Files handoff action for seeded DOCUMENT.
Opening the cached Files surface is the receiver-local half of the gesture;
`org-mode.open-seed' still performs the authoritative seed, path validation,
document-host selection, and content publication in Emacs."
  (jetpacs-shell-action-opening-surface
   "org-mode.open-seed"
   (jetpacs-org-mode--surface jetpacs-files-owner)
   :args (list :document document)))

(defun jetpacs-org-mode--on-open-seed (args params)
  "Open a known seeded document selected by ARGS through the Files app."
  (let ((document (plist-get args :document)))
    (cond
     ((not (member document '("manual" "inbox"))) 'rejected)
     ((jetpacs-event-stale-p params) 'stale)
     (t
      (condition-case err
          (let* ((paths (jetpacs-org-mode-seed))
                 (path (and paths
                            (plist-get paths
                                       (if (equal document "manual")
                                           :manual :inbox)))))
            (if (not (stringp path))
                'rejected
              ;; This is intentionally the exact public path used by a Files
              ;; row—not a look-alike document view on the Org surface.  It
              ;; performs the same root check, canonicalization, document-host
              ;; selection, and navigation on the canonical Files surface.
              (jetpacs-files-open-path
               path (jetpacs-org-mode--surface jetpacs-files-owner))))
        (error
         (message "jetpacs-org-mode: opening seed failed: %s"
                  (jetpacs-error-label err))
         'rejected))))))

(defun jetpacs-org-mode--screen (back)
  "Build the Org Mode app home screen with BACK navigation."
  (jetpacs-chrome-screen
   jetpacs-org-mode-title
   (jetpacs-lazy-column
    (jetpacs-text
     "A complete Org app powered by Emacs itself: reader mode, visibility cycling, search and sparse trees; whole-file or narrowed editing; structured edits, links, media, citations, attachments, LaTeX, tables, Org Crypt, capture, and recurring reminders."
     :style "body")
    (jetpacs-chrome-row
     "Open Org files"
     :subtitle "Browse, read, and edit .org documents"
     :icon "folder_open"
     :on-tap (jetpacs-shell-open-surface-action
              (jetpacs-org-mode--surface jetpacs-files-owner))
     :key "org-mode-files")
    (jetpacs-chrome-row
     "Orgro manual"
     :subtitle "Bundled upstream guide and feature examples"
     :icon "menu_book"
     :on-tap (jetpacs-org-mode--open-seed-action "manual")
     :key "org-mode-manual")
    (jetpacs-chrome-row
     "Starter inbox"
     :subtitle "A safe, editable seed created only when missing"
     :icon "inbox"
     :on-tap (jetpacs-org-mode--open-seed-action "inbox")
     :key "org-mode-inbox")
    (jetpacs-chrome-row
     "Quick capture"
     :subtitle "Use your built-in org-capture templates"
     :icon "add_task"
     :on-tap (jetpacs-action "org.capture.show")
     :key "org-mode-capture")
    (jetpacs-chrome-row
     "Habits"
     :subtitle "Consistency graphs from built-in org-habit"
     :icon "event_repeat"
     :on-tap (jetpacs-shell-open-surface-action
              (jetpacs-org-mode--surface jetpacs-org-habits-owner))
     :key "org-mode-habits")
    (jetpacs-card
     (jetpacs-column
      (jetpacs-text "Built-in-backed" :style "title")
      (jetpacs-text
       "Org parses and edits the document; Jetpacs only presents it. Timed agenda entries become recurring-aware device reminders when permission is granted. The bundled walkthrough is Orgro's upstream manual; its transclusion section is retained as source documentation, but transclusion remains Jetpacs' sole intentional omission because it is not built into Emacs."
       :style "body")
      :spacing 6))
    :spacing 12)
   :back back))

(defun jetpacs-org-mode--claimed-surfaces ()
  "Owners whose surfaces form the Org Mode app experience."
  (list jetpacs-org-mode-owner
        jetpacs-files-owner
        jetpacs-org-habits-owner))

(defun jetpacs-org-mode--dock-items (surface)
  "Return the Org Mode dock destination for SURFACE."
  (let ((selected
         (member surface
                 (mapcar #'jetpacs-org-mode--surface
                         (jetpacs-org-mode--claimed-surfaces)))))
    (list
     (list :label jetpacs-org-mode-title
           :icon jetpacs-org-mode-icon
           :on-tap (jetpacs-shell-open-surface-action
                    (jetpacs-org-mode--surface jetpacs-org-mode-owner))
           :selected (and selected t)))))

(defun jetpacs-org-mode-register ()
  "Register the Org adapters, root surface, and app identity."
  (jetpacs-org-reminders-register)
  (jetpacs-org-capture-register)
  (jetpacs-org-clock-register)
  (unless jetpacs-org-mode--registered
    (setq jetpacs-org-mode--registered t)
    (jetpacs-reader-install)
    (jetpacs-editor-install)
    (jetpacs-reader-org-register)
    (jetpacs-editor-org-register)
    (with-jetpacs-owner jetpacs-org-mode-owner
      (jetpacs-defaction "org-mode.open-seed"
                         #'jetpacs-org-mode--on-open-seed
                         :doc "Open bundled Org Mode content")
      (jetpacs-chrome-define-root jetpacs-org-mode-owner "home"
                                  #'jetpacs-org-mode--screen))
    (jetpacs-defapp
     jetpacs-org-mode-owner
     :label jetpacs-org-mode-title
     :icon jetpacs-org-mode-icon
     :surfaces (jetpacs-org-mode--claimed-surfaces)
     :dock #'jetpacs-org-mode--dock-items
     :order 40))
  (unless noninteractive
    (jetpacs-org-mode--seed-safely))
  t)

(defun jetpacs-org-mode-unregister ()
  "Unregister the Org Mode app and its mode adapters."
  (jetpacs-org-reminders-unregister)
  (jetpacs-org-capture-unregister)
  (jetpacs-org-clock-unregister)
  (when jetpacs-org-mode--registered
    (setq jetpacs-org-mode--registered nil)
    (jetpacs-undefaction "org-mode.open-seed")
    (jetpacs-apps-unregister jetpacs-org-mode-owner)
    (jetpacs-chrome-remove jetpacs-org-mode-owner)
    (jetpacs-reader-org-unregister)
    (jetpacs-editor-org-unregister))
  t)

(jetpacs-org-mode-register)

;;;###autoload
(defun jetpacs-org-mode ()
  "Open the Org Mode app on the connected device."
  (interactive)
  (jetpacs-org-mode--seed-safely)
  (jetpacs-shell-push jetpacs-org-mode-owner))

(defun jetpacs-org-mode-unload-function ()
  "Unload hygiene for the Org Mode app."
  (jetpacs-org-mode-unregister)
  nil)

(provide 'jetpacs-org-mode)
;;; jetpacs-org-mode.el ends here
