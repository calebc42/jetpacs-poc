;;; jetpacs-design.el --- bounded Elisp design authoring -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Deterministic public builders for `jetpacs.design'.  They produce ordinary
;; EBP node plists; no executable Elisp or AndroidX type crosses the wire.
;; Identifier maps are sorted before serialization and locally checked
;; against the same closed property language used by the receiver compiler.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'jetpacs-design-vocabulary)
(require 'jetpacs-widgets)

(jetpacs-register-renderer-extension
 jetpacs-design-extension
 jetpacs-design-node-schema
 jetpacs-design-target-node-types)

(defconst jetpacs-design--value-kinds
  '("boolean" "color" "dimension" "font-family" "font-weight"
    "number" "theme-role" "text-align" "token")
  "Closed value-kind spellings accepted by the design runtime.")

(defconst jetpacs-design-component-style-slots
  '("text.body" "text.title" "text.headline" "text.caption" "text.label"
    "text.mono"
    "card.filled" "card.elevated" "card.outlined"
    "list-item.container" "list-item.overline" "list-item.title"
    "list-item.subtitle"
    "swipe.cell" "swipe.label"
    "icon-button.container"
    "badge.container" "badge.label"
    "empty-state.container" "empty-state.title" "empty-state.caption"
    "button.filled" "button.tonal" "button.elevated" "button.outlined"
    "button.text" "button.label"
    "chip.container" "chip.label"
    "divider.line"
    "section-header.container" "section-header.title"
    "menu.trigger" "menu.container" "menu.item" "menu.item-label"
    "menu.item-supporting" "menu.group-label"
    "switch.track" "switch.thumb" "switch.label"
    "collapsible.header" "collapsible.chevron" "collapsible.body"
    "month-grid.container" "month-grid.header" "month-grid.nav"
    "month-grid.weekday" "month-grid.day" "month-grid.today"
    "month-grid.range" "month-grid.mark"
    "action.container" "action.label"
    "choice.container" "choice.indicator" "choice.label"
    "panel.container" "panel.label"
    "tabs.container" "tabs.item" "tabs.indicator" "tabs.label"
    "section-navigator.container" "section-navigator.option"
    "section-navigator.label" "section-navigator.button"
    "section-navigator.selector" "section-navigator.popup"
    "section-navigator.popup-item"
    "text-field.outlined" "text-field.filled" "text-field.text"
    "text-field.label" "text-field.placeholder" "text-field.supporting"
    "text-field.affix"
    "editor.surface" "editor.chromeless" "editor.text" "editor.gutter"
    "editor.toolbar" "editor.toolbar-item" "editor.sync-status"
    "editor.completion-list" "editor.completion-item"
    "editor.candidate-document" "editor.tooling-status")
  "Closed semantic-component visual slots accepted by design scopes.")

(defconst jetpacs-design--property-kinds
  '(("background_color" . "color")
    ("content_color" . "color")
    ("border_color" . "color")
    ("border_width" . "dimension")
    ("corner_radius" . "dimension")
    ("padding" . "dimension")
    ("padding_horizontal" . "dimension")
    ("padding_vertical" . "dimension")
    ("padding_start" . "dimension")
    ("padding_top" . "dimension")
    ("padding_end" . "dimension")
    ("padding_bottom" . "dimension")
    ("width" . "dimension")
    ("height" . "dimension")
    ("min_width" . "dimension")
    ("min_height" . "dimension")
    ("alpha" . "number")
    ("scale" . "number")
    ("font_size" . "dimension")
    ("line_height" . "dimension")
    ("letter_spacing" . "dimension")
    ("font_family" . "font-family")
    ("font_weight" . "font-weight")
    ("text_align" . "text-align")
    ("fill_width" . "boolean"))
  "Property names paired with their direct value kinds.")

(defconst jetpacs-design--states
  '("disabled" "selected" "toggled" "hovered" "focused" "pressed")
  "Closed state names accepted by an authored style rule.")

(defconst jetpacs-design--easings
  '("linear" "ease-in" "ease-out" "ease-in-out" "spring")
  "Closed easing names accepted by an authored motion.")

(defun jetpacs-design--finite-number-p (value)
  "Return non-nil when VALUE is a finite number."
  (and (numberp value)
       (not (string-match-p "NaN\\|INF" (number-to-string value)))))

(defun jetpacs-design--number-string (value minimum maximum what)
  "Return deterministic VALUE text after checking bounds.
MINIMUM and MAXIMUM are inclusive; WHAT identifies the authored field."
  (unless (and (jetpacs-design--finite-number-p value)
               (<= minimum value maximum))
    (error "jetpacs-design: %s must be finite and between %s and %s"
           what minimum maximum))
  (if (zerop value) "0" (number-to-string value)))

(defun jetpacs-design--value (kind value)
  "Build a checked design value with KIND and string VALUE."
  (unless (member kind jetpacs-design--value-kinds)
    (error "jetpacs-design: unknown value kind %S" kind))
  (unless (and (stringp value) (<= 1 (length value) 256))
    (error "jetpacs-design: value must be a string of 1 to 256 characters"))
  (jetpacs-make-node nil :kind kind :value value))

(defun jetpacs-design-boolean (value)
  "Build a design boolean VALUE, which is t or `:json-false'."
  (jetpacs-check-bool value "design boolean")
  (jetpacs-design--value "boolean" (if (eq value t) "true" "false")))

(defun jetpacs-design-color (value)
  "Build a design color from #RRGGBB or #AARRGGBB string VALUE."
  (unless (and (stringp value)
               (string-match-p
                "\\`#[[:xdigit:]]\\{6\\}\\(?:[[:xdigit:]]\\{2\\}\\)?\\'"
                value))
    (error "jetpacs-design: color must be #RRGGBB or #AARRGGBB"))
  (jetpacs-design--value "color" value))

(defun jetpacs-design-theme-role (value)
  "Build a dynamic design color referencing EBP theme role VALUE."
  (unless (member value jetpacs-theme-roles)
    (error "jetpacs-design: unknown EBP theme role %S" value))
  (jetpacs-design--value "theme-role" value))

(defun jetpacs-design-dimension (value)
  "Build a non-negative design dimension VALUE in density-independent units."
  (jetpacs-design--value
   "dimension" (jetpacs-design--number-string value 0 10000 "dimension")))

(defun jetpacs-design-number (value)
  "Build a bounded, finite unitless design number VALUE."
  (jetpacs-design--value
   "number" (jetpacs-design--number-string value -10000 10000 "number")))

(defun jetpacs-design-font-family (value)
  "Build a design font-family VALUE."
  (unless (member value '("system" "plex-sans" "plex-serif" "plex-mono"))
    (error "jetpacs-design: unknown font family %S" value))
  (jetpacs-design--value "font-family" value))

(defun jetpacs-design-font-weight (value)
  "Build a design font weight VALUE in 100 steps from 100 through 900."
  (unless (and (integerp value) (<= 100 value 900) (zerop (% value 100)))
    (error "jetpacs-design: font weight must be a 100 step from 100 to 900"))
  (jetpacs-design--value "font-weight" (number-to-string value)))

(defun jetpacs-design-text-align (value)
  "Build a design text-alignment VALUE: start, center, or end."
  (unless (member value '("start" "center" "end"))
    (error "jetpacs-design: unknown text alignment %S" value))
  (jetpacs-design--value "text-align" value))

(defun jetpacs-design-token (identifier)
  "Build a reference to design token IDENTIFIER."
  (jetpacs-check-identifier identifier "design token reference")
  (jetpacs-design--value "token" identifier))

(defun jetpacs-design--checked-value (value what)
  "Return VALUE when it is a closed design-value plist; WHAT names it."
  (unless (and (proper-list-p value)
               (= (length value) 4)
               (equal (plist-get value :kind)
                      (car (member (plist-get value :kind)
                                   jetpacs-design--value-kinds)))
               (stringp (plist-get value :value)))
    (error "jetpacs-design: %s must come from a design value builder" what))
  value)

(defun jetpacs-design--sorted-map (entries maximum what validator)
  "Turn alist ENTRIES into a sorted identifier plist.
MAXIMUM bounds its size, WHAT labels diagnostics, and VALIDATOR checks values."
  (unless (proper-list-p entries)
    (error "jetpacs-design: %s must be a proper alist" what))
  (when (> (length entries) maximum)
    (error "jetpacs-design: %s contains more than %d entries" what maximum))
  (let ((sorted (sort (copy-sequence entries)
                      (lambda (left right) (string< (car left) (car right)))))
        previous
        result)
    (dolist (entry sorted)
      (unless (and (consp entry) (stringp (car entry)))
        (error "jetpacs-design: %s entries must have string identifiers" what))
      (jetpacs-check-identifier (car entry) what)
      (when (equal previous (car entry))
        (error "jetpacs-design: %s repeats identifier %S" what (car entry)))
      (setq previous (car entry))
      (push (intern (concat ":" (car entry))) result)
      (push (funcall validator (cdr entry) (car entry)) result))
    (nreverse result)))

(defun jetpacs-design-properties (entries)
  "Build a deterministic property map from alist ENTRIES.
Each key is a closed design property and each value comes from one of the
design value builders.  Direct value kinds are checked locally; token
references are resolved when their containing scope is built."
  (jetpacs-design--sorted-map
   entries 64 "properties"
   (lambda (value name)
     (jetpacs-design--checked-value value name)
     (let ((expected (cdr (assoc name jetpacs-design--property-kinds)))
           (actual (plist-get value :kind)))
       (unless expected
         (error "jetpacs-design: unknown property %S" name))
       (unless (or (equal actual "token")
                   (equal actual expected)
                   (and (equal expected "color")
                        (equal actual "theme-role")))
         (error "jetpacs-design: property %s needs %s, not %s"
                name expected actual))
       (when (and (member name '("alpha" "scale"))
                  (equal actual "number"))
         (let ((number (string-to-number (plist-get value :value))))
           (unless (if (equal name "alpha")
                       (<= 0 number 1)
                     (<= 0 number 4))
             (error "jetpacs-design: %s is out of range" name))))
       value))))

(cl-defun jetpacs-design-rule (state properties &key motion)
  "Build a STATE rule over checked property alist PROPERTIES.
MOTION, when present, names a motion in the effective design scope."
  (unless (member state jetpacs-design--states)
    (error "jetpacs-design: unknown state %S" state))
  (when motion (jetpacs-check-identifier motion "rule motion"))
  (jetpacs-make-node nil
                     :state state
                     :properties (jetpacs-design-properties properties)
                     :motion motion))

(cl-defun jetpacs-design-style (properties &key rules motion)
  "Build a design style over property alist PROPERTIES.
RULES is an authored-order list from `jetpacs-design-rule' and may contain at
most 16 elements.  MOTION names a motion in the effective scope."
  (unless (and (proper-list-p rules) (<= (length rules) 16))
    (error "jetpacs-design: rules must be a proper list of at most 16 rules"))
  (dolist (rule rules)
    (unless (and (proper-list-p rule)
                 (member (plist-get rule :state) jetpacs-design--states)
                 (plist-member rule :properties))
      (error "jetpacs-design: each rule must come from `jetpacs-design-rule'")))
  (when motion (jetpacs-check-identifier motion "style motion"))
  (jetpacs-make-node nil
                     :properties (jetpacs-design-properties properties)
                     :rules (vconcat rules)
                     :motion motion))

(defun jetpacs-design-motion (duration-ms easing)
  "Build a motion lasting DURATION-MS with EASING."
  (unless (and (integerp duration-ms) (<= 0 duration-ms 10000))
    (error "jetpacs-design: duration must be an integer from 0 to 10000"))
  (unless (member easing jetpacs-design--easings)
    (error "jetpacs-design: unknown easing %S" easing))
  (jetpacs-make-node nil :duration_ms duration-ms :easing easing))

(defun jetpacs-design--children (children maximum what)
  "Return vector of checked CHILDREN bounded by MAXIMUM; WHAT labels errors."
  (unless (and (proper-list-p children)
               (<= (length children) maximum)
               (cl-every #'jetpacs-root-node-p children))
    (error "jetpacs-design: %s must be at most %d typed nodes" what maximum))
  (vconcat children))

(defun jetpacs-design--style-references (styles)
  "Return vector of one to 16 checked style identifier STRINGS."
  (unless (and (proper-list-p styles) (<= 1 (length styles) 16))
    (error "jetpacs-design: styles must contain one to 16 identifiers"))
  (dolist (style styles) (jetpacs-check-identifier style "style reference"))
  (vconcat styles))

(defun jetpacs-design-component-style (slot styles)
  "Bind component visual SLOT to ordered style identifiers STYLES."
  (unless (member slot jetpacs-design-component-style-slots)
    (error "jetpacs-design: unknown component style slot %S" slot))
  (jetpacs-make-node
   nil :slot slot :styles (jetpacs-design--style-references styles)))

(defun jetpacs-design--component-styles (bindings)
  "Return deterministic vector of checked component style BINDINGS."
  (unless (and (proper-list-p bindings)
               (<= (length bindings)
                   (length jetpacs-design-component-style-slots)))
    (error "jetpacs-design: component styles must be a bounded proper list"))
  (let ((sorted (sort (copy-sequence bindings)
                      (lambda (left right)
                        (string< (plist-get left :slot)
                                 (plist-get right :slot)))))
        previous)
    (dolist (binding sorted)
      (unless (and (proper-list-p binding)
                   (member (plist-get binding :slot)
                           jetpacs-design-component-style-slots)
                   (vectorp (plist-get binding :styles)))
        (error "jetpacs-design: component binding must come from `jetpacs-design-component-style'"))
      (when (equal previous (plist-get binding :slot))
        (error "jetpacs-design: duplicate component style slot %S" previous))
      (setq previous (plist-get binding :slot)))
    (vconcat sorted)))

(defun jetpacs-design--plist-pairs (map)
  "Return string-keyed alist pairs from identifier plist MAP."
  (cl-loop for (key value) on map by #'cddr
           collect (cons (substring (symbol-name key) 1) value)))

(defun jetpacs-design--validate-local-references (tokens styles motions)
  "Reject unresolved or cyclic references within local scope maps."
  (let ((token-pairs (jetpacs-design--plist-pairs tokens))
        (style-pairs (jetpacs-design--plist-pairs styles))
        (motion-pairs (jetpacs-design--plist-pairs motions))
        (resolved (make-hash-table :test #'equal))
        (resolving (make-hash-table :test #'equal)))
    (cl-labels
        ((resolve-token
          (name)
          (unless (gethash name resolved)
            (when (gethash name resolving)
              (error "jetpacs-design: cyclic token reference involving %s" name))
            (let ((entry (assoc name token-pairs)))
              (unless entry
                (error "jetpacs-design: unresolved token %s" name))
              (puthash name t resolving)
              (when (equal (plist-get (cdr entry) :kind) "token")
                (resolve-token (plist-get (cdr entry) :value)))
              (remhash name resolving)
              (puthash name t resolved))))
         (check-properties
          (properties)
          (dolist (entry (jetpacs-design--plist-pairs properties))
            (when (equal (plist-get (cdr entry) :kind) "token")
              (resolve-token (plist-get (cdr entry) :value)))))
         (check-motion
          (name)
          (when (and name (not (assoc name motion-pairs)))
            (error "jetpacs-design: unresolved motion %s" name))))
      (dolist (entry token-pairs) (resolve-token (car entry)))
      (dolist (entry style-pairs)
        (let ((style (cdr entry)))
          (check-properties (plist-get style :properties))
          (check-motion (plist-get style :motion))
          (dolist (rule (append (plist-get style :rules) nil))
            (check-properties (plist-get rule :properties))
            (check-motion (plist-get rule :motion))))))))

(defun jetpacs-design--theme-roles (roles)
  "Return a sorted identifier plist for the EBP role alist ROLES.
Each entry pairs one of the 13 role names with a color, token, or theme-role
design value; the receiver re-derives its toolkit theme for the scope from
these, so chrome follows the profile instead of the ambient Emacs theme."
  (unless (and (proper-list-p roles) (<= (length roles) (length jetpacs-theme-roles)))
    (error "jetpacs-design: theme roles must be an alist of at most %d roles"
           (length jetpacs-theme-roles)))
  (jetpacs-design--sorted-map
   roles (length jetpacs-theme-roles) "theme roles"
   (lambda (value name)
     (unless (member name jetpacs-theme-roles)
       (error "jetpacs-design: %S is not an EBP theme role" name))
     (let ((checked (jetpacs-design--checked-value value name)))
       (unless (member (plist-get checked :kind) '("color" "token" "theme-role"))
         (error "jetpacs-design: theme role %s needs a color value" name))
       checked))))

(cl-defun jetpacs-design-scope
    (tokens styles children &key motions component-styles theme-roles)
  "Build a design scope from identifier alists TOKENS, STYLES, and MOTIONS.
CHILDREN is a proper list of ordinary EBP nodes.  Identifier maps are sorted
lexically and all locally declared token and motion references are checked.
COMPONENT-STYLES is an optional list of closed component slot bindings.
THEME-ROLES optionally re-declares EBP theme roles for the subtree, see
`jetpacs-design--theme-roles'."
  (let ((role-map (and theme-roles (jetpacs-design--theme-roles theme-roles)))
        (token-map
         (jetpacs-design--sorted-map
          tokens 256 "tokens"
          (lambda (value name)
            (jetpacs-design--checked-value value name))))
        (style-map
         (jetpacs-design--sorted-map
          styles 256 "styles"
          (lambda (value name)
            (unless (and (proper-list-p value)
                         (plist-member value :properties)
                         (vectorp (plist-get value :rules)))
              (error "jetpacs-design: style %s must come from a style builder"
                     name))
            value)))
        (motion-map
         (jetpacs-design--sorted-map
          motions 64 "motions"
          (lambda (value name)
            (unless (and (proper-list-p value)
                         (integerp (plist-get value :duration_ms))
                         (member (plist-get value :easing)
                                 jetpacs-design--easings))
              (error "jetpacs-design: motion %s must come from a motion builder"
                     name))
            value))))
    (jetpacs-design--validate-local-references token-map style-map motion-map)
    (dolist (pair (jetpacs-design--plist-pairs role-map))
      (when (equal (plist-get (cdr pair) :kind) "token")
        (unless (plist-get token-map (intern (concat ":" (plist-get (cdr pair) :value))))
          (error "jetpacs-design: theme role %s references unresolved token %s"
                 (car pair) (plist-get (cdr pair) :value)))))
    ;; `tokens' and `styles' are required wire members.  A nested scope that
    ;; only rebinds component slots declares neither, so an empty map must
    ;; survive `jetpacs-make-node's nil-drop as a literal `{}'.
    (jetpacs-make-node
     "jetpacs.design_scope"
     :tokens (or token-map (make-hash-table :test #'equal))
     :styles (or style-map (make-hash-table :test #'equal))
     :children (jetpacs-design--children children 10000 "scope children")
     :motions (and motions motion-map)
     :component_styles
     (and component-styles
          (jetpacs-design--component-styles component-styles))
     :theme_roles role-map)))

(defun jetpacs-design-styled (styles children)
  "Apply base-only STYLES to the child modifier of CHILDREN."
  (jetpacs-make-node
   "jetpacs.styled"
   :styles (jetpacs-design--style-references styles)
   :children (jetpacs-design--children children 10000 "styled children")))

(cl-defun jetpacs-design-pressable
    (styles on-tap children &key enabled selected toggled)
  "Build one accessible pressable face with STYLES and ON-TAP.
CHILDREN must contain one to 16 passive presentation nodes.  ENABLED,
SELECTED, and TOGGLED are t or `:json-false' when authored; transient hover,
focus, and press state stays local to the receiver."
  (jetpacs-check-descriptor on-tap ":on-tap")
  (unless (and (proper-list-p children) (<= 1 (length children) 16))
    (error "jetpacs-design: pressable children must contain one to 16 nodes"))
  (dolist (pair `((,enabled . "enabled")
                  (,selected . "selected")
                  (,toggled . "toggled")))
    (when (car pair) (jetpacs-check-bool (car pair) (cdr pair))))
  (jetpacs-make-node
   "jetpacs.pressable"
   :styles (jetpacs-design--style-references styles)
   :on_tap on-tap
   :children (jetpacs-design--children children 16 "pressable children")
   :enabled enabled
   :selected selected
   :toggled toggled))

(provide 'jetpacs-design)
;;; jetpacs-design.el ends here
