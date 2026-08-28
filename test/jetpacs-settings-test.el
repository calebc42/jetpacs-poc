;;; jetpacs-settings-test.el --- ERT for settings + customize -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;; Batch-safe: persistence is stubbed (no custom-file writes), refresh
;; pushes are collected, and the fixtures are this file's own defcustoms
;; so the schema mapping runs against real custom-type metadata.

(require 'ert)
(require 'jetpacs-settings)
(require 'jetpacs-customize)

(defgroup jetpacs-settings-test nil "Fixtures." :group 'emacs)

(defcustom jetpacs-settings-test--flag t
  "A boolean fixture."
  :type 'boolean :group 'jetpacs-settings-test)

(defcustom jetpacs-settings-test--mode 'auto
  "A choice fixture."
  :type '(choice (const :tag "Automatic" auto)
                 (const :tag "Manual" manual))
  :group 'jetpacs-settings-test)

(defcustom jetpacs-settings-test--name "abc"
  "A string fixture."
  :type 'string :group 'jetpacs-settings-test)

(defcustom jetpacs-settings-test--count 3
  "A number fixture."
  :type 'integer :group 'jetpacs-settings-test)

(ert-deftest jetpacs-settings-cold-drawer-targets-are-required ()
  "All Settings and Customize exist before local surface.open taps."
  (dolist (surface '("app:jetpacs.settings" "app:jetpacs.customize"))
    (should
     (plist-get
      (alist-get surface jetpacs-shell--roots nil nil #'equal)
      :required))))

(defmacro jetpacs-settings-test--env (&rest body)
  "Stub persistence/refresh/toast; reset fixture values afterwards."
  (declare (indent 0))
  `(let ((saved nil) (toasts nil) (refreshes 0)
         (jetpacs-settings-registry nil)
         (jetpacs-settings-links nil))
     (cl-letf (((symbol-function 'jetpacs-settings-save-variable)
                (lambda (sym value)
                  (push (cons sym value) saved)
                  (set-default sym value)))
               ((symbol-function 'jetpacs-toast)
                (cl-function (lambda (text &key duration-s)
                               (ignore duration-s) (push text toasts))))
               ((symbol-function 'jetpacs-settings-refresh)
                (lambda () (cl-incf refreshes)))
               ((symbol-function 'jetpacs-customize--refresh)
                (lambda () (cl-incf refreshes))))
       (ignore saved toasts refreshes)
       (unwind-protect (progn ,@body)
         (set-default 'jetpacs-settings-test--flag t)
         (set-default 'jetpacs-settings-test--mode 'auto)
         (set-default 'jetpacs-settings-test--name "abc")
         (set-default 'jetpacs-settings-test--count 3)))))

(ert-deftest jetpacs-settings-kind-mapping ()
  "Custom-type schemas map to the right control kinds."
  (should (eq (jetpacs-settings--kind 'boolean) 'boolean))
  (should (eq (jetpacs-settings--kind 'string) 'string))
  (should (eq (jetpacs-settings--kind '(choice (const a) (const b))) 'choice))
  (should (eq (jetpacs-settings--kind '(choice (const a) string)) 'sexp))
  (should (eq (jetpacs-settings--kind 'natnum) 'number))
  (should (eq (jetpacs-settings--kind '(repeat string)) 'sexp)))

(ert-deftest jetpacs-settings-choice-options-read-tags ()
  "Const arms yield (LABEL . VALUE) with :tag labels when present."
  (should (equal (jetpacs-settings--choice-options
                  (jetpacs-settings--type 'jetpacs-settings-test--mode))
                 '(("Automatic" . auto) ("Manual" . manual)))))

(ert-deftest jetpacs-settings-wire-decode-per-kind ()
  "Wire payloads decode into each kind's value domain, or refuse."
  (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--flag t)
                 '(t)))
  (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--flag
                                           :json-false)
                 '(nil)))
  (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--mode
                                           ["Manual"])
                 '(manual)))
  (should-not (jetpacs-settings--decode 'jetpacs-settings-test--mode
                                        ["Bogus"]))
  (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--count "7")
                 '(7)))
  (should-not (jetpacs-settings--decode 'jetpacs-settings-test--count "x")))

(ert-deftest jetpacs-settings-link-add-remove-pair ()
  "The public pair preserves order and removal deletes every duplicate.
Removing an absent builder is idempotent, so register/unregister cycles
do not require callers to mutate the foundation registry themselves."
  (jetpacs-settings-test--env
    (jetpacs-settings-add-link 30 #'car)
    (jetpacs-settings-add-link 20 #'cdr)
    (jetpacs-settings-add-link 10 #'car)
    (should (equal (mapcar #'cadr jetpacs-settings-links)
                   '(car cdr car)))
    (jetpacs-settings-remove-link #'car)
    (should (equal (mapcar #'cadr jetpacs-settings-links) '(cdr)))
    (let ((remaining jetpacs-settings-links))
      (jetpacs-settings-remove-link #'car)
      (should (equal jetpacs-settings-links remaining)))))

(ert-deftest jetpacs-settings-apply-validates-against-schema ()
  "Schema-invalid values toast and refuse; valid ones save through."
  (jetpacs-settings-test--env
    (should-not (jetpacs-settings-apply 'jetpacs-settings-test--count "nope"))
    (should-not saved)
    (should (cl-find-if (lambda (s) (string-match-p "Invalid" s)) toasts))
    (should (jetpacs-settings-apply 'jetpacs-settings-test--count 9))
    (should (equal saved '((jetpacs-settings-test--count . 9))))))

(ert-deftest jetpacs-settings-set-action-is-registry-gated ()
  "settings.set touches registry symbols only; customize.set is wider."
  (jetpacs-settings-test--env
    (should (eq (jetpacs-settings--action-set
                 '(:name "jetpacs-settings-test--count" :value "5") nil)
                'rejected))
    (jetpacs-settings-register-section
     "Test" '((jetpacs-settings-test--count)))
    (should (eq (jetpacs-settings--action-set
                 '(:name "jetpacs-settings-test--count" :value "5") nil)
                'accepted))
    (should (= (default-value 'jetpacs-settings-test--count) 5))
    ;; The customize gate is custom-variable-p, not the registry.
    (should (eq (jetpacs-customize--action-set
                 '(:name "jetpacs-settings-test--name" :value "xyz") nil)
                'accepted))
    (should (equal (default-value 'jetpacs-settings-test--name) "xyz"))
    (should (eq (jetpacs-customize--action-set
                 '(:name "not-a-defcustom-at-all" :value "1") nil)
                'rejected))))

(ert-deftest jetpacs-settings-item-renders-schema-controls ()
  "Boolean -> switch, choice -> enum list with the current tag selected."
  (jetpacs-settings-test--env
    (let ((sw (jetpacs-settings-item 'jetpacs-settings-test--flag))
          (en (jetpacs-settings-item 'jetpacs-settings-test--mode))
          types)
      (cl-labels ((walk (n)
                    (push (plist-get n :t) types)
                    (mapc #'walk (append (plist-get n :children) nil))))
        (walk sw) (walk en))
      (should (member "switch" types))
      (should (member "enum_list" types)))))

(ert-deftest jetpacs-customize-members-and-flat-search ()
  "The group walk sees this file's fixtures; search narrows the flat list."
  (pcase-let ((`(,_groups ,vars ,_faces)
               (jetpacs-customize--members 'jetpacs-settings-test)))
    (should (memq 'jetpacs-settings-test--flag vars))
    (should (memq 'jetpacs-settings-test--mode vars)))
  (let ((jetpacs-customize--search "jetpacs-settings-test--fl")
        (jetpacs-customize--modified-only nil))
    (should (equal (jetpacs-customize--flat-vars)
                   '(jetpacs-settings-test--flag)))))

(ert-deftest jetpacs-customize-browse-pops-on-breadcrumb ()
  "Descending appends; a breadcrumb tap truncates back to that depth."
  (jetpacs-settings-test--env
    (let ((jetpacs-customize--path '(emacs))
          (jetpacs-customize--search "")
          (jetpacs-customize--modified-only nil))
      (should (eq (jetpacs-customize--action-browse
                   '(:group "jetpacs-settings-test") nil)
                  'accepted))
      (should (equal jetpacs-customize--path '(emacs jetpacs-settings-test)))
      (should (eq (jetpacs-customize--action-browse '(:group "emacs") nil)
                  'accepted))
      (should (equal jetpacs-customize--path '(emacs)))
      (should (eq (jetpacs-customize--action-browse '(:group "no-such") nil)
                  'rejected)))))

(ert-deftest jetpacs-settings-drawer-entry-opens-the-required-hub ()
  "The drawer has one local target; the hub owns category enumeration."
  (let* ((entry (jetpacs-settings-drawer-entry))
         (tap (plist-get entry :on_tap))
         (json (jetpacs-node->canonical-json entry)))
    (should (equal (plist-get entry :t) "card"))
    (should (string-match-p "Settings" json))
    (should (equal (plist-get tap :builtin) "surface.open"))
    (should (equal (plist-get tap :surface) "app:jetpacs.settings"))))

(ert-deftest jetpacs-settings-hub-enumerates-its-owned-categories ()
  "Jetpacs, Emacs, and Theme are reachable from the one drawer target."
  (let ((json (jetpacs-node->canonical-json (jetpacs-settings--view))))
    (dolist (label '("Jetpacs Settings" "Emacs Settings" "Theme"))
      (should (string-match-p (regexp-quote label) json)))
    (dolist (action '("settings.emacs" "modus.show" "settings.refresh"))
      (should (string-match-p (regexp-quote action) json)))
    (should (string-match-p "companion:settings" json))))

(ert-deftest jetpacs-settings-actions-declare-their-public-contract ()
  "Every settings verb exposes readable docs and its consumed arguments."
  (dolist (name '("settings.set" "settings.reset"
                  "settings.emacs" "settings.refresh"))
    (let ((doc (plist-get (jetpacs-action-schema name) :doc)))
      (should (and (stringp doc) (not (string-empty-p doc))))))
  (should (equal (mapcar (lambda (arg) (plist-get arg :name))
                         (plist-get (jetpacs-action-schema "settings.set")
                                    :args))
                 '(name value))))

;;;; The one-live-dialog slot (§3 step 2)

(ert-deftest jetpacs-settings-dialog-slot-single-writer ()
  "The slot is single-writer: a second show abandons the first, the
first's late conclusion (the abandon's 1301) must not clear the LIVE
slot, a dismissal clears it, and a submitted conclusion routes its
captured fields to ON-SUBMIT whoever owns the slot by then."
  (let ((jetpacs-settings--dialog nil)
        (ids '("req-1" "req-2")) (abandoned nil) (shown nil))
    (cl-letf (((symbol-function 'jetpacs-client) (lambda () 'fake))
              ((symbol-function 'ebp-client-abandon)
               (lambda (_c id) (push id abandoned)))
              ((symbol-function 'ebp-client-dialog-show)
               (lambda (_c _id _spec &rest kw)
                 (push (plist-get kw :callback) shown)
                 (pop ids))))
      (jetpacs-settings-show-dialog "d" nil :params '(:surface "s1"))
      (jetpacs-settings-show-dialog "d" nil :params '(:surface "s2"))
      (let ((first-callback (cadr shown))) ; SHOWN is push-ordered
        (should (equal abandoned '("req-1")))
        (should (equal (plist-get jetpacs-settings--dialog :request-id)
                       "req-2"))
        (funcall first-callback "error" nil '(:code 1301))
        (should (equal (plist-get jetpacs-settings--dialog :request-id)
                       "req-2"))
        (should (equal (jetpacs-settings-dialog-params)
                       '(:surface "s2")))
        (funcall (car shown) "dismissed" nil nil)
        (should-not jetpacs-settings--dialog))))
  ;; The on-submit route: fields flow out of the conclusion.
  (let ((jetpacs-settings--dialog nil) (got nil) (cb nil))
    (cl-letf (((symbol-function 'jetpacs-client) (lambda () 'fake))
              ((symbol-function 'ebp-client-abandon) #'ignore)
              ((symbol-function 'ebp-client-dialog-show)
               (lambda (_c _id _spec &rest kw)
                 (setq cb (plist-get kw :callback))
                 "req-3")))
      (jetpacs-settings-show-dialog
       "d" nil :on-submit (lambda (fields) (setq got fields)))
      (funcall cb "submitted" '(:fields (:name "x")) nil)
      (should (equal got '(:name "x"))))))

(provide 'jetpacs-settings-test)
;;; jetpacs-settings-test.el ends here
