;;; jetpacs-component-catalog-test.el --- Jetpacs component slice -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-component-catalog)

(defun jetpacs-component-catalog-test--find-node (tree id)
  "Return the first plist node below TREE whose :id equals ID."
  (let (found)
    (cl-labels ((walk (value)
                  (cond
                   ((vectorp value) (mapc #'walk (append value nil)))
                   ((and (listp value) (keywordp (car value)))
                    (when (and (null found) (equal (plist-get value :id) id))
                      (setq found value))
                    (cl-loop for (_key child) on value by #'cddr
                             do (walk child)))
                   ((listp value) (mapc #'walk value)))))
      (walk tree))
    found))

(defun jetpacs-component-catalog-test--nodes (tree &optional type)
  "Return every typed node below TREE, optionally restricted to TYPE."
  (let (nodes)
    (cl-labels ((walk (value)
                  (cond
                   ((vectorp value) (mapc #'walk (append value nil)))
                   ((and (listp value) (keywordp (car value)))
                    (when (and (stringp (plist-get value :t))
                               (or (null type)
                                   (equal (plist-get value :t) type)))
                      (push value nodes))
                    (cl-loop for (_key child) on value by #'cddr
                             do (walk child)))
                   ((listp value) (mapc #'walk value)))))
      (walk tree))
    (nreverse nodes)))

(defun jetpacs-component-catalog-test--direct-lazy-child-p (tree id)
  "Whether TREE has node ID as a direct `lazy_column' child."
  (cl-some
   (lambda (column)
     (cl-some (lambda (child) (equal (plist-get child :id) id))
              (append (plist-get column :children) nil)))
   (jetpacs-component-catalog-test--nodes tree "lazy_column")))

(defun jetpacs-component-catalog-test--direct-lazy-key-p (tree key)
  "Whether TREE has node KEY as a direct `lazy_column' child."
  (cl-some
   (lambda (column)
     (cl-some (lambda (child) (equal (plist-get child :key) key))
              (append (plist-get column :children) nil)))
   (jetpacs-component-catalog-test--nodes tree "lazy_column")))

(defun jetpacs-component-catalog-test--component (id)
  "Return the registered catalog component whose identifier is ID."
  (cl-find id jetpacs-component-catalog--components
           :key (lambda (component) (plist-get component :id))
           :test #'equal))

(defun jetpacs-component-catalog-test--presentation-button (tree mode)
  "Return TREE's presentation icon button targeting MODE."
  (cl-find-if
   (lambda (node)
     (let ((descriptor (plist-get node :on_tap)))
       (and (equal (plist-get descriptor :action)
                   "jpcatalog.presentation")
            (equal (plist-get (plist-get descriptor :args) :mode) mode))))
   (jetpacs-component-catalog-test--nodes tree "icon_button")))

(defun jetpacs-component-catalog-test--remote-actions (tree)
  "Return every remote action name structurally reachable below TREE."
  (let (actions)
    (dolist (node (jetpacs-component-catalog-test--nodes tree))
      (dolist (hook '(:on_tap :on_change :on_submit :on_save :on_enter))
        (when-let* ((descriptor (plist-get node hook))
                    (action (plist-get descriptor :action)))
          (push action actions))))
    (nreverse actions)))

(defun jetpacs-component-catalog-test--copy-texts (tree)
  "Return every clipboard-copy payload authored below TREE."
  (delq nil
        (mapcar
         (lambda (node)
           (let ((descriptor (plist-get node :on_tap)))
             (and (equal (plist-get descriptor :builtin) "clipboard.copy")
                  (plist-get descriptor :text))))
         (jetpacs-component-catalog-test--nodes tree))))

(defun jetpacs-component-catalog-test--edit-descriptors (tree)
  "Return every catalog edit ActionDescriptor structurally present in TREE."
  (let (descriptors)
    (dolist (node (jetpacs-component-catalog-test--nodes tree))
      (dolist (hook '(:on_tap :on_change :on_submit :on_save))
        (when-let* ((descriptor (plist-get node hook))
                    ((equal (plist-get descriptor :action) "jpcatalog.edit")))
          (push descriptor descriptors))))
    (nreverse descriptors)))

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

(ert-deftest jetpacs-components-tabs-builder-is-controlled-string-ir ()
  "Tabs emits closed options and one application-owned selected string."
  (let* ((options
          (list (jetpacs-component-tab "Preview" "preview")
                (jetpacs-component-tab "Source" "source")))
         (node
         (jetpacs-component-tabs
           "projection" options "preview" (jetpacs-action "test.tabs")
           :enabled :json-false :scrollable t :pinned t)))
    (should (equal (plist-get node :t) "jetpacs.tabs"))
    (should (equal (plist-get node :value) "preview"))
    (should (vectorp (plist-get node :options)))
    (should (equal (aref (plist-get node :options) 1)
                   '(:label "Source" :value "source")))
    (should (eq (plist-get node :enabled) :json-false))
    (should (eq (plist-get node :scrollable) t))
    (should (eq (plist-get node :pinned) t))))

(ert-deftest jetpacs-components-tabs-builder-derives-variant-compatibility ()
  "Explicit Tabs variants derive one matching legacy scrollable boolean."
  (let ((options (list (jetpacs-component-tab "A" "a"))))
    (dolist (case '(("fixed" . :json-false)
                    ("scrollable" . t)
                    ("navigator" . t)
                    ("adaptive" . t)))
      (let ((node
             (jetpacs-component-tabs
              "tabs" options "a" (jetpacs-action "test.tabs")
              :variant (car case))))
        (should (equal (plist-get node :variant) (car case)))
        (should (eq (plist-get node :scrollable) (cdr case)))))
    (should
     (eq (plist-get
          (jetpacs-component-tabs
           "tabs" options "a" (jetpacs-action "test.tabs")
           :variant "fixed" :scrollable :json-false)
          :scrollable)
         :json-false))
    (should-error
     (jetpacs-component-tabs
      "tabs" options "a" (jetpacs-action "test.tabs")
      :variant "fixed" :scrollable t))
    (should-error
     (jetpacs-component-tabs
      "tabs" options "a" (jetpacs-action "test.tabs")
      :variant "adaptive" :scrollable :json-false))
    (should-error
     (jetpacs-component-tabs
      "tabs" options "a" (jetpacs-action "test.tabs")
      :variant "unknown"))))

(ert-deftest jetpacs-components-section-navigator-is-controlled-closed-ir ()
  "Section Navigator emits closed hierarchical options and controlled state."
  (let* ((sections
          (list (jetpacs-component-section "Overview" "overview" 1)
                (jetpacs-component-section "Behavior" "behavior" 3)))
         (node
          (jetpacs-component-section-navigator
           "sections" sections "behavior"
           (jetpacs-action "test.section")
           :enabled :json-false :pinned t)))
    (should (equal (car sections)
                   '(:label "Overview" :value "overview" :level 1)))
    (should (equal (plist-get node :t) "jetpacs.section_navigator"))
    (should (vectorp (plist-get node :options)))
    (should (equal (aref (plist-get node :options) 1)
                   '(:label "Behavior" :value "behavior" :level 3)))
    (should (equal (plist-get node :value) "behavior"))
    (should (eq (plist-get node :enabled) :json-false))
    (should (eq (plist-get node :pinned) t))))

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
  (should-error (jetpacs-component-tab "" "preview"))
  (should-error (jetpacs-component-tab "Preview" ""))
  (should-error
   (jetpacs-component-tabs
    "tabs" nil "preview" (jetpacs-action "test.tabs")))
  (should-error
   (jetpacs-component-tabs
    "tabs" (list (jetpacs-component-tab "A" "same")
                 (jetpacs-component-tab "B" "same"))
    "same" (jetpacs-action "test.tabs")))
  (should-error
   (jetpacs-component-tabs
    "tabs" (list (jetpacs-component-tab "A" "a"))
    "missing" (jetpacs-action "test.tabs")))
  (should-error
   (jetpacs-component-tabs
    "tabs" (list (jetpacs-component-tab "A" "a"))
    "a" (jetpacs-action "test.tabs") :pinned "yes"))
  (should-error (jetpacs-component-section "Heading" "heading" 0))
  (should-error (jetpacs-component-section "Heading" "heading" 7))
  (should-error
   (jetpacs-component-section-navigator
    "sections" nil "heading" (jetpacs-action "test.section")))
  (should-error
   (jetpacs-component-section-navigator
    "sections"
    (list (jetpacs-component-section "A" "same" 1)
          (jetpacs-component-section "B" "same" 2))
    "same" (jetpacs-action "test.section")))
  (should-error
   (jetpacs-component-section-navigator
    "sections" (list (jetpacs-component-section "A" "a" 1))
    "missing" (jetpacs-action "test.section")))
  (should-error (jetpacs-component-panel "Panel" (list '(:not-a-node t))))
  (should-error (jetpacs-component-scope (list '(:not-a-node t))))
  (should-error (jetpacs-component-scope
                 (cons (jetpacs-text "Ready") t))))

(ert-deftest jetpacs-components-panel-keeps-real-child-vector ()
  "Panel returns the actual plist/vector IR without a parallel catalog AST."
  (let* ((child (jetpacs-text "Ready"))
         (panel (jetpacs-component-panel "STATUS" (list child))))
    (should (equal (plist-get panel :t) "jetpacs.panel"))
    (should (vectorp (plist-get panel :children)))
    (should (equal (aref (plist-get panel :children) 0) child))))

(ert-deftest jetpacs-components-scope-keeps-canonical-child-vector ()
  "Scope is actual IR and adds no parallel component or semantics model."
  (let* ((child (jetpacs-text "Ready"))
         (scope (jetpacs-component-scope (list child))))
    (should (equal (plist-get scope :t) "jetpacs.scope"))
    (should (vectorp (plist-get scope :children)))
    (should (equal (aref (plist-get scope :children) 0) child))
    (should
     (equal
      (jetpacs-node->canonical-json scope)
      "{\"children\":[{\"t\":\"text\",\"text\":\"Ready\"}],\"t\":\"jetpacs.scope\"}"))))

(ert-deftest jetpacs-components-generated-extension-registers-all-targets ()
  "Generated schema and target maps are the authoring authority."
  (should (equal jetpacs-components-extension "jetpacs.components"))
  (should (equal (cdr (assoc jetpacs-components-extension
                             jetpacs-renderer-extensions))
                 '("jetpacs.action" "jetpacs.choice" "jetpacs.list_item"
                   "jetpacs.panel" "jetpacs.scope"
                   "jetpacs.section_navigator" "jetpacs.tabs")))
  (should (equal (jetpacs-renderer-target-node-types 'dialog) nil))
  (dolist (type '("jetpacs.action" "jetpacs.choice" "jetpacs.panel"
                  "jetpacs.scope" "jetpacs.section_navigator"
                  "jetpacs.tabs"))
    (should (member type (jetpacs-renderer-target-node-types 'app))))
  (let ((scope (jetpacs-component-scope (list (jetpacs-text "Ready")))))
    (should (jetpacs-check-profile scope 'app))
    (should-error (jetpacs-check-profile scope 'dialog))))

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

(ert-deftest jetpacs-component-catalog-registry-names-exactly-seven-builders ()
  "The concise index and literate viewer share one complete registry."
  (let ((expected
         '(("action" "Action" jetpacs-component-catalog--action-screen)
           ("choice" "Choice" jetpacs-component-catalog--choice-screen)
           ("tabs" "Tabs" jetpacs-component-catalog--tabs-screen)
           ("section-navigator" "Section Navigator"
            jetpacs-component-catalog--section-navigator-screen)
           ("panel" "Panel" jetpacs-component-catalog--panel-screen)
           ("text-field" "Text Field"
            jetpacs-component-catalog--text-field-screen)
           ("editor" "Editor" jetpacs-component-catalog--editor-screen))))
    (should (= (length jetpacs-component-catalog--components) 7))
    (should
     (equal
      (mapcar
       (lambda (component)
         (list (plist-get component :id)
               (plist-get component :name)
               (plist-get component :builder)))
       jetpacs-component-catalog--components)
      expected))
    (dolist (component jetpacs-component-catalog--components)
      (dolist (member '(:id :name :purpose :category))
        (should (stringp (plist-get component member)))
        (should-not (string-empty-p (plist-get component member))))
      (should (symbolp (plist-get component :builder)))
      (should (fboundp (plist-get component :builder))))))

(ert-deftest jetpacs-component-catalog-home-keeps-the-seven-entry-index ()
  "Root Preview remains an ordered index rather than a component itself."
  (let* ((screen (jetpacs-component-catalog--home-screen nil))
         (actions
          (cl-remove-if-not
           (lambda (node)
             (equal (plist-get (plist-get node :on_tap) :action)
                    "jpcatalog.open"))
           (jetpacs-component-catalog-test--nodes
            screen "jetpacs.action"))))
    (should
     (equal
      (mapcar
       (lambda (node)
         (plist-get (plist-get (plist-get node :on_tap) :args) :component))
       actions)
      '("action" "choice" "tabs" "section-navigator" "panel"
        "text-field" "editor")))
    (should (cl-every
             (lambda (node) (string-prefix-p "Open " (plist-get node :label)))
             actions))))

(ert-deftest jetpacs-component-catalog-open-handler-requires-live-home ()
  "A delayed Home card cannot navigate after its root snapshot is stale."
  (let (continued pushed)
    (should (eq (jetpacs-component-catalog--on-open
                 '(:component 42) '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-open
                 '(:component "missing") '(:surface "app:jpcatalog"))
                'stale))
    (should (eq (jetpacs-component-catalog--on-open
                 '(:component "action") '(:surface "app:foreign"))
                'stale))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) t))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("home")))
              ((symbol-function 'jetpacs-flow-continue)
               (lambda (_thunk) (setq continued t))))
      (should (eq (jetpacs-component-catalog--on-open
                   '(:component "action")
                   '(:surface "app:jpcatalog" :revision_seen 1))
                  'stale)))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("component-choice" "home")))
              ((symbol-function 'jetpacs-flow-continue)
               (lambda (_thunk) (setq continued t))))
      (should (eq (jetpacs-component-catalog--on-open
                   '(:component "action") '(:surface "app:jpcatalog"))
                  'stale)))
    (should-not continued)
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("home")))
              ((symbol-function 'jetpacs-flow-continue)
               (lambda (thunk) (setq continued t) (funcall thunk)))
              ((symbol-function 'jetpacs-chrome-push-screen)
               (lambda (surface id builder)
                 (setq pushed (list surface id builder)))))
      (should (eq (jetpacs-component-catalog--on-open
                   '(:component "action") '(:surface "app:jpcatalog"))
                  'accepted)))
    (should continued)
    (should (equal (seq-take pushed 2)
                   '("app:jpcatalog" "component-action")))
    (should (functionp (nth 2 pushed)))))

(ert-deftest jetpacs-component-catalog-root-projections-are-valid-read-only-tabs ()
  "Every root projection is valid, reachable, and cannot edit a specimen."
  (let ((jetpacs-component-catalog--root-presentation-mode 'preview)
        (jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal))
        (jetpacs-component-catalog--drafts
         (make-hash-table :test #'equal))
        (expectations
         '((preview "Jetpacs Components · Preview" "jpcatalog-root-preview")
           (visual "Jetpacs Components · Visual" "jpcatalog-root-visual")
           (lisp "Jetpacs Components · Lisp" "jpcatalog-root-lisp")
           (source "Jetpacs Components · Source" "jpcatalog-root-source"))))
    (puthash "action" 'source
             jetpacs-component-catalog--presentation-modes)
    (puthash "choice" '(:sentinel "unchanged")
             jetpacs-component-catalog--drafts)
    (dolist (mode '(preview visual lisp source))
      (setq jetpacs-component-catalog--root-presentation-mode mode)
      (let* ((screen (jetpacs-component-catalog--root-screen nil))
             (expectation (assq mode expectations))
             (nodes (jetpacs-component-catalog-test--nodes screen))
             (tabs (jetpacs-component-catalog-test--find-node
                    screen "jpcatalog-root-projection-tabs"))
             (descriptor (and tabs (plist-get tabs :on_change))))
        (should (jetpacs-root-node-p screen))
        (should (jetpacs-check-profile screen 'app))
        (should (stringp (jetpacs-node->canonical-json screen)))
        ;; Prove the dispatcher selected the corresponding root builder,
        ;; rather than merely proving that every tab descriptor is present.
        (should
         (cl-find-if
          (lambda (node)
            (equal (plist-get node :text) (nth 1 expectation)))
          nodes))
        (should
         (cl-find-if
          (lambda (node)
            (equal (plist-get node :key) (nth 2 expectation)))
          nodes))
        (should (equal (plist-get tabs :t) "jetpacs.tabs"))
        (should (equal (plist-get tabs :value) (symbol-name mode)))
        (should (eq (plist-get tabs :pinned) t))
        (should (equal (plist-get tabs :variant) "fixed"))
        (should (eq (plist-get tabs :scrollable) :json-false))
        (should
         (jetpacs-component-catalog-test--direct-lazy-child-p
          screen "jpcatalog-root-projection-tabs"))
        (should (equal (plist-get descriptor :action)
                       "jpcatalog.root.presentation"))
        (should-not (plist-member descriptor :args))
        (let ((ids (jetpacs-collect-node-ids screen nil)))
          (should (= (length ids)
                     (length (delete-dups (copy-sequence ids))))))
        (dolist (destination '("preview" "visual" "lisp" "source"))
          (should
           (cl-find destination (append (plist-get tabs :options) nil)
                    :key (lambda (option) (plist-get option :value))
                    :test #'equal)))
        (dolist (mutation '("jpcatalog.edit" "jpcatalog.lisp.apply"
                            "jpcatalog.reset" "jpcatalog.arm"))
          (should-not
           (member mutation
                   (jetpacs-component-catalog-test--remote-actions screen))))))
    ;; Root inspection is a separate read-only state domain.  Merely building
    ;; any root projection cannot seed or rewrite a per-component draft.
    (should (eq (gethash "action"
                         jetpacs-component-catalog--presentation-modes)
                'source))
    (should (= (hash-table-count
                jetpacs-component-catalog--presentation-modes)
               1))
    (should (equal (gethash "choice" jetpacs-component-catalog--drafts)
                   '(:sentinel "unchanged")))
    (should (= (hash-table-count jetpacs-component-catalog--drafts) 1))))

(ert-deftest jetpacs-component-catalog-root-manifest-is-deterministic-inert-data ()
  "The Lisp projection reproducibly describes the live drill-down program."
  (let* ((first (jetpacs-component-catalog--root-program-manifest))
         (second (jetpacs-component-catalog--root-program-manifest))
         (printed (prin1-to-string first))
         (round-trip (car (read-from-string printed)))
         (cursor 0))
    (should (listp first))
    (should (equal first second))
    (should (equal first round-trip))
    (dolist (id '("action" "choice" "tabs" "section-navigator" "panel"
                  "text-field" "editor"))
      (let ((position (string-search id printed cursor)))
        (should position)
        (setq cursor (1+ position))))
    (dolist (fact '("jpcatalog.open"
                    "jpcatalog.presentation"
                    "jpcatalog.root.presentation"
                    "jetpacs-component-catalog--home-screen"
                    "jetpacs-component-catalog--open-action"
                    "jetpacs-component-catalog--on-open"
                    "jetpacs-component-catalog--detail-screen"
                    "jetpacs-component-catalog--on-presentation"
                    "jetpacs-component-catalog--on-root-presentation"
                    "jetpacs-component-catalog-register"))
      (should (string-search fact printed)))))

(ert-deftest jetpacs-component-catalog-root-source-composes-under-detail ()
  "A hidden Source root and Visual detail remain within fixed wire floors."
  (let ((jetpacs-component-catalog--root-presentation-mode 'source)
        (jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal))
        (jetpacs-component-catalog--drafts
         (make-hash-table :test #'equal)))
    (puthash "panel" 'visual jetpacs-component-catalog--presentation-modes)
    (let* ((spec
            (jetpacs-multi-view
             (list
              (cons "home" (jetpacs-component-catalog--root-screen nil))
              (cons "component-panel"
                    (jetpacs-component-catalog--detail-screen "panel" nil)))
             "component-panel"))
           (analysis (jetpacs-shell--analyze-spec spec))
           (counts (plist-get analysis :counts))
           (bytes (string-bytes (jetpacs-node->canonical-json spec))))
      (should-not (plist-get analysis :duplicate-id))
      (should-not (plist-get analysis :duplicate-key))
      (should-not (plist-get analysis :identity-error))
      (should (<= (plist-get counts :nodes) 10000))
      (should (<= (plist-get counts :depth) 20))
      (should (<= (plist-get counts :max-children) 10000))
      (should (<= bytes 4194304)))))

(ert-deftest jetpacs-component-catalog-root-source-bundles-exact-drill-down ()
  "Root Source preserves the authored forms that take home to a component."
  (let* ((source (jetpacs-component-catalog--root-source-bundle))
         (text (plist-get source :text)))
    (should (eq (plist-get source :origin) 'authored))
    (should-not (plist-get source :truncated))
    (should (stringp (plist-get source :caption)))
    (should-not (string-empty-p (plist-get source :caption)))
    (should (stringp text))
    (should (< (length text)
               (+ jetpacs-component-catalog--projection-max-chars 256)))
    (should (string-search
             "(defconst jetpacs-component-catalog--components" text))
    (dolist (function
             '(jetpacs-component-catalog--root-screen
               jetpacs-component-catalog--home-screen
               jetpacs-component-catalog--root-visual-screen
               jetpacs-component-catalog--root-lisp-screen
               jetpacs-component-catalog--root-source-screen
               jetpacs-component-catalog--open-action
               jetpacs-component-catalog--on-open
               jetpacs-component-catalog--detail-screen
               jetpacs-component-catalog--projection-value
               jetpacs-component-catalog--on-presentation
               jetpacs-component-catalog--on-root-presentation
               jetpacs-component-catalog-register))
      (let ((authored (jetpacs-elisp-defun-text function)))
        (should authored)
        (should (string-search authored text))))))

(ert-deftest jetpacs-component-catalog-root-source-omits-whole-forms ()
  "A growing Source bundle never truncates through an authored form."
  (let ((jetpacs-component-catalog--projection-max-chars 72)
        (jetpacs-component-catalog--root-source-forms
         '((function root-one "ONE") (function root-two "TWO"))))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--root-source-result)
               (lambda (spec)
                 (list :text (format "(defun %s () t)" (nth 1 spec))
                       :caption "fixture.el — as authored"
                       :origin 'authored :truncated nil))))
      (let* ((source (jetpacs-component-catalog--root-source-bundle))
             (text (plist-get source :text)))
        (should (eq (plist-get source :origin) 'mixed))
        (should (eq (plist-get source :truncated) t))
        (should (<= (length text)
                    jetpacs-component-catalog--projection-max-chars))
        (should (string-search "(defun root-one () t)" text))
        (should-not (string-search "(defun root-two" text))
        (should-not (string-suffix-p "(defun" text))))))

(ert-deftest jetpacs-component-catalog-root-presentation-handler-is-isolated ()
  "Root mode switching validates context and never changes component state."
  (let ((jetpacs-component-catalog--root-presentation-mode 'preview)
        (jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal))
        (jetpacs-component-catalog--drafts
         (make-hash-table :test #'equal))
        refreshes
        observed-mode)
    (puthash "action" 'lisp
             jetpacs-component-catalog--presentation-modes)
    (puthash "action" '(:sentinel "draft")
             jetpacs-component-catalog--drafts)
    (should (eq (jetpacs-component-catalog--on-root-presentation
                 '(:mode 42) '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-root-presentation
                 '(:mode "side-by-side") '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-root-presentation
                 '(:value "preview" :mode "source")
                 '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-root-presentation
                 '(:mode "source") '(:surface "app:foreign"))
                'stale))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) t))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("home"))))
      (should (eq (jetpacs-component-catalog--on-root-presentation
                   '(:mode "source")
                   '(:surface "app:jpcatalog" :revision_seen 1))
                  'stale)))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("component-action" "home"))))
      (should (eq (jetpacs-component-catalog--on-root-presentation
                   '(:mode "source") '(:surface "app:jpcatalog"))
                  'stale)))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("home")))
              ((symbol-function 'jetpacs-buffer-defer-view-refresh)
               (lambda (surface)
                 (setq observed-mode
                       jetpacs-component-catalog--root-presentation-mode)
                 (push surface refreshes))))
      (should (eq (jetpacs-component-catalog--on-root-presentation
                   '(:value "source")
                   '(:surface "app:jpcatalog" :revision_seen 7))
                  'accepted)))
    (should (eq observed-mode 'source))
    (should (eq jetpacs-component-catalog--root-presentation-mode 'source))
    (should (equal refreshes '("app:jpcatalog")))
    (should (eq (gethash "action"
                         jetpacs-component-catalog--presentation-modes)
                'lisp))
    (should (equal (gethash "action" jetpacs-component-catalog--drafts)
                   '(:sentinel "draft")))))

(ert-deftest jetpacs-component-catalog-builds-home-and-every-detail ()
  "Every catalog screen is typed, scoped, serializable extension IR."
  (let* ((screens
          (list (jetpacs-component-catalog--home-screen nil)
                (jetpacs-component-catalog--action-screen nil)
                (jetpacs-component-catalog--choice-screen nil)
                (jetpacs-component-catalog--tabs-screen nil)
                (jetpacs-component-catalog--section-navigator-screen nil)
                (jetpacs-component-catalog--panel-screen nil)
                (jetpacs-component-catalog--text-field-screen nil)
                (jetpacs-component-catalog--editor-screen nil)))
         (json (mapconcat #'jetpacs-node->canonical-json screens "\n")))
    (dolist (screen screens)
      (should (jetpacs-root-node-p screen))
      (should (string-match-p
               (regexp-quote "\"t\":\"jetpacs.scope\"")
               (jetpacs-node->canonical-json screen))))
    (dolist (type '("jetpacs.action" "jetpacs.choice" "jetpacs.tabs"
                    "jetpacs.section_navigator" "jetpacs.panel"
                    "jetpacs.scope" "text_input" "editor"))
      (should (string-match-p (regexp-quote type) json)))))

(ert-deftest jetpacs-component-catalog-previews-drop-duplicated-code-panels ()
  "Preview is the live specimen, with no hand-maintained authoring dump."
  (dolist (component jetpacs-component-catalog--components)
    (let* ((screen (funcall (plist-get component :builder) nil))
           (panels (jetpacs-component-catalog-test--nodes
                    screen "jetpacs.panel")))
      (should-not (cl-find "AUTHORING / EBP" panels
                           :key (lambda (node) (plist-get node :label))
                           :test #'equal))
      (should-not (string-match-p
                   (regexp-quote "AUTHORING / EBP")
                   (jetpacs-node->canonical-json screen))))))

(ert-deftest jetpacs-component-catalog-previews-retain-existing-specimens ()
  "The viewer refactor preserves every implemented state and variant fixture."
  (let ((cases
         '((jetpacs-component-catalog--action-screen
            "Run ordinary action" "Disabled action" "\"enabled\":false")
           (jetpacs-component-catalog--choice-screen
            "jpcatalog-choice" "jpcatalog-choice-disabled"
            "Mirror catalog updates")
           (jetpacs-component-catalog--tabs-screen
            "jpcatalog-tabs-demo" "jpcatalog-tabs-disabled"
            "jpcatalog-tabs-scrollable" "jpcatalog-tabs-navigator"
            "jpcatalog-tabs-adaptive" "jpcatalog-tabs-legacy-scrollable"
            "\"variant\":\"fixed\"" "\"variant\":\"navigator\"")
           (jetpacs-component-catalog--section-navigator-screen
            "jpcatalog-section-navigator-demo"
            "\"t\":\"jetpacs.section_navigator\""
            "\"scroll_here\":true" "\"heading_level\":2")
           (jetpacs-component-catalog--panel-screen
            "NESTED ACTION REMAINS INDEPENDENT" "Run nested action")
           (jetpacs-component-catalog--text-field-screen
            "jpcatalog-text-live" "jpcatalog-text-filled"
            "jpcatalog-text-phone" "jpcatalog-text-code"
            "jpcatalog-text-error" "jpcatalog-text-disabled"
            "jpcatalog-password")
           (jetpacs-component-catalog--editor-screen
            "jpcatalog-editor-live" "jpcatalog-editor-enter"
            "jpcatalog-editor-read-only" "jpcatalog-editor-disabled"
            "jpcatalog-editor-chromeless"))))
    (dolist (case cases)
      (let ((json (jetpacs-node->canonical-json (funcall (car case) nil))))
        (dolist (needle (cdr case))
          (should (string-match-p (regexp-quote needle) json)))))))

(ert-deftest jetpacs-component-catalog-all-projection-roots-are-valid ()
  "All four projections remain ordinary app-profile Jetpacs roots."
  (let ((jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal)))
    (dolist (component jetpacs-component-catalog--components)
      (let* ((id (plist-get component :id))
             (preview (jetpacs-component-catalog--detail-screen id nil)))
        (should (jetpacs-root-node-p preview))
        (should (jetpacs-check-profile preview 'app))
        (should (stringp (jetpacs-node->canonical-json preview)))
        (let ((ids (jetpacs-collect-node-ids preview nil)))
          (should (= (length ids)
                     (length (delete-dups (copy-sequence ids))))))
        (dolist (mode '(visual lisp source))
          (puthash id mode jetpacs-component-catalog--presentation-modes)
          (let ((projection
                 (jetpacs-component-catalog--detail-screen id nil)))
            (should (jetpacs-root-node-p projection))
            (should (jetpacs-check-profile projection 'app))
            (should (stringp (jetpacs-node->canonical-json projection)))
            (let ((ids (jetpacs-collect-node-ids projection nil)))
              (should (= (length ids)
                         (length
                          (delete-dups (copy-sequence ids))))))))))))

(ert-deftest jetpacs-component-catalog-projection-actions-are-explicit ()
  "Each top-bar action names its destination, component, and accessible role."
  (dolist (component jetpacs-component-catalog--components)
    (let* ((id (plist-get component :id))
           (preview (funcall (plist-get component :builder) nil))
           (to-source
            (jetpacs-component-catalog-test--presentation-button
             preview "source"))
           (source (jetpacs-component-catalog--elisp-screen component nil))
           (to-preview
            (jetpacs-component-catalog-test--presentation-button
             source "preview")))
      (should to-source)
      (should (equal (plist-get to-source :icon) "code"))
      (should (equal (plist-get to-source :content_description)
                     "Show authored source"))
      (should (equal
               (plist-get
                (plist-get (plist-get to-source :on_tap) :args) :component)
               id))
      (should (member (plist-get (plist-get to-source :on_tap) :when_offline)
                      '(nil "drop")))
      (should-not (plist-member (plist-get to-source :on_tap) :dedupe))
      (should to-preview)
      (should (equal (plist-get to-preview :icon) "preview"))
      (should (equal (plist-get to-preview :content_description)
                     "Show rendered preview"))
      (should (equal
               (plist-get
                (plist-get (plist-get to-preview :on_tap) :args) :component)
               id)))))

(ert-deftest jetpacs-component-catalog-elisp-shows-authored-builder-and-ebp ()
  "The literate page derives both readable projections from its builder."
  (let ((back (jetpacs-view-switch "home"))
        collapsible-ids)
    (dolist (component jetpacs-component-catalog--components)
      (let* ((builder (plist-get component :builder))
             (source (jetpacs-elisp-source-for-function builder))
             (authored (jetpacs-elisp-defun-text builder))
             (wire (jetpacs-component-catalog--preview-json component back))
             (screen (jetpacs-component-catalog--elisp-screen component back))
             (source-node
              (cl-find-if
               (lambda (node)
                 (and (equal (plist-get node :syntax) "elisp")
                      (equal (plist-get node :text) (plist-get source :text))))
               (jetpacs-component-catalog-test--nodes screen "text")))
             (collapsibles
              (jetpacs-component-catalog-test--nodes screen "collapsible"))
             (copy-texts
              (jetpacs-component-catalog-test--copy-texts screen)))
        (should authored)
        (should (eq (plist-get source :origin) 'authored))
        (should (equal (plist-get source :text) authored))
        (should source-node)
        (should (equal (plist-get source-node :style) "mono"))
        (should (eq (plist-get source-node :selectable) t))
        (should (= (length collapsibles) 1))
        (let* ((collapsible (car collapsibles))
               (header (plist-get collapsible :header))
               (json-node
                (cl-find (plist-get wire :text)
                         (jetpacs-component-catalog-test--nodes
                          collapsible "text")
                         :key (lambda (node) (plist-get node :text))
                         :test #'equal)))
          (push (plist-get collapsible :id) collapsible-ids)
          (should (equal (plist-get header :text) "EBP / canonical JSON"))
          (should (eq (plist-get collapsible :collapsed) t))
          (should json-node)
          (should (equal (plist-get json-node :style) "mono"))
          (should (eq (plist-get json-node :selectable) t))
          (should (member (plist-get source :text) copy-texts))
          (should (member (plist-get wire :text) copy-texts)))))
    (should (= (length collapsible-ids)
               (length jetpacs-component-catalog--components)))
    (should (= (length (delete-dups collapsible-ids))
               (length jetpacs-component-catalog--components)))))

(ert-deftest jetpacs-component-catalog-canonical-ebp-is-the-preview-root ()
  "The EBP section serializes the same current Preview, without an envelope."
  (let ((back (jetpacs-view-switch "home")))
    (dolist (component jetpacs-component-catalog--components)
      (let* ((preview (funcall (plist-get component :builder) back))
             (expected
              (decode-coding-string
               (jetpacs-node->canonical-json preview) 'utf-8 t))
             (result
              (jetpacs-component-catalog--preview-json component back)))
        (should (plist-get result :available))
        (should-not (plist-get result :truncated))
        (should (equal (plist-get result :text) expected))
        (should-not (string-match-p "\"multi_view\"" expected))
        (should (string-match-p "current Preview screen"
                                (plist-get result :caption)))
        (should (string-match-p "multi_view" (plist-get result :caption)))))))

(ert-deftest jetpacs-component-catalog-editor-is-local-and-complete-for-phase-4 ()
  "The Editor fallback retains the complete local Phase 4 tier."
  (let* ((screen (jetpacs-component-catalog--editor-screen nil))
         (json (jetpacs-node->canonical-json screen)))
    (dolist (member '("\"publish_state\":true"
                      "\"line_numbers\":true"
                      "\"syntax\":\"elisp\""
                      "\"toolbar\":["
                      "\"on_save\":{"
                      "\"on_enter\":{"
                      "\"read_only\":true"
                      "\"enabled\":false"
                      "\"chromeless\":true"))
      (should (string-match-p (regexp-quote member) json)))
    (should-not (string-match-p "\"document\":" json))
    (should-not (string-match-p "\"complete\":" json))
    (should (eq (gethash '("app:jpcatalog" . "jpcatalog-editor-live")
                         jetpacs--state-handlers)
                #'jetpacs-component-catalog--on-editor-state))))

(ert-deftest jetpacs-component-catalog-editor-adds-bounded-phase-6-tooling-fixture ()
  "The admitted page authors one real sync editor with Phase 6 tooling."
  (let ((jetpacs-component-catalog--sync-buffer nil))
    (unwind-protect
        (cl-letf (((symbol-function
                    'jetpacs-component-catalog--sync-available-p)
                   (lambda (&optional _client) t)))
          (let* ((screen (jetpacs-component-catalog--editor-screen nil))
                 (node (jetpacs-component-catalog-test--find-node
                        screen jetpacs-component-catalog--sync-editor-id)))
            (should node)
            (should (equal (plist-get node :document)
                           jetpacs-component-catalog--sync-document))
            (should (equal (plist-get node :value)
                           jetpacs-component-catalog--sync-seed))
            (should (equal (plist-get (plist-get node :on_save) :action)
                           "jpcatalog.sync-editor-save"))
            (should (eq (plist-get node :line_numbers) t))
            (should (eq (plist-get node :complete) t))
            (should (equal (plist-get node :syntax) "elisp"))
            (should (equal (plist-get (aref (plist-get node :toolbar) 0) :command)
                           "indent-region"))
            (should-not (plist-member node :publish_state))))
      (jetpacs-component-catalog--release-sync-buffer))))

(ert-deftest jetpacs-component-catalog-ready-attaches-real-phase-6-riders ()
  "READY binds the in-memory buffer with local tooling but without Eglot."
  (let ((jetpacs-component-catalog--sync-buffer nil)
        attached)
    (unwind-protect
        (cl-letf (((symbol-function
                    'jetpacs-component-catalog--sync-available-p)
                   (lambda (&optional _client) t))
                  ((symbol-function 'ebp-sync-attach)
                   (lambda (client document editor-id buffer)
                     (setq attached (list client document editor-id buffer))
                     buffer)))
          (jetpacs-component-catalog--on-ready 'client)
          (should (equal (butlast attached)
                         (list 'client
                               jetpacs-component-catalog--sync-document
                               jetpacs-component-catalog--sync-editor-id)))
          (with-current-buffer (car (last attached))
            (should-not ebp-sync-eglot)
            (should ebp-sync-diagnostics)
            (should ebp-sync-fontify)
            (should ebp-sync-eldoc)
            (should (eq major-mode 'emacs-lisp-mode))
            (should (memq #'jetpacs-component-catalog--completion-at-point
                          completion-at-point-functions))
            (should (memq #'jetpacs-component-catalog--eldoc
                          eldoc-documentation-functions))
            (should-not buffer-auto-save-file-name)
            (should-not buffer-file-name)))
      (jetpacs-component-catalog--release-sync-buffer))))

(ert-deftest jetpacs-component-catalog-completion-fixture-has-kind-and-lazy-docs ()
  "The buffer-local CAPF deterministically exercises optional candidate data."
  (let ((jetpacs-component-catalog--sync-buffer nil)
        (jetpacs-component-catalog--completion-doc-buffer nil))
    (unwind-protect
        (with-current-buffer (jetpacs-component-catalog--ensure-sync-buffer)
          (goto-char (point-max))
          (pcase-let ((`(,beg ,end ,table . ,props)
                       (jetpacs-component-catalog--completion-at-point)))
            (should (equal (buffer-substring-no-properties beg end) "jpc"))
            (should (member "jpcatalog-print" table))
            (should (equal
                     (funcall (plist-get props :annotation-function)
                              "jpcatalog-print")
                     "catalog function"))
            (should (eq (funcall (plist-get props :company-kind)
                                 "jpcatalog-print")
                        'function))
            (let ((doc (funcall (plist-get props :company-doc-buffer)
                                "jpcatalog-print")))
              (should (buffer-live-p doc))
              (with-current-buffer doc
                (should (string-match-p "edit.candidate.doc"
                                        (buffer-string)))))))
      (jetpacs-component-catalog--release-sync-buffer))))

(ert-deftest jetpacs-component-catalog-sync-save-is-bounded-and-refreshes-once ()
  "One admitted synchronized save records one bounded non-secret preview."
  (let ((jetpacs-component-catalog--sync-save-count 0)
        (jetpacs-component-catalog--last-sync-preview nil)
        refreshes)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (params) (push params refreshes))))
      (should (eq (jetpacs-component-catalog--on-sync-editor-save
                   (list :value (concat "line\n" (make-string 100 ?x)))
                   '(:surface "app:jpcatalog"))
                  'accepted))
      (should (= jetpacs-component-catalog--sync-save-count 1))
      (should (<= (string-width
                   jetpacs-component-catalog--last-sync-preview) 72))
      (should (string-search "↵"
                             jetpacs-component-catalog--last-sync-preview))
      (should (= (length refreshes) 1))
      (should (eq (jetpacs-component-catalog--on-sync-editor-save
                   '(:value 42) nil)
                  'rejected)))))

(ert-deftest jetpacs-component-catalog-registers-its-ready-attachment ()
  "The synchronization fixture is wired at load, not from a screen builder."
  (should (memq #'jetpacs-component-catalog--on-ready
                jetpacs-ready-functions))
  (should (memq #'jetpacs-component-catalog--before-replay
                jetpacs-before-replay-functions))
  (should (< (cl-position #'jetpacs-component-catalog--before-replay
                          jetpacs-before-replay-functions)
             (cl-position #'jetpacs-shell--before-replay
                          jetpacs-before-replay-functions)))
  (should
   (plist-get
    (alist-get "app:jpcatalog" jetpacs-shell--roots nil nil #'equal)
    :required)))

(ert-deftest jetpacs-component-catalog-stale-reset-ack-clears-debt ()
  "A successful stale push concludes reset debt just like applied."
  (let ((jetpacs-component-catalog--pending-reset-ids
         (make-hash-table :test #'equal))
        (surface "app:jpcatalog"))
    (puthash surface '("reset-me")
             jetpacs-component-catalog--pending-reset-ids)
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--component-from-surface)
               (lambda (_surface) "action"))
              ((symbol-function
                'jetpacs-component-catalog--current-resettable-ids)
               (lambda (_component) '("reset-me")))
              ((symbol-function
                'jetpacs-component-catalog--presentation-mode)
               (lambda (_component) 'preview))
              ((symbol-function 'jetpacs-shell-push)
               (lambda (_surface &rest keys)
                 (funcall (plist-get keys :callback) "stale" nil))))
      (jetpacs-component-catalog--push-pending-reset surface))
    (should-not (gethash surface
                         jetpacs-component-catalog--pending-reset-ids))))

(ert-deftest jetpacs-component-catalog-mode-defaults-and-isolates-components ()
  "Projection memory defaults to Preview and is keyed by component."
  (let ((jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal)))
    (dolist (component jetpacs-component-catalog--components)
      (let ((id (plist-get component :id))
            (builder (plist-get component :builder)))
        (should (equal (jetpacs-component-catalog--detail-screen id nil)
                       (funcall builder nil)))))
    (puthash "action" 'source jetpacs-component-catalog--presentation-modes)
    (should
     (equal (jetpacs-component-catalog--detail-screen "action" nil)
            (jetpacs-component-catalog--elisp-screen
             (jetpacs-component-catalog-test--component "action") nil)))
    (should
     (equal (jetpacs-component-catalog--detail-screen "choice" nil)
            (jetpacs-component-catalog--choice-screen nil)))
    ;; A subsequent build observes the same process-local choice.
    (should
     (jetpacs-component-catalog-test--presentation-button
     (jetpacs-component-catalog--detail-screen "action" nil) "preview"))))

(ert-deftest jetpacs-component-catalog-projection-value-is-unambiguous ()
  "Injected value and legacy mode may coexist only when they agree."
  (should (equal (jetpacs-component-catalog--projection-value
                  '(:value "visual"))
                 "visual"))
  (should (equal (jetpacs-component-catalog--projection-value
                  '(:mode "lisp"))
                 "lisp"))
  (should (equal (jetpacs-component-catalog--projection-value
                  '(:value "source" :mode "source"))
                 "source"))
  (should-not (jetpacs-component-catalog--projection-value
               '(:value "preview" :mode "source"))))

(ert-deftest jetpacs-component-catalog-presentation-handler-validates-context ()
  "Malformed requests reject; unknown, stale, and wrong-screen requests stale."
  (let ((jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal)))
    (should (eq (jetpacs-component-catalog--on-presentation
                   '(:component 42 :mode "source")
                 '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-presentation
                 '(:component "action" :mode 42)
                 '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-presentation
                 '(:component "action" :mode "side-by-side")
                 '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-presentation
                 '(:component "action" :value "preview" :mode "source")
                 '(:surface "app:jpcatalog"))
                'rejected))
    (should (eq (jetpacs-component-catalog--on-presentation
                 '(:component "not-implemented" :mode "source")
                 '(:surface "app:jpcatalog"))
                'stale))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) t))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("component-action" "home"))))
      (should (eq (jetpacs-component-catalog--on-presentation
                   '(:component "action" :mode "source")
                   '(:surface "app:jpcatalog" :revision_seen 1))
                  'stale)))
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("component-choice" "home"))))
      (should (eq (jetpacs-component-catalog--on-presentation
                   '(:component "action" :mode "source")
                   '(:surface "app:jpcatalog"))
                  'stale)))
    (should (= (hash-table-count
                jetpacs-component-catalog--presentation-modes)
               0))))

(ert-deftest jetpacs-component-catalog-presentation-handler-refreshes-once ()
  "An accepted explicit destination is remembered before one scoped refresh."
  (let ((jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal))
        refreshes
        observed-mode)
    (cl-letf (((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("component-action" "home")))
              ((symbol-function 'jetpacs-buffer-defer-view-refresh)
               (lambda (surface)
                 (setq observed-mode
                       (gethash "action"
                                jetpacs-component-catalog--presentation-modes))
                 (push surface refreshes))))
      (should (eq (jetpacs-component-catalog--on-presentation
                   '(:component "action" :value "source")
                   '(:surface "app:jpcatalog" :revision_seen 7
                     :irrelevant "not forwarded"))
                  'accepted))
      (should (eq observed-mode 'source))
      (should (eq (gethash "action"
                           jetpacs-component-catalog--presentation-modes)
                  'source))
      (should (equal refreshes '("app:jpcatalog")))
      ;; Repeating the explicit destination is idempotent state-wise and still
      ;; gives that accepted user event its one deterministic refresh.
      (should (eq (jetpacs-component-catalog--on-presentation
                   '(:component "action" :mode "source")
                   '(:surface "app:jpcatalog"))
                  'accepted))
      (should (eq (gethash "action"
                           jetpacs-component-catalog--presentation-modes)
                  'source))
      (should (= (length refreshes) 2)))))

(ert-deftest jetpacs-component-catalog-source-and-ebp-fail-independently ()
  "Either unavailable projection leaves the other readable and copyable."
  (let ((component (jetpacs-component-catalog-test--component "action")))
    (cl-letf (((symbol-function 'jetpacs-elisp-source-for-function)
               (lambda (_builder)
                 '(:text "Source unavailable"
                   :caption "No builder."
                   :origin unavailable :truncated nil)))
              ((symbol-function 'jetpacs-component-catalog--preview-json)
               (lambda (_component _back)
                 '(:text "{\"t\":\"fake\"}"
                   :caption "Preview root; no multi_view envelope."
                   :available t :truncated nil))))
      (let* ((screen (jetpacs-component-catalog--elisp-screen component nil))
             (json (jetpacs-node->canonical-json screen)))
        (should (string-match-p "Source unavailable" json))
        (should (string-match-p
                 (regexp-quote "{\\\"t\\\":\\\"fake\\\"}") json))))
    (cl-letf (((symbol-function 'jetpacs-elisp-source-for-function)
               (lambda (_builder)
                 '(:text "(defun fake () t)"
                   :caption "fake.el — as authored"
                   :origin authored :truncated nil)))
              ((symbol-function 'jetpacs-component-catalog--preview-json)
               (lambda (_component _back)
                 '(:text "EBP unavailable: preview broke"
                   :caption "EBP unavailable: preview broke"
                   :available nil :truncated nil))))
      (let* ((screen (jetpacs-component-catalog--elisp-screen component nil))
             (json (jetpacs-node->canonical-json screen)))
        (should (string-match-p (regexp-quote "(defun fake () t)") json))
        (should (string-match-p "EBP unavailable" json))))))

(ert-deftest jetpacs-component-catalog-preview-json-discards-exposure ()
  "An off-tree Preview can never authorize actions absent from the document."
  (let ((jetpacs-buffer-exposed (make-hash-table :test #'equal))
        (jetpacs-buffer--exposure-capture (list nil))
        (component
         '(:id "scratch" :name "Scratch" :purpose "Test"
           :category "TEST" :builder
           jetpacs-component-catalog-test--exposing-screen)))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog-test--exposing-screen)
               (lambda (_back)
                 (jetpacs-buffer-expose
                  "*jpcatalog-preview-only*" 9 "emacs.buffer.act")
                 (jetpacs-column (jetpacs-text "Scratch preview")))))
      (should
       (plist-get
        (jetpacs-component-catalog--preview-json component nil) :available)))
    (should-not
     (jetpacs-buffer-exposed-p
      "*jpcatalog-preview-only*" 9 "emacs.buffer.act"))
    (should-not (car jetpacs-buffer--exposure-capture))))

(ert-deftest jetpacs-component-catalog-preview-json-bounds-builder-errors ()
  "A broken Preview produces an honest bounded result rather than hiding Elisp."
  (let ((component
         '(:id "broken" :name "Broken" :purpose "Test"
           :category "TEST" :builder
           jetpacs-component-catalog-test--broken-screen)))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog-test--broken-screen)
               (lambda (_back) (error "%s" (make-string 30000 ?x)))))
      (let ((result
             (jetpacs-component-catalog--preview-json component nil)))
        (should-not (plist-get result :available))
        (should (stringp (plist-get result :caption)))
        (should-not (string-empty-p (plist-get result :caption)))
        (should (< (length (plist-get result :caption)) 1000))))))

(ert-deftest jetpacs-component-catalog-preview-json-bounds-large-output ()
  "A successful oversized Preview is visibly truncated at the shared cap."
  (let ((component
         '(:id "large" :name "Large" :purpose "Test"
           :category "TEST" :builder
           jetpacs-component-catalog-test--large-screen)))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog-test--large-screen)
               (lambda (_back)
                 (jetpacs-column
                  (jetpacs-text
                   (make-string
                    (+ jetpacs-component-catalog--projection-max-chars 1000)
                    ?x))))))
      (let ((result
             (jetpacs-component-catalog--preview-json component nil)))
        (should (plist-get result :available))
        (should (plist-get result :truncated))
        (should (= (length (plist-get result :text))
                   jetpacs-component-catalog--projection-max-chars))
        (should (string-match-p "truncated at 20000 characters"
                                (plist-get result :caption)))))))

(ert-deftest jetpacs-component-catalog-sync-panel-build-is-observational ()
  "Screen construction no longer mutates completion-kind registration."
  (let (kind-calls)
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--sync-available-p)
               (lambda (&optional _client) nil))
              ((symbol-function 'ebp-complete-set-editor-kinds)
               (lambda (&rest args) (push args kind-calls))))
      (should (jetpacs-node-p (jetpacs-component-catalog--sync-panel)))
      (should-not kind-calls))))

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

(ert-deftest jetpacs-component-catalog-tabs-commit-controlled-values ()
  "Tabs reference actions validate injected strings before refreshing."
  (let ((jetpacs-component-catalog--tabs-value "preview")
        (jetpacs-component-catalog--tabs-scrollable-value "overview")
        refreshes)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (params) (push params refreshes))))
      (should (eq (jetpacs-component-catalog--on-tabs
                   '(:value "visual") '(:surface "app:jpcatalog"))
                  'accepted))
      (should (equal jetpacs-component-catalog--tabs-value "visual"))
      (should (eq (jetpacs-component-catalog--on-tabs
                   '(:value "unknown") nil)
                  'rejected))
      (should (eq (jetpacs-component-catalog--on-tabs-scrollable
                   '(:value "examples") '(:surface "app:jpcatalog"))
                  'accepted))
      (should (equal jetpacs-component-catalog--tabs-scrollable-value
                     "examples"))
      (should (= (length refreshes) 2)))))

(ert-deftest jetpacs-component-catalog-section-navigation-targets-one-heading ()
  "A section command selects exactly one direct keyed scroll target."
  (let ((jetpacs-component-catalog--section-value "overview")
        refreshes)
    (cl-labels
        ((assert-screen
          (value)
          (let* ((screen
                  (jetpacs-component-catalog--section-navigator-screen nil))
                 (navigator
                  (jetpacs-component-catalog-test--find-node
                   screen "jpcatalog-section-navigator-demo"))
                 (targets
                  (seq-filter
                   (lambda (node) (eq (plist-get node :scroll_here) t))
                   (jetpacs-component-catalog-test--nodes screen)))
                 (target (car targets))
                 (key (format "jpcatalog-section-heading-%s" value)))
            (should (equal (plist-get navigator :t)
                           "jetpacs.section_navigator"))
            (should (eq (plist-get navigator :pinned) t))
            (should (jetpacs-component-catalog-test--direct-lazy-child-p
                     screen "jpcatalog-section-navigator-demo"))
            (should (= (length targets) 1))
            (should (equal (plist-get target :key) key))
            (should (jetpacs-component-catalog-test--direct-lazy-key-p
                     screen key))
            (should (integerp
                     (plist-get (plist-get target :semantics)
                                :heading_level))))))
      (assert-screen "overview")
      (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
                 (lambda (params) (push params refreshes))))
        (should (eq
                 (jetpacs-component-catalog--on-section-navigate
                  '(:value "accessibility") '(:surface "app:jpcatalog"))
                 'accepted))
        (should (equal jetpacs-component-catalog--section-value
                       "accessibility"))
        (should (eq
                 (jetpacs-component-catalog--on-section-navigate
                  '(:value "unknown") nil)
                 'rejected)))
      (assert-screen "accessibility")
      (should (= (length refreshes) 1)))
    (should
     (eq (cdr (assoc "jpcatalog.section.navigate"
                     jetpacs-component-catalog--verbs))
         'jetpacs-component-catalog--on-section-navigate))
    (should
     (equal
      (plist-get (jetpacs-action-schema "jpcatalog.section.navigate") :args)
      '((:name value :type "text" :required t))))))

(ert-deftest jetpacs-component-catalog-text-actions-separate-drafts-and-submits ()
  "Text changes stay IME-safe while submit commits only non-secret state."
  (let ((jetpacs-component-catalog--text-change-count 0)
        (jetpacs-component-catalog--text-submit-count 0)
        (jetpacs-component-catalog--last-text-submit nil)
        refreshed)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (params) (setq refreshed params))))
      (should (eq (jetpacs-component-catalog--on-text-change nil nil)
                  'accepted))
      (should (= jetpacs-component-catalog--text-change-count 1))
      (should-not refreshed)
      (should (eq (jetpacs-component-catalog--on-text-submit
                   '(:value "accepted draft") '(:surface "app:jpcatalog"))
                  'accepted))
      (should (= jetpacs-component-catalog--text-submit-count 1))
      (should (equal jetpacs-component-catalog--last-text-submit
                     "accepted draft"))
      (should (equal refreshed '(:surface "app:jpcatalog")))
      (should (eq (jetpacs-component-catalog--on-text-submit
                   '(:value 42) nil)
                  'rejected)))))

(ert-deftest jetpacs-component-catalog-editor-actions-bound-live-readout ()
  "Published drafts stay IME-safe and save/enter actions refresh once."
  (let ((jetpacs-component-catalog--editor-state-count 0)
        (jetpacs-component-catalog--editor-save-count 0)
        (jetpacs-component-catalog--editor-enter-count 0)
        (jetpacs-component-catalog--last-editor-preview nil)
        (jetpacs-component-catalog--last-editor-length nil)
        refreshes)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (params) (push params refreshes))))
      (jetpacs-component-catalog--on-editor-state
       (concat "first line\n" (make-string 100 ?x)))
      (should (= jetpacs-component-catalog--editor-state-count 1))
      (should (= jetpacs-component-catalog--last-editor-length 111))
      (should (string-match-p "first line↵"
                              jetpacs-component-catalog--last-editor-preview))
      (should (<= (string-width jetpacs-component-catalog--last-editor-preview)
                  72))
      (should-not refreshes)
      (should (eq (jetpacs-component-catalog--on-editor-save
                   '(:value "saved") '(:surface "app:jpcatalog"))
                  'accepted))
      (should (eq (jetpacs-component-catalog--on-editor-enter
                   '(:value "entered") '(:surface "app:jpcatalog"))
                  'accepted))
      (should (= jetpacs-component-catalog--editor-save-count 1))
      (should (= jetpacs-component-catalog--editor-enter-count 1))
      (should (equal jetpacs-component-catalog--last-editor-preview "entered"))
      (should (= (length refreshes) 2))
      (should (eq (jetpacs-component-catalog--on-editor-save
                   '(:value 42) nil)
                  'rejected))
      (should (eq (jetpacs-component-catalog--on-editor-enter
                   '(:value :json-false) nil)
                  'rejected)))))

(ert-deftest jetpacs-component-catalog-secure-submit-retains-only-length ()
  "The secure demonstration destroys its volatile field string in place."
  (let* ((secret (copy-sequence "swordfish"))
         (jetpacs-component-catalog--secure-submit-count 0)
         (jetpacs-component-catalog--last-secret-length nil)
         refreshed)
    (cl-letf (((symbol-function 'jetpacs-app-defer-refresh)
               (lambda (params) (setq refreshed params))))
      (should (eq (jetpacs-component-catalog--on-secure-submit
                   nil (list :surface "app:jpcatalog"
                             :fields (list :jpcatalog-password secret)))
                  'accepted))
      (should (= jetpacs-component-catalog--secure-submit-count 1))
      (should (= jetpacs-component-catalog--last-secret-length 9))
      (should (equal secret (make-string 9 0)))
      (should (equal (plist-get refreshed :surface) "app:jpcatalog"))
      (should (equal (plist-get (plist-get refreshed :fields)
                                :jpcatalog-password)
                     (make-string 9 0)))
      (should (eq (jetpacs-component-catalog--on-secure-submit
                   nil '(:fields (:jpcatalog-password 42)))
                  'rejected)))))

(ert-deftest jetpacs-component-catalog-secure-field-authors-no-secret-value ()
  "The catalog's secure field uses canonical capture without an authored value."
  (let* ((screen (jetpacs-component-catalog--text-field-screen nil))
         (json (jetpacs-node->canonical-json screen)))
    (should (string-match-p
             (regexp-quote
              "\"capture_fields\":[\"jpcatalog-password\"]")
             json))
    (should (string-match-p
             (regexp-quote "\"id\":\"jpcatalog-password\"")
             json))
    (should-not (string-match-p
                 "\"id\":\"jpcatalog-password\"[^}]*\"value\""
                 json))))

(ert-deftest jetpacs-component-catalog-unregister-forgets-authoring-state ()
  "Unload clears root/component modes, drafts, resets, and subscriptions."
  (let ((jetpacs-component-catalog--root-presentation-mode 'source)
        (jetpacs-component-catalog--section-value "examples")
        (jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal))
        (jetpacs-component-catalog--drafts
         (make-hash-table :test #'equal))
        (jetpacs-component-catalog--pending-reset-ids
         (make-hash-table :test #'equal))
        cleared)
    (puthash "action" 'source
             jetpacs-component-catalog--presentation-modes)
    (puthash "editor" 'visual
             jetpacs-component-catalog--presentation-modes)
    (puthash "action" (jetpacs-component-catalog--make-draft "action")
             jetpacs-component-catalog--drafts)
    (puthash "app:jpcatalog" '("jpcatalog-choice-specimen")
             jetpacs-component-catalog--pending-reset-ids)
    (cl-letf (((symbol-function 'remove-hook) #'ignore)
              ((symbol-function
                'jetpacs-component-catalog--release-authoring-sync-buffer)
               #'ignore)
              ((symbol-function
                'jetpacs-component-catalog--release-sync-buffer)
               #'ignore)
              ((symbol-function 'jetpacs-undefaction) #'ignore)
              ((symbol-function 'jetpacs-on-state-change-clear)
               (lambda (prefix surface)
                 (setq cleared (list prefix surface))))
              ((symbol-function 'jetpacs-apps-unregister) #'ignore)
              ((symbol-function 'jetpacs-chrome-remove) #'ignore))
      (jetpacs-component-catalog-unregister))
    (should (= (hash-table-count
                jetpacs-component-catalog--presentation-modes)
               0))
    (should (= (hash-table-count jetpacs-component-catalog--drafts) 0))
    (should (= (hash-table-count
                jetpacs-component-catalog--pending-reset-ids)
               0))
    (should (eq jetpacs-component-catalog--root-presentation-mode 'preview))
    (should (equal jetpacs-component-catalog--section-value "overview"))
    (should (equal cleared '("" "app:jpcatalog")))))

(ert-deftest jetpacs-component-catalog-visual-controls-cover-every-specimen ()
  "Every implemented component gets schema-driven edit actions and four modes."
  (let ((jetpacs-component-catalog--drafts
         (make-hash-table :test #'equal)))
    (dolist (component jetpacs-component-catalog--components)
      (let* ((id (plist-get component :id))
             (document (jetpacs-component-catalog--document id))
             (digest (jetpacs-component-catalog--draft-digest id))
             (visual (jetpacs-component-catalog-editor-visual
                      id document digest))
             (tabs
              (jetpacs-component-catalog-editor-mode-row id "visual"))
             (modes
              (mapcar (lambda (option) (plist-get option :value))
                      (append (plist-get tabs :options) nil))))
        (should
         (seq-some
          (lambda (node)
            (let ((descriptor
                   (or (plist-get node :on_tap)
                       (plist-get node :on_change)
                       (plist-get node :on_submit)
                       (plist-get node :on_save))))
              (equal (plist-get descriptor :action) "jpcatalog.edit")))
          (jetpacs-component-catalog-test--nodes visual)))
        (should (equal (plist-get tabs :t) "jetpacs.tabs"))
        (should (equal (plist-get tabs :value) "visual"))
        (should (eq (plist-get tabs :pinned) t))
        (should (equal (plist-get tabs :variant) "fixed"))
        (should (eq (plist-get tabs :scrollable) :json-false))
        (should (equal
                 (plist-get (plist-get tabs :on_change) :action)
                 "jpcatalog.presentation"))
        (should (equal
                 (plist-get
                  (plist-get (plist-get tabs :on_change) :args) :component)
                 id))
        (should (equal modes '("preview" "visual" "lisp" "source")))))))

(ert-deftest jetpacs-component-catalog-projection-tabs-stay-pinned-and-direct ()
  "Every projection strip is a direct sticky item of its scrolling body."
  (let ((jetpacs-component-catalog--root-presentation-mode 'preview)
        (jetpacs-component-catalog--presentation-modes
         (make-hash-table :test #'equal))
        (jetpacs-component-catalog--drafts
         (make-hash-table :test #'equal)))
    (dolist (mode '(preview visual lisp source))
      (setq jetpacs-component-catalog--root-presentation-mode mode)
      (let* ((screen (jetpacs-component-catalog--root-screen nil))
             (id "jpcatalog-root-projection-tabs")
             (tabs (jetpacs-component-catalog-test--find-node screen id)))
        (should (eq (plist-get tabs :pinned) t))
        (should (equal (plist-get tabs :variant) "fixed"))
        (should (eq (plist-get tabs :scrollable) :json-false))
        (should
         (jetpacs-component-catalog-test--direct-lazy-child-p screen id))))
    (dolist (component jetpacs-component-catalog--components)
      (let ((id (plist-get component :id)))
        (dolist (mode '(preview visual lisp source))
          (puthash id mode jetpacs-component-catalog--presentation-modes)
          (let* ((screen
                  (jetpacs-component-catalog--detail-screen id nil))
                 (tabs-id (format "jpcatalog-%s-projection-tabs" id))
                 (tabs
                  (jetpacs-component-catalog-test--find-node screen tabs-id)))
            (should (eq (plist-get tabs :pinned) t))
            (should (equal (plist-get tabs :variant) "fixed"))
            (should (eq (plist-get tabs :scrollable) :json-false))
            (should
             (jetpacs-component-catalog-test--direct-lazy-child-p
              screen tabs-id))))))))

(ert-deftest jetpacs-component-catalog-authored-pinned-navigators-stay-direct ()
  "Every GUI-authored sticky navigator stays direct in live projections."
  (let* ((jetpacs-component-catalog--presentation-modes
          (make-hash-table :test #'equal))
         (jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal)))
    (dolist (case '(("tabs" . "jpcatalog-tabs-specimen")
                    ("section-navigator" .
                     "jpcatalog-section-navigator-specimen")))
      (let* ((component (car case))
             (id (cdr case))
             (document
              (jetpacs-component-authoring-set-at-path
               (jetpacs-component-authoring-default-document component)
               '(:pinned) t)))
        (jetpacs-component-catalog--replace-document component document)
        (dolist (mode '(preview visual lisp))
          (puthash component mode
                   jetpacs-component-catalog--presentation-modes)
          (let* ((screen
                  (jetpacs-component-catalog--detail-screen component nil))
                 (navigator
                  (jetpacs-component-catalog-test--find-node screen id)))
            (should (eq (plist-get navigator :pinned) t))
            (should
             (jetpacs-component-catalog-test--direct-lazy-child-p screen id))
            (should (jetpacs-check-profile screen 'app))))))))

(ert-deftest jetpacs-component-catalog-tabs-visual-editor-is-atomic ()
  "Tabs GUI edits options while retaining one valid controlled selection."
  (let* ((document (jetpacs-component-authoring-default-document "tabs"))
         (digest (jetpacs-component-authoring-document-digest document))
         (visual (jetpacs-component-catalog-editor-visual
                  "tabs" document digest))
         (descriptors
          (jetpacs-component-catalog-test--edit-descriptors visual))
         (rename
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :codec) "tabs-option-value")
                    (= (or (plist-get args :index) -1) 0))))
           descriptors))
         (delete
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :codec) "tabs-option-delete")
                    (= (or (plist-get args :index) -1) 0))))
           descriptors))
         (add
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :path) ["options"])
                    (equal (plist-get args :codec) "vector-add"))))
           descriptors))
         (selected
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :path) ["value"])
                    (equal (plist-get args :codec) "string"))))
           descriptors))
         (presentation
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :path) ["variant"])
                    (equal (plist-get args :codec) "tabs-presentation"))))
           descriptors))
         (presentation-control
          (cl-find "Presentation"
                   (jetpacs-component-catalog-test--nodes visual "dropdown")
                   :key (lambda (node) (plist-get node :label))
                   :test #'equal))
         (pinned-toggle
          (cl-find "Pin while scrolling"
                   (jetpacs-component-catalog-test--nodes
                    visual "jetpacs.choice")
                   :key (lambda (node) (plist-get node :label))
                   :test #'equal)))
    (should rename)
    (should delete)
    (should add)
    (should selected)
    (should presentation)
    (should (equal (plist-get presentation-control :value) "adaptive"))
    (should
     (equal
      (mapcar (lambda (option) (plist-get option :value))
              (append (plist-get presentation-control :options) nil))
      '("legacy-fixed" "legacy-scrollable" "fixed" "scrollable"
        "navigator" "adaptive")))
    (should pinned-toggle)
    (should (eq (plist-get pinned-toggle :checked) :json-false))
    (should (equal
             (plist-get (plist-get pinned-toggle :on_change) :action)
             "jpcatalog.edit.boolean"))
    (should (equal
             (plist-get (plist-get (plist-get pinned-toggle :on_change) :args)
                        :path)
             ["pinned"]))
    (let* ((rename-args
            (append (copy-tree (plist-get rename :args))
                    '(:value "rendered")))
           (renamed
            (jetpacs-component-catalog--edit-document
             document '(:options 0 :value) "tabs-option-value" rename-args))
           (delete-args (copy-tree (plist-get delete :args)))
           (deleted
            (jetpacs-component-catalog--edit-document
             document '(:options) "tabs-option-delete" delete-args))
           (pinned
            (jetpacs-component-catalog--edit-document
             document '(:pinned) "injected-boolean" '(:value t)))
           (fixed
            (jetpacs-component-catalog--edit-document
             document '(:variant) "tabs-presentation"
             (append (copy-tree (plist-get presentation :args))
                     '(:value "fixed")))))
      (should (equal (plist-get (plist-get renamed :root) :value)
                     "rendered"))
      (should (equal (plist-get (plist-get deleted :root) :value) "visual"))
      (should (eq (plist-get (plist-get pinned :root) :pinned) t))
      (should (equal (plist-get (plist-get fixed :root) :variant) "fixed"))
      (should-not (plist-member (plist-get fixed :root) :scrollable))
      (should (eq
               (plist-get
                (jetpacs-component-authoring-compile-document fixed)
                :scrollable)
               :json-false))
      (should (= (length (plist-get (plist-get deleted :root) :options))
                 2)))))

(ert-deftest jetpacs-component-catalog-section-visual-editor-is-ordered ()
  "Section GUI keeps Label, Value, and Level rows in option order."
  (let* ((document
          (jetpacs-component-authoring-default-document "section-navigator"))
         (digest (jetpacs-component-authoring-document-digest document))
         (visual
          (jetpacs-component-catalog-editor-visual
           "section-navigator" document digest))
         (descriptors
          (jetpacs-component-catalog-test--edit-descriptors visual))
         (labels
          (cl-loop
           for node in (jetpacs-component-catalog-test--nodes
                        visual "text_input")
           for label = (plist-get node :label)
           when (member label '("Label" "Value" "Level (1–6)"))
           collect label))
         (rename
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :codec) "section-option-value")
                    (= (or (plist-get args :index) -1) 0))))
           descriptors))
         (delete
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :codec) "section-option-delete")
                    (= (or (plist-get args :index) -1) 0))))
           descriptors))
         (level
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :codec) "integer")
                    (equal (plist-get args :path)
                           ["options" 0 "level"]))))
           descriptors))
         (selected
          (cl-find-if
           (lambda (descriptor)
             (let ((args (plist-get descriptor :args)))
               (and (equal (plist-get args :codec) "string")
                    (equal (plist-get args :path) ["value"]))))
           descriptors)))
    (should
     (equal labels
            '("Label" "Value" "Level (1–6)"
              "Label" "Value" "Level (1–6)"
              "Label" "Value" "Level (1–6)"
              "Label" "Value" "Level (1–6)")))
    (should rename)
    (should delete)
    (should level)
    (should selected)
    (let* ((args (append (copy-tree (plist-get rename :args))
                         '(:value "introduction")))
           (renamed
            (jetpacs-component-catalog--edit-document
             document '(:options 0 :value) "section-option-value" args)))
      (should (equal (plist-get (plist-get renamed :root) :value)
                     "introduction")))))

(ert-deftest jetpacs-component-catalog-first-semantic-action-is-authorable ()
  "The first custom Semantics action creates its absent vector atomically."
  (let* ((base (jetpacs-component-authoring-default-document "action"))
         (document
          (jetpacs-component-authoring-set-at-path
           base '(:semantics) '(:name "Specimen")))
         (digest (jetpacs-component-authoring-document-digest document))
         (visual (jetpacs-component-catalog-editor-visual
                  "action" document digest))
         (button
          (cl-find-if
           (lambda (node)
             (equal (plist-get node :label) "Add semantic action"))
           (jetpacs-component-catalog-test--nodes visual "button")))
         (descriptor (and button (plist-get button :on_tap)))
         (args (and descriptor (plist-get descriptor :args))))
    (should (equal (plist-get descriptor :action) "jpcatalog.edit"))
    (should (equal (plist-get args :path) ["semantics" "actions"]))
    (should (equal (plist-get args :codec) "literal"))
    (let* ((literal
            (jetpacs-authoring-read-one (plist-get args :literal_source) 8192))
           (updated
            (jetpacs-component-catalog--edit-document
             document '(:semantics :actions) (plist-get args :codec) args))
           (actions (plist-get (plist-get (plist-get updated :root) :semantics)
                               :actions))
           (next-visual
            (jetpacs-component-catalog-editor-visual
             "action" updated
             (jetpacs-component-authoring-document-digest updated)))
           (next-button
            (cl-find-if
             (lambda (node)
               (equal (plist-get node :label) "Add semantic action"))
             (jetpacs-component-catalog-test--nodes next-visual "button"))))
      (should (vectorp literal))
      (should (= (length literal) 1))
      (should (= (length actions) 1))
      (should (equal (plist-get (aref actions 0) :label) "Action"))
      (should
       (equal
        (plist-get (plist-get (plist-get next-button :on_tap) :args) :codec)
        "vector-add")))))

(ert-deftest jetpacs-component-catalog-toolbar-choices-are-profile-proven ()
  "Visual toolbar choices cannot introduce an unadvertised feature gate."
  (let* ((client (ebp--make-client))
         (document (jetpacs-component-authoring-default-document "editor"))
         (digest (jetpacs-component-authoring-document-digest document)))
    (cl-labels
        ((toolbar-edits
          (features)
          (setf (ebp-client-profiles client)
                `(:app (:features ,features)))
          (let ((visual (jetpacs-component-catalog-editor-visual
                         "editor" document digest)))
            (cl-loop
             for descriptor in
             (jetpacs-component-catalog-test--edit-descriptors visual)
             for args = (plist-get descriptor :args)
             when (and (equal (plist-get args :path) ["toolbar"])
                       (equal (plist-get args :codec) "literal"))
             collect args))))
      (cl-letf (((symbol-function 'jetpacs-client) (lambda () client)))
        (let* ((features [])
               (edits (toolbar-edits features))
               (literals
                (mapcar (lambda (args)
                          (jetpacs-authoring-read-one
                           (plist-get args :literal_source) 8192))
                        edits)))
          (should (= (length literals) 1))
          (should (vectorp (car literals)))
          (dolist (args edits)
            (let ((node
                   (jetpacs-component-authoring-compile-document
                    (jetpacs-component-catalog--edit-document
                     document '(:toolbar) "literal" args))))
              (should-not
               (jetpacs-shell--check-features node nil "catalog audit")))))
        (let* ((features ["toolbar.safe-one" "image.https"
                          "toolbar.safe-two"])
               (edits (toolbar-edits features))
               (literals
                (mapcar (lambda (args)
                          (jetpacs-authoring-read-one
                           (plist-get args :literal_source) 8192))
                        edits))
               (registered (seq-filter #'stringp literals)))
          (should (equal registered '("safe-one" "safe-two")))
          (should-not (member "jpcatalog-toolbar" registered))
          (dolist (args edits)
            (let ((node
                   (jetpacs-component-authoring-compile-document
                    (jetpacs-component-catalog--edit-document
                     document '(:toolbar) "literal" args))))
              (should-not
               (jetpacs-shell--check-features
                node (append features nil) "catalog audit")))))))))

(ert-deftest jetpacs-component-catalog-visual-edit-updates-shared-authority ()
  "A valid Visual edit immutably updates the document seen by Lisp."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (component "action")
         (old-document (jetpacs-component-catalog--document component))
         (digest (jetpacs-component-catalog--draft-digest component))
         refreshed)
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function
                'jetpacs-component-catalog--defer-authoring-refresh)
               (lambda (surface ids) (setq refreshed (list surface ids)))))
      (should
       (eq
        (jetpacs-component-catalog--on-edit
         (list :component component :digest digest :path ["label"]
               :codec "string" :value "Configured action")
         '(:surface "app:jpcatalog"))
        'accepted)))
    (let* ((new-document (jetpacs-component-catalog--document component))
           (lisp (jetpacs-component-authoring-print-document new-document)))
      (should (equal (plist-get (plist-get old-document :root) :label)
                     "Run ordinary action"))
      (should (equal (plist-get (plist-get new-document :root) :label)
                     "Configured action"))
      (should (string-match-p "Configured action" lisp))
      (should-not (equal digest
                         (jetpacs-component-catalog--draft-digest component)))
      (should (equal refreshed '("app:jpcatalog" nil))))))

(ert-deftest jetpacs-component-catalog-pinned-toggle-keeps-native-boolean ()
  "The Choice-backed pin toggle commits its injected wire boolean."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (component "tabs")
         (digest (jetpacs-component-catalog--draft-digest component))
         refreshed)
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function
                'jetpacs-component-catalog--defer-authoring-refresh)
               (lambda (surface ids) (setq refreshed (list surface ids)))))
      (should
       (eq
        (jetpacs-component-catalog--on-boolean-edit
         (list :component component :digest digest :path ["pinned"]
               :value t)
         '(:surface "app:jpcatalog"))
        'accepted))
      (should
       (eq
        (jetpacs-component-catalog--on-boolean-edit
         (list :component component :digest digest :path ["pinned"]
               :value "true")
         '(:surface "app:jpcatalog"))
        'rejected)))
    (should (eq (plist-get
                 (plist-get (jetpacs-component-catalog--document component)
                            :root)
                 :pinned)
                t))
    (should (equal refreshed '("app:jpcatalog" nil)))))

(ert-deftest jetpacs-component-catalog-invalid-visual-edit-keeps-last-valid ()
  "Invalid control input renders feedback without replacing the specimen."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (component "text-field")
         (document (jetpacs-component-catalog--document component))
         (digest (jetpacs-component-catalog--draft-digest component))
         refreshes)
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function 'jetpacs-buffer-defer-view-refresh)
               (lambda (surface) (push surface refreshes))))
      (should
       (eq
        (jetpacs-component-catalog--on-edit
         (list :component component :digest digest :path ["min_lines"]
               :codec "integer" :value "not-a-number")
         '(:surface "app:jpcatalog"))
        'accepted)))
    (should (equal document
                   (jetpacs-component-catalog--document component)))
    (should (string-match-p
             "whole number"
             (plist-get (jetpacs-component-catalog--draft component) :error)))
    (should (equal refreshes '("app:jpcatalog")))))

(defvar jetpacs-component-catalog-test--read-eval-proof nil)

(ert-deftest jetpacs-component-catalog-lisp-is-inert-and-last-valid ()
  "Canonical Lisp applies as data; invalid reader syntax remains corrective."
  (let* ((jetpacs-component-catalog-test--read-eval-proof nil)
         (jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (component "action")
         (old-document (jetpacs-component-catalog--document component))
         (new-document
          (jetpacs-component-authoring-set-at-path
           old-document '(:label) "From Lisp"))
         (source
          (jetpacs-component-authoring-print-document new-document))
         (refreshes 0))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function
                'jetpacs-component-catalog--defer-authoring-refresh)
               (lambda (&rest _arguments) (cl-incf refreshes)))
              ((symbol-function 'jetpacs-buffer-defer-view-refresh)
               (lambda (&rest _arguments) (cl-incf refreshes))))
      (should
       (eq (jetpacs-component-catalog--on-lisp-apply
            (list :component component
                  :digest (jetpacs-component-catalog--draft-digest component)
                  :value source)
            '(:surface "app:jpcatalog"))
           'accepted))
      (let ((invalid
             "#.(setq jetpacs-component-catalog-test--read-eval-proof t)"))
        (should
         (eq (jetpacs-component-catalog--on-lisp-apply
              (list :component component
                    :digest (jetpacs-component-catalog--draft-digest component)
                    :value invalid)
              '(:surface "app:jpcatalog"))
             'accepted))
        (should
         (equal (plist-get (jetpacs-component-catalog--draft component)
                           :lisp-buffer)
                invalid))))
    (should-not jetpacs-component-catalog-test--read-eval-proof)
    (should (equal (jetpacs-component-catalog--document component)
                   new-document))
    (should (= refreshes 2))))

(ert-deftest jetpacs-component-catalog-reset-restores-and-reconciles-state ()
  "Reset restores the component default and names its live stateful specimen."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (jetpacs-component-catalog--presentation-modes
          (make-hash-table :test #'equal))
         (component "choice")
         (changed
          (jetpacs-component-authoring-set-at-path
           (jetpacs-component-catalog--document component)
           '(:checked) :json-false))
         reset)
    (jetpacs-component-catalog--replace-document component changed)
    (puthash component 'visual
             jetpacs-component-catalog--presentation-modes)
    (let ((draft (jetpacs-component-catalog--draft component)))
      (setq draft (plist-put draft :armed t))
      (setq draft (plist-put draft :traces '((:name "old"))))
      (jetpacs-component-catalog--put-draft component draft))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function
                'jetpacs-component-catalog--defer-authoring-refresh)
               (lambda (surface ids) (setq reset (list surface ids)))))
      (should
       (eq (jetpacs-component-catalog--on-reset
            (list :component component
                  :digest (jetpacs-component-catalog--draft-digest component))
            '(:surface "app:jpcatalog"))
           'accepted)))
    (let ((draft (jetpacs-component-catalog--draft component)))
      (should (equal (plist-get (plist-get draft :document) :root)
                     (plist-get (jetpacs-component-authoring-default-document
                                 component)
                                :root)))
      (should-not (plist-get draft :armed))
      (should-not (plist-get draft :traces)))
    (should (equal (car reset) "app:jpcatalog"))
    (should (member "jpcatalog-choice-specimen" (cadr reset)))))

(ert-deftest jetpacs-component-catalog-trace-retains-no-values-or-field-ids ()
  "Unarmed actions retain only validated catalog metadata and value shapes."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (component "action")
         (instrumentation
          (jetpacs-component-catalog--instrumented-specimen component))
         (record
          (car (jetpacs-component-catalog-actions-descriptors
                (plist-get instrumentation :document))))
         (base-args (copy-tree
                     (plist-get (plist-get record :descriptor) :args) t))
         (args (append base-args '(:value "top secret")))
         (secret (copy-sequence "hunter2"))
         (params (list :surface "app:jpcatalog"
                       :fields (list :password-field nil)))
         refreshed)
    (setf (plist-get (plist-get params :fields) :password-field) secret)
    (cl-letf (((symbol-function 'jetpacs-chrome-stack)
               (lambda (_surface) '("component-action" "home")))
              ((symbol-function 'jetpacs-event-stale-p)
               (lambda (_params) nil))
              ((symbol-function 'jetpacs-buffer-defer-view-refresh)
               (lambda (surface) (setq refreshed surface))))
      (should
       (eq (jetpacs-component-catalog--on-trace
            args params)
           'accepted)))
    (let* ((trace (car (plist-get
                        (jetpacs-component-catalog--draft component)
                        :traces)))
           (printed (prin1-to-string trace)))
      (should (equal refreshed "app:jpcatalog"))
      (should-not (string-match-p "top secret\|hunter2\|password-field"
                                  printed))
      (should (equal secret (make-string 7 0)))
      (should (= (plist-get trace :injected_count) 1))
      (should (= (plist-get trace :field_count) 1)))))

(ert-deftest jetpacs-component-catalog-arm-and-ready-fail-closed ()
  "Arming requires a live proof, and every READY transition disarms drafts."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (jetpacs-component-catalog--pending-reset-ids
          (make-hash-table :test #'equal))
         (component "action")
         (refreshes 0))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function 'jetpacs-buffer-defer-view-refresh)
               (lambda (&rest _arguments) (cl-incf refreshes)))
              ((symbol-function
                'jetpacs-component-catalog--sync-available-p)
               (lambda (&optional _client) nil))
              ((symbol-function
                'jetpacs-component-catalog--detach-fixed-sync-buffer)
               #'ignore))
      (should
       (eq (jetpacs-component-catalog--on-arm
            (list :component component
                  :digest (jetpacs-component-catalog--draft-digest component)
                  :armed t)
            '(:surface "app:jpcatalog"))
           'accepted))
      (should-not (plist-get (jetpacs-component-catalog--draft component)
                             :armed))
      (should (plist-get (jetpacs-component-catalog--draft component) :error))
      (let ((draft (jetpacs-component-catalog--draft component)))
        (jetpacs-component-catalog--put-draft
         component (plist-put draft :armed t)))
      (jetpacs-component-catalog--on-ready 'client)
      (should-not (plist-get (jetpacs-component-catalog--draft component)
                             :armed)))
    (should (= refreshes 2))))

(ert-deftest jetpacs-component-catalog-sync-editor-refuses-session-theft ()
  "An authored synchronized Editor cannot steal another buffer's exact key."
  (let* ((base (jetpacs-component-authoring-default-document "editor"))
         (candidate
          (jetpacs-component-authoring-set-at-path
           base '(:document) "doc:jpcatalog/authored"))
         (foreign (generate-new-buffer " *foreign authored editor*"))
         (jetpacs-component-catalog--authoring-sync-buffer nil)
         (jetpacs-component-catalog--authoring-sync-key nil))
    (unwind-protect
        (cl-letf (((symbol-function 'ebp-sync-attached-buffer)
                   (lambda (_document _editor-id) foreign)))
          (should-error
           (jetpacs-component-catalog--validate-authoring-sync candidate)
           :type 'jetpacs-component-catalog-edit-error))
      (kill-buffer foreign))))

(ert-deftest jetpacs-component-catalog-rejects-descendant-id-collisions ()
  "A valid node tree cannot commit duplicate ids that break its whole page."
  (let* ((base (jetpacs-component-authoring-default-document "panel"))
         (root-id
          (jetpacs-component-authoring-set-at-path
           base '(:id) "duplicate-specimen-id"))
         (duplicate
          (jetpacs-component-authoring-set-at-path
           root-id '(:children 0 :id) "duplicate-specimen-id")))
    (should-error
     (jetpacs-component-catalog--validate-authoring-sync duplicate)
     :type 'jetpacs-component-catalog-edit-error)))

(ert-deftest jetpacs-component-catalog-sync-key-failure-preserves-live-buffer ()
  "A failed new-key attach cannot destroy the old synchronized draft text."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (old-document
          (jetpacs-component-authoring-set-at-path
           (jetpacs-component-authoring-default-document "editor")
           '(:document) "doc:jpcatalog/old"))
         (new-document
          (jetpacs-component-authoring-set-at-path
           old-document '(:document) "doc:jpcatalog/new"))
         (old-key (jetpacs-component-catalog--authoring-editor-key
                   old-document))
         (old-buffer (generate-new-buffer " *catalog old sync*"))
         (jetpacs-component-catalog--authoring-sync-buffer old-buffer)
         (jetpacs-component-catalog--authoring-sync-key old-key)
         candidate detached)
    (unwind-protect
        (progn
          (jetpacs-component-catalog--configure-authoring-buffer
           old-buffer old-document)
          (jetpacs-component-catalog--replace-buffer-text
           old-buffer "live text that is not the authored seed")
          (let ((draft (jetpacs-component-catalog--make-draft "editor")))
            (jetpacs-component-catalog--put-draft
             "editor" (plist-put draft :document old-document)))
          (cl-letf (((symbol-function
                      'jetpacs-component-catalog--register-state-handlers)
                     #'ignore)
                    ((symbol-function
                      'jetpacs-component-catalog--sync-available-p)
                     (lambda (&optional _client) t))
                    ((symbol-function 'ebp-sync-buffer)
                     (lambda (_client document editor-id)
                       (and (equal (cons document editor-id) old-key)
                            old-buffer)))
                    ((symbol-function 'ebp-sync-attach)
                     (lambda (_client _document _editor-id buffer)
                       (setq candidate buffer)
                       (jetpacs-component-catalog--replace-buffer-text
                        buffer "partially adopted mirror")
                       (error "synthetic attach failure")))
                    ((symbol-function 'ebp-sync-detach)
                     (lambda (&optional buffer)
                       (push (or buffer (current-buffer)) detached)))
                    ((symbol-function 'ebp-complete-set-editor-kinds)
                     #'ignore)
                    ((symbol-function 'jetpacs-feature-advertised-p)
                     (lambda (&rest _arguments) nil)))
            (should-error
             (jetpacs-component-catalog--replace-and-reconcile
              "editor" old-document new-document nil)))
          (should (eq jetpacs-component-catalog--authoring-sync-buffer
                      old-buffer))
          (should (equal jetpacs-component-catalog--authoring-sync-key
                         old-key))
          (should (buffer-live-p old-buffer))
          (with-current-buffer old-buffer
            (should (equal (buffer-string)
                           "live text that is not the authored seed")))
          (should candidate)
          (should-not (buffer-live-p candidate))
          (should-not (memq old-buffer detached))
          (should (equal (jetpacs-component-catalog--document "editor")
                         old-document)))
      (when (buffer-live-p old-buffer)
        (kill-buffer old-buffer)))))

(ert-deftest jetpacs-component-catalog-sync-syntax-change-preserves-text ()
  "A same-key syntax edit detaches, changes mode, and reattaches without loss."
  (let* ((synchronized
          (jetpacs-component-authoring-set-at-path
           (jetpacs-component-authoring-default-document "editor")
           '(:document) "doc:jpcatalog/same"))
         (old-document
          (jetpacs-component-authoring-delete-at-path synchronized '(:syntax)))
         (new-document
          (jetpacs-component-authoring-set-at-path
           old-document '(:syntax) "elisp"))
         (key (jetpacs-component-catalog--authoring-editor-key old-document))
         (buffer (generate-new-buffer " *catalog syntax sync*"))
         (jetpacs-component-catalog--authoring-sync-buffer buffer)
         (jetpacs-component-catalog--authoring-sync-key key)
         sequence attached-text attached-mode)
    (unwind-protect
        (progn
          (jetpacs-component-catalog--configure-authoring-buffer
           buffer old-document)
          (jetpacs-component-catalog--replace-buffer-text buffer "live syntax text")
          (cl-letf (((symbol-function
                      'jetpacs-component-catalog--sync-available-p)
                     (lambda (&optional _client) t))
                    ((symbol-function 'ebp-sync-buffer)
                     (lambda (&rest _arguments) nil))
                    ((symbol-function 'ebp-sync-detach)
                     (lambda (&optional _buffer) (push 'detach sequence)))
                    ((symbol-function 'ebp-sync-attach)
                     (lambda (_client _document _editor-id target)
                       (push 'attach sequence)
                       (with-current-buffer target
                         (setq attached-text (buffer-string)
                               attached-mode major-mode))
                       target))
                    ((symbol-function 'ebp-complete-set-editor-kinds)
                     #'ignore)
                    ((symbol-function 'jetpacs-feature-advertised-p)
                     (lambda (&rest _arguments) nil)))
            (jetpacs-component-catalog--reconcile-authoring-sync
             new-document nil 'client))
          (should (equal (nreverse sequence) '(detach attach)))
          (should (eq attached-mode 'emacs-lisp-mode))
          (should (equal attached-text "live syntax text"))
          (with-current-buffer buffer
            (should (equal (buffer-string) "live syntax text")))
          (should (eq jetpacs-component-catalog--authoring-sync-buffer buffer))
          (should (equal jetpacs-component-catalog--authoring-sync-key key)))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest jetpacs-component-catalog-sync-value-applies-after-attach ()
  "A same-key syntax/value edit applies authored text only after attachment."
  (let* ((synchronized
          (jetpacs-component-authoring-set-at-path
           (jetpacs-component-authoring-default-document "editor")
           '(:document) "doc:jpcatalog/value"))
         (old-document
          (jetpacs-component-authoring-delete-at-path synchronized '(:syntax)))
         (with-syntax
          (jetpacs-component-authoring-set-at-path
           old-document '(:syntax) "elisp"))
         (new-document
          (jetpacs-component-authoring-set-at-path
           with-syntax '(:value) "new authored text"))
         (key (jetpacs-component-catalog--authoring-editor-key old-document))
         (buffer (generate-new-buffer " *catalog value sync*"))
         (jetpacs-component-catalog--authoring-sync-buffer buffer)
         (jetpacs-component-catalog--authoring-sync-key key)
         attached-text)
    (unwind-protect
        (progn
          (jetpacs-component-catalog--configure-authoring-buffer
           buffer old-document)
          (jetpacs-component-catalog--replace-buffer-text buffer "old live text")
          (cl-letf (((symbol-function
                      'jetpacs-component-catalog--sync-available-p)
                     (lambda (&optional _client) t))
                    ((symbol-function 'ebp-sync-buffer)
                     (lambda (&rest _arguments) nil))
                    ((symbol-function 'ebp-sync-detach) #'ignore)
                    ((symbol-function 'ebp-sync-attach)
                     (lambda (_client _document _editor-id target)
                       (with-current-buffer target
                         (setq attached-text (buffer-string)))
                       target))
                    ((symbol-function 'ebp-complete-set-editor-kinds)
                     #'ignore)
                    ((symbol-function 'jetpacs-feature-advertised-p)
                     (lambda (&rest _arguments) nil)))
            (jetpacs-component-catalog--reconcile-authoring-sync
             new-document t 'client))
          (should (equal attached-text "old live text"))
          (with-current-buffer buffer
            (should (equal (buffer-string) "new authored text"))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))))

(ert-deftest jetpacs-component-catalog-ready-without-sync-retains-buffers ()
  "READY without editor.sync detaches both fixtures but keeps volatile text."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (jetpacs-component-catalog--pending-reset-ids
          (make-hash-table :test #'equal))
         (document
          (jetpacs-component-authoring-set-at-path
           (jetpacs-component-authoring-default-document "editor")
           '(:document) "doc:jpcatalog/offline"))
         (key (jetpacs-component-catalog--authoring-editor-key document))
         (fixed (generate-new-buffer " *catalog fixed retained*"))
         (authored (generate-new-buffer " *catalog authored retained*"))
         (jetpacs-component-catalog--sync-buffer fixed)
         (jetpacs-component-catalog--authoring-sync-buffer authored)
         (jetpacs-component-catalog--authoring-sync-key key)
         detached kinds attached)
    (unwind-protect
        (progn
          (with-current-buffer fixed (insert "fixed live text"))
          (jetpacs-component-catalog--configure-authoring-buffer
           authored document)
          (jetpacs-component-catalog--replace-buffer-text
           authored "authored live text")
          (let ((draft (jetpacs-component-catalog--make-draft "editor")))
            (jetpacs-component-catalog--put-draft
             "editor" (plist-put draft :document document)))
          (cl-letf (((symbol-function
                      'jetpacs-component-catalog--sync-available-p)
                     (lambda (&optional _client) nil))
                    ((symbol-function 'ebp-sync-detach)
                     (lambda (&optional buffer)
                       (push (or buffer (current-buffer)) detached)))
                    ((symbol-function 'ebp-sync-attach)
                     (lambda (&rest _arguments) (setq attached t)))
                    ((symbol-function 'ebp-complete-set-editor-kinds)
                     (lambda (document editor-id value)
                       (push (list document editor-id value) kinds)))
                    ((symbol-function 'ebp-client-editor-text)
                     (lambda (&rest _arguments) nil)))
            (jetpacs-component-catalog--on-ready 'client))
          (should-not attached)
          (should (memq fixed detached))
          (should (memq authored detached))
          (should (buffer-live-p fixed))
          (should (buffer-live-p authored))
          (with-current-buffer authored
            (should (equal (buffer-string) "authored live text")))
          (should (eq jetpacs-component-catalog--authoring-sync-buffer
                      authored))
          (should (equal jetpacs-component-catalog--authoring-sync-key key))
          (should (member
                   (list jetpacs-component-catalog--sync-document
                         jetpacs-component-catalog--sync-editor-id nil)
                   kinds))
          (should (member (list (car key) (cdr key) nil) kinds)))
      (dolist (buffer (list fixed authored))
        (when (buffer-live-p buffer) (kill-buffer buffer))))))

(ert-deftest jetpacs-component-catalog-ready-attach-error-keeps-live-text ()
  "A partial READY attach failure is diagnosed without releasing live text."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (jetpacs-component-catalog--pending-reset-ids
          (make-hash-table :test #'equal))
         (document
          (jetpacs-component-authoring-set-at-path
           (jetpacs-component-authoring-default-document "editor")
           '(:document) "doc:jpcatalog/retry"))
         (key (jetpacs-component-catalog--authoring-editor-key document))
         (fixed (generate-new-buffer " *catalog fixed retry*"))
         (authored (generate-new-buffer " *catalog authored retry*"))
         (jetpacs-component-catalog--sync-buffer fixed)
         (jetpacs-component-catalog--authoring-sync-buffer authored)
         (jetpacs-component-catalog--authoring-sync-key key)
         released)
    (unwind-protect
        (progn
          (jetpacs-component-catalog--configure-authoring-buffer
           authored document)
          (jetpacs-component-catalog--replace-buffer-text authored "retry me")
          (let ((draft (jetpacs-component-catalog--make-draft "editor")))
            (jetpacs-component-catalog--put-draft
             "editor" (plist-put draft :document document)))
          (cl-letf (((symbol-function
                      'jetpacs-component-catalog--sync-available-p)
                     (lambda (&optional _client) t))
                    ((symbol-function 'ebp-sync-buffer)
                     (lambda (&rest _arguments) nil))
                    ((symbol-function 'ebp-sync-detach) #'ignore)
                    ((symbol-function 'ebp-sync-attach)
                     (lambda (_client _document editor-id buffer)
                       (if (equal editor-id
                                  jetpacs-component-catalog--sync-editor-id)
                           buffer
                         (jetpacs-component-catalog--replace-buffer-text
                          buffer "partially adopted mirror")
                         (error "synthetic rider failure"))))
                    ((symbol-function 'ebp-complete-set-editor-kinds)
                     #'ignore)
                    ((symbol-function 'ebp-client-editor-text)
                     (lambda (&rest _arguments) nil))
                    ((symbol-function 'jetpacs-feature-advertised-p)
                     (lambda (&rest _arguments) nil))
                    ((symbol-function
                      'jetpacs-component-catalog--release-authoring-sync-buffer)
                     (lambda () (setq released t))))
            (jetpacs-component-catalog--on-ready 'client))
          (should-not released)
          (should (buffer-live-p authored))
          (with-current-buffer authored
            (should (equal (buffer-string) "retry me")))
          (should (plist-get (jetpacs-component-catalog--draft "editor")
                             :error)))
      (dolist (buffer (list fixed authored))
        (when (buffer-live-p buffer) (kill-buffer buffer))))))

(ert-deftest jetpacs-component-catalog-structured-codecs-stay-in-model ()
  "Compound GUI operations round-trip through the closed document validator."
  (let* ((action (jetpacs-component-authoring-default-document "action"))
         (queued
          (jetpacs-component-catalog--edit-document
           action '(:on_tap) "descriptor-policy" '(:value "queue")))
         (dropped
          (jetpacs-component-catalog--edit-document
           queued '(:on_tap) "descriptor-policy" '(:value "drop")))
         (builtin
          (jetpacs-component-catalog--edit-document
           dropped '(:on_tap) "descriptor-builtin-select"
           '(:value "clipboard.copy")))
         (semantic
          (jetpacs-component-catalog--edit-document
           builtin '(:semantics) "literal"
           (list :literal_source
                 (jetpacs-authoring-print '(:name "Specimen") 8192))))
         (editor (jetpacs-component-authoring-default-document "editor"))
         (toolbar
          (jetpacs-component-catalog--edit-document
           editor '(:toolbar) "literal"
           (list :literal_source
                 (jetpacs-authoring-print
                  '[(:label "Item" :snippet "${selection}")] 8192))))
         (on-tap
          (jetpacs-component-catalog--edit-document
           toolbar '(:toolbar 0) "toolbar-op" '(:value "on_tap")))
         (added
          (jetpacs-component-catalog--edit-document
           on-tap '(:toolbar) "vector-add"
           (list :literal_source
                 (jetpacs-authoring-print
                  '(:label "Second" :line "move-down") 8192)))))
    (should (= (plist-get (plist-get (plist-get queued :root) :on_tap)
                          :ttl_s)
               3600))
    (should-not
     (plist-member (plist-get (plist-get dropped :root) :on_tap) :ttl_s))
    (should
     (equal (plist-get (plist-get (plist-get builtin :root) :on_tap)
                       :builtin)
            "clipboard.copy"))
    (should
     (equal (plist-get (plist-get (plist-get semantic :root) :semantics)
                       :name)
            "Specimen"))
    (should
     (equal (plist-get
             (aref (plist-get (plist-get on-tap :root) :toolbar) 0)
             :on_tap)
            '(:action "jpcatalog.activate")))
    (should (= (length (plist-get (plist-get added :root) :toolbar)) 2))))

(ert-deftest jetpacs-component-catalog-presentation-action-has-public-schema ()
  "The projection switch admits injected value and legacy explicit mode."
  (should
   (eq (cdr (assoc "jpcatalog.presentation"
                   jetpacs-component-catalog--verbs))
       'jetpacs-component-catalog--on-presentation))
  (let ((schema (jetpacs-action-schema "jpcatalog.presentation")))
    (should schema)
    (should
     (equal
      (plist-get schema :args)
      '((:name component :type "text" :required t)
        (:name value :type "text")
        (:name mode :type "text"))))
    (should (string-match-p "Preview" (plist-get schema :doc)))
    (should (string-match-p "Visual" (plist-get schema :doc)))
    (should (string-match-p "Lisp" (plist-get schema :doc)))
    (should (string-match-p "Source" (plist-get schema :doc)))))

(ert-deftest jetpacs-component-catalog-root-presentation-has-public-schema ()
  "The root switch admits injected value and no component argument."
  (should
   (eq (cdr (assoc "jpcatalog.root.presentation"
                   jetpacs-component-catalog--verbs))
       'jetpacs-component-catalog--on-root-presentation))
  (let ((schema (jetpacs-action-schema "jpcatalog.root.presentation")))
    (should schema)
    (should
     (equal (plist-get schema :args)
            '((:name value :type "text")
              (:name mode :type "text"))))
    (should (string-match-p "root" (downcase (plist-get schema :doc))))
    (dolist (projection '("Preview" "Visual" "Lisp" "Source"))
      (should (string-match-p projection (plist-get schema :doc))))))

(ert-deftest jetpacs-component-catalog-actions-have-public-metadata ()
  "Every wire-visible catalog verb documents the behavior it owns."
  (dolist (verb (mapcar #'car jetpacs-component-catalog--verbs))
    (let ((schema (jetpacs-action-schema verb)))
      (should schema)
      (should (stringp (plist-get schema :doc)))
      (should-not (string-empty-p (plist-get schema :doc))))))

(ert-deftest jetpacs-component-catalog-visual-booleans-are-switches ()
  "Boolean members are switches on the native boolean edit path."
  (let* ((document (jetpacs-component-authoring-default-document "text-field"))
         (digest (jetpacs-component-authoring-document-digest document))
         (visual (jetpacs-component-catalog-editor-visual
                  "text-field" document digest))
         (switches (jetpacs-component-catalog-test--nodes visual "switch"))
         (by-label (lambda (label)
                     (cl-find label switches
                              :key (lambda (node) (plist-get node :label))
                              :test #'equal)))
         (single-line (funcall by-label "Single Line"))
         (password (funcall by-label "Password"))
         (enabled (funcall by-label "Enabled")))
    ;; No boolean member is left on the tri-state dropdown.
    (should-not
     (cl-find-if
      (lambda (node)
        (cl-find "true" (append (plist-get node :options) nil)
                 :key (lambda (option) (plist-get option :value))
                 :test #'equal))
      (jetpacs-component-catalog-test--nodes visual "dropdown")))
    (should single-line)
    (should (eq (plist-get single-line :checked) t))
    (should password)
    (should (eq (plist-get password :checked) :json-false))
    ;; An absent `enabled' means enabled, and the switch says so.
    (should enabled)
    (should (eq (plist-get enabled :checked) t))
    (dolist (switch switches)
      (let ((descriptor (plist-get switch :on_change)))
        (should (equal (plist-get descriptor :action) "jpcatalog.edit.boolean"))
        (should-not (plist-get (plist-get descriptor :args) :codec))))
    (should (equal (plist-get (plist-get (plist-get password :on_change) :args)
                              :path)
                   ["password"]))
    ;; An optional boolean keeps its explicit "use the default" control.
    (should
     (cl-find-if
      (lambda (node)
        (let ((args (plist-get (plist-get node :on_tap) :args)))
          (and (equal (plist-get args :codec) "unset")
               (equal (plist-get args :path) ["password"]))))
      (jetpacs-component-catalog-test--nodes visual "icon_button")))
    (should (jetpacs-check-profile visual 'app))))

(ert-deftest jetpacs-component-catalog-presence-flag-is-a-switch ()
  "A presence-only flag is a switch whose off position removes the member."
  (let* ((jetpacs-component-catalog--drafts
          (make-hash-table :test #'equal))
         (component "panel")
         (document (jetpacs-component-catalog--document component))
         (digest (jetpacs-component-authoring-document-digest document))
         (visual (jetpacs-component-catalog-editor-visual
                  component document digest))
         (selectable
          (cl-find "Selectable"
                   (jetpacs-component-catalog-test--nodes visual "switch")
                   :key (lambda (node) (plist-get node :label))
                   :test #'equal))
         (args (plist-get (plist-get selectable :on_change) :args))
         refreshed)
    (should selectable)
    (should (eq (plist-get selectable :checked) :json-false))
    (should (equal (plist-get (plist-get selectable :on_change) :action)
                   "jpcatalog.edit.boolean"))
    (should (equal (plist-get args :codec) "injected-presence"))
    (should (equal (plist-get args :path) ["children" 0 "selectable"]))
    (let ((path '(:children 0 :selectable)))
      (should (eq (jetpacs-component-catalog--path-value
                   (jetpacs-component-catalog--edit-document
                    document path "injected-presence" '(:value t))
                   path)
                  t))
      (should-not
       (jetpacs-component-catalog-editor--path-present-p
        (jetpacs-component-catalog--edit-document
         (jetpacs-component-catalog--edit-document
          document path "injected-presence" '(:value t))
         path "injected-presence" '(:value :json-false))
        path))
      (should-error
       (jetpacs-component-catalog--edit-document
        document path "injected-presence" '(:value "true"))
       :type 'jetpacs-component-catalog-edit-error))
    (cl-letf (((symbol-function
                'jetpacs-component-catalog--live-edit-context-p)
               (lambda (&rest _arguments) t))
              ((symbol-function
                'jetpacs-component-catalog--defer-authoring-refresh)
               (lambda (surface ids) (setq refreshed (list surface ids)))))
      (should
       (eq (jetpacs-component-catalog--on-boolean-edit
            (append (list :value t) (copy-tree args))
            '(:surface "app:jpcatalog"))
           'accepted))
      (should (eq (plist-get
                   (aref (plist-get
                          (plist-get (jetpacs-component-catalog--document
                                      component)
                                     :root)
                          :children)
                         0)
                   :selectable)
                  t))
      ;; The next digest addresses the updated draft; a control that claims
      ;; some other codec collapses to the plain boolean write.
      (let ((next (jetpacs-component-authoring-document-digest
                   (jetpacs-component-catalog--document component))))
        (should
         (eq (jetpacs-component-catalog--on-boolean-edit
              (list :component component :digest next
                    :path ["children" 1 "enabled"] :codec "lisp"
                    :value :json-false)
              '(:surface "app:jpcatalog"))
             'accepted))
        (should (eq (plist-get
                     (aref (plist-get
                            (plist-get (jetpacs-component-catalog--document
                                        component)
                                       :root)
                            :children)
                           1)
                     :enabled)
                    :json-false))))
    (should (equal refreshed '("app:jpcatalog" nil)))))

(ert-deftest jetpacs-component-catalog-compound-members-are-disclosures ()
  "Compound members fold into collapsibles seeded open only when authored."
  (let* ((document (jetpacs-component-authoring-default-document "editor"))
         (digest (jetpacs-component-authoring-document-digest document))
         (visual (jetpacs-component-catalog-editor-visual
                  "editor" document digest))
         (collapsibles
          (jetpacs-component-catalog-test--nodes visual "collapsible"))
         (header-label
          (lambda (node)
            (let ((header (plist-get node :header)))
              (or (plist-get header :text)
                  (plist-get (aref (plist-get header :children) 0) :text)))))
         (by-label (lambda (label)
                     (cl-find label collapsibles
                              :key header-label :test #'equal)))
         (on-save (funcall by-label "On Save"))
         (toolbar (funcall by-label "Toolbar"))
         (semantics (funcall by-label "Semantics")))
    (should (>= (length collapsibles) 7))
    (dolist (label '("On Save" "On Enter" "Toolbar" "Directional padding"
                     "Corner" "Border" "Semantics"))
      (should (funcall by-label label))
      (should-not
       (cl-find label (jetpacs-component-catalog-test--nodes
                       visual "jetpacs.panel")
                :key (lambda (node) (plist-get node :label))
                :test #'equal)))
    ;; Authored members open; defaulted members start folded and say so.
    (should (eq (plist-get on-save :collapsed) :json-false))
    (should (eq (plist-get toolbar :collapsed) t))
    (should (eq (plist-get semantics :collapsed) t))
    (should (equal (plist-get (aref (plist-get (plist-get toolbar :header)
                                               :children)
                                    1)
                              :text)
                   "Default"))
    (should (equal (plist-get (aref (plist-get (plist-get on-save :header)
                                               :children)
                                    1)
                              :text)
                   "Authored"))
    ;; Every disclosure id is catalog-owned and unique.
    (let ((ids (mapcar (lambda (node) (plist-get node :id)) collapsibles)))
      (should (= (length ids) (length (delete-dups (copy-sequence ids)))))
      (dolist (id ids)
        (should (string-prefix-p
                 jetpacs-component-catalog-editor--reserved-id-prefix id))))
    ;; The section panels around the fixed nodes are still panels.
    (should (cl-find "Root component"
                     (jetpacs-component-catalog-test--nodes
                      visual "jetpacs.panel")
                     :key (lambda (node) (plist-get node :label))
                     :test #'equal))
    (should (jetpacs-check-profile visual 'app))))

(provide 'jetpacs-component-catalog-test)
;;; jetpacs-component-catalog-test.el ends here
