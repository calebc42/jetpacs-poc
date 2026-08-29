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
(require 'ebp-sync)

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

(defvar jetpacs-component-catalog--editor-state-count 0
  "Number of local Editor draft publications accepted this session.")

(defvar jetpacs-component-catalog--editor-save-count 0
  "Number of local Editor save actions accepted this session.")

(defvar jetpacs-component-catalog--editor-enter-count 0
  "Number of local single-line Editor enter actions accepted this session.")

(defvar jetpacs-component-catalog--last-editor-preview nil
  "Bounded non-secret preview of the latest local Editor value.")

(defvar jetpacs-component-catalog--last-editor-length nil
  "Character length of the latest local Editor value.")

(defconst jetpacs-component-catalog--sync-document
  "doc:jpcatalog/synchronized-editor"
  "Stable EBP document identifier for the in-memory synchronization fixture.")

(defconst jetpacs-component-catalog--sync-editor-id
  "jpcatalog-editor-sync"
  "Canonical editor node identifier for the synchronization fixture.")

(defconst jetpacs-component-catalog--sync-seed
  "; Synchronized Jetpacs buffer\n(message \"Edit either side\")\n"
  "Initial non-secret text for the synchronization fixture.")

(defvar jetpacs-component-catalog--sync-buffer nil
  "Process-volatile buffer backing the synchronized Editor demonstration.")

(defvar jetpacs-component-catalog--sync-save-count 0
  "Number of synchronized Editor save actions accepted this session.")

(defvar jetpacs-component-catalog--last-sync-preview nil
  "Bounded preview of the latest synchronized Editor save value.")

(defconst jetpacs-component-catalog--components
  '(("action" "Action" "One explicit, full-width command target." "COMMANDS")
    ("choice" "Choice" "A binary setting with reconciled boolean state." "SELECTION")
    ("panel" "Panel" "Labeled containment without descendant merging." "STRUCTURE")
    ("text-field" "Text Field" "Native text entry with reconciled EBP state." "TEXT EDITING")
    ("editor" "Editor" "Local multiline editing on the canonical EBP node." "TEXT EDITING"))
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
         "Five components prove command dispatch, reconciled state, containment, semantics, text editing, and renderer-extension negotiation."
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

(defun jetpacs-component-catalog--editor-toolbar ()
  "Return the deterministic local Editor toolbar demonstration."
  (list
   (jetpacs-toolbar-item
    :label "Comment"
    :snippet ";; ${input:Comment}"
    :placement 'line-start
    :long-press '(:snippet ";;; ${input:Heading}"))
   (jetpacs-toolbar-item :label "Move down" :line 'move-down)
   (jetpacs-toolbar-item
    :label "Insert"
    :menu (list
           (jetpacs-toolbar-item :label "Date" :snippet "${date}")
           (jetpacs-toolbar-item :label "Time" :snippet "${time}")
           (jetpacs-toolbar-item
            :label "Message"
            :snippet "(message \"${input:Text}\")")))))

(defun jetpacs-component-catalog--ensure-sync-buffer ()
  "Return the catalog's process-volatile synchronized buffer.
The fixture deliberately disables every Phase 6 rider before attachment; it
tests only the normative text, caret, selection, and session lifecycle path."
  (unless (buffer-live-p jetpacs-component-catalog--sync-buffer)
    (setq jetpacs-component-catalog--sync-buffer
          (generate-new-buffer " *Jetpacs synchronized editor*"))
    (with-current-buffer jetpacs-component-catalog--sync-buffer
      (insert jetpacs-component-catalog--sync-seed)
      (set-buffer-modified-p nil)
      (setq buffer-undo-list nil)
      (setq-local buffer-auto-save-file-name nil)
      (setq-local ebp-sync-eglot nil)
      (setq-local ebp-sync-diagnostics nil)
      (setq-local ebp-sync-fontify nil)
      (setq-local ebp-sync-eldoc nil)))
  jetpacs-component-catalog--sync-buffer)

(defun jetpacs-component-catalog--sync-value ()
  "Return the whole synchronized catalog buffer as plain text."
  (with-current-buffer (jetpacs-component-catalog--ensure-sync-buffer)
    (save-restriction
      (widen)
      (buffer-substring-no-properties (point-min) (point-max)))))

(defun jetpacs-component-catalog--sync-available-p (&optional client)
  "Non-nil when CLIENT admits the catalog's synchronized app editor."
  (and (jetpacs-granted-p "editor.sync" client)
       (jetpacs-node-advertised-p "editor" :app)))

(defun jetpacs-component-catalog--on-ready (client)
  "Attach the in-memory catalog fixture to READY CLIENT when admitted."
  (when (jetpacs-component-catalog--sync-available-p client)
    (condition-case err
        (ebp-sync-attach
         client
         jetpacs-component-catalog--sync-document
         jetpacs-component-catalog--sync-editor-id
         (jetpacs-component-catalog--ensure-sync-buffer))
      (error
       (message "jetpacs-component-catalog: sync attach failed: %s"
                (jetpacs-error-label err))))))

(defun jetpacs-component-catalog--release-sync-buffer ()
  "Detach and destroy the catalog's non-durable synchronization fixture."
  (when (buffer-live-p jetpacs-component-catalog--sync-buffer)
    (with-current-buffer jetpacs-component-catalog--sync-buffer
      (ignore-errors (ebp-sync-detach))
      (set-buffer-modified-p nil))
    (kill-buffer jetpacs-component-catalog--sync-buffer))
  (setq jetpacs-component-catalog--sync-buffer nil))

(defun jetpacs-component-catalog--sync-panel ()
  "Build the admitted synchronized Editor panel or an honest fallback."
  (jetpacs-component-panel
   "SYNCHRONIZED / LIVE"
   (if (jetpacs-component-catalog--sync-available-p)
       (list
        (jetpacs-editor
         jetpacs-component-catalog--sync-editor-id
         :document jetpacs-component-catalog--sync-document
         :value (jetpacs-component-catalog--sync-value)
         :on-save (jetpacs-action "jpcatalog.sync-editor-save")
         :min-lines 5 :max-lines 8 :line-numbers t)
        (jetpacs-text
         (format "Saves: %d · latest: %s"
                 jetpacs-component-catalog--sync-save-count
                 (or jetpacs-component-catalog--last-sync-preview "—"))
         :style "caption")
        (jetpacs-text
         "Disconnect keeps the visible value but makes this field read-only; reconnect opens a fresh session without an offline draft."
         :style "caption"))
     (list
      (jetpacs-text
       "This Companion did not admit editor.sync for app surfaces."
       :style "caption")))))

(defun jetpacs-component-catalog--editor-screen (back)
  "Build the canonical local Editor reference and live state screen with BACK."
  (jetpacs-component-catalog--screen
   "Editor"
   (jetpacs-lazy-column
    (jetpacs-component-panel
     "PURPOSE"
     (list
      (jetpacs-text
       "A canonical local EBP editor presented by Jetpacs only inside jetpacs.scope.")
      (jetpacs-text
       "The shared controller owns selection, composition, byte limits, publication, and snapshot reconciliation; Styles own visuals only."
       :style "caption")))
    (jetpacs-component-panel
     "LIVE MULTILINE / SAVE"
     (list
      (jetpacs-editor
       "jpcatalog-editor-live"
       :value "; Local Jetpacs draft\n(message \"Edit me\")\n"
       :on-save (jetpacs-action "jpcatalog.editor-save")
       :min-lines 5 :max-lines 8
       :syntax "elisp" :line-numbers t :publish-state t :autofocus t
       :toolbar (jetpacs-component-catalog--editor-toolbar))
      (jetpacs-text
       (format "Drafts: %d · saves: %d · latest: %s chars · %s"
               jetpacs-component-catalog--editor-state-count
               jetpacs-component-catalog--editor-save-count
               (or jetpacs-component-catalog--last-editor-length "—")
               (or jetpacs-component-catalog--last-editor-preview "—"))
       :style "caption")))
    (jetpacs-component-panel
     "SINGLE LINE / ENTER"
     (list
      (jetpacs-editor
       "jpcatalog-editor-enter"
       :value "Press Enter"
       :on-enter (jetpacs-action "jpcatalog.editor-enter")
       :single-line t :publish-state t)
      (jetpacs-text
       (format "Accepted Enter actions: %d"
               jetpacs-component-catalog--editor-enter-count)
       :style "caption")))
    (jetpacs-component-catalog--sync-panel)
    (jetpacs-component-panel
     "READ-ONLY / DISABLED"
     (list
      (jetpacs-editor
       "jpcatalog-editor-read-only"
       :value "Read-only text remains selectable."
       :read-only t :min-lines 2 :max-lines 3)
      (jetpacs-editor
       "jpcatalog-editor-disabled"
       :value "Disabled editor"
       :enabled :json-false :min-lines 2 :max-lines 3)))
    (jetpacs-component-panel
     "CHROMELESS"
     (list
      (jetpacs-editor
       "jpcatalog-editor-chromeless"
       :value "Borderless local scratch surface"
       :chromeless t :min-lines 2 :max-lines 3)))
    (jetpacs-component-panel
     "LATER TIERS"
     (list
      (jetpacs-text
       "Completion, authoritative diagnostics, eldoc, and editor commands remain Phase 6 work; this synchronized fixture enables none of those riders."
       :style "caption")))
    (jetpacs-component-catalog--code-panel
     "(jetpacs-editor \"draft\"\n  :value \"(message \\\"Jetpacs\\\")\"\n  :on-save (jetpacs-action \"app.save\")\n  :syntax \"elisp\" :line-numbers t\n  :publish-state t)"
     "{\"t\":\"editor\",\"id\":\"draft\",\"value\":\"(message \\\"Jetpacs\\\")\",\"on_save\":{\"action\":\"app.save\"},\"syntax\":\"elisp\",\"line_numbers\":true,\"publish_state\":true}")
    :spacing 12 :content-padding 12)
   :back back))

(defun jetpacs-component-catalog--detail-screen (component back)
  "Build COMPONENT's detail screen using BACK navigation."
  (pcase component
    ("action" (jetpacs-component-catalog--action-screen back))
    ("choice" (jetpacs-component-catalog--choice-screen back))
    ("panel" (jetpacs-component-catalog--panel-screen back))
    ("text-field" (jetpacs-component-catalog--text-field-screen back))
    ("editor" (jetpacs-component-catalog--editor-screen back))
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

(defun jetpacs-component-catalog--remember-editor-value (value)
  "Retain only a bounded display preview and length for editor VALUE."
  (let ((single-line (string-replace "\n" "↵" value)))
    (setq jetpacs-component-catalog--last-editor-length (length value)
          jetpacs-component-catalog--last-editor-preview
          (truncate-string-to-width single-line 72 nil nil "…"))))

(defun jetpacs-component-catalog--on-editor-state (value)
  "Record one published non-secret local Editor VALUE without refreshing."
  (when (stringp value)
    (cl-incf jetpacs-component-catalog--editor-state-count)
    (jetpacs-component-catalog--remember-editor-value value)))

(defun jetpacs-component-catalog--on-editor-save (args params)
  "Record ARGS' local Editor save and refresh PARAMS' current screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--editor-save-count)
      (jetpacs-component-catalog--remember-editor-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-editor-enter (args params)
  "Record ARGS' single-line Editor value and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--editor-enter-count)
      (jetpacs-component-catalog--remember-editor-value value)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-component-catalog--on-sync-editor-save (args params)
  "Record ARGS' synchronized Editor value and refresh PARAMS' screen."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (cl-incf jetpacs-component-catalog--sync-save-count)
      (setq jetpacs-component-catalog--last-sync-preview
            (truncate-string-to-width
             (string-replace "\n" "↵" value) 72 nil nil "…"))
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defconst jetpacs-component-catalog--verbs
  '(("jpcatalog.open" . jetpacs-component-catalog--on-open)
    ("jpcatalog.activate" . jetpacs-component-catalog--on-activate)
    ("jpcatalog.choice" . jetpacs-component-catalog--on-choice)
    ("jpcatalog.text-change" . jetpacs-component-catalog--on-text-change)
    ("jpcatalog.text-submit" . jetpacs-component-catalog--on-text-submit)
    ("jpcatalog.secure-submit" . jetpacs-component-catalog--on-secure-submit)
    ("jpcatalog.editor-save" . jetpacs-component-catalog--on-editor-save)
    ("jpcatalog.editor-enter" . jetpacs-component-catalog--on-editor-enter)
    ("jpcatalog.sync-editor-save" .
     jetpacs-component-catalog--on-sync-editor-save))
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
    (jetpacs-defaction
     "jpcatalog.editor-save" #'jetpacs-component-catalog--on-editor-save
     :args '((:name value :type "text" :required t))
     :doc "Record and display one local Editor save value")
    (jetpacs-defaction
     "jpcatalog.editor-enter" #'jetpacs-component-catalog--on-editor-enter
     :args '((:name value :type "text" :required t))
     :doc "Record and display one single-line Editor enter value")
    (jetpacs-defaction
     "jpcatalog.sync-editor-save"
     #'jetpacs-component-catalog--on-sync-editor-save
     :args '((:name value :type "text" :required t))
     :doc "Record one synchronized Editor save admitted only while READY")
    (jetpacs-on-state-change
     "jpcatalog-editor-live" #'jetpacs-component-catalog--on-editor-state)
    (jetpacs-on-state-change
     "jpcatalog-editor-enter" #'jetpacs-component-catalog--on-editor-state)
    (jetpacs-chrome-define-root
     jetpacs-component-catalog-owner "home"
     #'jetpacs-component-catalog--home-screen))
  (add-hook 'jetpacs-ready-functions #'jetpacs-component-catalog--on-ready)
  (jetpacs-component-catalog--ensure-sync-buffer)
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
  (remove-hook 'jetpacs-ready-functions #'jetpacs-component-catalog--on-ready)
  (jetpacs-component-catalog--release-sync-buffer)
  (dolist (verb (mapcar #'car jetpacs-component-catalog--verbs))
    (jetpacs-undefaction verb))
  (jetpacs-on-state-change-clear "jpcatalog-editor-" "app:jpcatalog")
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
