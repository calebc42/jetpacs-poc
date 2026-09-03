;;; jetpacs-component-catalog.el --- Jetpacs component reference -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A separate, Elisp-authored literate reference for Jetpacs' own design
;; language.  It coexists with the Glasspane Material 3 Catalog and projects
;; each implemented component's live Preview, exact builder source, and
;; canonical EBP through the real negotiation, action, and state paths.  The
;; editable specimen on each page has one inert Lisp-data authority projected
;; as Preview, schema-driven Visual controls, or canonical Lisp; no authored
;; form is evaluated.  Home applies the same four-mode language read-only to
;; the catalog program: its live index, derived structure, inert manifest, and
;; the curated exact forms that implement drill-down and projection dispatch.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-authoring)
(require 'jetpacs-buffer)
(require 'jetpacs-components)
(require 'jetpacs-component-authoring)
(require 'jetpacs-component-catalog-actions)
(require 'jetpacs-component-catalog-editor)
(require 'jetpacs-catalog-inspection)
(require 'jetpacs-elisp-source)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-apps)
(require 'ebp-sync)
(require 'ebp-complete)
(require 'jetpacs-design-lab)

(defconst jetpacs-component-catalog-owner "jpcatalog"
  "Owner of the Jetpacs Components catalog surface and actions.")

(defconst jetpacs-component-catalog-title "Jetpacs Components"
  "User-facing title and app label for the Jetpacs-owned catalog.")

(defvar jetpacs-component-catalog--action-count 0
  "Number of accepted Action demonstrations in this Emacs session.")

(defvar jetpacs-component-catalog--choice t
  "Current Emacs-owned value of the interactive Choice demonstration.")

(defvar jetpacs-component-catalog--tabs-value "preview"
  "Current Emacs-owned value of the interactive Tabs demonstration.")

(defvar jetpacs-component-catalog--tabs-scrollable-value "overview"
  "Current Emacs-owned value of the scrollable Tabs demonstration.")

(defvar jetpacs-component-catalog--section-value "overview"
  "Current Emacs-owned heading selected by the section navigator demo.")

(defconst jetpacs-component-catalog--section-outline
  '(("Overview" "overview" 1
     "What this component controls and why the selected value stays in Emacs.")
    ("Anatomy" "anatomy" 2
     "A pinned navigation strip, hierarchical labels, and stable section identities.")
    ("Behavior" "behavior" 2
     "Selection dispatches a command; the rebuilt document targets one keyed heading.")
    ("Guidance" "guidance" 1
     "Use levels to communicate hierarchy without inventing a second content tree.")
    ("Accessibility" "accessibility" 2
     "Each destination is exposed as a heading with its authored level.")
    ("Examples" "examples" 2
     "The catalog itself demonstrates controlled selection and command scrolling."))
  "Ordered hierarchical content used by the section navigator fixture.")

(defvar jetpacs-component-catalog--text-change-count 0
  "Number of non-secret Text Field change actions accepted this session.")

(defvar jetpacs-component-catalog--text-submit-count 0
  "Number of non-secret Text Field submissions accepted this session.")

(defvar jetpacs-component-catalog--last-text-submit nil
  "Last non-secret Text Field value submitted to the catalog.")

(defvar jetpacs-component-catalog--secure-submit-count 0
  "Number of secure Text Field submissions accepted this session.")

(defvar jetpacs-component-catalog--last-secret-length nil
  "Scalar length of the latest secure demo; never the secret itself.")

(defvar jetpacs-component-catalog--editor-state-count 0
  "Number of local Editor draft publications accepted this session.")

(defvar jetpacs-component-catalog--editor-save-count 0
  "Number of local Editor save actions accepted this session.")

(defvar jetpacs-component-catalog--editor-enter-count 0
  "Number of local single-line Editor enter actions accepted this session.")

(defvar jetpacs-component-catalog--last-editor-preview nil
  "Bounded non-secret preview of the latest local Editor value.")

(defvar jetpacs-component-catalog--last-editor-length nil
  "Character length of the latest local Editor value.")

(defconst jetpacs-component-catalog--sync-document
  "doc:jpcatalog/synchronized-editor"
  "Stable EBP document identifier for the in-memory synchronization fixture.")

(defconst jetpacs-component-catalog--sync-editor-id
  "jpcatalog-editor-sync"
  "Canonical editor node identifier for the synchronization fixture.")

(defconst jetpacs-component-catalog--sync-seed
  "; Synchronized Jetpacs tooling\n(defun jpcatalog-demo ()\n  (message \"%s\" undefined-value))\n\njpc"
  "Initial non-secret text for the synchronization fixture.")

(defvar jetpacs-component-catalog--sync-buffer nil
  "Process-volatile buffer backing the synchronized Editor demonstration.")

(defvar jetpacs-component-catalog--completion-doc-buffer nil
  "Process-volatile documentation buffer for the catalog completion fixture.")

(defvar jetpacs-component-catalog--sync-save-count 0
  "Number of synchronized Editor save actions accepted this session.")

(defvar jetpacs-component-catalog--last-sync-preview nil
  "Bounded preview of the latest synchronized Editor save value.")

(defvar jetpacs-component-catalog--presentation-modes
  (make-hash-table :test #'equal)
  "Component id to process-local Preview, Visual, Lisp, or Source symbol.")

(defvar jetpacs-component-catalog--root-presentation-mode 'preview
  "Process-local Preview, Visual, Lisp, or Source mode for catalog Home.")

(defvar jetpacs-component-catalog--drafts (make-hash-table :test #'equal)
  "Component id to process-local authoring draft state.")

(defconst jetpacs-component-catalog--max-traces 24
  "Maximum redacted action traces retained for one specimen.")

(defvar jetpacs-component-catalog--authoring-sync-buffer nil
  "Process-volatile buffer owned by the editable Editor specimen.")

(defvar jetpacs-component-catalog--authoring-sync-key nil
  "Current (DOCUMENT . EDITOR-ID) owned by the editable Editor specimen.")

(defvar jetpacs-component-catalog--authoring-state-id nil
  "Current editable specimen id subscribed on the catalog surface.")

(defvar jetpacs-component-catalog--pending-reset-ids
  (make-hash-table :test #'equal)
  "Surface to stateful ids awaiting an applied draft-reset snapshot.")

(defconst jetpacs-component-catalog--fixture-ids
  '(("choice" "jpcatalog-choice" "jpcatalog-choice-disabled")
    ("tabs" "jpcatalog-tabs-demo" "jpcatalog-tabs-disabled"
     "jpcatalog-tabs-scrollable" "jpcatalog-tabs-navigator"
     "jpcatalog-tabs-adaptive" "jpcatalog-tabs-legacy-scrollable")
    ("section-navigator" "jpcatalog-section-navigator-demo")
    ("text-field" "jpcatalog-text-live" "jpcatalog-text-code"
     "jpcatalog-text-filled" "jpcatalog-text-error" "jpcatalog-text-phone"
     "jpcatalog-password" "jpcatalog-text-disabled")
    ("editor" "jpcatalog-editor-live" "jpcatalog-editor-enter"
     "jpcatalog-editor-sync" "jpcatalog-editor-read-only"
     "jpcatalog-editor-disabled" "jpcatalog-editor-chromeless"))
  "Per-component ids already owned by each full reference fixture.")

(defconst jetpacs-component-catalog--edit-codecs
  '("unset" "string" "number" "integer" "boolean" "presence-boolean"
    "injected-boolean" "injected-presence" "enum" "lisp" "literal"
    "descriptor-remote" "descriptor-builtin"
    "descriptor-builtin-select" "descriptor-policy" "toolbar-op"
    "toolbar-long-op" "vector-add" "vector-delete" "vector-move"
    "tabs-option-value" "tabs-option-delete" "tabs-presentation"
    "section-option-value" "section-option-delete")
  "Closed edit operations accepted from catalog-generated controls.")

(define-error 'jetpacs-component-catalog-edit-error
  "Jetpacs component catalog edit error")

(defconst jetpacs-component-catalog--projection-max-chars
  jetpacs-elisp-source-max-chars
  "Maximum source or canonical-EBP characters shown by one projection.")

(defconst jetpacs-component-catalog--components
  '((:id "action" :name "Action"
     :purpose "One explicit, full-width command target."
     :category "COMMANDS"
     :builder jetpacs-component-catalog--action-screen)
    (:id "choice" :name "Choice"
     :purpose "A binary setting with reconciled boolean state."
     :category "SELECTION"
     :builder jetpacs-component-catalog--choice-screen)
    (:id "tabs" :name "Tabs"
     :purpose "A controlled projection switcher with native tab semantics."
     :category "SELECTION"
     :builder jetpacs-component-catalog--tabs-screen)
    (:id "section-navigator" :name "Section Navigator"
     :purpose "A hierarchical command-driven table of contents."
     :category "NAVIGATION"
     :builder jetpacs-component-catalog--section-navigator-screen)
    (:id "panel" :name "Panel"
     :purpose "Labeled containment without descendant merging."
     :category "STRUCTURE"
     :builder jetpacs-component-catalog--panel-screen)
    (:id "text-field" :name "Text Field"
     :purpose "Native text entry with reconciled EBP state."
     :category "TEXT EDITING"
     :builder jetpacs-component-catalog--text-field-screen)
    (:id "editor" :name "Editor"
     :purpose "Local multiline editing on the canonical EBP node."
     :category "TEXT EDITING"
     :builder jetpacs-component-catalog--editor-screen))
  "Implemented component metadata and each exact Preview builder.")

(defconst jetpacs-component-catalog--root-source-forms
  '((variable jetpacs-component-catalog--components "COMPONENT REGISTRY")
    (function jetpacs-component-catalog--root-screen "ROOT DISPATCHER")
    (function jetpacs-component-catalog--home-screen "HOME PREVIEW")
    (function jetpacs-component-catalog--root-visual-screen
              "ROOT VISUAL INSPECTOR")
    (function jetpacs-component-catalog--root-lisp-screen
              "ROOT LISP INSPECTOR")
    (function jetpacs-component-catalog--root-source-screen
              "ROOT SOURCE INSPECTOR")
    (function jetpacs-component-catalog--open-action "DRILL DESCRIPTOR")
    (function jetpacs-component-catalog--on-open "DRILL HANDLER")
    (function jetpacs-component-catalog--detail-screen "DETAIL DISPATCHER")
    (function jetpacs-component-catalog--projection-value
              "PROJECTION VALUE DECODER")
    (function jetpacs-component-catalog--on-presentation
              "COMPONENT PROJECTION HANDLER")
    (function jetpacs-component-catalog--on-root-presentation
              "ROOT PROJECTION HANDLER")
    (function jetpacs-component-catalog-register "APP REGISTRATION"))
  "Ordered exact authored forms comprising the catalog's program path.")

(defun jetpacs-component-catalog--component (id)
  "Return implemented component metadata for string ID, or nil."
  (and (stringp id)
       (cl-find id jetpacs-component-catalog--components
                :key (lambda (component) (plist-get component :id))
                :test #'equal)))

(defun jetpacs-component-catalog--presentation-mode (id)
  "Return ID's remembered presentation, defaulting to `preview'."
  (or (gethash id jetpacs-component-catalog--presentation-modes) 'preview))

(defun jetpacs-component-catalog--make-draft (component)
  "Return fresh process-local authoring state for COMPONENT."
  (list :document
        (jetpacs-component-authoring-default-document component)
        :error nil :lisp-buffer nil :armed nil :traces nil))

(defun jetpacs-component-catalog--draft (component)
  "Return COMPONENT's draft, lazily seeding its authored default."
  (or (gethash component jetpacs-component-catalog--drafts)
      (let ((draft (jetpacs-component-catalog--make-draft component)))
        (puthash component draft jetpacs-component-catalog--drafts)
        draft)))

(defun jetpacs-component-catalog--document (component)
  "Return COMPONENT's current valid canonical authoring document."
  (plist-get (jetpacs-component-catalog--draft component) :document))

(defun jetpacs-component-catalog--draft-digest (component)
  "Return COMPONENT's current canonical authoring digest."
  (jetpacs-component-authoring-document-digest
   (jetpacs-component-catalog--document component)))

(defun jetpacs-component-catalog--put-draft (component draft)
  "Store COMPONENT's DRAFT and return it."
  (puthash component draft jetpacs-component-catalog--drafts)
  draft)

(defun jetpacs-component-catalog--error-text (err)
  "Return a bounded authoring diagnostic for ERR without source text."
  (let ((known '(jetpacs-component-catalog-edit-error
                 jetpacs-component-authoring-error
                 jetpacs-component-authoring-schema-error
                 jetpacs-authoring-error jetpacs-authoring-read-error
                 jetpacs-authoring-limit-error)))
    (truncate-string-to-width
     (if (memq (car-safe err) known)
         (error-message-string err)
       (format "Edit rejected (%s)" (jetpacs-error-label err)))
     240 nil nil "…")))

(defun jetpacs-component-catalog--set-draft-error
    (component err &optional lisp-buffer)
  "Attach bounded ERR to COMPONENT without replacing its valid document.
LISP-BUFFER, when non-nil, remains visible for correction."
  (let ((draft (jetpacs-component-catalog--draft component)))
    (setq draft (plist-put draft :error
                           (jetpacs-component-catalog--error-text err)))
    (when lisp-buffer
      (setq draft (plist-put draft :lisp-buffer lisp-buffer)))
    (jetpacs-component-catalog--put-draft component draft)))

(defun jetpacs-component-catalog--replace-document (component document)
  "Replace COMPONENT's last valid draft with normalized DOCUMENT.
An edit always clears transient errors, invalid Lisp, traces, and arming."
  (let* ((normalized
          (jetpacs-component-authoring-normalize-document document))
         (draft (jetpacs-component-catalog--draft component)))
    (unless (equal component (plist-get normalized :component))
      (error "Component identity is immutable on an open catalog page"))
    (setq draft (plist-put draft :document normalized))
    (setq draft (plist-put draft :error nil))
    (setq draft (plist-put draft :lisp-buffer nil))
    (setq draft (plist-put draft :armed nil))
    (setq draft (plist-put draft :traces nil))
    (jetpacs-component-catalog--put-draft component draft)
    normalized))

(defun jetpacs-component-catalog--component-from-surface (surface)
  "Return the component id at SURFACE's current detail screen, or nil."
  (when (stringp surface)
    (let ((screen (car (jetpacs-chrome-stack surface))))
      (and (stringp screen)
           (string-prefix-p "component-" screen)
           (substring screen (length "component-"))))))

(defun jetpacs-component-catalog--live-edit-context-p
    (component digest params)
  "Return non-nil when PARAMS still addresses COMPONENT at DIGEST."
  (and (stringp component)
       (stringp digest)
       (jetpacs-component-catalog--component component)
       (equal component
              (jetpacs-component-catalog--component-from-surface
               (plist-get params :surface)))
       (not (jetpacs-event-stale-p params))
       (equal digest (jetpacs-component-catalog--draft-digest component))))

(defun jetpacs-component-catalog--edit-error (message)
  "Signal a redaction-safe catalog edit error carrying fixed MESSAGE."
  (signal 'jetpacs-component-catalog-edit-error (list message)))

(defun jetpacs-component-catalog--decode-path (wire-path)
  "Decode bounded WIRE-PATH without interning receiver-controlled symbols."
  (unless (and (vectorp wire-path) (<= (length wire-path) 32))
    (jetpacs-component-catalog--edit-error
     "The edit path must be a bounded array"))
  (cl-loop
   for step across wire-path
   collect
   (cond
    ((and (integerp step) (<= 0 step 255)) step)
    ((and (stringp step) (<= (string-bytes step) 64))
     (let ((key (intern-soft (concat ":" step))))
       (unless (keywordp key)
         (jetpacs-component-catalog--edit-error
          "The edit path contains an unknown member"))
       key))
    (t
     (jetpacs-component-catalog--edit-error
      "The edit path contains an invalid step")))))

(defun jetpacs-component-catalog--path-value (document path)
  "Return DOCUMENT's root-relative value at PATH, or nil when absent."
  (let ((value (plist-get document :root)))
    (dolist (step path value)
      (setq value
            (if (integerp step)
                (and (vectorp value) (< step (length value))
                     (aref value step))
              (and (listp value) (plist-get value step)))))))

(defun jetpacs-component-catalog--path-present-p (document path)
  "Return non-nil when root-relative PATH is present in DOCUMENT."
  (if (null path)
      t
    (let* ((parent (jetpacs-component-catalog--path-value
                    document (butlast path)))
           (last (car (last path))))
      (if (integerp last)
          (and (vectorp parent) (< last (length parent)))
        (and (listp parent) (plist-member parent last))))))

(defun jetpacs-component-catalog--plist-without (plist keys)
  "Return PLIST without any member in KEYS, preserving all other order."
  (cl-loop for (key value) on plist by #'cddr
           unless (memq key keys) append (list key value)))

(defun jetpacs-component-catalog--edit-value (args maximum)
  "Return ARGS' injected string value bounded to MAXIMUM UTF-8 bytes."
  (let ((value (plist-get args :value)))
    (unless (and (plist-member args :value) (stringp value)
                 (<= (string-bytes value) maximum))
      (jetpacs-component-catalog--edit-error
       "This control requires a bounded text value"))
    (substring-no-properties value)))

(defun jetpacs-component-catalog--parse-number (source integerp)
  "Parse finite numeric SOURCE, requiring an integer when INTEGERP."
  (unless (string-match-p
           (if integerp
               "\\`[-+]?[0-9]+\\'"
             "\\`[-+]?\\(?:[0-9]+\\(?:\\.[0-9]*\\)?\\|\\.[0-9]+\\)\\(?:[eE][-+]?[0-9]+\\)?\\'")
           source)
    (jetpacs-component-catalog--edit-error
     (if integerp "Enter a whole number" "Enter a finite number")))
  (let ((number (string-to-number source)))
    (unless (and (numberp number) (= number number)
                 (<= (abs number) 9007199254740991)
                 (or (not integerp) (integerp number)))
      (jetpacs-component-catalog--edit-error
       "The number is outside the supported range"))
    number))

(defun jetpacs-component-catalog--builtin-default (name)
  "Return a valid fresh descriptor for builtin NAME, or signal."
  (pcase name
    ("view.switch" (list :builtin name :view "home"))
    ("variant.switch" (list :builtin name :id "variant"))
    ("surface.open" (list :builtin name :surface "app:jpcatalog"))
    ("clipboard.copy" (list :builtin name :text "Specimen text"))
    ("share.send" (list :builtin name :text "Specimen text"))
    ("companion.settings.open" (list :builtin name))
    ("trigger.fire" (list :builtin name :id "jpcatalog-trigger"))
    ("dialog.submit" (list :builtin name))
    ("dialog.dismiss" (list :builtin name))
    (_ (jetpacs-component-catalog--edit-error
        "The selected builtin is not supported"))))

(defun jetpacs-component-catalog--descriptor-with-policy
    (descriptor policy)
  "Return remote DESCRIPTOR using POLICY and a valid policy envelope."
  (unless (and (listp descriptor) (plist-member descriptor :action))
    (jetpacs-component-catalog--edit-error
     "Offline policy applies only to a remote action"))
  (unless (member policy '("__unset" "drop" "queue" "wake"))
    (jetpacs-component-catalog--edit-error
     "The selected offline policy is not supported"))
  (let* ((ttl (plist-get descriptor :ttl_s))
         (dedupe-present (plist-member descriptor :dedupe))
         (dedupe (plist-get descriptor :dedupe))
         (result (jetpacs-component-catalog--plist-without
                  (copy-tree descriptor) '(:when_offline :dedupe :ttl_s))))
    (unless (equal policy "__unset")
      (setq result (plist-put result :when_offline policy)))
    (when (member policy '("queue" "wake"))
      (setq result (plist-put result :ttl_s
                              (if (and (integerp ttl) (<= 1 ttl 604800))
                                  ttl 3600)))
      (when dedupe-present
        (setq result (plist-put result :dedupe dedupe))))
    result))

(defun jetpacs-component-catalog--toolbar-operation (item operation menu-p)
  "Return toolbar ITEM with OPERATION selected.
When MENU-P is nil, reject the nested-menu operation."
  (let ((allowed (if menu-p
                     (plist-get jetpacs-toolbar-contract :ops)
                   (remove "menu" (plist-get jetpacs-toolbar-contract :ops)))))
    (unless (member operation allowed)
      (jetpacs-component-catalog--edit-error
       "The selected toolbar operation is not supported"))
    (let ((result
           (jetpacs-component-catalog--plist-without
            (copy-tree item) '(:snippet :on_tap :menu :command :line))))
      (append
       result
       (pcase operation
         ("snippet" '(:snippet "${selection}"))
         ("on_tap" '(:on_tap (:action "jpcatalog.activate")))
         ("menu" '(:menu [(:label "Item" :snippet "${selection}")]))
         ("command" '(:command "indent-region"))
         ("line" '(:line "move-down")))))))

(defun jetpacs-component-catalog--vector-edit
    (document path codec args)
  "Apply vector CODEC from ARGS to DOCUMENT at root-relative PATH."
  (let ((items (jetpacs-component-catalog--path-value document path))
        (index (plist-get args :index)))
    (unless (vectorp items)
      (jetpacs-component-catalog--edit-error
       "The edit target is not an ordered collection"))
    (pcase codec
      ("vector-add"
       (let ((source (plist-get args :literal_source)))
         (unless (stringp source)
           (jetpacs-component-catalog--edit-error
            "The collection template is missing"))
         (jetpacs-component-authoring-set-at-path
          document path
          (vconcat items
                   (vector (jetpacs-authoring-read-one source 8192))))))
      ("vector-delete"
       (unless (and (integerp index) (<= 0 index) (< index (length items)))
         (jetpacs-component-catalog--edit-error
          "The collection item no longer exists"))
       (jetpacs-component-authoring-set-at-path
        document path
        (vconcat (seq-subseq items 0 index)
                 (seq-subseq items (1+ index)))))
      ("vector-move"
       (let* ((direction (plist-get args :direction))
              (target (and (integerp index)
                           (+ index (if (equal direction "up") -1 1)))))
         (unless (and (integerp index) (member direction '("up" "down"))
                      (<= 0 index) (< index (length items)))
           (jetpacs-component-catalog--edit-error
            "The collection move is invalid"))
         (if (not (and (<= 0 target) (< target (length items))))
             document
           (let ((copy (copy-sequence items)))
             (aset copy index (aref items target))
             (aset copy target (aref items index))
             (jetpacs-component-authoring-set-at-path document path copy))))))))

(defun jetpacs-component-catalog--edit-document
    (document path codec args)
  "Return DOCUMENT after applying closed root-relative PATH edit CODEC.
ARGS supplies only bounded receiver injection or catalog-authored literals."
  (pcase codec
    ("unset"
     (jetpacs-component-authoring-delete-at-path document path))
    ("string"
     (jetpacs-component-authoring-set-at-path
      document path (jetpacs-component-catalog--edit-value args 8192)))
    ("number"
     (jetpacs-component-authoring-set-at-path
      document path
      (jetpacs-component-catalog--parse-number
       (jetpacs-component-catalog--edit-value args 128) nil)))
    ("integer"
     (jetpacs-component-authoring-set-at-path
      document path
      (jetpacs-component-catalog--parse-number
       (jetpacs-component-catalog--edit-value args 128) t)))
    ((or "boolean" "presence-boolean")
     (let ((value (jetpacs-component-catalog--edit-value args 16)))
       (pcase value
         ("__unset"
          (jetpacs-component-authoring-delete-at-path document path))
         ("true"
          (jetpacs-component-authoring-set-at-path document path t))
         ("false"
          (if (equal codec "boolean")
              (jetpacs-component-authoring-set-at-path
               document path :json-false)
            (jetpacs-component-catalog--edit-error
             "This presence-only flag can be true or default")))
         (_ (jetpacs-component-catalog--edit-error
             "The selected boolean value is invalid")))))
    ("injected-boolean"
     (let ((value (plist-get args :value)))
       (unless (memq value '(t :json-false))
         (jetpacs-component-catalog--edit-error
          "This toggle requires a boolean value"))
       (jetpacs-component-authoring-set-at-path document path value)))
    ("injected-presence"
     (let ((value (plist-get args :value)))
       (unless (memq value '(t :json-false))
         (jetpacs-component-catalog--edit-error
          "This toggle requires a boolean value"))
       (if (eq value t)
           (jetpacs-component-authoring-set-at-path document path t)
         (jetpacs-component-authoring-delete-at-path document path))))
    ("enum"
     (let ((value (jetpacs-component-catalog--edit-value args 256)))
       (if (equal value "__unset")
           (jetpacs-component-authoring-delete-at-path document path)
         (jetpacs-component-authoring-set-at-path document path value))))
    ("lisp"
     (jetpacs-component-authoring-set-at-path
      document path
      (jetpacs-authoring-read-one
       (jetpacs-component-catalog--edit-value args 8192) 8192)))
    ("literal"
     (let ((source (plist-get args :literal_source)))
       (unless (stringp source)
         (jetpacs-component-catalog--edit-error
          "The catalog edit template is missing"))
       (jetpacs-component-authoring-set-at-path
        document path (jetpacs-authoring-read-one source 8192))))
    ("descriptor-remote"
     (jetpacs-component-authoring-set-at-path
      document path '(:action "jpcatalog.activate")))
    ("descriptor-builtin"
     (jetpacs-component-authoring-set-at-path
      document path '(:builtin "companion.settings.open")))
    ("descriptor-builtin-select"
     (jetpacs-component-authoring-set-at-path
      document path
      (jetpacs-component-catalog--builtin-default
       (jetpacs-component-catalog--edit-value args 128))))
    ("descriptor-policy"
     (jetpacs-component-authoring-set-at-path
      document path
      (jetpacs-component-catalog--descriptor-with-policy
       (jetpacs-component-catalog--path-value document path)
       (jetpacs-component-catalog--edit-value args 16))))
    ((or "toolbar-op" "toolbar-long-op")
     (jetpacs-component-authoring-set-at-path
      document path
      (jetpacs-component-catalog--toolbar-operation
       (jetpacs-component-catalog--path-value document path)
       (jetpacs-component-catalog--edit-value args 32)
       (equal codec "toolbar-op"))))
    ("tabs-option-value"
     (let ((index (plist-get args :index)))
       (unless (equal path (list :options index :value))
         (jetpacs-component-catalog--edit-error
          "The tab option value path is invalid"))
       (jetpacs-component-authoring-set-tab-option-value
        document index (jetpacs-component-catalog--edit-value args 8192))))
    ("tabs-option-delete"
     (let ((index (plist-get args :index)))
       (unless (equal path '(:options))
         (jetpacs-component-catalog--edit-error
          "The tab option collection path is invalid"))
       (jetpacs-component-authoring-delete-tab-option document index)))
    ("tabs-presentation"
     (unless (equal path '(:variant))
       (jetpacs-component-catalog--edit-error
        "The Tabs presentation path is invalid"))
     (jetpacs-component-authoring-set-tabs-presentation
      document (jetpacs-component-catalog--edit-value args 32)))
    ("section-option-value"
     (let ((index (plist-get args :index)))
       (unless (equal path (list :options index :value))
         (jetpacs-component-catalog--edit-error
          "The section value path is invalid"))
       (jetpacs-component-authoring-set-section-option-value
        document index (jetpacs-component-catalog--edit-value args 8192))))
    ("section-option-delete"
     (let ((index (plist-get args :index)))
       (unless (equal path '(:options))
         (jetpacs-component-catalog--edit-error
          "The section collection path is invalid"))
       (jetpacs-component-authoring-delete-section-option document index)))
    ((or "vector-add" "vector-delete" "vector-move")
     (jetpacs-component-catalog--vector-edit document path codec args))
    (_ (jetpacs-component-catalog--edit-error
        "The requested edit operation is not supported"))))

(defun jetpacs-component-catalog--stateful-specimen-node-p (node)
  "Return non-nil when authored NODE owns resettable input state."
  (and (jetpacs-root-node-p node)
       (not (eq (plist-get node :password) t))
       (or (jetpacs-stateful-node-p node)
           (equal (plist-get node :t) "jetpacs.choice"))))

(defun jetpacs-component-catalog--collect-stateful-ids (value)
  "Return distinct resettable state ids structurally present in VALUE.
Action descriptors and opaque application values are not traversed."
  (let (ids)
    (cl-labels
        ((walk
          (member)
          (cond
           ((vectorp member) (mapc #'walk member))
           ((and (listp member) (keywordp (car-safe member)))
            (when (and (jetpacs-component-catalog--stateful-specimen-node-p
                        member)
                       (stringp (plist-get member :id)))
              (push (plist-get member :id) ids))
            (cl-loop
             for (key child) on member by #'cddr
             unless (or (memq key '(:args :meta :value :semantics :toolbar))
                        (and (keywordp key)
                             (member (substring (symbol-name key) 1)
                                     jetpacs-action-hook-keys)))
             do (walk child)))
           ((proper-list-p member) (mapc #'walk member)))))
      (walk value))
    (delete-dups (nreverse ids))))

(defun jetpacs-component-catalog--specimen-node-for-document (document)
  "Compile DOCUMENT's specimen node without applying action instrumentation."
  (jetpacs-component-authoring-compile-document document))

(defun jetpacs-component-catalog--projection-stateful-ids
    (component document)
  "Return stateful GUI control ids for COMPONENT's current DOCUMENT projection."
  (let ((mode (jetpacs-component-catalog--presentation-mode component))
        (digest (jetpacs-component-authoring-document-digest document)))
    (pcase mode
      ('visual
       (jetpacs-component-catalog--collect-stateful-ids
        (jetpacs-component-catalog-editor-visual
         component document digest)))
      ('lisp
       (jetpacs-component-catalog--collect-stateful-ids
        (jetpacs-component-catalog-editor-lisp
         component document digest nil)))
      (_ nil))))

(defun jetpacs-component-catalog--state-seed (node)
  "Return NODE's authored input seed and compatibility envelope."
  (when (jetpacs-root-node-p node)
    (pcase (plist-get node :t)
      ("jetpacs.choice"
       (list :t "jetpacs.choice" :id (plist-get node :id)
             :checked (plist-get node :checked)))
      ("text_input"
       (list :t "text_input" :id (plist-get node :id)
             :value (plist-get node :value)
             :single_line (plist-get node :single_line)
             :filter (plist-get node :filter)
             :max_length (plist-get node :max_length)
             :password (plist-get node :password)))
      ("editor"
       (list :t "editor" :id (plist-get node :id)
             :value (plist-get node :value)
             :single_line (plist-get node :single_line)
             :publish_state (plist-get node :publish_state)
             :document (plist-get node :document)))
      (_ nil))))

(defun jetpacs-component-catalog--edit-reset-ids
    (component old-document new-document input-id reset-all)
  "Return valid reset ids after changing COMPONENT's document.
OLD-DOCUMENT and NEW-DOCUMENT identify specimen seed changes.  INPUT-ID is
the committing GUI control.  RESET-ALL intentionally clears every stateful
control in the current projection."
  (let* ((old-node
          (jetpacs-component-catalog--specimen-node-for-document old-document))
         (new-node
          (jetpacs-component-catalog--specimen-node-for-document new-document))
         (specimen-ids
          (jetpacs-component-catalog--collect-stateful-ids new-node))
         (projection-ids
          (jetpacs-component-catalog--projection-stateful-ids
           component new-document))
         (present (delete-dups (append specimen-ids projection-ids)))
         ids)
    (if reset-all
        (setq ids present)
      (when (and (stringp input-id) (member input-id projection-ids))
        (push input-id ids))
      (when (and (jetpacs-component-catalog--stateful-specimen-node-p new-node)
                 (equal (plist-get old-node :id) (plist-get new-node :id))
                 (not (equal (jetpacs-component-catalog--state-seed old-node)
                             (jetpacs-component-catalog--state-seed new-node))))
        (push (plist-get new-node :id) ids)))
    (sort (delete-dups (delq nil ids)) #'string<)))

(defun jetpacs-component-catalog--current-resettable-ids (component)
  "Return resettable ids emitted by COMPONENT's current non-Source screen."
  (unless (eq (jetpacs-component-catalog--presentation-mode component) 'source)
    (let ((document (jetpacs-component-catalog--document component)))
      (delete-dups
       (append
        (jetpacs-component-catalog--collect-stateful-ids
         (jetpacs-component-catalog--specimen-node-for-document document))
        (jetpacs-component-catalog--projection-stateful-ids
         component document))))))

(defun jetpacs-component-catalog--push-pending-reset (surface)
  "Push SURFACE once with every still-valid pending input reset."
  (when-let* ((component
               (jetpacs-component-catalog--component-from-surface surface))
              (pending (gethash surface
                                jetpacs-component-catalog--pending-reset-ids)))
    (let* ((valid (jetpacs-component-catalog--current-resettable-ids component))
           (ids (seq-filter (lambda (id) (member id valid)) pending)))
      (cond
       ((eq (jetpacs-component-catalog--presentation-mode component) 'source)
        nil)
       ((null ids)
        (remhash surface jetpacs-component-catalog--pending-reset-ids)
        (jetpacs-buffer-defer-view-refresh surface))
       (t
        (condition-case err
            (jetpacs-shell-push
             surface :reset-input-ids ids
             :callback
             (lambda (status error)
               (when (and (null error)
                          (member status '("applied" "stale")))
                 (let* ((current
                         (gethash
                          surface
                          jetpacs-component-catalog--pending-reset-ids))
                        (remaining
                         (seq-remove (lambda (id) (member id ids)) current)))
                   (if remaining
                       (puthash
                        surface remaining
                        jetpacs-component-catalog--pending-reset-ids)
                     (remhash
                      surface jetpacs-component-catalog--pending-reset-ids))))))
          (error
           (message "jetpacs-component-catalog: reset push failed: %s"
                    (jetpacs-error-label err)))))))))

(defun jetpacs-component-catalog--defer-authoring-refresh (surface reset-ids)
  "Refresh SURFACE, carrying RESET-IDS until one snapshot is applied."
  (if (null reset-ids)
      (jetpacs-buffer-defer-view-refresh surface)
    (let ((pending
           (gethash surface jetpacs-component-catalog--pending-reset-ids)))
      (puthash surface (sort (delete-dups (append pending reset-ids)) #'string<)
               jetpacs-component-catalog--pending-reset-ids)
      (jetpacs-flow-continue
       (lambda ()
         (jetpacs-component-catalog--push-pending-reset surface))))))

(defun jetpacs-component-catalog--editor-root (document)
  "Return DOCUMENT's authored Editor root, or nil."
  (and (equal (plist-get document :component) "editor")
       (plist-get document :root)))

(defun jetpacs-component-catalog--authoring-editor-key (document)
  "Return DOCUMENT's synchronized (DOCUMENT . EDITOR-ID) key, or nil."
  (when-let* ((root (jetpacs-component-catalog--editor-root document))
              (remote-document
               (and (plist-member root :document)
                    (plist-get root :document))))
    (cons remote-document (plist-get root :id))))

(defun jetpacs-component-catalog--validate-specimen-identity (document)
  "Reject duplicate or page-colliding node ids throughout DOCUMENT."
  (let* ((component (plist-get document :component))
         (node (jetpacs-component-catalog--specimen-node-for-document document))
         (ids (jetpacs-collect-node-ids node nil))
         (fixtures (cdr (assoc component
                               jetpacs-component-catalog--fixture-ids)))
         seen duplicate)
    (dolist (id ids)
      (if (member id seen)
          (setq duplicate t)
        (push id seen)))
    (when duplicate
      (jetpacs-component-catalog--edit-error
       "Node ids must be unique throughout the editable specimen"))
    (when (seq-some (lambda (id) (member id fixtures)) ids)
      (jetpacs-component-catalog--edit-error
       "That id is already used by a reference fixture on this page"))))

(defun jetpacs-component-catalog--validate-authoring-sync (document &optional client)
  "Reject a synchronized Editor DOCUMENT that would steal a live session.
CLIENT defaults to the current Jetpacs client."
  (jetpacs-component-catalog--validate-specimen-identity document)
  (let ((client (or client (jetpacs-client))))
    (when-let* ((key (jetpacs-component-catalog--authoring-editor-key document)))
    (when (equal key (cons jetpacs-component-catalog--sync-document
                           jetpacs-component-catalog--sync-editor-id))
      (jetpacs-component-catalog--edit-error
       "That synchronized Editor key belongs to the fixed catalog fixture"))
    (when-let* ((attached (ebp-sync-attached-buffer (car key) (cdr key))))
      (unless (and (eq attached
                       jetpacs-component-catalog--authoring-sync-buffer)
                   (equal key jetpacs-component-catalog--authoring-sync-key))
        (jetpacs-component-catalog--edit-error
         "That synchronized Editor key is already attached elsewhere")))
    (when (and client
               (not (equal key jetpacs-component-catalog--authoring-sync-key))
               (stringp
                (ebp-client-editor-text client (car key) (cdr key))))
      (jetpacs-component-catalog--edit-error
       "That synchronized Editor key already has a live device mirror")))))

(defun jetpacs-component-catalog--configure-authoring-buffer-mode
    (buffer document)
  "Configure BUFFER's mode and sync riders for Editor DOCUMENT.
The buffer's text is not edited."
  (let* ((root (jetpacs-component-catalog--editor-root document))
         (desired (if (equal (plist-get root :syntax) "elisp")
                      'emacs-lisp-mode
                    'fundamental-mode)))
    (with-current-buffer buffer
      (unless (eq major-mode desired)
        (if (eq desired 'emacs-lisp-mode)
            (delay-mode-hooks (emacs-lisp-mode))
          (fundamental-mode)))
      (setq-local buffer-auto-save-file-name nil)
      (setq-local ebp-sync-eglot nil)
      (setq-local ebp-sync-diagnostics t)
      (setq-local ebp-sync-fontify t)
      (setq-local ebp-sync-eldoc t))))

(defun jetpacs-component-catalog--configure-authoring-buffer
    (buffer document)
  "Configure process-volatile BUFFER from synchronized Editor DOCUMENT."
  (let* ((root (jetpacs-component-catalog--editor-root document))
         (value (or (plist-get root :value) "")))
    (with-current-buffer buffer
      (insert value)
      (jetpacs-component-catalog--configure-authoring-buffer-mode
       buffer document)
      (goto-char (point-max))
      (set-buffer-modified-p nil)
      (setq buffer-undo-list nil))))

(defun jetpacs-component-catalog--dispose-authoring-sync-buffer (buffer key)
  "Detach and destroy explicit authored sync BUFFER registered under KEY."
  (when key
    (ebp-complete-set-editor-kinds (car key) (cdr key) nil))
  (when (buffer-live-p buffer)
    (ignore-errors (ebp-sync-detach buffer))
    (with-current-buffer buffer
      (set-buffer-modified-p nil))
    (kill-buffer buffer)))

(defun jetpacs-component-catalog--release-authoring-sync-buffer ()
  "Detach and destroy the editable specimen's process-volatile sync buffer."
  (jetpacs-component-catalog--dispose-authoring-sync-buffer
   jetpacs-component-catalog--authoring-sync-buffer
   jetpacs-component-catalog--authoring-sync-key)
  (setq jetpacs-component-catalog--authoring-sync-buffer nil
        jetpacs-component-catalog--authoring-sync-key nil))

(defun jetpacs-component-catalog--replace-buffer-text (buffer value)
  "Replace BUFFER's whole text with VALUE only when it differs."
  (with-current-buffer buffer
    (save-restriction
      (widen)
      (unless (equal value (buffer-substring-no-properties
                            (point-min) (point-max)))
        (let ((inhibit-read-only t))
          (delete-region (point-min) (point-max))
          (insert value))
        (set-buffer-modified-p nil)))))

(defun jetpacs-component-catalog--set-authoring-editor-kinds
    (key root client available)
  "Publish completion kinds for KEY from ROOT, CLIENT, and AVAILABLE."
  (ebp-complete-set-editor-kinds
   (car key) (cdr key)
   (and available client (eq (plist-get root :complete) t)
        (jetpacs-feature-advertised-p "editor.candidate_kind" :app))))

(defun jetpacs-component-catalog--reconcile-authoring-sync
    (document &optional replace-value client)
  "Reconcile synchronized Editor DOCUMENT with its owned scratch buffer.
REPLACE-VALUE explicitly replaces a same-key live buffer from authored
`:value'.  CLIENT defaults to the attached client."
  (let* ((root (jetpacs-component-catalog--editor-root document))
         (key (jetpacs-component-catalog--authoring-editor-key document))
         (client (or client (jetpacs-client)))
         (available (jetpacs-component-catalog--sync-available-p client))
         (old-buffer jetpacs-component-catalog--authoring-sync-buffer)
         (old-key jetpacs-component-catalog--authoring-sync-key)
         (same (and (equal key old-key) (buffer-live-p old-buffer))))
    (cond
     ((null key)
      (jetpacs-component-catalog--release-authoring-sync-buffer))
     ((not same)
      ;; Prepare and, when admitted, attach the candidate before disposing the
      ;; old owner.  A failed new-key attach therefore cannot erase live text.
      (let ((candidate (generate-new-buffer " *Jetpacs authored editor*"))
            committed)
        (unwind-protect
            (progn
              (jetpacs-component-catalog--configure-authoring-buffer
               candidate document)
              (when available
                (ebp-sync-attach client (car key) (cdr key) candidate))
              (jetpacs-component-catalog--dispose-authoring-sync-buffer
               old-buffer old-key)
              (setq jetpacs-component-catalog--authoring-sync-buffer candidate
                    jetpacs-component-catalog--authoring-sync-key key
                    committed t)
              (jetpacs-component-catalog--set-authoring-editor-kinds
               key root client available))
          (unless committed
            (jetpacs-component-catalog--dispose-authoring-sync-buffer
             candidate key)))))
     (t
      (let* ((buffer old-buffer)
             (desired (if (equal (plist-get root :syntax) "elisp")
                          'emacs-lisp-mode
                        'fundamental-mode))
             (mode-change
              (with-current-buffer buffer (not (eq major-mode desired))))
             (before-text
              (with-current-buffer buffer
                (save-restriction
                  (widen)
                  (buffer-substring-no-properties (point-min) (point-max)))))
             (target-text
              (cond
               (replace-value (or (plist-get root :value) ""))
               (mode-change before-text))))
        (condition-case err
            (progn
              ;; Major-mode initialization clears buffer-local sync ownership,
              ;; so it always happens detached and is followed by a fresh
              ;; attach.  Preserve the pre-detach text across mirror adoption.
              (when mode-change
                (ignore-errors (ebp-sync-detach buffer))
                (jetpacs-component-catalog--configure-authoring-buffer-mode
                 buffer document))
              (if available
                  (unless (eq (ebp-sync-buffer client (car key) (cdr key))
                              buffer)
                    (ebp-sync-attach client (car key) (cdr key) buffer))
                (ignore-errors (ebp-sync-detach buffer)))
              ;; Explicit authored value replacement and syntax-only text
              ;; restoration happen after attach, so mirror adoption cannot
              ;; silently undo either authority decision.
              (when target-text
                (jetpacs-component-catalog--replace-buffer-text
                 buffer target-text))
              (jetpacs-component-catalog--set-authoring-editor-kinds
               key root client available))
          (error
           (ignore-errors (ebp-sync-detach buffer))
           (jetpacs-component-catalog--replace-buffer-text buffer before-text)
           (jetpacs-component-catalog--set-authoring-editor-kinds
            key root client nil)
           (signal (car err) (cdr err)))))))))

(defun jetpacs-component-catalog--register-state-handlers (&optional document)
  "Install fixed and optional authored Editor state handlers.
DOCUMENT defaults to the current authored Editor document."
  (let* ((document (or document
                       (jetpacs-component-catalog--document "editor")))
         (root (jetpacs-component-catalog--editor-root document))
         (dynamic-id
          (and root (not (plist-member root :document))
               (eq (plist-get root :publish_state) t)
               (plist-get root :id))))
    (jetpacs-on-state-change-clear "" "app:jpcatalog")
    (jetpacs-on-state-change
     "jpcatalog-editor-live" #'jetpacs-component-catalog--on-editor-state
     "app:jpcatalog")
    (jetpacs-on-state-change
     "jpcatalog-editor-enter" #'jetpacs-component-catalog--on-editor-state
     "app:jpcatalog")
    (when dynamic-id
      (jetpacs-on-state-change
       dynamic-id #'jetpacs-component-catalog--on-editor-state
       "app:jpcatalog"))
    (setq jetpacs-component-catalog--authoring-state-id dynamic-id)))

(defun jetpacs-component-catalog--open-action (id)
  "Return the catalog navigation descriptor for component ID."
  (jetpacs-action "jpcatalog.open" :args (list :component id)))

(defun jetpacs-component-catalog--presentation-action (id mode)
  "Return an explicit descriptor selecting ID presentation MODE."
  (jetpacs-action
   "jpcatalog.presentation"
   :args (list :component id :mode mode)))

(defun jetpacs-component-catalog--presentation-button (id mode)
  "Return ID's top-bar button selecting presentation MODE."
  (pcase mode
    ((or "elisp" "source")
     (jetpacs-icon-button
      "code" (jetpacs-component-catalog--presentation-action id mode)
      :content-description "Show authored source"))
    ("visual"
     (jetpacs-icon-button
      "tune" (jetpacs-component-catalog--presentation-action id mode)
      :content-description "Show visual editor"))
    ("lisp"
     (jetpacs-icon-button
      "data_object" (jetpacs-component-catalog--presentation-action id mode)
      :content-description "Show canonical Lisp data"))
    ("preview"
     (jetpacs-icon-button
      "preview" (jetpacs-component-catalog--presentation-action id mode)
      :content-description "Show rendered preview"))
    (_ (error "Unknown catalog presentation %S" mode))))

(defun jetpacs-component-catalog--root-presentation-action (mode)
  "Return an explicit descriptor selecting root presentation MODE."
  (jetpacs-action "jpcatalog.root.presentation" :args (list :mode mode)))

(defun jetpacs-component-catalog--root-presentation-button (mode)
  "Return a top-bar button selecting root presentation MODE."
  (pcase mode
    ("source"
     (jetpacs-icon-button
      "code" (jetpacs-component-catalog--root-presentation-action mode)
      :content-description "Show catalog program source"))
    ("visual"
     (jetpacs-icon-button
      "tune" (jetpacs-component-catalog--root-presentation-action mode)
      :content-description "Show catalog program structure"))
    ("lisp"
     (jetpacs-icon-button
      "data_object" (jetpacs-component-catalog--root-presentation-action mode)
      :content-description "Show catalog program manifest"))
    ("preview"
     (jetpacs-icon-button
      "preview" (jetpacs-component-catalog--root-presentation-action mode)
      :content-description "Show catalog index"))
    (_ (error "Unknown root catalog presentation %S" mode))))

(defun jetpacs-component-catalog--root-mode-row (mode)
  "Return root Preview/Visual/Lisp/Source controls with MODE selected."
  (jetpacs-component-tabs
   "jpcatalog-root-projection-tabs"
   (mapcar (lambda (entry)
             (jetpacs-component-tab (cdr entry) (car entry)))
           '(("preview" . "Preview") ("visual" . "Visual")
             ("lisp" . "Lisp") ("source" . "Source")))
   mode
   (jetpacs-action "jpcatalog.root.presentation")
   :variant "fixed" :pinned t))

(defun jetpacs-component-catalog--bounded-projection (text)
  "Return a bounded projection plist for string TEXT."
  (jetpacs-catalog-inspection-bounded-text
   text jetpacs-component-catalog--projection-max-chars))

(defun jetpacs-component-catalog--root-program-manifest ()
  "Return deterministic inert data describing the catalog program path.
This is an inspection projection, not executable input or a second UI AST."
  (list
   :schema-version 1
   :kind "jetpacs-component-catalog-program"
   :owner jetpacs-component-catalog-owner
   :surface "app:jpcatalog"
   :root
   (list :screen "home"
         :dispatcher 'jetpacs-component-catalog--root-screen
         :preview-builder 'jetpacs-component-catalog--home-screen)
   :navigation
   (list :open-action "jpcatalog.open"
         :descriptor-builder 'jetpacs-component-catalog--open-action
         :handler 'jetpacs-component-catalog--on-open
         :screen-prefix "component-"
         :detail-dispatcher 'jetpacs-component-catalog--detail-screen)
   :projections
   (list :root-action "jpcatalog.root.presentation"
         :root-handler 'jetpacs-component-catalog--on-root-presentation
         :component-action "jpcatalog.presentation"
         :component-handler 'jetpacs-component-catalog--on-presentation
         :modes ["preview" "visual" "lisp" "source"])
   :components
   (vconcat
    (mapcar
     (lambda (component)
       (list :id (plist-get component :id)
             :name (plist-get component :name)
             :category (plist-get component :category)
             :purpose (plist-get component :purpose)
             :preview-builder (plist-get component :builder)))
     jetpacs-component-catalog--components))
   :registration 'jetpacs-component-catalog-register))

(defun jetpacs-component-catalog--root-source-result (spec)
  "Return one source projection result for root source-form SPEC."
  (pcase-let ((`(,kind ,symbol ,_label) spec))
    (pcase kind
      ('variable (jetpacs-elisp-source-for-variable symbol))
      ('function (jetpacs-elisp-source-for-function symbol))
      (_ (error "Unknown root source form kind %S" kind)))))

(defun jetpacs-component-catalog--root-source-bundle ()
  "Return a bounded exact-source bundle for the catalog drill-down program.
Each available section is an exact balanced defining form.  The bundle is
curated rather than pretending the catalog's shared framework dependencies
are part of this app's authored source boundary."
  (let ((limit jetpacs-component-catalog--projection-max-chars)
        (used 0) (exact-count 0) (all-exact t)
        sections omitted)
    (dolist (spec jetpacs-component-catalog--root-source-forms)
      (let* ((label (nth 2 spec))
             (result (jetpacs-component-catalog--root-source-result spec))
             (exact (and (eq (plist-get result :origin) 'authored)
                         (not (plist-get result :truncated))))
             (section
              (if exact
                  (format ";;;; %s\n;; %s\n%s"
                          label (plist-get result :caption)
                          (plist-get result :text))
                (format ";;;; %s\n;; Exact authored form unavailable: %s"
                        label (plist-get result :caption))))
             (cost (+ (length section) (if sections 2 0))))
        (unless exact (setq all-exact nil))
        (if (<= (+ used cost) limit)
            (progn
              (push section sections)
              (cl-incf used cost)
              (when exact (cl-incf exact-count)))
          ;; Never cut through a defining form.  A later, smaller form can
          ;; still fit, so record this omission and keep walking.
          (setq all-exact nil)
          (push label omitted))))
    (let* ((text
            (if sections
                (mapconcat #'identity (nreverse sections) "\n\n")
              ";; No exact catalog source forms fit the projection bound."))
           (omitted (nreverse omitted))
           (omission-note
            (and omitted
                 (format "\n\n;; Omitted whole sections at the %d-character boundary: %s"
                         limit (string-join omitted ", "))))
           (text
            (if (and omission-note
                     (<= (+ (length text) (length omission-note)) limit))
                (concat text omission-note)
              text))
           (origin
            (cond
             ((and all-exact (null omitted)) 'authored)
             ((> exact-count 0) 'mixed)
             (t 'unavailable)))
           (truncated (or omitted (not all-exact))))
      (list
       :text text
       :caption
       (if (eq origin 'authored)
           "Curated exact defining forms for the catalog registry, root projections, drill-down, projection dispatch, and registration path. Shared Chrome and renderer internals remain outside this app-owned source boundary."
         (format
          "A partial catalog source bundle: only complete exact forms are copied; unavailable or over-bound sections are identified without substituting loaded objects. The boundary is %d characters."
          limit))
       :origin origin
       :truncated (and truncated t)))))

(defun jetpacs-component-catalog--root-node-summary (node)
  "Return a bounded one-line inspection summary for typed EBP NODE."
  (let* ((descriptor
          (seq-some
           (lambda (member)
             (and (plist-member node member) (plist-get node member)))
           '(:on_tap :on_change :on_submit :on_save :on_enter)))
         (action
          (and (listp descriptor)
               (or (plist-get descriptor :action)
                   (plist-get descriptor :builtin))))
         (content
          (seq-some
           (lambda (member)
             (when-let* ((value (plist-get node member))
                         ((stringp value)))
               (truncate-string-to-width
                (string-replace "\n" "↵" value) 64 nil nil "…")))
           '(:label :text :title :content_description))))
    (string-join
     (delq nil
           (list (plist-get node :t)
                 (when-let* ((id (plist-get node :id)))
                   (format "id=%s" id))
                 (when content (format "%S" content))
                 (when action (format "action=%s" action))))
     " · ")))

(defun jetpacs-component-catalog--root-node-tree (root)
  "Return a bounded visual tree string derived directly from EBP ROOT."
  (jetpacs-catalog-inspection-node-tree root 96))

(defun jetpacs-component-catalog--preview-json (component back)
  "Return COMPONENT's current Preview EBP projection using BACK.
The result is a plist with :text, :caption, :available, and :truncated.
Building happens under throwaway buffer-exposure authority because this
Preview is inspected off-tree rather than presented."
  (condition-case err
      (let* ((builder (plist-get component :builder))
             (preview
              (jetpacs-buffer-with-scratch-exposure
                (funcall builder back)))
             (bounded
              (jetpacs-component-catalog--bounded-projection
               ;; The canonicalizer returns UTF-8 bytes for exact golden
               ;; comparison.  This value becomes EBP text in the viewer, so
               ;; decode it once at that embedding boundary.
               (decode-coding-string
                (jetpacs-node->canonical-json preview) 'utf-8 t)))
             (truncated (plist-get bounded :truncated)))
        (list
         :text (plist-get bounded :text)
         :caption
         (concat
          "Canonical node JSON for the current Preview screen; the outer "
          "surface and multi_view envelope are omitted."
          (when truncated
            (format " Display and copy are truncated at %d characters."
                    jetpacs-component-catalog--projection-max-chars)))
         :available t
         :truncated truncated))
    (error
     (list
      :text ""
      :caption
      (format "Canonical EBP is unavailable: %s"
              (truncate-string-to-width
               (format "%s" (jetpacs-error-label err)) 160 nil nil "…"))
      :available nil
      :truncated nil))))

(cl-defun jetpacs-component-catalog--screen
    (title body &key back actions body-key)
  "Build a catalog screen titled TITLE around scoped BODY and BACK.
Glasspane chrome remains outside the invisible `jetpacs.scope'; canonical EBP
nodes inside BODY may therefore select installed Jetpacs core overrides.
ACTIONS are top-bar nodes.  BODY-KEY, when non-nil, gives the projection's
scrolling body a distinct receiver identity."
  (jetpacs-chrome-screen
   title
   (jetpacs-component-scope
    (list (if body-key (jetpacs-with-attrs body :key body-key) body)))
   :back back :actions actions))

(defun jetpacs-component-catalog--elisp-screen (component back)
  "Build COMPONENT's read-only authored Source viewer with BACK navigation."
  (let* ((id (plist-get component :id))
         (name (plist-get component :name))
         (source
          (jetpacs-elisp-source-for-function (plist-get component :builder)))
         (source-text (plist-get source :text))
         (origin (plist-get source :origin))
         (ebp (jetpacs-component-catalog--preview-json component back))
         (ebp-text (plist-get ebp :text))
         (source-copy
          (pcase origin
            ('authored "Copy Elisp")
            ('loaded "Copy loaded function")
            (_ nil)))
         (source-children
          (append
           (when source-copy
             (list (jetpacs-button
                    source-copy (jetpacs-clipboard-copy source-text)
                    :icon "content_copy" :variant "tonal")))
           (list
            (jetpacs-text (plist-get source :caption) :style "caption")
            (jetpacs-text source-text :style "mono" :selectable t
                          :syntax "elisp"))))
         (ebp-children
          (append
           (when (plist-get ebp :available)
             (list (jetpacs-button
                    "Copy canonical EBP" (jetpacs-clipboard-copy ebp-text)
                    :icon "content_copy" :variant "tonal")))
           (list (jetpacs-text (plist-get ebp :caption) :style "caption"))
           (when (plist-get ebp :available)
             (list (jetpacs-text ebp-text :style "mono" :selectable t))))))
    (jetpacs-component-catalog--screen
     (format "%s · Source" name)
     (jetpacs-lazy-column
      (jetpacs-component-catalog-editor-mode-row id "source")
      (jetpacs-component-panel "AUTHORED ELISP" source-children)
      (apply #'jetpacs-collapsible
             (format "jpcatalog-ebp-%s" id)
             (jetpacs-text "EBP / canonical JSON" :style "title")
             (append ebp-children (list :collapsed t)))
      :spacing 12 :content-padding 12)
     :back back
     :actions
     (list (jetpacs-component-catalog--presentation-button id "preview"))
     :body-key (format "jpcatalog-%s-elisp" id))))

(defun jetpacs-component-catalog--instrumented-specimen (component)
  "Return COMPONENT's trace/arm instrumentation result."
  (let ((draft (jetpacs-component-catalog--draft component)))
    (jetpacs-component-catalog-actions-instrument
     (plist-get draft :document) (plist-get draft :armed)
     (list :surface "app:jpcatalog" :target :app
           :client (jetpacs-client)))))

(defun jetpacs-component-catalog--specimen-node (component instrumentation)
  "Compile COMPONENT's live specimen from INSTRUMENTATION.
An Editor requiring unavailable synchronization degrades locally so the
authoring controls remain reachable instead of refusing the whole surface."
  (let* ((document (plist-get instrumentation :document))
         (root (plist-get document :root))
         (key (jetpacs-component-catalog--authoring-editor-key document))
         (client (jetpacs-client)))
    (if (and (equal component "editor")
             (plist-member root :document)
             (or (not (jetpacs-component-catalog--sync-available-p client))
                 (not
                  (and key client
                       (eq (ebp-sync-buffer client (car key) (cdr key))
                           jetpacs-component-catalog--authoring-sync-buffer)))))
        (jetpacs-card
         (jetpacs-column
          (jetpacs-text "Synchronized specimen unavailable" :style "title")
          (jetpacs-text
           "The authored document is valid, but this live session did not admit editor.sync. Remove Document or reconnect to a compatible Companion."
           :style "caption") :spacing 6)
         :variant "outlined")
      (jetpacs-component-authoring-compile-document document))))

(defun jetpacs-component-catalog--specimen-nodes (component)
  "Return COMPONENT's live specimen and action-safety nodes.
Pin-capable specimens are emitted as direct lazy-list siblings so their
authored `pinned' members can take effect.  The surrounding label and safety
controls remain ordinary panels; other specimens retain one compact panel."
  (let* ((digest (jetpacs-component-catalog--draft-digest component))
         (instrumentation
          (jetpacs-component-catalog--instrumented-specimen component))
         (records (plist-get instrumentation :authored))
         (armed (plist-get instrumentation :armed))
         (compatibility (plist-get instrumentation :compatibility))
         (compatible (and records
                          (plist-get compatibility :compatible)))
         (specimen
          (jetpacs-component-catalog--specimen-node component instrumentation))
         (status
          (delq
           nil
           (list
            (jetpacs-text
             (format "%s · %d authored action%s · %s"
                     (if armed "ARMED — compatible actions run for real"
                       "TRACE — authored actions are inert")
                     (length records) (if (= (length records) 1) "" "s")
                     (substring digest 0 12))
             :style "caption" :color (if armed "primary" nil))
            (when-let* ((issues (plist-get compatibility :issues)))
              (jetpacs-text
               (format "Cannot arm: %s"
                       (mapconcat
                        (lambda (issue)
                          (or (plist-get issue :message)
                              (symbol-name (plist-get issue :reason))))
                        issues "; "))
               :style "caption" :color "error"))
            (jetpacs-component-catalog-editor-actions-row
             component digest armed compatible)))))
    (if (member component '("tabs" "section-navigator"))
        (list
         (jetpacs-component-panel
          "EDITABLE SPECIMEN"
          (list
           (jetpacs-text
            "The live authored navigator follows as a direct scrolling-list item, so Pin while scrolling can take effect."
            :style "caption")))
         specimen
         (jetpacs-component-panel "SPECIMEN CONTROLS" status))
      (list
       (jetpacs-component-panel
        "EDITABLE SPECIMEN" (cons specimen status))))))

(defun jetpacs-component-catalog--draft-error-node (component)
  "Return COMPONENT's current authoring error card, or nil."
  (when-let* ((message (plist-get (jetpacs-component-catalog--draft component)
                                  :error)))
    (jetpacs-card
     (jetpacs-column
      (jetpacs-text "Last edit was not applied" :style "title" :color "error")
      (jetpacs-text message :style "body" :color "error")
      (jetpacs-text "The live specimen still uses the last valid document."
                    :style "caption")
      :spacing 4)
     :variant "outlined")))

(defun jetpacs-component-catalog--trace-panel (component)
  "Return COMPONENT's bounded, redacted trace history panel, or nil."
  (when-let* ((traces (plist-get (jetpacs-component-catalog--draft component)
                                 :traces)))
    (jetpacs-component-panel
     "ACTION TRACE"
     (mapcar
      (lambda (trace)
        (jetpacs-text
         (string-trim-right (jetpacs-authoring-print trace 4096))
         :style "mono" :selectable t))
      traces))))

(defun jetpacs-component-catalog--authoring-screen (component back mode)
  "Build COMPONENT's authoring projection for MODE using BACK navigation."
  (let* ((entry (jetpacs-component-catalog--component component))
         (draft (jetpacs-component-catalog--draft component))
         (document (plist-get draft :document))
         (digest (jetpacs-component-catalog--draft-digest component))
         (projection
          (pcase mode
            ("visual"
             (jetpacs-component-catalog-editor-visual
              component document digest))
            ("lisp"
             (jetpacs-component-catalog-editor-lisp
              component document digest (plist-get draft :lisp-buffer)))
            (_ (error "Unknown authoring projection %S" mode)))))
    (jetpacs-component-catalog--screen
     (format "%s · %s" (plist-get entry :name) (capitalize mode))
     (apply
      #'jetpacs-lazy-column
      (append
       (list (jetpacs-component-catalog-editor-mode-row component mode))
       (jetpacs-component-catalog--specimen-nodes component)
       (delq
        nil
        (list
         (jetpacs-component-catalog--draft-error-node component)
         projection
         (jetpacs-component-catalog--trace-panel component)))
       '(:spacing 12 :content-padding 12)))
     :back back
     :actions (list (jetpacs-component-catalog--presentation-button
                     component "source"))
     :body-key (format "jpcatalog-%s-%s" component mode))))

(defun jetpacs-component-catalog--preview-authoring-nodes (component)
  "Return COMPONENT's direct lazy-list authoring nodes for Preview."
  (append
   (list (jetpacs-component-catalog-editor-mode-row component "preview"))
   (jetpacs-component-catalog--specimen-nodes component)
   (delq nil
         (list
          (jetpacs-component-catalog--draft-error-node component)
          (jetpacs-component-catalog--trace-panel component)))))

(defun jetpacs-component-catalog--preview-column (component &rest children)
  "Return COMPONENT's Preview lazy column followed by CHILDREN.
Keeping authoring nodes at this level lets pinned Tabs participate in the
parent lazy list instead of being hidden below a static Column or Panel."
  (apply
   #'jetpacs-lazy-column
   (append
    (jetpacs-component-catalog--preview-authoring-nodes component)
    children
    '(:spacing 12 :content-padding 12))))

(defun jetpacs-component-catalog--root-program-flow ()
  "Return a compact literate account of the catalog drill-down path."
  (string-join
   '("home · jetpacs-component-catalog--root-screen"
     "  Preview · jetpacs-component-catalog--home-screen"
     "  Open card · jpcatalog.open"
     "  Handler · jetpacs-component-catalog--on-open"
     "  Chrome view · component-{id}"
     "  Dispatcher · jetpacs-component-catalog--detail-screen"
     "  Preview · registered component builder"
     "  Visual/Lisp/Source · explicit projection dispatch")
   "\n"))

(defun jetpacs-component-catalog--root-registry-nodes ()
  "Return visual inspection nodes for the live component registry."
  (mapcan
   (lambda (component)
     (let ((id (plist-get component :id))
           (name (plist-get component :name)))
       (list
        (jetpacs-text
         (format "%s · %s" name id) :style "title")
        (jetpacs-text
         (format "%s · %s\nPreview builder: %S"
                 (plist-get component :category)
                 (plist-get component :purpose)
                 (plist-get component :builder))
         :style "caption")
        (jetpacs-component-action
         (format "Inspect %s" name)
         (jetpacs-component-catalog--open-action id)))))
   jetpacs-component-catalog--components))

(defun jetpacs-component-catalog--root-visual-screen (back)
  "Build a read-only visual catalog-program inspection using BACK."
  (let ((preview (jetpacs-component-catalog--home-screen back)))
    (jetpacs-component-catalog--screen
     (format "%s · Visual" jetpacs-component-catalog-title)
     (jetpacs-lazy-column
      (jetpacs-component-catalog--root-mode-row "visual")
      (jetpacs-component-panel
       "PROGRAM FLOW"
       (list
        (jetpacs-text
         "The route below is the program path that turns a catalog card into a component projection."
         :style "caption")
        (jetpacs-text (jetpacs-component-catalog--root-program-flow)
                      :style "mono" :selectable t)))
      (jetpacs-component-panel
       "LIVE COMPONENT REGISTRY"
       (jetpacs-component-catalog--root-registry-nodes))
      (jetpacs-component-panel
       "BUILT PREVIEW EBP TREE"
       (list
        (jetpacs-text
         "Derived directly from the current Home Preview node; this is inspection output, not a parallel UI model."
         :style "caption")
        (jetpacs-text
         (jetpacs-component-catalog--root-node-tree preview)
         :style "mono" :selectable t)))
      :spacing 12 :content-padding 12)
     :back back
     :actions
     (list (jetpacs-component-catalog--root-presentation-button "source"))
     :body-key "jpcatalog-root-visual")))

(defun jetpacs-component-catalog--root-lisp-screen (back)
  "Build the read-only inert Lisp program manifest using BACK."
  (let ((text
         (jetpacs-authoring-print
          (jetpacs-component-catalog--root-program-manifest)
          jetpacs-component-catalog--projection-max-chars)))
    (jetpacs-component-catalog--screen
     (format "%s · Lisp" jetpacs-component-catalog-title)
     (jetpacs-lazy-column
      (jetpacs-component-catalog--root-mode-row "lisp")
      (jetpacs-component-panel
       "INERT PROGRAM MANIFEST"
       (list
        (jetpacs-text
         "A deterministic, read-only Lisp description derived from the live registry and fixed routing contract. It is never evaluated and is not an editable program AST."
         :style "caption")
        (jetpacs-button
         "Copy program manifest" (jetpacs-clipboard-copy text)
         :icon "content_copy" :variant "tonal")
        (jetpacs-text text :style "mono" :selectable t :syntax "elisp")))
      :spacing 12 :content-padding 12)
     :back back
     :actions
     (list (jetpacs-component-catalog--root-presentation-button "source"))
     :body-key "jpcatalog-root-lisp")))

(defun jetpacs-component-catalog--root-source-screen (back)
  "Build the exact authored catalog-program Source projection using BACK."
  (let* ((source (jetpacs-component-catalog--root-source-bundle))
         (text (plist-get source :text))
         (copyable (not (eq (plist-get source :origin) 'unavailable))))
    (jetpacs-component-catalog--screen
     (format "%s · Source" jetpacs-component-catalog-title)
     (jetpacs-lazy-column
      (jetpacs-component-catalog--root-mode-row "source")
      (jetpacs-component-panel
       "CATALOG PROGRAM SOURCE"
       (append
        (when copyable
          (list
           (jetpacs-button
            (if (eq (plist-get source :origin) 'authored)
                "Copy catalog program"
              "Copy available exact source")
            (jetpacs-clipboard-copy text)
            :icon "content_copy" :variant "tonal")))
        (list
         (jetpacs-text (plist-get source :caption) :style "caption")
         (jetpacs-text text :style "mono" :selectable t :syntax "elisp"))))
      :spacing 12 :content-padding 12)
     :back back
     :actions
     (list (jetpacs-component-catalog--root-presentation-button "preview"))
     :body-key "jpcatalog-root-source")))

(defun jetpacs-component-catalog--home-screen (back)
  "Build the component catalog Preview index with BACK navigation."
  (jetpacs-component-catalog--screen
   (format "%s · Preview" jetpacs-component-catalog-title)
   (apply
    #'jetpacs-lazy-column
    (append
     (list
      (jetpacs-component-catalog--root-mode-row "preview")
      (jetpacs-component-panel
       "LITERATE COMPONENT CATALOG"
       (list
        (jetpacs-text
         "Choose a component to explore one live draft as Preview, Visual controls, canonical Lisp, or authored Source.")
        (jetpacs-text
         "Visual and Lisp edit the same inert data; Source remains the exact implementation."
         :style "caption"))))
     (mapcar
      (lambda (component)
        (let ((id (plist-get component :id))
              (name (plist-get component :name)))
          (jetpacs-component-panel
           (plist-get component :category)
           (list
            (jetpacs-text name :style "label")
            (jetpacs-text (plist-get component :purpose))
            (jetpacs-component-action
             (format "Open %s" name)
             (jetpacs-component-catalog--open-action id))))))
      jetpacs-component-catalog--components)
     (list :spacing 12 :content-padding 12)))
   :back back
   :actions
   (list (jetpacs-component-catalog--root-presentation-button "source"))
   :body-key "jpcatalog-root-preview"))

(defun jetpacs-component-catalog--root-screen (back)
  "Build the selected root inspection projection with BACK navigation."
  (pcase jetpacs-component-catalog--root-presentation-mode
    ('visual (jetpacs-component-catalog--root-visual-screen back))
    ('lisp (jetpacs-component-catalog--root-lisp-screen back))
    ('source (jetpacs-component-catalog--root-source-screen back))
    (_ (jetpacs-component-catalog--home-screen back))))

(defun jetpacs-component-catalog--action-screen (back)
  "Build the complete Action Preview screen with BACK navigation."
  (jetpacs-component-catalog--screen
   "Action · Preview"
   (jetpacs-component-catalog--preview-column
    "action"
    (jetpacs-component-panel
     "PURPOSE"
     (list (jetpacs-text
            "A load-bearing command with one accessible button and one click target.")))
    (jetpacs-component-panel
     "ANATOMY"
     (list (jetpacs-text "Label · outlined container · focus/hover/press states")
           (jetpacs-text "Minimum interaction height: 48 dp" :style "caption")))
    (jetpacs-component-panel
     "INTERACTIVE STATES"
     (list
      (jetpacs-component-action
       "Run ordinary action"
       (jetpacs-action "jpcatalog.activate"))
      (jetpacs-component-action
       "Disabled action"
       (jetpacs-action "jpcatalog.activate")
       :enabled :json-false)
      (jetpacs-text
       (format "Accepted activations: %d"
               jetpacs-component-catalog--action-count)
       :style "caption"))))
   :back back
   :actions
   (list (jetpacs-component-catalog--presentation-button "action" "source"))
   :body-key "jpcatalog-action-preview"))

(defun jetpacs-component-catalog--choice-screen (back)
  "Build the complete Choice Preview screen with BACK navigation."
  (jetpacs-component-catalog--screen
   "Choice · Preview"
   (jetpacs-component-catalog--preview-column
    "choice"
    (jetpacs-component-panel
     "PURPOSE"
     (list (jetpacs-text
            "A boolean setting whose visual draft and Emacs-owned value reconcile through the ordinary input-state path.")))
    (jetpacs-component-panel
     "ANATOMY"
     (list (jetpacs-text "Indicator · label · full-row checkbox target")
           (jetpacs-text "State publishes before on_change dispatch." :style "caption")))
    (jetpacs-component-panel
     "INTERACTIVE STATES"
     (list
      (jetpacs-component-choice
       "jpcatalog-choice" "Mirror catalog updates"
       (jetpacs-bool jetpacs-component-catalog--choice)
       (jetpacs-action "jpcatalog.choice"))
      (jetpacs-component-choice
       "jpcatalog-choice-disabled" "Disabled choice"
       :json-false (jetpacs-action "jpcatalog.choice")
       :enabled :json-false)
      (jetpacs-text
       (format "Emacs value: %s"
               (if jetpacs-component-catalog--choice "checked" "unchecked"))
       :style "caption"))))
   :back back
   :actions
   (list (jetpacs-component-catalog--presentation-button "choice" "source"))
   :body-key "jpcatalog-choice-preview"))

(defun jetpacs-component-catalog--tabs-screen (back)
  "Build the complete Tabs Preview screen with BACK navigation."
  (let ((projection-options
         (mapcar
          (lambda (entry)
            (jetpacs-component-tab (cdr entry) (car entry)))
          '(("preview" . "Preview") ("visual" . "Visual")
            ("lisp" . "Lisp") ("source" . "Source"))))
        (long-options
         (mapcar
          (lambda (label)
            (jetpacs-component-tab label (downcase label)))
          '("Overview" "Anatomy" "Behavior" "Accessibility" "Examples"))))
    (jetpacs-component-catalog--screen
     "Tabs · Preview"
     (jetpacs-component-catalog--preview-column
      "tabs"
      (jetpacs-component-panel
       "PURPOSE"
       (list
        (jetpacs-text
         "A controlled, mutually exclusive switcher for peer views of the same subject.")
        (jetpacs-text
         "Emacs owns the selected string; the renderer injects the next option value into on_change."
         :style "caption")))
      (jetpacs-component-panel
       "ANATOMY"
       (list
        (jetpacs-text "Tab label · selection indicator · shared tab list")
        (jetpacs-text
         "Values are stable strings, independent of option order."
         :style "caption")))
      (jetpacs-component-panel
       "PINNING"
       (list
        (jetpacs-text
         "The Preview/Visual/Lisp/Source strip above is pinned. Scroll this page to keep switching projections without returning to the top.")
        (jetpacs-text
         "Pin while scrolling is application-authored and takes effect when Tabs is a direct lazy-column item. The editable specimen is arranged that way here."
         :style "caption")))
      (jetpacs-component-panel
       "INTERACTIVE STATES"
       (list
        (jetpacs-component-tabs
         "jpcatalog-tabs-demo" projection-options
         jetpacs-component-catalog--tabs-value
         (jetpacs-action "jpcatalog.tabs") :variant "fixed")
        (jetpacs-text
         (format "Emacs value: %s" jetpacs-component-catalog--tabs-value)
         :style "caption")
        (jetpacs-component-tabs
         "jpcatalog-tabs-disabled" projection-options "preview"
         (jetpacs-action "jpcatalog.tabs")
         :enabled :json-false :variant "fixed")))
      (jetpacs-component-panel
       "PRESENTATION VARIANTS"
       (list
        (jetpacs-component-tabs
         "jpcatalog-tabs-scrollable" long-options
         jetpacs-component-catalog--tabs-scrollable-value
         (jetpacs-action "jpcatalog.tabs.scrollable")
         :variant "scrollable")
        (jetpacs-component-tabs
         "jpcatalog-tabs-navigator" long-options
         jetpacs-component-catalog--tabs-scrollable-value
         (jetpacs-action "jpcatalog.tabs.scrollable")
         :variant "navigator")
        (jetpacs-component-tabs
         "jpcatalog-tabs-adaptive" long-options
         jetpacs-component-catalog--tabs-scrollable-value
         (jetpacs-action "jpcatalog.tabs.scrollable")
         :variant "adaptive")
        (jetpacs-text
         "Scrollable, navigator, and adaptive are explicit presentations; each retains the derived legacy scrollable=true member in renderer IR."
         :style "caption")))
      (jetpacs-component-panel
       "LEGACY COMPATIBILITY"
       (list
        (jetpacs-component-tabs
         "jpcatalog-tabs-legacy-scrollable" long-options
         jetpacs-component-catalog--tabs-scrollable-value
         (jetpacs-action "jpcatalog.tabs.scrollable") :scrollable t)
        (jetpacs-text
         "Documents without variant continue to use the original scrollable boolean unchanged."
         :style "caption"))))
     :back back
     :actions
     (list (jetpacs-component-catalog--presentation-button "tabs" "source"))
     :body-key "jpcatalog-tabs-preview")))

(defun jetpacs-component-catalog--section-options ()
  "Return the catalog's hierarchical section options as closed IR objects."
  (mapcar
   (lambda (entry)
     (jetpacs-component-section (nth 0 entry) (nth 1 entry) (nth 2 entry)))
   jetpacs-component-catalog--section-outline))

(defun jetpacs-component-catalog--section-heading (entry)
  "Return ENTRY's direct keyed heading, targeting the selected section."
  (let* ((label (nth 0 entry))
         (value (nth 1 entry))
         (level (nth 2 entry))
         (heading
          (jetpacs-with-semantics
           (jetpacs-text label :style (if (= level 1) "headline" "title"))
           :heading-level level))
         (attrs (list :key (format "jpcatalog-section-heading-%s" value))))
    (when (equal value jetpacs-component-catalog--section-value)
      (setq attrs (append attrs '(:scroll_here t))))
    (apply #'jetpacs-with-attrs heading attrs)))

(defun jetpacs-component-catalog--section-content-nodes (entry)
  "Return ENTRY's direct heading and explanatory content nodes."
  (list
   (jetpacs-component-catalog--section-heading entry)
   (jetpacs-component-panel
    (upcase (nth 1 entry))
    (list
     (jetpacs-text (nth 3 entry))
     (jetpacs-text
      (format "Stable value: %s · heading level: %d"
              (nth 1 entry) (nth 2 entry))
      :style "caption")))))

(defun jetpacs-component-catalog--section-navigator-screen (back)
  "Build the Section Navigator Preview screen with BACK navigation."
  (let ((content
         (mapcan #'jetpacs-component-catalog--section-content-nodes
                 jetpacs-component-catalog--section-outline)))
    (jetpacs-component-catalog--screen
     "Section Navigator · Preview"
     (apply
      #'jetpacs-component-catalog--preview-column
      "section-navigator"
      (append
       (list
        (jetpacs-component-panel
         "PURPOSE"
         (list
          (jetpacs-text
           "A controlled hierarchical table of contents whose selection dispatches an ordinary Emacs action.")
          (jetpacs-text
           "The application rebuilds one direct keyed heading with scroll_here; the navigator owns no hidden scroll state."
           :style "caption")))
        (jetpacs-component-panel
         "ANATOMY"
         (list
          (jetpacs-text
           "Section label · level 1–6 · stable value · controlled selection")
          (jetpacs-text
           "Levels describe hierarchy but do not require an artificial no-gap tree."
           :style "caption")))
        (jetpacs-component-section-navigator
         "jpcatalog-section-navigator-demo"
         (jetpacs-component-catalog--section-options)
         jetpacs-component-catalog--section-value
         (jetpacs-action "jpcatalog.section.navigate")
         :pinned t)
        (jetpacs-text
         "Choose a destination. The pinned navigator remains available while the selected keyed heading becomes the one command scroll target."
         :style "caption"))
       content))
     :back back
     :actions
     (list
      (jetpacs-component-catalog--presentation-button
       "section-navigator" "source"))
     :body-key "jpcatalog-section-navigator-preview")))

(defun jetpacs-component-catalog--panel-screen (back)
  "Build the complete Panel Preview screen with BACK navigation."
  (jetpacs-component-catalog--screen
   "Panel · Preview"
   (jetpacs-component-catalog--preview-column
    "panel"
    (jetpacs-component-panel
     "PURPOSE"
     (list (jetpacs-text
            "A compact labeled boundary that never hides, merges, or takes interaction from its descendants.")))
    (jetpacs-component-panel
     "ANATOMY"
     (list (jetpacs-text "Monospaced heading · outlined surface · content stack")
           (jetpacs-text "The label is a heading; every child keeps its semantics."
                         :style "caption")))
    (jetpacs-component-panel
     "NESTED ACTION REMAINS INDEPENDENT"
     (list
      (jetpacs-text "This text and the action below are separate descendants.")
      (jetpacs-component-action
       "Run nested action"
       (jetpacs-action "jpcatalog.activate")))))
   :back back
   :actions
   (list (jetpacs-component-catalog--presentation-button "panel" "source"))
   :body-key "jpcatalog-panel-preview"))

(defun jetpacs-component-catalog--text-field-screen (back)
  "Build the complete Text Field Preview screen with BACK navigation."
  (jetpacs-component-catalog--screen
   "Text Field · Preview"
   (jetpacs-component-catalog--preview-column
    "text-field"
    (jetpacs-component-panel
     "PURPOSE"
     (list
      (jetpacs-text
       "A canonical EBP text_input presented as a compact Jetpacs work surface inside jetpacs.scope.")
      (jetpacs-text
       "Emacs owns application values; the Companion owns native selection, composition, drafts, and secure volatility."
       :style "caption")))
    (jetpacs-component-panel
     "LIVE ACTION / STATE"
     (list
      (jetpacs-text-input
       "jpcatalog-text-live"
       :label "Command text"
       :hint "Type and press Done"
       :single-line t
       :on-change (jetpacs-action "jpcatalog.text-change")
       :on-submit (jetpacs-action "jpcatalog.text-submit")
       :clear-on-submit t
       :hide-keyboard-on-submit t)
      (jetpacs-text
       (format "Changes: %d · submits: %d · last: %s"
               jetpacs-component-catalog--text-change-count
               jetpacs-component-catalog--text-submit-count
               (or jetpacs-component-catalog--last-text-submit "—"))
       :style "caption")))
    (jetpacs-component-panel
     "VARIANTS / DECORATIONS"
     (list
      (jetpacs-text-input
       "jpcatalog-text-filled"
       :variant "filled" :label "Amount" :hint "0"
       :prefix "$" :suffix ".00" :leading-icon "search"
       :trailing-icon "clear" :single-line t :keyboard "decimal")
      (jetpacs-text-input
       "jpcatalog-text-phone"
       :label "Phone" :hint "Digits only" :single-line t
       :keyboard "phone" :filter "digits" :max-length 10
       :mask "(###) ###-####")))
    (jetpacs-component-panel
     "MULTILINE / CODE"
     (list
      (jetpacs-text-input
       "jpcatalog-text-code"
       :value "(message \"Jetpacs\")"
       :label "Elisp" :syntax "elisp" :monospace t
       :min-lines 2 :max-lines 3)))
    (jetpacs-component-panel
     "ERROR / DISABLED"
     (list
      (jetpacs-text-input
       "jpcatalog-text-error"
       :label "Workspace name" :value "taken" :single-line t
       :is-error t :supporting-text "That name is already in use")
      (jetpacs-text-input
       "jpcatalog-text-disabled"
       :label "Managed value" :value "Read from Emacs"
       :single-line t :enabled :json-false)))
    (jetpacs-component-panel
     "SECURE"
     (list
      (jetpacs-text-input
       "jpcatalog-password"
       :label "One-time secret" :hint "Never retained"
       :password t :single-line t
       :on-submit
       (jetpacs-action
        "jpcatalog.secure-submit"
        :capture-fields '("jpcatalog-password")))
      (jetpacs-text
       (format "Secure submits: %d · last length: %s"
               jetpacs-component-catalog--secure-submit-count
               (or jetpacs-component-catalog--last-secret-length "—"))
       :style "caption"))))
   :back back
   :actions
   (list
    (jetpacs-component-catalog--presentation-button "text-field" "source"))
   :body-key "jpcatalog-text-field-preview"))

(defun jetpacs-component-catalog--editor-toolbar ()
  "Return the deterministic local Editor toolbar demonstration."
  (list
   (jetpacs-toolbar-item
    :label "Comment"
    :snippet ";; ${input:Comment}"
    :placement 'line-start
    :long-press '(:snippet ";;; ${input:Heading}"))
   (jetpacs-toolbar-item :label "Move down" :line 'move-down)
   (jetpacs-toolbar-item
    :label "Insert"
    :menu (list
           (jetpacs-toolbar-item :label "Date" :snippet "${date}")
           (jetpacs-toolbar-item :label "Time" :snippet "${time}")
           (jetpacs-toolbar-item
            :label "Message"
            :snippet "(message \"${input:Text}\")")))))

(defconst jetpacs-component-catalog--completion-candidates
  '("jpcatalog-print" "jpcatalog-process" "jpcatalog-preview")
  "Deterministic candidates offered alongside the buffer's real Elisp CAPFs.")

(defun jetpacs-component-catalog--completion-annotation (candidate)
  "Return the catalog annotation for one completion CANDIDATE."
  (ignore candidate)
  "catalog function")

(defun jetpacs-component-catalog--completion-kind (candidate)
  "Return the contract category for one completion CANDIDATE."
  (ignore candidate)
  'function)

(defun jetpacs-component-catalog--completion-document (candidate)
  "Return a volatile documentation buffer for completion CANDIDATE."
  (unless (buffer-live-p jetpacs-component-catalog--completion-doc-buffer)
    (setq jetpacs-component-catalog--completion-doc-buffer
          (generate-new-buffer " *Jetpacs completion documentation*")))
  (with-current-buffer jetpacs-component-catalog--completion-doc-buffer
    (let ((inhibit-read-only t))
      (erase-buffer)
      (insert (format "%s is a deterministic catalog completion.\n\n\
Long-press documentation travels through edit.candidate.doc and is never \
fetched for an unselected row."
                      (substring-no-properties candidate))))
    (current-buffer)))

(defun jetpacs-component-catalog--completion-at-point ()
  "Offer deterministic catalog candidates at a `jpc' symbol prefix."
  (when-let* ((bounds (bounds-of-thing-at-point 'symbol))
              (beg (car bounds))
              (prefix (buffer-substring-no-properties beg (point)))
              ((string-prefix-p "jpc" prefix)))
    (list beg (cdr bounds) jetpacs-component-catalog--completion-candidates
          :annotation-function
          #'jetpacs-component-catalog--completion-annotation
          :company-kind #'jetpacs-component-catalog--completion-kind
          :company-doc-buffer
          #'jetpacs-component-catalog--completion-document)))

(defun jetpacs-component-catalog--eldoc (callback)
  "Return deterministic eldoc and ignore asynchronous CALLBACK."
  (ignore callback)
  (when (thing-at-point 'symbol)
    "jpcatalog-demo: synchronized completion, diagnostics, and commands"))

(defun jetpacs-component-catalog--sync-toolbar ()
  "Return the synchronized fixture's safe editor command toolbar."
  (list (jetpacs-toolbar-item
         :label "Indent selection" :icon "format_indent_increase"
         :command "indent-region")))

(defun jetpacs-component-catalog--ensure-sync-buffer ()
  "Return the catalog's process-volatile synchronized buffer.
The fixture uses real synchronization, completion, font-lock, Flymake, and
eldoc riders without visiting a file or starting an external language server."
  (unless (buffer-live-p jetpacs-component-catalog--sync-buffer)
    (setq jetpacs-component-catalog--sync-buffer
          (generate-new-buffer " *Jetpacs synchronized editor*"))
    (with-current-buffer jetpacs-component-catalog--sync-buffer
      (insert jetpacs-component-catalog--sync-seed)
      (delay-mode-hooks (emacs-lisp-mode))
      (goto-char (point-max))
      (set-buffer-modified-p nil)
      (setq buffer-undo-list nil)
      (setq-local buffer-auto-save-file-name nil)
      (setq-local ebp-sync-eglot nil)
      (setq-local ebp-sync-diagnostics t)
      (setq-local ebp-sync-fontify t)
      (setq-local ebp-sync-eldoc t)
      (add-hook 'completion-at-point-functions
                #'jetpacs-component-catalog--completion-at-point nil t)
      (add-hook 'eldoc-documentation-functions
                #'jetpacs-component-catalog--eldoc nil t)))
  jetpacs-component-catalog--sync-buffer)

(defun jetpacs-component-catalog--sync-value ()
  "Return the whole synchronized catalog buffer as plain text."
  (with-current-buffer (jetpacs-component-catalog--ensure-sync-buffer)
    (save-restriction
      (widen)
      (buffer-substring-no-properties (point-min) (point-max)))))

(defun jetpacs-component-catalog--sync-available-p (&optional client)
  "Non-nil when CLIENT admits the catalog's synchronized app editor."
  (and (jetpacs-granted-p "editor.sync" client)
       (jetpacs-node-advertised-p "editor" :app)))

(defun jetpacs-component-catalog--disarm-all-drafts ()
  "Clear every process-local real-action arm bit and report any change."
  (let (changed)
    (maphash
     (lambda (component draft)
       (when (plist-get draft :armed)
         (setq changed t)
         (puthash component (plist-put (copy-tree draft t) :armed nil)
                  jetpacs-component-catalog--drafts)))
     jetpacs-component-catalog--drafts)
    changed))

(defun jetpacs-component-catalog--before-replay (_client)
  "Disarm authored actions before the reconnect barrier rebuilds the catalog."
  (jetpacs-component-catalog--disarm-all-drafts))

(defun jetpacs-component-catalog--detach-fixed-sync-buffer ()
  "Detach the fixed synchronized fixture while retaining its volatile text."
  (when (buffer-live-p jetpacs-component-catalog--sync-buffer)
    (with-current-buffer jetpacs-component-catalog--sync-buffer
      (ignore-errors (ebp-sync-detach))))
  (ebp-complete-set-editor-kinds
   jetpacs-component-catalog--sync-document
   jetpacs-component-catalog--sync-editor-id nil))

(defun jetpacs-component-catalog--on-ready (client)
  "Reconcile catalog sync resources and safety state for READY CLIENT."
  ;; The before-replay hook normally clears this before the required-root
  ;; barrier push.  This fallback also refreshes if a caller enters READY
  ;; without running that barrier (or the barrier push failed).
  (when (jetpacs-component-catalog--disarm-all-drafts)
    (jetpacs-buffer-defer-view-refresh jetpacs-component-catalog-owner))
  (if (jetpacs-component-catalog--sync-available-p client)
      (progn
        (ebp-complete-set-editor-kinds
         jetpacs-component-catalog--sync-document
         jetpacs-component-catalog--sync-editor-id
         (and (jetpacs-client)
              (jetpacs-feature-advertised-p "editor.candidate_kind" :app)))
        (condition-case err
            (ebp-sync-attach
             client
             jetpacs-component-catalog--sync-document
             jetpacs-component-catalog--sync-editor-id
             (jetpacs-component-catalog--ensure-sync-buffer))
          (error
           (message "jetpacs-component-catalog: sync attach failed: %s"
                    (jetpacs-error-label err)))))
    (jetpacs-component-catalog--detach-fixed-sync-buffer))
  (when-let* ((editor-draft
               (gethash "editor" jetpacs-component-catalog--drafts)))
    (condition-case err
        (progn
          (jetpacs-component-catalog--validate-authoring-sync
           (plist-get editor-draft :document) client)
          (jetpacs-component-catalog--reconcile-authoring-sync
           (plist-get editor-draft :document) nil client))
      (error
       (jetpacs-component-catalog--set-draft-error "editor" err))))
  (maphash
   (lambda (surface _ids)
     (jetpacs-flow-continue
      (lambda ()
        (jetpacs-component-catalog--push-pending-reset surface))))
   jetpacs-component-catalog--pending-reset-ids))

(defun jetpacs-component-catalog--release-sync-buffer ()
  "Detach and destroy the catalog's non-durable synchronization fixture."
  (jetpacs-component-catalog--detach-fixed-sync-buffer)
  (when (buffer-live-p jetpacs-component-catalog--sync-buffer)
    (with-current-buffer jetpacs-component-catalog--sync-buffer
      (set-buffer-modified-p nil))
    (kill-buffer jetpacs-component-catalog--sync-buffer))
  (when (buffer-live-p jetpacs-component-catalog--completion-doc-buffer)
    (kill-buffer jetpacs-component-catalog--completion-doc-buffer))
  (setq jetpacs-component-catalog--sync-buffer nil
        jetpacs-component-catalog--completion-doc-buffer nil))

(defun jetpacs-component-catalog--sync-panel ()
  "Build the admitted synchronized Editor panel or an honest fallback."
  (jetpacs-component-panel
   "SYNCHRONIZED / LIVE"
   (if (jetpacs-component-catalog--sync-available-p)
       (list
        (jetpacs-editor
         jetpacs-component-catalog--sync-editor-id
         :document jetpacs-component-catalog--sync-document
         :value (jetpacs-component-catalog--sync-value)
         :on-save (jetpacs-action "jpcatalog.sync-editor-save")
         :min-lines 5 :max-lines 8 :line-numbers t
         :syntax "elisp" :complete t
         :toolbar (jetpacs-component-catalog--sync-toolbar))
        (jetpacs-text
         (format "Saves: %d · latest: %s"
                 jetpacs-component-catalog--sync-save-count
                 (or jetpacs-component-catalog--last-sync-preview "—"))
         :style "caption")
        (jetpacs-text
         "Type after the final jpc for completion; long-press a row for documentation. Move onto a diagnostic for its message, or select text and run Indent selection. Disconnect makes every synchronized tool inert."
         :style "caption"))
     (list
      (jetpacs-text
       "This Companion did not admit editor.sync for app surfaces."
       :style "caption")))))

(defun jetpacs-component-catalog--editor-screen (back)
  "Build the complete local Editor Preview screen with BACK navigation."
  (jetpacs-component-catalog--screen
   "Editor · Preview"
   (jetpacs-component-catalog--preview-column
    "editor"
    (jetpacs-component-panel
     "PURPOSE"
     (list
      (jetpacs-text
       "A canonical local EBP editor presented by Jetpacs only inside jetpacs.scope.")
      (jetpacs-text
       "The shared controller owns selection, composition, byte limits, publication, and snapshot reconciliation; Styles own visuals only."
       :style "caption")))
    (jetpacs-component-panel
     "LIVE MULTILINE / SAVE"
     (list
      (jetpacs-editor
       "jpcatalog-editor-live"
       :value "; Local Jetpacs draft\n(message \"Edit me\")\n"
       :on-save (jetpacs-action "jpcatalog.editor-save")
       :min-lines 5 :max-lines 8
       :syntax "elisp" :line-numbers t :publish-state t :autofocus t
       :toolbar (jetpacs-component-catalog--editor-toolbar))
      (jetpacs-text
       (format "Drafts: %d · saves: %d · latest: %s chars · %s"
               jetpacs-component-catalog--editor-state-count
               jetpacs-component-catalog--editor-save-count
               (or jetpacs-component-catalog--last-editor-length "—")
               (or jetpacs-component-catalog--last-editor-preview "—"))
       :style "caption")))
    (jetpacs-component-panel
     "SINGLE LINE / ENTER"
     (list
      (jetpacs-editor
       "jpcatalog-editor-enter"
       :value "Press Enter"
       :on-enter (jetpacs-action "jpcatalog.editor-enter")
       :single-line t :publish-state t)
      (jetpacs-text
       (format "Accepted Enter actions: %d"
               jetpacs-component-catalog--editor-enter-count)
       :style "caption")))
    (jetpacs-component-catalog--sync-panel)
    (jetpacs-component-panel
     "READ-ONLY / DISABLED"
     (list
      (jetpacs-editor
       "jpcatalog-editor-read-only"
       :value "Read-only text remains selectable."
       :read-only t :min-lines 2 :max-lines 3)
      (jetpacs-editor
       "jpcatalog-editor-disabled"
       :value "Disabled editor"
       :enabled :json-false :min-lines 2 :max-lines 3)))
    (jetpacs-component-panel
     "CHROMELESS"
     (list
      (jetpacs-editor
       "jpcatalog-editor-chromeless"
       :value "Borderless local scratch surface"
       :chromeless t :min-lines 2 :max-lines 3)))
    (jetpacs-component-panel
     "SYNCHRONIZED TOOLING"
     (list
      (jetpacs-text
       "Phase 6 uses the same Emacs completion, font-lock, Flymake, eldoc, and command paths as ordinary synchronized documents. Jetpacs owns only their scoped presentation."
       :style "caption"))))
   :back back
   :actions
   (list (jetpacs-component-catalog--presentation-button "editor" "source"))
   :body-key "jpcatalog-editor-preview"))

(defun jetpacs-component-catalog--detail-screen (component back)
  "Build COMPONENT's detail screen using BACK navigation."
  (let ((entry (jetpacs-component-catalog--component component)))
    (unless entry
      (error "Unknown Jetpacs component %S" component))
    (pcase (jetpacs-component-catalog--presentation-mode component)
      ((or 'source 'elisp)
       (jetpacs-component-catalog--elisp-screen entry back))
      ('visual (jetpacs-component-catalog--authoring-screen
                component back "visual"))
      ('lisp (jetpacs-component-catalog--authoring-screen
              component back "lisp"))
      (_ (funcall (plist-get entry :builder) back)))))

(defun jetpacs-component-catalog--edit-envelope-p (args)
  "Return non-nil when ARGS has the closed catalog edit envelope."
  (let ((component (plist-get args :component))
        (digest (plist-get args :digest))
        (codec (plist-get args :codec))
        (input-id (plist-get args :input_id)))
    (and (stringp component)
         (stringp digest)
         (string-match-p "\\`[0-9a-f]\\{64\\}\\'" digest)
         (vectorp (plist-get args :path))
         (stringp codec)
         (member codec jetpacs-component-catalog--edit-codecs)
         (or (null input-id)
             (and (stringp input-id)
                  (<= (string-bytes input-id) 128)
                  (string-prefix-p
                   jetpacs-component-catalog-editor--reserved-id-prefix
                   input-id))))))

(defun jetpacs-component-catalog--replace-and-reconcile
    (component old-document new-document &optional replace-sync-value)
  "Commit COMPONENT's NEW-DOCUMENT and reconcile Editor synchronization.
OLD-DOCUMENT is restored if reconciliation fails.  REPLACE-SYNC-VALUE means
an explicit edit changed a same-key synchronized Editor seed."
  (jetpacs-component-catalog--validate-authoring-sync new-document)
  (jetpacs-component-catalog--replace-document component new-document)
  (condition-case err
      (progn
        (when (equal component "editor")
          (jetpacs-component-catalog--register-state-handlers new-document)
          (jetpacs-component-catalog--reconcile-authoring-sync
           new-document replace-sync-value))
        new-document)
    (error
     (jetpacs-component-catalog--replace-document component old-document)
     (when (equal component "editor")
       (jetpacs-component-catalog--register-state-handlers old-document)
       (ignore-errors
         (jetpacs-component-catalog--reconcile-authoring-sync
          old-document nil)))
     (signal (car err) (cdr err)))))

(defun jetpacs-component-catalog--on-edit (args params)
  "Apply one schema-bound Visual edit from ARGS on PARAMS' live page."
  (if (not (jetpacs-component-catalog--edit-envelope-p args))
      'rejected
    (let* ((component (plist-get args :component))
           (digest (plist-get args :digest))
           (surface (plist-get params :surface)))
      (if (not (jetpacs-component-catalog--live-edit-context-p
                component digest params))
          'stale
        (condition-case path-error
            (let* ((path
                    (jetpacs-component-catalog--decode-path
                     (plist-get args :path)))
                   (codec (plist-get args :codec))
                   (old-document
                    (jetpacs-component-catalog--document component))
                   (new-document
                    (jetpacs-component-catalog--edit-document
                     old-document path codec args))
                   (reset-ids
                    (jetpacs-component-catalog--edit-reset-ids
                     component old-document new-document
                     (plist-get args :input_id) nil))
                   (replace-sync-value
                    (and (equal component "editor")
                         (equal path '(:value))
                         (equal
                          (jetpacs-component-catalog--authoring-editor-key
                           old-document)
                          (jetpacs-component-catalog--authoring-editor-key
                           new-document)))))
              (jetpacs-component-catalog--replace-and-reconcile
               component old-document new-document replace-sync-value)
              (jetpacs-component-catalog--defer-authoring-refresh
               surface reset-ids)
              'accepted)
          (jetpacs-component-catalog-edit-error
           ;; Decode failures name a forged structural address, not user
           ;; input from a catalog control, so they are terminally invalid.
           (if (null (condition-case nil
                         (progn
                           (jetpacs-component-catalog--decode-path
                            (plist-get args :path))
                           t)
                       (error nil)))
               'rejected
             (jetpacs-component-catalog--set-draft-error
              component path-error)
             (jetpacs-buffer-defer-view-refresh surface)
             'accepted))
          ((jetpacs-component-authoring-error jetpacs-authoring-error)
           (jetpacs-component-catalog--set-draft-error component path-error)
           (jetpacs-buffer-defer-view-refresh surface)
           'accepted)
          (error
           (jetpacs-component-catalog--set-draft-error component path-error)
           (jetpacs-buffer-defer-view-refresh surface)
           'accepted))))))

(defun jetpacs-component-catalog--on-lisp-apply (args params)
  "Commit one inert canonical Lisp document from ARGS under PARAMS."
  (let ((component (plist-get args :component))
        (digest (plist-get args :digest))
        (source (plist-get args :value))
        (surface (plist-get params :surface)))
    (cond
     ((not (and (stringp component) (stringp digest) (stringp source)
                (string-match-p "\\`[0-9a-f]\\{64\\}\\'" digest)))
      'rejected)
     ((not (jetpacs-component-catalog--live-edit-context-p
            component digest params))
      'stale)
     (t
      (let ((old-document (jetpacs-component-catalog--document component)))
        (condition-case err
            (let* ((new-document
                    (jetpacs-component-authoring-read-document source))
                   (reset-ids
                    (jetpacs-component-catalog--edit-reset-ids
                     component old-document new-document nil nil))
                   (replace-sync-value
                    (and (equal component "editor")
                         (equal
                          (jetpacs-component-catalog--authoring-editor-key
                           old-document)
                          (jetpacs-component-catalog--authoring-editor-key
                           new-document))
                         (not (equal
                               (plist-get (plist-get old-document :root) :value)
                               (plist-get (plist-get new-document :root)
                                          :value))))))
              (jetpacs-component-catalog--replace-and-reconcile
               component old-document new-document replace-sync-value)
              (jetpacs-component-catalog--defer-authoring-refresh
               surface reset-ids)
              'accepted)
          (error
           (jetpacs-component-catalog--set-draft-error
            component err
            (and (<= (string-bytes source)
                     jetpacs-component-authoring-max-bytes)
                 (substring-no-properties source)))
           (jetpacs-buffer-defer-view-refresh surface)
           'accepted)))))))

(defun jetpacs-component-catalog--on-boolean-edit (args params)
  "Apply one native boolean Visual edit from ARGS under PARAMS.
The dedicated wire action preserves a switch's or Choice's boolean injected
value before joining the ordinary bounded, digest-addressed edit path.  A
control authored with the `injected-presence' codec keeps it; every other
codec claim collapses to `injected-boolean'."
  (if (not (memq (plist-get args :value) '(t :json-false)))
      'rejected
    (jetpacs-component-catalog--on-edit
     (plist-put (copy-sequence args) :codec
                (if (equal (plist-get args :codec) "injected-presence")
                    "injected-presence"
                  "injected-boolean"))
     params)))

(defun jetpacs-component-catalog--on-reset (args params)
  "Restore ARGS' component default on PARAMS' current detail page."
  (let ((component (plist-get args :component))
        (digest (plist-get args :digest))
        (surface (plist-get params :surface)))
    (cond
     ((not (and (stringp component) (stringp digest))) 'rejected)
     ((not (jetpacs-component-catalog--live-edit-context-p
            component digest params))
      'stale)
     (t
      (let* ((old-document (jetpacs-component-catalog--document component))
             (new-document
              (jetpacs-component-authoring-default-document component)))
        (condition-case err
            (let ((reset-ids
                   (jetpacs-component-catalog--edit-reset-ids
                    component old-document new-document nil t)))
              (jetpacs-component-catalog--replace-and-reconcile
               component old-document new-document t)
              (jetpacs-component-catalog--defer-authoring-refresh
               surface reset-ids)
              'accepted)
          (error
           (jetpacs-component-catalog--set-draft-error component err)
           (jetpacs-buffer-defer-view-refresh surface)
           'accepted)))))))

(defun jetpacs-component-catalog--on-arm (args params)
  "Arm or disarm ARGS' specimen in PARAMS after current compatibility."
  (let ((component (plist-get args :component))
        (digest (plist-get args :digest))
        (armed (plist-get args :armed))
        (surface (plist-get params :surface)))
    (cond
     ((not (and (stringp component) (stringp digest)
                (memq armed '(t :json-false))))
      'rejected)
     ((not (jetpacs-component-catalog--live-edit-context-p
            component digest params))
      'stale)
     ((eq armed :json-false)
      (let ((draft (copy-tree
                    (jetpacs-component-catalog--draft component) t)))
        (setq draft (plist-put draft :armed nil))
        (setq draft (plist-put draft :error nil))
        (jetpacs-component-catalog--put-draft component draft)
        (jetpacs-buffer-defer-view-refresh surface)
        'accepted))
     (t
      (let* ((document (jetpacs-component-catalog--document component))
             (compatibility
              (jetpacs-component-catalog-actions-compatibility
               document (list :surface surface :target :app
                              :client (jetpacs-client))))
             (records (plist-get compatibility :descriptors)))
        (if (and records (plist-get compatibility :compatible))
            (let ((draft (copy-tree
                          (jetpacs-component-catalog--draft component) t)))
              (setq draft (plist-put draft :armed t))
              (setq draft (plist-put draft :error nil))
              (jetpacs-component-catalog--put-draft component draft)
              (jetpacs-buffer-defer-view-refresh surface)
              'accepted)
          (let* ((issue (car (plist-get compatibility :issues)))
                 (message (or (plist-get issue :message)
                              "This specimen has no action that can be armed.")))
            (jetpacs-component-catalog--set-draft-error
             component
             (list 'jetpacs-component-catalog-edit-error message))
            (jetpacs-buffer-defer-view-refresh surface)
            'accepted)))))))

(defun jetpacs-component-catalog--trace-metadata-valid-p
    (component args)
  "Return non-nil when trace ARGS names a descriptor currently in COMPONENT."
  (let* ((instrumentation
          (jetpacs-component-catalog--instrumented-specimen component))
         (safe-document (plist-get instrumentation :document))
         (records
          (jetpacs-component-catalog-actions-descriptors safe-document))
         (keys '(:catalog_path :catalog_kind :catalog_name
                 :catalog_fingerprint)))
    (and (not (plist-get instrumentation :armed))
         (seq-some
          (lambda (record)
            (let* ((descriptor (plist-get record :descriptor))
                   (expected (plist-get descriptor :args)))
              (and (equal (plist-get descriptor :action) "jpcatalog.trace")
                   (cl-every (lambda (key)
                               (equal (plist-get args key)
                                      (plist-get expected key)))
                             keys))))
          records))))

(defun jetpacs-component-catalog--clear-captured-field-values (params)
  "Zero mutable string values in PARAMS' captured field object in place."
  (let ((fields (plist-get params :fields)))
    (cond
     ((hash-table-p fields)
      (maphash (lambda (_id value)
                 (when (stringp value) (clear-string value)))
               fields))
     ((proper-list-p fields)
      (cl-loop for (_id value) on fields by #'cddr
               when (stringp value) do (clear-string value))))))

(defun jetpacs-component-catalog--on-trace (args params)
  "Retain only a redacted summary of unarmed action ARGS and PARAMS."
  (let* ((surface (plist-get params :surface))
         (component
          (jetpacs-component-catalog--component-from-surface surface)))
    (unwind-protect
        (cond
         ((or (not (jetpacs-component-catalog--component component))
              (jetpacs-event-stale-p params))
          'stale)
         ((not (jetpacs-component-catalog--trace-metadata-valid-p
                component args))
          'rejected)
         (t
          (let* ((summary
                  (jetpacs-component-catalog-actions-trace-summary args params))
                 (draft (copy-tree
                         (jetpacs-component-catalog--draft component) t))
                 (traces (plist-get draft :traces)))
            ;; No raw ARGS, PARAMS, captured values, or field ids survive this
            ;; call.  The summarizer retains only catalog metadata and shapes.
            (setq draft
                  (plist-put
                   draft :traces
                   (seq-take (cons summary traces)
                             jetpacs-component-catalog--max-traces)))
            (jetpacs-component-catalog--put-draft component draft)
            (jetpacs-buffer-defer-view-refresh surface)
            'accepted)))
      (jetpacs-component-catalog--clear-captured-field-values params))))

(defun jetpacs-component-catalog--on-open (args params)
  "Open the component in ARGS on PARAMS' originating surface."
  (let ((component (plist-get args :component))
        (surface (plist-get params :surface)))
    (cond
     ((not (stringp component)) 'rejected)
     ((not (jetpacs-component-catalog--component component)) 'stale)
     ((not (equal surface "app:jpcatalog")) 'stale)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (equal (car (jetpacs-chrome-stack surface)) "home")) 'stale)
     (t
      (jetpacs-flow-continue
       (lambda ()
         (condition-case err
             (jetpacs-chrome-push-screen
              surface (format "component-%s" component)
              (lambda (back)
                (jetpacs-component-catalog--detail-screen component back)))
           (error
            (message "jetpacs-component-catalog: open failed: %s"
                     (jetpacs-error-label err))))))
      'accepted))))

(defun jetpacs-component-catalog--projection-value (args)
  "Return ARGS' injected value or legacy mode, rejecting disagreement.
Nil means neither member supplies one unambiguous projection value."
  (let ((value-present (plist-member args :value))
        (mode-present (plist-member args :mode))
        (value (plist-get args :value))
        (mode (plist-get args :mode)))
    (cond
     ((and value-present mode-present (not (equal value mode))) nil)
     (value-present value)
     (mode-present mode)
     (t nil))))

(defun jetpacs-component-catalog--on-presentation (args params)
  "Select ARGS' component projection value on PARAMS' live screen.
The Tabs renderer injects `value'; `mode' remains accepted for existing
top-bar descriptors and in-flight documents from the button-row version."
  (let ((component (plist-get args :component))
        (mode (jetpacs-component-catalog--projection-value args))
        (surface (plist-get params :surface)))
    (cond
     ((not (and (stringp component) (stringp mode))) 'rejected)
     ((not (member mode '("preview" "visual" "lisp" "source" "elisp")))
      'rejected)
     ((not (jetpacs-component-catalog--component component)) 'stale)
     ((not (stringp surface)) 'stale)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (equal (car (jetpacs-chrome-stack surface))
                  (format "component-%s" component)))
     'stale)
     (t
      (puthash component
               (pcase mode
                 ("preview" 'preview)
                 ("visual" 'visual)
                 ("lisp" 'lisp)
                 ((or "source" "elisp") 'source))
               jetpacs-component-catalog--presentation-modes)
      (if (and (not (member mode '("source" "elisp")))
               (gethash surface
                        jetpacs-component-catalog--pending-reset-ids))
          (jetpacs-flow-continue
           (lambda ()
             (jetpacs-component-catalog--push-pending-reset surface)))
        (jetpacs-buffer-defer-view-refresh surface))
      'accepted))))

(defun jetpacs-component-catalog--on-root-presentation (args params)
  "Select ARGS' root inspection projection on PARAMS' Home screen.
The Tabs renderer injects `value'; legacy top-bar descriptors supply `mode'."
  (let ((mode (jetpacs-component-catalog--projection-value args))
        (surface (plist-get params :surface)))
    (cond
     ((not (stringp mode)) 'rejected)
     ((not (member mode '("preview" "visual" "lisp" "source")))
      'rejected)
     ((not (equal surface "app:jpcatalog")) 'stale)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (equal (car (jetpacs-chrome-stack surface)) "home")) 'stale)
     (t
      (setq jetpacs-component-catalog--root-presentation-mode
            (pcase mode
              ("visual" 'visual)
              ("lisp" 'lisp)
              ("source" 'source)
              (_ 'preview)))
      (jetpacs-buffer-defer-view-refresh surface)
      'accepted))))

(defun jetpacs-component-catalog--on-activate (_args params)
  "Count one accepted Action and refresh PARAMS' current detail screen."
  (cl-incf jetpacs-component-catalog--action-count)
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun jetpacs-component-catalog--on-choice (args params)
  "Commit the boolean Choice value from ARGS and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (memq value '(t :json-false)))
        'rejected
      (setq jetpacs-component-catalog--choice (eq value t))
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-tabs (args params)
  "Commit the controlled Tabs value from ARGS and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (member value '("preview" "visual" "lisp" "source")))
        'rejected
      (setq jetpacs-component-catalog--tabs-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-tabs-scrollable (args params)
  "Commit the scrollable Tabs value from ARGS and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (member value
                     '("overview" "anatomy" "behavior" "accessibility"
                       "examples")))
        'rejected
      (setq jetpacs-component-catalog--tabs-scrollable-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-section-navigate (args params)
  "Select ARGS' section and command-scroll its keyed heading under PARAMS."
  (let ((value (plist-get args :value)))
    (if (not (member value
                     (mapcar (lambda (entry) (nth 1 entry))
                             jetpacs-component-catalog--section-outline)))
        'rejected
      (setq jetpacs-component-catalog--section-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-text-change (_args _params)
  "Count one non-secret text change without refreshing active IME state."
  (cl-incf jetpacs-component-catalog--text-change-count)
  'accepted)

(defun jetpacs-component-catalog--on-text-submit (args params)
  "Record ARGS' non-secret submitted value and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--text-submit-count)
      (setq jetpacs-component-catalog--last-text-submit value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-secure-submit (_args params)
  "Count PARAMS' volatile password by length, erase it, and retain no secret."
  (let ((secret (plist-get (plist-get params :fields) :jpcatalog-password)))
    (if (not (stringp secret))
        'rejected
      (setq jetpacs-component-catalog--last-secret-length (length secret))
      (cl-incf jetpacs-component-catalog--secure-submit-count)
      (clear-string secret)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--remember-editor-value (value)
  "Retain only a bounded display preview and length for editor VALUE."
  (let ((single-line (string-replace "\n" "↵" value)))
    (setq jetpacs-component-catalog--last-editor-length (length value)
          jetpacs-component-catalog--last-editor-preview
          (truncate-string-to-width single-line 72 nil nil "…"))))

(defun jetpacs-component-catalog--on-editor-state (value)
  "Record one published non-secret local Editor VALUE without refreshing."
  (when (stringp value)
    (cl-incf jetpacs-component-catalog--editor-state-count)
    (jetpacs-component-catalog--remember-editor-value value)))

(defun jetpacs-component-catalog--on-editor-save (args params)
  "Record ARGS' local Editor save and refresh PARAMS' current screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--editor-save-count)
      (jetpacs-component-catalog--remember-editor-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-editor-enter (args params)
  "Record ARGS' single-line Editor value and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--editor-enter-count)
      (jetpacs-component-catalog--remember-editor-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-sync-editor-save (args params)
  "Record ARGS' synchronized Editor value and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--sync-save-count)
      (setq jetpacs-component-catalog--last-sync-preview
            (truncate-string-to-width
             (string-replace "\n" "↵" value) 72 nil nil "…"))
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defconst jetpacs-component-catalog--verbs
  '(("jpcatalog.open" . jetpacs-component-catalog--on-open)
    ("jpcatalog.root.presentation" .
     jetpacs-component-catalog--on-root-presentation)
    ("jpcatalog.presentation" .
     jetpacs-component-catalog--on-presentation)
    ("jpcatalog.edit" . jetpacs-component-catalog--on-edit)
    ("jpcatalog.edit.boolean" .
     jetpacs-component-catalog--on-boolean-edit)
    ("jpcatalog.lisp.apply" . jetpacs-component-catalog--on-lisp-apply)
    ("jpcatalog.reset" . jetpacs-component-catalog--on-reset)
    ("jpcatalog.arm" . jetpacs-component-catalog--on-arm)
    ("jpcatalog.trace" . jetpacs-component-catalog--on-trace)
    ("jpcatalog.activate" . jetpacs-component-catalog--on-activate)
    ("jpcatalog.choice" . jetpacs-component-catalog--on-choice)
    ("jpcatalog.tabs" . jetpacs-component-catalog--on-tabs)
    ("jpcatalog.tabs.scrollable" .
     jetpacs-component-catalog--on-tabs-scrollable)
    ("jpcatalog.section.navigate" .
     jetpacs-component-catalog--on-section-navigate)
    ("jpcatalog.text-change" . jetpacs-component-catalog--on-text-change)
    ("jpcatalog.text-submit" . jetpacs-component-catalog--on-text-submit)
    ("jpcatalog.secure-submit" . jetpacs-component-catalog--on-secure-submit)
    ("jpcatalog.editor-save" . jetpacs-component-catalog--on-editor-save)
    ("jpcatalog.editor-enter" . jetpacs-component-catalog--on-editor-enter)
    ("jpcatalog.sync-editor-save" .
     jetpacs-component-catalog--on-sync-editor-save))
  "Catalog actions and their owning handlers.")

(defun jetpacs-component-catalog--dock-items (surface)
  "Return the catalog dock destination, selected for SURFACE."
  (let ((home (jetpacs-shell-surface-for jetpacs-component-catalog-owner)))
    (list (list :label jetpacs-component-catalog-title
                :icon "view_compact"
                :on-tap (jetpacs-shell-open-surface-action home)
                :selected (equal surface home)))))

(defun jetpacs-component-catalog-register ()
  "Register the Jetpacs component catalog, its actions, and app identity."
  (with-jetpacs-owner jetpacs-component-catalog-owner
    (jetpacs-defaction
     "jpcatalog.open" #'jetpacs-component-catalog--on-open
     :args '((:name component :type "text" :required t))
     :doc "Open one Jetpacs component reference page")
    (jetpacs-defaction
     "jpcatalog.presentation" #'jetpacs-component-catalog--on-presentation
     :args '((:name component :type "text" :required t)
             (:name value :type "text")
             (:name mode :type "text"))
     :doc "Select Preview, Visual, Lisp, or Source for one component")
    (jetpacs-defaction
     "jpcatalog.root.presentation"
     #'jetpacs-component-catalog--on-root-presentation
     :args '((:name value :type "text")
             (:name mode :type "text"))
     :doc "Select root Preview, Visual, Lisp, or Source inspection for catalog Home")
    (jetpacs-defaction
     "jpcatalog.edit" #'jetpacs-component-catalog--on-edit
     :args '((:name component :type "text" :required t)
             (:name digest :type "text" :required t)
             (:name path :type "path" :required t)
             (:name codec :type "enum" :required t)
             (:name literal_source :type "text")
             (:name index :type "number")
             (:name direction :type "enum")
             (:name input_id :type "ref")
             (:name value :type "text"))
     :doc "Apply one bounded schema-driven edit to a component specimen")
    (jetpacs-defaction
     "jpcatalog.edit.boolean" #'jetpacs-component-catalog--on-boolean-edit
     :args '((:name component :type "text" :required t)
             (:name digest :type "text" :required t)
             (:name path :type "path" :required t)
             (:name value :type "bool" :required t))
     :doc "Toggle one boolean field in a component specimen")
    (jetpacs-defaction
     "jpcatalog.lisp.apply" #'jetpacs-component-catalog--on-lisp-apply
     :args '((:name component :type "text" :required t)
             (:name digest :type "text" :required t)
             (:name input_id :type "ref")
             (:name value :type "text" :required t))
     :doc "Validate and apply one inert canonical component Lisp form")
    (jetpacs-defaction
     "jpcatalog.reset" #'jetpacs-component-catalog--on-reset
     :args '((:name component :type "text" :required t)
             (:name digest :type "text" :required t))
     :doc "Restore one process-local component specimen to its default")
    (jetpacs-defaction
     "jpcatalog.arm" #'jetpacs-component-catalog--on-arm
     :args '((:name component :type "text" :required t)
             (:name digest :type "text" :required t)
             (:name armed :type "bool" :required t))
     :doc "Arm or disarm currently compatible drop-policy specimen actions")
    (jetpacs-defaction
     "jpcatalog.trace" #'jetpacs-component-catalog--on-trace
     :args '((:name catalog_path :type "text" :required t)
             (:name catalog_kind :type "enum" :required t)
             (:name catalog_name :type "text" :required t)
             (:name catalog_fingerprint :type "text" :required t))
     :doc "Record a redacted shape-only trace for an unarmed specimen action")
    (jetpacs-defaction
     "jpcatalog.activate" #'jetpacs-component-catalog--on-activate
     :doc "Execute the catalog Action demonstration")
    (jetpacs-defaction
     "jpcatalog.choice" #'jetpacs-component-catalog--on-choice
     :args '((:name value :type "bool" :required t))
     :doc "Commit the catalog Choice demonstration value")
    (jetpacs-defaction
     "jpcatalog.tabs" #'jetpacs-component-catalog--on-tabs
     :args '((:name value :type "text" :required t))
     :doc "Commit the catalog Tabs demonstration value")
    (jetpacs-defaction
     "jpcatalog.tabs.scrollable"
     #'jetpacs-component-catalog--on-tabs-scrollable
     :args '((:name value :type "text" :required t))
     :doc "Commit the scrollable catalog Tabs demonstration value")
    (jetpacs-defaction
     "jpcatalog.section.navigate"
     #'jetpacs-component-catalog--on-section-navigate
     :args '((:name value :type "text" :required t))
     :doc "Select and command-scroll one catalog section destination")
    (jetpacs-defaction
     "jpcatalog.text-change" #'jetpacs-component-catalog--on-text-change
     :args '((:name value :type "text" :required t))
     :doc "Count one non-secret Text Field change without disrupting IME state")
    (jetpacs-defaction
     "jpcatalog.text-submit" #'jetpacs-component-catalog--on-text-submit
     :args '((:name value :type "text" :required t))
     :doc "Record and display one non-secret Text Field submission")
    (jetpacs-defaction
     "jpcatalog.secure-submit" #'jetpacs-component-catalog--on-secure-submit
     :doc "Count and immediately erase one volatile secure-field submission")
    (jetpacs-defaction
     "jpcatalog.editor-save" #'jetpacs-component-catalog--on-editor-save
     :args '((:name value :type "text" :required t))
     :doc "Record and display one local Editor save value")
    (jetpacs-defaction
     "jpcatalog.editor-enter" #'jetpacs-component-catalog--on-editor-enter
     :args '((:name value :type "text" :required t))
     :doc "Record and display one single-line Editor enter value")
    (jetpacs-defaction
     "jpcatalog.sync-editor-save"
     #'jetpacs-component-catalog--on-sync-editor-save
     :args '((:name value :type "text" :required t))
     :doc "Record one synchronized Editor save admitted only while READY")
    (jetpacs-component-catalog--register-state-handlers)
    (jetpacs-chrome-define-root
     jetpacs-component-catalog-owner "home"
     #'jetpacs-component-catalog--root-screen
     :required t))
  ;; Run before `jetpacs-shell--before-replay' (depth zero), so its required
  ;; root push can never rebuild a formerly armed specimen on a new session.
  (add-hook 'jetpacs-before-replay-functions
            #'jetpacs-component-catalog--before-replay -90)
  (add-hook 'jetpacs-ready-functions #'jetpacs-component-catalog--on-ready)
  (jetpacs-component-catalog--ensure-sync-buffer)
  (jetpacs-defapp
   jetpacs-component-catalog-owner
   :label jetpacs-component-catalog-title
   :icon "view_compact"
   :surfaces (list jetpacs-component-catalog-owner)
   :dock #'jetpacs-component-catalog--dock-items
   :requires-extensions '("jetpacs.components")
   :order 91))

(defun jetpacs-component-catalog-unregister ()
  "Remove the catalog actions, surface, and app registration."
  (remove-hook 'jetpacs-before-replay-functions
               #'jetpacs-component-catalog--before-replay)
  (remove-hook 'jetpacs-ready-functions #'jetpacs-component-catalog--on-ready)
  (jetpacs-component-catalog--release-authoring-sync-buffer)
  (jetpacs-component-catalog--release-sync-buffer)
  (clrhash jetpacs-component-catalog--presentation-modes)
  (clrhash jetpacs-component-catalog--drafts)
  (clrhash jetpacs-component-catalog--pending-reset-ids)
  (setq jetpacs-component-catalog--authoring-state-id nil
        jetpacs-component-catalog--root-presentation-mode 'preview
        jetpacs-component-catalog--tabs-value "preview"
        jetpacs-component-catalog--tabs-scrollable-value "overview"
        jetpacs-component-catalog--section-value "overview")
  (dolist (verb (mapcar #'car jetpacs-component-catalog--verbs))
    (jetpacs-undefaction verb))
  (jetpacs-on-state-change-clear "" "app:jpcatalog")
  (jetpacs-apps-unregister jetpacs-component-catalog-owner)
  (jetpacs-chrome-remove jetpacs-component-catalog-owner))

(jetpacs-component-catalog-register)

;;;###autoload
(defun jetpacs-component-catalog ()
  "Open the Jetpacs Components catalog on the connected Companion."
  (interactive)
  (jetpacs-chrome-reset-screens jetpacs-component-catalog-owner))

(defun jetpacs-component-catalog-unload-function ()
  "Unload the Jetpacs Components catalog without leaving stale handlers."
  (jetpacs-component-catalog-unregister)
  nil)

(provide 'jetpacs-component-catalog)
;;; jetpacs-component-catalog.el ends here
