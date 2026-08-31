;;; jetpacs-component-authoring.el --- Inert component specimen data -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A pure, versioned authoring model for the seven editable specimens in the
;; Jetpacs Components catalog.  Documents are EBP-shaped Lisp data.  They are
;; read with `jetpacs-authoring', normalized against a closed schema, and
;; compiled only by calling the public Jetpacs node, attribute, semantics,
;; action, and toolbar builders.  Authored data is never evaluated.
;;
;; This is intentionally not a general EBP tree editor.  The root type is
;; fixed by `:component', and Panel always contains exactly one Text followed
;; by one Jetpacs Action.  The small boundary keeps future arbitrary-tree work
;; an explicit spike rather than an accidental execution language.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-authoring)
(require 'jetpacs-components)

(define-error 'jetpacs-component-authoring-error
  "Jetpacs component authoring error")
(define-error 'jetpacs-component-authoring-schema-error
  "Invalid Jetpacs component document"
  'jetpacs-component-authoring-error)

(defconst jetpacs-component-authoring-schema-version 1
  "Current canonical component specimen document version.")

(defconst jetpacs-component-authoring-max-bytes 65536
  "Maximum UTF-8 size of source and canonical component documents.")

(defconst jetpacs-component-authoring-max-leaf-bytes 8192
  "Maximum UTF-8 size of an authored string leaf.")

(defconst jetpacs-component-authoring-max-data-depth 12
  "Maximum nesting depth of inert data inside an action argument object.")

(defconst jetpacs-component-authoring-max-data-members 256
  "Maximum members in one inert action object or array.")

(defconst jetpacs-component-authoring-reserved-id-prefix
  "jpcatalog-authoring-"
  "Identifier prefix reserved for the catalog's own authoring controls.")

(defconst jetpacs-component-authoring-components
  '("action" "choice" "tabs" "section-navigator" "panel" "text-field"
    "editor")
  "Component identifiers supported by the closed authoring model.")

(defconst jetpacs-component-authoring--missing (make-symbol "missing")
  "Private sentinel distinguishing an absent member from Lisp nil.")

(defvar jetpacs-component-authoring--data-obarray (make-vector 257 0)
  "Stable private obarray for normalized arbitrary action argument keys.")

(defconst jetpacs-component-authoring--node-schemas
  '(("jetpacs.action"
     :required (:label :on_tap)
     :optional (:enabled))
    ("jetpacs.choice"
     :required (:id :label :checked :on_change)
     :optional (:enabled))
    ("jetpacs.tabs"
     :required (:id :options :value :on_change)
     :optional (:enabled :variant :scrollable :pinned))
    ("jetpacs.section_navigator"
     :required (:id :options :value :on_change)
     :optional (:enabled :pinned))
    ("jetpacs.panel"
     :required (:label :children)
     :optional ())
    ("text"
     :required (:text)
     :optional (:style :font_weight :color :selectable :max_lines :syntax))
    ("text_input"
     :required (:id)
     :optional (:value :hint :label :on_change :on_submit :single_line
                :min_lines :max_lines :monospace :syntax :password :keyboard
                :autofocus :clear_on_submit :variant :is_error
                :supporting_text :prefix :suffix :leading_icon :trailing_icon
                :max_length :selection :hide_keyboard_on_submit
                :content_padding :mask :filter :enabled))
    ("editor"
     :required (:id)
     :optional (:document :value :on_save :on_enter :single_line :min_lines
                :max_lines :read_only :syntax :line_numbers :complete
                :chromeless :publish_state :autofocus :toolbar :enabled)))
  "Closed node schemas owned by this catalog authoring model.")

(defconst jetpacs-component-authoring--component-root-types
  '(("action" . "jetpacs.action")
    ("choice" . "jetpacs.choice")
    ("tabs" . "jetpacs.tabs")
    ("section-navigator" . "jetpacs.section_navigator")
    ("panel" . "jetpacs.panel")
    ("text-field" . "text_input")
    ("editor" . "editor"))
  "Catalog component identifier to its one permitted root node type.")

(defconst jetpacs-component-authoring--action-fields
  '(:on_tap :on_change :on_submit :on_save :on_enter :on_action)
  "ActionDescriptor-valued members reachable in supported specimens.")

(defconst jetpacs-component-authoring--remote-descriptor-fields
  '(:action :args :when_offline :dedupe :ttl_s :confirm :capture_fields
    :open_surface)
  "Allowed wire members of a remote ActionDescriptor.")

(defconst jetpacs-component-authoring--builtin-schemas
  '(("view.switch" (:builtin :view) ())
    ("variant.switch" (:builtin :id) (:value))
    ("surface.open" (:builtin :surface) ())
    ("clipboard.copy" (:builtin :text) ())
    ("share.send" (:builtin :text) (:title))
    ("companion.settings.open" (:builtin) ())
    ("trigger.fire" (:builtin :id) ())
    ("dialog.submit" (:builtin) (:value :capture_fields))
    ("dialog.dismiss" (:builtin) ()))
  "Closed schemas for built-in ActionDescriptors.")

(defconst jetpacs-component-authoring--semantics-fields
  '(:name :description :state_description :error :pane_title :heading_level
    :live_region :collection :collection_item :traversal_group
    :traversal_index :actions)
  "Canonical wire members of an authored Semantics object.")

(defconst jetpacs-component-authoring--toolbar-fields
  '(:label :icon :snippet :on_tap :menu :command :line :placement :long_press)
  "Canonical wire members of one Editor toolbar item.")

(defconst jetpacs-component-authoring--known-key-names
  (delete-dups
   (mapcar
    #'symbol-name
    (append
     '(:schema-version :component :root :t)
     jetpacs-universal-attributes
     (cl-loop for (_type . schema) in jetpacs-component-authoring--node-schemas
              append (append (plist-get schema :required)
                             (plist-get schema :optional)))
     jetpacs-component-authoring--remote-descriptor-fields
     '(:builtin :view :surface :text :title :value
       :confirm_label :dismiss_label)
     jetpacs-component-authoring--semantics-fields
     '(:row_count :column_count :row_index :row_span :column_index
       :column_span :label :on_action)
     jetpacs-component-authoring--toolbar-fields
     '(:start :top :end :bottom :horizontal :vertical
       :top_start :top_end :bottom_start :bottom_end :width :color))))
  "Finite known keys which normalize onto the global obarray.")

;;;; Inert plist helpers

(defun jetpacs-component-authoring--key-name (key)
  "Return KEY's colon-prefixed printed name, or nil."
  (and (symbolp key)
       (let ((name (symbol-name key)))
         (and (string-prefix-p ":" name) name))))

(defun jetpacs-component-authoring--key-equal-p (left right)
  "Whether plist keys LEFT and RIGHT have the same printed name."
  (equal (jetpacs-component-authoring--key-name left)
         (jetpacs-component-authoring--key-name right)))

(defun jetpacs-component-authoring--plist-p (value)
  "Whether VALUE is a proper, even plist with colon-prefixed symbol keys."
  (and (proper-list-p value)
       (cl-evenp (length value))
       (cl-loop for (key _member) on value by #'cddr
                always (jetpacs-component-authoring--key-name key))))

(defun jetpacs-component-authoring--fetch (plist key)
  "Fetch name-equivalent KEY from PLIST, or return the missing sentinel."
  (let ((tail plist)
        (result jetpacs-component-authoring--missing))
    (while tail
      (when (jetpacs-component-authoring--key-equal-p (car tail) key)
        (setq result (cadr tail)
              tail nil))
      (when tail (setq tail (cddr tail))))
    result))

(defun jetpacs-component-authoring--present-p (plist key)
  "Return non-nil when PLIST contains name-equivalent KEY."
  (not (eq (jetpacs-component-authoring--fetch plist key)
           jetpacs-component-authoring--missing)))

(defun jetpacs-component-authoring--diagnostic-key (name)
  "Return a bounded diagnostic rendering of key NAME."
  (truncate-string-to-width name 80 nil nil "..."))

(defun jetpacs-component-authoring--schema-error (path kind)
  "Signal a bounded schema error identifying PATH and KIND."
  (signal 'jetpacs-component-authoring-schema-error
          (list (format "%s: %s" path kind))))

(defun jetpacs-component-authoring--require-object (value path allowed)
  "Require VALUE to be a duplicate-free plist restricted to ALLOWED.
ALLOWED contains canonical keyword symbols.  Equality is by printed name so
forms returned by `jetpacs-authoring-read-one' remain safe.  PATH identifies
the object in bounded diagnostics."
  (unless (jetpacs-component-authoring--plist-p value)
    (jetpacs-component-authoring--schema-error path "must be a plist"))
  (let ((allowed-names (mapcar #'symbol-name allowed))
        seen)
    (cl-loop for (key _member) on value by #'cddr
             for name = (jetpacs-component-authoring--key-name key)
             do (when (member name seen)
                  (jetpacs-component-authoring--schema-error
                   path "has a duplicate key"))
             do (push name seen)
             do (unless (member name allowed-names)
                  (jetpacs-component-authoring--schema-error
                   path (format "unknown key %s"
                                (jetpacs-component-authoring--diagnostic-key
                                 name))))))
  value)

(defun jetpacs-component-authoring--required (plist key path)
  "Return required KEY from PLIST or signal at PATH."
  (let ((value (jetpacs-component-authoring--fetch plist key)))
    (when (eq value jetpacs-component-authoring--missing)
      (jetpacs-component-authoring--schema-error
       path (format "missing %s" key)))
    value))

(defun jetpacs-component-authoring--canonical-key (key)
  "Return the stable canonical symbol for colon-prefixed KEY.
Finite protocol keys live on the global obarray.  Open action argument keys
live in a private stable obarray, avoiding reader-time global interning."
  (let ((name (if (stringp key)
                  key
                (jetpacs-component-authoring--key-name key))))
    (unless (and name (string-prefix-p ":" name))
      (jetpacs-component-authoring--schema-error "path" "Has a non-key step"))
    (if (member name jetpacs-component-authoring--known-key-names)
        (intern name)
      ;; Reuse a key already declared by application code or an action schema,
      ;; so its eventual decoded echo remains accessible through `plist-get'.
      ;; Novel authored names still never enlarge the global obarray.
      (or (intern-soft name)
          (intern name jetpacs-component-authoring--data-obarray)))))

(defun jetpacs-component-authoring--bounded-string (value path &optional nonempty)
  "Return plain bounded string VALUE or signal at PATH.
When NONEMPTY is non-nil, reject the empty string."
  (unless (and (stringp value)
               (or (not nonempty) (not (string-empty-p value)))
               (<= (string-bytes value)
                   jetpacs-component-authoring-max-leaf-bytes))
    (jetpacs-component-authoring--schema-error path "must be a bounded string"))
  (substring-no-properties value))

(defun jetpacs-component-authoring--safe-number (value path)
  "Return finite JSON-safe number VALUE or signal at PATH."
  (unless (and (numberp value)
               (= value value)
               (<= (abs value) 9007199254740991))
    (jetpacs-component-authoring--schema-error path "must be a JSON-safe number"))
  value)

(defun jetpacs-component-authoring--scalar (value path &optional allow-null)
  "Normalize inert scalar VALUE at PATH.
ALLOW-NULL permits Lisp nil.  Explicit JSON false is always canonicalized to
the global `:json-false' symbol."
  (cond
   ((null value)
    (if allow-null nil
      (jetpacs-component-authoring--schema-error
       path "must be deleted rather than set to nil")))
   ((stringp value)
    (jetpacs-component-authoring--bounded-string value path))
   ((numberp value)
    (jetpacs-component-authoring--safe-number value path))
   ((and (symbolp value) (equal (symbol-name value) "t")) t)
   ((and (symbolp value) (equal (symbol-name value) ":json-false"))
    :json-false)
   (t (jetpacs-component-authoring--schema-error
       path "must be inert scalar data"))))

(defun jetpacs-component-authoring--literal (value path &optional depth)
  "Normalize bounded inert JSON-like VALUE at PATH.
DEPTH is private recursion state.  Objects are sorted plists and arrays are
vectors.  Symbols other than true and `:json-false' are rejected."
  (let ((depth (or depth 0)))
    (when (> depth jetpacs-component-authoring-max-data-depth)
      (jetpacs-component-authoring--schema-error path "is too deeply nested"))
    (cond
     ((or (null value) (stringp value) (numberp value) (symbolp value))
      (jetpacs-component-authoring--scalar value path t))
     ((vectorp value)
      (when (> (length value) jetpacs-component-authoring-max-data-members)
        (jetpacs-component-authoring--schema-error path "array is too long"))
      (apply #'vector
             (cl-loop for member across value for index from 0
                      collect (jetpacs-component-authoring--literal
                               member (format "%s[%d]" path index)
                               (1+ depth)))))
     ((jetpacs-component-authoring--plist-p value)
      (when (> (/ (length value) 2)
               jetpacs-component-authoring-max-data-members)
        (jetpacs-component-authoring--schema-error path "object is too large"))
      (let (pairs seen)
        (cl-loop for (key member) on value by #'cddr
                 for name = (jetpacs-component-authoring--key-name key)
                 do (when (member name seen)
                      (jetpacs-component-authoring--schema-error
                       path "has a duplicate key"))
                 do (push name seen)
                 do (push
                     (cons name
                           (jetpacs-component-authoring--literal
                            member (concat path name) (1+ depth)))
                     pairs))
        (cl-loop for (name . member)
                 in (sort pairs (lambda (left right)
                                  (string< (car left) (car right))))
                 append (list (jetpacs-component-authoring--canonical-key name)
                              member))))
     (t (jetpacs-component-authoring--schema-error
         path "must be inert JSON-like data")))))

(defun jetpacs-component-authoring--array (value path member-fn &optional nonempty)
  "Normalize array VALUE at PATH by applying MEMBER-FN.
Proper lists are accepted at the Lisp boundary and canonicalized to vectors.
NONEMPTY rejects an empty array."
  (unless (or (vectorp value) (proper-list-p value))
    (jetpacs-component-authoring--schema-error path "must be an array"))
  (let ((members (if (vectorp value) (append value nil) value)))
    (when (and nonempty (null members))
      (jetpacs-component-authoring--schema-error path "must not be empty"))
    (when (> (length members) jetpacs-component-authoring-max-data-members)
      (jetpacs-component-authoring--schema-error path "array is too long"))
    (apply #'vector
           (cl-loop for member in members for index from 0
                    collect (funcall member-fn member
                                     (format "%s[%d]" path index))))))

(defun jetpacs-component-authoring--builder (path function &rest arguments)
  "Call public builder FUNCTION with ARGUMENTS or signal safely at PATH."
  (condition-case _error
      (apply function arguments)
    (error
     (jetpacs-component-authoring--schema-error
      path "violates the Jetpacs builder contract"))))

;;;; Action descriptors

(defun jetpacs-component-authoring--capture-fields (value path)
  "Normalize identifier array VALUE at PATH to a canonical vector."
  (jetpacs-component-authoring--array
   value path
   (lambda (member member-path)
     (jetpacs-component-authoring--bounded-string member member-path t))
   t))

(defun jetpacs-component-authoring--confirm (value path)
  "Normalize a remote confirmation VALUE at PATH."
  (if (stringp value)
      (jetpacs-component-authoring--bounded-string value path t)
    (let ((allowed '(:text :title :icon :confirm_label :dismiss_label)))
      (jetpacs-component-authoring--require-object value path allowed)
      (jetpacs-component-authoring--required value :text path)
      (cl-loop for key in allowed
               when (jetpacs-component-authoring--present-p value key)
               append
               (list key
                     (jetpacs-component-authoring--bounded-string
                      (jetpacs-component-authoring--fetch value key)
                      (format "%s%s" path key)
                      (eq key :text)))))))

(defun jetpacs-component-authoring--normalize-remote-descriptor (value path)
  "Normalize remote ActionDescriptor VALUE at PATH through `jetpacs-action'."
  (jetpacs-component-authoring--require-object
   value path jetpacs-component-authoring--remote-descriptor-fields)
  (let* ((name (jetpacs-component-authoring--bounded-string
                (jetpacs-component-authoring--required value :action path)
                (concat path ":action") t))
         (args-present (jetpacs-component-authoring--present-p value :args))
         (args
          (and args-present
               (let ((raw (jetpacs-component-authoring--fetch value :args)))
                 (unless (and (consp raw)
                              (jetpacs-component-authoring--plist-p raw))
                   (jetpacs-component-authoring--schema-error
                    (concat path ":args") "must be a non-empty object"))
                 (jetpacs-component-authoring--literal
                  raw (concat path ":args")))))
         (policy-present
          (jetpacs-component-authoring--present-p value :when_offline))
         (policy (and policy-present
                      (jetpacs-component-authoring--bounded-string
                       (jetpacs-component-authoring--fetch value :when_offline)
                       (concat path ":when_offline") t)))
         (dedupe-present (jetpacs-component-authoring--present-p value :dedupe))
         (dedupe (and dedupe-present
                      (jetpacs-component-authoring--bounded-string
                       (jetpacs-component-authoring--fetch value :dedupe)
                       (concat path ":dedupe") t)))
         (ttl-present (jetpacs-component-authoring--present-p value :ttl_s))
         (ttl (and ttl-present
                   (jetpacs-component-authoring--safe-number
                    (jetpacs-component-authoring--fetch value :ttl_s)
                    (concat path ":ttl_s"))))
         (confirm-present (jetpacs-component-authoring--present-p value :confirm))
         (confirm (and confirm-present
                       (jetpacs-component-authoring--confirm
                        (jetpacs-component-authoring--fetch value :confirm)
                        (concat path ":confirm"))))
         (capture-present
          (jetpacs-component-authoring--present-p value :capture_fields))
         (capture (and capture-present
                       (jetpacs-component-authoring--capture-fields
                        (jetpacs-component-authoring--fetch value :capture_fields)
                        (concat path ":capture_fields"))))
         (surface-present
          (jetpacs-component-authoring--present-p value :open_surface))
         (surface (and surface-present
                       (jetpacs-component-authoring--bounded-string
                        (jetpacs-component-authoring--fetch value :open_surface)
                        (concat path ":open_surface") t)))
         (builder-confirm
          (if (and confirm (listp confirm))
              (cl-loop for (key member) on confirm by #'cddr
                       append
                       (list (pcase key
                               (:confirm_label :confirm-label)
                               (:dismiss_label :dismiss-label)
                               (_ key))
                             member))
            confirm))
         ;; `jetpacs-action' only checks that :args starts with a global
         ;; keyword.  Validate the real arbitrary-key object ourselves, pass a
         ;; harmless marker through the public builder, then restore it.
         (builder-args (and args-present '(:data t)))
         (descriptor
          (jetpacs-component-authoring--builder
           path #'jetpacs-action name
           :args builder-args
           :when-offline policy
           :dedupe dedupe
           :ttl-s ttl
           :confirm builder-confirm
           :capture-fields (and capture (append capture nil))
           :open-surface surface)))
    (when args-present
      (setq descriptor (plist-put descriptor :args args)))
    descriptor))

(defun jetpacs-component-authoring--normalize-builtin-descriptor (value path)
  "Normalize builtin ActionDescriptor VALUE at PATH through public builders."
  (let* ((builtin (jetpacs-component-authoring--bounded-string
                   (jetpacs-component-authoring--required value :builtin path)
                   (concat path ":builtin") t))
         (schema (assoc builtin jetpacs-component-authoring--builtin-schemas)))
    (unless schema
      (jetpacs-component-authoring--schema-error path "names an unknown builtin"))
    (let ((required (nth 1 schema))
          (optional (nth 2 schema)))
      (jetpacs-component-authoring--require-object
       value path (append required optional))
      (dolist (key required)
        (jetpacs-component-authoring--required value key path))
      (pcase builtin
        ("view.switch"
         (jetpacs-component-authoring--builder
          path #'jetpacs-view-switch
          (jetpacs-component-authoring--bounded-string
           (jetpacs-component-authoring--fetch value :view)
           (concat path ":view") t)))
        ("variant.switch"
         (let ((id (jetpacs-component-authoring--bounded-string
                    (jetpacs-component-authoring--fetch value :id)
                    (concat path ":id") t))
               (present (jetpacs-component-authoring--present-p value :value)))
           (if present
               (jetpacs-component-authoring--builder
                path #'jetpacs-variant-switch id :value
                (jetpacs-component-authoring--bounded-string
                 (jetpacs-component-authoring--fetch value :value)
                 (concat path ":value") t))
             (jetpacs-component-authoring--builder
              path #'jetpacs-variant-switch id))))
        ("surface.open"
         (jetpacs-component-authoring--builder
          path #'jetpacs-surface-open
          (jetpacs-component-authoring--bounded-string
           (jetpacs-component-authoring--fetch value :surface)
           (concat path ":surface") t)))
        ("clipboard.copy"
         (jetpacs-component-authoring--builder
          path #'jetpacs-clipboard-copy
          (jetpacs-component-authoring--bounded-string
           (jetpacs-component-authoring--fetch value :text)
           (concat path ":text"))))
        ("share.send"
         (let ((text (jetpacs-component-authoring--bounded-string
                      (jetpacs-component-authoring--fetch value :text)
                      (concat path ":text")))
               (present (jetpacs-component-authoring--present-p value :title)))
           (if present
               (jetpacs-component-authoring--builder
                path #'jetpacs-share text :title
                (jetpacs-component-authoring--bounded-string
                 (jetpacs-component-authoring--fetch value :title)
                 (concat path ":title")))
             (jetpacs-component-authoring--builder path #'jetpacs-share text))))
        ("companion.settings.open"
         (jetpacs-component-authoring--builder path #'jetpacs-settings-open))
        ("trigger.fire"
         (jetpacs-component-authoring--builder
          path #'jetpacs-trigger-fire
          (jetpacs-component-authoring--bounded-string
           (jetpacs-component-authoring--fetch value :id)
           (concat path ":id") t)))
        ("dialog.submit"
         (let* ((value-present
                 (jetpacs-component-authoring--present-p value :value))
                (capture-present
                 (jetpacs-component-authoring--present-p value :capture_fields))
                (submitted
                 (and value-present
                      (jetpacs-component-authoring--scalar
                       (jetpacs-component-authoring--fetch value :value)
                       (concat path ":value"))))
                (capture
                 (and capture-present
                      (jetpacs-component-authoring--capture-fields
                       (jetpacs-component-authoring--fetch value :capture_fields)
                       (concat path ":capture_fields")))))
           (apply #'jetpacs-component-authoring--builder
                  path #'jetpacs-dialog-submit
                  (append (when value-present (list :value submitted))
                          (when capture-present
                            (list :capture-fields (append capture nil)))))))
        ("dialog.dismiss"
         (jetpacs-component-authoring--builder path #'jetpacs-dialog-dismiss))))))

(defun jetpacs-component-authoring--normalize-descriptor (value path)
  "Normalize one closed ActionDescriptor VALUE at PATH."
  (unless (jetpacs-component-authoring--plist-p value)
    (jetpacs-component-authoring--schema-error path "must be a descriptor plist"))
  (let ((action (jetpacs-component-authoring--present-p value :action))
        (builtin (jetpacs-component-authoring--present-p value :builtin)))
    (unless (and (or action builtin) (not (and action builtin)))
      (jetpacs-component-authoring--schema-error
       path "must contain exactly one of :action and :builtin"))
    (if action
        (jetpacs-component-authoring--normalize-remote-descriptor value path)
      (jetpacs-component-authoring--normalize-builtin-descriptor value path))))

;;;; Semantics and toolbar sub-objects

(defun jetpacs-component-authoring--fixed-object
    (value path required optional normalizers)
  "Normalize closed object VALUE at PATH.
REQUIRED and OPTIONAL are ordered keyword lists.  NORMALIZERS is an alist from
key to a unary-with-path normalizer; other members use scalar normalization."
  (jetpacs-component-authoring--require-object
   value path (append required optional))
  (dolist (key required)
    (jetpacs-component-authoring--required value key path))
  (cl-loop for key in (append required optional)
           when (jetpacs-component-authoring--present-p value key)
           append
           (let* ((member (jetpacs-component-authoring--fetch value key))
                  (normalizer (alist-get key normalizers)))
             (list key
                   (if normalizer
                       (funcall normalizer member (format "%s%s" path key))
                     (jetpacs-component-authoring--scalar
                      member (format "%s%s" path key)))))))

(defun jetpacs-component-authoring--normalize-semantics (value path)
  "Normalize Semantics VALUE at PATH through public semantics builders."
  (jetpacs-component-authoring--require-object
   value path jetpacs-component-authoring--semantics-fields)
  (let* ((collection-normalizer
          (lambda (member member-path)
            (jetpacs-component-authoring--fixed-object
             member member-path '(:row_count :column_count) nil nil)))
         (item-normalizer
          (lambda (member member-path)
            (jetpacs-component-authoring--fixed-object
             member member-path
             '(:row_index :row_span :column_index :column_span) nil nil)))
         (action-normalizer
          (lambda (member member-path)
            (jetpacs-component-authoring--fixed-object
             member member-path '(:label :on_action) nil
             `((:on_action . ,#'jetpacs-component-authoring--normalize-descriptor)))))
         (actions-normalizer
          (lambda (member member-path)
            (jetpacs-component-authoring--array
             member member-path action-normalizer)))
         (semantics
          (jetpacs-component-authoring--fixed-object
           value path nil jetpacs-component-authoring--semantics-fields
           `((:collection . ,collection-normalizer)
             (:collection_item . ,item-normalizer)
             (:actions . ,actions-normalizer))))
         (dummy (jetpacs-component-authoring--apply-semantics
                 (jetpacs-text "authoring validation") semantics path)))
    (plist-get dummy :semantics)))

(defun jetpacs-component-authoring--normalize-long-press (value path)
  "Normalize toolbar long-press operation VALUE at PATH."
  (jetpacs-component-authoring--require-object
   value path '(:snippet :on_tap :command :line))
  (let ((ops (cl-remove-if-not
              (lambda (key)
                (jetpacs-component-authoring--present-p value key))
              '(:snippet :on_tap :command :line))))
    (unless (= (length ops) 1)
      (jetpacs-component-authoring--schema-error
       path "must contain exactly one operation"))
    (let ((key (car ops)))
      (list key
            (if (eq key :on_tap)
                (jetpacs-component-authoring--normalize-descriptor
                 (jetpacs-component-authoring--fetch value key)
                 (concat path ":on_tap"))
              (jetpacs-component-authoring--scalar
               (jetpacs-component-authoring--fetch value key)
               (format "%s%s" path key)))))))

(defun jetpacs-component-authoring--normalize-toolbar-item
    (value path &optional menu-child)
  "Normalize ToolbarItem VALUE at PATH through `jetpacs-toolbar-item'.
MENU-CHILD rejects recursively nested menus."
  (jetpacs-component-authoring--require-object
   value path jetpacs-component-authoring--toolbar-fields)
  (when (and menu-child
             (jetpacs-component-authoring--present-p value :menu))
    (jetpacs-component-authoring--schema-error path "must not nest a menu"))
  (let* ((menu-normalizer
          (lambda (member member-path)
            (jetpacs-component-authoring--array
             member member-path
             (lambda (child child-path)
               (jetpacs-component-authoring--normalize-toolbar-item
                child child-path t))
             t)))
         (item
          (jetpacs-component-authoring--fixed-object
           value path nil jetpacs-component-authoring--toolbar-fields
           `((:on_tap . ,#'jetpacs-component-authoring--normalize-descriptor)
             (:menu . ,menu-normalizer)
             (:long_press . ,#'jetpacs-component-authoring--normalize-long-press))))
         args)
    (cl-loop for (key member) on item by #'cddr
             do (setq args
                      (append
                       args
                       (list
                        (pcase key
                          (:on_tap :on-tap)
                          (:long_press :long-press)
                          (_ key))
                        (if (eq key :menu) (append member nil) member)))))
    (apply #'jetpacs-component-authoring--builder
           path #'jetpacs-toolbar-item args)))

(defun jetpacs-component-authoring--normalize-toolbar (value path)
  "Normalize Editor toolbar VALUE at PATH."
  (if (stringp value)
      (jetpacs-component-authoring--bounded-string value path t)
    (jetpacs-component-authoring--array
     value path #'jetpacs-component-authoring--normalize-toolbar-item t)))

;;;; Node normalization and closed compilation

(defun jetpacs-component-authoring--node-schema (type)
  "Return private schema for node TYPE, or nil."
  (cdr (assoc type jetpacs-component-authoring--node-schemas)))

(defun jetpacs-component-authoring--normalize-attr-object (key value path)
  "Normalize structured universal attribute KEY's VALUE at PATH."
  (unless (consp value)
    (jetpacs-component-authoring--schema-error
     path "Must be a non-empty attribute object"))
  (let ((members
         (pcase key
           (:pad '(:start :top :end :bottom :horizontal :vertical))
           (:corner '(:top_start :top_end :bottom_start :bottom_end))
           (:border '(:width :color))
           (_ nil))))
    (jetpacs-component-authoring--fixed-object
     value path nil members nil)))

(defun jetpacs-component-authoring--normalize-tab-options (value path)
  "Normalize the non-empty closed Jetpacs tab option array VALUE at PATH."
  (let ((options
         (jetpacs-component-authoring--array
          value path
          (lambda (option option-path)
            (jetpacs-component-authoring--require-object
             option option-path '(:label :value))
            (let ((label
                   (jetpacs-component-authoring--bounded-string
                    (jetpacs-component-authoring--required
                     option :label option-path)
                    (concat option-path ":label") t))
                  (option-value
                   (jetpacs-component-authoring--bounded-string
                    (jetpacs-component-authoring--required
                     option :value option-path)
                    (concat option-path ":value") t)))
              (list :label label :value option-value)))
          t)))
    (let ((values
           (mapcar (lambda (option) (plist-get option :value))
                   (append options nil))))
      (unless (= (length values)
                 (length (delete-dups (copy-sequence values))))
        (jetpacs-component-authoring--schema-error
         path "must have unique option values")))
    options))

(defun jetpacs-component-authoring--normalize-section-options (value path)
  "Normalize hierarchical section option array VALUE at PATH."
  (let ((options
         (jetpacs-component-authoring--array
          value path
          (lambda (option option-path)
            (jetpacs-component-authoring--require-object
             option option-path '(:label :value :level))
            (let ((label
                   (jetpacs-component-authoring--bounded-string
                    (jetpacs-component-authoring--required
                     option :label option-path)
                    (concat option-path ":label") t))
                  (option-value
                   (jetpacs-component-authoring--bounded-string
                    (jetpacs-component-authoring--required
                     option :value option-path)
                    (concat option-path ":value") t))
                  (level
                   (jetpacs-component-authoring--required
                    option :level option-path)))
              (unless (and (integerp level) (<= 1 level 6))
                (jetpacs-component-authoring--schema-error
                 (concat option-path ":level")
                 "must be an integer from 1 to 6"))
              (list :label label :value option-value :level level)))
          t)))
    (let ((values
           (mapcar (lambda (option) (plist-get option :value))
                   (append options nil))))
      (unless (= (length values)
                 (length (delete-dups (copy-sequence values))))
        (jetpacs-component-authoring--schema-error
         path "must have unique section values")))
    options))

(defun jetpacs-component-authoring--normalize-node-member
    (type key value path)
  "Normalize TYPE node member KEY with VALUE at PATH."
  (cond
   ((memq key jetpacs-component-authoring--action-fields)
    (jetpacs-component-authoring--normalize-descriptor value path))
   ((eq key :semantics)
    (jetpacs-component-authoring--normalize-semantics value path))
   ((eq key :toolbar)
    (jetpacs-component-authoring--normalize-toolbar value path))
   ((and (equal type "jetpacs.tabs") (eq key :options))
    (jetpacs-component-authoring--normalize-tab-options value path))
   ((and (equal type "jetpacs.section_navigator") (eq key :options))
    (jetpacs-component-authoring--normalize-section-options value path))
   ((and (equal type "jetpacs.tabs") (eq key :variant))
    (let ((variant
           (jetpacs-component-authoring--bounded-string value path t)))
      (unless (member variant jetpacs-component-tabs-variants)
        (jetpacs-component-authoring--schema-error
         path "must name a supported Tabs presentation"))
      variant))
   ((eq key :selection)
    (jetpacs-component-authoring--array
     value path (lambda (member member-path)
                  (jetpacs-component-authoring--safe-number
                   member member-path))))
   ((memq key '(:pad :corner :border))
    (if (and (eq key :corner) (numberp value))
        (jetpacs-component-authoring--safe-number value path)
      (jetpacs-component-authoring--normalize-attr-object key value path)))
   ((and (equal type "text") (eq key :selectable)
         (and (symbolp value)
              (equal (symbol-name value) ":json-false")))
    ;; The public Text builder intentionally exposes selectability as a
    ;; presence flag, unlike tri-state EBP booleans on input components.
    (jetpacs-component-authoring--schema-error
     path "does not support explicit false; delete the member"))
   (t (jetpacs-component-authoring--scalar value path))))

(defun jetpacs-component-authoring--normalize-node (value expected-type path)
  "Normalize node VALUE as EXPECTED-TYPE at PATH, without evaluating data."
  (let* ((schema (jetpacs-component-authoring--node-schema expected-type))
         (required (plist-get schema :required))
         (optional (plist-get schema :optional))
         (own-fields (append required optional))
         (allowed (delete-dups
                   (append '(:t) own-fields
                           (copy-sequence jetpacs-universal-attributes)))))
    (unless schema
      (jetpacs-component-authoring--schema-error path "has an unsupported type"))
    (jetpacs-component-authoring--require-object value path allowed)
    (let ((type (jetpacs-component-authoring--required value :t path)))
      (unless (and (stringp type) (equal type expected-type))
        (jetpacs-component-authoring--schema-error
         path "has the wrong fixed node type")))
    (dolist (key required)
      (jetpacs-component-authoring--required value key path))
    (let ((id (jetpacs-component-authoring--fetch value :id)))
      (when (and (not (eq id jetpacs-component-authoring--missing))
                 (stringp id)
                 (string-prefix-p
                  jetpacs-component-authoring-reserved-id-prefix id))
        (jetpacs-component-authoring--schema-error
         (concat path ":id") "uses the reserved catalog control prefix")))
    (let (normalized)
      (dolist (key (delete-dups
                    (append own-fields
                            (copy-sequence jetpacs-universal-attributes))))
        (when (jetpacs-component-authoring--present-p value key)
          (let ((member (jetpacs-component-authoring--fetch value key)))
            (setq normalized
                  (append
                   normalized
                   (list
                    key
                    (if (eq key :children)
                        (let ((children
                               (jetpacs-component-authoring--array
                                member (concat path ":children")
                                (lambda (child child-path)
                                  (let ((index
                                         (string-to-number
                                          (or (and (string-match
                                                    "\\[\\([0-9]+\\)\\]\\'"
                                                    child-path)
                                                   (match-string 1 child-path))
                                              "-1"))))
                                    (jetpacs-component-authoring--normalize-node
                                     child
                                     (pcase index
                                       (0 "text")
                                       (1 "jetpacs.action")
                                       (_ "unsupported"))
                                     child-path))))))
                          (unless (= (length children) 2)
                            (jetpacs-component-authoring--schema-error
                             (concat path ":children")
                             "must contain exactly Text then Action"))
                          children)
                      (jetpacs-component-authoring--normalize-node-member
                       expected-type key member (format "%s%s" path key)))))))))
      (cons :t (cons expected-type normalized)))))

(defconst jetpacs-component-authoring--text-keywords
  '((:style . :style) (:font_weight . :font-weight) (:color . :color)
    (:selectable . :selectable) (:max_lines . :max-lines) (:syntax . :syntax))
  "Text wire keys mapped to public builder keywords.")

(defconst jetpacs-component-authoring--text-input-keywords
  '((:value . :value) (:hint . :hint) (:label . :label)
    (:on_change . :on-change) (:on_submit . :on-submit)
    (:single_line . :single-line) (:min_lines . :min-lines)
    (:max_lines . :max-lines) (:monospace . :monospace) (:syntax . :syntax)
    (:password . :password) (:keyboard . :keyboard) (:autofocus . :autofocus)
    (:clear_on_submit . :clear-on-submit) (:variant . :variant)
    (:is_error . :is-error) (:supporting_text . :supporting-text)
    (:prefix . :prefix) (:suffix . :suffix) (:leading_icon . :leading-icon)
    (:trailing_icon . :trailing-icon) (:max_length . :max-length)
    (:selection . :selection)
    (:hide_keyboard_on_submit . :hide-keyboard-on-submit)
    (:content_padding . :content-padding) (:mask . :mask) (:filter . :filter)
    (:enabled . :enabled))
  "Text Input wire keys mapped to public builder keywords.")

(defconst jetpacs-component-authoring--editor-keywords
  '((:document . :document) (:value . :value) (:on_save . :on-save)
    (:on_enter . :on-enter) (:single_line . :single-line)
    (:min_lines . :min-lines) (:max_lines . :max-lines)
    (:read_only . :read-only) (:syntax . :syntax)
    (:line_numbers . :line-numbers) (:complete . :complete)
    (:chromeless . :chromeless) (:publish_state . :publish-state)
    (:autofocus . :autofocus) (:toolbar . :toolbar) (:enabled . :enabled))
  "Editor wire keys mapped to public builder keywords.")

(defun jetpacs-component-authoring--keyword-arguments (node mapping)
  "Return public builder keyword arguments from NODE according to MAPPING."
  (cl-loop for (wire . builder) in mapping
           when (jetpacs-component-authoring--present-p node wire)
           append
           (list builder
                 (let ((value (jetpacs-component-authoring--fetch node wire)))
                   (cond
                    ((and (eq wire :selection) (vectorp value))
                     (append value nil))
                    ((and (eq wire :toolbar) (vectorp value))
                     (append value nil))
                    (t value))))))

(defun jetpacs-component-authoring--apply-semantics (node semantics path)
  "Return NODE with canonical SEMANTICS attached through public builders.
PATH identifies the semantics object in bounded diagnostics."
  (let (arguments)
    (dolist (key jetpacs-component-authoring--semantics-fields)
      (when (jetpacs-component-authoring--present-p semantics key)
        (let ((value (jetpacs-component-authoring--fetch semantics key)))
          (setq value
                (pcase key
                  (:collection
                   (jetpacs-component-authoring--builder
                    (concat path ":collection")
                    #'jetpacs-semantic-collection
                    (plist-get value :row_count)
                    (plist-get value :column_count)))
                  (:collection_item
                   (jetpacs-component-authoring--builder
                    (concat path ":collection_item")
                    #'jetpacs-semantic-collection-item
                    (plist-get value :row_index) (plist-get value :row_span)
                    (plist-get value :column_index)
                    (plist-get value :column_span)))
                  (:actions
                   (mapcar
                    (lambda (action)
                      (jetpacs-component-authoring--builder
                       (concat path ":actions") #'jetpacs-semantic-action
                       (plist-get action :label) (plist-get action :on_action)))
                    (append value nil)))
                  (_ value)))
          (setq arguments
                (append
                 arguments
                 (list
                  (pcase key
                    (:state_description :state-description)
                    (:pane_title :pane-title)
                    (:heading_level :heading-level)
                    (:live_region :live-region)
                    (:collection_item :collection-item)
                    (:traversal_group :traversal-group)
                    (:traversal_index :traversal-index)
                    (_ key))
                  value))))))
    (apply #'jetpacs-component-authoring--builder
           path #'jetpacs-with-semantics node arguments)))

(defun jetpacs-component-authoring--compile-node (node path)
  "Compile structurally normalized NODE at PATH through public builders."
  (let* ((type (plist-get node :t))
         (schema (jetpacs-component-authoring--node-schema type))
         (own-fields (append (plist-get schema :required)
                             (plist-get schema :optional)))
         (base
          (pcase type
            ("jetpacs.action"
             (apply #'jetpacs-component-authoring--builder
                    path #'jetpacs-component-action
                    (plist-get node :label) (plist-get node :on_tap)
                    (when (jetpacs-component-authoring--present-p node :enabled)
                      (list :enabled (plist-get node :enabled)))))
            ("jetpacs.choice"
             (apply #'jetpacs-component-authoring--builder
                    path #'jetpacs-component-choice
                    (plist-get node :id) (plist-get node :label)
                    (plist-get node :checked) (plist-get node :on_change)
                    (when (jetpacs-component-authoring--present-p node :enabled)
                      (list :enabled (plist-get node :enabled)))))
            ("jetpacs.tabs"
             (apply
              #'jetpacs-component-authoring--builder
              path #'jetpacs-component-tabs
              (plist-get node :id)
              (mapcar
               (lambda (option)
                 (jetpacs-component-authoring--builder
                  (concat path ":options") #'jetpacs-component-tab
                  (plist-get option :label) (plist-get option :value)))
               (append (plist-get node :options) nil))
              (plist-get node :value) (plist-get node :on_change)
              (append
               (when (jetpacs-component-authoring--present-p node :enabled)
                 (list :enabled (plist-get node :enabled)))
               (when (jetpacs-component-authoring--present-p node :scrollable)
                 (list :scrollable (plist-get node :scrollable)))
               (when (jetpacs-component-authoring--present-p node :pinned)
                 (list :pinned (plist-get node :pinned)))
               (when (jetpacs-component-authoring--present-p node :variant)
                 (list :variant (plist-get node :variant))))))
            ("jetpacs.section_navigator"
             (apply
              #'jetpacs-component-authoring--builder
              path #'jetpacs-component-section-navigator
              (plist-get node :id)
              (mapcar
               (lambda (section)
                 (jetpacs-component-authoring--builder
                  (concat path ":options") #'jetpacs-component-section
                  (plist-get section :label) (plist-get section :value)
                  (plist-get section :level)))
               (append (plist-get node :options) nil))
              (plist-get node :value) (plist-get node :on_change)
              (append
               (when (jetpacs-component-authoring--present-p node :enabled)
                 (list :enabled (plist-get node :enabled)))
               (when (jetpacs-component-authoring--present-p node :pinned)
                 (list :pinned (plist-get node :pinned))))))
            ("jetpacs.panel"
             (jetpacs-component-authoring--builder
              path #'jetpacs-component-panel (plist-get node :label)
              (cl-loop for child across (plist-get node :children)
                       for index from 0
                       collect (jetpacs-component-authoring--compile-node
                                child (format "%s:children[%d]" path index)))))
            ("text"
             (apply #'jetpacs-component-authoring--builder
                    path #'jetpacs-text (plist-get node :text)
                    (jetpacs-component-authoring--keyword-arguments
                     node jetpacs-component-authoring--text-keywords)))
            ("text_input"
             (apply #'jetpacs-component-authoring--builder
                    path #'jetpacs-text-input (plist-get node :id)
                    (jetpacs-component-authoring--keyword-arguments
                     node jetpacs-component-authoring--text-input-keywords)))
            ("editor"
             (apply #'jetpacs-component-authoring--builder
                    path #'jetpacs-editor (plist-get node :id)
                    (jetpacs-component-authoring--keyword-arguments
                     node jetpacs-component-authoring--editor-keywords)))
            (_ (jetpacs-component-authoring--schema-error
                path "has an unsupported type"))))
         attrs)
    (dolist (key jetpacs-universal-attributes)
      (when (and (not (eq key :semantics))
                 (not (memq key own-fields))
                 (jetpacs-component-authoring--present-p node key))
        (setq attrs (append attrs (list key (plist-get node key))))))
    (when attrs
      (setq base
            (apply #'jetpacs-component-authoring--builder
                   path #'jetpacs-with-attrs base attrs)))
    (if (jetpacs-component-authoring--present-p node :semantics)
        (jetpacs-component-authoring--apply-semantics
         base (plist-get node :semantics) (concat path ":semantics"))
      base)))

;;;; Public document API

(defun jetpacs-component-authoring-component-schema (component)
  "Return immutable structural metadata for catalog COMPONENT.
The result contains `:component', `:root-type', `:root-path' (always nil), and
`:fixed-children'.  Panel's fixed children carry wire-key paths relative to
`:root'; all other components return an empty vector.  Return nil for an
unsupported component."
  (when-let* ((type (cdr (assoc component
                               jetpacs-component-authoring--component-root-types))))
    (copy-tree
     (list :component component :root-type type :root-path nil
           :fixed-children
           (if (equal component "panel")
               [(:path (:children 0) :type "text" :label "Text")
                (:path (:children 1) :type "jetpacs.action" :label "Action")]
             []))
     t)))

(defun jetpacs-component-authoring--field-kind (type field)
  "Return coarse GUI-relevant kind for TYPE's FIELD."
  (cond
   ((memq field jetpacs-component-authoring--action-fields) 'action-descriptor)
   ((and (equal type "jetpacs.tabs") (eq field :options)) 'tabs-options)
   ((and (equal type "jetpacs.tabs") (eq field :value)) 'tabs-value)
   ((and (equal type "jetpacs.tabs") (eq field :pinned)) 'tabs-pinned)
   ((and (equal type "jetpacs.tabs") (eq field :variant))
    'tabs-presentation)
   ((and (equal type "jetpacs.tabs") (eq field :scrollable))
    'tabs-presentation-shadow)
   ((and (equal type "jetpacs.section_navigator") (eq field :options))
    'section-options)
   ((and (equal type "jetpacs.section_navigator") (eq field :value))
    'section-value)
   ((and (equal type "jetpacs.section_navigator") (eq field :pinned))
    'section-pinned)
   ((eq field :semantics) 'semantics-object)
   ((eq field :toolbar) 'toolbar)
   ((eq field :children) 'fixed-children)
   ((eq field :selection) 'selection)
   ((eq field :pad) 'padding)
   ((eq field :corner) 'corner)
   ((eq field :border) 'border)
   ((and (equal type "text") (eq field :selectable)) 'presence-boolean)
   (t 'scalar)))

(defun jetpacs-component-authoring-field-schema (type &optional field)
  "Return enumerable schema metadata for supported node TYPE.
With FIELD nil, return `:required', `:optional', `:universal', and the
duplicate-free ordered `:fields' list.  With a wire keyword FIELD, return its
`:name', `:wire-name', required/universal booleans, and coarse `:kind', or nil
when FIELD is unavailable.  This metadata describes structure; generated
vocabulary remains the authority for detailed scalar types and enums."
  (when-let* ((schema (jetpacs-component-authoring--node-schema type)))
    (let* ((required (copy-sequence (plist-get schema :required)))
           (optional (copy-sequence (plist-get schema :optional)))
           (universal (copy-sequence jetpacs-universal-attributes))
           (fields (delete-dups
                    (append required optional (copy-sequence universal)))))
      (if field
          (when (memq field fields)
            (list :name field
                  :wire-name (substring (symbol-name field) 1)
                  :required (if (memq field required) t :json-false)
                  :universal (if (memq field universal) t :json-false)
                  :kind (jetpacs-component-authoring--field-kind type field)))
        (list :type type :required required :optional optional
              :universal universal :fields fields)))))

(defun jetpacs-component-authoring-normalize-document (document)
  "Return canonical, validated component specimen DOCUMENT.
DOCUMENT must be exactly `(:schema-version 1 :component ID :root NODE)'.
The component chooses NODE's fixed root type.  Normalization is pure, rejects
unknown and duplicate members, preserves explicit `:json-false', and invokes
only closed public builders; no authored form is evaluated."
  (jetpacs-component-authoring--require-object
   document "document" '(:schema-version :component :root))
  (let* ((version
          (jetpacs-component-authoring--required
           document :schema-version "document"))
         (component
          (jetpacs-component-authoring--bounded-string
           (jetpacs-component-authoring--required document :component "document")
           "document:component" t))
         (type (cdr (assoc component
                           jetpacs-component-authoring--component-root-types))))
    (unless (and (integerp version)
                 (= version jetpacs-component-authoring-schema-version))
      (jetpacs-component-authoring--schema-error
       "document:schema-version" "Is unsupported"))
    (unless type
      (jetpacs-component-authoring--schema-error
       "document:component" "Is unsupported"))
    (let* ((structural
            (jetpacs-component-authoring--normalize-node
             (jetpacs-component-authoring--required document :root "document")
             type "document:root"))
           (normalized
            (list :schema-version jetpacs-component-authoring-schema-version
                  :component component :root structural)))
      ;; Compilation is a validation boundary, but its derived compatibility
      ;; members are presentation IR rather than authored document state.
      (jetpacs-component-authoring--compile-node structural "document:root")
      ;; Canonical printing supplies the whole-document resource bound and
      ;; catches circular or otherwise unprintable data before it becomes a
      ;; process-local draft.
      (jetpacs-authoring-print
       normalized jetpacs-component-authoring-max-bytes)
      normalized)))

(defun jetpacs-component-authoring-read-document (source)
  "Safely read and normalize exactly one inert component document from SOURCE.
Reader evaluation, read labels, trailing forms, and global symbol interning are
disabled by `jetpacs-authoring-read-one'."
  (jetpacs-component-authoring-normalize-document
   (jetpacs-authoring-read-one
    source jetpacs-component-authoring-max-bytes)))

(defun jetpacs-component-authoring-print-document (document)
  "Return deterministic canonical Lisp text for component DOCUMENT."
  (jetpacs-authoring-print
   (jetpacs-component-authoring-normalize-document document)
   jetpacs-component-authoring-max-bytes))

(defun jetpacs-component-authoring-document-digest (document)
  "Return DOCUMENT's lowercase SHA-256 canonical-text digest."
  (jetpacs-authoring-digest
   (jetpacs-component-authoring-normalize-document document)
   jetpacs-component-authoring-max-bytes))

(defun jetpacs-component-authoring-compile-document (document)
  "Compile inert DOCUMENT to its canonical EBP node plist.
Compilation first normalizes the entire document and reaches rendering IR only
through known public builders.  It never resolves or evaluates an authored
symbol as code."
  (jetpacs-component-authoring--compile-node
   (plist-get (jetpacs-component-authoring-normalize-document document) :root)
   "document:root"))

(defun jetpacs-component-authoring-default-document (component)
  "Return a fresh canonical authored default for catalog COMPONENT."
  (let ((raw
         (pcase component
           ("action"
            '(:schema-version 1 :component "action"
              :root (:t "jetpacs.action" :label "Run ordinary action"
                     :on_tap (:action "jpcatalog.activate"))))
           ("choice"
            '(:schema-version 1 :component "choice"
              :root (:t "jetpacs.choice" :id "jpcatalog-choice-specimen"
                     :label "Mirror catalog updates" :checked t
                     :on_change (:action "jpcatalog.choice"))))
           ("tabs"
            '(:schema-version 1 :component "tabs"
              :root (:t "jetpacs.tabs" :id "jpcatalog-tabs-specimen"
                     :options [(:label "Preview" :value "preview")
                               (:label "Visual" :value "visual")
                               (:label "Lisp" :value "lisp")]
                     :value "preview"
                     :on_change (:action "jpcatalog.tabs")
                     :variant "adaptive"
                     :pinned :json-false)))
           ("section-navigator"
            '(:schema-version 1 :component "section-navigator"
              :root
              (:t "jetpacs.section_navigator"
               :id "jpcatalog-section-navigator-specimen"
               :options [(:label "Overview" :value "overview" :level 1)
                         (:label "Behavior" :value "behavior" :level 2)
                         (:label "Accessibility" :value "accessibility"
                          :level 2)
                         (:label "Examples" :value "examples" :level 1)]
               :value "overview"
               :on_change (:action "jpcatalog.section.navigate")
               :pinned :json-false)))
           ("panel"
            '(:schema-version 1 :component "panel"
              :root (:t "jetpacs.panel" :label "EDITABLE PANEL"
                     :children
                     [(:t "text" :text "Text and action remain independent.")
                      (:t "jetpacs.action" :label "Run nested action"
                       :on_tap (:action "jpcatalog.activate"))])))
           ("text-field"
            '(:schema-version 1 :component "text-field"
              :root (:t "text_input" :id "jpcatalog-text-specimen"
                     :label "Command text" :hint "Type and press Done"
                     :single_line t
                     :on_change (:action "jpcatalog.text-change")
                     :on_submit (:action "jpcatalog.text-submit"))))
           ("editor"
            '(:schema-version 1 :component "editor"
              :root (:t "editor" :id "jpcatalog-editor-specimen"
                     :value "; Local Jetpacs draft\n(message \"Edit me\")\n"
                     :on_save (:action "jpcatalog.editor-save")
                     :min_lines 5 :max_lines 8 :syntax "elisp"
                     :line_numbers t :publish_state t)))
           (_ (jetpacs-component-authoring--schema-error
               "component" "Is unsupported")))))
    (jetpacs-component-authoring-normalize-document (copy-tree raw t))))

;;;; Immutable path edits

(defun jetpacs-component-authoring--path-key (step path)
  "Return canonical key for STEP or signal for edit PATH."
  (unless (and (symbolp step)
               (string-prefix-p ":" (symbol-name step)))
    (jetpacs-component-authoring--schema-error path "contains a non-key step"))
  (jetpacs-component-authoring--canonical-key step))

(defun jetpacs-component-authoring--plist-put-by-name (plist key value)
  "Return PLIST with name-equivalent KEY set to VALUE, without mutation."
  (let (out found)
    (cl-loop for (old-key old-value) on plist by #'cddr
             do (if (jetpacs-component-authoring--key-equal-p old-key key)
                    (progn
                      (setq out (append out (list key value)))
                      (setq found t))
                  (setq out (append out (list old-key old-value)))))
    (unless found (setq out (append out (list key value))))
    out))

(defun jetpacs-component-authoring--plist-delete-by-name (plist key)
  "Return PLIST without name-equivalent KEY, without mutation."
  (cl-loop for (old-key old-value) on plist by #'cddr
           unless (jetpacs-component-authoring--key-equal-p old-key key)
           append (list old-key old-value)))

(defun jetpacs-component-authoring--edit-path (value path replacement deletep here)
  "Return VALUE edited at PATH with REPLACEMENT.
DELETEP removes the final member; HERE is a bounded diagnostic path."
  (if (null path)
      (if deletep
          (jetpacs-component-authoring--schema-error here "cannot delete the root")
        replacement)
    (let ((step (car path))
          (rest (cdr path)))
      (cond
     ((integerp step)
      (unless (vectorp value)
        (jetpacs-component-authoring--schema-error here "does not address an array"))
      (unless (<= 0 step (1- (length value)))
        (jetpacs-component-authoring--schema-error here "has an out-of-range index"))
      (if (null rest)
          (if deletep
              (apply #'vector
                     (append (seq-subseq value 0 step) nil
                             (seq-subseq value (1+ step)) nil))
            (let ((copy (copy-sequence value)))
              (aset copy step replacement)
              copy))
        (let ((copy (copy-sequence value)))
          (aset copy step
                (jetpacs-component-authoring--edit-path
                 (aref value step) rest replacement deletep
                 (format "%s[%d]" here step)))
          copy)))
     ((symbolp step)
      (unless (jetpacs-component-authoring--plist-p value)
        (jetpacs-component-authoring--schema-error here "does not address an object"))
      (let* ((key (jetpacs-component-authoring--path-key step here))
             (existing (jetpacs-component-authoring--fetch value key)))
        (if (null rest)
            (if deletep
                (progn
                  (when (eq existing jetpacs-component-authoring--missing)
                    (jetpacs-component-authoring--schema-error
                     here "does not contain the deleted member"))
                  (jetpacs-component-authoring--plist-delete-by-name value key))
              (jetpacs-component-authoring--plist-put-by-name
               value key replacement))
          (when (eq existing jetpacs-component-authoring--missing)
            (jetpacs-component-authoring--schema-error
             here "does not contain an intermediate member"))
          (jetpacs-component-authoring--plist-put-by-name
           value key
           (jetpacs-component-authoring--edit-path
            existing rest replacement deletep
            (concat here (symbol-name key)))))))
       (t (jetpacs-component-authoring--schema-error
           here "contains an invalid path step"))))))

(defun jetpacs-component-authoring-set-at-path (document path value)
  "Return normalized DOCUMENT with root-relative wire PATH set to VALUE.
PATH is a proper list of keyword keys and zero-based vector indices, for
example `(:children 0 :text)'.  The input document is never mutated, and the
result must still satisfy the complete component contract.  Nil PATH replaces
the whole root."
  (unless (proper-list-p path)
    (jetpacs-component-authoring--schema-error "path" "Must be a proper list"))
  (let* ((normalized (jetpacs-component-authoring-normalize-document document))
         (copy (copy-tree normalized t))
         (root (jetpacs-component-authoring--edit-path
                (plist-get copy :root) path value nil "root")))
    (jetpacs-component-authoring-normalize-document
     (plist-put copy :root root))))

(defun jetpacs-component-authoring-delete-at-path (document path)
  "Return normalized DOCUMENT with the member at root-relative PATH deleted.
PATH uses wire keywords and vector indices.  Deleting a required member or a
fixed Panel child is rejected by whole-document validation.  DOCUMENT is not
mutated."
  (unless (and (proper-list-p path) path)
    (jetpacs-component-authoring--schema-error
     "path" "Must be a non-empty proper list"))
  (let* ((normalized (jetpacs-component-authoring-normalize-document document))
         (copy (copy-tree normalized t))
         (root (jetpacs-component-authoring--edit-path
                (plist-get copy :root) path nil t "root")))
    (jetpacs-component-authoring-normalize-document
     (plist-put copy :root root))))

(defun jetpacs-component-authoring--set-selection-option-value
    (document component index value)
  "Return selection DOCUMENT for COMPONENT with INDEX renamed to VALUE."
  (let* ((normalized
          (jetpacs-component-authoring-normalize-document document))
         (root (plist-get normalized :root))
         (options (plist-get root :options)))
    (unless (equal (plist-get normalized :component) component)
      (jetpacs-component-authoring--schema-error
       "document:component" "Does not match the option editor"))
    (unless (and (integerp index) (<= 0 index) (< index (length options)))
      (jetpacs-component-authoring--schema-error
       "document:root:options" "Has an out-of-range index"))
    (setq value
          (jetpacs-component-authoring--bounded-string
           value "document:root:options:value" t))
    (let* ((old-value (plist-get (aref options index) :value))
           (copy (copy-tree normalized t))
           (changed
            (jetpacs-component-authoring--edit-path
             (plist-get copy :root) (list :options index :value)
             value nil "root")))
      (when (equal (plist-get root :value) old-value)
        (setq changed
              (jetpacs-component-authoring--edit-path
               changed '(:value) value nil "root")))
      (jetpacs-component-authoring-normalize-document
       (plist-put copy :root changed)))))

(defun jetpacs-component-authoring--delete-selection-option
    (document component index)
  "Return selection DOCUMENT for COMPONENT without option INDEX."
  (let* ((normalized
          (jetpacs-component-authoring-normalize-document document))
         (root (plist-get normalized :root))
         (options (plist-get root :options)))
    (unless (equal (plist-get normalized :component) component)
      (jetpacs-component-authoring--schema-error
       "document:component" "Does not match the option editor"))
    (unless (and (integerp index) (<= 0 index) (< index (length options)))
      (jetpacs-component-authoring--schema-error
       "document:root:options" "Has an out-of-range index"))
    (when (= (length options) 1)
      (jetpacs-component-authoring--schema-error
       "document:root:options" "Must retain at least one option"))
    (let* ((deleted-value (plist-get (aref options index) :value))
           (copy (copy-tree normalized t))
           (changed
            (jetpacs-component-authoring--edit-path
             (plist-get copy :root) (list :options index) nil t "root")))
      (when (equal (plist-get root :value) deleted-value)
        (setq changed
              (jetpacs-component-authoring--edit-path
               changed '(:value)
               (plist-get (aref (plist-get changed :options) 0) :value)
               nil "root")))
      (jetpacs-component-authoring-normalize-document
       (plist-put copy :root changed)))))

(defun jetpacs-component-authoring-set-tab-option-value
    (document index value)
  "Return Tabs DOCUMENT with option INDEX atomically renamed to VALUE.
When the renamed option is selected, its controlled root value changes in the
same whole-document validation transaction.  DOCUMENT is never mutated."
  (jetpacs-component-authoring--set-selection-option-value
   document "tabs" index value))

(defun jetpacs-component-authoring-delete-tab-option (document index)
  "Return Tabs DOCUMENT without option INDEX, preserving a valid selection.
At least one option must remain.  If INDEX names the selected option, the
first remaining option becomes the controlled value atomically.  DOCUMENT is
never mutated."
  (jetpacs-component-authoring--delete-selection-option
   document "tabs" index))

(defun jetpacs-component-authoring-set-section-option-value
    (document index value)
  "Return Section Navigator DOCUMENT with INDEX atomically renamed to VALUE.
When that section is selected, the controlled root value changes in the same
whole-document validation transaction.  DOCUMENT is never mutated."
  (jetpacs-component-authoring--set-selection-option-value
   document "section-navigator" index value))

(defun jetpacs-component-authoring-delete-section-option (document index)
  "Return Section Navigator DOCUMENT without option INDEX.
At least one section remains, and deleting the selected section atomically
selects the first remaining section.  DOCUMENT is never mutated."
  (jetpacs-component-authoring--delete-selection-option
   document "section-navigator" index))

(defun jetpacs-component-authoring-set-tabs-presentation
    (document presentation)
  "Return Tabs DOCUMENT using atomic PRESENTATION compatibility state.
PRESENTATION is `legacy-fixed', `legacy-scrollable', or one of
`jetpacs-component-tabs-variants'.  Variant documents store only `:variant';
legacy documents store no variant and only the true scrollable flag when
needed.  Derived compatibility booleans appear only in compiled renderer IR."
  (unless (member presentation
                  (append '("legacy-fixed" "legacy-scrollable")
                          jetpacs-component-tabs-variants))
    (jetpacs-component-authoring--schema-error
     "document:root:presentation" "Is unsupported"))
  (let* ((normalized
          (jetpacs-component-authoring-normalize-document document))
         (copy (copy-tree normalized t))
         (root (plist-get copy :root)))
    (unless (equal (plist-get normalized :component) "tabs")
      (jetpacs-component-authoring--schema-error
       "document:component" "Must be tabs for presentation editing"))
    (setq root
          (jetpacs-component-authoring--plist-delete-by-name root :variant))
    (setq root
          (jetpacs-component-authoring--plist-delete-by-name root :scrollable))
    (pcase presentation
      ("legacy-fixed" nil)
      ("legacy-scrollable"
       (setq root
             (jetpacs-component-authoring--plist-put-by-name
              root :scrollable t)))
      (_
       (setq root
             (jetpacs-component-authoring--plist-put-by-name
              root :variant presentation))))
    (jetpacs-component-authoring-normalize-document
     (plist-put copy :root root))))

(provide 'jetpacs-component-authoring)
;;; jetpacs-component-authoring.el ends here
