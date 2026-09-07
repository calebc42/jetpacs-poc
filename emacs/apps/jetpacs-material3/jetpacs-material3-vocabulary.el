;;; jetpacs-material3-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/jetpacs-material3.json (format
;; 1) by tools/gen-renderer-extension-vocabulary.py.
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

(provide 'jetpacs-material3-vocabulary)
;;; jetpacs-material3-vocabulary.el ends here
