;;; jetpacs-material3-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/jetpacs-material3.json (format
;; 2) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is Jetpacs authority, not an EBP registry.

;;; Code:

(defconst jetpacs-material3-extension "jetpacs.material3"
  "Renderer extension implemented by Jetpacs' Material design layer.")

(defconst jetpacs-material3-node-schema
  '(
    ("material3.assist_chip" ("label") ("enabled" "icon" "on_tap" "variant"))
    ("material3.split_button" ("on_tap") ("checked" "enabled" "icon" "items" "label" "on_change" "on_trailing_tap" "size" "trailing_description" "trailing_icon" "trailing_label" "variant"))
    ("material3.app_bar_row" ("items") ("max_items" "overflow_icon"))
    ("material3.app_bar_column" ("items") ("max_items" "overflow_icon"))
    ("material3.fab_menu" ("items") ("close_icon" "icon")))
  "Jetpacs Material node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst jetpacs-material3-target-node-types
  '(
    (app . ("material3.assist_chip" "material3.split_button" "material3.app_bar_row" "material3.app_bar_column" "material3.fab_menu"))
    (dialog . ("material3.assist_chip" "material3.split_button"))
    (notification . ()))
  "Jetpacs Material node types supported by each renderer target.")

(defconst jetpacs-material3-typed-node-schema
  '(
    ("material3.assist_chip" . (:required (("label" . (:type string :min-length 0 :max-length 4096))) :optional (("enabled" . (:type boolean)) ("icon" . (:type string :min-length 1 :max-length 128)) ("on_tap" . (:type action)) ("variant" . (:type enum :values ("elevated" "elevated_suggestion" "flat" "suggestion"))))))
    ("material3.split_button" . (:required (("on_tap" . (:type action))) :optional (("checked" . (:type boolean)) ("enabled" . (:type boolean)) ("icon" . (:type string :min-length 1 :max-length 128)) ("items" . (:type array :items (:type object :required (("label" . (:type string :min-length 0 :max-length 4096)) ("on_tap" . (:type action))) :optional (("enabled" . (:type boolean)) ("icon" . (:type string :min-length 1 :max-length 128)))) :min-items 0 :max-items 64)) ("label" . (:type string :min-length 0 :max-length 4096)) ("on_change" . (:type action)) ("on_trailing_tap" . (:type action)) ("size" . (:type enum :values ("large" "medium" "small" "xlarge" "xsmall"))) ("trailing_description" . (:type string :min-length 0 :max-length 4096)) ("trailing_icon" . (:type string :min-length 1 :max-length 128)) ("trailing_label" . (:type string :min-length 0 :max-length 4096)) ("variant" . (:type enum :values ("elevated" "filled" "outlined" "tonal"))))))
    ("material3.app_bar_row" . (:required (("items" . (:type array :items (:type object :required (("icon" . (:type string :min-length 1 :max-length 128)) ("label" . (:type string :min-length 0 :max-length 4096)) ("on_tap" . (:type action))) :optional (("enabled" . (:type boolean)))) :min-items 1 :max-items 64))) :optional (("max_items" . (:type integer :minimum 1 :maximum 64)) ("overflow_icon" . (:type string :min-length 1 :max-length 128)))))
    ("material3.app_bar_column" . (:required (("items" . (:type array :items (:type object :required (("icon" . (:type string :min-length 1 :max-length 128)) ("label" . (:type string :min-length 0 :max-length 4096)) ("on_tap" . (:type action))) :optional (("enabled" . (:type boolean)))) :min-items 1 :max-items 64))) :optional (("max_items" . (:type integer :minimum 1 :maximum 64)) ("overflow_icon" . (:type string :min-length 1 :max-length 128)))))
    ("material3.fab_menu" . (:required (("items" . (:type array :items (:type object :required (("icon" . (:type string :min-length 1 :max-length 128)) ("label" . (:type string :min-length 0 :max-length 4096)) ("on_tap" . (:type action))) :optional nil) :min-items 1 :max-items 64))) :optional (("close_icon" . (:type string :min-length 1 :max-length 128)) ("icon" . (:type string :min-length 1 :max-length 128))))))
  "Jetpacs Material closed typed node schemas.")

(defconst jetpacs-material3-schema-sha256 "99105d126640bae463fd4359753903f109ca9cfc68685f5ef7764e9068e27a87"
  "SHA-256 of the canonical resolved typed schema projection.")

(provide 'jetpacs-material3-vocabulary)
;;; jetpacs-material3-vocabulary.el ends here
