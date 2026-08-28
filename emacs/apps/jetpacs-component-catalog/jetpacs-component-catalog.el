;;; jetpacs-component-catalog.el --- Jetpacs component reference -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A separate, Elisp-authored reference app for Jetpacs' own design language.
;; It coexists with the Glasspane Material 3 Catalog and exercises the real
;; `jetpacs.components' negotiation, action, state, and descendant paths.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-components)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-apps)

(defconst jetpacs-component-catalog-owner "jpcatalog"
  "Owner of the Jetpacs Components catalog surface and actions.")

(defconst jetpacs-component-catalog-title "Jetpacs Components"
  "User-facing title and app label for the Jetpacs-owned catalog.")

(defvar jetpacs-component-catalog--action-count 0
  "Number of accepted Action demonstrations in this Emacs session.")

(defvar jetpacs-component-catalog--choice t
  "Current Emacs-owned value of the interactive Choice demonstration.")

(defconst jetpacs-component-catalog--components
  '(("action" "Action" "One explicit, full-width command target.")
    ("choice" "Choice" "A binary setting with reconciled boolean state.")
    ("panel" "Panel" "Labeled containment without descendant merging."))
  "Catalog component rows as (ID NAME PURPOSE).")

(defun jetpacs-component-catalog--open-action (id)
  "Return the catalog navigation descriptor for component ID."
  (jetpacs-action "jpcatalog.open" :args (list :component id)))

(defun jetpacs-component-catalog--code-panel (elisp ebp)
  "Build a protocol-reference panel showing ELISP and emitted EBP text."
  (jetpacs-component-panel
   "AUTHORING / EBP"
   (list
    (jetpacs-text elisp :style "mono" :selectable t)
    (jetpacs-divider)
    (jetpacs-text ebp :style "mono" :selectable t))))

(cl-defun jetpacs-component-catalog--screen (title body &key back)
  "Build a catalog screen titled TITLE around scoped BODY and BACK.
Glasspane chrome remains outside the invisible `jetpacs.scope'; canonical EBP
nodes inside BODY may therefore select installed Jetpacs core overrides."
  (jetpacs-chrome-screen
   title (jetpacs-component-scope (list body)) :back back))

(defun jetpacs-component-catalog--home-screen (back)
  "Build the component catalog root with BACK navigation."
  (jetpacs-component-catalog--screen
   jetpacs-component-catalog-title
   (apply
    #'jetpacs-lazy-column
    (append
     (list
      (jetpacs-component-panel
       "JETPACS DESIGN / FIRST SLICE"
       (list
        (jetpacs-text
         "Editor-native controls implemented with Compose Foundation. Material 3 remains a separate Glasspane library and catalog.")
        (jetpacs-text
         "Three components prove command dispatch, reconciled state, containment, semantics, and renderer-extension negotiation."
         :style "caption"))))
     (mapcar
      (lambda (component)
        (pcase-let ((`(,id ,name ,purpose) component))
          (jetpacs-component-panel
           name
           (list
            (jetpacs-text purpose)
            (jetpacs-component-action
             (format "Inspect %s" name)
             (jetpacs-component-catalog--open-action id))))))
      jetpacs-component-catalog--components)
     (list :spacing 12 :content-padding 12)))
   :back back))

(defun jetpacs-component-catalog--action-screen (back)
  "Build the Action anatomy, states, and protocol screen with BACK."
  (jetpacs-component-catalog--screen
   "Action"
   (jetpacs-lazy-column
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
       :style "caption")))
    (jetpacs-component-catalog--code-panel
     "(jetpacs-component-action \"Run\"\n  (jetpacs-action \"jpcatalog.activate\"))"
     "{\"t\":\"jetpacs.action\",\"label\":\"Run\",\"on_tap\":{\"action\":\"jpcatalog.activate\"}}")
    :spacing 12 :content-padding 12)
   :back back))

(defun jetpacs-component-catalog--choice-screen (back)
  "Build the Choice anatomy, states, and protocol screen with BACK."
  (jetpacs-component-catalog--screen
   "Choice"
   (jetpacs-lazy-column
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
       :style "caption")))
    (jetpacs-component-catalog--code-panel
     "(jetpacs-component-choice \"setting-id\" \"Mirror updates\"\n  (jetpacs-bool value) (jetpacs-action \"jpcatalog.choice\"))"
     "{\"t\":\"jetpacs.choice\",\"id\":\"setting-id\",\"label\":\"Mirror updates\",\"checked\":true,\"on_change\":{\"action\":\"jpcatalog.choice\"}}")
    :spacing 12 :content-padding 12)
   :back back))

(defun jetpacs-component-catalog--panel-screen (back)
  "Build the Panel anatomy, nesting, and protocol screen with BACK."
  (jetpacs-component-catalog--screen
   "Panel"
   (jetpacs-lazy-column
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
       (jetpacs-action "jpcatalog.activate"))))
    (jetpacs-component-catalog--code-panel
     "(jetpacs-component-panel \"STATUS\"\n  (list (jetpacs-text \"Ready\")))"
     "{\"t\":\"jetpacs.panel\",\"label\":\"STATUS\",\"children\":[{\"t\":\"text\",\"text\":\"Ready\"}]}" )
    :spacing 12 :content-padding 12)
   :back back))

(defun jetpacs-component-catalog--detail-screen (component back)
  "Build COMPONENT's detail screen using BACK navigation."
  (pcase component
    ("action" (jetpacs-component-catalog--action-screen back))
    ("choice" (jetpacs-component-catalog--choice-screen back))
    ("panel" (jetpacs-component-catalog--panel-screen back))
    (_ (error "Unknown Jetpacs component %S" component))))

(defun jetpacs-component-catalog--on-open (args params)
  "Open the component in ARGS on PARAMS' originating surface."
  (let ((component (plist-get args :component))
        (surface (plist-get params :surface)))
    (if (not (assoc component jetpacs-component-catalog--components))
        'stale
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
      'accepted)))

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

(defconst jetpacs-component-catalog--verbs
  '(("jpcatalog.open" . jetpacs-component-catalog--on-open)
    ("jpcatalog.activate" . jetpacs-component-catalog--on-activate)
    ("jpcatalog.choice" . jetpacs-component-catalog--on-choice))
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
     "jpcatalog.activate" #'jetpacs-component-catalog--on-activate
     :doc "Execute the catalog Action demonstration")
    (jetpacs-defaction
     "jpcatalog.choice" #'jetpacs-component-catalog--on-choice
     :args '((:name value :type "bool" :required t))
     :doc "Commit the catalog Choice demonstration value")
    (jetpacs-chrome-define-root
     jetpacs-component-catalog-owner "home"
     #'jetpacs-component-catalog--home-screen))
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
  (dolist (verb (mapcar #'car jetpacs-component-catalog--verbs))
    (jetpacs-undefaction verb))
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
