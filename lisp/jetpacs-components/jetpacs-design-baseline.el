;;; jetpacs-design-baseline.el --- the platform's own design profile -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The design profile Jetpacs wears when nothing more specific is active:
;; quiet Material, tidied.  System type, Material's radii, flat rows with
;; hairlines instead of a card around everything, and a palette that
;; resolves the EBP theme roles so Modus and ef themes, light and dark,
;; keep working.  Every closed component slot is bound, so a screen that is
;; presented through this profile has one authored voice rather than a
;; mix of profile and receiver defaults.
;;
;; `jetpacs-design-present' is the one step any screen takes to wear the
;; active profile: it is a no-op unless the receiver advertises the design
;; runtime, and it never wraps a screen that is already presented.  The
;; host chrome installs it as its presentation seam, so every
;; `jetpacs-chrome-screen' in every app takes the profile without naming
;; this package.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-surfaces)
(require 'jetpacs-design)
(require 'jetpacs-design-profiles)

(defconst jetpacs-design-baseline-id "jetpacs.baseline"
  "Profile ID of the platform baseline.")

(defconst jetpacs-design-baseline-typography
  '(("headline" . (:family "system" :size 24 :weight 400
                   :line-height 32 :letter-spacing 0))
    ("title" . (:family "system" :size 22 :weight 400
                :line-height 28 :letter-spacing 0))
    ("item-title" . (:family "system" :size 16 :weight 500
                     :line-height 24 :letter-spacing 0))
    ("body" . (:family "system" :size 16 :weight 400
               :line-height 24 :letter-spacing 0))
    ("supporting" . (:family "system" :size 14 :weight 400
                     :line-height 20 :letter-spacing 0))
    ("label" . (:family "system" :size 14 :weight 500
                :line-height 20 :letter-spacing 0))
    ("caption" . (:family "system" :size 12 :weight 400
                  :line-height 16 :letter-spacing 0))
    ("overline" . (:family "system" :size 11 :weight 500
                   :line-height 16 :letter-spacing 0.5))
    ("code" . (:family "plex-mono" :size 14 :weight 400
               :line-height 20 :letter-spacing 0))
    ("metadata" . (:family "plex-mono" :size 12 :weight 400
                   :line-height 16 :letter-spacing 0)))
  "Material's type scale on the system face; code and metadata in Plex Mono.")

(defun jetpacs-design-baseline--tokens ()
  "Theme-role colors, Material spacing and radii."
  (cl-flet ((role (name) (jetpacs-design-theme-role name))
            (dim (n) (jetpacs-design-dimension n)))
    `(("color.background" . ,(role "background"))
      ("color.on-background" . ,(role "on_background"))
      ("color.surface" . ,(role "surface"))
      ("color.on-surface" . ,(role "on_surface"))
      ("color.primary" . ,(role "primary"))
      ("color.on-primary" . ,(role "on_primary"))
      ("color.secondary" . ,(role "secondary"))
      ("color.on-secondary" . ,(role "on_secondary"))
      ("color.outline" . ,(role "outline"))
      ("color.error" . ,(role "error"))
      ("space.control-x" . ,(dim 16))
      ("space.control-y" . ,(dim 8))
      ("space.panel" . ,(dim 16))
      ("radius.control" . ,(dim 8))
      ("radius.panel" . ,(dim 12))
      ("radius.pill" . ,(dim 20)))))

(defun jetpacs-design-baseline--styles ()
  "The named faces; every color goes through a `color.*' token."
  (cl-flet ((tok (name) (jetpacs-design-token name))
            (dim (n) (jetpacs-design-dimension n))
            (num (n) (jetpacs-design-number n)))
    `(("base.screen"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.background"))
             ("content_color" . ,(tok "color.on-background"))
             ("fill_width" . ,(jetpacs-design-boolean t)))))
      ;; A surface is a hairline-bordered panel, never elevated.
      ("base.surface"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.surface"))
             ("content_color" . ,(tok "color.on-surface"))
             ("border_color" . ,(tok "color.outline"))
             ("border_width" . ,(dim 1))
             ("corner_radius" . ,(tok "radius.panel"))
             ("padding" . ,(tok "space.panel")))))
      ("base.popup"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.surface"))
             ("content_color" . ,(tok "color.on-surface"))
             ("border_color" . ,(tok "color.outline"))
             ("border_width" . ,(dim 1))
             ("corner_radius" . ,(tok "radius.control")))))
      ;; Bound, but says nothing: the toolkit keeps its own value.  The
      ;; wire carries no container roles, so a rail indicator painted from
      ;; `secondary' is a saturated pill where Material's is a pale one.
      ("base.toolkit" . ,(jetpacs-design-style nil))
      ;; A toast inverts the surface so it reads over any content.
      ("base.toast"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.on-surface"))
             ("content_color" . ,(tok "color.surface"))
             ("corner_radius" . ,(tok "radius.control")))))
      ;; A row is a region of a list, not an object floating above one:
      ;; no fill, no border, Material's one-line list metrics.  Its
      ;; interaction faces stay the receiver's own.
      ("base.row"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.on-surface"))
             ("corner_radius" . ,(tok "radius.control"))
             ("padding_horizontal" . ,(tok "space.control-x"))
             ("padding_vertical" . ,(tok "space.control-y"))
             ("min_height" . ,(dim 56)))
           :rules
           (list
            (jetpacs-design-rule
             "selected"
             `(("background_color" . ,(tok "color.secondary"))
               ("content_color" . ,(tok "color.on-secondary")))
             :motion "selection")
            (jetpacs-design-rule
             "disabled" `(("alpha" . ,(num 0.38))) :motion "quick"))))
      ("base.interactive"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.on-surface"))
             ("corner_radius" . ,(tok "radius.control"))
             ("padding_horizontal" . ,(dim 12))
             ("padding_vertical" . ,(tok "space.control-y"))
             ("min_height" . ,(dim 48)))
           :rules
           (list
            (jetpacs-design-rule
             "focused"
             `(("border_color" . ,(tok "color.primary"))
               ("border_width" . ,(dim 2)))
             :motion "quick")
            (jetpacs-design-rule
             "selected"
             `(("background_color" . ,(tok "color.secondary"))
               ("content_color" . ,(tok "color.on-secondary")))
             :motion "selection")
            (jetpacs-design-rule
             "toggled"
             `(("background_color" . ,(tok "color.secondary"))
               ("content_color" . ,(tok "color.on-secondary")))
             :motion "selection")
            (jetpacs-design-rule
             "disabled" `(("alpha" . ,(num 0.38))) :motion "quick"))))
      ("base.action"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.primary"))
             ("content_color" . ,(tok "color.on-primary"))
             ("corner_radius" . ,(tok "radius.pill")))))
      ("base.tonal"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.secondary"))
             ("content_color" . ,(tok "color.on-secondary"))
             ("corner_radius" . ,(tok "radius.pill")))))
      ("base.outlined"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.primary"))
             ("border_color" . ,(tok "color.outline"))
             ("border_width" . ,(dim 1))
             ("corner_radius" . ,(tok "radius.pill")))))
      ("base.indicator"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.primary"))
             ("content_color" . ,(tok "color.on-primary"))
             ("corner_radius" . ,(dim 3)))))
      ("base.chip"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.on-surface"))
             ("border_color" . ,(tok "color.outline"))
             ("border_width" . ,(dim 1))
             ("corner_radius" . ,(tok "radius.control"))
             ("padding_horizontal" . ,(dim 12))
             ("padding_vertical" . ,(dim 6)))
           :rules
           (list
            (jetpacs-design-rule
             "selected"
             `(("background_color" . ,(tok "color.secondary"))
               ("content_color" . ,(tok "color.on-secondary"))
               ("border_width" . ,(dim 0)))
             :motion "selection"))))
      ("base.field"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.surface"))
             ("content_color" . ,(tok "color.on-surface"))
             ("border_color" . ,(tok "color.outline"))
             ("border_width" . ,(dim 1))
             ("corner_radius" . ,(tok "radius.control"))
             ("padding_horizontal" . ,(tok "space.control-x"))
             ("padding_vertical" . ,(dim 12)))))
      ("base.line"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.outline")))))
      ("base.accent"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.primary")))))
      ("base.muted"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.outline")))))
      ("base.error"
       . ,(jetpacs-design-style
           `(("content_color" . ,(tok "color.error")))))
      ("base.section"
       . ,(jetpacs-design-style
           `(("padding_top" . ,(dim 16))
             ("padding_bottom" . ,(dim 8)))))
      ("base.indent"
       . ,(jetpacs-design-style
           `(("padding_start" . ,(dim 24)))))
      ("base.switch-track"
       . ,(jetpacs-design-style
           `(("width" . ,(dim 52))
             ("height" . ,(dim 32))
             ("corner_radius" . ,(dim 16))
             ("border_color" . ,(tok "color.outline"))
             ("border_width" . ,(dim 2)))
           :rules
           (list
            (jetpacs-design-rule
             "selected"
             `(("background_color" . ,(tok "color.primary"))
               ("border_width" . ,(dim 0)))
             :motion "selection")
            (jetpacs-design-rule
             "disabled" `(("alpha" . ,(num 0.38))) :motion "quick"))))
      ("base.switch-thumb"
       . ,(jetpacs-design-style
           `(("width" . ,(dim 24))
             ("height" . ,(dim 24))
             ("corner_radius" . ,(dim 12))
             ("background_color" . ,(tok "color.outline"))
             ("content_color" . ,(tok "color.primary")))
           :rules
           (list
            (jetpacs-design-rule
             "selected"
             `(("background_color" . ,(tok "color.on-primary")))
             :motion "selection"))))
      ("base.day"
       . ,(jetpacs-design-style
           `(("min_height" . ,(dim 40))
             ("corner_radius" . ,(dim 20))
             ("content_color" . ,(tok "color.on-surface")))
           :rules
           (list
            (jetpacs-design-rule
             "selected"
             `(("background_color" . ,(tok "color.primary"))
               ("content_color" . ,(tok "color.on-primary")))
             :motion "selection")
            (jetpacs-design-rule
             "disabled" `(("alpha" . ,(num 0.38))) :motion "quick"))))
      ("base.today"
       . ,(jetpacs-design-style
           `(("border_width" . ,(dim 1))
             ("border_color" . ,(tok "color.primary")))))
      ;; The band is its own strip, so its alpha softens only the band.
      ("base.band"
       . ,(jetpacs-design-style
           `(("background_color" . ,(tok "color.secondary"))
             ("alpha" . ,(num 0.35))))))))

(defconst jetpacs-design-baseline-motions
  `(("press" . ,(jetpacs-design-motion 90 "ease-out"))
    ("quick" . ,(jetpacs-design-motion 120 "ease-out"))
    ("selection" . ,(jetpacs-design-motion 160 "ease-in-out")))
  "Bounded state motions.")

(defconst jetpacs-design-baseline-component-styles
  '(("text.body" . ("typography.body"))
    ("text.title" . ("typography.title"))
    ("text.headline" . ("typography.headline"))
    ("text.caption" . ("typography.caption" "base.muted"))
    ("text.label" . ("typography.label"))
    ("text.mono" . ("typography.code"))
    ("icon-button.container" . ("base.interactive"))
    ("badge.container" . ("base.tonal"))
    ("badge.label" . ("typography.overline"))
    ("empty-state.container" . ("base.screen"))
    ("empty-state.title" . ("typography.item-title"))
    ("empty-state.caption" . ("typography.supporting" "base.muted"))
    ("card.filled" . ("base.surface"))
    ("card.elevated" . ("base.surface"))
    ("card.outlined" . ("base.surface"))
    ("list-item.container" . ("base.row"))
    ("list-item.overline" . ("typography.overline" "base.muted"))
    ("list-item.title" . ("typography.body"))
    ("list-item.subtitle" . ("typography.supporting" "base.muted"))
    ("swipe.cell" . ("base.tonal"))
    ("swipe.label" . ("typography.caption"))
    ("button.filled" . ("base.interactive" "base.action"))
    ("button.tonal" . ("base.interactive" "base.tonal"))
    ("button.elevated" . ("base.interactive" "base.tonal"))
    ("button.outlined" . ("base.interactive" "base.outlined"))
    ("button.text" . ("base.interactive" "base.accent"))
    ("button.label" . ("typography.label"))
    ("chip.container" . ("base.chip"))
    ("chip.label" . ("typography.label"))
    ("divider.line" . ("base.line"))
    ("section-header.container" . ("base.section"))
    ("section-header.title" . ("typography.label" "base.accent"))
    ("menu.trigger" . ("base.interactive"))
    ("menu.container" . ("base.popup"))
    ("menu.item" . ("base.interactive"))
    ("menu.item-label" . ("typography.body"))
    ("menu.item-supporting" . ("typography.supporting" "base.muted"))
    ("menu.group-label" . ("typography.overline" "base.muted"))
    ("switch.track" . ("base.switch-track"))
    ("switch.thumb" . ("base.switch-thumb"))
    ("switch.label" . ("typography.body"))
    ("collapsible.header" . ("base.row"))
    ("collapsible.chevron" . ("base.muted"))
    ("collapsible.body" . ("base.indent"))
    ("month-grid.container" . ("base.surface"))
    ("month-grid.header" . ("typography.item-title"))
    ("month-grid.nav" . ("base.interactive"))
    ("month-grid.weekday" . ("typography.caption" "base.muted"))
    ("month-grid.day" . ("typography.supporting" "base.day"))
    ("month-grid.today" . ("base.today"))
    ("month-grid.range" . ("base.band"))
    ("month-grid.mark" . ("base.accent"))
    ;; The chrome Material draws around presented content.
    ("chrome.tab-label" . ("typography.label"))
    ("chrome.tab-indicator" . ("base.indicator"))
    ("chrome.rail-label" . ("typography.label"))
    ("chrome.rail-indicator" . ("base.toolkit"))
    ("chrome.drawer" . ("base.surface"))
    ("chrome.snackbar" . ("base.toast"))
    ("action.container" . ("base.interactive" "base.action"))
    ("action.label" . ("typography.label"))
    ("choice.container" . ("base.row"))
    ("choice.indicator" . ("base.indicator"))
    ("choice.label" . ("typography.body"))
    ("panel.container" . ("base.surface"))
    ("panel.label" . ("typography.item-title"))
    ("tabs.container" . ("base.screen"))
    ("tabs.item" . ("base.interactive"))
    ("tabs.indicator" . ("base.indicator"))
    ("tabs.label" . ("typography.label"))
    ("section-navigator.container" . ("base.surface"))
    ("section-navigator.option" . ("base.interactive"))
    ("section-navigator.label" . ("typography.body"))
    ("section-navigator.button" . ("base.interactive"))
    ("section-navigator.selector" . ("base.interactive"))
    ("section-navigator.popup" . ("base.popup"))
    ("section-navigator.popup-item" . ("base.interactive"))
    ("text-field.outlined" . ("base.field"))
    ("text-field.filled" . ("base.field"))
    ("text-field.text" . ("typography.body"))
    ("text-field.label" . ("typography.label"))
    ("text-field.placeholder" . ("typography.body" "base.muted"))
    ("text-field.supporting" . ("typography.caption"))
    ("text-field.affix" . ("typography.label"))
    ("editor.surface" . ("base.surface"))
    ("editor.chromeless" . ("base.screen"))
    ("editor.text" . ("typography.code"))
    ("editor.gutter" . ("typography.metadata" "base.muted"))
    ("editor.toolbar" . ("base.surface"))
    ("editor.toolbar-item" . ("base.interactive" "typography.label"))
    ("editor.sync-status" . ("typography.metadata"))
    ("editor.completion-list" . ("base.popup"))
    ("editor.completion-item" . ("base.interactive"))
    ("editor.candidate-document" . ("typography.body"))
    ("editor.tooling-status" . ("typography.metadata")))
  "Every closed component slot, bound.")

(defun jetpacs-design-baseline-profile ()
  "Return the immutable platform baseline profile."
  (jetpacs-design-make-profile
   jetpacs-design-baseline-id "Jetpacs"
   :tokens (jetpacs-design-baseline--tokens)
   :typography jetpacs-design-baseline-typography
   :styles (jetpacs-design-baseline--styles)
   :motions jetpacs-design-baseline-motions
   :component-styles jetpacs-design-baseline-component-styles))

(defun jetpacs-design-present (screen &optional fallback-id)
  "Return SCREEN presented through the active design profile.

SCREEN comes back unchanged when it is not a node, when the receiver does
not advertise the design runtime, or when it is already presented -- a
screen whose root is not a scaffold has been wrapped by its own app and
keeps that app's profile.  Otherwise the active profile, else FALLBACK-ID,
else the platform baseline, is compiled around it."
  (cond
   ((not (jetpacs-root-node-p screen)) screen)
   ((not (equal (plist-get screen :t) "scaffold")) screen)
   ((not (jetpacs-extension-advertised-p "jetpacs.design" :app)) screen)
   (t (car (jetpacs-design-wrap-active-profile
            (list screen) (or fallback-id jetpacs-design-baseline-id))))))

(defun jetpacs-design-baseline-register ()
  "Register the baseline preset and install the chrome presentation seam.
Idempotent: the preset is immutable and registering it again is a no-op."
  (jetpacs-design-register-profile (jetpacs-design-baseline-profile))
  (with-eval-after-load 'jetpacs-chrome
    (when (and (boundp 'jetpacs-chrome-present-function)
               (null jetpacs-chrome-present-function))
      (setq jetpacs-chrome-present-function #'jetpacs-design-present)))
  jetpacs-design-baseline-id)

(defun jetpacs-design-baseline-unregister ()
  "Remove the baseline preset and release the chrome seam if it holds it."
  (jetpacs-design-unregister-profile jetpacs-design-baseline-id)
  (when (and (boundp 'jetpacs-chrome-present-function)
             (eq jetpacs-chrome-present-function #'jetpacs-design-present))
    (setq jetpacs-chrome-present-function nil)))

(jetpacs-design-baseline-register)

(provide 'jetpacs-design-baseline)
;;; jetpacs-design-baseline.el ends here
