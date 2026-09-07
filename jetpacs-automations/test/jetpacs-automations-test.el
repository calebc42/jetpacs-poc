;;; jetpacs-automations-test.el --- GUI-over-Lisp app tests -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-automations)

(cl-defmacro jetpacs-automations-test-with-draft ((key recipe) &body body)
  "Bind fresh draft KEY and RECIPE, then run BODY."
  (declare (indent 1) (debug ((symbolp symbolp) body)))
  `(let* ((jetpacs-automation-recipes nil)
          (jetpacs-automation-enabled-recipes nil)
          (,recipe (jetpacs-automations--new-recipe))
          (,key (plist-get ,recipe :id)))
     (clrhash jetpacs-automations--drafts)
     (puthash ,key (jetpacs-automations--make-draft ,recipe)
              jetpacs-automations--drafts)
     ,@body))

(ert-deftest jetpacs-automations-registers-as-a-separate-app ()
  (let ((entry (assoc jetpacs-automations-owner jetpacs-apps--registry)))
    (should entry)
    (should (equal (plist-get (cdr entry) :label) "Automations"))
    (should (equal (plist-get (cdr entry) :icon) "account_tree"))
    (should (gethash "automations.lisp.apply" jetpacs-action-handlers))
    (should (gethash "trigger.fired" jetpacs-action-handlers))))

(ert-deftest jetpacs-automations-all-projections-share-one-draft ()
  (jetpacs-automations-test-with-draft (key recipe)
    (let* ((draft (jetpacs-automations--draft key))
           (digest (jetpacs-automation-recipe-digest recipe)))
      (dolist (mode '("inspector" "tree" "lisp"))
        (setq draft (plist-put draft :mode mode))
        (puthash key draft jetpacs-automations--drafts)
        (should (stringp
                 (jetpacs-node->canonical-json
                  (jetpacs-automations--detail-screen key nil))))
        (should (equal digest
                       (jetpacs-automation-recipe-digest
                        (jetpacs-automations--recipe key))))))))

(ert-deftest jetpacs-automations-structured-edit-regenerates-lisp ()
  (jetpacs-automations-test-with-draft (key _recipe)
    (cl-letf (((symbol-function 'jetpacs-automations--refresh)
               (lambda (_params) nil)))
      (should (eq 'accepted
                  (jetpacs-automations--on-field
                   (list :id key :field "name" :value "Morning lights") nil)))
      (should (eq 'accepted
                  (jetpacs-automations--on-input-add (list :id key) nil)))
      (should (eq 'accepted
                  (jetpacs-automations--on-step-add
                   (list :id key :value "device.notify") nil))))
    (let* ((draft (jetpacs-automations--draft key))
           (source (jetpacs-automation-print-recipe
                    (plist-get draft :recipe))))
      (should (string-match-p "Morning lights" source))
      (should (string-match-p "input-1" source))
      (should (string-match-p "device.notify" source))
      (should-not (plist-get draft :dry-run))
      (should-not (plist-get draft :lisp-buffer))
      ;; The newly populated Inspector and Tree both remain wire-serializable.
      (dolist (mode '("inspector" "tree"))
        (setq draft (plist-put draft :mode mode))
        (puthash key draft jetpacs-automations--drafts)
        (should (stringp
                 (jetpacs-node->canonical-json
                  (jetpacs-automations--detail-screen key nil))))))))

(ert-deftest jetpacs-automations-invalid-lisp-cannot-replace-valid-draft ()
  (jetpacs-automations-test-with-draft (key recipe)
    (let ((before (jetpacs-automation-recipe-digest recipe))
          (bad "#.(progn (error \"must not run\") nil)"))
      (cl-letf (((symbol-function 'jetpacs-automations--refresh)
                 (lambda (_params) nil)))
        (should (eq 'accepted
                    (jetpacs-automations--on-lisp-apply
                     (list :id key :value bad) nil))))
      (let ((draft (jetpacs-automations--draft key)))
        (should (equal before
                       (jetpacs-automation-recipe-digest
                        (plist-get draft :recipe))))
        (should (equal (plist-get draft :lisp-buffer) bad))
        (should (stringp (plist-get draft :error)))))))

(ert-deftest jetpacs-automations-valid-lisp-replaces-structured-draft ()
  (jetpacs-automations-test-with-draft (key recipe)
    (let* ((changed (plist-put (copy-tree recipe t) :name "From Lisp"))
           (source (jetpacs-automation-print-recipe changed)))
      (cl-letf (((symbol-function 'jetpacs-automations--refresh)
                 (lambda (_params) nil)))
        (should (eq 'accepted
                    (jetpacs-automations--on-lisp-apply
                     (list :id key :value source) nil))))
      (let ((draft (jetpacs-automations--draft key)))
        (should (equal (plist-get (plist-get draft :recipe) :name)
                       "From Lisp"))
        (should-not (plist-get draft :error))
        (should-not (plist-get draft :lisp-buffer))))))

(ert-deftest jetpacs-automations-tree-explains-hybrid-boundary ()
  (jetpacs-automations-test-with-draft (key recipe)
    (setq recipe
          (jetpacs-automation-normalize-recipe
           (plist-put
            (copy-tree recipe t) :steps
            [(:id "local" :kind :action :action "device.notify"
              :args (:text "Now"))
             (:id "host" :kind :action :action "emacs.message"
              :args (:text "Later"))])))
    (puthash key (plist-put (jetpacs-automations--make-draft recipe)
                            :mode "tree")
             jetpacs-automations--drafts)
    (let ((json (jetpacs-node->canonical-json
                 (jetpacs-automations--detail-screen key nil))))
      (should (string-match-p "On device" json))
      (should (string-match-p "After reconnect" json)))))

(ert-deftest jetpacs-automations-dry-run-gates-save-state ()
  (jetpacs-automations-test-with-draft (key _recipe)
    (cl-letf (((symbol-function 'jetpacs-automations--refresh)
               (lambda (_params) nil)))
      (should (eq 'accepted
                  (jetpacs-automations--on-dry-run (list :id key) nil))))
    (let* ((draft (jetpacs-automations--draft key))
           (result (plist-get draft :dry-run))
           (json (jetpacs-node->canonical-json
                  (jetpacs-automations--detail-screen key nil))))
      (should (plist-get result :ok))
      (should (string-match-p "Dry Run passed" json))
      (should (string-match-p
               (regexp-quote "\"label\":\"Save\",\"on_tap\"") json)))))

(provide 'jetpacs-automations-test)
;;; jetpacs-automations-test.el ends here
