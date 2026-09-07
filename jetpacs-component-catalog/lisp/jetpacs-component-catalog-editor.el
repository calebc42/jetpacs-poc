;;; jetpacs-component-catalog-editor.el --- Component specimen forms -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Schema-driven controls for the Jetpacs Components catalog.  This module is
;; presentation only: every control emits a bounded, digest-addressed catalog
;; action.  The owning catalog performs the immutable edit and validates the
;; complete inert document before replacing its last valid draft.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'jetpacs-authoring)
(require 'jetpacs-component-authoring)
(require 'jetpacs-components)
(require 'jetpacs-surfaces)
(require 'jetpacs-vocabulary)
(require 'jetpacs-widgets)

(defconst jetpacs-component-catalog-editor--reserved-id-prefix
  "jpcatalog-authoring-"
  "Identifier prefix reserved for catalog authoring controls.")

(defconst jetpacs-component-catalog-editor--type-overrides
  '(("style" . "enum")
    ("pad" . "padding-object")
    ("min_width" . "number")
    ("max_width" . "number")
    ("min_height" . "number")
    ("max_height" . "number")
    ("fill_fraction" . "number")
    ("aspect_ratio" . "number")
    ("weight" . "number")
    ("corner" . "corner")
    ("border" . "border")
    ("alpha" . "number")
    ("align_self" . "enum")
    ("action" . "identifier")
    ("builtin" . "enum")
    ("args" . "lisp-data")
    ("when_offline" . "enum")
    ("dedupe" . "identifier")
    ("ttl_s" . "positive-integer")
    ("confirm" . "lisp-data")
    ("capture_fields" . "lisp-data")
    ("open_surface" . "identifier")
    ("surface" . "identifier")
    ("view" . "identifier"))
  "Small explicit type overlay for contract fields lacking `field_types'.")

(defconst jetpacs-component-catalog-editor--number-types
  '("number" "finite-number" "dp" "non-negative-dp" "font-weight")
  "Field kinds edited as finite numbers.")

(defconst jetpacs-component-catalog-editor--true-by-default '(:enabled)
  "Optional boolean members whose absence means true (SPEC 17.4).
An absent member's switch is seeded from this effective default, so the
control shows the state the specimen actually has.")

(defconst jetpacs-component-catalog-editor--integer-types
  '("positive-integer" "non-negative-integer" "integer-1-6"
    "integer-1-31" "integer-1-12")
  "Field kinds edited as integers.")

(defun jetpacs-component-catalog-editor--path-wire (path)
  "Return authoring PATH in the JSON-safe action representation."
  (vconcat
   (mapcar (lambda (part)
             (if (keywordp part) (substring (symbol-name part) 1) part))
           path)))

(defun jetpacs-component-catalog-editor--control-id (component path suffix)
  "Return a reserved stable id for COMPONENT, PATH, and SUFFIX."
  (format "%s%s-%s-%s"
          jetpacs-component-catalog-editor--reserved-id-prefix component
          (substring (secure-hash 'sha256 (prin1-to-string path)) 0 12)
          suffix))

(defun jetpacs-component-catalog-editor--label (field)
  "Return a human label for wire FIELD."
  (capitalize (string-replace "_" " " (substring (symbol-name field) 1))))

(defun jetpacs-component-catalog-editor--path-value (document path)
  "Return the value at root-relative PATH in DOCUMENT."
  (let ((value (plist-get document :root)))
    (dolist (part path value)
      (setq value
            (if (integerp part)
                (and (vectorp value) (< part (length value)) (aref value part))
              (and (listp value) (plist-get value part)))))))

(defun jetpacs-component-catalog-editor--path-present-p (document path)
  "Return non-nil when root-relative PATH is present in DOCUMENT."
  (if (null path)
      t
    (let ((parent (jetpacs-component-catalog-editor--path-value
                   document (butlast path)))
          (last (car (last path))))
      (if (integerp last)
          (and (vectorp parent) (< last (length parent)))
        (and (listp parent) (plist-member parent last))))))

(cl-defun jetpacs-component-catalog-editor-action
    (component digest path codec &key literal index direction input-id)
  "Return one catalog edit descriptor.
COMPONENT and DIGEST identify the current draft, PATH addresses its root,
and CODEC tells the handler how to decode an injected value.  LITERAL is
printed as inert data for button-driven edits.  INDEX and DIRECTION describe
bounded vector operations.  INPUT-ID, when non-nil, is the catalog-owned
stateful control whose locally retained draft must be reconciled after save."
  (jetpacs-action
   "jpcatalog.edit"
   :args
   (jetpacs-make-node
    nil :component component :digest digest
    :path (jetpacs-component-catalog-editor--path-wire path)
    :codec codec
    :literal_source (and literal (jetpacs-authoring-print literal 8192))
    :index index :direction direction :input_id input-id)))

(defun jetpacs-component-catalog-editor--boolean-action
    (component digest path &optional presence)
  "Return a boolean-injecting edit descriptor for COMPONENT at DIGEST.
PATH is encoded exactly like the other catalog editor paths, while the
dedicated action keeps a switch's or Choice's native boolean value type
honest.  PRESENCE non-nil names a presence-only flag: true is written and
false removes the member instead of writing false."
  (jetpacs-action
   "jpcatalog.edit.boolean"
   :args
   (jetpacs-make-node
    nil :component component :digest digest
    :path (jetpacs-component-catalog-editor--path-wire path)
    :codec (and presence "injected-presence"))))

(defun jetpacs-component-catalog-editor--remove-button
    (component digest path label)
  "Return an explicit unset button for COMPONENT at DIGEST.
PATH identifies the member and LABEL names it."
  (jetpacs-icon-button
   "remove_circle_outline"
   (jetpacs-component-catalog-editor-action
    component digest path "unset")
   :content-description (format "Use the default for %s" label)))

(defun jetpacs-component-catalog-editor--enum-values (type field)
  "Return generated enum values for node TYPE and FIELD, or nil."
  (or (pcase field
        (:when_offline jetpacs-action-offline-policies)
        (:builtin (mapcar #'car (cdr jetpacs-action-descriptor-schema)))
        (:live_region jetpacs-semantic-live-regions)
        (_ nil))
      (cdr (assoc (format "%s.%s" type (substring (symbol-name field) 1))
                  jetpacs-contract-enums))
      (cdr (assoc (substring (symbol-name field) 1) jetpacs-contract-enums))))

(defun jetpacs-component-catalog-editor--value-type (type field)
  "Return the editor value type for node TYPE's FIELD."
  (let ((name (substring (symbol-name field) 1)))
    (cond
     ((member name jetpacs-action-hook-keys) "action-descriptor")
     ((and (member type '("text_input" "editor"))
           (equal name "value"))
      "string")
     ((equal name "toolbar") "toolbar")
     ((equal name "semantics") "semantics-object")
     ((equal name "selection") "selection")
     ((equal type "semantics")
      (or (cdr (assoc name (plist-get jetpacs-semantics-schema :field-types)))
          (cl-loop for (_object schema) in jetpacs-semantic-object-schema
                   for found = (cdr (assoc name
                                           (plist-get schema :field-types)))
                   when found return found)
          "lisp-data"))
     ((cdr (assoc name jetpacs-component-catalog-editor--type-overrides)))
     ((cdr (assoc name jetpacs-field-types)))
     (t "lisp-data"))))

(defun jetpacs-component-catalog-editor--enum-options (values optional)
  "Return dropdown options for VALUES, including Default when OPTIONAL."
  (append
   (when optional (list (jetpacs-enum-option "Default / unset" "__unset")))
   (mapcar (lambda (value)
             (jetpacs-enum-option
              (capitalize (string-replace "_" " " value)) value))
           values)))

(defun jetpacs-component-catalog-editor--field-row
    (component digest document type path field required)
  "Return one scalar control for COMPONENT's FIELD.
DIGEST addresses DOCUMENT, TYPE supplies enum context, PATH is root-relative,
and REQUIRED controls whether the value can be removed."
  (let* ((field-path (append path (list field)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document field-path))
         (value (and present (jetpacs-component-catalog-editor--path-value
                             document field-path)))
         (label (jetpacs-component-catalog-editor--label field))
         (value-type (jetpacs-component-catalog-editor--value-type type field))
         (id (jetpacs-component-catalog-editor--control-id
              component field-path "value"))
         (optional (not required))
         control)
    (when (and (equal value-type "corner") (or (numberp value) (not present)))
      (setq value-type "number"))
    (when (and (equal value-type "toolbar") (stringp value))
      (setq value-type "identifier"))
    (setq
     control
     (cond
      ((equal value-type "boolean")
       (jetpacs-switch
        id
        :label label
        :checked (jetpacs-bool
                  (if present
                      (eq value t)
                    (memq field
                          jetpacs-component-catalog-editor--true-by-default)))
        :on-change (jetpacs-component-catalog-editor--boolean-action
                    component digest field-path)))
      ((or (equal value-type "enum")
           (jetpacs-component-catalog-editor--enum-values type field))
       (let ((values (or (jetpacs-component-catalog-editor--enum-values
                          type field)
                         (pcase field
                           (:align_self '("start" "center" "end" "stretch"))
                           (_ nil)))))
         (if values
             (jetpacs-dropdown
              id (jetpacs-component-catalog-editor--enum-options
                  values optional)
              :label label :value (or value "__unset")
              :on-change (jetpacs-component-catalog-editor-action
                          component digest field-path "enum" :input-id id))
           (jetpacs-text-input
            id :label label :value (or value "") :single-line t
            :on-submit (jetpacs-component-catalog-editor-action
                        component digest field-path "string" :input-id id)))))
      ((member value-type jetpacs-component-catalog-editor--integer-types)
       (jetpacs-text-input
        id :label label :value (if present (number-to-string value) "")
        :keyboard "number" :single-line t
        :on-submit (jetpacs-component-catalog-editor-action
                    component digest field-path "integer" :input-id id)))
      ((member value-type jetpacs-component-catalog-editor--number-types)
       (jetpacs-text-input
        id :label label :value (if present (number-to-string value) "")
        :keyboard "decimal" :single-line t
        :on-submit (jetpacs-component-catalog-editor-action
                    component digest field-path "number" :input-id id)))
      ((and (member field '(:text :value)) (equal value-type "string"))
       (jetpacs-editor
        id :value (or value "") :min-lines 2 :max-lines 6
        :on-save (jetpacs-component-catalog-editor-action
                  component digest field-path "string" :input-id id)))
      ((member value-type '("string" "identifier" "color"
                            "non-empty-plain-string"))
       (jetpacs-text-input
        id :label label :value (or value "") :single-line t
        :on-submit (jetpacs-component-catalog-editor-action
                    component digest field-path "string" :input-id id)))
      (t
       (jetpacs-editor
        id :value (if present
                      (string-trim-right (jetpacs-authoring-print value 8192))
                    "")
        :syntax "elisp" :min-lines 2 :max-lines 6
        :on-save (jetpacs-component-catalog-editor-action
                  component digest field-path "lisp" :input-id id)))))
    (if (or required (equal value-type "enum"))
        control
      (jetpacs-row
       (jetpacs-with-attrs control :weight 1)
       (jetpacs-component-catalog-editor--remove-button
        component digest field-path label)
       :spacing 6 :align "center" :fill t))))

(defun jetpacs-component-catalog-editor--presence-boolean
    (component digest document path field)
  "Return COMPONENT's Default/True control for FIELD at DIGEST.
DOCUMENT supplies the current value and PATH identifies its containing node."
  (let* ((field-path (append path (list field)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document field-path)))
    (jetpacs-switch
     (jetpacs-component-catalog-editor--control-id
      component field-path "presence")
     :label (jetpacs-component-catalog-editor--label field)
     :checked (jetpacs-bool present)
     :on-change (jetpacs-component-catalog-editor--boolean-action
                 component digest field-path t))))

(defun jetpacs-component-catalog-editor--disclosure
    (component path label present children &optional required)
  "Return a collapsible LABEL section for COMPONENT's member at PATH.
CHILDREN are its controls.  PRESENT seeds the disclosure open and, unless
the member is REQUIRED, the header says whether the member is authored or
left at its default."
  (jetpacs-collapsible
   (jetpacs-component-catalog-editor--control-id component path "disclosure")
   (if required
       (jetpacs-text label :style "label")
     (jetpacs-row
      (jetpacs-with-attrs (jetpacs-text label :style "label") :weight 1)
      (jetpacs-text (if present "Authored" "Default") :style "caption")
      :spacing 8 :align "center" :fill t))
   children
   :collapsed (jetpacs-bool (not present))))

(defun jetpacs-component-catalog-editor--compound-field
    (component digest document path field label default children)
  "Return an optional compound FIELD editor.
COMPONENT, DIGEST, DOCUMENT and PATH identify it.  LABEL names the panel,
DEFAULT seeds an absent object, and CHILDREN builds controls when present."
  (let* ((field-path (append path (list field)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document field-path)))
    (jetpacs-component-catalog-editor--disclosure
     component field-path label present
     (if present
         (append (funcall children field-path)
                 (list (jetpacs-button
                        "Use default"
                        (jetpacs-component-catalog-editor-action
                         component digest field-path "unset")
                        :icon "restart_alt" :variant "outlined")))
       (list (jetpacs-button
              (format "Configure %s" label)
              (jetpacs-component-catalog-editor-action
               component digest field-path "literal" :literal default)
              :icon "add" :variant "outlined"))))))

(defun jetpacs-component-catalog-editor--padding
    (component digest document path)
  "Return COMPONENT's structured `pad' editor for DOCUMENT at DIGEST and PATH."
  (jetpacs-component-catalog-editor--compound-field
   component digest document path :pad "Directional padding" '(:start 0)
   (lambda (object-path)
     (mapcar (lambda (field)
               (jetpacs-component-catalog-editor--field-row
                component digest document "universal" object-path field nil))
             '(:start :top :end :bottom :horizontal :vertical)))))

(defun jetpacs-component-catalog-editor--border
    (component digest document path)
  "Return COMPONENT's structured border editor for DOCUMENT at DIGEST and PATH."
  (jetpacs-component-catalog-editor--compound-field
   component digest document path :border "Border" '(:width 1 :color "outline")
   (lambda (object-path)
     (list
      (jetpacs-component-catalog-editor--field-row
       component digest document "border" object-path :width nil)
      (jetpacs-component-catalog-editor--field-row
       component digest document "border" object-path :color nil)))))

(defun jetpacs-component-catalog-editor--corner
    (component digest document path)
  "Return COMPONENT's corner editor for DOCUMENT at DIGEST and PATH."
  (let* ((field-path (append path '(:corner)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document field-path))
         (value (and present (jetpacs-component-catalog-editor--path-value
                             document field-path))))
    (jetpacs-component-catalog-editor--disclosure
     component field-path "Corner" present
     (cond
      ((not present)
       (list (jetpacs-button
              "Configure corner"
              (jetpacs-component-catalog-editor-action
               component digest field-path "literal" :literal 8)
              :icon "add" :variant "outlined")))
      ((numberp value)
       (list
        (jetpacs-component-catalog-editor--field-row
         component digest document "universal" path :corner nil)
        (jetpacs-button
         "Edit each corner"
         (jetpacs-component-catalog-editor-action
          component digest field-path "literal"
          :literal '(:top_start 8 :top_end 8 :bottom_start 8 :bottom_end 8))
         :variant "outlined")
        (jetpacs-component-catalog-editor--remove-button
         component digest field-path "corner")))
      (t
       (append
        (mapcar (lambda (field)
                  (jetpacs-component-catalog-editor--field-row
                   component digest document "corner" field-path field nil))
                '(:top_start :top_end :bottom_start :bottom_end))
        (list
         (jetpacs-button
          "Use one radius"
          (jetpacs-component-catalog-editor-action
           component digest field-path "literal" :literal 8)
          :variant "outlined")
         (jetpacs-component-catalog-editor--remove-button
          component digest field-path "corner"))))))))

(defun jetpacs-component-catalog-editor--descriptor-kind-row
    (component digest path descriptor required)
  "Return kind controls for DESCRIPTOR at PATH.
COMPONENT and DIGEST address the draft.  REQUIRED suppresses removal."
  (let ((remote (plist-member descriptor :action)))
    (jetpacs-row
     (jetpacs-button
      "Remote"
      (jetpacs-component-catalog-editor-action
       component digest path "descriptor-remote")
      :variant (if remote "filled" "outlined"))
     (jetpacs-button
      "Builtin"
      (jetpacs-component-catalog-editor-action
       component digest path "descriptor-builtin")
      :variant (if remote "outlined" "filled"))
     (unless required
       (jetpacs-button
        "Default"
        (jetpacs-component-catalog-editor-action
         component digest path "unset")
        :icon "restart_alt" :variant "outlined"))
     :spacing 6 :fill t)))

(defun jetpacs-component-catalog-editor--remote-descriptor
    (component digest document path)
  "Return COMPONENT's remote descriptor controls for DOCUMENT at DIGEST and PATH."
  (let ((policy (or (jetpacs-component-catalog-editor--path-value
                     document (append path '(:when_offline)))
                    "__unset")))
    (append
     (list
      (jetpacs-component-catalog-editor--field-row
       component digest document "action" path :action t)
      (jetpacs-component-catalog-editor--field-row
       component digest document "action" path :args nil)
      (jetpacs-dropdown
       (jetpacs-component-catalog-editor--control-id
        component (append path '(:when_offline)) "policy")
       (cons (jetpacs-enum-option "Default (drop)" "__unset")
             (mapcar (lambda (value)
                       (jetpacs-enum-option (capitalize value) value))
                     jetpacs-action-offline-policies))
       :label "Offline policy" :value policy
       :on-change (jetpacs-component-catalog-editor-action
                   component digest path "descriptor-policy"
                   :input-id
                   (jetpacs-component-catalog-editor--control-id
                    component (append path '(:when_offline)) "policy"))))
     (when (member policy '("queue" "wake"))
       (list
        (jetpacs-component-catalog-editor--field-row
         component digest document "action" path :dedupe nil)
        (jetpacs-component-catalog-editor--field-row
         component digest document "action" path :ttl_s t)))
     (list
      (jetpacs-component-catalog-editor--field-row
       component digest document "action" path :confirm nil)
      (jetpacs-component-catalog-editor--field-row
       component digest document "action" path :capture_fields nil)
      (jetpacs-component-catalog-editor--field-row
       component digest document "action" path :open_surface nil)))))

(defun jetpacs-component-catalog-editor--builtin-descriptor
    (component digest document path descriptor)
  "Return COMPONENT's builtin DESCRIPTOR controls at DIGEST.
DOCUMENT supplies values and PATH identifies the descriptor."
  (let* ((builtin (plist-get descriptor :builtin))
         (row (assoc builtin jetpacs-action-descriptor-schema))
         (members (append (plist-get (cdr row) :required)
                          (plist-get (cdr row) :optional))))
    (append
     (list
      (jetpacs-dropdown
       (jetpacs-component-catalog-editor--control-id
        component (append path '(:builtin)) "builtin")
       (mapcar (lambda (name)
                 (jetpacs-enum-option
                  (capitalize (string-replace "." " " name)) name))
               (mapcar #'car (cdr jetpacs-action-descriptor-schema)))
       :label "Builtin" :value builtin
       :on-change (jetpacs-component-catalog-editor-action
                   component digest path "descriptor-builtin-select"
                   :input-id
                   (jetpacs-component-catalog-editor--control-id
                    component (append path '(:builtin)) "builtin"))))
     (mapcar
      (lambda (name)
        (let ((field (intern (concat ":" name))))
          (jetpacs-component-catalog-editor--field-row
           component digest document "action" path field
           (member name (plist-get (cdr row) :required)))))
      (remove "builtin" members)))))

(defun jetpacs-component-catalog-editor--descriptor
    (component digest document path required)
  "Return the structured ActionDescriptor editor at PATH.
COMPONENT and DIGEST identify DOCUMENT; REQUIRED controls removal."
  (let* ((present (jetpacs-component-catalog-editor--path-present-p
                   document path))
         (descriptor (and present
                          (jetpacs-component-catalog-editor--path-value
                           document path))))
    (jetpacs-component-catalog-editor--disclosure
     component path (jetpacs-component-catalog-editor--label (car (last path)))
     present
     (if (not present)
         (list
          (jetpacs-row
           (jetpacs-button
            "Add remote action"
            (jetpacs-component-catalog-editor-action
             component digest path "descriptor-remote")
            :icon "add" :variant "outlined")
           (jetpacs-button
            "Add builtin"
            (jetpacs-component-catalog-editor-action
             component digest path "descriptor-builtin")
            :icon "add" :variant "outlined")
           :spacing 6 :fill t))
       (append
        (list (jetpacs-component-catalog-editor--descriptor-kind-row
               component digest path descriptor required))
        (if (plist-member descriptor :action)
            (jetpacs-component-catalog-editor--remote-descriptor
             component digest document path)
          (jetpacs-component-catalog-editor--builtin-descriptor
           component digest document path descriptor))))
     required)))

(defun jetpacs-component-catalog-editor--semantic-object
    (component digest document object-path kind fields defaults)
  "Return controls for a nested Semantics object.
COMPONENT and DIGEST address DOCUMENT.  OBJECT-PATH locates KIND, FIELDS is a
list of (FIELD TYPE REQUIRED), and DEFAULTS is installed by its add button."
  (let ((present (jetpacs-component-catalog-editor--path-present-p
                  document object-path)))
    (jetpacs-component-catalog-editor--disclosure
     component object-path kind present
     (if present
         (append
          (mapcar
           (lambda (spec)
             (pcase-let ((`(,field ,_type ,required) spec))
               (jetpacs-component-catalog-editor--field-row
                component digest document "semantics" object-path field
                required)))
           fields)
          (list (jetpacs-component-catalog-editor--remove-button
                 component digest object-path kind)))
       (list (jetpacs-button
              (format "Configure %s" kind)
              (jetpacs-component-catalog-editor-action
               component digest object-path "literal" :literal defaults)
              :icon "add" :variant "outlined"))))))

(defun jetpacs-component-catalog-editor--semantic-actions
    (component digest document semantics-path)
  "Return COMPONENT's custom Semantics actions for DOCUMENT at DIGEST.
SEMANTICS-PATH identifies the containing Semantics object."
  (let* ((path (append semantics-path '(:actions)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document path))
         (actions (if present
                      (jetpacs-component-catalog-editor--path-value
                       document path)
                    []))
         (template '(:label "Action" :on_action
                     (:action "jpcatalog.activate")))
         nodes)
    (cl-loop for _action across actions for index from 0
             for action-path = (append path (list index))
             do
             (push
              (jetpacs-card
               (jetpacs-column
                (jetpacs-row
                 (jetpacs-text (format "Action %d" (1+ index)) :style "title")
                 (jetpacs-icon-button
                  "arrow_upward"
                  (jetpacs-component-catalog-editor-action
                   component digest path "vector-move" :index index
                   :direction "up")
                  :content-description "Move semantic action up")
                 (jetpacs-icon-button
                  "arrow_downward"
                  (jetpacs-component-catalog-editor-action
                   component digest path "vector-move" :index index
                   :direction "down")
                  :content-description "Move semantic action down")
                 (jetpacs-icon-button
                  "delete"
                  (jetpacs-component-catalog-editor-action
                   component digest path "vector-delete" :index index)
                  :content-description "Delete semantic action")
                 :spacing 4 :align "center" :fill t)
                (jetpacs-component-catalog-editor--field-row
                 component digest document "semantics" action-path :label t)
                (jetpacs-component-catalog-editor--descriptor
                 component digest document (append action-path '(:on_action)) t)
                :spacing 8)
               :variant "outlined")
              nodes))
    (jetpacs-component-panel
     "Custom actions"
     (append
      (nreverse nodes)
      (list
       (jetpacs-button
        "Add semantic action"
        (jetpacs-component-catalog-editor-action
         component digest path (if present "vector-add" "literal")
         :literal (if present template (vector template)))
        :icon "add" :variant "outlined"))))))

(defun jetpacs-component-catalog-editor--semantics
    (component digest document path)
  "Return COMPONENT's Semantics editor for DOCUMENT at DIGEST and PATH."
  (let* ((semantics-path (append path '(:semantics)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document semantics-path)))
    (jetpacs-component-catalog-editor--disclosure
     component semantics-path "Semantics" present
     (if (not present)
         (list (jetpacs-button
                "Configure semantics"
                (jetpacs-component-catalog-editor-action
                 component digest semantics-path "literal"
                 :literal '(:name "Specimen"))
                :icon "add" :variant "outlined"))
       (append
        (mapcar
         (lambda (field)
           (jetpacs-component-catalog-editor--field-row
            component digest document "semantics" semantics-path field nil))
         '(:name :description :state_description :error :pane_title
           :heading_level :live_region :traversal_group :traversal_index))
        (list
         (jetpacs-component-catalog-editor--semantic-object
          component digest document (append semantics-path '(:collection))
          "Collection"
          '((:row_count "non-negative-integer" t)
            (:column_count "positive-integer" t))
          '(:row_count 1 :column_count 1))
         (jetpacs-component-catalog-editor--semantic-object
          component digest document
          (append semantics-path '(:collection_item)) "Collection item"
          '((:row_index "non-negative-integer" t)
            (:row_span "positive-integer" t)
            (:column_index "non-negative-integer" t)
            (:column_span "positive-integer" t))
          '(:row_index 0 :row_span 1 :column_index 0 :column_span 1))
         (jetpacs-component-catalog-editor--semantic-actions
          component digest document semantics-path)
         (jetpacs-button
          "Use default semantics"
          (jetpacs-component-catalog-editor-action
           component digest semantics-path "unset")
          :icon "restart_alt" :variant "outlined")))))))

(defun jetpacs-component-catalog-editor--toolbar-op (item)
  "Return ITEM's single primary toolbar operation name."
  (cl-loop for (field . name) in '((:snippet . "snippet")
                                   (:on_tap . "on_tap")
                                   (:menu . "menu")
                                   (:command . "command")
                                   (:line . "line"))
           when (plist-member item field) return name))

(defun jetpacs-component-catalog-editor--toolbar-op-control
    (component digest document item-path item depth)
  "Return ITEM's operation control for COMPONENT and DOCUMENT at DIGEST.
ITEM-PATH locates it and DEPTH controls whether a menu may nest."
  (let* ((op (jetpacs-component-catalog-editor--toolbar-op item))
         (ops (if (> depth 0)
                  (remove "menu" (plist-get jetpacs-toolbar-contract :ops))
                (plist-get jetpacs-toolbar-contract :ops)))
         (op-path (append item-path (list (intern (concat ":" op))))))
    (append
     (list
      (jetpacs-dropdown
       (jetpacs-component-catalog-editor--control-id
        component item-path "operation")
       (mapcar (lambda (name)
                 (jetpacs-enum-option
                  (capitalize (string-replace "_" " " name)) name))
               ops)
       :label "Operation" :value op
       :on-change (jetpacs-component-catalog-editor-action
                   component digest item-path "toolbar-op"
                   :input-id
                   (jetpacs-component-catalog-editor--control-id
                    component item-path "operation"))))
     (pcase op
       ("snippet"
        (list (jetpacs-component-catalog-editor--field-row
               component digest document "toolbar" item-path :snippet t)))
       ("on_tap"
        (list (jetpacs-component-catalog-editor--descriptor
               component digest document op-path t)))
       ("command"
        (list (jetpacs-component-catalog-editor--field-row
               component digest document "toolbar" item-path :command t)))
       ("line"
        (list
         (jetpacs-dropdown
          (jetpacs-component-catalog-editor--control-id
           component op-path "line")
          (mapcar (lambda (name)
                    (jetpacs-enum-option
                     (capitalize (string-replace "-" " " name)) name))
                  (plist-get jetpacs-toolbar-contract :line_ops))
          :label "Line operation" :value (plist-get item :line)
          :on-change (jetpacs-component-catalog-editor-action
                      component digest op-path "string"
                      :input-id
                      (jetpacs-component-catalog-editor--control-id
                       component op-path "line")))))
       ("menu"
        (list (jetpacs-component-catalog-editor--toolbar-items
               component digest document op-path (plist-get item :menu)
               (1+ depth))))
       (_ nil)))))

(defun jetpacs-component-catalog-editor--toolbar-long-press
    (component digest document item-path item)
  "Return ITEM's long-press editor for COMPONENT and DOCUMENT at DIGEST.
ITEM-PATH identifies the toolbar item."
  (let* ((path (append item-path '(:long_press)))
         (present (plist-member item :long_press))
         (operation (plist-get item :long_press))
         (op (and operation
                  (cl-loop for (field . name)
                           in '((:snippet . "snippet") (:on_tap . "on_tap")
                                (:command . "command") (:line . "line"))
                           when (plist-member operation field) return name))))
    (jetpacs-component-catalog-editor--disclosure
     component path "Long press" present
     (if (not present)
         (list (jetpacs-button
                "Add long press"
                (jetpacs-component-catalog-editor-action
                 component digest path "literal"
                 :literal '(:snippet "${selection}"))
                :icon "add" :variant "outlined"))
       (append
        (list
         (jetpacs-dropdown
          (jetpacs-component-catalog-editor--control-id
           component path "long-operation")
          (mapcar (lambda (name)
                    (jetpacs-enum-option
                     (capitalize (string-replace "_" " " name)) name))
                  (remove "menu" (plist-get jetpacs-toolbar-contract :ops)))
          :label "Operation" :value op
          :on-change (jetpacs-component-catalog-editor-action
                      component digest path "toolbar-long-op"
                      :input-id
                      (jetpacs-component-catalog-editor--control-id
                       component path "long-operation"))))
        (pcase op
          ("snippet"
           (list (jetpacs-component-catalog-editor--field-row
                  component digest document "toolbar" path :snippet t)))
          ("on_tap"
           (list (jetpacs-component-catalog-editor--descriptor
                  component digest document (append path '(:on_tap)) t)))
          ("command"
           (list (jetpacs-component-catalog-editor--field-row
                  component digest document "toolbar" path :command t)))
          ("line"
           (list
            (jetpacs-dropdown
             (jetpacs-component-catalog-editor--control-id
              component (append path '(:line)) "long-line")
             (mapcar (lambda (name)
                       (jetpacs-enum-option
                        (capitalize (string-replace "-" " " name)) name))
                     (plist-get jetpacs-toolbar-contract :line_ops))
             :label "Line operation" :value (plist-get operation :line)
             :on-change (jetpacs-component-catalog-editor-action
                         component digest (append path '(:line)) "string"
                         :input-id
                         (jetpacs-component-catalog-editor--control-id
                          component (append path '(:line)) "long-line")))))
          (_ nil))
        (list (jetpacs-component-catalog-editor--remove-button
               component digest path "long press")))))))

(defun jetpacs-component-catalog-editor--toolbar-item
    (component digest document parent-path item index depth)
  "Return COMPONENT's toolbar ITEM card for DOCUMENT at DIGEST.
INDEX locates it below PARENT-PATH; DEPTH records menu nesting."
  (let ((item-path (append parent-path (list index))))
    (jetpacs-card
     (apply
      #'jetpacs-column
      (append
       (list
        (jetpacs-row
         (jetpacs-with-attrs
          (jetpacs-text (format "Item %d" (1+ index)) :style "title")
          :weight 1)
         (jetpacs-icon-button
          "arrow_upward"
          (jetpacs-component-catalog-editor-action
           component digest parent-path "vector-move" :index index
           :direction "up")
          :content-description "Move toolbar item up")
         (jetpacs-icon-button
          "arrow_downward"
          (jetpacs-component-catalog-editor-action
           component digest parent-path "vector-move" :index index
           :direction "down")
          :content-description "Move toolbar item down")
         (jetpacs-icon-button
          "delete"
          (jetpacs-component-catalog-editor-action
           component digest parent-path "vector-delete" :index index)
          :content-description "Delete toolbar item")
         :spacing 4 :align "center" :fill t)
        (jetpacs-component-catalog-editor--field-row
         component digest document "toolbar" item-path :label nil)
        (jetpacs-component-catalog-editor--field-row
         component digest document "toolbar" item-path :icon nil))
       (jetpacs-component-catalog-editor--toolbar-op-control
        component digest document item-path item depth)
       (list
        (jetpacs-dropdown
         (jetpacs-component-catalog-editor--control-id
          component (append item-path '(:placement)) "placement")
         (jetpacs-component-catalog-editor--enum-options
          (plist-get jetpacs-toolbar-contract :placements) t)
         :label "Placement"
         :value (or (plist-get item :placement) "__unset")
         :on-change (jetpacs-component-catalog-editor-action
                     component digest (append item-path '(:placement))
                     "enum"
                     :input-id
                     (jetpacs-component-catalog-editor--control-id
                      component (append item-path '(:placement))
                      "placement")))
        (jetpacs-component-catalog-editor--toolbar-long-press
         component digest document item-path item)
        :spacing 8)))
     :variant "outlined")))

(defun jetpacs-component-catalog-editor--toolbar-items
    (component digest document path items depth)
  "Return COMPONENT's inline toolbar ITEMS for DOCUMENT at DIGEST.
PATH locates the vector and DEPTH records menu nesting."
  (let (nodes)
    (cl-loop for item across items for index from 0
             do (push (jetpacs-component-catalog-editor--toolbar-item
                       component digest document path item index depth)
                      nodes))
    (apply
     #'jetpacs-column
     (append
      (nreverse nodes)
      (list
       (jetpacs-button
        "Add toolbar item"
        (jetpacs-component-catalog-editor-action
         component digest path "vector-add"
         :literal '(:label "Item" :snippet "${selection}"))
        :icon "add" :variant "outlined")
       :spacing 8)))))

(defun jetpacs-component-catalog-editor--advertised-toolbar-ids ()
  "Return sorted registered toolbar ids advertised for the live app profile.
An absent client or profile yields nil: without a concrete advertisement the
Visual editor must not author a registered-toolbar feature dependency."
  (when-let* ((client (jetpacs-client))
              (profile (plist-get (ebp-client-profiles client) :app)))
    (sort
     (delete-dups
      (cl-loop for feature in (append (plist-get profile :features) nil)
               for id = (and (stringp feature)
                             (string-prefix-p "toolbar." feature)
                             (> (length feature) (length "toolbar."))
                             (substring feature (length "toolbar.")))
               when (jetpacs-identifier-p id)
               collect (substring-no-properties id)))
     #'string<)))

(defun jetpacs-component-catalog-editor--registered-toolbar-button
    (component digest path toolbar-id current)
  "Return a profile-proven registered TOOLBAR-ID choice.
COMPONENT and DIGEST address PATH.  CURRENT is the configured toolbar id, or
nil, and only affects the button's selected presentation."
  (jetpacs-button
   (format "Use registered · %s" toolbar-id)
   (jetpacs-component-catalog-editor-action
    component digest path "literal" :literal toolbar-id)
   :icon "build"
   :variant (if (equal toolbar-id current) "filled" "outlined")))

(defun jetpacs-component-catalog-editor--toolbar
    (component digest document path)
  "Return COMPONENT's structured toolbar for DOCUMENT at DIGEST and PATH."
  (let* ((toolbar-path (append path '(:toolbar)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document toolbar-path))
         (toolbar (and present
                       (jetpacs-component-catalog-editor--path-value
                        document toolbar-path)))
         (registered
          (jetpacs-component-catalog-editor--advertised-toolbar-ids)))
    (jetpacs-component-catalog-editor--disclosure
     component toolbar-path "Toolbar" present
     (cond
      ((not present)
       (append
        (mapcar
         (lambda (toolbar-id)
           (jetpacs-component-catalog-editor--registered-toolbar-button
            component digest toolbar-path toolbar-id nil))
         registered)
        (list
         (jetpacs-button
          "Use inline items"
          (jetpacs-component-catalog-editor-action
           component digest toolbar-path "literal"
           :literal [(:label "Item" :snippet "${selection}")])
          :icon "add" :variant "outlined"))))
      ((stringp toolbar)
       (append
        (list
         (jetpacs-text (format "Registered toolbar · %s" toolbar)
                       :style "caption" :selectable t))
        (mapcar
         (lambda (toolbar-id)
           (jetpacs-component-catalog-editor--registered-toolbar-button
            component digest toolbar-path toolbar-id toolbar))
         (remove toolbar registered))
        (list
         (jetpacs-button
          "Switch to inline items"
          (jetpacs-component-catalog-editor-action
           component digest toolbar-path "literal"
           :literal [(:label "Item" :snippet "${selection}")])
          :variant "outlined")
         (jetpacs-component-catalog-editor--remove-button
          component digest toolbar-path "toolbar"))))
      (t
       (append
        (list
         (jetpacs-component-catalog-editor--toolbar-items
          component digest document toolbar-path toolbar 0))
        (mapcar
         (lambda (toolbar-id)
           (jetpacs-component-catalog-editor--registered-toolbar-button
            component digest toolbar-path toolbar-id nil))
         registered)
        (list
         (jetpacs-component-catalog-editor--remove-button
          component digest toolbar-path "toolbar"))))))))

(defun jetpacs-component-catalog-editor--selection
    (component digest document path)
  "Return COMPONENT's two-offset selection for DOCUMENT at DIGEST and PATH."
  (let* ((selection-path (append path '(:selection)))
         (present (jetpacs-component-catalog-editor--path-present-p
                   document selection-path))
         (selection (and present
                         (jetpacs-component-catalog-editor--path-value
                          document selection-path))))
    (jetpacs-component-catalog-editor--disclosure
     component selection-path "Selection" present
     (if (not present)
         (list (jetpacs-button
                "Configure selection"
                (jetpacs-component-catalog-editor-action
                 component digest selection-path "literal" :literal [0 0])
                :icon "add" :variant "outlined"))
       (list
        (jetpacs-row
         (jetpacs-text-input
          (jetpacs-component-catalog-editor--control-id
           component (append selection-path '(0)) "start")
          :label "Start" :value (number-to-string (aref selection 0))
          :keyboard "number" :single-line t
          :on-submit (jetpacs-component-catalog-editor-action
                      component digest (append selection-path '(0)) "integer"
                      :input-id
                      (jetpacs-component-catalog-editor--control-id
                       component (append selection-path '(0)) "start")))
         (jetpacs-text-input
          (jetpacs-component-catalog-editor--control-id
           component (append selection-path '(1)) "end")
          :label "End" :value (number-to-string (aref selection 1))
          :keyboard "number" :single-line t
          :on-submit (jetpacs-component-catalog-editor-action
                      component digest (append selection-path '(1)) "integer"
                      :input-id
                      (jetpacs-component-catalog-editor--control-id
                       component (append selection-path '(1)) "end")))
         :spacing 6 :fill t)
        (jetpacs-component-catalog-editor--remove-button
         component digest selection-path "selection"))))))

(defun jetpacs-component-catalog-editor--tabs-next-value (options)
  "Return a deterministic unused option value for tab OPTIONS."
  (let ((values
         (mapcar (lambda (option) (plist-get option :value))
                 (append options nil)))
        (index (1+ (length options)))
        candidate)
    (while (progn
             (setq candidate (format "tab-%d" index))
             (setq index (1+ index))
             (member candidate values)))
    candidate))

(defun jetpacs-component-catalog-editor--tabs-value
    (component digest document path)
  "Return COMPONENT's controlled Tabs value editor at DIGEST.
DOCUMENT supplies the tab options and PATH identifies the root node."
  (let* ((options-path (append path '(:options)))
         (value-path (append path '(:value)))
         (options (jetpacs-component-catalog-editor--path-value
                   document options-path))
         (id (jetpacs-component-catalog-editor--control-id
              component value-path "selected")))
    (jetpacs-dropdown
     id
     (mapcar
      (lambda (option)
        (jetpacs-enum-option (plist-get option :label)
                             (plist-get option :value)))
      (append options nil))
     :label "Selected value"
     :value (jetpacs-component-catalog-editor--path-value document value-path)
     :on-change
     (jetpacs-component-catalog-editor-action
     component digest value-path "string" :input-id id))))

(defun jetpacs-component-catalog-editor--tabs-pinned
    (component digest document path)
  "Return COMPONENT's Pin while scrolling toggle at DIGEST.
DOCUMENT supplies the current Tabs value and PATH identifies its root node."
  (let* ((pinned-path (append path '(:pinned)))
         (pinned (and
                  (jetpacs-component-catalog-editor--path-present-p
                   document pinned-path)
                  (eq (jetpacs-component-catalog-editor--path-value
                       document pinned-path)
                      t))))
    (jetpacs-component-choice
     (jetpacs-component-catalog-editor--control-id
      component pinned-path "toggle")
     "Pin while scrolling"
     (jetpacs-bool pinned)
     (jetpacs-component-catalog-editor--boolean-action
      component digest pinned-path))))

(defun jetpacs-component-catalog-editor--tabs-presentation
    (component digest document path)
  "Return COMPONENT's atomic Tabs Presentation editor at DIGEST.
DOCUMENT supplies legacy and variant members; PATH identifies the Tabs node."
  (let* ((variant-path (append path '(:variant)))
         (scrollable-path (append path '(:scrollable)))
         (variant
          (and (jetpacs-component-catalog-editor--path-present-p
                document variant-path)
               (jetpacs-component-catalog-editor--path-value
                document variant-path)))
         (legacy-scrollable
          (and (null variant)
               (jetpacs-component-catalog-editor--path-present-p
                document scrollable-path)
               (eq (jetpacs-component-catalog-editor--path-value
                    document scrollable-path)
                   t)))
         (value (or variant
                    (if legacy-scrollable
                        "legacy-scrollable"
                      "legacy-fixed")))
         (id (jetpacs-component-catalog-editor--control-id
              component variant-path "presentation")))
    (jetpacs-component-panel
     "Presentation"
     (list
      (jetpacs-dropdown
       id
       (list
        (jetpacs-enum-option "Legacy fixed" "legacy-fixed")
        (jetpacs-enum-option "Legacy scrollable" "legacy-scrollable")
        (jetpacs-enum-option "Fixed" "fixed")
        (jetpacs-enum-option "Scrollable" "scrollable")
        (jetpacs-enum-option "Navigator" "navigator")
        (jetpacs-enum-option "Adaptive" "adaptive"))
       :label "Presentation" :value value
       :on-change
       (jetpacs-component-catalog-editor-action
        component digest variant-path "tabs-presentation" :input-id id))
      (jetpacs-text
       "Variant choices atomically replace legacy scrollable state; compiled IR derives the compatible boolean."
       :style "caption")))))

(defun jetpacs-component-catalog-editor--tabs-options
    (component digest document path)
  "Return the structured tab options editor for COMPONENT at DIGEST.
DOCUMENT supplies the current options and PATH identifies the Tabs node."
  (let* ((options-path (append path '(:options)))
         (options (jetpacs-component-catalog-editor--path-value
                   document options-path))
         (count (length options))
         nodes)
    (cl-loop
     for option across options for index from 0
     for option-path = (append options-path (list index))
     for label-id = (jetpacs-component-catalog-editor--control-id
                     component (append option-path '(:label)) "label")
     for value-id = (jetpacs-component-catalog-editor--control-id
                     component (append option-path '(:value)) "value")
     do
     (push
      (jetpacs-card
       (jetpacs-column
        (jetpacs-row
         (jetpacs-with-attrs
          (jetpacs-text (format "Tab %d" (1+ index)) :style "title")
          :weight 1)
         (jetpacs-icon-button
          "arrow_upward"
          (jetpacs-component-catalog-editor-action
           component digest options-path "vector-move"
           :index index :direction "up")
          :content-description "Move tab left"
          :enabled (if (> index 0) t :json-false))
         (jetpacs-icon-button
          "arrow_downward"
          (jetpacs-component-catalog-editor-action
           component digest options-path "vector-move"
           :index index :direction "down")
          :content-description "Move tab right"
          :enabled (if (< index (1- count)) t :json-false))
         (jetpacs-icon-button
          "delete"
          (jetpacs-component-catalog-editor-action
           component digest options-path "tabs-option-delete" :index index)
          :content-description "Delete tab"
          :enabled (if (> count 1) t :json-false)
          :color "error")
         :spacing 4 :align "center" :fill t)
        (jetpacs-text-input
         label-id :label "Label" :value (plist-get option :label)
         :single-line t
         :on-submit
         (jetpacs-component-catalog-editor-action
          component digest (append option-path '(:label)) "string"
          :input-id label-id))
        (jetpacs-text-input
         value-id :label "Value" :value (plist-get option :value)
         :single-line t
         :on-submit
         (jetpacs-component-catalog-editor-action
          component digest (append option-path '(:value))
          "tabs-option-value" :index index :input-id value-id))
        :spacing 8)
       :variant "outlined")
      nodes))
    (jetpacs-component-panel
     "Options"
     (append
      (nreverse nodes)
      (let ((value (jetpacs-component-catalog-editor--tabs-next-value
                    options)))
        (list
         (jetpacs-button
          "Add tab"
          (jetpacs-component-catalog-editor-action
           component digest options-path "vector-add"
           :literal (list :label "New tab" :value value))
          :icon "add" :variant "outlined")))))))

(defun jetpacs-component-catalog-editor--section-next-value (options)
  "Return a deterministic unused section value for OPTIONS."
  (let ((values
         (mapcar (lambda (option) (plist-get option :value))
                 (append options nil)))
        (index (1+ (length options)))
        candidate)
    (while (progn
             (setq candidate (format "section-%d" index))
             (setq index (1+ index))
             (member candidate values)))
    candidate))

(defun jetpacs-component-catalog-editor--section-value
    (component digest document path)
  "Return COMPONENT's controlled section value editor at DIGEST.
DOCUMENT supplies the section options and PATH identifies the root node."
  (let* ((options-path (append path '(:options)))
         (value-path (append path '(:value)))
         (options (jetpacs-component-catalog-editor--path-value
                   document options-path))
         (id (jetpacs-component-catalog-editor--control-id
              component value-path "selected")))
    (jetpacs-dropdown
     id
     (mapcar
      (lambda (option)
        (jetpacs-enum-option (plist-get option :label)
                             (plist-get option :value)))
      (append options nil))
     :label "Selected section"
     :value (jetpacs-component-catalog-editor--path-value document value-path)
     :on-change
     (jetpacs-component-catalog-editor-action
      component digest value-path "string" :input-id id))))

(defun jetpacs-component-catalog-editor--section-pinned
    (component digest document path)
  "Return COMPONENT's section pinning toggle at DIGEST.
DOCUMENT supplies the current value and PATH identifies the root node."
  (jetpacs-component-catalog-editor--tabs-pinned
   component digest document path))

(defun jetpacs-component-catalog-editor--section-options
    (component digest document path)
  "Return the ordered section option editor for COMPONENT at DIGEST.
DOCUMENT supplies the current options and PATH identifies the navigator."
  (let* ((options-path (append path '(:options)))
         (options (jetpacs-component-catalog-editor--path-value
                   document options-path))
         (count (length options))
         nodes)
    (cl-loop
     for option across options for index from 0
     for option-path = (append options-path (list index))
     for label-path = (append option-path '(:label))
     for value-path = (append option-path '(:value))
     for level-path = (append option-path '(:level))
     for label-id = (jetpacs-component-catalog-editor--control-id
                     component label-path "label")
     for value-id = (jetpacs-component-catalog-editor--control-id
                     component value-path "value")
     for level-id = (jetpacs-component-catalog-editor--control-id
                     component level-path "level")
     do
     (push
      (jetpacs-card
       (jetpacs-column
        (jetpacs-row
         (jetpacs-with-attrs
          (jetpacs-text (format "Section %d" (1+ index)) :style "title")
          :weight 1)
         (jetpacs-icon-button
          "arrow_upward"
          (jetpacs-component-catalog-editor-action
           component digest options-path "vector-move"
           :index index :direction "up")
          :content-description "Move section up"
          :enabled (if (> index 0) t :json-false))
         (jetpacs-icon-button
          "arrow_downward"
          (jetpacs-component-catalog-editor-action
           component digest options-path "vector-move"
           :index index :direction "down")
          :content-description "Move section down"
          :enabled (if (< index (1- count)) t :json-false))
         (jetpacs-icon-button
          "delete"
          (jetpacs-component-catalog-editor-action
           component digest options-path "section-option-delete"
           :index index)
          :content-description "Delete section"
          :enabled (if (> count 1) t :json-false)
          :color "error")
         :spacing 4 :align "center" :fill t)
        (jetpacs-text-input
         label-id :label "Label" :value (plist-get option :label)
         :single-line t
         :on-submit
         (jetpacs-component-catalog-editor-action
          component digest label-path "string" :input-id label-id))
        (jetpacs-text-input
         value-id :label "Value" :value (plist-get option :value)
         :single-line t
         :on-submit
         (jetpacs-component-catalog-editor-action
          component digest value-path "section-option-value"
          :index index :input-id value-id))
        (jetpacs-text-input
         level-id :label "Level (1–6)"
         :value (number-to-string (plist-get option :level))
         :keyboard "number" :single-line t
         :on-submit
         (jetpacs-component-catalog-editor-action
          component digest level-path "integer" :input-id level-id))
        :spacing 8)
       :variant "outlined")
      nodes))
    (jetpacs-component-panel
     "Sections"
     (append
      (nreverse nodes)
      (let ((value (jetpacs-component-catalog-editor--section-next-value
                    options)))
        (list
         (jetpacs-button
          "Add section"
          (jetpacs-component-catalog-editor-action
           component digest options-path "vector-add"
           :literal (list :label "New section" :value value :level 1))
          :icon "add" :variant "outlined")))))))

(defun jetpacs-component-catalog-editor--node-section
    (component digest document type path label)
  "Return all schema-derived controls for one fixed node.
COMPONENT and DIGEST identify DOCUMENT; TYPE is at root-relative PATH and
LABEL names this section."
  (let* ((schema (jetpacs-component-authoring-field-schema type))
         (fields (plist-get schema :fields))
         nodes)
    (dolist (field fields)
      (unless (eq field :children)
        (let* ((field-schema
                (jetpacs-component-authoring-field-schema type field))
               (kind (plist-get field-schema :kind))
               (required (eq (plist-get field-schema :required) t)))
          (push
           (pcase kind
             ('action-descriptor
              (jetpacs-component-catalog-editor--descriptor
               component digest document (append path (list field)) required))
             ('tabs-options
              (jetpacs-component-catalog-editor--tabs-options
               component digest document path))
             ('tabs-presentation
              (jetpacs-component-catalog-editor--tabs-presentation
               component digest document path))
             ('tabs-presentation-shadow nil)
             ('tabs-value
              (jetpacs-component-catalog-editor--tabs-value
               component digest document path))
             ('tabs-pinned
              (jetpacs-component-catalog-editor--tabs-pinned
               component digest document path))
             ('section-options
              (jetpacs-component-catalog-editor--section-options
               component digest document path))
             ('section-value
              (jetpacs-component-catalog-editor--section-value
               component digest document path))
             ('section-pinned
              (jetpacs-component-catalog-editor--section-pinned
               component digest document path))
             ('semantics-object
              (jetpacs-component-catalog-editor--semantics
               component digest document path))
             ('toolbar
              (jetpacs-component-catalog-editor--toolbar
               component digest document path))
             ('selection
              (jetpacs-component-catalog-editor--selection
               component digest document path))
             ('padding
              (jetpacs-component-catalog-editor--padding
               component digest document path))
             ('corner
              (jetpacs-component-catalog-editor--corner
               component digest document path))
             ('border
              (jetpacs-component-catalog-editor--border
               component digest document path))
             ('presence-boolean
              (jetpacs-component-catalog-editor--presence-boolean
               component digest document path field))
             ('fixed-children nil)
             (_
              (jetpacs-component-catalog-editor--field-row
               component digest document type path field required)))
           nodes))))
    (jetpacs-component-panel label (delq nil (nreverse nodes)))))

(defun jetpacs-component-catalog-editor-visual
    (component document digest)
  "Return the complete structured editor for COMPONENT DOCUMENT at DIGEST."
  (let* ((schema (jetpacs-component-authoring-component-schema component))
         (root-type (plist-get schema :root-type))
         (fixed (plist-get schema :fixed-children))
         (nodes
          (list
           (jetpacs-component-catalog-editor--node-section
            component digest document root-type nil "Root component"))))
    (cl-loop for child across fixed
             do (setq nodes
                      (append
                       nodes
                       (list
                        (jetpacs-component-catalog-editor--node-section
                         component digest document
                         (plist-get child :type) (plist-get child :path)
                         (format "Fixed %s child" (plist-get child :label)))))))
    (apply #'jetpacs-column (append nodes '(:spacing 12)))))

(defun jetpacs-component-catalog-editor-mode-row (component mode)
  "Return Preview/Visual/Lisp/Source projection controls for COMPONENT.
MODE is the selected mode name string."
  (jetpacs-component-tabs
   (format "jpcatalog-%s-projection-tabs" component)
   (mapcar (lambda (entry)
             (jetpacs-component-tab (cdr entry) (car entry)))
           '(("preview" . "Preview") ("visual" . "Visual")
             ("lisp" . "Lisp") ("source" . "Source")))
   mode
   (jetpacs-action "jpcatalog.presentation"
                   :args (list :component component))
   :variant "fixed" :pinned t))

(defun jetpacs-component-catalog-editor-lisp
    (component document digest lisp-buffer)
  "Return canonical Lisp controls for COMPONENT's DOCUMENT and DIGEST.
LISP-BUFFER, when non-nil, is invalid source retained for correction."
  (jetpacs-column
   (jetpacs-text
    "One inert, versioned data form. Saving validates and replaces the same draft used by Visual; no Lisp is evaluated."
    :style "caption")
   (jetpacs-editor
    (jetpacs-component-catalog-editor--control-id
     component '(:canonical-lisp) "editor")
    :value (or lisp-buffer
               (jetpacs-component-authoring-print-document document))
    :on-save
    (jetpacs-action "jpcatalog.lisp.apply"
                    :args (list :component component :digest digest
                                :input_id
                                (jetpacs-component-catalog-editor--control-id
                                 component '(:canonical-lisp) "editor")))
    :syntax "elisp" :line-numbers t :min-lines 16 :max-lines 28
    :autofocus t)
   (jetpacs-row
    (jetpacs-button
     "Copy canonical Lisp"
     (jetpacs-clipboard-copy
      (jetpacs-component-authoring-print-document document))
     :icon "content_copy" :variant "tonal")
    (jetpacs-button
     "Reset"
     (jetpacs-action "jpcatalog.reset"
                     :args (list :component component :digest digest))
     :icon "restart_alt" :variant "outlined")
    :spacing 8 :fill t)
   :spacing 10))

(defun jetpacs-component-catalog-editor-actions-row
    (component digest armed compatible)
  "Return Reset and trace/arm controls for COMPONENT at DIGEST.
ARMED and COMPATIBLE describe the current preview policy."
  (jetpacs-row
   (jetpacs-button
    "Reset"
    (jetpacs-action "jpcatalog.reset"
                    :args (list :component component :digest digest))
    :icon "restart_alt" :variant "outlined")
   (jetpacs-button
    (if armed "Disarm" "Arm real actions")
    (jetpacs-action
     "jpcatalog.arm"
     :args (list :component component :digest digest :armed
                 (if armed :json-false t))
     :confirm (and (not armed)
                   "Run every compatible drop-policy action in this specimen for real?"))
    :icon (if armed "lock_open" "bolt")
    :variant (if armed "filled" "outlined")
    :enabled (if (or armed compatible) t :json-false))
   :spacing 8 :fill t))

(provide 'jetpacs-component-catalog-editor)
;;; jetpacs-component-catalog-editor.el ends here
