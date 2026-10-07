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
    (should (>= (length vectors) 38))
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

(ert-deftest jetpacs-data-test-dated-members-bind ()
  "Dates, times, months and the month grid's object members bind; a rule
between two members holds only between two literals (SPEC 13.8.3)."
  (let ((node (jetpacs-month-grid
               (jetpacs-ref "/p/month")
               :marks (jetpacs-ref "/p/marks")
               :day-styles (jetpacs-ref "/p/day_styles")
               :selected (jetpacs-ref "/p/selected")
               :range-start (jetpacs-ref "/p/range_start")
               :range-end (jetpacs-ref "/p/range_end")
               :min-date "2026-01-01" :max-date (jetpacs-ref "/p/max"))))
    (should (equal (sort (copy-sequence (plist-get node :bind)) #'string<)
                   (sort (list :month "/p/month" :marks "/p/marks"
                               :day_styles "/p/day_styles" :selected "/p/selected"
                               :range_start "/p/range_start" :range_end "/p/range_end"
                               :max_date "/p/max")
                         #'string<)))
    (should (equal (plist-get node :min_date) "2026-01-01"))
    ;; The document decides between a selection and a range; the
    ;; materialized tree is what the literal constructor would build.
    (let* ((marks (jetpacs-month-marks '(("2026-09-04" . (:dots 2)))))
           (styles (jetpacs-month-day-styles
                    (list (cons "2026-09-08"
                                (jetpacs-month-day-style "error" "on_error")))))
           (ranged (jetpacs-data-materialize
                    node (list :p (list :month "2026-09" :marks marks
                                        :day_styles styles
                                        :range_start "2026-09-04"
                                        :range_end "2026-09-08"))
                    :strip t))
           (picked (jetpacs-data-materialize
                    node (list :p (list :month "2026-09" :selected "2026-09-04"))
                    :strip t)))
      (should (equal (jetpacs-node->canonical-json ranged)
                     (jetpacs-node->canonical-json
                      (jetpacs-month-grid
                       "2026-09" :marks '(("2026-09-04" . (:dots 2)))
                       :day-styles (list (cons "2026-09-08"
                                               (jetpacs-month-day-style
                                                "error" "on_error")))
                       :range-start "2026-09-04" :range-end "2026-09-08"
                       :min-date "2026-01-01"))))
      (should (equal (plist-get picked :selected) "2026-09-04"))
      (should-not (plist-member picked :range_start))
      ;; A resolved value of the wrong shape is refused where it was bound.
      (should-error (jetpacs-data-materialize
                     node (list :p (list :month "2026-09" :marks [1])))
                    :type 'jetpacs-data-error)))
  ;; Two literals still answer for each other.
  (should-error (jetpacs-month-grid "2026-09" :selected "2026-09-01"
                                    :range-start "2026-09-02"
                                    :range-end "2026-09-03"))
  (should-error (jetpacs-month-grid (jetpacs-ref "/m") :range-start "2026-09-04"
                                    :range-end "2026-09-02"))
  (should (jetpacs-month-grid "2026-09" :selected (jetpacs-ref "/s")
                              :range-start (jetpacs-ref "/a")
                              :range-end (jetpacs-ref "/b")))
  (let ((date (jetpacs-date-button (jetpacs-ref "/d/label")
                                   (jetpacs-action "x.pick")
                                   :value (jetpacs-ref "/d/value")))
        (time (jetpacs-time-button "Add time" (jetpacs-action "x.pick")
                                   :value (jetpacs-ref "/t/value"))))
    (should (equal (plist-get date :bind) '(:label "/d/label" :value "/d/value")))
    (should (equal (plist-get time :bind) '(:value "/t/value")))
    (should-error (jetpacs-time-button "x" (jetpacs-action "x.pick")
                                       :value "25:00"))))

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

(ert-deftest jetpacs-data-test-templates-build-and-expand ()
  "SPEC 13.8.5: a `jetpacs-template' becomes `:template' beside a
`:children' binding, relative refs are admitted only inside it, the
structural rules are checked at build time, and the Elisp expansion
writes the item key into every descriptor when it stands in for the
Companion."
  (let ((list (jetpacs-lazy-column
               (jetpacs-template "/rows"
                 (jetpacs-with-attrs
                  (jetpacs-row (jetpacs-text (jetpacs-ref "title"))
                               (jetpacs-button "Open" (jetpacs-action "note.open")))
                  :key (jetpacs-ref "id")))
               :spacing 4)))
    (should (equal (plist-get list :bind) '(:children "/rows")))
    (should (equal (plist-get (plist-get list :template) :bind) '(:key "id")))
    (should-not (plist-member list :children))
    (let* ((data (list :rows (vector (list :id "n1" :title "Inbox")
                                     (list :id "n2" :title "Projects"))))
           (sent (jetpacs-data-materialize list data))
           (expanded (jetpacs-data-materialize list data :strip t))
           (row (aref (plist-get expanded :children) 1)))
      ;; Sent to a Companion with the feature: the template stays, the
      ;; copies carry their relative bindings.
      (should (plist-member sent :template))
      (should (equal (mapcar (lambda (r) (plist-get r :key))
                             (append (plist-get sent :children) nil))
                     '("n1" "n2")))
      ;; Expanded here: no template, no bindings, and the item injected.
      (should-not (plist-member expanded :template))
      (should-not (plist-member expanded :bind))
      (should (equal (plist-get row :key) "n2"))
      (should (equal (plist-get (aref (plist-get row :children) 0) :text) "Projects"))
      (should (equal (plist-get (plist-get (plist-get (aref (plist-get row :children) 1) :on_tap)
                                           :args)
                                :item)
                     "n2"))
      ;; A patch to the array re-expands.
      (should (equal (length (plist-get (jetpacs-data-materialize
                                         list (list :rows []) :strip t)
                                        :children))
                     0))))
  ;; Relative refs are refused outside a template; `:key' binds only on
  ;; the template node; the template node needs a bound key.
  (should-error (jetpacs-text (jetpacs-ref "title")))
  (should-error (jetpacs-with-attrs (jetpacs-text "x") :key (jetpacs-ref "/k")))
  (should-error (jetpacs-template "/rows" (jetpacs-text (jetpacs-ref "title")))
                :type 'jetpacs-data-error)
  (should-error (jetpacs-template "/rows"
                  (jetpacs-with-attrs (jetpacs-text (jetpacs-ref "title"))
                                      :key (jetpacs-ref "id") :id "fixed"))
                :type 'jetpacs-data-error)
  (should-error (jetpacs-template "/rows"
                  (jetpacs-with-attrs (jetpacs-row (jetpacs-text-input "f"))
                                      :key (jetpacs-ref "id")))
                :type 'jetpacs-data-error)
  (should-error (jetpacs-template "/rows"
                  (jetpacs-with-attrs
                   (jetpacs-button "Go" (jetpacs-action "x.y" :args '(:item "z")))
                   :key (jetpacs-ref "id")))
                :type 'jetpacs-data-error)
  ;; A template only ever expands :children.
  (should-error (jetpacs-make-node "text" :text (jetpacs--make-template-spec "/r" nil)))
  ;; Nested templates resolve the inner array in the outer item.
  (let* ((nested (jetpacs-column
                  (jetpacs-template "/rows"
                    (jetpacs-with-attrs
                     (jetpacs-column
                      (jetpacs-template "subs"
                        (jetpacs-with-attrs (jetpacs-text (jetpacs-ref "label"))
                                            :key (jetpacs-ref "id"))))
                     :key (jetpacs-ref "id")))))
         (out (jetpacs-data-materialize
               nested (list :rows (vector (list :id "a" :subs (vector (list :id "a1" :label "A1")))))
               :strip t))
         (inner (aref (plist-get (aref (plist-get out :children) 0) :children) 0)))
    (should (equal (plist-get inner :key) "a1"))
    (should (equal (plist-get inner :text) "A1"))))

(ert-deftest jetpacs-data-test-the-shell-sends-templates-only-with-the-feature ()
  "SPEC 22.4: a templated snapshot travels to a Companion that granted
`data.model' AND advertises `data.template'; otherwise Emacs expands it,
and a later patch re-pushes instead of patching a snapshot that holds
no bindings."
  (skip-unless (require 'jetpacs-shell nil t))
  (let ((spec (jetpacs-column
               (jetpacs-template "/rows"
                 (jetpacs-with-attrs (jetpacs-text (jetpacs-ref "title"))
                                     :key (jetpacs-ref "id")))))
        (data (list :rows (vector (list :id "n1" :title "Hi")))))
    (should (equal (jetpacs-shell--prepare-data spec data t t) (cons spec data)))
    (pcase-let ((`(,sent . ,document) (jetpacs-shell--prepare-data spec data t nil)))
      (should-not document)
      (should-not (plist-member sent :template))
      (should (equal (plist-get (aref (plist-get sent :children) 0) :text) "Hi")))
    (pcase-let ((`(,sent . ,document) (jetpacs-shell--prepare-data spec data nil nil)))
      (should-not document)
      (should (equal (plist-get (aref (plist-get sent :children) 0) :key) "n1")))
    ;; The analysis gates see the template as the node it is.
    (should (member "text" (plist-get (jetpacs-shell--analyze-spec spec) :types)))))

(provide 'jetpacs-data-test)
;;; jetpacs-data-test.el ends here
