;;; jetpacs-components-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/jetpacs-components.json (format
;; 1) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is Jetpacs authority, not an EBP registry.

;;; Code:

(defconst jetpacs-components-extension "jetpacs.components"
  "Renderer extension implemented by Jetpacs' Components design layer.")

(defconst jetpacs-components-node-schema
  '(
    ("jetpacs.action" ("label" "on_tap") ("enabled"))
    ("jetpacs.choice" ("checked" "id" "label" "on_change") ("enabled"))
    ("jetpacs.panel" ("children" "label") ()))
  "Jetpacs Components node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst jetpacs-components-target-node-types
  '(
    (app . ("jetpacs.action" "jetpacs.choice" "jetpacs.panel"))
    (dialog . ())
    (notification . ()))
  "Jetpacs Components node types supported by each Jetpacs target.")

(provide 'jetpacs-components-vocabulary)
;;; jetpacs-components-vocabulary.el ends here
