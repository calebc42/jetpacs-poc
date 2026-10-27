;;; jetpacs-init.el --- Compose and start Jetpacs -*- lexical-binding: t; -*-

;; This is the package composition root loaded by `(require 'jetpacs)'.  Code
;; may come from package.el/package-vc or from the Companion's bundled offline
;; installation.  Durable state has one layout-independent home at
;; ~/.emacs.d/jetpacs; package code is never assumed to live there.

(require 'subr-x)
(declare-function server-running-p "server" ())
(declare-function server-start "server" (&optional leave-dead inhibit-prompt))
(declare-function ebp-unix-default-path "ebp-unix" ())

;; Android's exported file-opening activity hands warm launches to
;; emacsclient.  The user's init has already run before this managed loader,
;; so honor any server configuration it established and fill in only the
;; missing server.  This makes onboarding/repair links to the real init.el
;; work after the first Jetpacs start without affecting desktop Emacs.
(when (eq system-type 'android)
  (require 'server)
  (unless (server-running-p)
    (server-start)))

(defvar jetpacs-install-root
  (file-name-as-directory
   (expand-file-name "jetpacs/" user-emacs-directory))
  "Root of Jetpacs's removable, user-private state tree.")

(defconst jetpacs-elisp-directory
  (file-name-as-directory
   (file-name-directory
    (or load-file-name
        (locate-library "jetpacs-init")
        buffer-file-name)))
  "Directory containing the active Jetpacs package libraries.")

(defconst jetpacs-var-directory
  (expand-file-name "var/" jetpacs-install-root)
  "Durable Jetpacs protocol state, kept inside the managed tree.")

(defun jetpacs-install-conf-value (key)
  "Return KEY from this installation's simple key=value record."
  (let ((file (expand-file-name "install.conf" jetpacs-install-root)))
    (when (file-readable-p file)
      (with-temp-buffer
        (insert-file-contents-literally file)
        (goto-char (point-min))
        (when (re-search-forward
               (concat "^" (regexp-quote key) "=\\(.*\\)$") nil t)
          (string-trim-right (match-string-no-properties 1) "\\r"))))))

(defvar jetpacs-vault-directory
  (file-name-as-directory
   (or (jetpacs-install-conf-value "vault")
       (getenv "HOME")
       (expand-file-name "~/")))
  "Root for user content selected during onboarding.
This is deliberately separate from HOME and `user-emacs-directory', which
remain in the private Emacs/Termux home on Android.")

(defvar jetpacs-vault-mode
  (or (jetpacs-install-conf-value "vault_mode") "advanced")
  "Onboarding label for `jetpacs-vault-directory'.")

(defvar jetpacs-home-mode
  (or (jetpacs-install-conf-value "home_mode") "emacs")
  "Onboarding label for the Unix HOME containing `user-emacs-directory'.")

(unless (and (file-name-absolute-p jetpacs-vault-directory)
             (not (file-remote-p jetpacs-vault-directory)))
  (error "Jetpacs Vault must be a local absolute directory: %s"
         jetpacs-vault-directory))

;; The user's init ran before this loader and is authoritative.  The selected
;; Vault supplies fallbacks only: an existing `org-directory' or any
;; predeclared jetpacs-* path survives unchanged.  In particular, do not reset
;; `default-directory' out from under a user's project/session setup.
(defvar org-directory (expand-file-name "org/" jetpacs-vault-directory))
(defvar jetpacs-files-default-dir jetpacs-vault-directory)
(defvar jetpacs-files-roots
  (delete-dups
   (delq nil
         (list user-emacs-directory
               (file-name-as-directory (expand-file-name "~/"))
               jetpacs-vault-directory
               (and (stringp org-directory)
                    (file-name-as-directory
                     (expand-file-name org-directory)))))))

(add-to-list 'load-path jetpacs-elisp-directory)

;; Repo apps keep their subdirectories in the managed installation instead of
;; being flattened into one ambiguous directory.
(let ((apps-dir (expand-file-name "apps/" jetpacs-elisp-directory)))
  (when (file-directory-p apps-dir)
    (dolist (dir (directory-files apps-dir t directory-files-no-dot-files-regexp))
      (when (file-directory-p dir)
        (add-to-list 'load-path dir)))))

(make-directory jetpacs-var-directory t)

;; Profile defaults are established before their defining libraries load.
;; Any value already set by the user's init or Emacs Custom remains bound and
;; therefore wins; the libraries' defvar/defcustom forms preserve it too.
(declare-function jetpacs-emacs-ui-mx-button "jetpacs-emacs-ui" ())
(defvar jetpacs-apps-core-global-actions
  (lambda (_surface) (list (jetpacs-emacs-ui-mx-button))))
;; The same M-x as DATA (S10), which is what
;; `jetpacs-chrome-global-actions-placement' can re-author into the fab
;; slot; at the default `top-bar' placement it authors the node above
;; byte for byte.  Both seeds ship: the data one supersedes, so exactly
;; one M-x renders either way, and the node seam stays the working
;; override for a host that seeds only it.
(defvar jetpacs-apps-core-global-items
  (lambda (_surface)
    (list (list :icon "terminal" :label "M-x"
                :on-tap (jetpacs-action "jetpacs.emacs.mx")))))
(defvar jetpacs-apps-core-dock-items #'jetpacs-hub--dock-items)
(defvar jetpacs-apps-core-drawer-rows #'jetpacs-hub--drawer-rows)
(defvar jetpacs-theme-mode 'mirror)
(defvar jetpacs-clip-auto-refresh nil)

;; The base stack…
(require 'jetpacs-widgets)
(require 'jetpacs-async)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-buffer)
(require 'jetpacs-navigate)
(require 'jetpacs-chrome)
(require 'jetpacs-dialog)
(require 'jetpacs-transient)
(require 'jetpacs-devtools)   ; the push profiler + failure flight recorder
(require 'jetpacs-profile-snapshot) ; last negotiated profile, for authoring tools
;; …the mode skins (additive: they register for their major modes)…
(require 'jetpacs-repl)      ; the Elisp REPL this home screen IS
(require 'jetpacs-comint)
(require 'jetpacs-sections)
(require 'jetpacs-results)
(require 'jetpacs-tablist)
(require 'jetpacs-package-browser)
(require 'jetpacs-settings)
;; Optional applet runtimes are discovered from installed packages. The
;; Jetpacs composition root may activate them, but the host does not own or
;; require their source.
(require 'jetpacs-automation-runtime nil t)
(require 'jetpacs-customize)
(require 'jetpacs-project)
(require 'jetpacs-sql)
(require 'jetpacs-apps)
(require 'jetpacs-app-store)
(require 'jetpacs-app-store-registry) ; applet registries as an Apps source
(require 'jetpacs-hypertext)
;; …and the apps.
(require 'jetpacs-theme)
;; The renderer-primitives demo is a Jetpacs satellite.  Optional Modus-family
;; providers are contributed through `jetpacs-modus-register-theme-provider'
;; by their downstream owner; Jetpacs does not name or load those integrations.
(require 'jetpacs-gallery)
(jetpacs-gallery-register)
(require 'jetpacs-clip)
(require 'jetpacs-device)
(require 'jetpacs-files)
(defvar ebp-org-roots (jetpacs-files-effective-roots)
  "Directories structured Org actions may resolve by default.
Jetpacs uses the same effective allowlist as the Files app so an Org document
opened from the user's Vault never renders action controls that the Org engine
then silently refuses.  A value set by the user's init remains authoritative.")
(require 'jetpacs-launcher)
(require 'jetpacs-emacs-ui)   ; the Buffers app + the global M-x verb
;; Org Mode is a real Jetpacs App.  Its composition root installs the
;; reusable reader/editor hosts, their Org adapters, the existing Org
;; renderer/dialogs/habits modules, and app identity.  A `.org' file in
;; Files therefore opens in the reader with a text-editor toggle, while
;; the Apps view also gains an Org Mode home.  This is the template a
;; future `jetpacs-elisp-mode' app can reuse.
(require 'jetpacs-org-mode)
;; Material 3 Catalog (owner `m3catalog'): the upstream Material 3 inventory,
;; 41 components and 279 examples of the reusable Jetpacs M3 vocabulary,
;; authored in Elisp on its own surface.  It is also the tree's first
;; `jetpacs-defapp' registration, so its "Material 3" destination composes
;; into every dock.
;; Reach it there, from the Apps button, or M-x jetpacs-m3-catalog.
(require 'jetpacs-m3-catalog nil t)
;; Jetpacs Components is a separate reference for the emerging Foundation-only
;; Jetpacs design language.  Its app is visible only when the connected
;; Companion positively advertises `jetpacs.components'.
(require 'jetpacs-component-catalog nil t)
;; The platform's own design profile.  When the Companion advertises the
;; design runtime, every chrome screen in every app wears it unless the
;; app presents its own; otherwise it is inert.
(require 'jetpacs-design-baseline nil t)
;; Automations is the GUI-over-Lisp workflow editor.  Its runtime was loaded
;; above before any connection can replay durable trigger events; this package
;; contributes only the separate app surface and editor projections.
(require 'jetpacs-automations nil t)
;; The live editor loop (parity P1), and the `ebp-' half of the stack:
;; wire and Emacs only, no node vocabulary.  Buffer sync with its
;; riders, then the capf completion server answering `edit.complete' —
;; `jetpacs-connect' looks for it at dial time, so requiring it HERE is
;; what turns device completion on.
(require 'ebp-sync)
(require 'ebp-complete)

;; The curated settings content (owner: POC 1's settings had the right
;; content in the wrong shape).  Registered at boot so a queued toggle
;; replays even before the screen first renders.  The org/calendar
;; sections + the phone-generic org seeding are the foundation
;; module's (the ratified §3 relocation) — required here under the
;; same boot rule.
(require 'jetpacs-org-settings)
;; The bundled app's engine has no user account behind it: Emacs for
;; Android's placeholder identity ("Android user", u0_…@localhost) would
;; sign the owner's exported notes.  Both start empty, which is also
;; their default for Reset, until the owner enters them in Emacs
;; Settings; a saved value wins.
(when (jetpacs-bundled-p)
  (dolist (sym '(user-full-name user-mail-address))
    (put sym 'standard-value (list ""))
    (unless (get sym 'saved-value)
      (set-default sym ""))))
;; Every `:plain' below is the bundled app's wording: its user has no
;; Emacs of their own, so it replaces the docstrings there and only
;; there (`jetpacs-settings-item').  `:advanced' rows are for the people
;; building Jetpacs and gather in one section at the end.
(jetpacs-settings-register-section
 "Appearance"
 '(;; The §3 fold-in: the app's authored enum node retired — the
   ;; choice-of-consts custom-type renders an equivalent enum.
   (jetpacs-line-numbers
    :label "Line numbers"
    :plain (:summary "Number the lines of text in buffer views."))
   ;; S10: where M-x rides — the whole point of the choice-of-consts
   ;; type is that the phone can set it, so the phone must SHOW it.
   (jetpacs-chrome-global-actions-placement
    :label "Global actions"
    :plain (:label "M-x button"
            :summary "Where the M-x button sits on every screen."
            :choices ((top-bar . "Top bar")
                      (fab . "Floating button")
                      (fab-menu . "Floating menu"))))))
(jetpacs-settings-register-section
 "Editor"
 '((ebp-sync-diagnostics :label "Push diagnostics" :advanced t)
   (ebp-sync-fontify :label "Push syntax highlighting" :advanced t)
   (ebp-sync-eldoc :label "Push documentation" :advanced t)))
;; The SPEC 23.3 explicit developer setting: full failure detail stays
;; local and bounded, and only while this is on.
(jetpacs-settings-register-section
 "Devtools"
 '((jetpacs-devtools-recording :label "Record failure details" :advanced t)
   (jetpacs-devtools-stream-log :label "Log wire frames" :advanced t)))
(jetpacs-settings-register-section
 "Clipboard"
 '((jetpacs-clip-auto-refresh
    :label "Auto-refresh the kill ring"
    :plain (:label "Update the clipboard list as you copy"
            :summary "Refresh the list after every cut or copy."))
   (jetpacs-clip-max-entries
    :label "Kill-ring entries shown"
    :plain (:label "Clipboard entries shown"
            :summary "How many recent cuts and copies the list keeps."))))
;; In the bundled app the Notes setting in Jetpacs Settings decides
;; what storage the engine sees, so the browser's own storage switches
;; are left out there.
(jetpacs-settings-register-section
 "Files"
 '((jetpacs-files-sync-editor
    :label "Live editing"
    :plain (:summary "Text files open in the live editor."))
   (jetpacs-files-shared-storage :label "Shared storage access"
                                 :standalone-only t)
   (jetpacs-files-android-private-locations
    :label "Android private locations" :standalone-only t)
   (jetpacs-files-max-rows
    :label "Directory rows shown"
    :plain (:label "Files shown per folder"
            :summary "A folder with more files lists only the first ones."))))

;;;; The hub — the screen you land on and come home to

;; The hub chrome follows docs/CHROME-VOCABULARY.md: the DRAWER (left,
;; behind the Companion's hamburger) holds app destinations, the TOP BAR
;; keeps M-x top-right, and the view switcher — Home / Files / Eval,
;; the poc's tabs reborn — docks as the BOTTOM BAR, or as a NAVIGATION
;; RAIL when the window is expanded (SPEC 20.1.1).  Eval is *ielm*: ielm
;; derives from comint-mode, so the comint skin's pinned input row is
;; the REPL.

(defun jetpacs-hub--tools-entry ()
  "The drawer's Tools nest: the everyday utilities under one header —
Clipboard, the Messages log, the buffer switcher, and the two devtools
rows: the push-loop report, and Inspect a screen."
  (jetpacs-collapsible
   "drawer-tools"
   ;; A plain row: the header line is the expand target.
   (jetpacs-row
    (jetpacs-icon "build")
    (jetpacs-with-attrs
     (jetpacs-column (jetpacs-text "Tools")
                     (jetpacs-text "Clipboard, logs, buffers"
                                   :style "caption")
                     :spacing 2)
     :weight 1)
    :align "center" :spacing 12)
   (jetpacs-chrome-row
    "Clipboard" :subtitle "the kill ring" :icon "content_paste"
    :on-tap (jetpacs-shell-open-surface-action "app:jetpacs.clip")
    :key "drawer-tools-clip")
   (jetpacs-chrome-row
    "Messages" :subtitle "the Emacs log" :icon "description"
    :on-tap (jetpacs-action "hub.open" :args '(:buffer "*Messages*"))
    :key "drawer-tools-messages")
   (jetpacs-chrome-row
    "Buffers" :subtitle "every live buffer" :icon "view_list"
    :on-tap (jetpacs-action "jetpacs.emacs.buffers")
    :key "drawer-tools-buffers")
   (jetpacs-chrome-row
    "Scratch" :subtitle "lisp playground" :icon "description"
    :on-tap (jetpacs-action "hub.open" :args '(:buffer "*scratch*"))
    :key "drawer-tools-scratch")
   (jetpacs-chrome-row
    "Shell" :subtitle "comint, with input" :icon "terminal"
    :on-tap (jetpacs-action "hub.open" :args '(:buffer "*shell*"))
    :key "drawer-tools-shell")
   (jetpacs-chrome-row
    "Devtools" :subtitle "push loop, failures" :icon "build"
    :on-tap (jetpacs-action "hub.open" :args '(:buffer "*jetpacs-devtools*"))
    :key "drawer-tools-devtools")
   ;; The data path, visible: every frame with its bytes (behind the
   ;; wire-log setting), and every action, state report, push and patch.
   (jetpacs-chrome-row
    "Wire log" :subtitle "frames, bytes per method" :icon "swap_vert"
    :on-tap (jetpacs-action "hub.open" :args '(:buffer "*jetpacs-stream*"))
    :key "drawer-tools-stream")
   (jetpacs-chrome-row
    "Action log" :subtitle "actions, state, pushes, patches" :icon "history"
    :on-tap (jetpacs-action "hub.open" :args '(:buffer "*jetpacs-actions*"))
    :key "drawer-tools-actions")
   (jetpacs-chrome-row
    "Inspect a data document" :subtitle "edit it into a patch" :icon "edit_note"
    :on-tap (jetpacs-action "jetpacs.devtools.inspect-data-pick")
    :key "drawer-tools-inspect-data")
   ;; The homoiconic loop, one tap in: the verb picks a live surface
   ;; through the bridged picker, builds its spec fresh, and shows the
   ;; Lisp on the phone.  Copy the sexp into the Eval REPL two screens
   ;; away, edit it, and `jetpacs-shell-push' it back with :spec.
   (jetpacs-chrome-row
    "Inspect a screen" :subtitle "its spec, as Lisp" :icon "data_object"
    :on-tap (jetpacs-action "jetpacs.devtools.inspect-pick")
    :key "drawer-tools-inspect")
   :collapsed t))

(defun jetpacs-hub--drawer-rows (_surface)
  ;; The host's drawer TAIL (owner decisions 2026-08-06, pass 4): the
  ;; Tools nest, then — below the divider, settings-last like every
  ;; Android app — the Settings nest.  The HEAD (the Apps row and the
  ;; S1 destination nests) is `jetpacs-apps-drawer''s own; this seed
  ;; composes below it through `jetpacs-apps-core-drawer-rows', and
  ;; the S8 seam hangs the finished drawer on every build-within
  ;; root — the hub stopped authoring it by hand.  An App = a Tier 1
  ;; elisp package built ON jetpacs; org activates like a major mode
  ;; (on file open, never a destination); Files lives in the nav-bar
  ;; dock only.
  (list (jetpacs-hub--tools-entry)
        (jetpacs-divider)
        (jetpacs-settings-drawer-entry)))

(defun jetpacs-hub--dock-items (surface)
  "The persistent view switcher's destinations, as data.
The hub root IS the Eval REPL (owner decision 2026-08-06: no separate
home screen), so the two global destinations are named honestly: Eval
and Files."
  (let ((sel (cond ((equal surface "app:jetpacs.files") 'files)
                   ((equal surface "app:hub") 'home))))
    (list (list :key "eval" :label "Eval" :icon "code"
                :on-tap (jetpacs-shell-open-surface-action "app:hub")
                :selected (eq sel 'home))
          (list :key "files" :label "Files" :icon "folder_open"
                :on-tap (jetpacs-shell-open-surface-action
                         "app:jetpacs.files")
                :selected (eq sel 'files)))))

;;;; Home IS the Eval REPL (owner decision 2026-08-06: no hub screen —
;;;; the core is a command-runner, a file explorer, and this).  POC 1's
;;;; Eval feel, improved: history cards newest-first with copy and
;;;; re-run, error results styled as errors, a pinned elisp input that
;;;; can never be pushed off-screen, and *scratch*-style multi-form
;;;; evaluation with * ** *** holding the last three results.

(defconst jetpacs-hub--repl "hub"
  "The hub's `jetpacs-repl' session id.")

(defun jetpacs-hub--screen (_back)
  ;; Amendment #169 (R3): the REPL editor below presents in the hub APP
  ;; surface — register whether its completion replies may carry
  ;; candidate `kind', per the live welcome (the author-time half of the
  ;; sender-omit rule; re-evaluated every build, so it follows a
  ;; reconnect's welcome).  The editor node itself now comes from
  ;; `jetpacs-repl-input-row', but the ids are the same pair.
  (when (fboundp 'ebp-complete-set-editor-kinds)
    (ebp-complete-set-editor-kinds
     "scratch.el" "hub-eval"
     (and (jetpacs-client)
          (jetpacs-feature-advertised-p "editor.candidate_kind" :app))))
  (jetpacs-chrome-screen
   "Jetpacs"
   (jetpacs-column
    (jetpacs-with-attrs
     (if-let* ((cards (jetpacs-repl-cards jetpacs-hub--repl :verb "hub.eval")))
         (apply #'jetpacs-lazy-column cards)
       (jetpacs-repl-empty-state))
     :weight 1)
    (jetpacs-divider)
    ;; A SYNCHRONIZED §19 editor, not a local draft: `:document' makes
    ;; the Companion open an edit session on the next push, so the text
    ;; arrives as deltas into `ebp.el''s mirror and `hub.eval' reads it
    ;; from there.  (Since R0 a bridged `completing-read' picker claims
    ;; only its OWN document in ebp.el's override table, so this
    ;; dropdown keeps answering while a prompt is up.)
    (jetpacs-repl-input-row :editor-id "hub-eval" :document "scratch.el"
                            :verb "hub.eval"))
   ;; No :drawer here since S8: the seam hangs the composed host
   ;; drawer on this root — and on every other build-within root —
   ;; from `jetpacs-apps-drawer'; authoring it here too would merely
   ;; shadow the same node.
   :actions (list (jetpacs-emacs-ui-mx-button))))

(with-jetpacs-owner "hub"
  (jetpacs-chrome-define-root "hub" "home" #'jetpacs-hub--screen
                              :required t)
  ;; Both hub verbs are GLOBAL (the dock renders them on every chrome
  ;; surface) and target the HUB surface explicitly: tapping Eval from
  ;; Files means "take me to the hub's Eval view", never "drill ielm
  ;; onto the files stack".
  (jetpacs-defaction "hub.open"
    (lambda (args _params)
      (let ((name (plist-get args :buffer)))
        (jetpacs-flow-continue
         (lambda ()
           (when (and (equal name "*shell*") (not (get-buffer name)))
             (save-window-excursion (shell)))
           (when (and (equal name "*ielm*") (not (get-buffer name)))
             (save-window-excursion (ielm)))
           ;; The devtools report regenerates on every open — stale
           ;; instrumentation is worse than none.
           (when (equal name "*jetpacs-devtools*")
             (jetpacs-devtools-report-buffer))
           (when (equal name "*jetpacs-stream*")
             (jetpacs-devtools-stream-log-buffer))
           (when (equal name "*jetpacs-actions*")
             (jetpacs-devtools-action-log-buffer))
           (condition-case err
               (jetpacs-navigate-buffer name "app:hub")
             (error (message "hub.open: %s" (jetpacs-error-label err))))))
        'accepted))
    :any-surface t)

  (jetpacs-defaction "hub.home"
    ;; The view switcher's Home tab: back to the hub root.
    (lambda (_args _params)
      (jetpacs-flow-continue
       (lambda () (jetpacs-chrome-reset-screens "hub")))
      'accepted)
    :any-surface t)

  (jetpacs-defaction "hub.eval"
    ;; The REPL submit: the send button arrives without a value and
    ;; reads the SYNCHRONIZED mirror — the editor carries `:document',
    ;; so it is no longer a stateful draft and `jetpacs-ui-state' would
    ;; be nil forever.  `on-enter' and the re-run button carry :value.
    ;; Evaluation is continuation work — user code can take arbitrarily
    ;; long, prompt, or signal.
    (lambda (args _params)
      (let ((input (or (plist-get args :value)
                       (when-let* ((client (jetpacs-client)))
                         (ebp-client-editor-text
                          client "scratch.el" "hub-eval")))))
        (if (not (and (stringp input)
                      (not (string-blank-p input))))
            'rejected
          (jetpacs-flow-continue
           (lambda ()
             (jetpacs-repl-run jetpacs-hub--repl input)
             (ignore-errors (jetpacs-shell-push "hub"))))
          'accepted)))
    :any-surface t))

;;;; Connection

;; A bundled engine's init names a credential file (docs/PLAN-bundled-emacs.md,
;; SPEC 9.1 under amendment #209); the user's own init sets the variables.
(require 'jetpacs-credentials)

(defvar jetpacs-host "127.0.0.1"
  "Loopback address the Companion listens on (EBP SPEC 5.2).
The `loopback-tcp' profile binds an IP loopback interface only; a bridged
deployment forwards the Companion's listener onto this host's loopback, so
the value stays a loopback address.")

(defvar jetpacs-port 8765
  "Loopback port the Companion listens on (EBP SPEC 5.2, default 8765).
A Companion that was started on another port, such as a desktop Companion
beside an `adb forward' of a phone, shows its port on its pairing screen.")

(defvar jetpacs-transport 'loopback-tcp
  "The EBP SPEC 5 transport profile this Emacs uses.
Under `loopback-tcp' (SPEC 5.2, the default on every platform) Emacs dials
the Companion at `jetpacs-host' and `jetpacs-port'.  Under `loopback-unix'
\(SPEC 5.3, the bundled Android engine) Emacs dials the Companion's Unix
socket `jetpacs-socket', refusing one another user owns, and redials after
any loss like the TCP dialer.  Under `emacs-unix' (SPEC 5.6, a daemon Emacs
serving desktop Companions) Emacs binds the Unix socket `jetpacs-socket' in
a directory private to this user, and the Companion, started with
`--transport emacs-unix', dials it and redials after any loss; this Emacs
never redials.")

(defvar jetpacs-socket nil
  "The Unix socket of the `loopback-unix' or `emacs-unix' profile, or nil.
Under `loopback-unix' it is the Companion's socket this Emacs dials and must
be set.  Under `emacs-unix' nil means ebp.el's default,
`ebp-unix-default-path': `ebp.sock' in $XDG_RUNTIME_DIR/ebp, else in the
temporary directory's ebp<uid>; the desktop Companion's pairing screen names
the path it dials.")

(defvar jetpacs-pairing-id "101112131415161718191a1b1c1d1e1f"
  "Pairing identity used to authenticate this Emacs to the Companion.")

(defvar jetpacs-pairing-token "AAECAwQFBgcICQoLDA0ODw"
  "Display-form pairing token used to authenticate to the Companion.")

(defun jetpacs-start ()
  "Keep the EBP endpoint active and land on the hub when READY.
Under `loopback-tcp' Emacs is the SPEC 5.2 dialer: the logical client
survives a stopped or restarted Companion and performs fresh handshakes with
jittered bounded backoff until the listener returns.  Under `emacs-unix'
Emacs is the SPEC 5.6 binder: the client listens on `jetpacs-socket' until a
Companion dials and authenticates, and never redials (the Companion does,
SPEC 5.7).  Calling this command while a redial is pending requests an
immediate attempt (a listener re-checks its socket); calling it while READY
brings the hub forward without creating a competing client."
  (interactive)
  (let ((client (jetpacs-client)))
    (cond
     ((and client (ebp-client-active-p client))
      (if (jetpacs-connected-p)
          (jetpacs-hub)
        (ebp-client-reconnect-now client))
      client)
     (t (jetpacs--start-1)))))

(defun jetpacs--connect-config ()
  "The `ebp-connect' config this composition root uses under every profile.
When `jetpacs-credentials-file' is set it is read first, so a re-provisioned
pairing takes effect on the next dial."
  (jetpacs-credentials-apply)
  (list :client-name "device-emacs" :client-version emacs-version
        :pairing-id jetpacs-pairing-id
        :token (ebp-decode-pairing-token jetpacs-pairing-token)
        :wants '("theme" "presentation.toast" "presentation.snackbar"
                 "surfaces.dialog" "surfaces.notification" "surfaces.widget"
                 "reminders.owner" "reminders.actions" "offline.wake" "editor.sync"
                 "triggers" "capabilities" "data.model")
        :receipt-file (expand-file-name "receipts.sqlite"
                                        jetpacs-var-directory)
        :ready-function #'jetpacs-ready-landing))

(defun jetpacs--start-1 ()
  "Open the logical client for `jetpacs-transport'.
Under `loopback-tcp' Emacs dials `jetpacs-host' and `jetpacs-port' (SPEC
5.2).  Under `loopback-unix' Emacs dials the Companion's socket
`jetpacs-socket' (SPEC 5.3): the host argument is the socket path and the
port is nil, as `ebp-connect' documents.  Under `emacs-unix' Emacs binds
`jetpacs-socket', or ebp.el's default path, and the Companion dials it (SPEC
5.6): the host argument is the socket path and the port is nil, exactly as
`ebp-unix-listen' passes them."
  (pcase jetpacs-transport
    ('loopback-tcp
     (apply #'jetpacs-connect jetpacs-host jetpacs-port
            (jetpacs--connect-config)))
    ('loopback-unix
     (unless (stringp jetpacs-socket)
       (error "jetpacs-socket must name the Companion's socket under loopback-unix"))
     (apply #'jetpacs-connect (expand-file-name jetpacs-socket) nil
            :transport 'loopback-unix
            (jetpacs--connect-config)))
    ('emacs-unix
     (require 'ebp-unix)
     (apply #'jetpacs-connect
            (expand-file-name (or jetpacs-socket (ebp-unix-default-path))) nil
            :transport 'emacs-unix
            (jetpacs--connect-config)))
    (_ (error "jetpacs-transport must be loopback-tcp, loopback-unix or emacs-unix, not %S"
              jetpacs-transport))))

(defun jetpacs-ready-landing (_client)
  "Land on an explicitly seeded app/route, or the native Jetpacs hub.
The app registry owns the generic selection mechanism; downstream policy is
only the seed.  Therefore a profile can select its app landing without this
composition root knowing that app, while an unseeded daily-driver install
keeps the historical Eval landing.  A broken app landing degrades to that
same safe native root instead of making READY callback failure fatal."
  (unless (condition-case err
              (jetpacs-apps-open-seeded)
            (error
             (message "jetpacs: seeded ready landing failed: %s"
                      (jetpacs-error-label err))
             nil))
    (jetpacs-hub)))

(defun jetpacs-hub ()
  "Bring the hub back to the screen (from clip, or anywhere)."
  (interactive)
  (jetpacs-shell-push "app:hub" :current-view "home"))

(defun jetpacs-stop ()
  "Close the session; under `emacs-unix' also the listener, unlinking its socket."
  (interactive)
  (when-let* ((c (jetpacs-client)))
    (ebp-client-close c 'user-quit)
    (jetpacs-detach)))

;; Keep one logical client alive from startup: a dialer, or under
;; `emacs-unix' a binder.  A Companion that is not yet running leaves the
;; dialer in bounded backoff (and the binder listening); opening or
;; restarting the app no longer requires restarting Emacs.
(add-hook 'after-init-hook
          (lambda () (with-demoted-errors "jetpacs-start: %S"
                       (jetpacs-start))))

;; Personal overrides are deliberately last and deliberately inside the one
;; removable tree.  Onboarding never creates or overwrites this file.
(let ((user-file (expand-file-name "user.el" jetpacs-install-root)))
  (when (file-readable-p user-file)
    (load user-file nil 'nomessage)))

;; Optional apps are the last composition tier: activate only choices
;; explicitly enabled through Apps, after every foundation host AND personal
;; variable override they may consume.  Each app remains isolated, so one
;; broken optional app never costs the boot.
(jetpacs-app-store-boot)

(provide 'jetpacs-init)
;;; jetpacs-init.el ends here
