;;; jetpacs-design-profiles.el --- named bounded design profiles -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Emacs-owned profile registry for `jetpacs.design'.  Presets are registered
;; by applets and remain immutable.  User snapshots and the active profile ID
;; are ordinary Customize values; Android receives only a compiled design
;; scope and never becomes a second profile authority.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-design)

(defgroup jetpacs-design nil
  "Bounded renderer design profiles."
  :group 'jetpacs)

(defconst jetpacs-design-profile-version 1
  "Current closed profile representation version.")

(defconst jetpacs-design-max-user-profiles 16
  "Maximum number of persisted user design profiles.")

(defconst jetpacs-design--profile-keys
  '(:version :id :label :tokens :typography :styles :motions
    :component-styles :theme-roles)
  "Closed member set of a design profile plist.")

(defconst jetpacs-design--profile-required-keys
  '(:version :id :label :tokens :typography :styles :motions
    :component-styles)
  "Profile members every snapshot must carry.
`:theme-roles' is optional so snapshots saved before it existed stay valid.")

(defcustom jetpacs-design-user-profiles nil
  "Canonical snapshots created from registered design presets."
  :type 'sexp
  :group 'jetpacs-design)

(defcustom jetpacs-design-active-profile-id nil
  "Jetpacs-wide profile ID used by explicitly opted-in app content.
Nil lets each applet use its registered default profile."
  :type '(choice (const :tag "Applet default" nil) string)
  :group 'jetpacs-design)

(defvar jetpacs-design-profile-changed-hook nil
  "Hook run after active or persisted design profile state changes.")

(defvar jetpacs-design--presets (make-hash-table :test #'equal)
  "Immutable profiles registered by their owning packages.")

(defun jetpacs-design--closed-plist-p (value keys)
  "Return non-nil when VALUE is an even plist using only KEYS."
  (and (proper-list-p value)
       (zerop (% (length value) 2))
       (cl-loop for (key _value) on value by #'cddr
                always (and (keywordp key) (memq key keys)))))

(defun jetpacs-design--profile-identifier (value what)
  "Return identifier VALUE after checking it for WHAT."
  (jetpacs-check-identifier value what)
  value)

(defun jetpacs-design--typography-style (entry)
  "Return generated style pair for typography alist ENTRY."
  (let* ((role (jetpacs-design--profile-identifier
                (car entry) "typography role"))
         (value (cdr entry))
         (keys '(:family :size :weight :line-height :letter-spacing)))
    (unless (and (jetpacs-design--closed-plist-p value keys)
                 (cl-every (lambda (key) (plist-member value key)) keys))
      (error "jetpacs-design: typography role %s is not a closed definition"
             role))
    (cons
     (concat "typography." role)
     (jetpacs-design-style
      `(("font_family"
         . ,(jetpacs-design-font-family (plist-get value :family)))
        ("font_size" . ,(jetpacs-design-dimension (plist-get value :size)))
        ("font_weight"
         . ,(jetpacs-design-font-weight (plist-get value :weight)))
        ("line_height"
         . ,(jetpacs-design-dimension (plist-get value :line-height)))
        ("letter_spacing"
         . ,(jetpacs-design-dimension
             (plist-get value :letter-spacing))))))))

(defun jetpacs-design--profile-styles (profile)
  "Return PROFILE's styles with generated typography styles included."
  (let ((styles (copy-tree (plist-get profile :styles))))
    (dolist (entry (plist-get profile :typography))
      (let ((generated (jetpacs-design--typography-style entry)))
        (when (assoc (car generated) styles)
          (error "jetpacs-design: style %s collides with typography"
                 (car generated)))
        (push generated styles)))
    styles))

(defun jetpacs-design--profile-bindings (profile style-names)
  "Compile PROFILE component bindings after resolving STYLE-NAMES."
  (let (bindings)
    (dolist (entry (plist-get profile :component-styles))
      (unless (and (consp entry) (stringp (car entry))
                   (proper-list-p (cdr entry)))
        (error "jetpacs-design: component styles must be an alist"))
      (dolist (style (cdr entry))
        (unless (member style style-names)
          (error "jetpacs-design: unresolved component style %s" style)))
      (push (jetpacs-design-component-style (car entry) (cdr entry)) bindings))
    (nreverse bindings)))

(defun jetpacs-design-validate-profile (profile)
  "Validate and return a canonical deep copy of PROFILE.
PROFILE is inert data with a closed versioned schema."
  (unless (jetpacs-design--closed-plist-p profile jetpacs-design--profile-keys)
    (error "jetpacs-design: profile must use the closed profile schema"))
  (dolist (key jetpacs-design--profile-required-keys)
    (unless (plist-member profile key)
      (error "jetpacs-design: profile is missing %S" key)))
  (unless (= (plist-get profile :version) jetpacs-design-profile-version)
    (error "jetpacs-design: unsupported profile version %S"
           (plist-get profile :version)))
  (jetpacs-design--profile-identifier (plist-get profile :id) "profile ID")
  (unless (and (stringp (plist-get profile :label))
               (<= 1 (length (plist-get profile :label)) 128))
    (error "jetpacs-design: profile label must contain 1 to 128 characters"))
  (dolist (key '(:tokens :typography :styles :motions :component-styles
                 :theme-roles))
    (unless (proper-list-p (plist-get profile key))
      (error "jetpacs-design: %S must be a proper alist" key)))
  (when (> (length (plist-get profile :typography)) 32)
    (error "jetpacs-design: a profile may define at most 32 typography roles"))
  (let* ((styles (jetpacs-design--profile-styles profile))
         (style-names (mapcar #'car styles))
         (bindings (jetpacs-design--profile-bindings profile style-names))
         (empty-scope
          (jetpacs-design-scope
           (plist-get profile :tokens)
           styles nil
           :motions (plist-get profile :motions)
           :component-styles bindings
           :theme-roles (plist-get profile :theme-roles))))
    (when (> (jetpacs-node-wire-bytes empty-scope) (* 256 1024))
      (error "jetpacs-design: compiled profile exceeds 256 KiB")))
  (copy-tree profile))

(cl-defun jetpacs-design-make-profile
    (id label &key tokens typography styles motions component-styles
        theme-roles)
  "Build a validated profile named ID and LABEL.
TOKENS, TYPOGRAPHY, STYLES, MOTIONS, COMPONENT-STYLES, and THEME-ROLES are
inert alists.  THEME-ROLES maps EBP role names to color values so the
receiver's chrome follows the profile; leave it empty to keep the Emacs theme."
  (jetpacs-design-validate-profile
   (append
    (list :version jetpacs-design-profile-version
          :id id :label label
          :tokens tokens :typography typography :styles styles
          :motions motions :component-styles component-styles)
    (and theme-roles (list :theme-roles theme-roles)))))

(defun jetpacs-design-register-profile (profile)
  "Register immutable preset PROFILE and return its ID.
Repeating the exact registration is idempotent; changing a registered ID is
an error so an app update cannot silently rewrite a user's starting point."
  (let* ((checked (jetpacs-design-validate-profile profile))
         (id (plist-get checked :id))
         (existing (gethash id jetpacs-design--presets)))
    (when (and existing (not (equal existing checked)))
      (error "jetpacs-design: preset %s is already registered" id))
    (when (cl-find id jetpacs-design-user-profiles
                   :key (lambda (item) (plist-get item :id)) :test #'equal)
      (error "jetpacs-design: preset %s collides with a user profile" id))
    (puthash id checked jetpacs-design--presets)
    id))

(defun jetpacs-design-unregister-profile (id)
  "Unregister preset ID without altering persisted user snapshots."
  (remhash id jetpacs-design--presets))

(defun jetpacs-design-profile-get (id)
  "Return a deep copy of named profile ID, or nil."
  (when id
    (copy-tree
     (or (cl-find id jetpacs-design-user-profiles
                  :key (lambda (item) (plist-get item :id)) :test #'equal)
         (gethash id jetpacs-design--presets)))))

(defun jetpacs-design-profiles ()
  "Return every registered and user profile sorted by ID."
  (let ((profiles (copy-tree jetpacs-design-user-profiles)))
    (maphash (lambda (_id profile) (push (copy-tree profile) profiles))
             jetpacs-design--presets)
    (sort profiles
          (lambda (left right)
            (string< (plist-get left :id) (plist-get right :id))))))

(defun jetpacs-design--save-custom (symbol value)
  "Persist Customize SYMBOL as VALUE."
  (customize-save-variable symbol value))

(defun jetpacs-design-save-profile-as (profile &optional persist)
  "Add new user PROFILE snapshot and return its ID.
When PERSIST is non-nil, save the complete canonical profile with Customize."
  (let* ((checked (jetpacs-design-validate-profile profile))
         (id (plist-get checked :id)))
    (when (or (gethash id jetpacs-design--presets)
              (jetpacs-design-profile-get id))
      (error "jetpacs-design: profile %s already exists" id))
    (when (>= (length jetpacs-design-user-profiles)
              jetpacs-design-max-user-profiles)
      (error "jetpacs-design: at most %d user profiles may be saved"
             jetpacs-design-max-user-profiles))
    (let ((next (sort (cons checked (copy-tree jetpacs-design-user-profiles))
                      (lambda (left right)
                        (string< (plist-get left :id)
                                 (plist-get right :id))))))
      (if persist
          (jetpacs-design--save-custom 'jetpacs-design-user-profiles next)
        (setq jetpacs-design-user-profiles next)))
    (run-hooks 'jetpacs-design-profile-changed-hook)
    id))

(defun jetpacs-design-update-user-profile (profile &optional persist)
  "Replace existing user PROFILE snapshot after validation.
When PERSIST is non-nil, save the replacement with Customize."
  (let* ((checked (jetpacs-design-validate-profile profile))
         (id (plist-get checked :id)))
    (unless (cl-find id jetpacs-design-user-profiles
                     :key (lambda (item) (plist-get item :id)) :test #'equal)
      (error "jetpacs-design: user profile %s does not exist" id))
    (let ((next (mapcar (lambda (item)
                          (if (equal id (plist-get item :id)) checked item))
                        jetpacs-design-user-profiles)))
      (if persist
          (jetpacs-design--save-custom 'jetpacs-design-user-profiles next)
        (setq jetpacs-design-user-profiles next)))
    (run-hooks 'jetpacs-design-profile-changed-hook)
    id))

(defun jetpacs-design-delete-user-profile (id &optional persist)
  "Delete user profile ID; immutable presets are never affected.
When PERSIST is non-nil, save both profile and active-ID changes."
  (unless (cl-find id jetpacs-design-user-profiles
                   :key (lambda (item) (plist-get item :id)) :test #'equal)
    (error "jetpacs-design: user profile %s does not exist" id))
  (let ((next (cl-remove id jetpacs-design-user-profiles
                         :key (lambda (item) (plist-get item :id))
                         :test #'equal)))
    (if persist
        (jetpacs-design--save-custom 'jetpacs-design-user-profiles next)
      (setq jetpacs-design-user-profiles next)))
  (when (equal id jetpacs-design-active-profile-id)
    (if persist
        (jetpacs-design--save-custom 'jetpacs-design-active-profile-id nil)
      (setq jetpacs-design-active-profile-id nil)))
  (run-hooks 'jetpacs-design-profile-changed-hook))

(defun jetpacs-design-set-active-profile (id &optional persist)
  "Select profile ID, or nil for applet defaults.
When PERSIST is non-nil, save the selection with Customize."
  (when (and id (not (jetpacs-design-profile-get id)))
    (error "jetpacs-design: unknown profile %s" id))
  (if persist
      (jetpacs-design--save-custom 'jetpacs-design-active-profile-id id)
    (setq jetpacs-design-active-profile-id id))
  (run-hooks 'jetpacs-design-profile-changed-hook)
  id)

(defun jetpacs-design-active-profile (&optional fallback-id)
  "Return active profile, falling back to FALLBACK-ID when unavailable."
  (or (jetpacs-design-profile-get jetpacs-design-active-profile-id)
      (jetpacs-design-profile-get fallback-id)))

(defun jetpacs-design-profile-scope (profile children)
  "Compile PROFILE around ordinary EBP node list CHILDREN."
  (let* ((checked (jetpacs-design-validate-profile profile))
         (styles (jetpacs-design--profile-styles checked))
         (bindings
          (jetpacs-design--profile-bindings checked (mapcar #'car styles))))
    (jetpacs-design-scope
     (plist-get checked :tokens) styles children
     :motions (plist-get checked :motions)
     :component-styles bindings
     :theme-roles (plist-get checked :theme-roles))))

(defun jetpacs-design-wrap-active-profile (children &optional fallback-id)
  "Wrap CHILDREN with the active profile or registered FALLBACK-ID.
Return CHILDREN unchanged when neither profile exists."
  (if-let* ((profile (jetpacs-design-active-profile fallback-id)))
      (list (jetpacs-design-profile-scope profile children))
    children))

(provide 'jetpacs-design-profiles)
;;; jetpacs-design-profiles.el ends here
