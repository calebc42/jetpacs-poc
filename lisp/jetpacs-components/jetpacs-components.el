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

(provide 'jetpacs-components)
;;; jetpacs-components.el ends here
