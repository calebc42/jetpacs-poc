;;; jetpacs-org-capture.el --- Native Org capture sheets for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The Org Mode app's capture engine: one picker -> form sheet chain, Android
;; share intake, and Org Protocol capture.  This module owns native Emacs Org
;; behavior and durable action names.  Downstream apps may place capture
;; affordances wherever their UX calls for them; no downstream presentation
;; package is part of this module's dependency closure.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'org)
(require 'org-protocol)
(require 'ebp)
(require 'ebp-org)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)
(require 'jetpacs-shell)

(defconst jetpacs-org-capture-owner "org-mode"
  "Owner of the native Org capture engine and its durable verbs.")

(defvar jetpacs-org-capture-enabled t
  "Non-nil when the native Org capture verbs are registered.
The independent flag exists only for the ordered rollback window of the
capture-owner cutover.")

;;;; Shared-in content

(defvar jetpacs-org-capture--shared-text nil
  "Body text shared from another app, pending the next capture submit.")

(defvar jetpacs-org-capture--shared-subject nil
  "Shared subject used to seed the capture Headline field.")

(defvar jetpacs-org-capture--protocol nil
  "Normalized Org Protocol parameters pending capture, or nil.")

(defun jetpacs-org-capture--clear-shared ()
  "Forget the pending shared-in and Org Protocol payload."
  (setq jetpacs-org-capture--shared-text nil
        jetpacs-org-capture--shared-subject nil
        jetpacs-org-capture--protocol nil))

;;;; The one live sheet

(defvar jetpacs-org-capture--dialog nil
  "The live capture sheet, as (:request-id ID), or nil.
One slot is intentional: a new intake retires an older capture sheet instead
of stacking another one.")

(defun jetpacs-org-capture-dialog-close ()
  "Retire the live capture sheet, if any.
The slot is cleared before rpc.cancel so an asynchronous 1301 conclusion from
the abandoned sheet cannot clear a successor's newly stashed share payload."
  (let ((sheet jetpacs-org-capture--dialog))
    (setq jetpacs-org-capture--dialog nil)
    (when-let* ((client (jetpacs-client))
                (request-id (plist-get sheet :request-id)))
      (ignore-errors (ebp-client-abandon client request-id)))))

(defun jetpacs-org-capture--show-dialog (id spec on-submit)
  "Show SPEC as bottom sheet ID; call ON-SUBMIT with its submitted result."
  (when-let* ((client (jetpacs-client)))
    (jetpacs-org-capture-dialog-close)
    (let* ((self (list :request-id nil))
           (request-id
            (ebp-client-dialog-show
             client id spec
             :style "sheet"
             :callback
             (lambda (status result _error)
               ;; Identity, not merely request id, protects a successor from
               ;; the abandoned sheet's delayed 1301 conclusion.
               (when (eq jetpacs-org-capture--dialog self)
                 (setq jetpacs-org-capture--dialog nil)
                 (if (equal status "submitted")
                     (funcall on-submit result)
                   (jetpacs-org-capture--clear-shared)))))))
      (when request-id
        (plist-put self :request-id request-id)
        (setq jetpacs-org-capture--dialog self)))))

;;;; Templates and sheet bodies

(defun jetpacs-org-capture-templates ()
  "Return selectable, non-prefix Org capture template plists."
  (cl-remove-if-not
   (lambda (template)
     (let ((entry (assoc (plist-get template :key) org-capture-templates)))
       (and entry (> (length entry) 4))))
   (ebp-org-capture-templates)))

(defun jetpacs-org-capture--template (key)
  "Return the current selectable capture template for KEY, or nil."
  (cl-find key (jetpacs-org-capture-templates)
           :key (lambda (template) (plist-get template :key))
           :test #'equal))

(defun jetpacs-org-capture--preview-text ()
  "Return a short pending-intake preview, or nil."
  (or jetpacs-org-capture--shared-text
      (and jetpacs-org-capture--protocol
           (or (plist-get jetpacs-org-capture--protocol :body)
               (plist-get jetpacs-org-capture--protocol :title)
               (plist-get jetpacs-org-capture--protocol :url)))))

(defun jetpacs-org-capture--picker-body (templates)
  "Build the template-picker sheet body over TEMPLATES."
  (let ((preview (jetpacs-org-capture--preview-text)))
    (apply #'jetpacs-column
           (append
            (list (jetpacs-text "Quick Capture" :style "title")
                  (jetpacs-text "Select a template:" :style "caption"))
            (when preview
              (list (jetpacs-card
                     (jetpacs-text
                      (truncate-string-to-width preview 200 nil nil "…")
                      :style "caption"))))
            (mapcar
             (lambda (template)
               (jetpacs-button
                (jetpacs-scalar-text
                 (or (plist-get template :description)
                     (plist-get template :key)))
                (jetpacs-dialog-submit :value (plist-get template :key))
                :variant "outlined"))
             templates)
            (list (jetpacs-button "Cancel" (jetpacs-dialog-dismiss)
                                  :variant "text")
                  :spacing 8)))))

(defun jetpacs-org-capture--field-pairs (template)
  "Return ordered (PROMPT . FIELD-ID) pairs for TEMPLATE."
  (mapcar (lambda (prompt)
            (cons prompt (jetpacs-wire-id "capf" prompt)))
          (append (plist-get template :prompts) nil)))

(defun jetpacs-org-capture--form-body (template)
  "Build the capture field-form sheet body for TEMPLATE."
  (let ((pairs (jetpacs-org-capture--field-pairs template)))
    (apply #'jetpacs-column
           (append
            (list
             (jetpacs-text
              (format "Capture: %s"
                      (jetpacs-scalar-text
                       (or (plist-get template :description)
                           (plist-get template :key))))
              :style "title"))
            (mapcar
             (lambda (cell)
               (jetpacs-text-input
                (cdr cell)
                :label (car cell)
                :value (and (equal (car cell) "Headline")
                            jetpacs-org-capture--shared-subject)))
             pairs)
            (list
             (jetpacs-row
              (jetpacs-spacer :weight 1)
              (jetpacs-button "Cancel" (jetpacs-dialog-dismiss)
                              :variant "text")
              (jetpacs-spacer :width 8)
              (jetpacs-button
               "Capture"
               (jetpacs-dialog-submit :capture-fields (mapcar #'cdr pairs))))
             :spacing 8)))))

;;;; Org Protocol

(defun jetpacs-org-capture--protocol-info (text)
  "Return Org Protocol capture info encoded by TEXT, or nil.
Both modern query-string and legacy slash-separated capture URLs are parsed by
the Org version shipped with Emacs."
  (when (and (stringp text)
             (string-match
              "\\`org-protocol:/+capture\\(:/+\\|/*\\?\\)" text))
    (let ((separator (match-string 1 text))
          (data (substring text (match-end 0))))
      (if (string-suffix-p "?" separator)
          (org-protocol-parse-parameters data t)
        data))))

(defun jetpacs-org-capture--protocol-parts (info)
  "Normalize built-in Org Protocol capture INFO to a plist."
  (pcase (org-protocol-parse-parameters info)
    ((let `(,(pred keywordp) . ,_) info) info)
    (parts
     (let ((keys (if (= 1 (length (car parts)))
                     '(:template :url :title :body)
                   '(:url :title :body))))
       (org-protocol-assign-parameters parts keys)))))

(defun jetpacs-org-capture--protocol-values (template)
  "Return headless field values for protocol TEMPLATE.
Org Protocol's title supplies the free-form Headline field.  Any other prompt
requires the sheet form and therefore is not returned by this helper."
  (mapcar
   (lambda (prompt)
     (cons prompt
           (if (equal prompt "Headline")
               (or (plist-get jetpacs-org-capture--protocol :title) "")
             "")))
   (append (plist-get template :prompts) nil)))

(defun jetpacs-org-capture--protocol-headless-p (template)
  "Non-nil when protocol data fully supplies TEMPLATE's phone fields."
  (cl-every (lambda (prompt) (equal prompt "Headline"))
            (append (plist-get template :prompts) nil)))

(defun jetpacs-org-capture--run-engine (key values)
  "Run capture KEY with VALUES under the pending intake context."
  (if (null jetpacs-org-capture--protocol)
      (ebp-org-capture-run key values jetpacs-org-capture--shared-text)
    (let* ((parts jetpacs-org-capture--protocol)
           (url (and (plist-get parts :url)
                     (org-protocol-sanitize-uri (plist-get parts :url))))
           (title (or (plist-get parts :title) ""))
           (body (or (plist-get parts :body) ""))
           (type (and url (string-match "^\\([a-z]+\\):" url)
                      (match-string 1 url)))
           (orglink (if (null url)
                        title
                      (org-link-make-string
                       url (or (org-string-nw-p title) url))))
           (org-capture-link-is-already-stored t))
      (when url
        (push (list url title) org-stored-links))
      (org-link-store-props :type type
                            :link url
                            :description title
                            :annotation orglink
                            :initial body
                            :query parts)
      (ebp-org-capture-run key values))))

;;;; Picker -> form -> durable capture

(defun jetpacs-org-capture--defer-refresh (params)
  "Re-push PARAMS' surface after the dialog conclusion returns."
  (let ((surface (plist-get params :surface)))
    (jetpacs-flow-continue
     (lambda ()
       (ignore-errors (jetpacs-shell-push surface))))))

(defun jetpacs-org-capture--submit-values (key values params)
  "Run capture KEY with VALUES; celebrate only after durable success."
  (condition-case err
      (progn
        (jetpacs-org-capture--run-engine key values)
        (jetpacs-org-capture--clear-shared)
        ;; A capture may write a file covered by any consumer's memo.  This is
        ;; intentionally a whole-cache invalidation, never owner-scoped.
        (ebp-org-cache-invalidate)
        (jetpacs-shell-notify "Captured ✓")
        (jetpacs-org-capture--defer-refresh params)
        t)
    (error
     (message "jetpacs-org-capture: capture failed: %s"
              (jetpacs-error-label err))
     (jetpacs-org-capture--clear-shared)
     (jetpacs-toast "Capture failed")
     nil)))

(defun jetpacs-org-capture--submit (key pairs fields params)
  "Map concluded FIELDS through PAIRS and submit capture KEY."
  (let ((values
         (mapcar
          (lambda (cell)
            (let ((value
                   (plist-get fields (intern (concat ":" (cdr cell))))))
              (cons (car cell) (if (stringp value) value ""))))
          pairs)))
    (jetpacs-org-capture--submit-values key values params)))

(defun jetpacs-org-capture--show-form (template params)
  "Show TEMPLATE's field form; its conclusion runs the capture."
  (let ((key (plist-get template :key))
        (pairs (jetpacs-org-capture--field-pairs template)))
    (jetpacs-org-capture--show-dialog
     "org-capture-form"
     (jetpacs-org-capture--form-body template)
     (lambda (result)
       (jetpacs-org-capture--submit
        key pairs (plist-get result :fields) params)))))

(defun jetpacs-org-capture--show-picker (params)
  "Show the filtered template picker and revalidate its conclusion."
  (let ((templates (jetpacs-org-capture-templates)))
    (if (null templates)
        (progn
          (jetpacs-org-capture--clear-shared)
          (jetpacs-shell-notify "No Org capture templates are configured"))
      (jetpacs-org-capture--show-dialog
       "org-capture-picker"
       (jetpacs-org-capture--picker-body templates)
       (lambda (result)
         (let ((template
                (jetpacs-org-capture--template (plist-get result :value))))
           (if (null template)
               (progn
                 (jetpacs-toast "That template no longer exists")
                 (jetpacs-org-capture--clear-shared))
             (jetpacs-org-capture--show-form template params))))))))

(defun jetpacs-org-capture--start (params)
  "Start the pending normal or Org Protocol capture for PARAMS."
  ;; Every new intake retires an older picker first, including a fully
  ;; specified protocol URL that can commit without showing a successor sheet.
  (jetpacs-org-capture-dialog-close)
  (if (null jetpacs-org-capture--protocol)
      (jetpacs-org-capture--show-picker params)
    (condition-case err
        (let* ((requested
                (or (plist-get jetpacs-org-capture--protocol :template)
                    org-protocol-default-template-key))
               (template
                (and (stringp requested)
                     (not (string-empty-p requested))
                     (jetpacs-org-capture--template requested))))
          (cond
           ((and (stringp requested) (not (string-empty-p requested))
                 (null template))
            (user-error "Org Protocol requested an unknown capture template"))
           ((null template)
            (jetpacs-org-capture--show-picker params))
           ((jetpacs-org-capture--protocol-headless-p template)
            (jetpacs-org-capture--submit-values
             (plist-get template :key)
             (jetpacs-org-capture--protocol-values template)
             params))
           (t
            (jetpacs-org-capture--show-form template params))))
      (error
       (message "jetpacs-org-capture: protocol intake failed: %s"
                (jetpacs-error-label err))
       (jetpacs-org-capture--clear-shared)
       (jetpacs-toast "Capture failed")))))

;;;; Handlers and registration

(defun jetpacs-org-capture--on-show (_args params)
  "Open the Org capture picker sheet."
  (if (null (jetpacs-client))
      'rejected
    (jetpacs-flow-continue
     (lambda () (jetpacs-org-capture--start params)))
    'accepted))

(defun jetpacs-org-capture--on-share (args params)
  "Stash shared ARGS and start capture, including Org Protocol URLs."
  (let ((text (plist-get args :text))
        (subject (plist-get args :subject)))
    (setq jetpacs-org-capture--protocol nil
          jetpacs-org-capture--shared-text
          (and (stringp text)
               (not (string-empty-p (string-trim text)))
               (string-trim text))
          jetpacs-org-capture--shared-subject
          (and (stringp subject)
               (not (string-empty-p (string-trim subject)))
               (string-trim subject)))
    (unless jetpacs-org-capture--shared-text
      (setq jetpacs-org-capture--shared-text
            jetpacs-org-capture--shared-subject))
    (when-let* ((info (jetpacs-org-capture--protocol-info
                       jetpacs-org-capture--shared-text)))
      (setq jetpacs-org-capture--protocol
            (jetpacs-org-capture--protocol-parts info)
            jetpacs-org-capture--shared-text nil
            jetpacs-org-capture--shared-subject
            (or (plist-get jetpacs-org-capture--protocol :title)
                jetpacs-org-capture--shared-subject))))
  ;; The stash is deliberately written before this guard: a refused share
  ;; remains available to the next connected capture presentation.
  (if (null (jetpacs-client))
      'rejected
    (jetpacs-flow-continue
     (lambda () (jetpacs-org-capture--start params)))
    'accepted))

(defconst jetpacs-org-capture--verbs
  '("org.capture.show" "share.text" "org.capture.share" "org-mode.capture")
  "Durable capture verbs plus the temporary Org Mode deprecation alias.")

(defun jetpacs-org-capture-register ()
  "Register or disable the native capture verbs according to the rollout flag."
  (if (not jetpacs-org-capture-enabled)
      (jetpacs-org-capture-unregister)
    (with-jetpacs-owner jetpacs-org-capture-owner
      ;; Global intake affordance: capture taps originate on any app's screen
      ;; and on durable descriptors.  No guest screen exists at tap time for
      ;; S4 delegation, while a forwarding verb would create two names for one
      ;; action; honest :any-surface scope is the only non-rejecting D1 shape.
      (jetpacs-defaction
       "org.capture.show" #'jetpacs-org-capture--on-show
       :any-surface t
       :doc "Global Org capture intake; dialogs continue without a surface")
      ;; A share is attributed by the Companion, not an app surface.  Both the
      ;; current name and the durable pre-rename replay alias stay global.
      (jetpacs-defaction "share.text" #'jetpacs-org-capture--on-share
                         :any-surface t
                         :doc "Capture text shared from another app")
      (jetpacs-defaction "org.capture.share" #'jetpacs-org-capture--on-share
                         :any-surface t
                         :doc "Replay-compatible Org share intake")
      ;; One-release receipt insurance for the old Org Mode home action.
      (jetpacs-defaction "org-mode.capture" #'jetpacs-org-capture--on-show
                         :doc "Deprecated alias for org.capture.show")))
  t)

(defun jetpacs-org-capture-unregister ()
  "Drop this module's capture verbs, live sheet, and pending intake."
  ;; This is the canonical owner, not an optional fallback.  Registration and
  ;; teardown are therefore unconditional; the old deferential owner/handler
  ;; dance is intentionally gone.
  (dolist (name jetpacs-org-capture--verbs)
    (jetpacs-undefaction name))
  (jetpacs-org-capture-dialog-close)
  (jetpacs-org-capture--clear-shared)
  t)

(provide 'jetpacs-org-capture)
;;; jetpacs-org-capture.el ends here
