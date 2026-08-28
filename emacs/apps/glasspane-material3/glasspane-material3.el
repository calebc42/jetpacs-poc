;;; glasspane-material3.el --- optional Material design layer for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Glasspane's Material 3 implementation over the Jetpacs Compose-shaped
;; surface API.  Loading this file registers the renderer-owned wire nodes and
;; their builders.  Jetpacs and EBP do not require this feature: applications
;; that choose this design layer require it explicitly and declare the matching
;; `glasspane.material3' renderer extension.

;;; Code:

(require 'cl-lib)
(require 'glasspane-material3-vocabulary)
(require 'jetpacs-widgets)

(defvar jetpacs-chrome-fab-menu-function nil
  "Optional Jetpacs chrome FAB-menu builder installed by a design layer.")

(jetpacs-register-renderer-extension
 glasspane-material3-extension
 glasspane-material3-node-schema
 glasspane-material3-target-node-types)

(defconst jetpacs--material3-assist-chip-variants
  '("flat" "elevated" "suggestion" "elevated_suggestion"))

(defconst jetpacs--material3-split-button-variants
  '("filled" "tonal" "elevated" "outlined"))

(defconst jetpacs--material3-split-button-sizes
  '("xsmall" "small" "medium" "large" "xlarge"))

(cl-defun jetpacs-material3-assist-chip
    (label &key on-tap icon variant enabled)
  "Build Glasspane's Material assist chip labeled LABEL.
ON-TAP is its optional action descriptor, ICON is an icon identifier, VARIANT
is flat, elevated, suggestion, or elevated_suggestion, and ENABLED controls
interaction.  The node requires `glasspane.material3'."
  (jetpacs-require-string label ":label")
  (when on-tap (jetpacs-check-descriptor on-tap ":on-tap"))
  (when icon (jetpacs-check-identifier icon ":icon"))
  (when variant
    (setq variant
          (jetpacs-check-enum variant
                              jetpacs--material3-assist-chip-variants
                              ":variant")))
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (jetpacs-make-node "material3.assist_chip"
                     :label label :on_tap on-tap :icon icon
                     :variant variant :enabled enabled))

(cl-defun jetpacs-material3-split-button
    (label on-tap &key icon variant size trailing-icon trailing-label
           trailing-description checked on-change on-trailing-tap items enabled)
  "Build one Glasspane Material split control with LABEL and ON-TAP.
ICON may replace a missing LABEL.  VARIANT and SIZE select the Material face.
TRAILING-ICON, TRAILING-LABEL, and TRAILING-DESCRIPTION describe the trailing
half.  ITEMS, CHECKED with ON-CHANGE, or ON-TRAILING-TAP choose its behavior.
ENABLED controls both halves.  A checked node is stateful and therefore needs
an `:id' attached through `jetpacs-with-attrs'."
  (if label
      (jetpacs-require-string label ":label")
    (unless icon
      (error "jetpacs-material3-split-button: the leading half needs a :label, an icon, or both")))
  (jetpacs-check-descriptor on-tap ":on-tap")
  (when icon (jetpacs-check-identifier icon ":icon"))
  (when variant
    (setq variant
          (jetpacs-check-enum variant
                              jetpacs--material3-split-button-variants
                              ":variant")))
  (when size
    (setq size
          (jetpacs-check-enum size jetpacs--material3-split-button-sizes
                              ":size")))
  (when trailing-icon
    (jetpacs-check-identifier trailing-icon ":trailing-icon"))
  (when trailing-label
    (jetpacs-require-string trailing-label ":trailing-label"))
  (when trailing-description
    (jetpacs-require-string trailing-description ":trailing-description"))
  (when checked (jetpacs-check-bool checked ":checked"))
  (when on-change (jetpacs-check-descriptor on-change ":on-change"))
  (when on-trailing-tap
    (jetpacs-check-descriptor on-trailing-tap ":on-trailing-tap"))
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (jetpacs-make-node "material3.split_button"
                     :label label :on_tap on-tap :icon icon
                     :variant variant :size size
                     :trailing_icon trailing-icon
                     :trailing_label trailing-label
                     :trailing_description trailing-description
                     :checked checked :on_change on-change
                     :on_trailing_tap on-trailing-tap
                     :items (and items (vconcat items))
                     :enabled enabled))

(cl-defun jetpacs-app-bar-item (label icon on-tap &key enabled)
  "Build a Glasspane app-bar item with LABEL, ICON, and ON-TAP.
ENABLED controls both its inline and overflow presentations."
  (jetpacs-require-string label ":label")
  (jetpacs-check-identifier icon ":icon")
  (jetpacs-check-descriptor on-tap ":on-tap")
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (jetpacs-make-node nil :label label :icon icon
                     :on_tap on-tap :enabled enabled))

(defun jetpacs-material3--app-bar-strip (type items opts)
  "Build Glasspane app-bar TYPE over ITEMS according to OPTS."
  (let ((overflow-icon (plist-get opts :overflow-icon))
        (max-items (plist-get opts :max-items)))
    (unless items
      (error "jetpacs-%s: items must be non-empty"
             (string-replace "_" "-" type)))
    (when overflow-icon
      (jetpacs-check-identifier overflow-icon ":overflow-icon"))
    (when max-items (jetpacs-check-integer max-items ":max-items" 1 nil))
    (jetpacs-make-node type :items (vconcat items)
                       :overflow_icon overflow-icon :max_items max-items)))

(cl-defun jetpacs-material3-app-bar-row
    (items &key overflow-icon max-items)
  "Build a horizontal Glasspane app bar over ITEMS.
OVERFLOW-ICON names the overflow affordance; MAX-ITEMS caps inline actions."
  (jetpacs-material3--app-bar-strip
   "material3.app_bar_row" items
   (list :overflow-icon overflow-icon :max-items max-items)))

(cl-defun jetpacs-material3-app-bar-column
    (items &key overflow-icon max-items)
  "Build a vertical Glasspane app bar over ITEMS.
OVERFLOW-ICON names the overflow affordance; MAX-ITEMS caps inline actions."
  (jetpacs-material3--app-bar-strip
   "material3.app_bar_column" items
   (list :overflow-icon overflow-icon :max-items max-items)))

(cl-defun jetpacs-material3-fab-menu-item (label icon on-tap)
  "Build one Material FAB-menu item with LABEL, ICON, and ON-TAP."
  (jetpacs-require-string label ":label")
  (jetpacs-check-identifier icon ":icon")
  (jetpacs-check-descriptor on-tap ":on-tap")
  (jetpacs-make-node nil :label label :icon icon :on_tap on-tap))

(cl-defun jetpacs-material3-fab-menu (items &key icon close-icon)
  "Build Glasspane's Material FAB menu over non-empty ITEMS.
ICON and CLOSE-ICON select its collapsed and expanded toggle glyphs."
  (unless items
    (error "jetpacs-material3-fab-menu: items must be non-empty"))
  (when icon (jetpacs-check-identifier icon ":icon"))
  (when close-icon (jetpacs-check-identifier close-icon ":close-icon"))
  (jetpacs-make-node "material3.fab_menu" :items (vconcat items)
                     :icon icon :close_icon close-icon))

(defun glasspane-material3-chrome-fab-menu (items)
  "Build the shell FAB menu from normalized chrome ITEMS."
  (jetpacs-material3-fab-menu
   (mapcar (lambda (item)
             (jetpacs-material3-fab-menu-item
              (plist-get item :label)
              (plist-get item :icon)
              (plist-get item :on-tap)))
           items)
   :icon "more_vert"))

(with-eval-after-load 'jetpacs-chrome
  (setq jetpacs-chrome-fab-menu-function
        #'glasspane-material3-chrome-fab-menu))

(provide 'glasspane-material3)
;;; glasspane-material3.el ends here
