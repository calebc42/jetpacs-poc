;;; jetpacs-components-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/jetpacs-components.json (format
;; 2) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is Jetpacs authority, not an EBP registry.

;;; Code:

(defconst jetpacs-components-extension "jetpacs.components"
  "Renderer extension implemented by Jetpacs' Components design layer.")

(defconst jetpacs-components-node-schema
  '(
    ("jetpacs.action" ("label" "on_tap") ("enabled"))
    ("jetpacs.choice" ("checked" "id" "label" "on_change") ("enabled"))
    ("jetpacs.list_item" ("title") ("enabled" "leading" "on_long_tap" "on_tap" "overline" "selected" "subtitle" "subtitle_max_lines" "swipe_end" "swipe_start" "title_max_lines" "trailing"))
    ("jetpacs.panel" ("children" "label") ())
    ("jetpacs.scope" ("children") ())
    ("jetpacs.section_navigator" ("id" "on_change" "options" "value") ("enabled" "pinned"))
    ("jetpacs.tabs" ("id" "on_change" "options" "value") ("enabled" "pinned" "scrollable" "variant")))
  "Jetpacs Components node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst jetpacs-components-target-node-types
  '(
    (app . ("jetpacs.action" "jetpacs.choice" "jetpacs.list_item" "jetpacs.panel" "jetpacs.scope" "jetpacs.section_navigator" "jetpacs.tabs"))
    (dialog . ())
    (notification . ()))
  "Jetpacs Components node types supported by each renderer target.")

(defconst jetpacs-components-typed-node-schema
  '(
    ("jetpacs.action" . (:required (("label" . (:type string :min-length 0 :max-length 4096)) ("on_tap" . (:type action))) :optional (("enabled" . (:type boolean)))))
    ("jetpacs.choice" . (:required (("checked" . (:type boolean)) ("id" . (:type string :min-length 1 :max-length 128)) ("label" . (:type string :min-length 0 :max-length 4096)) ("on_change" . (:type action))) :optional (("enabled" . (:type boolean)))))
    ("jetpacs.list_item" . (:required (("title" . (:type string :min-length 0 :max-length 4096))) :optional (("enabled" . (:type boolean)) ("leading" . (:type node)) ("on_long_tap" . (:type action)) ("on_tap" . (:type action)) ("overline" . (:type string :min-length 0 :max-length 4096)) ("selected" . (:type boolean)) ("subtitle" . (:type string :min-length 0 :max-length 4096)) ("subtitle_max_lines" . (:type integer :minimum 1 :maximum 10000)) ("swipe_end" . (:type swipe-side)) ("swipe_start" . (:type swipe-side)) ("title_max_lines" . (:type integer :minimum 1 :maximum 10000)) ("trailing" . (:type node)))))
    ("jetpacs.panel" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("label" . (:type string :min-length 0 :max-length 4096))) :optional nil))
    ("jetpacs.scope" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000))) :optional nil))
    ("jetpacs.section_navigator" . (:required (("id" . (:type string :min-length 1 :max-length 128)) ("on_change" . (:type action)) ("options" . (:type array :items (:type object :required (("label" . (:type string :min-length 0 :max-length 4096)) ("level" . (:type integer :minimum 1 :maximum 6)) ("value" . (:type string :min-length 1 :max-length 4096))) :optional nil) :min-items 1 :max-items 10000)) ("value" . (:type string :min-length 1 :max-length 4096))) :optional (("enabled" . (:type boolean)) ("pinned" . (:type boolean)))))
    ("jetpacs.tabs" . (:required (("id" . (:type string :min-length 1 :max-length 128)) ("on_change" . (:type action)) ("options" . (:type array :items (:type object :required (("label" . (:type string :min-length 0 :max-length 4096)) ("value" . (:type string :min-length 1 :max-length 4096))) :optional nil) :min-items 1 :max-items 10000)) ("value" . (:type string :min-length 1 :max-length 4096))) :optional (("enabled" . (:type boolean)) ("pinned" . (:type boolean)) ("scrollable" . (:type boolean)) ("variant" . (:type string :min-length 1 :max-length 64))))))
  "Jetpacs Components closed typed node schemas.")

(defconst jetpacs-components-schema-sha256 "efc0fd59a656b4f10279ac752a07edaef4de9549986e100d20f74706602d85d0"
  "SHA-256 of the canonical resolved typed schema projection.")

(provide 'jetpacs-components-vocabulary)
;;; jetpacs-components-vocabulary.el ends here
