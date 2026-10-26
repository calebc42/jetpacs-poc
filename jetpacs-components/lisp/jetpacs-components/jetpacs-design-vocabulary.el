;;; jetpacs-design-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/jetpacs-design.json (format
;; 2) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is Jetpacs authority, not an EBP registry.

;;; Code:

(defconst jetpacs-design-extension "jetpacs.design"
  "Renderer extension implemented by Jetpacs' Design design layer.")

(defconst jetpacs-design-node-schema
  '(
    ("jetpacs.design_scope" ("children" "styles" "tokens") ("component_styles" "motions" "theme_roles"))
    ("jetpacs.styled" ("children" "styles") ())
    ("jetpacs.pressable" ("children" "on_tap" "styles") ("enabled" "on_long_tap" "selected" "swipe_end" "swipe_start" "toggled"))
    ("jetpacs.glyph" ("paths" "size" "viewport_height" "viewport_width") ("color" "content_description"))
    ("jetpacs.inline_text" ("children") ("style"))
    ("jetpacs.inline" ("child" "height") ("reserve_extra" "reserve_max" "reserve_text" "reserve_text_size"))
    ("jetpacs.wheel" ("id" "value" "values") ("color" "divider_color" "enabled" "on_change" "wrap"))
    ("jetpacs.state_switch" ("children" "source" "values") ("otherwise")))
  "Jetpacs Design node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst jetpacs-design-target-node-types
  '(
    (app . ("jetpacs.design_scope" "jetpacs.styled" "jetpacs.pressable" "jetpacs.glyph" "jetpacs.inline_text" "jetpacs.inline" "jetpacs.wheel" "jetpacs.state_switch"))
    (dialog . ("jetpacs.design_scope" "jetpacs.styled" "jetpacs.pressable" "jetpacs.glyph" "jetpacs.inline_text" "jetpacs.inline" "jetpacs.wheel" "jetpacs.state_switch"))
    (notification . ()))
  "Jetpacs Design node types supported by each renderer target.")

(defconst jetpacs-design-typed-node-schema
  '(
    ("jetpacs.design_scope" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("styles" . (:type identifier-map :values (:type object :required (("properties" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 64)) ("rules" . (:type array :items (:type object :required (("properties" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 64)) ("state" . (:type enum :values ("disabled" "focused" "hovered" "pressed" "selected" "toggled")))) :optional (("motion" . (:type string :min-length 1 :max-length 128)))) :min-items 0 :max-items 16))) :optional (("motion" . (:type string :min-length 1 :max-length 128)))) :min-entries 0 :max-entries 256)) ("tokens" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 256))) :optional (("component_styles" . (:type array :items (:type object :required (("slot" . (:type enum :values ("action.container" "action.label" "badge.container" "badge.label" "button.elevated" "button.filled" "button.label" "button.outlined" "button.text" "button.tonal" "card.elevated" "card.filled" "card.outlined" "chip.container" "chip.label" "choice.container" "choice.indicator" "choice.label" "chrome.bottom-bar" "chrome.dialog" "chrome.drawer" "chrome.rail-indicator" "chrome.rail-label" "chrome.scrim" "chrome.snackbar" "chrome.tab-indicator" "chrome.tab-label" "collapsible.body" "collapsible.chevron" "collapsible.header" "divider.line" "dropdown.field" "dropdown.item" "dropdown.label" "dropdown.popup" "dropdown.text" "editor.candidate-document" "editor.chromeless" "editor.completion-item" "editor.completion-list" "editor.gutter" "editor.placeholder" "editor.surface" "editor.sync-status" "editor.syntax.comment" "editor.syntax.constant" "editor.syntax.done" "editor.syntax.function" "editor.syntax.heading" "editor.syntax.heading-1" "editor.syntax.heading-2" "editor.syntax.heading-3" "editor.syntax.heading-4" "editor.syntax.heading-5" "editor.syntax.heading-6" "editor.syntax.heading-7" "editor.syntax.heading-8" "editor.syntax.keyword" "editor.syntax.link" "editor.syntax.number" "editor.syntax.operator" "editor.syntax.preprocessor" "editor.syntax.string" "editor.syntax.tag" "editor.syntax.todo" "editor.syntax.type" "editor.syntax.variable" "editor.text" "editor.toolbar" "editor.toolbar-item" "editor.tooling-status" "empty-state.caption" "empty-state.container" "empty-state.title" "icon-button.container" "list-item.container" "list-item.overline" "list-item.subtitle" "list-item.title" "menu.container" "menu.group-label" "menu.item" "menu.item-label" "menu.item-supporting" "menu.trigger" "month-grid.container" "month-grid.day" "month-grid.header" "month-grid.mark" "month-grid.nav" "month-grid.range" "month-grid.today" "month-grid.weekday" "panel.container" "panel.label" "picker.container" "picker.label" "section-header.container" "section-header.title" "section-navigator.button" "section-navigator.container" "section-navigator.label" "section-navigator.option" "section-navigator.popup" "section-navigator.popup-item" "section-navigator.selector" "swipe.cell" "swipe.label" "swipe.popup" "swipe.popup-button" "switch.label" "switch.thumb" "switch.track" "tabs.container" "tabs.indicator" "tabs.item" "tabs.label" "text-field.affix" "text-field.filled" "text-field.label" "text-field.outlined" "text-field.placeholder" "text-field.supporting" "text-field.text" "text.body" "text.caption" "text.headline" "text.label" "text.mono" "text.title"))) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional nil) :min-items 0 :max-items 128)) ("motions" . (:type identifier-map :values (:type object :required (("duration_ms" . (:type integer :minimum 0 :maximum 10000)) ("easing" . (:type enum :values ("ease-in" "ease-in-out" "ease-out" "linear" "spring")))) :optional nil) :min-entries 0 :max-entries 64)) ("theme_roles" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 13)))))
    ("jetpacs.styled" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional nil))
    ("jetpacs.pressable" . (:required (("children" . (:type node-array :min-items 1 :max-items 16)) ("on_tap" . (:type action)) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional (("enabled" . (:type boolean)) ("on_long_tap" . (:type action)) ("selected" . (:type boolean)) ("swipe_end" . (:type swipe-side)) ("swipe_start" . (:type swipe-side)) ("toggled" . (:type boolean)))))
    ("jetpacs.glyph" . (:required (("paths" . (:type array :items (:type string :min-length 1 :max-length 16384) :min-items 1 :max-items 8)) ("size" . (:type number :minimum 0 :maximum 1024)) ("viewport_height" . (:type number :minimum 1 :maximum 100000)) ("viewport_width" . (:type number :minimum 1 :maximum 100000))) :optional (("color" . (:type string :min-length 1 :max-length 64)) ("content_description" . (:type string :min-length 0 :max-length 512)))))
    ("jetpacs.inline_text" . (:required (("children" . (:type node-array :min-items 1 :max-items 512))) :optional (("style" . (:type string :min-length 1 :max-length 32)))))
    ("jetpacs.inline" . (:required (("child" . (:type node)) ("height" . (:type number :minimum 1 :maximum 512))) :optional (("reserve_extra" . (:type number :minimum 0 :maximum 1024)) ("reserve_max" . (:type number :minimum 0 :maximum 4096)) ("reserve_text" . (:type string :min-length 0 :max-length 1024)) ("reserve_text_size" . (:type number :minimum 1 :maximum 200)))))
    ("jetpacs.wheel" . (:required (("id" . (:type string :min-length 1 :max-length 128)) ("value" . (:type string :min-length 1 :max-length 64)) ("values" . (:type array :items (:type string :min-length 1 :max-length 64) :min-items 1 :max-items 256))) :optional (("color" . (:type string :min-length 1 :max-length 64)) ("divider_color" . (:type string :min-length 1 :max-length 64)) ("enabled" . (:type boolean)) ("on_change" . (:type action)) ("wrap" . (:type boolean)))))
    ("jetpacs.state_switch" . (:required (("children" . (:type node-array :min-items 1 :max-items 256)) ("source" . (:type string :min-length 1 :max-length 128)) ("values" . (:type array :items (:type string :min-length 0 :max-length 64) :min-items 1 :max-items 256))) :optional (("otherwise" . (:type node))))))
  "Jetpacs Design closed typed node schemas.")

(defconst jetpacs-design-schema-sha256 "7be5753828c82f097e64b8ba94b517ac8e65e8b262834e3eb2f2919ee272c021"
  "SHA-256 of the canonical resolved typed schema projection.")

(provide 'jetpacs-design-vocabulary)
;;; jetpacs-design-vocabulary.el ends here
