;;; jetpacs-component-catalog-test.el --- Jetpacs component slice -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-component-catalog)

(ert-deftest jetpacs-components-builders-preserve-required-false ()
  "Choice emits JSON false rather than dropping its required state."
  (let ((node (jetpacs-component-choice
               "setting" "Mirror" :json-false
               (jetpacs-action "test.choice"))))
    (should (equal (plist-get node :t) "jetpacs.choice"))
    (should (eq (plist-get node :checked) :json-false))
    (should (string-match-p
             "\\\"checked\\\":false"
             (jetpacs-node->canonical-json node)))))

(ert-deftest jetpacs-components-builders-reject-invalid-contracts ()
  "Public constructors fail before malformed extension IR reaches a sender."
  (should-error
   (jetpacs-component-action "" (jetpacs-action "test.run")))
  (should-error
   (jetpacs-component-action
    "Run" '(:action "test.run" :builtin "view.switch")))
  (should-error
   (jetpacs-component-choice
    "choice" "Choice" nil (jetpacs-action "test.choice")))
  (should-error (jetpacs-component-panel "Panel" (list '(:not-a-node t)))))

(ert-deftest jetpacs-components-panel-keeps-real-child-vector ()
  "Panel returns the actual plist/vector IR without a parallel catalog AST."
  (let* ((child (jetpacs-text "Ready"))
         (panel (jetpacs-component-panel "STATUS" (list child))))
    (should (equal (plist-get panel :t) "jetpacs.panel"))
    (should (vectorp (plist-get panel :children)))
    (should (equal (aref (plist-get panel :children) 0) child))))

(ert-deftest jetpacs-components-generated-extension-registers-all-targets ()
  "Generated schema and target maps are the authoring authority."
  (should (equal jetpacs-components-extension "jetpacs.components"))
  (should (equal (cdr (assoc jetpacs-components-extension
                             jetpacs-renderer-extensions))
                 '("jetpacs.action" "jetpacs.choice" "jetpacs.panel")))
  (should (equal (jetpacs-renderer-target-node-types 'dialog) nil))
  (dolist (type '("jetpacs.action" "jetpacs.choice" "jetpacs.panel"))
    (should (member type (jetpacs-renderer-target-node-types 'app)))))

(ert-deftest jetpacs-components-require-node-and-extension-advertisement ()
  "A namespaced node alone cannot imply its renderer extension."
  (let* ((receipt (make-temp-file "jetpacs-components-receipts"))
         (client (ebp-client-create :receipt-file receipt))
         (node (jetpacs-component-action
                "Run" (jetpacs-action "test.run"))))
    (unwind-protect
        (cl-letf (((symbol-function 'jetpacs-client) (lambda () client)))
          (setf (ebp-client-state client) 'ready
                (ebp-client-profiles client)
                '(:app (:node_types ["text" "jetpacs.action"]
                        :builtins [] :features [] :extensions [])))
          (should-not (jetpacs-node-advertised-p "jetpacs.action" :app))
          (should-not (jetpacs-apps-available-p
                       jetpacs-component-catalog-owner))
          (setf (ebp-client-profiles client)
                '(:app (:node_types ["text" "jetpacs.action"]
                        :builtins [] :features []
                        :extensions ["jetpacs.components"])))
          (should (jetpacs-node-advertised-p "jetpacs.action" :app))
          (should (jetpacs-apps-available-p
                   jetpacs-component-catalog-owner))
          ;; The static reference profile still recognizes the generated node;
          ;; the live predicate above supplies the independent owner gate.
          (should (jetpacs-check-profile node 'app)))
      (delete-file receipt))))

(ert-deftest jetpacs-component-catalog-is-a-separate-gated-app ()
  "Jetpacs Components coexists with, rather than relabels, Material 3."
  (let ((entry (assoc jetpacs-component-catalog-owner
                      jetpacs-apps--registry)))
    (should entry)
    (should (equal (plist-get (cdr entry) :label) "Jetpacs Components"))
    (should (equal (plist-get (cdr entry) :requires-extensions)
                   '("jetpacs.components")))
    (should (equal (plist-get (cdr entry) :surfaces) '("jpcatalog")))))

(ert-deftest jetpacs-component-catalog-builds-home-and-every-detail ()
  "Every catalog screen is typed, serializable IR with all three node kinds."
  (let* ((screens
          (list (jetpacs-component-catalog--home-screen nil)
                (jetpacs-component-catalog--action-screen nil)
                (jetpacs-component-catalog--choice-screen nil)
                (jetpacs-component-catalog--panel-screen nil)))
         (json (mapconcat #'jetpacs-node->canonical-json screens "\n")))
    (dolist (screen screens) (should (jetpacs-root-node-p screen)))
    (dolist (type '("jetpacs.action" "jetpacs.choice" "jetpacs.panel"))
      (should (string-match-p (regexp-quote type) json)))))

(ert-deftest jetpacs-component-catalog-action-dispatches-exactly-once ()
  "One ordinary Action event produces one application mutation."
  (let ((jetpacs-component-catalog--action-count 0)
        refresh)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (params) (setq refresh params))))
      (should (eq (jetpacs-component-catalog--on-activate
                   nil '(:surface "app:jpcatalog"))
                  'accepted))
      (should (= jetpacs-component-catalog--action-count 1))
      (should (equal refresh '(:surface "app:jpcatalog"))))))

(ert-deftest jetpacs-component-catalog-choice-normalizes-json-false ()
  "The injected boolean becomes Emacs-owned state before refresh."
  (let ((jetpacs-component-catalog--choice t)
        refreshed)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (_params) (setq refreshed t))))
      (should (eq (jetpacs-component-catalog--on-choice
                   '(:value :json-false) '(:surface "app:jpcatalog"))
                  'accepted))
      (should-not jetpacs-component-catalog--choice)
      (should refreshed)
      (should (eq (jetpacs-component-catalog--on-choice
                   '(:value "false") nil)
                  'rejected)))))

(ert-deftest jetpacs-component-catalog-actions-have-public-metadata ()
  "Every wire-visible catalog verb documents the behavior it owns."
  (dolist (verb (mapcar #'car jetpacs-component-catalog--verbs))
    (let ((schema (jetpacs-action-schema verb)))
      (should schema)
      (should (stringp (plist-get schema :doc)))
      (should-not (string-empty-p (plist-get schema :doc))))))

(provide 'jetpacs-component-catalog-test)
;;; jetpacs-component-catalog-test.el ends here
