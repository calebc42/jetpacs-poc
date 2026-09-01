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
    ("jetpacs.design_scope" ("children" "styles" "tokens") ("motions"))
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
    ("jetpacs.design_scope" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("styles" . (:type identifier-map :values (:type object :required (("properties" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 64)) ("rules" . (:type array :items (:type object :required (("properties" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 64)) ("state" . (:type enum :values ("disabled" "focused" "hovered" "pressed" "selected" "toggled")))) :optional (("motion" . (:type string :min-length 1 :max-length 128)))) :min-items 0 :max-items 16))) :optional (("motion" . (:type string :min-length 1 :max-length 128)))) :min-entries 0 :max-entries 256)) ("tokens" . (:type identifier-map :values (:type object :required (("kind" . (:type enum :values ("boolean" "color" "dimension" "font-family" "font-weight" "number" "text-align" "token"))) ("value" . (:type string :min-length 1 :max-length 256))) :optional nil) :min-entries 0 :max-entries 256))) :optional (("motions" . (:type identifier-map :values (:type object :required (("duration_ms" . (:type integer :minimum 0 :maximum 10000)) ("easing" . (:type enum :values ("ease-in" "ease-in-out" "ease-out" "linear" "spring")))) :optional nil) :min-entries 0 :max-entries 64)))))
    ("jetpacs.styled" . (:required (("children" . (:type node-array :min-items 0 :max-items 10000)) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional nil))
    ("jetpacs.pressable" . (:required (("children" . (:type node-array :min-items 1 :max-items 16)) ("on_tap" . (:type action)) ("styles" . (:type array :items (:type string :min-length 1 :max-length 128) :min-items 1 :max-items 16))) :optional (("enabled" . (:type boolean)) ("selected" . (:type boolean)) ("toggled" . (:type boolean))))))
  "Jetpacs Design closed typed node schemas.")

(defconst jetpacs-design-schema-sha256 "dcfc1bc52138a4e9bf40e5eca4580cc23a92d8bcc3e809645683080c38d8be52"
  "SHA-256 of the canonical resolved typed schema projection.")

(provide 'jetpacs-design-vocabulary)
;;; jetpacs-design-vocabulary.el ends here
