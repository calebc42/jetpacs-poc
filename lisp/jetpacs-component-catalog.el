;;; jetpacs-component-catalog.el --- Jetpacs component reference -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A separate, Elisp-authored reference app for Jetpacs' own design language.
;; It coexists with the Glasspane Material 3 Catalog and exercises the real
;; `jetpacs.components' negotiation, action, state, and descendant paths.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
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

(defvar jetpacs-component-catalog--text-change-count 0
  "Number of non-secret Text Field change actions accepted this session.")

(defvar jetpacs-component-catalog--text-submit-count 0
  "Number of non-secret Text Field submissions accepted this session.")

(defvar jetpacs-component-catalog--last-text-submit nil
  "Last non-secret Text Field value submitted to the catalog.")

(defvar jetpacs-component-catalog--secure-submit-count 0
  "Number of secure Text Field submissions accepted this session.")

(defvar jetpacs-component-catalog--last-secret-length nil
  "Scalar length of the latest secure demo; never the secret itself.")

(defconst jetpacs-component-catalog--components
  '(("action" "Action" "One explicit, full-width command target." "COMMANDS")
    ("choice" "Choice" "A binary setting with reconciled boolean state." "SELECTION")
    ("panel" "Panel" "Labeled containment without descendant merging." "STRUCTURE")
    ("text-field" "Text Field" "Native text entry with reconciled EBP state." "TEXT EDITING"))
  "Catalog component rows as (ID NAME PURPOSE CATEGORY).")

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
         "Four components prove command dispatch, reconciled state, containment, semantics, text editing, and renderer-extension negotiation."
         :style "caption"))))
     (mapcar
      (lambda (component)
        (pcase-let ((`(,id ,name ,purpose ,category) component))
          (jetpacs-component-panel
           category
           (list
            (jetpacs-text name :style "label")
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

(defun jetpacs-component-catalog--text-field-screen (back)
  "Build the canonical Text Field reference and live state screen with BACK."
  (jetpacs-component-catalog--screen
   "Text Field"
   (jetpacs-lazy-column
    (jetpacs-component-panel
     "PURPOSE"
     (list
      (jetpacs-text
       "A canonical EBP text_input presented as a compact Jetpacs work surface inside jetpacs.scope.")
      (jetpacs-text
       "Emacs owns application values; the Companion owns native selection, composition, drafts, and secure volatility."
       :style "caption")))
    (jetpacs-component-panel
     "LIVE ACTION / STATE"
     (list
      (jetpacs-text-input
       "jpcatalog-text-live"
       :label "Command text"
       :hint "Type and press Done"
       :single-line t
       :on-change (jetpacs-action "jpcatalog.text-change")
       :on-submit (jetpacs-action "jpcatalog.text-submit")
       :clear-on-submit t
       :hide-keyboard-on-submit t)
      (jetpacs-text
       (format "Changes: %d · submits: %d · last: %s"
               jetpacs-component-catalog--text-change-count
               jetpacs-component-catalog--text-submit-count
               (or jetpacs-component-catalog--last-text-submit "—"))
       :style "caption")))
    (jetpacs-component-panel
     "VARIANTS / DECORATIONS"
     (list
      (jetpacs-text-input
       "jpcatalog-text-filled"
       :variant "filled" :label "Amount" :hint "0"
       :prefix "$" :suffix ".00" :leading-icon "search"
       :trailing-icon "clear" :single-line t :keyboard "decimal")
      (jetpacs-text-input
       "jpcatalog-text-phone"
       :label "Phone" :hint "Digits only" :single-line t
       :keyboard "phone" :filter "digits" :max-length 10
       :mask "(###) ###-####")))
    (jetpacs-component-panel
     "MULTILINE / CODE"
     (list
      (jetpacs-text-input
       "jpcatalog-text-code"
       :value "(message \"Jetpacs\")"
       :label "Elisp" :syntax "elisp" :monospace t
       :min-lines 2 :max-lines 3)))
    (jetpacs-component-panel
     "ERROR / DISABLED"
     (list
      (jetpacs-text-input
       "jpcatalog-text-error"
       :label "Workspace name" :value "taken" :single-line t
       :is-error t :supporting-text "That name is already in use")
      (jetpacs-text-input
       "jpcatalog-text-disabled"
       :label "Managed value" :value "Read from Emacs"
       :single-line t :enabled :json-false)))
    (jetpacs-component-panel
     "SECURE"
     (list
      (jetpacs-text-input
       "jpcatalog-password"
       :label "One-time secret" :hint "Never retained"
       :password t :single-line t
       :on-submit
       (jetpacs-action
        "jpcatalog.secure-submit"
        :capture-fields '("jpcatalog-password")))
      (jetpacs-text
       (format "Secure submits: %d · last length: %s"
               jetpacs-component-catalog--secure-submit-count
               (or jetpacs-component-catalog--last-secret-length "—"))
       :style "caption")))
    (jetpacs-component-catalog--code-panel
     "(jetpacs-text-input \"query\"\n  :label \"Command text\" :single-line t\n  :on-submit (jetpacs-action \"app.submit\"))"
     "{\"t\":\"text_input\",\"id\":\"query\",\"label\":\"Command text\",\"single_line\":true,\"on_submit\":{\"action\":\"app.submit\"}}")
    :spacing 12 :content-padding 12)
   :back back))

(defun jetpacs-component-catalog--detail-screen (component back)
  "Build COMPONENT's detail screen using BACK navigation."
  (pcase component
    ("action" (jetpacs-component-catalog--action-screen back))
    ("choice" (jetpacs-component-catalog--choice-screen back))
    ("panel" (jetpacs-component-catalog--panel-screen back))
    ("text-field" (jetpacs-component-catalog--text-field-screen back))
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

(defun jetpacs-component-catalog--on-text-change (_args _params)
  "Count one non-secret text change without refreshing active IME state."
  (cl-incf jetpacs-component-catalog--text-change-count)
  'accepted)

(defun jetpacs-component-catalog--on-text-submit (args params)
  "Record ARGS' non-secret submitted value and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--text-submit-count)
      (setq jetpacs-component-catalog--last-text-submit value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-secure-submit (_args params)
  "Count PARAMS' volatile password by length, erase it, and retain no secret."
  (let ((secret (plist-get (plist-get params :fields) :jpcatalog-password)))
    (if (not (stringp secret))
        'rejected
      (setq jetpacs-component-catalog--last-secret-length (length secret))
      (cl-incf jetpacs-component-catalog--secure-submit-count)
      (clear-string secret)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defconst jetpacs-component-catalog--verbs
  '(("jpcatalog.open" . jetpacs-component-catalog--on-open)
    ("jpcatalog.activate" . jetpacs-component-catalog--on-activate)
    ("jpcatalog.choice" . jetpacs-component-catalog--on-choice)
    ("jpcatalog.text-change" . jetpacs-component-catalog--on-text-change)
    ("jpcatalog.text-submit" . jetpacs-component-catalog--on-text-submit)
    ("jpcatalog.secure-submit" . jetpacs-component-catalog--on-secure-submit))
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
    (jetpacs-defaction
     "jpcatalog.text-change" #'jetpacs-component-catalog--on-text-change
     :args '((:name value :type "text" :required t))
     :doc "Count one non-secret Text Field change without disrupting IME state")
    (jetpacs-defaction
     "jpcatalog.text-submit" #'jetpacs-component-catalog--on-text-submit
     :args '((:name value :type "text" :required t))
     :doc "Record and display one non-secret Text Field submission")
    (jetpacs-defaction
     "jpcatalog.secure-submit" #'jetpacs-component-catalog--on-secure-submit
     :doc "Count and immediately erase one volatile secure-field submission")
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
