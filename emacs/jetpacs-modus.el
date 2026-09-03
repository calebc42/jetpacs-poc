;;; jetpacs-modus.el --- Modus queries and settings screen -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Version-adaptive modus queries plus the Jetpacs settings screen and its
;; actions.  The query functions remain independent of a loaded modus library;
;; the screen half declares its actual Jetpacs dependencies and registers only
;; bounded settings actions.  `jetpacs-theme' requires this feature and
;; re-exports nothing (the names are already public).

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-chrome)
(require 'jetpacs-settings)
(require 'jetpacs-apps)
(require 'jetpacs-theme-picker)

;; Modus is bundled but version-adaptive: Emacs 30's 4.x and an installed 5.x
;; expose different optional helpers.  Every call below is guarded by
;; `fboundp'; these declarations keep warning-as-error compilation honest
;; without making the newer library a load-time dependency.
(declare-function modus-themes-get-color-value "ext:modus-themes"
                  (color &optional overrides theme))
(declare-function modus-themes-load-theme "ext:modus-themes" (theme))
(declare-function modus-themes-rotate "ext:modus-themes" (themes))
(declare-function modus-themes-toggle "ext:modus-themes" ())
(defvar modus-themes-to-rotate)
(defvar modus-themes-to-toggle)
(defvar jetpacs-theme-mode)

;;;; Modus-family provider registry

(defvar jetpacs-modus-theme-provider-links nil
  "Ordered list of (ORDER BUILDER . OWNER) Modus-family provider rows.
BUILDER is a nullary node builder.  This registry extends the Theme screen
without making Jetpacs name an optional downstream theme package.  Providers
belong here only when their themes implement the Modus semantic palette API.")

(defun jetpacs-modus-unregister-theme-provider (builder)
  "Remove every Theme-screen provider row registered with BUILDER."
  (setq jetpacs-modus-theme-provider-links
        (cl-remove builder jetpacs-modus-theme-provider-links :key #'cadr)))

(defun jetpacs-modus-register-theme-provider (order builder)
  "Register Modus-family provider row BUILDER at numeric ORDER.
Registration is idempotent by BUILDER.  When called inside
`with-jetpacs-owner', retain that owner with the contribution so tooling can
attribute the row to the downstream app that supplies it."
  (unless (numberp order)
    (signal 'wrong-type-argument (list 'numberp order)))
  (unless (functionp builder)
    (signal 'wrong-type-argument (list 'functionp builder)))
  (jetpacs-modus-unregister-theme-provider builder)
  (setq jetpacs-modus-theme-provider-links
        (sort (cons (cons order
                          (cons builder
                                (bound-and-true-p jetpacs-current-owner)))
                    jetpacs-modus-theme-provider-links)
              (lambda (a b) (< (car a) (car b))))))

(defun jetpacs-modus--theme-provider-nodes ()
  "Build the registered Modus-family provider rows in display order."
  (mapcar (lambda (entry) (funcall (cadr entry)))
          jetpacs-modus-theme-provider-links))

;;;; Modus queries (version-adaptive; public — JA-10's screen substrate)

(defun jetpacs-modus-available-p ()
  "Non-nil when the built-in modus themes are installed in this Emacs."
  (and (seq-some (lambda (theme)
                   (string-prefix-p "modus-" (symbol-name theme)))
                 (custom-available-themes))
       t))

(defun jetpacs-modus--ensure ()
  "Load the modus-themes library without enabling a theme; non-nil on success.
The library lives in the themes directory rather than on `load-path', so
`require-theme' is the reliable loader; a plain `require' covers the
on-load-path case, and `featurep' the case where a modus theme is
already active."
  (or (featurep 'modus-themes)
      (require 'modus-themes nil t)
      (and (ignore-errors (require-theme 'modus-themes t))
           (featurep 'modus-themes))))

(defun jetpacs-modus-themes ()
  "Selectable modus themes: the stock set, plus derivatives where supported."
  (cond ((fboundp 'modus-themes-get-all-known-themes)
         (modus-themes-get-all-known-themes))
        ((boundp 'modus-themes-items) modus-themes-items)))

(defun jetpacs-modus-current ()
  "The active modus theme symbol, or nil."
  (if (fboundp 'modus-themes-get-current-theme)
      (modus-themes-get-current-theme)
    (let ((known (jetpacs-modus-themes)))
      (seq-find (lambda (theme) (memq theme known)) custom-enabled-themes))))

(defun jetpacs-modus-dark-p (theme)
  "Non-nil when THEME reads as a dark modus theme.
Prefer the theme's own `:background-mode' property (set by 4.4's stock
themes and the 5.0 registry); fall back to the stock naming, where
every `vivendi' is dark and every `operandi' light."
  (let ((props (get theme 'theme-properties)))
    (if (plist-member props :background-mode)
        (eq (plist-get props :background-mode) 'dark)
      (and (string-match-p "vivendi" (symbol-name theme)) t))))

(defun jetpacs-modus-toggle ()
  "Toggle between the two `modus-themes-to-toggle' themes.
The desktop face of the `modus.toggle' action; interactively, 4.x's
`completing-read' fallback (when the toggle pair isn't two themes) is
fine — there is a user at the keyboard."
  (interactive)
  (if (and (jetpacs-modus--ensure) (fboundp 'modus-themes-toggle))
      (modus-themes-toggle)
    (message "Jetpacs: modus themes are not available in this Emacs")))

(defun jetpacs-modus--color (key &optional theme)
  (when (fboundp 'modus-themes-get-color-value)
    (let ((value (ignore-errors
                   (if theme
                       (modus-themes-get-color-value key nil theme)
                     (modus-themes-get-color-value key :with-overrides)))))
      (and (stringp value) value))))

;;;; Theme screen view

(defun jetpacs-modus--display-name (theme)
  (jetpacs-theme-picker-display-name "modus-" theme))

(defun jetpacs-modus--current-card (current)
  (jetpacs-theme-picker-current-card current
                                     :display-fn #'jetpacs-modus--display-name
                                     :dark-p-fn #'jetpacs-modus-dark-p
                                     :color-fn #'jetpacs-modus--color
                                     :mirror-action "modus.mirror"
                                     :theme-mode
                                     (and (boundp 'jetpacs-theme-mode)
                                          jetpacs-theme-mode)
                                     :none-label "No modus theme active"))

(defun jetpacs-modus--actions-row ()
  (let (buttons)
    (when (and (fboundp 'modus-themes-rotate)
               (boundp 'modus-themes-to-rotate))
      (push (jetpacs-button "Rotate"
                            (jetpacs-action "modus.rotate" :when-offline "drop")
                            :icon "autorenew" :variant "tonal")
            buttons))
    (when (and (fboundp 'modus-themes-toggle)
               (boundp 'modus-themes-to-toggle)
               (= (length modus-themes-to-toggle) 2))
      (push (jetpacs-button "Toggle"
                            (jetpacs-action "modus.toggle" :when-offline "drop")
                            :icon "brightness_6" :variant "tonal")
            buttons))
    (when buttons (apply #'jetpacs-row buttons))))

(defun jetpacs-modus--themes-section (current)
  (jetpacs-theme-picker-themes-section (jetpacs-modus-themes) current
                                       :dark-p-fn #'jetpacs-modus-dark-p
                                       :display-fn #'jetpacs-modus--display-name
                                       :color-fn #'jetpacs-modus--color
                                       :load-action "modus.load"))

(defconst jetpacs-modus--options
  '((modus-themes-bold-constructs    . "Bold keywords")
    (modus-themes-italic-constructs  . "Italic comments")
    (modus-themes-mixed-fonts        . "Mixed fonts in code")
    (modus-themes-variable-pitch-ui  . "Variable-pitch UI")
    (modus-themes-disable-other-themes . "Disable other themes on load")))

(defun jetpacs-modus--option-symbols ()
  (mapcar #'car jetpacs-modus--options))

(defun jetpacs-modus--style-section ()
  (cons
   (jetpacs-section-header "Modus Style")
   (mapcar (lambda (opt)
             (let ((sym (car opt)) (label (cdr opt)))
               (jetpacs-card
                (list (if (boundp sym)
                          (jetpacs-settings-item sym
                                                 :label label
                                                 :id-prefix "modus/"
                                                 :set-action "modus.set"
                                                 :reset-action "modus.reset")
                        (jetpacs-text (concat label " — not available") :style "caption"))))))
           jetpacs-modus--options)))

(defun jetpacs-modus--body ()
  (let ((current (jetpacs-modus-current)))
    (apply #'jetpacs-lazy-column
           (delq nil
                 (append
                  (list (jetpacs-section-header "Companion Theme")
                        ;; A person reads "Color scheme", not the variable.
                        (jetpacs-card (list (jetpacs-settings-item
                                             'jetpacs-theme-mode
                                             :label "Color scheme")))
                        (jetpacs-section-header "Modus Themes")
                        (jetpacs-modus--current-card current)
                        (jetpacs-modus--actions-row))
                  (jetpacs-modus--themes-section current)
                  (jetpacs-modus--style-section)
                  (jetpacs-modus--theme-provider-nodes)
                  (list (jetpacs-theme-picker-more-link "modus-themes")))))))

(defun jetpacs-modus--view (back)
  (jetpacs-chrome-screen
   "Theme"
   (if (jetpacs-modus--ensure)
       (jetpacs-modus--body)
     (jetpacs-column
      (jetpacs-text "The modus themes are not available in this Emacs.")))
   :back back))

(defun jetpacs-modus--reload (&rest _)
  (when-let* ((theme (jetpacs-modus-current)))
    (when (fboundp 'modus-themes-load-theme)
      (ignore-errors (modus-themes-load-theme theme)))))

(defun jetpacs-modus--action-toggle (_args params)
  "Toggle the configured Modus pair for action PARAMS.
Return `rejected' unless the non-interactive two-theme operation is available;
this keeps Modus's `completing-read' fallback outside the wire dispatch."
  (if (not (and (jetpacs-modus--ensure)
                (fboundp 'modus-themes-toggle)
                (boundp 'modus-themes-to-toggle)
                (= 2 (length modus-themes-to-toggle))))
      'rejected
    (condition-case err
        (progn
          (modus-themes-toggle)
          (jetpacs-app-defer-refresh params)
          'accepted)
      (error
       (jetpacs-shell-notify (error-message-string err)
                             (plist-get params :surface))
       'rejected))))

(defun jetpacs-modus--action-show (_args _params)
  "Push the Modus picker onto the Jetpacs settings surface."
  (jetpacs-chrome-push-screen jetpacs-settings-surface
                              "jetpacs-modus"
                              #'jetpacs-modus--view)
  'accepted)

(defun jetpacs-modus--action-load (args params)
  "Load the Modus theme named by ARGS and refresh action PARAMS' screen."
  (let* ((name (plist-get args :theme))
         (sym (and (stringp name) (intern-soft name))))
    (if (and sym (jetpacs-modus--ensure) (memq sym (jetpacs-modus-themes)))
        (condition-case err
            (modus-themes-load-theme sym)
          (error (jetpacs-shell-notify (error-message-string err)
                                       (plist-get params :surface))))
      (jetpacs-shell-notify (format "Unknown modus theme: %s" (or name "?"))
                            (plist-get params :surface)))
    (jetpacs-app-defer-refresh params)
    'accepted))

(defun jetpacs-modus--action-rotate (_args params)
  "Rotate the configured Modus themes and refresh action PARAMS' screen."
  (when (and (jetpacs-modus--ensure)
             (fboundp 'modus-themes-rotate)
             (boundp 'modus-themes-to-rotate))
    (condition-case err (modus-themes-rotate modus-themes-to-rotate)
      (error (jetpacs-shell-notify (error-message-string err)
                                   (plist-get params :surface)))))
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun jetpacs-modus--action-set (args params)
  "Apply the allowlisted Modus option in ARGS and refresh action PARAMS."
  (let* ((name (plist-get args :name))
         (sym (and (stringp name) (intern-soft name))))
    (if (memq sym (jetpacs-modus--option-symbols))
        (progn
          (jetpacs-settings-apply-wire sym (plist-get args :value))
          (jetpacs-modus--reload))
      (jetpacs-shell-notify (format "%s is not a modus option" (or name "?"))
                            (plist-get params :surface)))
    (jetpacs-app-defer-refresh params)
    'accepted))

(defun jetpacs-modus--action-reset (args params)
  "Reset the allowlisted Modus option in ARGS and refresh action PARAMS."
  (let* ((name (plist-get args :name))
         (sym (and (stringp name) (intern-soft name))))
    (when (memq sym (jetpacs-modus--option-symbols))
      (jetpacs-settings-reset sym)
      (jetpacs-modus--reload))
    (jetpacs-app-defer-refresh params)
    'accepted))

(defun jetpacs-modus--action-mirror (_args params)
  "Enable companion theme mirroring and refresh action PARAMS' screen."
  (when (boundp 'jetpacs-theme-mode)
    (jetpacs-settings-apply 'jetpacs-theme-mode 'mirror))
  (jetpacs-app-defer-refresh params)
  'accepted)

;;;; Actions

(with-jetpacs-owner "jetpacs.settings"
  (jetpacs-defaction "modus.show" #'jetpacs-modus--action-show
                     :doc "Open the Modus theme picker")

  (jetpacs-defaction "modus.load" #'jetpacs-modus--action-load
                     :args '((:name theme :type "text" :required t))
                     :doc "Load an allowlisted Modus theme")

  (jetpacs-defaction "modus.toggle" #'jetpacs-modus--action-toggle
                     :doc "Toggle the configured two-theme Modus pair")

  (jetpacs-defaction "modus.rotate" #'jetpacs-modus--action-rotate
                     :doc "Rotate through the configured Modus themes")

  (jetpacs-defaction "modus.set" #'jetpacs-modus--action-set
                     :args '((:name name :type "text" :required t)
                             (:name value :required t))
                     :doc "Set an allowlisted Modus style option")

  (jetpacs-defaction "modus.reset" #'jetpacs-modus--action-reset
                     :args '((:name name :type "text" :required t))
                     :doc "Reset an allowlisted Modus style option")

  (jetpacs-defaction "modus.mirror" #'jetpacs-modus--action-mirror
                     :doc "Mirror the current Emacs theme to the companion"))

;;;; Registration

(dolist (sym (jetpacs-modus--option-symbols))
  (jetpacs-settings-watch-toggle
   sym (concat "modus/" (symbol-name sym)) #'jetpacs-modus--reload))

(provide 'jetpacs-modus)
;;; jetpacs-modus.el ends here
