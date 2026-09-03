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
    ("jetpacs.pressable" ("children" "on_tap" "styles") ("enabled" "selected" "toggled")))
  "Jetpacs Design node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst jetpacs-design-target-node-types
  '(
    (app . ("jetpacs.design_scope" "jetpacs.styled" "jetpacs.pressable"))
    (dialog . ())
    (notification . ()))
  "Jetpacs Design node types supported by each renderer target.")

(defconst jetpacs-design-typed-node-schema
  '(
    ("jetpacs.design_scope" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("styles" . (:type identifier-map :values (:type object :required (("properties" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 64)) ("rules" . (:type array :items (:type object :required (("properties" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 64)) ("state" . (:type enum :values ("disabled" "focused" "hovered" "pressed" "selected" "toggled")))) :optional (("motion" . (:type string :min-length 1 :max-length 128)))) :min-items 0 :max-items 16))) :optional (("motion" . (:type string :min-length 1 :max-length 128)))) :min-entries 0 :max-entries 256)) ("tokens" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 256))) :optional (("component_styles" . (:type array :items (:type object :required (("slot" . (:type enum :values ("action.container" "action.label" "badge.container" "badge.label" "button.elevated" "button.filled" "button.label" "button.outlined" "button.text" "button.tonal" "card.elevated" "card.filled" "card.outlined" "chip.container" "chip.label" "choice.container" "choice.indicator" "choice.label" "divider.line" "editor.candidate-document" "editor.chromeless" "editor.completion-item" "editor.completion-list" "editor.gutter" "editor.surface" "editor.sync-status" "editor.text" "editor.toolbar" "editor.toolbar-item" "editor.tooling-status" "empty-state.caption" "empty-state.container" "empty-state.title" "icon-button.container" "list-item.container" "list-item.overline" "list-item.subtitle" "list-item.title" "menu.container" "menu.group-label" "menu.item" "menu.item-label" "menu.item-supporting" "menu.trigger" "panel.container" "panel.label" "section-header.container" "section-header.title" "section-navigator.button" "section-navigator.container" "section-navigator.label" "section-navigator.option" "section-navigator.popup" "section-navigator.popup-item" "section-navigator.selector" "swipe.cell" "swipe.label" "switch.label" "switch.thumb" "switch.track" "tabs.container" "tabs.indicator" "tabs.item" "tabs.label" "text-field.affix" "text-field.filled" "text-field.label" "text-field.outlined" "text-field.placeholder" "text-field.supporting" "text-field.text" "text.body" "text.caption" "text.headline" "text.label" "text.mono" "text.title"))) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional nil) :min-items 0 :max-items 77)) ("motions" . (:type identifier-map :values (:type object :required (("duration_ms" . (:type integer :minimum 0 :maximum 10000)) ("easing" . (:type enum :values ("ease-in" "ease-in-out" "ease-out" "linear" "spring")))) :optional nil) :min-entries 0 :max-entries 64)) ("theme_roles" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "theme-role" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 13)))))
    ("jetpacs.styled" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional nil))
    ("jetpacs.pressable" . (:required (("children" . (:type node-array :min-items 1 :max-items 16)) ("on_tap" . (:type action)) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional (("enabled" . (:type boolean)) ("selected" . (:type boolean)) ("toggled" . (:type boolean))))))
  "Jetpacs Design closed typed node schemas.")

(defconst jetpacs-design-schema-sha256 "c24afdf2520710cae1c8d327522e7c1d39e8bff224233867ba7e21558ddc7a3a"
  "SHA-256 of the canonical resolved typed schema projection.")

(provide 'jetpacs-design-vocabulary)
;;; jetpacs-design-vocabulary.el ends here
