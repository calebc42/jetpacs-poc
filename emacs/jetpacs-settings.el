;;; jetpacs-settings.el --- Schema-driven settings from defcustom metadata -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;; Renders an allowlisted set of defcustom variables as companion widgets
;; derived from their `custom-type' schemas, and applies edits through
;; Customize: candidate values are validated with the type's widget
;; `:match', set via `customize-set-variable' (so `:set' setters run),
;; and persisted via `customize-save-variable'.
;;
;; The registry is the security boundary: `settings.set'/`settings.reset'
;; only touch symbols present in `jetpacs-settings-registry', never
;; arbitrary names off the wire.  Exposing a new setting is one registry
;; entry.  The rendering/apply machinery itself is public and
;; gate-agnostic — jetpacs-customize.el reuses it under its own
;; `customize.*' actions with a `custom-variable-p' gate; the registry
;; rule binds `settings.*' only.
;;
;; Widget mapping by type: boolean -> switch (the switch publishes
;; state.changed rather than dispatching an action, so per-id handlers
;; register with the section), choice-of-consts -> single-select enum
;; list, string/file/directory -> text input, integer/number -> numeric
;; text input, anything else -> a raw elisp expression read with `read'.
;;
;; (Behavior reference: POC 1's jetpacs-settings.el.  Dropped here: the
;; jetpacs-config custom-file management — POC 3 warns once instead —
;; and the settings-hub split bodies, which wait on the app-identity
;; design pass recorded in PLAN-poc1-parity.)

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'wid-edit)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-dialog-slot)

(defconst jetpacs-settings-surface "jetpacs.settings"
  "The settings root surface (owner and surface name).")

(defvar jetpacs-settings-registry nil
  "Alist of (TITLE . ENTRIES); each entry (SYMBOL . PLIST).
PLIST keys: :label, :after-set, :render (a nullary node builder that
replaces the schema-derived control), :library (a feature that defines
SYMBOL, required before the row renders), :advanced (non-nil: a
developer or internal option, shown in the Advanced section at the end
instead of its own), :standalone-only (non-nil: hidden in the bundled
app, where the option belongs to the app's own settings or to an Emacs
the user does not have), and :plain (the bundled app's plain-language
copy, see `jetpacs-settings-item').  Presence here is what authorizes
`settings.set'/`settings.reset' for a symbol.")

(defvar jetpacs-settings-section-order
  '("Org Workflow" "Org Agenda" "Org Editing & Display" "Reader"
    "Calendar & Location" "User Defaults" "Appearance" "Files" "Clipboard")
  "Titles of the settings sections, in display order.
Sections not named here follow in registration order, then the
satellite links, then the Advanced section.")

(defvar jetpacs-settings-hub-entries nil
  "Alist of (KEY . PLIST) for hub-level settings categories.
PLIST keys: :order, :icon, :title, :subtitle, :on-tap, and :plain (a
plist whose :subtitle the bundled app shows instead).")

(defun jetpacs-settings-register-hub-entry (key &rest plist)
  "Register a settings hub category.
KEY is a string identifier.  PLIST contains :order (integer),
:icon (string), :title (string), :subtitle (string), and
:on-tap (a jetpacs-action)."
  (setf (alist-get key jetpacs-settings-hub-entries nil nil #'equal) plist)
  (setq jetpacs-settings-hub-entries
        (cl-sort jetpacs-settings-hub-entries #'<
                 :key (lambda (x) (or (plist-get (cdr x) :order) 50))))
  (jetpacs--claim "settings-hub" key))

(defun jetpacs-settings-remove-hub-entry (key)
  "Remove a hub entry."
  (setq jetpacs-settings-hub-entries
        (cl-remove key jetpacs-settings-hub-entries :key #'car :test #'equal))
  (jetpacs--unclaim "settings-hub" key))

;;;; Registry

(defun jetpacs-settings-register-section (title entries)
  "Register (or replace) settings section TITLE with ENTRIES.
Also registers the state.changed handlers for its boolean switches —
a queued toggle can replay before the screen ever renders, so handlers
belong to registration, not to rendering."
  (setf (alist-get title jetpacs-settings-registry nil nil #'equal) entries)
  (dolist (entry entries)
    (unless (plist-get (cdr entry) :render)
      (jetpacs-settings-watch-toggle
       (car entry)
       (concat "setting/" (symbol-name (car entry)))
       (plist-get (cdr entry) :after-set))))
  (jetpacs--claim "settings-section" title))

(defun jetpacs-settings-remove-section (title)
  "Remove settings section TITLE and the switch handlers it installed."
  (dolist (entry (alist-get title jetpacs-settings-registry nil nil #'equal))
    (unless (plist-get (cdr entry) :render)
      (jetpacs-on-state-change-remove
       (concat "setting/" (symbol-name (car entry))))))
  (setf (alist-get title jetpacs-settings-registry nil 'remove #'equal) nil)
  (jetpacs--unclaim "settings-section" title))

(defun jetpacs-settings--entry (sym)
  "SYM's registry entry (SYMBOL . PLIST), or nil when not exposed."
  (cl-loop for (_title . entries) in jetpacs-settings-registry
           thereis (assq sym entries)))

;;;; Applying values through Customize

(defvar jetpacs-settings--custom-file-warned nil)

(defun jetpacs-settings-save-variable (symbol value)
  "Set SYMBOL to VALUE via Customize and persist it.
Without a `custom-file', `customize-save-variable' writes the init
file; warn once so the owner knows where edits are landing."
  (unless (or custom-file jetpacs-settings--custom-file-warned)
    (setq jetpacs-settings--custom-file-warned t)
    (message "jetpacs-settings: no custom-file set; saving to init file"))
  (customize-save-variable symbol value))

(defun jetpacs-settings--type (sym)
  "SYM's Customize type, once what Customize loads for SYM is loaded.
Built-in variables such as `user-full-name' carry a type only after
cus-start runs; without one they fell to the raw Lisp editor, which
showed a name in quotes."
  (custom-load-symbol sym)
  (jetpacs-settings--complete-cus-start)
  (or (get sym 'custom-type) 'sexp))

(defun jetpacs-settings--complete-cus-start ()
  "Load the half of cus-start that types built-in variables, if missing.
loadup preloads only cus-start's first half and leaves the feature
unprovided while dumping, so that a later `require' loads the rest.
Emacs for Android dumps at its first start, where the feature ends up
provided after the first half alone (seen in the bundled app's engine,
Emacs 31.1), so `require' never loads the half that types
`user-full-name' and every other built-in option.  Loading the file
again is what that design expects; it runs at most once."
  (when (and (featurep 'cus-start) (not (get 'user-full-name 'custom-type)))
    (ignore-errors (load "cus-start" nil t))))

(defun jetpacs-settings--wire-key (value)
  "VALUE as the wire value of its option: a string as is, else printed.
Option labels are presentation only, so the bundled app can relabel
them and a decode never depends on the words on screen."
  (if (stringp value) value (prin1-to-string value)))

(defun jetpacs-settings--const-option (alt)
  "The (LABEL . VALUE) of a const choice arm ALT, or nil."
  (pcase alt
    (`(const . ,rest)
     (let* ((plist (and (keywordp (car-safe rest)) rest))
            (value (if plist (car (last rest)) (car rest)))
            (tag (and plist (plist-get plist :tag))))
       (cons (or tag (prin1-to-string value)) value)))
    (_ nil)))

(defun jetpacs-settings--choice-options (type)
  "The (LABEL . VALUE) list of a choice-of-consts TYPE, or nil."
  (when (eq (car-safe type) 'choice)
    (let ((opts (mapcar #'jetpacs-settings--const-option (cdr type))))
      (and (not (memq nil opts)) opts))))

(defun jetpacs-settings--open-choice (type)
  "The (OPTIONS . ARM) of a choice TYPE with a free-form arm, or nil.
OPTIONS are the (LABEL . VALUE) of its const arms; ARM is its first
string or number arm, the field for any other value (for example
`org-agenda-span': Day, Week ... or a number of days).  Further
non-const arms, such as `calendar-latitude''s degrees-and-minutes
vector, have no field of their own; such a value still shows."
  (when (eq (car-safe type) 'choice)
    (let ((consts (delq nil (mapcar #'jetpacs-settings--const-option
                                    (cdr type))))
          (arm (cl-find-if (lambda (arm)
                             (and (not (jetpacs-settings--const-option arm))
                                  (memq (jetpacs-settings--kind arm)
                                        '(string number))))
                           (cdr type))))
      (and consts arm (cons consts arm)))))

(defun jetpacs-settings--kind (type)
  "One of boolean/choice/open-choice/string/number/sexp for schema TYPE."
  (pcase (car-safe (if (consp type) type (list type)))
    ('boolean 'boolean)
    ((or 'string 'file 'directory 'regexp) 'string)
    ((or 'integer 'number 'natnum 'float) 'number)
    ('choice (cond ((jetpacs-settings--choice-options type) 'choice)
                   ((jetpacs-settings--open-choice type) 'open-choice)
                   (t 'sexp)))
    (_ 'sexp)))

(defun jetpacs-settings--arm-tag (arm)
  "The :tag of choice ARM (a type), or nil."
  (and (consp arm) (plist-get (cdr arm) :tag)))

(defun jetpacs-settings--valid-p (sym value)
  "Whether VALUE matches SYM's custom-type widget schema."
  (condition-case nil
      (widget-apply (widget-convert (jetpacs-settings--type sym))
                    :match value)
    (error nil)))

(defun jetpacs-settings-apply (sym value &optional after-set)
  "Validate VALUE against SYM's schema, set and persist it; t on success."
  (if (not (jetpacs-settings--valid-p sym value))
      (progn
        (jetpacs-toast (format "Invalid value for %s" sym))
        nil)
    (jetpacs-settings-save-variable sym value)
    (when after-set (funcall after-set sym value))
    t))

(defun jetpacs-settings--decode (sym wire)
  "Decode WIRE (a state/submit payload) into SYM's value domain.
Booleans arrive as t/:json-false; numbers and sexps as strings to
`read'; a choice arrives as its option's wire key
\(`jetpacs-settings--wire-key'), or as its label from before keys;
the free arm of an open choice as text in that arm's domain.  Returns
\(VALUE) on success, nil on a parse failure — a nil VALUE is legal."
  (let* ((type (jetpacs-settings--type sym))
         (kind (jetpacs-settings--kind type))
         (wire (if (and (memq kind '(choice open-choice))
                        (vectorp wire) (> (length wire) 0))
                   (aref wire 0)
                 wire)))
    (pcase kind
      ('boolean (list (eq wire t)))
      ((or 'choice 'open-choice)
       (let* ((open (and (eq kind 'open-choice)
                         (jetpacs-settings--open-choice type)))
              (opts (if open (car open)
                      (jetpacs-settings--choice-options type)))
              (hit (or (cl-find wire opts :test #'equal
                                :key (lambda (o)
                                       (jetpacs-settings--wire-key (cdr o))))
                       (assoc wire opts))))
         (cond (hit (list (cdr hit)))
               (open (jetpacs-settings--decode-kind
                      (jetpacs-settings--kind (cdr open)) wire)))))
      (_ (jetpacs-settings--decode-kind kind wire)))))

(defun jetpacs-settings--decode-kind (kind wire)
  "Decode WIRE for a string, number or sexp KIND, as for `--decode'."
  (pcase kind
    ('string (and (stringp wire) (list wire)))
    ('number (let ((n (and (stringp wire)
                           (ignore-errors (read wire)))))
               (and (numberp n) (list n))))
    (_ (and (stringp wire)
            (condition-case nil (list (read wire))
              (error nil))))))

(defun jetpacs-settings-apply-wire (sym wire &optional after-set)
  "Apply a wire payload WIRE to SYM; non-nil on success."
  (let ((decoded (jetpacs-settings--decode sym wire)))
    (if (not decoded)
        (progn (jetpacs-toast (format "Cannot parse value for %s" sym)) nil)
      (jetpacs-settings-apply sym (car decoded) after-set))))

(defun jetpacs-settings--standard-value (sym)
  (eval (car (get sym 'standard-value)) t))

(defun jetpacs-settings-modified-p (sym)
  "Whether SYM's current global value differs from its standard default.
Safe on any symbol: unbound means unmodified, and a standard-value form
that fails to evaluate counts as unmodified rather than erroring (the
customize browser calls this across arbitrary defcustoms)."
  (and (boundp sym)
       (get sym 'standard-value)
       (not (equal (default-value sym)
                   (condition-case nil (jetpacs-settings--standard-value sym)
                     (error (default-value sym)))))))

(defun jetpacs-settings-reset (sym &optional after-set label)
  "Reset SYM to its defcustom standard value; non-nil on success.
LABEL names the setting in the confirmation; the symbol otherwise."
  (if (not (get sym 'standard-value))
      (progn (jetpacs-toast "Cannot reset this setting") nil)
    (when (jetpacs-settings-apply
           sym (jetpacs-settings--standard-value sym) after-set)
      (jetpacs-toast (format "%s reset to default" (or label sym)))
      t)))

;;;; Rendering

(defun jetpacs-settings--doc-line (sym)
  "First line of SYM's docstring, or nil.
A parenthesised spec reference such as \"(SPEC 18.4)\" is a note for
the developer reading the source, not for the person reading the
screen, so it is dropped."
  (let ((doc (documentation-property sym 'variable-documentation)))
    (and doc
         (let ((line (car (split-string (substitute-command-keys doc)
                                        "\n" t))))
           (and line
                (string-trim
                 (replace-regexp-in-string
                  "[ \t]*(\\(?:SPEC\\|§\\)[^)]*)" "" line)))))))

(defun jetpacs-settings--enum (wid-id opts value set &optional exclude open)
  "An enum list under WID-ID over OPTS, a list of (LABEL . VALUE).
VALUE is selected; options whose value is in EXCLUDE are left out
unless selected.  A VALUE outside OPTS (set from Lisp) is shown, printed,
as the selection, unless OPEN says a free-form field shows it instead.
Option values travel as wire keys, so the labels are free to change."
  (let* ((opts (cl-remove-if (lambda (o) (and (member (cdr o) exclude)
                                              (not (equal (cdr o) value))))
                             opts))
         (hit (rassoc value opts)))
    (unless (or hit open)
      (setq opts (append opts (list (cons (prin1-to-string value) value)))))
    (jetpacs-enum-list
     wid-id
     (mapcar (lambda (o)
               (jetpacs-enum-option (car o) (jetpacs-settings--wire-key (cdr o))))
             opts)
     :value (and (or hit (not open)) (jetpacs-settings--wire-key value))
     :on-change set)))

(defun jetpacs-settings--relabel (opts choices)
  "OPTS, a list of (LABEL . VALUE), with the labels CHOICES gives.
CHOICES is an alist (VALUE . LABEL); options it does not name keep
their own label."
  (mapcar (lambda (o)
            (let ((plain (assoc (cdr o) choices)))
              (if plain (cons (cdr plain) (cdr o)) o)))
          opts))

(cl-defun jetpacs-settings-item (sym &key label (id-prefix "setting/")
                                     (set-action "settings.set")
                                     (reset-action "settings.reset")
                                     library plain exclude)
  "Widget column rendering SYM's control from its `custom-type' schema.
LABEL defaults to the symbol name.  ID-PREFIX keys the control's widget
id; a switch under it publishes state.changed, so pair a non-default
prefix with `jetpacs-settings-watch-toggle'.  SET-ACTION and
RESET-ACTION name the wire actions, each carrying the symbol name under
`:name' — the settings screen uses the registry-gated `settings.*', the
customize browser the `custom-variable-p'-gated `customize.*'.

LIBRARY is a feature that defines SYM, required before rendering.
EXCLUDE lists choice values not offered unless current.  PLAIN is the
bundled app's plain-language copy, used there only, since its user has
no Emacs of their own and docstrings speak to Emacs users: :label,
:summary (in place of the docstring's first line), :hint (an empty
field's placeholder) and :choices, an alist (VALUE . LABEL) that
relabels a choice's options or offers that fixed set of values for
any other type."
  (when library (require library nil t))
  (if (not (boundp sym))
      (jetpacs-text (format "%s is not available" (or label sym))
                    :style "caption")
    (let* ((plain (and (jetpacs-bundled-p) plain))
           (name (symbol-name sym))
           (label (or (plist-get plain :label) label name))
           (doc (or (plist-get plain :summary)
                    (jetpacs-settings--doc-line sym)))
           (hint (plist-get plain :hint))
           (choices (plist-get plain :choices))
           (value (default-value sym))
           (type (jetpacs-settings--type sym))
           (kind (jetpacs-settings--kind type))
           (wid-id (concat id-prefix name))
           (set (jetpacs-action set-action :args `(:name ,name)))
           (reset (and (jetpacs-settings-modified-p sym)
                       (jetpacs-icon-button
                        "history"
                        (jetpacs-action reset-action :args `(:name ,name))
                        :content-description
                        (format "Reset %s to default" label))))
           (control
            (pcase kind
              ('boolean
               (jetpacs-switch wid-id :checked (and value t) :label label))
              ('choice
               (jetpacs-settings--enum
                wid-id (jetpacs-settings--relabel
                        (jetpacs-settings--choice-options type) choices)
                value set exclude))
              ('open-choice
               ;; The const arms as options, and the one free-form arm
               ;; (a number of days, a drawer name) as a field below,
               ;; holding the value when it is none of the options.
               (let* ((open (jetpacs-settings--open-choice type))
                      (opts (jetpacs-settings--relabel (car open) choices))
                      (other (not (rassoc value (car open)))))
                 (jetpacs-column
                  (jetpacs-settings--enum wid-id opts value set exclude t)
                  (jetpacs-text-input
                   (concat wid-id "/other")
                   :value (and other value (format "%s" value))
                   :hint (or hint (jetpacs-settings--arm-tag (cdr open))
                             "Other")
                   :single-line t :on-submit set))))
              (_
               (if choices
                   ;; A fixed set of values for a free type, e.g. the
                   ;; days of the week for an integer.
                   (jetpacs-settings--enum
                    wid-id (mapcar (lambda (c) (cons (cdr c) (car c))) choices)
                    value set)
                 ;; The label row above names the field; a second label
                 ;; on the field itself only repeated it.
                 (pcase kind
                   ('string
                    (jetpacs-text-input wid-id
                                        :value (and (stringp value) value)
                                        :hint hint :single-line t
                                        :on-submit set))
                   ('number
                    (jetpacs-text-input wid-id
                                        :value (and (numberp value)
                                                    (number-to-string value))
                                        :hint hint :single-line t
                                        :on-submit set))
                   (_
                    (jetpacs-text-input wid-id :value (prin1-to-string value)
                                        :single-line t
                                        :hint (or hint "Elisp expression")
                                        :on-submit set))))))))
      (apply #'jetpacs-column
             (delq nil
                   (list
                    ;; Booleans carry their label inside the switch row;
                    ;; everything else gets a plain label row.  The
                    ;; weighted wrap keeps the reset button on-screen.
                    (jetpacs-row
                     (jetpacs-with-attrs
                      (if (eq kind 'boolean)
                          control
                        (jetpacs-text label :style "label"))
                      :weight 1)
                     reset)
                    (when doc (jetpacs-text doc :style "caption"))
                    (unless (eq kind 'boolean) control)))))))

(defun jetpacs-settings--item (entry)
  "Widget column for registry ENTRY (a :render row renders itself)."
  (let ((plist (cdr entry)))
    (if-let* ((render (plist-get plist :render)))
        (funcall render)
      (jetpacs-settings-item (car entry)
                             :label (plist-get plist :label)
                             :library (plist-get plist :library)
                             :plain (plist-get plist :plain)
                             :exclude (plist-get plist :exclude)))))

(defun jetpacs-settings--entry-label (entry)
  "The label registry ENTRY shows in this app."
  (let ((plist (cdr entry)))
    (or (and (jetpacs-bundled-p) (plist-get (plist-get plist :plain) :label))
        (plist-get plist :label))))

(defun jetpacs-settings--shown-p (entry)
  "Whether registry ENTRY renders in this app (see `:standalone-only')."
  (not (and (plist-get (cdr entry) :standalone-only) (jetpacs-bundled-p))))

(defvar jetpacs-settings-links nil
  "Ordered list of (ORDER BUILDER . OWNER) satellite entries.
BUILDER is a nullary node builder (usually a tappable card leading to
another screen), or one returning nil where its screen does not apply.
Apps register satellite screens here — the package browser, Customize —
instead of each claiming chrome of its own.")

(defvar jetpacs-settings--advanced-links nil
  "The builders among `jetpacs-settings-links' shown under Advanced.")

(defun jetpacs-settings-add-link (order builder &optional advanced)
  "Add BUILDER (a nullary node builder) to the settings screen at ORDER.
ADVANCED non-nil places it at the end of the Advanced section, with the
developer options.  Registrations made under `with-jetpacs-owner' are
attributed to that owner for the app-identity layer to filter once it
exists."
  (setq jetpacs-settings-links
        (sort (cons (cons order
                          (cons builder
                                (bound-and-true-p jetpacs-current-owner)))
                    jetpacs-settings-links)
              (lambda (a b) (< (car a) (car b)))))
  (setq jetpacs-settings--advanced-links
        (delq builder jetpacs-settings--advanced-links))
  (when advanced (push builder jetpacs-settings--advanced-links))
  (jetpacs--claim "settings-link" builder))

(defun jetpacs-settings-remove-link (builder)
  "Remove every settings satellite link registered with BUILDER.
Safe before registration and after prior removal.  Removing every
match makes an add-after-remove registration idempotent even when an
older caller accidentally registered the same builder more than once."
  (setq jetpacs-settings-links
        (cl-remove builder jetpacs-settings-links :key #'cadr))
  (setq jetpacs-settings--advanced-links
        (delq builder jetpacs-settings--advanced-links))
  (jetpacs--unclaim "settings-link" builder))

(jetpacs-define-claim-disposer "settings-section"
                               #'jetpacs-settings-remove-section)
(jetpacs-define-claim-disposer "settings-hub"
                               #'jetpacs-settings-remove-hub-entry)
(jetpacs-define-claim-disposer "settings-link"
                               #'jetpacs-settings-remove-link)

(defun jetpacs-settings--ordered-sections ()
  "The registry's sections in `jetpacs-settings-section-order'.
The registry holds the newest section first; the rest keep the order
they were registered in."
  (let ((rank (lambda (section)
                (or (cl-position (car section) jetpacs-settings-section-order
                                 :test #'equal)
                    (length jetpacs-settings-section-order)))))
    (cl-stable-sort (reverse jetpacs-settings-registry) #'< :key rank)))

(defun jetpacs-settings-sections ()
  "The settings body: the sections in order, the links, then Advanced.
Rows marked `:advanced', from any section, gather under one Advanced
header at the end, followed by the links registered as advanced; a
section left without rows shows no header, and rows marked
`:standalone-only' are left out of the bundled app."
  (let (body advanced)
    (dolist (section (jetpacs-settings--ordered-sections))
      (let* ((rows (cl-remove-if-not #'jetpacs-settings--shown-p (cdr section)))
             (dev (cl-remove-if-not (lambda (e) (plist-get (cdr e) :advanced))
                                    rows))
             (rows (cl-set-difference rows dev :test #'eq)))
        (setq advanced (append advanced dev))
        (when rows
          (setq body (append body
                             (cons (jetpacs-section-header (car section))
                                   (mapcar #'jetpacs-settings--item
                                           (cl-remove-if-not
                                            (lambda (e) (memq e rows))
                                            (cdr section)))))))))
    (let* ((builders (mapcar #'cadr jetpacs-settings-links))
           (dev-p (lambda (b) (memq b jetpacs-settings--advanced-links)))
           (dev-links (delq nil (mapcar #'funcall
                                        (cl-remove-if-not dev-p builders)))))
      (append body
              (delq nil (mapcar #'funcall (cl-remove-if dev-p builders)))
              (when (or advanced dev-links)
                (cons (jetpacs-section-header "Advanced")
                      (append (mapcar #'jetpacs-settings--item advanced)
                              dev-links)))))))

(defun jetpacs-settings--emacs-view (back)
  "The Emacs settings screen: all registered sections + satellites."
  (jetpacs-chrome-screen
   "Emacs Settings"
   (apply #'jetpacs-lazy-column
          (append
           (list (jetpacs-chrome-row
                  "Customize"
                  :subtitle "Browse and edit any Emacs option"
                  :icon "tune"
                  :on-tap (if (jetpacs-builtin-advertised-p "surface.open" :app)
                              (jetpacs-surface-open "app:jetpacs.customize")
                            (jetpacs-action "customize.show" :when-offline "drop"))
                  :key "emacs-settings-customize")
                 (jetpacs-chrome-row
                  "Packages"
                  :subtitle "Install and manage Emacs packages"
                  :icon "archive"
                  :on-tap (jetpacs-action "packages.show" :when-offline "drop")
                  :key "emacs-settings-packages"))
           (jetpacs-settings-sections)))
   :back back))

(defun jetpacs-settings--hub-cards ()
  "Render the dynamically registered hub entries as cards."
  (mapcar (lambda (entry)
            (let ((plist (cdr entry)))
              (jetpacs-chrome-row (plist-get plist :title)
                                  :subtitle
                                  (or (and (jetpacs-bundled-p)
                                           (plist-get (plist-get plist :plain)
                                                      :subtitle))
                                      (plist-get plist :subtitle))
                                  :icon (plist-get plist :icon)
                                  :on-tap (plist-get plist :on-tap)
                                  :key (concat "hub-entry-" (car entry)))))
          jetpacs-settings-hub-entries))

(defun jetpacs-settings--view ()
  "The settings hub: category cards for each settings domain."
  (jetpacs-chrome-screen
   "Settings"
   (apply #'jetpacs-lazy-column (jetpacs-settings--hub-cards))
   :on-refresh (jetpacs-action "settings.refresh")))

(defun jetpacs-settings-refresh ()
  "Re-push the settings surface (deferred; safe from dispatch)."
  (jetpacs-flow-continue
   (lambda ()
     (ignore-errors (jetpacs-shell-push jetpacs-settings-surface)))))

;;;; The one-live-settings-dialog slot (§3 step 2: foundation-owned)
;;
;; Settings management dialogs — the org-workflow editors
;; (jetpacs-org-settings.el) and downstream saved-search editors
;; — share ONE live request slot: nowhere in settings shows two dialogs
;; at once, and a single writer is what lets a Save fired from INSIDE a
;; dialog find the params the dialog was opened from (a dialog-context
;; event carries no `:surface', SPEC 14.4).  Promoted so app writers do not
;; reach across modules into a private slot.

(defconst jetpacs-settings-dialog-owner "jetpacs.settings"
  "The dialog-slot owner every settings editor dialog shares.")

(defun jetpacs-settings-dialog-params ()
  "The opening event's params of the live settings dialog, or nil."
  (jetpacs-dialog-slot-params jetpacs-settings-dialog-owner))

(defun jetpacs-settings-dialog-close ()
  "Retire the live settings dialog (the S3 handler-side dismissal)."
  (jetpacs-dialog-slot-close jetpacs-settings-dialog-owner))

(cl-defun jetpacs-settings-show-dialog (id spec &key params on-submit)
  "Show SPEC as dialog ID in the settings dialog slot.
PARAMS and ON-SUBMIT have their `jetpacs-dialog-slot-show' meanings:
dialogs whose Save is a remote action conclude through
`jetpacs-settings-dialog-close' in that action's handler."
  (jetpacs-dialog-slot-show id spec :owner jetpacs-settings-dialog-owner
                            :params params :on-submit on-submit))

;;;; Actions and state handlers

(defun jetpacs-settings--action-set (args _params)
  "Apply the allowlisted setting and wire value named by ARGS."
  (let* ((name (plist-get args :name))
         (sym (and (stringp name) (intern-soft name)))
         (entry (and sym (jetpacs-settings--entry sym))))
    (if (not entry)
        'rejected
      (jetpacs-settings-apply-wire sym (plist-get args :value)
                                   (plist-get (cdr entry) :after-set))
      (jetpacs-settings-refresh)
      'accepted)))

(defun jetpacs-settings--action-reset (args _params)
  "Reset the allowlisted setting named by ARGS to its standard value."
  (let* ((name (plist-get args :name))
         (sym (and (stringp name) (intern-soft name)))
         (entry (and sym (jetpacs-settings--entry sym))))
    (if (not entry)
        'rejected
      (jetpacs-settings-reset sym (plist-get (cdr entry) :after-set)
                              (jetpacs-settings--entry-label entry))
      (jetpacs-settings-refresh)
      'accepted)))

(defun jetpacs-settings-watch-toggle (sym id &optional after-set)
  "Register the state.changed handler applying SYM's switch under ID.
The switch widget publishes state.changed instead of dispatching an
action, so a boolean setting only works once a handler exists for its
widget id.  Non-boolean payloads under ID are ignored; those save
through their submit action instead."
  (jetpacs-on-state-change
   id (lambda (val)
        (when (memq val '(t :json-false))
          (jetpacs-settings-apply sym (eq val t) after-set)
          (jetpacs-settings-refresh)))))

(defun jetpacs-settings--action-emacs (_args _params)
  "Push the Emacs settings screen onto the settings surface."
  (jetpacs-chrome-push-screen jetpacs-settings-surface
                              "jetpacs-settings-emacs"
                              #'jetpacs-settings--emacs-view)
  'accepted)

(defun jetpacs-settings--action-refresh (_args _params)
  "Schedule a refresh of the current settings screen."
  (jetpacs-settings-refresh)
  'accepted)

(with-jetpacs-owner "jetpacs.settings"
  (jetpacs-chrome-define-root jetpacs-settings-surface "home"
                              (lambda (_back) (jetpacs-settings--view))
                              :required t)

  (jetpacs-settings-register-hub-entry
   "jetpacs" :order 10 :icon "build"
   :title "Jetpacs Settings" :subtitle "App configuration and repair"
   :plain '(:subtitle "Background running, notes and diagnostics")
   :on-tap (jetpacs-surface-open "companion:settings"))
  (jetpacs-settings-register-hub-entry
   "emacs" :order 20 :icon "settings"
   :title "Emacs Settings" :subtitle "Editor, files, org, and more"
   :plain '(:subtitle "Org, files, clipboard and more")
   :on-tap (jetpacs-action "settings.emacs"))
  (jetpacs-settings-register-hub-entry
   "theme" :order 30 :icon "palette"
   :title "Theme" :subtitle "Mode and Modus-family themes"
   :plain '(:subtitle "Colors and themes")
   :on-tap (jetpacs-action "modus.show")))

(jetpacs-defaction "settings.set" #'jetpacs-settings--action-set
                   :args '((:name name :type "text" :required t)
                           (:name value :required t))
                   :doc "Set one allowlisted Jetpacs setting")
(jetpacs-defaction "settings.reset" #'jetpacs-settings--action-reset
                   :args '((:name name :type "text" :required t))
                   :doc "Reset one allowlisted Jetpacs setting")
(jetpacs-defaction "settings.emacs" #'jetpacs-settings--action-emacs
                   :doc "Open the registered Emacs settings")
(jetpacs-defaction "settings.refresh" #'jetpacs-settings--action-refresh
                   :doc "Refresh the current settings screen")

(defun jetpacs-settings-drawer-entry ()
  "The drawer's Settings entry — a single tap opens the settings hub."
  (jetpacs-chrome-row "Settings"
                      :subtitle "Jetpacs, Emacs, theme, and app options"
                      :icon "settings"
                      :on-tap (jetpacs-shell-open-surface-action
                               (concat "app:" jetpacs-settings-surface))
                      :key "drawer-settings"))

(defvar jetpacs-launcher-row-icons)
(with-eval-after-load 'jetpacs-launcher
  (setf (alist-get (concat "app:" jetpacs-settings-surface)
                   jetpacs-launcher-row-icons nil nil #'equal)
        "settings"))

(provide 'jetpacs-settings)
;;; jetpacs-settings.el ends here
