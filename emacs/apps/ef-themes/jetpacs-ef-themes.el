;;; jetpacs-ef-themes.el --- Ef-themes control screen -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A Jetpacs extension screen for Prot's ef-themes — the colorful companion
;; to the built-in Modus themes.  The provider is optional, while the screen
;; composes the generic `jetpacs-theme-picker' scaffold.  It offers:
;;
;;  - a light/dark grouped picker, each row previewing a theme's
;;    background and identity accent as swatches; the active theme is
;;    marked, a tap loads another (`ef-themes-load-theme');
;;  - the current theme's palette strip;
;;  - "Random", "Random dark", "Random light" — ef-themes' surprise-me
;;    loaders;
;;  - the everyday style options (bold, italic, mixed fonts,
;;    variable-pitch UI) as switches, each reloading the theme so the
;;    change shows at once.
;;
;; ef-themes 2.0+ are built on the modus 5.0 palette API, so an ef theme
;; is a registered modus derivative: the theme mirror
;; (`jetpacs-theme-mode' `mirror') already reflects it faithfully onto
;; the companion, reading its semantic roles.  When mirroring is on,
;; switching a theme here re-pushes it; when it is off, a one-tap
;; "Mirror on phone" flips it.
;;
;; Everything reads ef-themes through its public API, so the screen tracks
;; the installed provider version and degrades to the native package browser
;; when it is absent.  The Settings row is the only cross-surface opener;
;; once pushed as a sanctioned guest, its controls remain owner-scoped.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-apps)
(require 'jetpacs-settings)
(require 'jetpacs-theme)
(require 'jetpacs-theme-picker)
(require 'jetpacs-package-browser)

;; ef-themes is an optional runtime dependency loaded on demand; every
;; use is guarded, and the `ext:' pseudo-file keeps the error-on-warn
;; byte-compile honest with the package absent.
(declare-function ef-themes-get-color-value "ext:ef-themes"
                  (color &optional with-overrides theme))
(declare-function ef-themes-load-theme "ext:ef-themes" (theme &optional hook))
(declare-function ef-themes-load-random "ext:ef-themes" (&optional variant))
(declare-function ef-themes-load-random-dark "ext:ef-themes" ())
(declare-function ef-themes-load-random-light "ext:ef-themes" ())
(defvar ef-themes-items)

;;;; Availability and loading

(defun jetpacs-ef-themes--available-p ()
  "Non-nil when the ef-themes package is installed in this Emacs."
  (and (seq-some (lambda (theme)
                   (string-prefix-p "ef-" (symbol-name theme)))
                 (custom-available-themes))
       t))

(defun jetpacs-ef-themes--ensure ()
  "Load the ef-themes library; non-nil on success.
ef-themes is a package, so a plain `require' finds it once the package
system has initialised; `require-theme' is the fallback for the case
where only its theme directory is on the load path."
  (or (featurep 'ef-themes)
      (require 'ef-themes nil t)
      (and (ignore-errors (require-theme 'ef-themes t))
           (featurep 'ef-themes))))

;;;; Theme queries

(defun jetpacs-ef-themes--themes ()
  "The list of selectable ef themes."
  (and (boundp 'ef-themes-items) ef-themes-items))

(defun jetpacs-ef-themes--current ()
  "The active ef theme symbol, or nil."
  (let ((known (jetpacs-ef-themes--themes)))
    (seq-find (lambda (theme) (memq theme known)) custom-enabled-themes)))

(defun jetpacs-ef-themes--dark-p (theme)
  "Non-nil when THEME is a dark ef theme.
ef derivatives register a `:background-mode' theme property (the modus
5.0 API), so this needs no name-guessing."
  (eq (plist-get (get theme 'theme-properties) :background-mode) 'dark))

(defun jetpacs-ef-themes--color (key &optional theme)
  "Hex value of ef palette KEY for THEME (or the current theme), or nil."
  (when (fboundp 'ef-themes-get-color-value)
    (let ((value (ignore-errors
                   (if theme
                       (ef-themes-get-color-value key nil theme)
                     (ef-themes-get-color-value key :with-overrides)))))
      (and (stringp value) value))))

;;;; View sections (the shared scaffold, instantiated for ef)

(defun jetpacs-ef-themes--display-name (theme)
  "A human-friendly label for THEME: drop the `ef-' prefix, then
title-case, so `ef-melissa-dark' reads as \"Melissa Dark\"."
  (jetpacs-theme-picker-display-name "ef-" theme))

(defun jetpacs-ef-themes--current-card (current)
  "The header card: the active theme's name, polarity, palette, mirror status."
  (jetpacs-theme-picker-current-card current
                                       :display-fn #'symbol-name
                                       :dark-p-fn #'jetpacs-ef-themes--dark-p
                                       :color-fn #'jetpacs-ef-themes--color
                                       :mirror-action "ef.mirror"
                                       :none-label "No ef theme active"))

(defun jetpacs-ef-themes--actions-row ()
  "The surprise-me loaders ef-themes is known for."
  (jetpacs-row
   (jetpacs-button "Random" (jetpacs-action "ef.random")
                   :icon "shuffle" :variant "tonal")
   (jetpacs-button "Random dark" (jetpacs-action "ef.random-dark")
                   :icon "dark_mode" :variant "tonal")
   (jetpacs-button "Random light" (jetpacs-action "ef.random-light")
                   :icon "light_mode" :variant "tonal")))

(defun jetpacs-ef-themes--themes-section (current)
  "The theme picker: cards grouped Light then Dark."
  (jetpacs-theme-picker-themes-section (jetpacs-ef-themes--themes) current
                                         :dark-p-fn #'jetpacs-ef-themes--dark-p
                                         :display-fn #'jetpacs-ef-themes--display-name
                                         :color-fn #'jetpacs-ef-themes--color
                                         :load-action "ef.load"))

(defconst jetpacs-ef-themes--options
  '((ef-themes-bold-constructs    . "Bold keywords")
    (ef-themes-italic-constructs  . "Italic comments")
    (ef-themes-mixed-fonts        . "Mixed fonts in code")
    (ef-themes-variable-pitch-ui  . "Variable-pitch UI"))
  "Ef style options exposed as switches, each with a friendly label.
Presence here is what authorizes `ef.option' for a symbol.")

(defun jetpacs-ef-themes--style-section ()
  "The style options as switch cards.
ef-themes' options carry no reified `custom-type', so the switch
renders directly rather than through `jetpacs-settings-item' (which
classifies by type); each switch re-seeds `:checked' from the live
variable every render (S2) and dispatches `ef.option' on change."
  (cons
   (jetpacs-section-header "Style")
   (mapcar (lambda (opt)
             (let ((sym (car opt)) (label (cdr opt)))
               (jetpacs-card
                (if (boundp sym)
                    (jetpacs-switch (concat "ef-opt/" (symbol-name sym))
                                    :checked (jetpacs-bool (symbol-value sym))
                                    :label label
                                    :on-change
                                    (jetpacs-action
                                     "ef.option"
                                     :args (list :name (symbol-name sym))))
                  (jetpacs-text (concat label " — not available")
                                :style "caption")))))
           jetpacs-ef-themes--options)))

(defun jetpacs-ef-themes--body ()
  "The screen body, assuming the ef-themes library is loaded."
  (let ((current (jetpacs-ef-themes--current)))
    (apply #'jetpacs-lazy-column
           (delq nil
                 (append
                  (list (jetpacs-ef-themes--current-card current)
                        (jetpacs-ef-themes--actions-row))
                  (jetpacs-ef-themes--themes-section current)
                  (jetpacs-ef-themes--style-section)
                  (list (jetpacs-theme-picker-more-link "ef-themes")))))))

(defun jetpacs-ef-themes--not-installed ()
  "The ef-themes-absent placeholder.
The package browser is the upstream installation path; this extension does
not acquire a downstream package policy merely to fetch its provider."
  (let ((available (gethash "packages.show" jetpacs-action-handlers)))
    (jetpacs-empty-state
     :icon "colorize"
     :title "ef-themes isn't installed yet"
     :caption "Open Packages, refresh the archives if needed, then install ef-themes."
     :action-label (when available "Open Packages")
     :on-tap (when available (jetpacs-action "packages.show")))))

(defun jetpacs-ef-themes-screen (back)
  "The pushed Ef Themes screen; back returns to wherever the user was."
  (jetpacs-chrome-screen
   "Ef Themes"
   (if (jetpacs-ef-themes--ensure)
       (jetpacs-ef-themes--body)
     (jetpacs-ef-themes--not-installed))
   :back back))

;;;; Live re-apply

(defun jetpacs-ef-themes--reload (&rest _)
  "Reload the active ef theme so a just-changed option takes effect.
The reload also drives `enable-theme-functions', re-pushing the mirror
when `jetpacs-theme-mode' is `mirror'.  Hook-safe arity: doubles as a
`jetpacs-settings-apply' after-set."
  (when-let* ((theme (jetpacs-ef-themes--current)))
    (when (fboundp 'ef-themes-load-theme)
      (ignore-errors (ef-themes-load-theme theme)))))

;;;; Handlers (S4 — every one answers accepted/stale/rejected)

(defun jetpacs-ef-themes--on-show (_args params)
  "Push the Ef Themes screen onto the tapped surface."
  (let ((surface (or (plist-get params :surface)
                     (jetpacs-shell-surface-for
                      jetpacs-settings-surface))))
    (jetpacs-flow-continue
     (lambda ()
       ;; A deferred `jetpacs-chrome-push-screen' must catch its own
       ;; re-signal or a refused gate dies in a timer.
       (condition-case err
           (jetpacs-chrome-push-screen surface "jetpacs-ef-themes"
                                       #'jetpacs-ef-themes-screen)
         (error (message "jetpacs-ef-themes: push failed: %s"
                         (jetpacs-error-label err))))))
    'accepted))

(defun jetpacs-ef-themes--on-load (args params)
  "Load the ef theme named by `:theme'."
  (let* ((name (plist-get args :theme))
         (sym (and (stringp name) (intern-soft name)))
         (surface (plist-get params :surface)))
    (cond
     ((not (stringp name)) 'rejected)
     ((not (jetpacs-ef-themes--ensure))
      (jetpacs-shell-notify "ef-themes is not installed" surface)
      'rejected)
     ((not (and sym (memq sym (jetpacs-ef-themes--themes))))
      (jetpacs-shell-notify (format "Unknown ef theme: %s" name) surface)
      'rejected)
     (t
      (condition-case err
          (progn
            (ef-themes-load-theme sym)
            (jetpacs-app-defer-refresh params)
            'accepted)
        (error
         (jetpacs-shell-notify (format "Ef theme: %s"
                                       (jetpacs-error-label err))
                               surface)
         'rejected))))))

(defun jetpacs-ef-themes--surprise (loader params)
  "Run surprise-me LOADER (an ef-themes random function) and refresh.
The SPEC 14.4 status for the three random verbs: `rejected' when the
package (or this version's LOADER) is absent or the load signals —
never a swallowed `accepted' (the G7 engine-wrapper lesson)."
  (if (not (and (jetpacs-ef-themes--ensure) (fboundp loader)))
      (progn
        (jetpacs-shell-notify "ef-themes is not installed"
                              (plist-get params :surface))
        'rejected)
    (condition-case err
        (progn
          (funcall loader)
          (jetpacs-app-defer-refresh params)
          'accepted)
      (error
       (jetpacs-shell-notify (format "Ef theme: %s" (jetpacs-error-label err))
                             (plist-get params :surface))
       'rejected))))

(defun jetpacs-ef-themes--on-random (_args params)
  (jetpacs-ef-themes--surprise 'ef-themes-load-random params))

(defun jetpacs-ef-themes--on-random-dark (_args params)
  (jetpacs-ef-themes--surprise 'ef-themes-load-random-dark params))

(defun jetpacs-ef-themes--on-random-light (_args params)
  (jetpacs-ef-themes--surprise 'ef-themes-load-random-light params))

(defun jetpacs-ef-themes--on-mirror (_args params)
  "Flip the companion into mirror mode.
`jetpacs-settings-apply' validates against the defcustom's choice type
and persists; the mode's own `:set' pushes the current theme on a live
connection."
  (if (jetpacs-settings-apply 'jetpacs-theme-mode 'mirror)
      (progn (jetpacs-app-defer-refresh params)
             'accepted)
    'rejected))

(defun jetpacs-ef-themes--on-option (args params)
  "Set the style option named by `:name' to the switch's injected `:value'."
  (let* ((name (plist-get args :name))
         (sym (and (stringp name) (intern-soft name)))
         (value (plist-get args :value)))
    (cond
     ((not (and sym (assq sym jetpacs-ef-themes--options))) 'rejected)
     ((not (memq value '(t :json-false))) 'rejected)
     ((not (boundp sym))
      (jetpacs-shell-notify "ef-themes is not installed"
                            (plist-get params :surface))
      'rejected)
     ((jetpacs-settings-apply sym (eq value t) #'jetpacs-ef-themes--reload)
      (jetpacs-app-defer-refresh params)
      'accepted)
     (t 'rejected))))

;;;; Registration

(defconst jetpacs-ef-themes--verbs
  '("ef.show" "ef.load" "ef.random" "ef.random-dark" "ef.random-light"
    "ef.mirror" "ef.option")
  "The verbs this module owns, for the register/unregister sweep.")

(defun jetpacs-ef-themes--settings-link ()
  "The Settings-root satellite row leading to the Ef Themes screen.
Satellite screens live in Settings, not the drawer (the drawer-UX
rule, unchanged from v1)."
  (jetpacs-chrome-row "Ef Themes"
                      :subtitle "Pick, preview, and tune the colorful ef-themes"
                      :icon "colorize"
                      :on-tap (jetpacs-action "ef.show")
                      :key "jetpacs-ef-themes-link"))

(defun jetpacs-ef-themes-register ()
  "Register the ef verbs and the Settings satellite link.
Called by the Jetpacs composition root.  Idempotent: re-registration replaces
handlers in place and the link is re-added exactly once."
  (with-jetpacs-owner "jetpacs.ef"
    ;; This one action is emitted by the Settings root before the guest screen
    ;; exists.  The push sanctions that guest; every inner action below then
    ;; passes through screen-lifetime delegation instead of a permanent grant.
    (jetpacs-defaction "ef.show" #'jetpacs-ef-themes--on-show
                       :any-surface t
                       :doc "Push the Ef Themes screen")
    (jetpacs-defaction "ef.load" #'jetpacs-ef-themes--on-load
                       :doc "Load the named ef theme")
    (jetpacs-defaction "ef.random" #'jetpacs-ef-themes--on-random
                       :doc "Load a random ef theme")
    (jetpacs-defaction "ef.random-dark" #'jetpacs-ef-themes--on-random-dark
                       :doc "Load a random dark ef theme")
    (jetpacs-defaction "ef.random-light" #'jetpacs-ef-themes--on-random-light
                       :doc "Load a random light ef theme")
    (jetpacs-defaction "ef.mirror" #'jetpacs-ef-themes--on-mirror
                       :doc "Mirror the Emacs theme onto the companion")
    (jetpacs-defaction "ef.option" #'jetpacs-ef-themes--on-option
                       :doc "Set an ef style option from its switch")
    (jetpacs-settings-remove-link #'jetpacs-ef-themes--settings-link)
    ;; Keep the optional provider close to the other appearance controls.
    (jetpacs-settings-add-link 81 #'jetpacs-ef-themes--settings-link)))

(defun jetpacs-ef-themes-unregister ()
  "Drop the ef verbs and the Settings satellite link."
  (dolist (name jetpacs-ef-themes--verbs)
    (jetpacs-undefaction name))
  (jetpacs-settings-remove-link #'jetpacs-ef-themes--settings-link))

;;;###autoload
(defun jetpacs-ef-themes-open ()
  "Open the Ef Themes screen on the connected phone.
Disconnected, the push is kept and renders on the next connection —
chrome keeps the stack mutation; nil from the push is not failure."
  (interactive)
  (with-jetpacs-owner "jetpacs.ef"
    (jetpacs-chrome-push-screen jetpacs-settings-surface
                                "jetpacs-ef-themes"
                                #'jetpacs-ef-themes-screen))
  (message (if (jetpacs-connected-p)
               "Ef Themes opened on the phone"
             "Ef Themes staged — it renders when a phone connects")))

(provide 'jetpacs-ef-themes)
;;; jetpacs-ef-themes.el ends here
