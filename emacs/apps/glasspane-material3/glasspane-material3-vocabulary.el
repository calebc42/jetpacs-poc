;;; glasspane-material3-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/glasspane-material3.json (format
;; 1) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is Glasspane authority, not an EBP registry.

;;; Code:

(defconst glasspane-material3-extension "glasspane.material3"
  "Renderer extension implemented by Glasspane's Material design layer.")

(defconst glasspane-material3-node-schema
  '(
    ("material3.assist_chip" ("label") ("enabled" "icon" "on_tap" "variant"))
    ("material3.split_button" ("on_tap") ("checked" "enabled" "icon" "items" "label" "on_change" "on_trailing_tap" "size" "trailing_description" "trailing_icon" "trailing_label" "variant"))
    ("material3.app_bar_row" ("items") ("max_items" "overflow_icon"))
    ("material3.app_bar_column" ("items") ("max_items" "overflow_icon"))
    ("material3.fab_menu" ("items") ("close_icon" "icon")))
  "Glasspane Material node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst glasspane-material3-target-node-types
  '(
    (app . ("material3.assist_chip" "material3.split_button" "material3.app_bar_row" "material3.app_bar_column" "material3.fab_menu"))
    (dialog . ("material3.assist_chip" "material3.split_button"))
    (notification . ()))
  "Glasspane Material node types supported by each Jetpacs target.")

(provide 'glasspane-material3-vocabulary)
;;; glasspane-material3-vocabulary.el ends here
