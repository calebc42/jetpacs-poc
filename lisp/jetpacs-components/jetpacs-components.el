;;; jetpacs-components.el --- Jetpacs-owned component nodes -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Public Elisp builders for the optional `jetpacs.components' renderer
;; extension.  These nodes are authored as ordinary EBP JSON IR; executable
;; Elisp never crosses the wire.  Loading the library installs the generated
;; schema used by sender-side profile and member gates.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'jetpacs-components-vocabulary)
(require 'jetpacs-widgets)

(jetpacs-register-renderer-extension
 jetpacs-components-extension
 jetpacs-components-node-schema
 jetpacs-components-target-node-types)

(defun jetpacs-components--non-empty-label (label what)
  "Return non-empty string LABEL or signal; WHAT names its member."
  (jetpacs-require-string label what)
  (when (string-empty-p label)
    (error "jetpacs-components: %s must be non-empty" what))
  label)

(cl-defun jetpacs-component-action (label on-tap &key enabled)
  "Build a Jetpacs Action labeled LABEL dispatching ON-TAP.
ENABLED is t or `:json-false' when present.  The returned value is the actual
plist node IR and requires the receiver extension `jetpacs.components'."
  (jetpacs-components--non-empty-label label ":label")
  (jetpacs-check-descriptor on-tap ":on-tap")
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (jetpacs-make-node "jetpacs.action"
                     :label label :on_tap on-tap :enabled enabled))

(cl-defun jetpacs-component-choice (id label checked on-change &key enabled)
  "Build one Jetpacs Choice identified by ID and labeled LABEL.
CHECKED is t or `:json-false'.  ON-CHANGE receives the next boolean through
the ordinary action value path after `state.changed' is published.  ENABLED
is t or `:json-false' when present."
  (jetpacs-check-identifier id ":id")
  (jetpacs-components--non-empty-label label ":label")
  (jetpacs-check-bool checked ":checked")
  (jetpacs-check-descriptor on-change ":on-change")
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (jetpacs-make-node "jetpacs.choice"
                     :id id :label label :checked checked
                     :on_change on-change :enabled enabled))

(defun jetpacs-component-panel (label children)
  "Build a labeled Jetpacs Panel containing CHILDREN.
LABEL is a non-empty string.  CHILDREN is a proper list of typed node plists;
their semantics and actions remain independent descendants of the panel."
  (jetpacs-components--non-empty-label label ":label")
  (unless (and (proper-list-p children)
               (cl-every #'jetpacs-root-node-p children))
    (error "jetpacs-component-panel: CHILDREN must be a proper list of nodes"))
  (jetpacs-make-node "jetpacs.panel"
                     :label label :children (vconcat children)))

(defun jetpacs-component-tab (label value)
  "Build one Jetpacs tab option labeled LABEL with string VALUE.
LABEL and VALUE are non-empty strings.  The returned plist is an untyped,
closed option object for `jetpacs-component-tabs'; it is not a standalone
renderer node."
  (jetpacs-components--non-empty-label label ":label")
  (jetpacs-components--non-empty-label value ":value")
  (jetpacs-make-node nil :label label :value value))

(defconst jetpacs-component-tabs-variants
  '("fixed" "scrollable" "navigator" "adaptive")
  "Closed presentation variants accepted by `jetpacs-component-tabs'.")

(cl-defun jetpacs-component-tabs
    (id options value on-change &key enabled scrollable pinned variant)
  "Build controlled Jetpacs Tabs identified by ID over OPTIONS.
OPTIONS is a non-empty proper list from `jetpacs-component-tab', with unique
string values.  VALUE must equal one option value.  ON-CHANGE receives the
selected string as its injected action `value'.  ENABLED, SCROLLABLE, and
PINNED are t or `:json-false' when present.  VARIANT is `fixed', `scrollable',
`navigator', or `adaptive'.  With VARIANT present, SCROLLABLE is derived as
false for `fixed' and true for every other variant; a matching explicit legacy
SCROLLABLE is accepted and a contradiction is rejected.  With VARIANT absent,
SCROLLABLE retains its legacy behavior.  A true PINNED requires a direct
`lazy_column' parent, which keeps the strip visible while its remaining content
scrolls.  Absent or false PINNED renders ordinary Tabs under any valid parent.
Selection remains application-owned: VALUE is the authoritative value sent in
each document."
  (jetpacs-check-identifier id ":id")
  (unless (and (proper-list-p options) options)
    (error "jetpacs-component-tabs: OPTIONS must be a non-empty proper list"))
  (let (values)
    (dolist (option options)
      (unless (and (listp option)
                   (equal (length option) 4)
                   (plist-member option :label)
                   (plist-member option :value))
        (error "jetpacs-component-tabs: Each option must come from `jetpacs-component-tab'"))
      (jetpacs-components--non-empty-label (plist-get option :label)
                                           ":options[].label")
      (jetpacs-components--non-empty-label (plist-get option :value)
                                           ":options[].value")
      (push (plist-get option :value) values))
    (unless (= (length values)
               (length (delete-dups (copy-sequence values))))
      (error "jetpacs-component-tabs: Option values must be unique"))
    (jetpacs-components--non-empty-label value ":value")
    (unless (member value values)
      (error "jetpacs-component-tabs: VALUE must name one option")))
  (jetpacs-check-descriptor on-change ":on-change")
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (when scrollable (jetpacs-check-bool scrollable ":scrollable"))
  (when pinned (jetpacs-check-bool pinned ":pinned"))
  (when variant
    (jetpacs-require-string variant ":variant")
    (unless (member variant jetpacs-component-tabs-variants)
      (error "jetpacs-component-tabs: VARIANT is not recognized"))
    (let ((derived (if (equal variant "fixed") :json-false t)))
      (when (and scrollable (not (eq scrollable derived)))
        (error "jetpacs-component-tabs: SCROLLABLE contradicts VARIANT"))
      (setq scrollable derived)))
  (jetpacs-make-node
   "jetpacs.tabs"
   :id id :options (vconcat options) :value value
   :on_change on-change :enabled enabled
   :scrollable scrollable :pinned pinned :variant variant))

(defun jetpacs-component-section (label value level)
  "Build one hierarchical section option labeled LABEL.
VALUE is its non-empty stable string identity and LEVEL is an integer from 1
through 6.  The returned closed plist is an untyped option object for
`jetpacs-component-section-navigator', not a standalone renderer node."
  (jetpacs-components--non-empty-label label ":label")
  (jetpacs-components--non-empty-label value ":value")
  (unless (and (integerp level) (<= 1 level 6))
    (error "jetpacs-component-section: LEVEL must be an integer from 1 to 6"))
  (jetpacs-make-node nil :label label :value value :level level))

(cl-defun jetpacs-component-section-navigator
    (id sections value on-change &key enabled pinned)
  "Build a controlled hierarchical section navigator identified by ID.
SECTIONS is a non-empty proper list from `jetpacs-component-section', with
unique string values and required levels from 1 through 6.  VALUE must name
one section.  ON-CHANGE receives the selected string through the ordinary
injected action `value'.  ENABLED and PINNED are t or `:json-false' when
present.  A true PINNED requires this node to be a direct `lazy_column' child.
Selection and any command-driven scrolling remain application-owned."
  (jetpacs-check-identifier id ":id")
  (unless (and (proper-list-p sections) sections)
    (error
     "jetpacs-component-section-navigator: SECTIONS must be a non-empty proper list"))
  (let (values)
    (dolist (section sections)
      (unless (and (listp section)
                   (equal (length section) 6)
                   (plist-member section :label)
                   (plist-member section :value)
                   (plist-member section :level))
        (error
         "jetpacs-component-section-navigator: Each section must come from `jetpacs-component-section'"))
      (jetpacs-components--non-empty-label
       (plist-get section :label) ":options[].label")
      (jetpacs-components--non-empty-label
       (plist-get section :value) ":options[].value")
      (unless (and (integerp (plist-get section :level))
                   (<= 1 (plist-get section :level) 6))
        (error
         "jetpacs-component-section-navigator: Section levels must be integers from 1 to 6"))
      (push (plist-get section :value) values))
    (unless (= (length values)
               (length (delete-dups (copy-sequence values))))
      (error "jetpacs-component-section-navigator: Section values must be unique"))
    (jetpacs-components--non-empty-label value ":value")
    (unless (member value values)
      (error
       "jetpacs-component-section-navigator: VALUE must name one section")))
  (jetpacs-check-descriptor on-change ":on-change")
  (when enabled (jetpacs-check-bool enabled ":enabled"))
  (when pinned (jetpacs-check-bool pinned ":pinned"))
  (jetpacs-make-node
   "jetpacs.section_navigator"
   :id id :options (vconcat sections) :value value
   :on_change on-change :enabled enabled :pinned pinned))

(defun jetpacs-component-scope (children)
  "Select Jetpacs core rendering for canonical EBP CHILDREN.
CHILDREN is a proper list of typed node plists.  The returned
`jetpacs.scope' is an invisible renderer-selection boundary: it owns no
layout, state, interaction, or accessibility semantics and emits CHILDREN as
the actual canonical node vector."
  (unless (and (proper-list-p children)
               (cl-every #'jetpacs-root-node-p children))
    (error "jetpacs-component-scope: CHILDREN must be a proper list of nodes"))
  (jetpacs-make-node "jetpacs.scope" :children (vconcat children)))

(provide 'jetpacs-components)
;;; jetpacs-components.el ends here
