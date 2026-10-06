;;; jetpacs-data-test.el --- ERT for the surface data document -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; SPEC 13.8 (amendment #194).  `jetpacs-data-materialize' is the Elisp
;; reference materializer: every ebp/goldens/data.golden vector must
;; reproduce exactly, as the Companion and validate.py reproduce it.  The
;; constructor side pins the `jetpacs-ref' hoist, and the shell's grant
;; decision pins the portable push.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-widgets)
(require 'jetpacs-data)

(defvar jetpacs-data-test--ebp-spec-dir
  (or (getenv "EBP_SPEC_DIR")
      (expand-file-name "../../ebp"
                        (file-name-directory (or load-file-name buffer-file-name))))
  "The ebp specification checkout holding goldens/.")

(defun jetpacs-data-test--vectors ()
  (with-temp-buffer
    (insert-file-contents
     (expand-file-name "goldens/data.golden" jetpacs-data-test--ebp-spec-dir))
    (cl-loop for line in (split-string (buffer-string) "\n" t)
             collect (cons (substring line 0 2)
                           (json-parse-string
                            (substring line 3)
                            :object-type 'plist :array-type 'array
                            :null-object nil :false-object :json-false)))))

(ert-deftest jetpacs-data-test-every-golden-vector-reproduces ()
  "SPEC 24.5: the materialization corpus, known answer by known answer."
  (let ((vectors (jetpacs-data-test--vectors)))
    (should (>= (length vectors) 21))
    (pcase-dolist (`(,index . ,vector) vectors)
      (let* ((expect (plist-get vector :expect))
             (outcome (condition-case err
                          (cons 'ok (jetpacs-data-materialize
                                     (plist-get vector :spec)
                                     (plist-get vector :data)))
                        (jetpacs-data-error (cons 'reject (nth 1 err))))))
        (cond
         ((plist-member expect :materialized)
          (should (equal (list index 'ok) (list index (car outcome))))
          (should (equal (list index (jetpacs-node->canonical-json
                                      (plist-get expect :materialized)))
                         (list index (jetpacs-node->canonical-json (cdr outcome))))))
         (t
          (should (equal (list index 'reject (plist-get expect :reject))
                         (list index (car outcome) (cdr outcome))))))))))

(ert-deftest jetpacs-data-test-refs-hoist-into-bind ()
  "A `jetpacs-ref' leaves the member absent and binds it (SPEC 13.8.3)."
  (should (equal (jetpacs-text (jetpacs-ref "/journal/title"))
                 '(:t "text" :bind (:text "/journal/title"))))
  (should (equal (jetpacs-text (jetpacs-ref "/t") :color (jetpacs-ref "/c") :max-lines 2)
                 '(:t "text" :max_lines 2 :bind (:text "/t" :color "/c"))))
  ;; A bound value is the Companion's to size against :max-length.
  (let ((node (jetpacs-text-input "f" :value (jetpacs-ref "/form/x")
                                  :max-length 3 :single-line t)))
    (should (equal (plist-get node :bind) '(:value "/form/x")))
    (should-not (plist-member node :value)))
  ;; Dispatch, identity and node slots stay authored.
  (should-error (jetpacs-button "Go" (jetpacs-ref "/action")))
  (should-error (jetpacs-make-node "text" :text "x" :id (jetpacs-ref "/id")))
  (should-error (jetpacs-make-node "section_header" :title "T"
                                   :trailing (jetpacs-ref "/node")))
  ;; A sub-spec cannot bind, and a pointer must be absolute.
  (should-error (jetpacs-make-node nil :label (jetpacs-ref "/l")))
  (should-error (jetpacs-text (jetpacs-ref "journal/title")))
  ;; An authored :bind is a universal attribute with the same grammar.
  (should (equal (plist-get (jetpacs-with-attrs (jetpacs-text "x") :bind '(:color "/c"))
                            :bind)
                 '(:color "/c")))
  (should-error (jetpacs-with-attrs (jetpacs-text "x") :bind '(:color "c"))))

(ert-deftest jetpacs-data-test-ops-and-document-checks ()
  "DataOps are built checked; the document is bounded (SPEC 13.8.1, 13.8.4)."
  (should (equal (jetpacs-data-set "/a/b" 1) '(:op "set" :path "/a/b" :value 1)))
  (should (equal (jetpacs-data-remove "/a") '(:op "remove" :path "/a")))
  (should-error (jetpacs-data-remove "") :type 'jetpacs-data-error)
  (should-error (jetpacs-data-set "a" 1) :type 'jetpacs-data-error)
  (should-error (jetpacs-data-check-op '(:op "move" :path "/a")) :type 'jetpacs-data-error)
  (should-error (jetpacs-data-check-op '(:op "set" :path "" :value 1))
                :type 'jetpacs-data-error)
  (should (jetpacs-data-check-op '(:op "set" :path "" :value (:fresh t))))
  (should-error (jetpacs-data-check-document [1]) :type 'jetpacs-data-error)
  (should (equal (jetpacs-data-resolve '(:a (:b [10 20])) '("a" "b" "1") "p")
                 '(t . 20)))
  (should (equal (jetpacs-data-resolve '(:a (:b [10 20])) '("a" "b" "2") "p")
                 '(nil)))
  (should-error (jetpacs-data-resolve '(:a [1]) '("a" "01") "p")
                :type 'jetpacs-data-error))

(ert-deftest jetpacs-data-test-the-shell-prepares-for-either-companion ()
  "SPEC 12 rule 3: bindings travel only to a Companion that granted
`data.model'; otherwise they are resolved here and stripped."
  (skip-unless (require 'jetpacs-shell nil t))
  (let ((spec (jetpacs-column (jetpacs-text (jetpacs-ref "/title"))
                              (jetpacs-text "literal")))
        (data '(:title "Hi")))
    (should (equal (jetpacs-shell--prepare-data spec data t) (cons spec data)))
    (should (equal (jetpacs-shell--prepare-data spec data nil)
                   (cons (jetpacs-column (jetpacs-text "Hi") (jetpacs-text "literal"))
                         nil)))
    ;; Unbound documents pass through untouched.
    (let ((plain (jetpacs-text "x")))
      (should (eq (car (jetpacs-shell--prepare-data plain nil nil)) plain)))
    ;; A binding the Companion would refuse is refused here first.
    (should-error (jetpacs-shell--prepare-data
                   '(:t "text" :text "x" :bind (:text "/t")) data t))))

(provide 'jetpacs-data-test)
;;; jetpacs-data-test.el ends here
