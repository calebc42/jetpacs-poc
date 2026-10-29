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
         (jetpacs-settings-links nil)
         (jetpacs-settings--app-links nil))
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

;; The bundled app's renderer checks (docs/PLAN-bundled-emacs.md).

(defvar jetpacs-home-mode)

(defcustom jetpacs-settings-test--span 'week
  "An open choice fixture: named spans or a number of days."
  :type '(choice (const :tag "Day" day) (const :tag "Week" week)
                 (integer :tag "Custom"))
  :group 'jetpacs-settings-test)

(defcustom jetpacs-settings-test--scheme 'mirror
  "A choice fixture with an option offered only elsewhere."
  :type '(choice (const :tag "System" system) (const :tag "Mirror" mirror)
                 (const :tag "Off" off))
  :group 'jetpacs-settings-test)

(defun jetpacs-settings-test--nodes (tree)
  "Every node in TREE, depth first."
  (let (out)
    (cl-labels ((walk (n)
                  (when (and (consp n) (plist-get n :t))
                    (push n out)
                    (mapc #'walk (append (plist-get n :children) nil)))))
      (walk tree))
    (nreverse out)))

(defun jetpacs-settings-test--of-type (type tree)
  "The nodes of TYPE in TREE."
  (cl-remove-if-not (lambda (n) (equal (plist-get n :t) type))
                    (jetpacs-settings-test--nodes tree)))

(defun jetpacs-settings-test--texts (tree)
  "The text of every text node, section header and switch in TREE."
  (delq nil (mapcar (lambda (n)
                      (or (plist-get n :text) (plist-get n :title)
                          (and (equal (plist-get n :t) "switch")
                               (plist-get n :label))))
                    (jetpacs-settings-test--nodes tree))))

(ert-deftest jetpacs-settings-kind-mapping ()
  "Custom-type schemas map to the right control kinds."
  (should (eq (jetpacs-settings--kind 'boolean) 'boolean))
  (should (eq (jetpacs-settings--kind 'string) 'string))
  (should (eq (jetpacs-settings--kind '(choice (const a) (const b))) 'choice))
  ;; Options plus one free string or number arm: an open choice; a free
  ;; arm the renderer has no field for keeps the raw expression editor.
  (should (eq (jetpacs-settings--kind '(choice (const a) string)) 'open-choice))
  (should (eq (jetpacs-settings--kind '(choice (const a) (repeat string)))
              'sexp))
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
  "Jetpacs, Emacs, and App are reachable from the one drawer target."
  (let ((json (jetpacs-node->canonical-json (jetpacs-settings--view))))
    (dolist (label '("Jetpacs Settings" "Emacs Settings" "App Settings"))
      (should (string-match-p (regexp-quote label) json)))
    (dolist (action '("settings.emacs" "settings.app" "settings.refresh"))
      (should (string-match-p (regexp-quote action) json)))
    (should (string-match-p "companion:settings" json))))

(ert-deftest jetpacs-settings-actions-declare-their-public-contract ()
  "Every settings verb exposes readable docs and its consumed arguments."
  (dolist (name '("settings.set" "settings.reset"
                  "settings.emacs" "settings.app" "settings.refresh"))
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
  (jetpacs-dialog-slot-reset)
  (let ((ids '("req-1" "req-2")) (abandoned nil) (shown nil))
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
        (should (jetpacs-dialog-slot-live-p jetpacs-settings-dialog-owner))
        (funcall first-callback "error" nil '(:code 1301))
        (should (jetpacs-dialog-slot-live-p jetpacs-settings-dialog-owner))
        (should (equal (jetpacs-settings-dialog-params)
                       '(:surface "s2")))
        (funcall (car shown) "dismissed" nil nil)
        (should-not (jetpacs-dialog-slot-live-p
                     jetpacs-settings-dialog-owner)))))
  ;; The on-submit route: fields flow out of the conclusion.
  (jetpacs-dialog-slot-reset)
  (let ((got nil) (cb nil))
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

(defvar jetpacs-settings-test--flag nil
  "A scratch boolean for the section-removal test.")

(defun jetpacs-settings-test--subscribed-p (id)
  "Non-nil when some surface holds a state subscription for exactly ID."
  (let (found)
    (maphash (lambda (key _fn) (when (equal (cdr key) id) (setq found t)))
             jetpacs--state-handlers)
    found))

(ert-deftest jetpacs-settings-remove-section-clears-toggle-subscriptions ()
  "Removing a section drops the switch handlers its registration installed,
and an owned section is claimed until then."
  (let ((jetpacs-settings-registry nil)
        (id "setting/jetpacs-settings-test--flag"))
    (with-jetpacs-owner "sectionapp"
      (jetpacs-settings-register-section
       "Tmp" '((jetpacs-settings-test--flag :label "Flag"))))
    (should (jetpacs-settings-test--subscribed-p id))
    (should (equal (jetpacs--owner-of "settings-section" "Tmp") "sectionapp"))
    (jetpacs-settings-remove-section "Tmp")
    (should-not (jetpacs-settings-test--subscribed-p id))
    (should-not (jetpacs--owner-of "settings-section" "Tmp"))
    (should-not jetpacs-settings-registry)))


(ert-deftest jetpacs-settings-text-fields-carry-no-second-label ()
  "The label row names a field; the field itself repeated it, so a text
input carries no label of its own."
  (jetpacs-settings-test--env
    (let* ((item (jetpacs-settings-item 'jetpacs-settings-test--name
                                        :label "Name"))
           (input (car (jetpacs-settings-test--of-type "text_input" item))))
      (should input)
      (should-not (plist-get input :label))
      (should (equal (cl-count "Name" (jetpacs-settings-test--texts item)
                               :test #'equal)
                     1)))))

(ert-deftest jetpacs-settings-builtin-variables-get-their-type ()
  "A built-in variable has a Customize type once cus-start loads, which
the renderer now asks for: `user-full-name' is a plain string field,
not a Lisp expression showing the name in quotes."
  (should (eq (jetpacs-settings--kind (jetpacs-settings--type 'user-full-name))
              'string))
  (let* ((user-full-name "Ada")
         (input (car (jetpacs-settings-test--of-type
                      "text_input" (jetpacs-settings-item 'user-full-name)))))
    (should (equal (plist-get input :value) "Ada"))))

(ert-deftest jetpacs-settings-completes-a-half-loaded-cus-start ()
  "Emacs for Android's first-start dump provides cus-start after only
its preload half, which types no built-in variable; the renderer loads
the rest once instead of showing raw Lisp."
  (require 'cus-start)
  (let ((type (get 'user-full-name 'custom-type)))
    (unwind-protect
        (progn
          (put 'user-full-name 'custom-type nil)
          (should (eq (jetpacs-settings--kind
                       (jetpacs-settings--type 'user-full-name))
                      'string)))
      (put 'user-full-name 'custom-type type))))

(ert-deftest jetpacs-settings-library-loads-before-rendering ()
  "A row names the library that defines its variable; rendering loads it
instead of reporting the variable as not loaded."
  (let* ((dir (make-temp-file "jetpacs-settings-lib" t))
         (load-path (cons dir load-path)))
    (unwind-protect
        (progn
          (with-temp-file (expand-file-name "jetpacs-settings-test-lib.el" dir)
            (insert ";;; -*- lexical-binding: t; -*-\n"
                    "(defcustom jetpacs-settings-test-lib-var 5 \"Doc.\"\n"
                    "  :type 'integer :group 'jetpacs-settings-test)\n"
                    "(provide 'jetpacs-settings-test-lib)\n"))
          (should-not (boundp 'jetpacs-settings-test-lib-var))
          (let ((item (jetpacs-settings-item 'jetpacs-settings-test-lib-var
                                             :label "Lib"
                                             :library 'jetpacs-settings-test-lib)))
            (should (boundp 'jetpacs-settings-test-lib-var))
            (should (jetpacs-settings-test--of-type "text_input" item))))
      (delete-directory dir t))))

(ert-deftest jetpacs-settings-open-choice-offers-options-and-a-field ()
  "A choice with a free-form arm renders its options and a field for any
other value, and decodes either way; options travel as wire keys."
  (jetpacs-settings-test--env
    (should (eq (jetpacs-settings--kind
                 (jetpacs-settings--type 'jetpacs-settings-test--span))
                'open-choice))
    (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--span "week")
                   '(week)))
    (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--span "Day")
                   '(day)))
    (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--span "10")
                   '(10)))
    (should-not (jetpacs-settings--decode 'jetpacs-settings-test--span "soon"))
    (set-default 'jetpacs-settings-test--span 10)
    (unwind-protect
        (let* ((item (jetpacs-settings-item 'jetpacs-settings-test--span))
               (enum (car (jetpacs-settings-test--of-type "enum_list" item)))
               (field (car (jetpacs-settings-test--of-type "text_input" item))))
          (should (equal (mapcar (lambda (o) (plist-get o :value))
                                 (plist-get enum :options))
                         '("day" "week")))
          (should-not (plist-get enum :value))
          (should (equal (plist-get field :value) "10"))
          (should (equal (plist-get field :hint) "Custom")))
      (set-default 'jetpacs-settings-test--span 'week))))

(ert-deftest jetpacs-settings-exclude-hides-an-option-unless-current ()
  "An excluded option is not offered, except while it is the value."
  (jetpacs-settings-test--env
    (cl-flet ((offered ()
                (mapcar (lambda (o) (plist-get o :label))
                        (plist-get (car (jetpacs-settings-test--of-type
                                         "enum_list"
                                         (jetpacs-settings-item
                                          'jetpacs-settings-test--scheme
                                          :exclude '(off))))
                                   :options))))
      (should (equal (offered) '("System" "Mirror")))
      (set-default 'jetpacs-settings-test--scheme 'off)
      (unwind-protect (should (equal (offered) '("System" "Mirror" "Off")))
        (set-default 'jetpacs-settings-test--scheme 'mirror)))))

(ert-deftest jetpacs-settings-plain-copy-is-the-bundled-apps-only ()
  "`:plain' relabels and rewords in the bundled app and nowhere else; the
relabeled options still decode, by their wire keys."
  (jetpacs-settings-test--env
    (let ((plain '(:label "Plan" :summary "Plain words."
                   :choices ((auto . "On its own") (manual . "By hand")))))
      (let* ((jetpacs-home-mode "emacs")
             (texts (jetpacs-settings-test--texts
                     (jetpacs-settings-item 'jetpacs-settings-test--mode
                                            :label "Mode" :plain plain))))
        (should (member "Mode" texts))
        (should (member "A choice fixture." texts))
        (should-not (member "Plan" texts)))
      (let* ((jetpacs-home-mode "bundled")
             (item (jetpacs-settings-item 'jetpacs-settings-test--mode
                                          :label "Mode" :plain plain))
             (enum (car (jetpacs-settings-test--of-type "enum_list" item))))
        (should (member "Plan" (jetpacs-settings-test--texts item)))
        (should (member "Plain words." (jetpacs-settings-test--texts item)))
        (should (equal (mapcar (lambda (o) (plist-get o :label))
                               (plist-get enum :options))
                       '("On its own" "By hand")))
        (should (equal (jetpacs-settings--decode 'jetpacs-settings-test--mode
                                                 "manual")
                       '(manual)))))))

(ert-deftest jetpacs-settings-sections-order-advanced-and-bundled-rows ()
  "Sections follow the display order; `:advanced' rows from any section,
then the advanced links, gather under one Advanced header at the end;
a section left empty shows no header; `:standalone-only' rows are left
out of the bundled app only."
  (jetpacs-settings-test--env
    (let ((jetpacs-settings-section-order '("First" "Second")))
      (jetpacs-settings-register-section
       "Second" '((jetpacs-settings-test--count :label "Count")
                  (jetpacs-settings-test--name :label "Name"
                                               :standalone-only t)))
      (jetpacs-settings-register-section
       "Dev" '((jetpacs-settings-test--flag :label "Flag" :advanced t)))
      (jetpacs-settings-register-section
       "First" '((jetpacs-settings-test--mode :label "Mode")))
      (jetpacs-settings-add-link 10 (lambda () (jetpacs-text "Everyday link")))
      (jetpacs-settings-add-link 20 (lambda () (jetpacs-text "Dev link")) t)
      (jetpacs-settings-add-link 30 #'ignore)
      (cl-flet ((headers-and-rows ()
                  (cl-remove-if-not
                   (lambda (s) (member s '("First" "Second" "Dev" "Advanced"
                                           "Mode" "Count" "Name" "Flag"
                                           "Everyday link" "Dev link")))
                   (jetpacs-settings-test--texts
                    (apply #'jetpacs-column (jetpacs-settings-sections))))))
        (let ((jetpacs-home-mode "emacs"))
          (should (equal (headers-and-rows)
                         '("First" "Mode" "Second" "Count" "Name"
                           "Everyday link" "Advanced" "Flag" "Dev link"))))
        (let ((jetpacs-home-mode "bundled"))
          (should (equal (headers-and-rows)
                         '("First" "Mode" "Second" "Count"
                           "Everyday link" "Advanced" "Flag" "Dev link"))))))))

(ert-deftest jetpacs-settings-homed-sections-render-on-their-own-screen ()
  "A section in `jetpacs-settings-homed-sections' stays registered (so
`settings.set' still accepts it) but leaves the Emacs Settings body;
`jetpacs-settings-section-nodes' draws it with its Advanced rows."
  (jetpacs-settings-test--env
    (let ((jetpacs-settings-homed-sections '("Homed"))
          (jetpacs-home-mode "emacs"))
      (jetpacs-settings-register-section
       "Kept" '((jetpacs-settings-test--mode :label "Mode")))
      (jetpacs-settings-register-section
       "Homed" '((jetpacs-settings-test--count :label "Count")
                 (jetpacs-settings-test--flag :label "Flag" :advanced t)))
      (cl-flet ((texts (nodes)
                  (cl-remove-if-not
                   (lambda (s) (member s '("Kept" "Mode" "Homed" "Count"
                                           "Advanced" "Flag")))
                   (jetpacs-settings-test--texts
                    (apply #'jetpacs-column nodes)))))
        (should (equal (texts (jetpacs-settings-sections)) '("Kept" "Mode")))
        (should (equal (texts (jetpacs-settings-section-nodes '("Homed")))
                       '("Homed" "Count" "Advanced" "Flag"))))
      (should (jetpacs-settings--entry 'jetpacs-settings-test--flag)))))

(ert-deftest jetpacs-settings-app-page-holds-the-companion-options ()
  "App Settings draws Theme, the `jetpacs-settings-app-sections' and the
links placed on it, advanced ones under its own Advanced header; Emacs
Settings draws none of them."
  (jetpacs-settings-test--env
    (let ((jetpacs-home-mode "emacs"))
      (jetpacs-settings-register-section
       "Appearance" '((jetpacs-settings-test--mode :label "Mode")))
      (jetpacs-settings-register-section
       "Devtools" '((jetpacs-settings-test--flag :label "Flag" :advanced t)))
      (jetpacs-settings-register-section
       "User Defaults" '((jetpacs-settings-test--count :label "Count")))
      (jetpacs-settings-add-link 10 (lambda () (jetpacs-text "Emacs link")))
      (jetpacs-settings-add-link 20 (lambda () (jetpacs-text "App link"))
                                 nil 'app)
      (jetpacs-settings-add-link 30 (lambda () (jetpacs-text "App dev link"))
                                 t 'app)
      (cl-flet ((texts (node)
                  (cl-remove-if-not
                   (lambda (s) (member s '("Theme" "Appearance" "Mode"
                                           "Devtools" "Flag" "User Defaults"
                                           "Count" "Advanced" "Emacs link"
                                           "App link" "App dev link")))
                   (jetpacs-settings-test--texts node))))
        (should (equal (texts (apply #'jetpacs-column
                                     (jetpacs-settings-sections)))
                       '("User Defaults" "Count" "Emacs link")))
        (should (equal (texts (jetpacs-settings--app-body))
                       '("Theme" "Appearance" "Mode" "App link"
                         "Advanced" "Flag" "App dev link"))))
      (should (string-search "modus.show"
                             (jetpacs-node->canonical-json
                              (jetpacs-settings--app-body))))
      (should (equal (plist-get (jetpacs-settings--app-view nil) :t)
                     "scaffold")))))

(ert-deftest jetpacs-settings-text-fields-span-the-row ()
  "Every settings text field spans its row, whatever its value's length:
a field takes its own width otherwise, so short values sat in short boxes."
  (jetpacs-settings-test--env
    (let ((jetpacs-home-mode "emacs"))
      (dolist (sym '(jetpacs-settings-test--count jetpacs-settings-test--name))
        (let* ((item (jetpacs-settings-item sym))
               (fields (jetpacs-settings-test--of-type "text_input" item)))
          (should fields)
          (dolist (field fields)
            (should (equal (plist-get field :fill_fraction) 1))))))))

(ert-deftest jetpacs-settings-measure-insets-and-centers ()
  "A settings body is always inset from the window's sides; on a wide
window it also centers at `jetpacs-measure-dp'."
  (let ((body (jetpacs-lazy-column (jetpacs-text "Row"))))
    (cl-letf (((symbol-function 'jetpacs-window-class)
               (lambda (_axis) "compact")))
      (let ((node (jetpacs-settings-measure body)))
        (should (equal (plist-get node :t) "lazy_column"))
        (should (equal (plist-get node :pad)
                       (list :horizontal jetpacs-edge-gap)))
        (jetpacs-node->canonical-json node)
      ;; Without :inset a phone gets the body untouched (the reading
      ;; screens' rule).
      (should (eq (jetpacs-measure body) body))))
    (cl-letf (((symbol-function 'jetpacs-window-class)
               (lambda (_axis) "expanded")))
      (let* ((node (jetpacs-settings-measure body))
             (json (jetpacs-node->canonical-json node)))
        (should (equal (plist-get node :t) "box"))
        (should (string-search (format "\"max_width\":%d"
                                       jetpacs-measure-dp)
                               json))
        (should (string-search "top_center" json))))))

(provide 'jetpacs-settings-test)
;;; jetpacs-settings-test.el ends here
