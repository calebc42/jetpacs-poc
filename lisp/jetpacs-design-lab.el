;;; jetpacs-design-lab.el --- bounded Jetpacs design profile editor -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A second catalog app for editing named `jetpacs.design' profiles.  The
;; authoring shell always uses the stable baseline renderer; only the preview
;; subtree receives the draft design scope.  Source editing uses the inert
;; `jetpacs-authoring' reader and a closed symbol normalizer—forms are never
;; evaluated.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-authoring)
(require 'jetpacs-components)
(require 'jetpacs-design-profiles)
(require 'jetpacs-design-baseline)
(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-shell)
(require 'jetpacs-surfaces)

(defconst jetpacs-design-lab-owner "jpdesign"
  "Owner of the Jetpacs Design Lab app surface and actions.")

(defconst jetpacs-design-lab-title "Jetpacs Design Lab"
  "User-facing label for the profile editor.")

(defconst jetpacs-design-lab--sections
  '(("Profiles" . "profiles")
    ("Typography" . "typography")
    ("Tokens" . "tokens")
    ("Styles & states" . "styles")
    ("Motions" . "motions")
    ("Bindings" . "bindings")
    ("Preview" . "preview")
    ("Source" . "source"))
  "Closed Design Lab section navigation.")

(defvar jetpacs-design-lab--section "profiles"
  "Currently selected Design Lab section.")

(defvar jetpacs-design-lab--selected-profile-id "jetpacs.baseline"
  "Profile currently represented by the process-local draft.")

(defvar jetpacs-design-lab--draft nil
  "Last valid process-local profile draft.")

(defvar jetpacs-design-lab--draft-error nil
  "Bounded diagnostic for the latest rejected edit, or nil.")

(defvar jetpacs-design-lab--save-id "my-design"
  "Process-local Save As identifier entry.")

(defvar jetpacs-design-lab--save-label "My design"
  "Process-local Save As label entry.")

(defconst jetpacs-design-lab--source-symbols
  '(":version" ":id" ":label" ":tokens" ":typography" ":styles"
    ":motions" ":component-styles" ":theme-roles" ":family" ":size" ":weight"
    ":line-height" ":letter-spacing" ":kind" ":value"
    ":properties" ":rules" ":motion" ":state" ":duration_ms"
    ":easing" ":background_color" ":content_color" ":border_color"
    ":border_width" ":corner_radius" ":padding" ":padding_horizontal"
    ":padding_vertical" ":padding_start" ":padding_top" ":padding_end"
    ":padding_bottom" ":width" ":height" ":min_width" ":min_height"
    ":alpha" ":scale" ":font_size" ":line_height"
    ":letter_spacing" ":font_family" ":font_weight" ":text_align"
    ":fill_width" ":json-false")
  "Only symbols an inert canonical profile source may contain.")

(defun jetpacs-design-lab--baseline-profile ()
  "Return the platform baseline preset the Lab edits copies of."
  (jetpacs-design-baseline-profile))

(defun jetpacs-design-lab--ensure-draft ()
  "Ensure the selected profile has one last-valid process-local draft."
  (unless jetpacs-design-lab--draft
    (setq jetpacs-design-lab--draft
          (or (jetpacs-design-profile-get
               jetpacs-design-lab--selected-profile-id)
              (car (jetpacs-design-profiles)))))
  jetpacs-design-lab--draft)

(defun jetpacs-design-lab--digest ()
  "Return the canonical digest of the last-valid draft."
  (jetpacs-authoring-digest (jetpacs-design-lab--ensure-draft)
                            (* 256 1024)))

(defun jetpacs-design-lab--normalize-source (value)
  "Normalize inert reader VALUE onto the closed profile symbol vocabulary."
  (cond
   ((null value) nil)
   ((vectorp value)
    (vconcat (mapcar #'jetpacs-design-lab--normalize-source value)))
   ((consp value)
    (cons (jetpacs-design-lab--normalize-source (car value))
          (jetpacs-design-lab--normalize-source (cdr value))))
   ((symbolp value)
    (let ((name (symbol-name value)))
      (cond
       ((equal name "t") t)
       ((member name jetpacs-design-lab--source-symbols) (intern name))
       (t (error "Design Lab: Source contains an unknown symbol")))))
   (t value)))

(defun jetpacs-design-lab--parse-source (source)
  "Read and validate one inert profile SOURCE string."
  (jetpacs-design-validate-profile
   (jetpacs-design-lab--normalize-source
    (jetpacs-authoring-read-one source (* 256 1024)))))

(defun jetpacs-design-lab--action (name &rest args)
  "Build drop-only Design Lab action NAME with member plist ARGS."
  (jetpacs-action name :args args))

(defun jetpacs-design-lab--live-p (params)
  "Return non-nil when PARAMS belongs to the live Design Lab surface."
  (and (equal (plist-get params :surface) "app:jpdesign")
       (not (jetpacs-event-stale-p params))))

(defun jetpacs-design-lab--current-digest-p (args)
  "Return non-nil when ARGS addresses the current draft digest."
  (equal (plist-get args :digest) (jetpacs-design-lab--digest)))

(defun jetpacs-design-lab--refresh (params)
  "Defer one Design Lab refresh for event PARAMS."
  (jetpacs-app-defer-refresh params))

(defun jetpacs-design-lab--set-error (err)
  "Retain a bounded label for rejected authoring ERR."
  (setq jetpacs-design-lab--draft-error
        (truncate-string-to-width (jetpacs-error-label err) 180 nil nil "…")))

(defun jetpacs-design-lab--number (value integer)
  "Parse bounded numeric string VALUE; require INTEGER when non-nil."
  (unless (and (stringp value)
               (string-match-p
                (if integer "\\`[0-9]+\\'" "\\`[0-9]+\\(?:\\.[0-9]+\\)?\\'")
                value))
    (error "Design Lab: Expected a non-negative number"))
  (if integer (string-to-number value) (string-to-number value)))

(defun jetpacs-design-lab--value-with-text (design-value text)
  "Rebuild DESIGN-VALUE using user-authored TEXT and its existing kind."
  (pcase (plist-get design-value :kind)
    ("boolean"
     (jetpacs-design-boolean
      (pcase text ("true" t) ("false" :json-false)
        (_ (error "Design Lab: Boolean must be true or false")))))
    ("color" (jetpacs-design-color text))
    ("theme-role" (jetpacs-design-theme-role text))
    ("dimension" (jetpacs-design-dimension
                  (jetpacs-design-lab--number text nil)))
    ("number" (jetpacs-design-number (jetpacs-design-lab--number text nil)))
    ("font-family" (jetpacs-design-font-family text))
    ("font-weight" (jetpacs-design-font-weight
                    (jetpacs-design-lab--number text t)))
    ("text-align" (jetpacs-design-text-align text))
    ("token" (jetpacs-design-token text))
    (_ (error "Design Lab: Unsupported design value kind"))))

(defun jetpacs-design-lab--keyword (name allowed)
  "Return keyword for NAME when it is in string list ALLOWED."
  (unless (member name allowed) (error "Design Lab: Unknown field"))
  (intern (concat ":" name)))

(defun jetpacs-design-lab--edit-typography (profile item field value)
  "Return PROFILE with typography ITEM FIELD replaced by VALUE."
  (let* ((copy (copy-tree profile))
         (entry (assoc item (plist-get copy :typography)))
         (key (jetpacs-design-lab--keyword
               field '("family" "size" "weight" "line-height"
                       "letter-spacing"))))
    (unless entry (error "Design Lab: Unknown typography role"))
    (setcdr entry
            (plist-put
             (cdr entry) key
             (if (equal field "family") value
               (jetpacs-design-lab--number
                value (equal field "weight")))))
    copy))

(defun jetpacs-design-lab--edit-token (profile item value)
  "Return PROFILE with token ITEM's scalar VALUE replaced."
  (let* ((copy (copy-tree profile))
         (entry (assoc item (plist-get copy :tokens))))
    (unless entry (error "Design Lab: Unknown token"))
    (setcdr entry (jetpacs-design-lab--value-with-text (cdr entry) value))
    copy))

(defun jetpacs-design-lab--edit-motion (profile item field value)
  "Return PROFILE with motion ITEM FIELD replaced by VALUE."
  (let* ((copy (copy-tree profile))
         (entry (assoc item (plist-get copy :motions))))
    (unless entry (error "Design Lab: Unknown motion"))
    (setcdr entry
            (pcase field
              ("duration"
               (jetpacs-design-motion
                (jetpacs-design-lab--number value t)
                (plist-get (cdr entry) :easing)))
              ("easing"
               (jetpacs-design-motion
                (plist-get (cdr entry) :duration_ms) value))
              (_ (error "Design Lab: Unknown motion field"))))
    copy))

(defun jetpacs-design-lab--edit-binding (profile item value)
  "Return PROFILE with binding ITEM replaced from comma-separated VALUE."
  (let* ((copy (copy-tree profile))
         (entry (assoc item (plist-get copy :component-styles)))
         (styles (split-string value "[[:space:]]*,[[:space:]]*" t)))
    (unless entry (error "Design Lab: Unknown component binding"))
    (setcdr entry styles)
    copy))

(defun jetpacs-design-lab--style-rule (style index)
  "Return STYLE rule at numeric INDEX, or signal."
  (let ((rules (plist-get style :rules)))
    (unless (and (integerp index) (<= 0 index) (< index (length rules)))
      (error "Design Lab: Unknown style rule"))
    (aref rules index)))

(defun jetpacs-design-lab--edit-style (profile item field index value)
  "Return PROFILE with style ITEM leaf FIELD at INDEX replaced by VALUE."
  (let* ((copy (copy-tree profile))
         (entry (assoc item (plist-get copy :styles)))
         (style (cdr entry)))
    (unless entry (error "Design Lab: Unknown style"))
    (if index
        (let* ((rules (copy-sequence (plist-get style :rules)))
               (rule (copy-tree (jetpacs-design-lab--style-rule style index))))
          (if (equal field "state")
              (setq rule (plist-put rule :state value))
            (let* ((properties (plist-get rule :properties))
                   (key (intern (concat ":" field)))
                   (old (plist-get properties key)))
              (unless old (error "Design Lab: Unknown rule property"))
              (setq properties
                    (plist-put properties key
                               (jetpacs-design-lab--value-with-text old value)))
              (setq rule (plist-put rule :properties properties))))
          (aset rules index rule)
          (setcdr entry (plist-put style :rules rules)))
      (let* ((properties (plist-get style :properties))
             (key (intern (concat ":" field)))
             (old (plist-get properties key)))
        (unless old (error "Design Lab: Unknown style property"))
        (setq properties
              (plist-put properties key
                         (jetpacs-design-lab--value-with-text old value)))
        (setcdr entry (plist-put style :properties properties))))
    copy))

(defun jetpacs-design-lab--edited-profile (args)
  "Return a validated draft after applying leaf edit ARGS."
  (let ((domain (plist-get args :domain))
        (item (plist-get args :item))
        (field (plist-get args :field))
        (index (plist-get args :index))
        (value (plist-get args :value))
        (profile (jetpacs-design-lab--ensure-draft)))
    (unless (and (stringp domain) (stringp item) (stringp value))
      (error "Design Lab: Malformed edit"))
    (jetpacs-design-validate-profile
     (pcase domain
       ("typography"
        (jetpacs-design-lab--edit-typography profile item field value))
       ("token" (jetpacs-design-lab--edit-token profile item value))
       ("motion" (jetpacs-design-lab--edit-motion profile item field value))
       ("binding" (jetpacs-design-lab--edit-binding profile item value))
       ("style"
        (jetpacs-design-lab--edit-style profile item field index value))
       (_ (error "Design Lab: Unknown edit domain"))))))

(defun jetpacs-design-lab--on-section (args params)
  "Select Design Lab section from ARGS under event PARAMS."
  (let ((value (plist-get args :value)))
    (if (not (and (jetpacs-design-lab--live-p params)
                  (assoc value (mapcar (lambda (entry)
                                         (cons (cdr entry) (car entry)))
                                       jetpacs-design-lab--sections))))
        'stale
      (setq jetpacs-design-lab--section value)
      (jetpacs-design-lab--refresh params)
      'accepted)))

(defun jetpacs-design-lab--on-profile (args params)
  "Load profile named by ARGS under event PARAMS."
  (let* ((id (plist-get args :id))
         (profile (jetpacs-design-profile-get id)))
    (if (not (and (jetpacs-design-lab--live-p params) profile))
        'stale
      (setq jetpacs-design-lab--selected-profile-id id
            jetpacs-design-lab--draft profile
            jetpacs-design-lab--draft-error nil)
      (jetpacs-design-lab--refresh params)
      'accepted)))

(defun jetpacs-design-lab--on-edit (args params)
  "Apply one digest-addressed visual edit from ARGS under PARAMS."
  (cond
   ((not (jetpacs-design-lab--live-p params)) 'stale)
   ((not (jetpacs-design-lab--current-digest-p args)) 'stale)
   (t
    (condition-case err
        (setq jetpacs-design-lab--draft
              (jetpacs-design-lab--edited-profile args)
              jetpacs-design-lab--draft-error nil)
      (error (jetpacs-design-lab--set-error err)))
    (jetpacs-design-lab--refresh params)
    'accepted)))

(defun jetpacs-design-lab--on-source (args params)
  "Apply inert profile source in ARGS under event PARAMS."
  (cond
   ((not (jetpacs-design-lab--live-p params)) 'stale)
   ((not (jetpacs-design-lab--current-digest-p args)) 'stale)
   ((not (stringp (plist-get args :value))) 'rejected)
   (t
    (condition-case err
        (setq jetpacs-design-lab--draft
              (jetpacs-design-lab--parse-source (plist-get args :value))
              jetpacs-design-lab--draft-error nil)
      (error (jetpacs-design-lab--set-error err)))
    (jetpacs-design-lab--refresh params)
    'accepted)))

(defun jetpacs-design-lab--on-reset (_args params)
  "Reset the selected draft under event PARAMS."
  (if-let* ((profile (and (jetpacs-design-lab--live-p params)
                          (jetpacs-design-profile-get
                           jetpacs-design-lab--selected-profile-id))))
      (progn
        (setq jetpacs-design-lab--draft profile
              jetpacs-design-lab--draft-error nil)
        (jetpacs-design-lab--refresh params)
        'accepted)
    'stale))

(defun jetpacs-design-lab--on-save-entry (args params)
  "Update process-local Save As field named by ARGS under PARAMS."
  (if (not (and (jetpacs-design-lab--live-p params)
                (stringp (plist-get args :value))))
      'stale
    (pcase (plist-get args :field)
      ("id"
       (setq jetpacs-design-lab--save-id (plist-get args :value))
       'accepted)
      ("label"
       (setq jetpacs-design-lab--save-label (plist-get args :value))
       'accepted)
      (_ 'rejected))))

(defun jetpacs-design-lab--on-save-as (_args params)
  "Persist the current draft as a new user profile under PARAMS."
  (if (not (jetpacs-design-lab--live-p params))
      'stale
    (condition-case err
        (let ((copy (copy-tree (jetpacs-design-lab--ensure-draft))))
          (setq copy (plist-put copy :id jetpacs-design-lab--save-id))
          (setq copy (plist-put copy :label jetpacs-design-lab--save-label))
          (jetpacs-design-save-profile-as copy t)
          (setq jetpacs-design-lab--selected-profile-id
                jetpacs-design-lab--save-id
                jetpacs-design-lab--draft copy
                jetpacs-design-lab--draft-error nil))
      (error (jetpacs-design-lab--set-error err)))
    (jetpacs-design-lab--refresh params)
    'accepted))

(defun jetpacs-design-lab--on-apply (args params)
  "Persist and activate the current user draft addressed by ARGS and PARAMS."
  (cond
   ((not (jetpacs-design-lab--live-p params)) 'stale)
   ((not (jetpacs-design-lab--current-digest-p args)) 'stale)
   (t
    (condition-case err
        (let ((stored (jetpacs-design-profile-get
                       jetpacs-design-lab--selected-profile-id)))
          (if (member jetpacs-design-lab--selected-profile-id
                      (mapcar (lambda (profile) (plist-get profile :id))
                              jetpacs-design-user-profiles))
              (jetpacs-design-update-user-profile
               (jetpacs-design-lab--ensure-draft) t)
            (unless (equal stored (jetpacs-design-lab--ensure-draft))
              (error "Design Lab: Save As before applying changes to a preset")))
          (jetpacs-design-set-active-profile
           jetpacs-design-lab--selected-profile-id t)
          (setq jetpacs-design-lab--draft-error nil))
      (error (jetpacs-design-lab--set-error err)))
    (jetpacs-design-lab--refresh params)
    'accepted)))

(defun jetpacs-design-lab--on-delete (_args params)
  "Delete the selected user profile under event PARAMS."
  (if (not (jetpacs-design-lab--live-p params))
      'stale
    (condition-case err
        (progn
          (jetpacs-design-delete-user-profile
           jetpacs-design-lab--selected-profile-id t)
          (setq jetpacs-design-lab--selected-profile-id "jetpacs.baseline"
                jetpacs-design-lab--draft
                (jetpacs-design-profile-get "jetpacs.baseline")
                jetpacs-design-lab--draft-error nil))
      (error (jetpacs-design-lab--set-error err)))
    (jetpacs-design-lab--refresh params)
    'accepted))

(defun jetpacs-design-lab--on-preview (_args _params)
  "Accept a local Design Lab preview interaction without profile mutation."
  'accepted)

(defun jetpacs-design-lab--field-id (domain item field &optional index)
  "Return stable input ID for DOMAIN ITEM FIELD and optional INDEX."
  (concat "jpdesign-"
          (substring (secure-hash 'sha256
                                  (format "%s/%s/%s/%s"
                                          domain item field index))
                     0 20)))

(defun jetpacs-design-lab--field
    (label value domain item field &optional index)
  "Build LABEL field editing VALUE at DOMAIN, ITEM, FIELD, and optional INDEX."
  (jetpacs-text-input
   (jetpacs-design-lab--field-id domain item field index)
   :value (format "%s" value)
   :label label
   :single-line t
   :on-submit
   (jetpacs-design-lab--action
    "jpdesign.edit"
    :digest (jetpacs-design-lab--digest)
    :domain domain :item item :field field :index index)))

(defun jetpacs-design-lab--section-tabs ()
  "Build controlled section navigation for the stable authoring shell."
  (jetpacs-component-tabs
   "jpdesign-sections"
   (mapcar (lambda (entry) (jetpacs-component-tab (car entry) (cdr entry)))
           jetpacs-design-lab--sections)
   jetpacs-design-lab--section
   (jetpacs-action "jpdesign.section")
   :variant "navigator"))

(defun jetpacs-design-lab--profiles-content ()
  "Build profile selection, persistence, and activation controls."
  (let* ((draft (jetpacs-design-lab--ensure-draft))
         (active (or jetpacs-design-active-profile-id "Applet default"))
         (is-user (assoc jetpacs-design-lab--selected-profile-id
                         (mapcar (lambda (profile)
                                   (cons (plist-get profile :id) profile))
                                 jetpacs-design-user-profiles))))
    (list
     (jetpacs-component-panel
      "PROFILE"
      (list
       (jetpacs-text (format "Editing %s (%s)"
                             (plist-get draft :label)
                             (plist-get draft :id)))
       (jetpacs-text (format "Active: %s" active) :style "caption")
       (jetpacs-component-action
        "Apply globally"
        (jetpacs-design-lab--action "jpdesign.apply"
                                    :digest (jetpacs-design-lab--digest)))
       (jetpacs-component-action
        "Reset draft" (jetpacs-design-lab--action "jpdesign.reset"))
       (jetpacs-component-action
        "Delete user profile" (jetpacs-design-lab--action "jpdesign.delete")
        :enabled (if is-user t :json-false))))
     (jetpacs-component-panel
      "AVAILABLE PROFILES"
      (mapcar
       (lambda (profile)
         (jetpacs-component-action
          (format "%s%s" (plist-get profile :label)
                  (if (equal (plist-get profile :id)
                             jetpacs-design-lab--selected-profile-id)
                      " · editing" ""))
          (jetpacs-design-lab--action
           "jpdesign.profile" :id (plist-get profile :id))))
       (jetpacs-design-profiles)))
     (jetpacs-component-panel
      "SAVE AS"
      (list
       (jetpacs-text-input
        "jpdesign-save-id" :value jetpacs-design-lab--save-id :label "ID"
        :single-line t
        :on-change (jetpacs-design-lab--action
                    "jpdesign.save-entry" :field "id"))
       (jetpacs-text-input
        "jpdesign-save-label" :value jetpacs-design-lab--save-label
        :label "Label" :single-line t
        :on-change (jetpacs-design-lab--action
                    "jpdesign.save-entry" :field "label"))
       (jetpacs-component-action
        "Save new profile" (jetpacs-design-lab--action "jpdesign.save-as")))))))

(defun jetpacs-design-lab--typography-content ()
  "Build editors for every typography role in the draft."
  (mapcar
   (lambda (entry)
     (let ((id (car entry)) (value (cdr entry)))
       (jetpacs-component-panel
        id
        (mapcar
         (lambda (field)
           (jetpacs-design-lab--field
            (car field) (plist-get value (cdr field))
            "typography" id (car field)))
         '(("family" . :family) ("size" . :size) ("weight" . :weight)
           ("line-height" . :line-height)
           ("letter-spacing" . :letter-spacing))))))
   (plist-get (jetpacs-design-lab--ensure-draft) :typography)))

(defun jetpacs-design-lab--tokens-content ()
  "Build scalar editors for every design token in the draft."
  (mapcar
   (lambda (entry)
     (jetpacs-component-panel
      (car entry)
      (list
       (jetpacs-text (format "Kind: %s" (plist-get (cdr entry) :kind))
                     :style "caption")
       (jetpacs-design-lab--field
        "value" (plist-get (cdr entry) :value) "token" (car entry) "value"))))
   (plist-get (jetpacs-design-lab--ensure-draft) :tokens)))

(defun jetpacs-design-lab--property-fields
    (style-id properties &optional rule-index)
  "Build leaf editors for STYLE-ID PROPERTIES and optional RULE-INDEX."
  (cl-loop for (key value) on properties by #'cddr
           collect
           (jetpacs-design-lab--field
            (substring (symbol-name key) 1)
            (plist-get value :value)
            "style" style-id (substring (symbol-name key) 1) rule-index)))

(defun jetpacs-design-lab--styles-content ()
  "Build base-property and ordered-rule editors for every style."
  (mapcar
   (lambda (entry)
     (let ((id (car entry)) (style (cdr entry)) children)
       (setq children
             (append
              (list (jetpacs-text "Base properties" :style "label"))
              (jetpacs-design-lab--property-fields
               id (plist-get style :properties))))
       (cl-loop for rule across (plist-get style :rules)
                for index from 0
                do (setq children
                         (append
                          children
                          (list
                           (jetpacs-text
                            (format "Rule %d · %s" index
                                    (plist-get rule :state))
                            :style "label"))
                          (jetpacs-design-lab--property-fields
                           id (plist-get rule :properties) index))))
       (jetpacs-component-panel id children)))
   (plist-get (jetpacs-design-lab--ensure-draft) :styles)))

(defun jetpacs-design-lab--motions-content ()
  "Build duration and easing editors for every motion."
  (mapcar
   (lambda (entry)
     (jetpacs-component-panel
      (car entry)
      (list
       (jetpacs-design-lab--field
        "duration" (plist-get (cdr entry) :duration_ms)
        "motion" (car entry) "duration")
       (jetpacs-design-lab--field
        "easing" (plist-get (cdr entry) :easing)
        "motion" (car entry) "easing"))))
   (plist-get (jetpacs-design-lab--ensure-draft) :motions)))

(defun jetpacs-design-lab--bindings-content ()
  "Build comma-separated style-reference editors for component bindings."
  (mapcar
   (lambda (entry)
     (jetpacs-component-panel
      (car entry)
      (list
       (jetpacs-design-lab--field
        "Ordered styles" (string-join (cdr entry) ", ")
        "binding" (car entry) "styles"))))
   (plist-get (jetpacs-design-lab--ensure-draft) :component-styles)))

(defun jetpacs-design-lab--preview-specimens ()
  "Return one inert specimen of every Jetpacs semantic component."
  (let ((noop (jetpacs-design-lab--action "jpdesign.preview")))
    (list
     (jetpacs-component-action "Action" noop)
     (jetpacs-component-choice
      "jpdesign-preview-choice" "Choice" t noop)
     (jetpacs-component-tabs
      "jpdesign-preview-tabs"
      (list (jetpacs-component-tab "One" "one")
            (jetpacs-component-tab "Two" "two"))
      "one" noop)
     (jetpacs-component-section-navigator
      "jpdesign-preview-sections"
      (list (jetpacs-component-section "Overview" "overview" 1)
            (jetpacs-component-section "Detail" "detail" 2))
      "overview" noop)
     (jetpacs-component-panel
      "PANEL" (list (jetpacs-text "Passive panel content")))
     (jetpacs-text-input
      "jpdesign-preview-field" :value "Editable field" :label "Text Field"
      :on-change noop)
     (jetpacs-editor
      "jpdesign-preview-editor" :value "A configurable editor\nwith a gutter."
      :line-numbers t :on-save noop))))

(defun jetpacs-design-lab--preview-content ()
  "Build live preview, scoped only when the design extension is advertised."
  (let* ((specimens (jetpacs-component-scope
                     (jetpacs-design-lab--preview-specimens)))
         (available (jetpacs-extension-advertised-p "jetpacs.design" :app))
         (preview (if available
                      (jetpacs-design-profile-scope
                       (jetpacs-design-lab--ensure-draft) (list specimens))
                    specimens)))
    (append
     (unless available
       (list
        (jetpacs-component-panel
         "DESIGN RUNTIME UNAVAILABLE"
         (list
          (jetpacs-text
           "Enable Experimental Elisp design runtime in Companion Settings, then reconnect. Baseline specimens remain available for repair.")))))
     (list preview))))

(defun jetpacs-design-lab--source-content ()
  "Build the inert canonical profile editor."
  (let ((source (jetpacs-authoring-print
                 (jetpacs-design-lab--ensure-draft) (* 256 1024))))
    (list
     (jetpacs-component-panel
      "INERT COMPLETE PROFILE"
      (list
       (jetpacs-text
        "Edit or add bounded tokens, typography, styles, state rules, motions, and bindings. Save validates one inert form; no Lisp is evaluated."
        :style "caption")
       (jetpacs-editor
        "jpdesign-source" :value source :syntax "elisp" :line-numbers t
        :on-save
        (jetpacs-design-lab--action
         "jpdesign.source" :digest (jetpacs-design-lab--digest))))))))

(defun jetpacs-design-lab--content ()
  "Return nodes for the selected Design Lab section."
  (pcase jetpacs-design-lab--section
    ("profiles" (jetpacs-design-lab--profiles-content))
    ("typography" (jetpacs-design-lab--typography-content))
    ("tokens" (jetpacs-design-lab--tokens-content))
    ("styles" (jetpacs-design-lab--styles-content))
    ("motions" (jetpacs-design-lab--motions-content))
    ("bindings" (jetpacs-design-lab--bindings-content))
    ("preview" (jetpacs-design-lab--preview-content))
    ("source" (jetpacs-design-lab--source-content))
    (_ (error "Design Lab: Unknown section"))))

(defun jetpacs-design-lab--screen (back)
  "Build the stable authoring shell using BACK navigation."
  (let ((nodes
         (append
          (list (jetpacs-design-lab--section-tabs))
          (when jetpacs-design-lab--draft-error
            (list
             (jetpacs-component-panel
              "LAST EDIT REJECTED"
              (list (jetpacs-text jetpacs-design-lab--draft-error
                                  :color "error")))))
          (jetpacs-design-lab--content))))
    (jetpacs-chrome-screen
     jetpacs-design-lab-title
     (apply #'jetpacs-lazy-column
            (append nodes (list :spacing 12 :content-padding 12)))
     :back back)))

(defun jetpacs-design-lab--dock-items (surface)
  "Return the Design Lab dock item selected for SURFACE."
  (let ((home (jetpacs-shell-surface-for jetpacs-design-lab-owner)))
    (list (list :label jetpacs-design-lab-title :icon "palette"
                :on-tap (jetpacs-shell-open-surface-action home)
                :selected (equal surface home)))))

(defconst jetpacs-design-lab--verbs
  '("jpdesign.section" "jpdesign.profile" "jpdesign.edit"
    "jpdesign.source" "jpdesign.reset" "jpdesign.save-entry"
    "jpdesign.save-as" "jpdesign.apply" "jpdesign.delete"
    "jpdesign.preview")
  "Actions owned by the Design Lab app.")

(defun jetpacs-design-lab-register ()
  "Register Design Lab preset, actions, surface, and app identity."
  (jetpacs-design-baseline-register)
  (with-jetpacs-owner jetpacs-design-lab-owner
    (jetpacs-defaction
     "jpdesign.section" #'jetpacs-design-lab--on-section
     :args '((:name value :type "text" :required t))
     :doc "Select one bounded Design Lab editor section")
    (jetpacs-defaction
     "jpdesign.profile" #'jetpacs-design-lab--on-profile
     :args '((:name id :type "text" :required t))
     :doc "Load one registered or persisted design profile draft")
    (jetpacs-defaction
     "jpdesign.edit" #'jetpacs-design-lab--on-edit
     :args '((:name digest :type "text" :required t)
             (:name domain :type "enum" :required t)
             (:name item :type "text" :required t)
             (:name field :type "text" :required t)
             (:name index :type "number")
             (:name value :type "text" :required t))
     :doc "Apply one digest-addressed scalar design profile edit")
    (jetpacs-defaction
     "jpdesign.source" #'jetpacs-design-lab--on-source
     :args '((:name digest :type "text" :required t)
             (:name value :type "text" :required t))
     :doc "Validate and apply one complete inert design profile form")
    (jetpacs-defaction
     "jpdesign.reset" #'jetpacs-design-lab--on-reset
     :doc "Restore the selected profile's persisted snapshot")
    (jetpacs-defaction
     "jpdesign.save-entry" #'jetpacs-design-lab--on-save-entry
     :args '((:name field :type "enum" :required t)
             (:name value :type "text" :required t))
     :doc "Update a process-local Save As name or label")
    (jetpacs-defaction
     "jpdesign.save-as" #'jetpacs-design-lab--on-save-as
     :doc "Persist the current draft as a new named user profile")
    (jetpacs-defaction
     "jpdesign.apply" #'jetpacs-design-lab--on-apply
     :args '((:name digest :type "text" :required t))
     :doc "Persist a user draft and select it for opted-in app content")
    (jetpacs-defaction
     "jpdesign.delete" #'jetpacs-design-lab--on-delete
     :doc "Delete the selected user profile and restore the baseline draft")
    (jetpacs-defaction
     "jpdesign.preview" #'jetpacs-design-lab--on-preview
     :args '((:name value :type "any"))
     :doc "Accept a preview-only component interaction without profile state")
    (jetpacs-chrome-define-root
     jetpacs-design-lab-owner "home" #'jetpacs-design-lab--screen :required t))
  (jetpacs-defapp
   jetpacs-design-lab-owner
   :label jetpacs-design-lab-title
   :icon "palette"
   :surfaces (list jetpacs-design-lab-owner)
   :dock #'jetpacs-design-lab--dock-items
   :requires-extensions '("jetpacs.components")
   :order 92))

(defun jetpacs-design-lab-unregister ()
  "Remove Design Lab actions, surface, and app.
The baseline preset belongs to the platform and stays registered."
  (dolist (verb jetpacs-design-lab--verbs) (jetpacs-undefaction verb))
  (jetpacs-apps-unregister jetpacs-design-lab-owner)
  (jetpacs-chrome-remove jetpacs-design-lab-owner)
  (setq jetpacs-design-lab--draft nil
        jetpacs-design-lab--draft-error nil
        jetpacs-design-lab--section "profiles"
        jetpacs-design-lab--selected-profile-id "jetpacs.baseline"))

(jetpacs-design-lab-register)

;;;###autoload
(defun jetpacs-design-lab ()
  "Open Jetpacs Design Lab on the connected Companion."
  (interactive)
  (jetpacs-chrome-reset-screens jetpacs-design-lab-owner))

(defun jetpacs-design-lab-unload-function ()
  "Unload Design Lab without leaving registrations behind."
  (jetpacs-design-lab-unregister)
  nil)

(provide 'jetpacs-design-lab)
;;; jetpacs-design-lab.el ends here
