;;; jetpacs-vocabulary-authoring-test.el --- generated authoring metadata -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Pins the authoring metadata in `jetpacs-vocabulary.el' directly to
;; ebp/contract.json.  The generator's --check gate proves byte-level drift;
;; these assertions also document the Elisp shapes consumed by GUI editors.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-vocabulary)

(defconst jetpacs-vocabulary-authoring-test--dir
  (file-name-directory (or load-file-name buffer-file-name))
  "Directory holding this test file.")

(defun jetpacs-vocabulary-authoring-test--contract ()
  "Read the repository EBP contract as symbol-keyed alists and list arrays."
  (with-temp-buffer
    (insert-file-contents
     (expand-file-name
      "contract.json"
      (or (getenv "EBP_SPEC_DIR")
          (expand-file-name
           "ebp"
           (or (getenv "JETPACS_REPOSITORIES_ROOT")
               (expand-file-name "../.."
                                 jetpacs-vocabulary-authoring-test--dir))))))
    (json-parse-buffer :object-type 'alist :array-type 'list)))

(defun jetpacs-vocabulary-authoring-test--string-alist (alist)
  "Return ALIST with its symbol keys converted to wire-name strings."
  (mapcar (lambda (entry)
            (cons (symbol-name (car entry)) (cdr entry)))
          alist))

(ert-deftest jetpacs-vocabulary-authoring/field-types-match-contract ()
  "Every generated field type is the contract's value in contract order."
  (let ((contract (jetpacs-vocabulary-authoring-test--contract)))
    (should
     (equal jetpacs-field-types
            (jetpacs-vocabulary-authoring-test--string-alist
             (alist-get 'field_types contract))))))

(ert-deftest jetpacs-vocabulary-authoring/actions-match-contract ()
  "Hooks, descriptor schemas, injections, policies, and feature stay pinned."
  (let* ((contract (jetpacs-vocabulary-authoring-test--contract))
         (actions (alist-get 'actions contract))
         (schema (alist-get 'schema actions))
         (injections (alist-get 'injections actions)))
    (should (equal jetpacs-action-hook-keys
                   (alist-get 'hook_keys actions)))
    (should (equal jetpacs-action-descriptor-fields
                   (alist-get 'descriptor_fields actions)))
    (should (equal jetpacs-action-offline-policies
                   (alist-get 'offline_policies actions)))
    (should (equal jetpacs-action-offline-default
                   (alist-get 'offline_default actions)))
    (should
     (equal jetpacs-action-descriptor-schema
            (mapcar
             (lambda (entry)
               (list (symbol-name (car entry))
                     :required (alist-get 'required (cdr entry))
                     :optional (alist-get 'optional (cdr entry))))
             schema)))
    (should
     (equal jetpacs-action-injections
            (jetpacs-vocabulary-authoring-test--string-alist injections)))
    (should (equal jetpacs-action-open-surface-feature
                   (alist-get 'open_surface_feature actions)))))

(ert-deftest jetpacs-vocabulary-authoring/toolbar-matches-contract ()
  "The generated Editor toolbar plist retains the whole contract section."
  (let* ((contract (jetpacs-vocabulary-authoring-test--contract))
         (toolbar (alist-get 'toolbar contract))
         (expected
          (cl-loop for (key . value) in toolbar
                   append (list (intern (concat ":" (symbol-name key)))
                                value))))
    (should (equal jetpacs-toolbar-contract expected))))

(provide 'jetpacs-vocabulary-authoring-test)
;;; jetpacs-vocabulary-authoring-test.el ends here
