;;; jetpacs-design-lab.el --- bounded Jetpacs design profile editor -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A second catalog app for editing named `jetpacs.design' profiles.  The
;; authoring shell wears the host's active profile; only the preview subtree
;; receives the draft design scope.  Every entry is a folded disclosure whose
;; header summarizes its value; closed leaves (families, weights, alignments,
;; theme roles, easings, rule states, motion and token references) are
;; dropdowns over the public design vocabularies, booleans are switches on a
;; dedicated boolean action, and numbers carry numeric keyboards.  Source
;; editing uses the inert `jetpacs-authoring' reader and a closed symbol
;; normalizer—forms are never evaluated.

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
  "Retain a bounded label for rejected authoring ERR.
The Lab's own signals and the design runtime's carry the reason in their
message and no private payload, so the message is shown; any other
condition shows only its symbol, as `jetpacs-error-label' does for logs."
  (setq jetpacs-design-lab--draft-error
        (truncate-string-to-width
         (if (and (consp err) (eq (car err) 'error) (stringp (cadr err)))
             (error-message-string err)
           (jetpacs-error-label err))
         180 nil nil "…")))

(defun jetpacs-design-lab--number (value integer &optional negative)
  "Parse bounded numeric string VALUE; require INTEGER when non-nil.
NEGATIVE non-nil admits a leading minus sign."
  (unless (and (stringp value)
               (string-match-p
                (concat "\\`" (if negative "-?" "")
                        (if integer "[0-9]+" "[0-9]+\\(?:\\.[0-9]+\\)?")
                        "\\'")
                value))
    (error (if negative
               "Design Lab: Expected a number"
             "Design Lab: Expected a non-negative number")))
  (string-to-number value))

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
    ("number" (jetpacs-design-number
               (jetpacs-design-lab--number text nil t)))
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

(defun jetpacs-design-lab--with-motion (plist value)
  "Return PLIST with its `:motion' set to VALUE, or removed for \"__none\".
The motion reference is the one optional leaf a style or rule carries."
  (let ((copy (copy-sequence plist)))
    (if (equal value "__none")
        (progn (cl-remf copy :motion) copy)
      (plist-put copy :motion value))))

(defun jetpacs-design-lab--edit-style (profile item field index value)
  "Return PROFILE with style ITEM leaf FIELD at INDEX replaced by VALUE."
  (let* ((copy (copy-tree profile))
         (entry (assoc item (plist-get copy :styles)))
         (style (cdr entry)))
    (unless entry (error "Design Lab: Unknown style"))
    (if index
        (let* ((rules (copy-sequence (plist-get style :rules)))
               (rule (copy-tree (jetpacs-design-lab--style-rule style index))))
          (cond
           ((equal field "state")
            (setq rule (plist-put rule :state value)))
           ((equal field "motion")
            (setq rule (jetpacs-design-lab--with-motion rule value)))
           (t
            (let* ((properties (plist-get rule :properties))
                   (key (intern (concat ":" field)))
                   (old (plist-get properties key)))
              (unless old (error "Design Lab: Unknown rule property"))
              (setq properties
                    (plist-put properties key
                               (jetpacs-design-lab--value-with-text old value)))
              (setq rule (plist-put rule :properties properties)))))
          (aset rules index rule)
          (setcdr entry (plist-put style :rules rules)))
      (if (equal field "motion")
          (setcdr entry (jetpacs-design-lab--with-motion style value))
        (let* ((properties (plist-get style :properties))
               (key (intern (concat ":" field)))
               (old (plist-get properties key)))
          (unless old (error "Design Lab: Unknown style property"))
          (setq properties
                (plist-put properties key
                           (jetpacs-design-lab--value-with-text old value)))
          (setcdr entry (plist-put style :properties properties)))))
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
  "Load the profile the picker's ARGS name under event PARAMS."
  (let* ((id (plist-get args :value))
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

(defun jetpacs-design-lab--on-boolean-edit (args params)
  "Apply one switch-injected boolean edit from ARGS under PARAMS.
A switch injects a native boolean; the dedicated wire action keeps that
type honest, then the edit joins the ordinary string-valued path."
  (let ((value (plist-get args :value)))
    (if (not (memq value '(t :json-false)))
        'rejected
      (jetpacs-design-lab--on-edit
       (plist-put (copy-sequence args) :value (if (eq value t) "true" "false"))
       params))))

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

(defun jetpacs-design-lab--edit-action (domain item field &optional index)
  "Return the digest-addressed edit descriptor for DOMAIN ITEM FIELD INDEX."
  (jetpacs-design-lab--action
   "jpdesign.edit"
   :digest (jetpacs-design-lab--digest)
   :domain domain :item item :field field :index index))

(defun jetpacs-design-lab--boolean-action (domain item field &optional index)
  "Return the boolean edit descriptor for DOMAIN ITEM FIELD INDEX."
  (jetpacs-design-lab--action
   "jpdesign.edit.boolean"
   :digest (jetpacs-design-lab--digest)
   :domain domain :item item :field field :index index))

(defun jetpacs-design-lab--options (values)
  "Return dropdown options for VALUES, spelled exactly as authored."
  (mapcar (lambda (value)
            (jetpacs-enum-option (format "%s" value) (format "%s" value)))
          values))

(defun jetpacs-design-lab--token-kind (draft id)
  "Return the terminal value kind of DRAFT's token ID, or nil when unknown.
A token that references another token takes that token's kind."
  (let ((tokens (plist-get draft :tokens))
        (current id)
        (hops 0)
        kind)
    (while (and current (<= hops (length tokens)))
      (let ((entry (cdr (assoc current tokens))))
        (setq hops (1+ hops))
        (cond
         ((null entry) (setq current nil))
         ((equal (plist-get entry :kind) "token")
          (setq current (plist-get entry :value)))
         (t (setq kind (plist-get entry :kind) current nil)))))
    kind))

(defun jetpacs-design-lab--token-ids (draft expected &optional self)
  "Return DRAFT's token ids a leaf expecting kind EXPECTED may reference.
EXPECTED nil admits every token; a theme role satisfies a color, as the
design runtime allows.  SELF, when non-nil, is excluded."
  (cl-loop for entry in (plist-get draft :tokens)
           for id = (car entry)
           for kind = (jetpacs-design-lab--token-kind draft id)
           when (and kind
                     (not (equal id self))
                     (or (null expected)
                         (equal kind expected)
                         (and (equal expected "color")
                              (equal kind "theme-role"))))
           collect id))

(defun jetpacs-design-lab--kind-options (kind draft &optional expected self)
  "Return the closed choices for a value of KIND, or nil for free text.
DRAFT supplies token ids, filtered to EXPECTED and excluding SELF."
  (pcase kind
    ("font-family" jetpacs-design-font-families)
    ("font-weight" (mapcar #'number-to-string jetpacs-design-font-weights))
    ("text-align" jetpacs-design-text-aligns)
    ("theme-role" jetpacs-theme-roles)
    ("token" (jetpacs-design-lab--token-ids draft expected self))
    (_ nil)))

(defun jetpacs-design-lab--typography-caption (value)
  "Summarize typography VALUE for a folded header."
  (format "%s %s / %s · lh %s"
          (plist-get value :family) (plist-get value :size)
          (plist-get value :weight) (plist-get value :line-height)))

(defun jetpacs-design-lab--token-caption (value)
  "Summarize design VALUE for a folded header."
  (format "%s · %s" (plist-get value :kind) (plist-get value :value)))

(defun jetpacs-design-lab--style-caption (style)
  "Summarize STYLE for a folded header."
  (concat (format "%d properties · %d rules"
                  (/ (length (plist-get style :properties)) 2)
                  (length (plist-get style :rules)))
          (if (plist-get style :motion)
              (format " · motion %s" (plist-get style :motion))
            "")))

(defun jetpacs-design-lab--motion-caption (motion)
  "Summarize MOTION for a folded header."
  (format "%s ms · %s" (plist-get motion :duration_ms)
          (plist-get motion :easing)))

(defun jetpacs-design-lab--binding-caption (styles)
  "Summarize the ordered STYLES of a binding for a folded header."
  (truncate-string-to-width (string-join styles ", ") 40 nil nil "…"))

(defun jetpacs-design-lab--disclosure (domain item caption children)
  "Return a folded disclosure for DOMAIN's entry ITEM holding CHILDREN.
The header carries the entry id and CAPTION, its current value in brief."
  (jetpacs-collapsible
   (jetpacs-design-lab--field-id domain item "disclosure")
   (jetpacs-row
    (jetpacs-with-attrs (jetpacs-text item :style "label") :weight 1)
    (jetpacs-text caption :style "caption")
    :spacing 8 :align "center" :fill t)
   children
   :collapsed t))

(cl-defun jetpacs-design-lab--text-field
    (label value domain item field &key index keyboard hint)
  "Build LABEL text field editing VALUE at DOMAIN ITEM FIELD INDEX.
KEYBOARD names the soft keyboard and HINT the placeholder."
  (jetpacs-text-input
   (jetpacs-design-lab--field-id domain item field index)
   :value (format "%s" value)
   :label label
   :single-line t
   :keyboard keyboard
   :hint hint
   :on-submit (jetpacs-design-lab--edit-action domain item field index)))

(defun jetpacs-design-lab--dropdown-field
    (label value values domain item field &optional index)
  "Build LABEL dropdown choosing VALUE among VALUES at DOMAIN ITEM FIELD INDEX.
A value outside VALUES (a profile saved before a vocabulary changed) keeps
a text field, so it still renders and can be corrected."
  (let ((value (format "%s" value)))
    (if (member value values)
        (jetpacs-dropdown
         (jetpacs-design-lab--field-id domain item field index)
         (jetpacs-design-lab--options values)
         :label label
         :value value
         :on-change (jetpacs-design-lab--edit-action domain item field index))
      (jetpacs-design-lab--text-field label value domain item field
                                      :index index))))

(defun jetpacs-design-lab--switch-field
    (label value domain item field &optional index)
  "Build LABEL switch for boolean text VALUE at DOMAIN ITEM FIELD INDEX."
  (jetpacs-switch
   (jetpacs-design-lab--field-id domain item field index)
   :label label
   :checked (jetpacs-bool (equal value "true"))
   :on-change (jetpacs-design-lab--boolean-action domain item field index)))

(defun jetpacs-design-lab--motion-field (draft item motion &optional index)
  "Build the motion reference dropdown for style ITEM's rule INDEX in DRAFT.
MOTION is the current reference or nil; None clears it."
  (let ((ids (mapcar #'car (plist-get draft :motions))))
    (if (or (null motion) (member motion ids))
        (jetpacs-dropdown
         (jetpacs-design-lab--field-id "style" item "motion" index)
         (cons (jetpacs-enum-option "None" "__none")
               (jetpacs-design-lab--options ids))
         :label "motion"
         :value (or motion "__none")
         :on-change (jetpacs-design-lab--edit-action "style" item "motion" index))
      (jetpacs-design-lab--text-field "motion" motion "style" item "motion"
                                      :index index))))

(defun jetpacs-design-lab--value-field
    (draft label design-value domain item field &optional index expected)
  "Build the control for DESIGN-VALUE labelled LABEL at DOMAIN ITEM FIELD INDEX.
The control follows the value's kind: a switch, a text field with the
matching keyboard, or a dropdown over the closed vocabulary.  DRAFT supplies
token ids, narrowed to the property kind EXPECTED."
  (let ((kind (plist-get design-value :kind))
        (value (plist-get design-value :value)))
    (pcase kind
      ("boolean"
       (jetpacs-design-lab--switch-field label value domain item field index))
      ("color"
       (jetpacs-design-lab--text-field label value domain item field
                                       :index index
                                       :hint "#RRGGBB or #AARRGGBB"))
      ("dimension"
       (jetpacs-design-lab--text-field label value domain item field
                                       :index index :keyboard "decimal"))
      ("number"
       (jetpacs-design-lab--text-field label value domain item field
                                       :index index :keyboard "decimal"
                                       :hint "-10000 to 10000"))
      (_
       (jetpacs-design-lab--dropdown-field
        label value
        (jetpacs-design-lab--kind-options
         kind draft expected (and (equal domain "token") item))
        domain item field index)))))

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
      (let* ((profiles (jetpacs-design-profiles))
             (ids (mapcar (lambda (profile) (plist-get profile :id)) profiles)))
        (list
         (jetpacs-dropdown
          "jpdesign-profile"
          (mapcar (lambda (profile)
                    (jetpacs-enum-option (plist-get profile :label)
                                         (plist-get profile :id)))
                  profiles)
          :label "Profile"
          :value (car (member jetpacs-design-lab--selected-profile-id ids))
          :hint "Choose a profile"
          :on-change (jetpacs-design-lab--action "jpdesign.profile")))))
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
  "Build one folded editor per typography role in the draft."
  (mapcar
   (lambda (entry)
     (let ((id (car entry)) (value (cdr entry)))
       (jetpacs-design-lab--disclosure
        "typography" id (jetpacs-design-lab--typography-caption value)
        (list
         (jetpacs-design-lab--dropdown-field
          "family" (plist-get value :family) jetpacs-design-font-families
          "typography" id "family")
         (jetpacs-design-lab--text-field
          "size" (plist-get value :size) "typography" id "size"
          :keyboard "decimal")
         (jetpacs-design-lab--dropdown-field
          "weight" (plist-get value :weight)
          (mapcar #'number-to-string jetpacs-design-font-weights)
          "typography" id "weight")
         (jetpacs-design-lab--text-field
          "line-height" (plist-get value :line-height)
          "typography" id "line-height" :keyboard "decimal")
         (jetpacs-design-lab--text-field
          "letter-spacing" (plist-get value :letter-spacing)
          "typography" id "letter-spacing" :keyboard "decimal")))))
   (plist-get (jetpacs-design-lab--ensure-draft) :typography)))

(defun jetpacs-design-lab--tokens-content ()
  "Build one folded editor per design token in the draft."
  (let ((draft (jetpacs-design-lab--ensure-draft)))
    (mapcar
     (lambda (entry)
       (jetpacs-design-lab--disclosure
        "token" (car entry) (jetpacs-design-lab--token-caption (cdr entry))
        (list
         (jetpacs-text (format "Kind: %s" (plist-get (cdr entry) :kind))
                       :style "caption")
         (jetpacs-design-lab--value-field
          draft "value" (cdr entry) "token" (car entry) "value"))))
     (plist-get draft :tokens))))

(defun jetpacs-design-lab--property-fields
    (draft style-id properties &optional rule-index)
  "Build leaf editors for STYLE-ID PROPERTIES in DRAFT at optional RULE-INDEX."
  (cl-loop for (key value) on properties by #'cddr
           for name = (substring (symbol-name key) 1)
           collect
           (jetpacs-design-lab--value-field
            draft name value "style" style-id name rule-index
            (cdr (assoc name jetpacs-design-property-kinds)))))

(defun jetpacs-design-lab--styles-content ()
  "Build one folded editor per style, with its base properties and rules."
  (let ((draft (jetpacs-design-lab--ensure-draft)))
    (mapcar
     (lambda (entry)
       (let* ((id (car entry))
              (style (cdr entry))
              (children
               (append
                (list (jetpacs-text "Base properties" :style "label")
                      (jetpacs-design-lab--motion-field
                       draft id (plist-get style :motion)))
                (jetpacs-design-lab--property-fields
                 draft id (plist-get style :properties)))))
         (cl-loop for rule across (plist-get style :rules)
                  for index from 0
                  do (setq children
                           (append
                            children
                            (list
                             (jetpacs-text (format "Rule %d" index)
                                           :style "label")
                             (jetpacs-design-lab--dropdown-field
                              "state" (plist-get rule :state)
                              jetpacs-design-states "style" id "state" index)
                             (jetpacs-design-lab--motion-field
                              draft id (plist-get rule :motion) index))
                            (jetpacs-design-lab--property-fields
                             draft id (plist-get rule :properties) index))))
         (jetpacs-design-lab--disclosure
          "style" id (jetpacs-design-lab--style-caption style) children)))
     (plist-get draft :styles))))

(defun jetpacs-design-lab--motions-content ()
  "Build one folded editor per motion: its duration and easing."
  (mapcar
   (lambda (entry)
     (let ((id (car entry)) (motion (cdr entry)))
       (jetpacs-design-lab--disclosure
        "motion" id (jetpacs-design-lab--motion-caption motion)
        (list
         (jetpacs-design-lab--text-field
          "duration" (plist-get motion :duration_ms) "motion" id "duration"
          :keyboard "number")
         (jetpacs-design-lab--dropdown-field
          "easing" (plist-get motion :easing) jetpacs-design-easings
          "motion" id "easing")))))
   (plist-get (jetpacs-design-lab--ensure-draft) :motions)))

(defun jetpacs-design-lab--bindings-content ()
  "Build one folded editor per component binding: its ordered styles."
  (mapcar
   (lambda (entry)
     (jetpacs-design-lab--disclosure
      "binding" (car entry) (jetpacs-design-lab--binding-caption (cdr entry))
      (list
       (jetpacs-design-lab--text-field
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
          ;; The status slot is always one node, so the disclosures below
          ;; keep their render paths, and with them their open state, when
          ;; an edit is rejected and the message appears.
          (list
           (if jetpacs-design-lab--draft-error
               (jetpacs-component-panel
                "LAST EDIT REJECTED"
                (list (jetpacs-text jetpacs-design-lab--draft-error
                                    :color "error")))
             (jetpacs-text
              (format "Editing %s · draft valid"
                      (plist-get (jetpacs-design-lab--ensure-draft) :label))
              :style "caption")))
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
    "jpdesign.edit.boolean" "jpdesign.source" "jpdesign.reset"
    "jpdesign.save-entry"
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
     :args '((:name value :type "enum" :required t))
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
     "jpdesign.edit.boolean" #'jetpacs-design-lab--on-boolean-edit
     :args '((:name digest :type "text" :required t)
             (:name domain :type "enum" :required t)
             (:name item :type "text" :required t)
             (:name field :type "text" :required t)
             (:name index :type "number")
             (:name value :type "bool" :required t))
     :doc "Toggle one digest-addressed boolean design profile leaf")
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
