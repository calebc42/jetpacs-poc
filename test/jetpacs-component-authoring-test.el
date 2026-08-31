;;; jetpacs-component-authoring-test.el --- Inert component model tests -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-component-authoring)

(defun jetpacs-component-authoring-test--document (component root)
  "Return a version-one COMPONENT document containing ROOT."
  (list :schema-version 1 :component component :root root))

(defun jetpacs-component-authoring-test--action-document (descriptor)
  "Return a minimal Action document using DESCRIPTOR."
  (jetpacs-component-authoring-test--document
   "action"
   (list :t "jetpacs.action" :label "Run" :on_tap descriptor)))

(defun jetpacs-component-authoring-test--sorted-names (keys)
  "Return sorted symbol names for KEYS."
  (sort (mapcar #'symbol-name keys) #'string<))

(ert-deftest jetpacs-component-authoring-defaults-cover-exact-catalog ()
  "Every supported component has one fresh, renderable canonical default."
  (should (equal jetpacs-component-authoring-components
                 '("action" "choice" "tabs" "section-navigator" "panel"
                   "text-field" "editor")))
  (let ((expected '("jetpacs.action" "jetpacs.choice" "jetpacs.tabs"
                    "jetpacs.section_navigator" "jetpacs.panel"
                    "text_input" "editor")))
    (cl-mapc
     (lambda (component type)
       (let* ((left (jetpacs-component-authoring-default-document component))
              (right (jetpacs-component-authoring-default-document component))
              (root (jetpacs-component-authoring-compile-document left)))
         (should (equal (plist-get left :component) component))
         (should (equal (plist-get root :t) type))
         (should (equal left right))
         (should-not (eq left right))))
     jetpacs-component-authoring-components expected))
  (let* ((panel (jetpacs-component-authoring-default-document "panel"))
         (children (plist-get (plist-get panel :root) :children)))
    (should (= (length children) 2))
    (should (equal (mapcar (lambda (node) (plist-get node :t))
                           (append children nil))
                   '("text" "jetpacs.action")))))

(ert-deftest jetpacs-component-authoring-canonical-lisp-round-trips ()
  "Canonical text and digest are stable for all seven defaults."
  (dolist (component jetpacs-component-authoring-components)
    (let* ((document
            (jetpacs-component-authoring-default-document component))
           (source (jetpacs-component-authoring-print-document document))
           (again (jetpacs-component-authoring-read-document source)))
      (should (equal document again))
      (should (equal source
                     (jetpacs-component-authoring-print-document again)))
      (should (equal (jetpacs-component-authoring-document-digest document)
                     (jetpacs-component-authoring-document-digest again)))))
  (should (string-match-p
           (regexp-quote ":root")
           (jetpacs-component-authoring-print-document
            (jetpacs-component-authoring-default-document "action")))))

(ert-deftest jetpacs-component-authoring-reader-is-inert-and-closed ()
  "Reader syntax cannot execute, trail, or enlarge the global obarray."
  (let ((name "jetpacs-component-authoring-never-intern-this"))
    (should-not (intern-soft name))
    (should-error
     (jetpacs-component-authoring-read-document
      (format
       "(:schema-version 1 :component %s :root (:t \"jetpacs.action\" :label \"Run\" :on_tap (:action \"x.y\")))"
       name))
     :type 'jetpacs-component-authoring-schema-error)
    (should-not (intern-soft name)))
  (should-error
   (jetpacs-component-authoring-read-document
    "#.(progn (error \"executed\") nil)")
   :type 'jetpacs-authoring-read-error)
  (should-error
   (jetpacs-component-authoring-read-document
    "(:schema-version 1) (:schema-version 1)")
   :type 'jetpacs-authoring-read-error)
  (should-error
   (jetpacs-component-authoring-read-document
    "(:schema-version 1 :schema-version 1 :component \"action\" :root nil)")
   :type 'jetpacs-component-authoring-schema-error))

(ert-deftest jetpacs-component-authoring-rejects-open-or-ambiguous-shapes ()
  "Unknown members, nil members, type changes, and free trees are rejected."
  (let ((action (jetpacs-component-authoring-default-document "action"))
        (panel (jetpacs-component-authoring-default-document "panel")))
    (should-error
     (jetpacs-component-authoring-set-at-path action '(:surprise) t)
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path action '(:enabled) nil)
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path action '(:pad) nil)
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path action '(:t) "button")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      panel '(:children 0 :t) "editor")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-delete-at-path panel '(:children 0))
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      action '(:id) "jpcatalog-authoring-label")
     :type 'jetpacs-component-authoring-schema-error)))

(ert-deftest jetpacs-component-authoring-preserves-false-versus-absence ()
  "Tri-state component booleans retain explicit false independently of nil."
  (let* ((base (jetpacs-component-authoring-default-document "action"))
         (disabled (jetpacs-component-authoring-set-at-path
                    base '(:enabled) :json-false))
         (restored (jetpacs-component-authoring-delete-at-path
                    disabled '(:enabled))))
    (should-not (plist-member (plist-get base :root) :enabled))
    (should (eq (plist-get (plist-get disabled :root) :enabled) :json-false))
    (should (string-match-p
             "\"enabled\":false"
             (jetpacs-node->canonical-json
              (jetpacs-component-authoring-compile-document disabled))))
    (should-not (plist-member (plist-get restored :root) :enabled))
    (should (equal base restored)))
  (let* ((choice (jetpacs-component-authoring-default-document "choice"))
         (unchecked (jetpacs-component-authoring-set-at-path
                     choice '(:checked) :json-false)))
    (should (eq (plist-get (plist-get unchecked :root) :checked)
                :json-false)))
  ;; Text's public builder intentionally models selectability as presence.
  (should-error
   (jetpacs-component-authoring-set-at-path
    (jetpacs-component-authoring-default-document "panel")
    '(:children 0 :selectable) :json-false)
   :type 'jetpacs-component-authoring-schema-error))

(ert-deftest jetpacs-component-authoring-tabs-are-closed-and-controlled ()
  "Tabs options and selection obey the closed string-valued contract."
  (let* ((document (jetpacs-component-authoring-default-document "tabs"))
         (root (plist-get document :root))
         (options (plist-get root :options)))
    (should (equal (plist-get root :t) "jetpacs.tabs"))
    (should (equal (plist-get root :value) "preview"))
    (should (eq (plist-get root :pinned) :json-false))
    (should (equal (plist-get root :variant) "adaptive"))
    (should-not (plist-member root :scrollable))
    (should (eq
             (plist-get
              (jetpacs-component-authoring-compile-document document)
              :scrollable)
             t))
    (should (= (length options) 3))
    (let ((pinned
           (jetpacs-component-authoring-set-at-path document '(:pinned) t)))
      (should (eq (plist-get (plist-get pinned :root) :pinned) t)))
    (should-error
     (jetpacs-component-authoring-set-at-path document '(:pinned) "true")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path document '(:value) "missing")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      document '(:options 1 :value) "preview")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path document '(:options) [])
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      document '(:options 0 :extra) "open")
     :type 'jetpacs-component-authoring-schema-error)))

(ert-deftest jetpacs-component-authoring-tabs-presentation-is-atomic ()
  "Tabs stores one authored presentation and derives legacy renderer state."
  (let* ((document (jetpacs-component-authoring-default-document "tabs"))
         (fixed (jetpacs-component-authoring-set-tabs-presentation
                 document "fixed"))
         (fixed-root (plist-get fixed :root))
         (fixed-ir (jetpacs-component-authoring-compile-document fixed))
         (legacy-scrollable
          (jetpacs-component-authoring-set-tabs-presentation
           fixed "legacy-scrollable"))
         (legacy-root (plist-get legacy-scrollable :root))
         (legacy-fixed
          (jetpacs-component-authoring-set-tabs-presentation
           legacy-scrollable "legacy-fixed")))
    (should (equal (plist-get fixed-root :variant) "fixed"))
    (should-not (plist-member fixed-root :scrollable))
    (should (eq (plist-get fixed-ir :scrollable) :json-false))
    (should (equal (plist-get fixed-ir :variant) "fixed"))
    (should-not (plist-member legacy-root :variant))
    (should (eq (plist-get legacy-root :scrollable) t))
    (should-not (plist-member (plist-get legacy-fixed :root) :variant))
    (should-not (plist-member (plist-get legacy-fixed :root) :scrollable))
    (should-error
     (jetpacs-component-authoring-set-tabs-presentation document "unknown")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      document '(:scrollable) :json-false)
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      document '(:variant) "unknown")
     :type 'jetpacs-component-authoring-schema-error)))

(ert-deftest jetpacs-component-authoring-sections-are-closed-and-controlled ()
  "Section options require levels 1..6 and one selected stable value."
  (let* ((document
          (jetpacs-component-authoring-default-document "section-navigator"))
         (root (plist-get document :root))
         (options (plist-get root :options))
         (gapped
          (jetpacs-component-authoring-set-at-path
           document '(:options 1 :level) 3)))
    (should (equal (plist-get root :t) "jetpacs.section_navigator"))
    (should (equal (plist-get root :value) "overview"))
    (should (eq (plist-get root :pinned) :json-false))
    (should (= (length options) 4))
    (should (= (plist-get (aref (plist-get (plist-get gapped :root) :options)
                                1)
                          :level)
               3))
    (dolist (level '(0 7 1.5))
      (should-error
       (jetpacs-component-authoring-set-at-path
        document '(:options 0 :level) level)
       :type 'jetpacs-component-authoring-schema-error))
    (should-error
     (jetpacs-component-authoring-set-at-path
      document '(:options 1 :value) "overview")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path document '(:value) "missing")
     :type 'jetpacs-component-authoring-schema-error)
    (should-error
     (jetpacs-component-authoring-set-at-path
      document '(:options 0 :extra) t)
     :type 'jetpacs-component-authoring-schema-error)))

(ert-deftest jetpacs-component-authoring-section-option-edits-are-atomic ()
  "Section value rename and deletion preserve the controlled selection."
  (let* ((original
          (jetpacs-component-authoring-default-document "section-navigator"))
         (renamed
          (jetpacs-component-authoring-set-section-option-value
           original 0 "introduction"))
         (deleted
          (jetpacs-component-authoring-delete-section-option original 0)))
    (should (equal (plist-get (plist-get renamed :root) :value)
                   "introduction"))
    (should (equal
             (plist-get
              (aref (plist-get (plist-get renamed :root) :options) 0)
              :value)
             "introduction"))
    (should (equal (plist-get (plist-get deleted :root) :value) "behavior"))
    (should (= (length (plist-get (plist-get deleted :root) :options)) 3))))

(ert-deftest jetpacs-component-authoring-tabs-option-edits-are-atomic ()
  "Renaming or deleting the selected option preserves one valid value."
  (let* ((original (jetpacs-component-authoring-default-document "tabs"))
         (renamed
          (jetpacs-component-authoring-set-tab-option-value
           original 0 "rendered"))
         (renamed-root (plist-get renamed :root))
         (deleted
          (jetpacs-component-authoring-delete-tab-option original 0))
         (deleted-root (plist-get deleted :root)))
    (should (equal (plist-get (plist-get original :root) :value) "preview"))
    (should (equal (plist-get renamed-root :value) "rendered"))
    (should (equal (plist-get
                    (aref (plist-get renamed-root :options) 0) :value)
                   "rendered"))
    (should (equal (plist-get deleted-root :value) "visual"))
    (should (= (length (plist-get deleted-root :options)) 2))
    (let ((single
           (jetpacs-component-authoring-set-at-path
            original '(:options)
            [(:label "Preview" :value "preview")])))
      (should-error
       (jetpacs-component-authoring-delete-tab-option single 0)
       :type 'jetpacs-component-authoring-schema-error))))

(ert-deftest jetpacs-component-authoring-compiles-universal-semantics ()
  "Universal attributes and structured Semantics remain canonical EBP."
  (let* ((document
          (jetpacs-component-authoring-test--action-document
           '(:action "example.run"
             :args (:z [1 :json-false nil] :a (:nested t))
             :when_offline "queue" :dedupe "example-run" :ttl_s 60
             :confirm (:text "Run it?" :title "Confirm"
                       :icon "play_arrow" :confirm_label "Run"
                       :dismiss_label "Cancel")
             :capture_fields ["field-one"] :open_surface "app:jpcatalog")))
         (root (plist-get document :root)))
    (setq root
          (append
           root
           '(:enabled :json-false :key "example-key" :id "example-action"
             :scroll_here :json-false :padding 4 :pad (:horizontal 8 :top 2)
             :width 240 :height 48 :min_width 100 :max_width 400
             :min_height 40 :max_height 80 :fill_fraction 0.5
             :aspect_ratio 2.0 :weight 1 :bg "surface"
             :corner (:top_start 8 :top_end 8) :border (:width 1 :color "outline")
             :alpha 0.9 :clip t :align_self "stretch"
             :semantics
             (:name "Run example" :description "Runs the example"
              :state_description "Ready" :error "Example error"
              :pane_title "Example pane" :heading_level 2
              :live_region "polite"
              :collection (:row_count 1 :column_count 1)
              :collection_item (:row_index 0 :row_span 1
                                :column_index 0 :column_span 1)
              :traversal_group :json-false :traversal_index 1.5
              :actions [(:label "Alternate"
                         :on_action (:builtin "clipboard.copy"
                                     :text "copied"))]))))
    (let* ((normalized
            (jetpacs-component-authoring-normalize-document
             (plist-put document :root root)))
           (compiled (jetpacs-component-authoring-compile-document normalized))
           (descriptor (plist-get compiled :on_tap))
           (semantics (plist-get compiled :semantics)))
      (should (equal compiled (plist-get normalized :root)))
      (should (eq (plist-get compiled :enabled) :json-false))
      (should (eq (plist-get semantics :traversal_group) :json-false))
      (should (vectorp (plist-get semantics :actions)))
      (should (equal (plist-get descriptor :when_offline) "queue"))
      (should (equal (plist-get descriptor :ttl_s) 60))
      (should (equal (plist-get (plist-get descriptor :args) :z)
                     [1 :json-false nil]))
      (should (string-match-p
               (regexp-quote "\"nested\":true")
               (jetpacs-node->wire-json compiled))))))

(ert-deftest jetpacs-component-authoring-actions-use-closed-builders ()
  "Remote policy constraints and the complete builtin set stay closed."
  (should-error
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--action-document
     '(:action "example.run" :ttl_s 30)))
   :type 'jetpacs-component-authoring-schema-error)
  (should-error
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--action-document
     '(:action "example.run" :args nil)))
   :type 'jetpacs-component-authoring-schema-error)
  (should-error
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--action-document
     '(:action "example.run" :capture_fields [])))
   :type 'jetpacs-component-authoring-schema-error)
  (should-error
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--action-document
     '(:builtin "not.real")))
   :type 'jetpacs-component-authoring-schema-error)
  (dolist (descriptor
           '((:builtin "view.switch" :view "preview")
             (:builtin "variant.switch" :id "host" :value "one")
             (:builtin "surface.open" :surface "app:jpcatalog")
             (:builtin "clipboard.copy" :text "copy")
             (:builtin "share.send" :text "share" :title "Title")
             (:builtin "companion.settings.open")
             (:builtin "trigger.fire" :id "manual-trigger")
             (:builtin "dialog.submit" :value :json-false
                       :capture_fields ["field-one"])
             (:builtin "dialog.dismiss")))
    (let* ((document
            (jetpacs-component-authoring-normalize-document
             (jetpacs-component-authoring-test--action-document descriptor)))
           (actual (plist-get (plist-get document :root) :on_tap)))
      (should (equal (plist-get actual :builtin)
                     (plist-get descriptor :builtin))))))

(ert-deftest jetpacs-component-authoring-text-input-enforces-real-contract ()
  "Text Input options compile through the public constraint envelope."
  (let* ((root
          '(:t "text_input" :id "phone-field" :value "1234567890"
            :hint "Digits" :label "Phone" :on_change (:action "example.change")
            :on_submit (:action "example.submit") :single_line t
            :min_lines 1 :max_lines 1 :monospace :json-false
            :keyboard "phone" :autofocus :json-false
            :clear_on_submit t :variant "filled" :is_error :json-false
            :supporting_text "Ten digits" :prefix "+1 " :suffix " ext"
            :leading_icon "phone" :trailing_icon "clear" :max_length 10
            :selection [2 4] :hide_keyboard_on_submit t :content_padding 8
            :mask "(###) ###-####" :filter "digits" :enabled :json-false))
         (document
          (jetpacs-component-authoring-normalize-document
           (jetpacs-component-authoring-test--document "text-field" root)))
         (compiled (jetpacs-component-authoring-compile-document document)))
    (should (equal compiled (plist-get document :root)))
    (should (equal (plist-get compiled :selection) [2 4]))
    (should (eq (plist-get compiled :monospace) :json-false))
    (should (eq (plist-get compiled :enabled) :json-false)))
  (should
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--document
     "text-field"
     '(:t "text_input" :id "secret" :password t :single_line t
       :on_submit (:action "example.secret" :capture_fields ["secret"])))))
  (should-error
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--document
     "text-field"
     '(:t "text_input" :id "secret" :password t :single_line t
       :on_submit (:action "example.secret"))))
   :type 'jetpacs-component-authoring-schema-error))

(ert-deftest jetpacs-component-authoring-editor-toolbars-are-structured-data ()
  "Registered and inline Editor toolbars normalize without executable Lisp."
  (let* ((root
          '(:t "editor" :id "editor-one" :document "doc:editor-one"
            :value "line" :on_save (:action "example.save")
            :on_enter (:action "example.enter") :single_line :json-false
            :min_lines 3 :max_lines 8 :read_only :json-false :syntax "elisp"
            :line_numbers t :complete t :chromeless :json-false
            :publish_state :json-false :autofocus t :enabled :json-false
            :toolbar
            [(:label "Snippet" :snippet "${input:Text}"
              :placement "cursor" :long_press (:line "promote"))
             (:icon "play_arrow" :on_tap (:action "example.run"))
             (:label "Insert" :menu
              [(:label "Date" :snippet "${date}")
               (:label "Dismiss" :on_tap (:builtin "dialog.dismiss"))])
             (:label "Indent" :command "indent-region")
             (:label "Move" :line "move-down")]))
         (document
          (jetpacs-component-authoring-normalize-document
           (jetpacs-component-authoring-test--document "editor" root)))
         (toolbar (plist-get (plist-get document :root) :toolbar)))
    (should (vectorp toolbar))
    (should (= (length toolbar) 5))
    (should (vectorp (plist-get (aref toolbar 2) :menu)))
    (should (equal (jetpacs-component-authoring-compile-document document)
                   (plist-get document :root))))
  (let ((registered
         (jetpacs-component-authoring-normalize-document
          (jetpacs-component-authoring-test--document
           "editor" '(:t "editor" :id "local" :toolbar "editing")))))
    (should (equal (plist-get (plist-get registered :root) :toolbar)
                   "editing")))
  (should-error
   (jetpacs-component-authoring-normalize-document
    (jetpacs-component-authoring-test--document
     "editor"
     '(:t "editor" :id "local"
       :toolbar [(:label "Command" :command "indent-region")])))
   :type 'jetpacs-component-authoring-schema-error))

(ert-deftest jetpacs-component-authoring-schema-enumerates-all-fields ()
  "Authoring enumeration stays in set-equality with installed node schemas."
  (dolist (type '("jetpacs.action" "jetpacs.choice" "jetpacs.tabs"
                  "jetpacs.section_navigator" "jetpacs.panel" "text"
                  "text_input" "editor"))
    (let* ((authoring (jetpacs-component-authoring-field-schema type))
           (generated (jetpacs-node-schema-row type)))
      (should authoring)
      (should
       (equal
        (jetpacs-component-authoring-test--sorted-names
         (plist-get authoring :required))
        (sort (mapcar (lambda (name) (concat ":" name)) (nth 1 generated))
              #'string<)))
      (should
       (equal
        (jetpacs-component-authoring-test--sorted-names
         (plist-get authoring :optional))
        (sort (mapcar (lambda (name) (concat ":" name)) (nth 2 generated))
              #'string<)))
      (should (equal (plist-get authoring :universal)
                     jetpacs-universal-attributes))
      (should (= (length (plist-get authoring :fields))
                 (length (delete-dups
                          (copy-sequence (plist-get authoring :fields))))))))
  (let ((hook (jetpacs-component-authoring-field-schema
               "jetpacs.action" :on_tap))
        (semantics (jetpacs-component-authoring-field-schema
                    "jetpacs.action" :semantics))
        (pinned (jetpacs-component-authoring-field-schema
                 "jetpacs.tabs" :pinned))
        (presentation (jetpacs-component-authoring-field-schema
                       "jetpacs.tabs" :variant))
        (sections (jetpacs-component-authoring-field-schema
                   "jetpacs.section_navigator" :options)))
    (should (eq (plist-get hook :kind) 'action-descriptor))
    (should (eq (plist-get semantics :kind) 'semantics-object))
    (should (eq (plist-get pinned :kind) 'tabs-pinned))
    (should (eq (plist-get presentation :kind) 'tabs-presentation))
    (should (eq (plist-get sections :kind) 'section-options))
    (should (eq (plist-get semantics :universal) t)))
  (let* ((panel (jetpacs-component-authoring-component-schema "panel"))
         (children (plist-get panel :fixed-children)))
    (should (equal (plist-get panel :root-path) nil))
    (should (equal (plist-get (aref children 0) :path) '(:children 0)))
    (should (equal (plist-get (aref children 1) :type) "jetpacs.action"))))

(ert-deftest jetpacs-component-authoring-path-edits-are-immutable ()
  "Root-relative set/delete updates are whole-document validated and pure."
  (let* ((original (jetpacs-component-authoring-default-document "panel"))
         (changed (jetpacs-component-authoring-set-at-path
                   original '(:children 0 :text) "Changed")))
    (should (equal
             (plist-get (aref (plist-get (plist-get original :root) :children) 0)
                        :text)
             "Text and action remain independent."))
    (should (equal
             (plist-get (aref (plist-get (plist-get changed :root) :children) 0)
                        :text)
             "Changed"))
    (should-not (equal original changed)))
  (let* ((original (jetpacs-component-authoring-default-document "action"))
         (with-id (jetpacs-component-authoring-set-at-path
                   original '(:id) "specimen-action"))
         (without-id (jetpacs-component-authoring-delete-at-path
                      with-id '(:id))))
    (should (equal original without-id))
    (should-not (plist-member (plist-get original :root) :id))
    (should (equal
             original
             (jetpacs-component-authoring-set-at-path
              original nil (plist-get original :root))))))

(provide 'jetpacs-component-authoring-test)
;;; jetpacs-component-authoring-test.el ends here
