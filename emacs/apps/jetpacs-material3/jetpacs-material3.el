;;; jetpacs-material3.el --- optional Material design layer for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Jetpacs's Material 3 implementation over the Jetpacs Compose-shaped
;; surface API.  Loading this file registers the renderer-owned wire nodes and
;; their builders.  Jetpacs and EBP do not require this feature: applications
;; that choose this design layer require it explicitly and declare the matching
;; `jetpacs.material3' renderer extension.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-material3-vocabulary)
(require 'jetpacs-widgets)

(defvar jetpacs-chrome-fab-menu-function nil
  "Optional Jetpacs chrome FAB-menu builder installed by a design layer.")

(jetpacs-register-renderer-extension
 jetpacs-material3-extension
 jetpacs-material3-node-schema
 jetpacs-material3-target-node-types)

(defun jetpacs-material3--enum-values (node-type member)
  "Return the enum domain of MEMBER on NODE-TYPE from the typed manifest.
The values are generated into `jetpacs-material3-typed-node-schema' from
renderer-extensions/jetpacs-material3.json, so the authoring checks and the
Companion's admission read one definition."
  (let* ((row (cdr (assoc node-type jetpacs-material3-typed-node-schema)))
         (schema (or (cdr (assoc member (plist-get row :optional)))
                     (cdr (assoc member (plist-get row :required))))))
    (unless (eq (plist-get schema :type) 'enum)
      (error "jetpacs-material3: %s.%s is not an enum member"
             node-type member))
    (plist-get schema :values)))

(defconst jetpacs--material3-assist-chip-variants
  (jetpacs-material3--enum-values "material3.assist_chip" "variant"))

(defconst jetpacs--material3-split-button-variants
  (jetpacs-material3--enum-values "material3.split_button" "variant"))

(defconst jetpacs--material3-split-button-sizes
  (jetpacs-material3--enum-values "material3.split_button" "size"))

(cl-defun jetpacs-material3-assist-chip
    (label &key on-tap icon variant enabled)
  "Build Jetpacs's Material assist chip labeled LABEL.
ON-TAP is its optional action descriptor, ICON is an icon identifier, VARIANT
is flat, elevated, suggestion, or elevated_suggestion, and ENABLED controls
interaction.  The node requires `jetpacs.material3'."
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
  "Build one Jetpacs Material split control with LABEL and ON-TAP.
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
  "Build a Jetpacs app-bar item with LABEL, ICON, and ON-TAP.
ENABLED controls both its inline and overflow presentations."
  (jetpacs-require-string label ":label")
  (jetpacs-check-identifier icon ":icon")
  (jetpacs-check-descriptor on-tap ":on-tap")
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (jetpacs-make-node nil :label label :icon icon
                     :on_tap on-tap :enabled enabled))

(defun jetpacs-material3--app-bar-strip (type items opts)
  "Build Jetpacs app-bar TYPE over ITEMS according to OPTS."
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
  "Build a horizontal Jetpacs app bar over ITEMS.
OVERFLOW-ICON names the overflow affordance; MAX-ITEMS caps inline actions."
  (jetpacs-material3--app-bar-strip
   "material3.app_bar_row" items
   (list :overflow-icon overflow-icon :max-items max-items)))

(cl-defun jetpacs-material3-app-bar-column
    (items &key overflow-icon max-items)
  "Build a vertical Jetpacs app bar over ITEMS.
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
  "Build Jetpacs's Material FAB menu over non-empty ITEMS.
ICON and CLOSE-ICON select its collapsed and expanded toggle glyphs."
  (unless items
    (error "jetpacs-material3-fab-menu: items must be non-empty"))
  (when icon (jetpacs-check-identifier icon ":icon"))
  (when close-icon (jetpacs-check-identifier close-icon ":close-icon"))
  (jetpacs-make-node "material3.fab_menu" :items (vconcat items)
                     :icon icon :close_icon close-icon))

(defconst jetpacs-material3--fab-sizes '("small" "medium" "large")
  "The icon FAB container sizes `jetpacs-material3-fab' accepts.")

(defconst jetpacs-material3--fab-colors '("primary" "secondary" "tertiary" "surface")
  "The container color families `jetpacs-material3-fab' accepts.")

(cl-defun jetpacs-material3-fab (icon on-tap &key label collapse-on-scroll size
                                      color content-description menu menu-icon)
  "Build Jetpacs's Material floating action button showing ICON, running ON-TAP.
LABEL makes it the extended FAB; COLLAPSE-ON-SCROLL folds that to its icon
while the body is scrolled away from its start.  SIZE (small, medium, large)
sizes an icon-only FAB; COLOR (primary, secondary, tertiary, surface) picks
the container family.  CONTENT-DESCRIPTION names it for assistive
technology (default: LABEL).  MENU, a list of
`jetpacs-material3-fab-menu-item', stacks a small toggle above the FAB that
fans those actions out; MENU-ICON is that toggle's glyph."
  (jetpacs-check-identifier icon ":icon")
  (jetpacs-check-descriptor on-tap ":on-tap")
  (when label (jetpacs-require-string label ":label"))
  (when collapse-on-scroll (jetpacs-check-bool collapse-on-scroll ":collapse-on-scroll"))
  (when size (setq size (jetpacs-check-enum size jetpacs-material3--fab-sizes ":size")))
  (when color (setq color (jetpacs-check-enum color jetpacs-material3--fab-colors ":color")))
  (when content-description
    (jetpacs-require-string content-description ":content-description"))
  (when menu-icon (jetpacs-check-identifier menu-icon ":menu-icon"))
  (when (and menu (> (length menu) 32))
    (error "jetpacs-material3-fab: at most 32 menu items, got %d" (length menu)))
  (jetpacs-make-node "material3.fab" :icon icon :on_tap on-tap :label label
                     :collapse_on_scroll collapse-on-scroll :size size :color color
                     :content_description content-description
                     :menu (and menu (vconcat menu)) :menu_icon menu-icon))

(defconst jetpacs-material3--chronometer-styles
  '("caption" "body" "title" "headline" "mono")
  "The text styles a chronometer takes.")

(cl-defun jetpacs-material3-chronometer (base &key count-down style color
                                              content-description)
  "Build a timer the Companion ticks itself from BASE, with no per-tick push.
BASE is a Lisp time value or epoch milliseconds: the start of an elapsed
timer, or with COUNT-DOWN the end it counts down to (held at zero past
it).  STYLE is a text style (caption, body, title, headline, mono);
COLOR a role or #hex, as text takes.  CONTENT-DESCRIPTION is spoken
before the time."
  (let ((ms (if (integerp base)
                base
              (truncate (* 1000 (float-time base))))))
    (unless (<= 0 ms 253402300799999)
      (error "jetpacs-material3-chronometer: base out of range: %S" base))
    (when count-down (jetpacs-check-bool count-down ":count-down"))
    (when style
      (setq style (jetpacs-check-enum style jetpacs-material3--chronometer-styles
                                      ":style")))
    (when color (jetpacs-require-string color ":color"))
    (when content-description
      (jetpacs-require-string content-description ":content-description"))
    (jetpacs-make-node "material3.chronometer" :base_ms ms
                       :count_down count-down :style style :color color
                       :content_description content-description)))

(defun jetpacs-material3-chrome-fab-menu (items)
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
        #'jetpacs-material3-chrome-fab-menu))

(provide 'jetpacs-material3)
;;; jetpacs-material3.el ends here
