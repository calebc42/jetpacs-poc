// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

/**
 * The texts the engine supervisor writes into the engine's HOME, kept pure
 * so a JVM test pins them (docs/PLAN-bundled-emacs.md, E3). Nothing here is
 * code the Companion evaluates: these are the Emacs endpoint's own
 * initialization and credential files, provisioned at install and update
 * time as SPEC 5.8 (amendment #209) allows.
 */
internal object EngineFiles {
    /** SPEC 9.1: 32 lowercase hexadecimal characters. */
    val PAIRING_ID: Regex = Regex("[0-9a-f]{32}")

    /** SPEC 9.1: the 22-character base64url token with padding omitted. */
    val TOKEN_DISPLAY: Regex = Regex("[A-Za-z0-9_-]{22}")

    /**
     * The managed block onboarding would ask a user to paste, plus the bundled
     * settings: the Companion's Unix socket (SPEC 5.3) when [socketPath] is
     * given, else the flavor's loopback port.
     */
    fun initEl(port: Int, socketPath: String? = null): String = """;;; init.el --- Jetpacs bundled engine -*- lexical-binding: t; -*-
;; Written by the Jetpacs engine supervisor. Personal overrides belong in
;; jetpacs/user.el, which loads last and survives updates.
;;; >>> Jetpacs managed: composition root >>>
;; The two timestamps bracket the kit load in the engine log (E6 timing).
(message "Jetpacs init: loading the kit")
${transportForms(port, socketPath)}
(setq jetpacs-credentials-file
      (expand-file-name "jetpacs/var/pairing" user-emacs-directory))
(add-to-list 'load-path (expand-file-name "jetpacs/emacs" user-emacs-directory))
(require 'jetpacs)
(message "Jetpacs init: kit loaded")
;;; <<< Jetpacs managed: composition root <<<
"""

    private fun transportForms(port: Int, socketPath: String?): String =
        if (socketPath == null) "(setq jetpacs-port $port)"
        else "(setq jetpacs-transport 'loopback-unix\n      jetpacs-socket \"${elispString(socketPath)}\")"

    /** A path as an Elisp string literal body: backslashes and quotes escaped. */
    private fun elispString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * The kit libraries `(require 'jetpacs)` loads, in load order, minus the
     * composition root (`jetpacs-init`, `jetpacs`), the credential reader and
     * the apps. Snapshot of the kit on 2026-10-27: a library missing from a
     * later kit is skipped at preload, one added later loads at init time;
     * both are correct, only slower.
     */
    val PRELOAD: List<String> = listOf(
        // Not jetpacs-package-browser: it reads package-user-dir at load and
        // package.el's own delayed-init variables cannot be loaded at loadup.
        "ebp-store", "ebp-sqlite", "ebp", "jetpacs-vocabulary", "jetpacs-renderer-registry",
        "jetpacs-widgets", "jetpacs-async", "ebp-path", "jetpacs-surfaces", "jetpacs-data",
        "jetpacs-shell", "jetpacs-buffer", "jetpacs-navigate", "jetpacs-chrome", "jetpacs-dialog",
        "jetpacs-transient", "jetpacs-devtools", "jetpacs-profile-snapshot", "jetpacs-repl",
        "jetpacs-comint", "jetpacs-commands", "jetpacs-results", "jetpacs-sections",
        "jetpacs-tablist", "jetpacs-dialog-slot", "jetpacs-settings",
        "jetpacs-customize", "ebp-sync", "jetpacs-apps", "jetpacs-files", "jetpacs-project",
        "jetpacs-sql", "jetpacs-app-store", "jetpacs-registry", "jetpacs-app-store-registry",
        "jetpacs-hypertext", "jetpacs-theme-picker", "jetpacs-modus", "jetpacs-theme",
        "jetpacs-gallery", "jetpacs-clip", "jetpacs-device", "jetpacs-launcher", "jetpacs-keymap",
        "jetpacs-emacs-ui", "ebp-org", "jetpacs-reader", "jetpacs-editor", "jetpacs-org-links",
        "jetpacs-dates", "jetpacs-org", "jetpacs-org-structure", "jetpacs-org-settings",
        "jetpacs-org-save", "jetpacs-org-dialogs", "jetpacs-org-render", "jetpacs-org-inline",
        "jetpacs-reader-org", "jetpacs-org-toolbar", "jetpacs-editor-org", "jetpacs-org-habits",
        "jetpacs-reminders", "jetpacs-org-reminders", "jetpacs-org-capture", "jetpacs-org-clock",
        "jetpacs-org-table", "jetpacs-org-mode", "ebp-complete",
    )

    /**
     * `site-lisp/site-init.el`, which Emacs's own loadup loads just before
     * it dumps on the engine's first start (loadup.el: "you can cause
     * additional libraries to be preloaded by writing a site-init.el"), so
     * the kit's libraries, Org and the EBP client sit in the dump and later
     * starts skip loading them. The composition root is never preloaded: it
     * creates directories, registers the apps and starts the server when it
     * loads, which belongs to a start, not to a dump. It also seeds some of
     * the preloaded libraries' variables with `defvar` before it loads them,
     * so the preload unbinds each of those again before the dump and the
     * root's seeds apply at init as they do without one. A library that
     * fails to preload is reported and loads at init time instead. The load-path
     * change is discarded after the dump, as loadup warns, and `init.el`
     * re-adds the kit at every start.
     */
    fun siteInit(): String = buildString {
        append(";;; site-init.el --- Preload for the Jetpacs engine dump -*- lexical-binding: t; -*-\n")
        append(";; Written by the Jetpacs engine supervisor; loaded by loadup before the dump.\n")
        // Loadup's load-path is the bootstrap list (site-lisp, /assets/lisp and
        // five of its subdirectories); Org, url, net and the rest live in
        // subdirectories normal-top-level adds only after the dump, so the
        // preload adds every /assets/lisp subdirectory itself.
        append(";; The bootstrap load-path lacks the lisp subdirectories (org, url, net...).\n")
        append("(dolist (dir (directory-files \"/assets/lisp/\" t directory-files-no-dot-files-regexp))\n")
        append("  (when (file-directory-p dir) (add-to-list 'load-path dir t)))\n")
        // During loadup `user-emacs-directory' is still nil (subr.el: "Emacs
        // sets this at startup"), so defaults a library derives from it at
        // load time would be wrong or fail. The engine's HOME is fixed per
        // install, so the value startup will compute is known now.
        append(";; What startup.el will set: HOME is fixed per install, XDG unset on Android.\n")
        append("(setq user-emacs-directory (expand-file-name \".emacs.d/\" \"~\"))\n")
        // ol.el computes the Org link regexps at load from user-mail-address,
        // a delayed-init variable still void during loadup; startup.el's own
        // default form gives the same value the device will have.
        // files.el's temporary-file-directory is delayed too; at loadup it is
        // nil and a preloaded library's temp directories would land in HOME.
        append(";; files.el's delayed default for temporary-file-directory: TMPDIR, the app's cache.\n")
        append("(setq temporary-file-directory (file-name-as-directory (or (getenv \"TMPDIR\") \"/tmp\")))\n")
        append(";; startup.el's delayed default for user-mail-address, evaluated the same way here.\n")
        append("(setq user-mail-address (or (getenv \"EMAIL\") (concat (user-login-name) \"@\" (system-name))))\n")
        append("(let ((kit (expand-file-name \"jetpacs/emacs\" user-emacs-directory))\n")
        append("      (first-failure t))\n")
        append("  (when (file-directory-p kit)\n")
        append("    (add-to-list 'load-path kit)\n")
        append("    (dolist (dir (directory-files (expand-file-name \"apps\" kit) t directory-files-no-dot-files-regexp))\n")
        append("      (when (file-directory-p dir) (add-to-list 'load-path dir)))\n")
        append("    (dolist (feature '(")
        append(PRELOAD.joinToString(" "))
        append("))\n")
        append("      (condition-case err\n")
        append("          (handler-bind ((error (lambda (e)\n")
        append("                                  (when first-failure\n")
        append("                                    (setq first-failure nil)\n")
        append("                                    (message \"site-init: first failure in %s: %s\\n%s\" feature e\n")
        append("                                             (substring (with-output-to-string (backtrace)) 0 2500))))))\n")
        append("            (require feature))\n")
        append("        (error (message \"site-init: %s not preloaded: %s\" feature err))))\n")
        // The composition root seeds some of these libraries' variables with
        // `defvar' before it loads them (jetpacs-init.el, "Profile defaults
        // are established before their defining libraries load"; the vault's
        // org-directory and Files roots, the chrome's dock and drawer seams).
        // The preload loaded the libraries first, which bound those variables
        // to the libraries' own defaults and would leave the root's seeds
        // no-ops: no navigation rail, no Settings, Org outside the vault.
        // Every variable the root seeds with a top-level defvar and the
        // preload bound is unbound again, so the root seeds it at init
        // exactly as it does without a dump, and user.el still wins after it.
        append("    ;; Unbind what the composition root seeds with defvar, so its seeds apply at init.\n")
        append("    (let ((root (expand-file-name \"jetpacs-init.el\" kit)) (unbound nil))\n")
        append("      (when (file-readable-p root)\n")
        append("        (with-temp-buffer\n")
        append("          (insert-file-contents root)\n")
        append("          (goto-char (point-min))\n")
        append("          (condition-case nil\n")
        append("              (while t\n")
        append("                (let ((form (read (current-buffer))))\n")
        append("                  (when (and (eq (car-safe form) 'defvar) (cddr form) (boundp (cadr form)))\n")
        append("                    (makunbound (cadr form))\n")
        append("                    (push (cadr form) unbound))))\n")
        append("            (end-of-file nil))))\n")
        append("      (message \"site-init: unbound %d root seeds: %S\" (length unbound) (nreverse unbound)))))\n")
        // The pdumper cannot dump overlays, and a library loaded in the current
        // buffer (*scratch* during loadup) may leave one behind; such overlays
        // are load-time artifacts, not state, so they go before the dump.
        append(";; pdumper refuses overlays; a load-time artifact in *scratch* would void the dump.\n")
        append("(dolist (b (buffer-list)) (with-current-buffer b (remove-overlays)))\n")

    }

    /** `install.conf` format 3 as `device/install-recommended.el` writes it; [vaultMode] is `private` or `shared`. */
    fun installConf(vault: String, home: String, root: String, vaultMode: String = "private"): String =
        "format=3\n" +
            "vault_mode=$vaultMode\n" +
            "vault=$vault\n" +
            "home_mode=bundled\n" +
            "home=$home\n" +
            "emacs_home=$home\n" +
            "root=$root\n"

    /** The Emacs endpoint's credential copy; `jetpacs-credentials.el` reads it. */
    fun pairingFile(pairingId: String, tokenDisplay: String): String {
        require(PAIRING_ID.matches(pairingId)) { "pairing id is not 32 lowercase hex characters" }
        require(TOKEN_DISPLAY.matches(tokenDisplay)) { "token is not the 22-character display form" }
        return "pairing_id=$pairingId\ntoken=$tokenDisplay\n"
    }

    /** The pairing id a credential file names, or null when it is not one. */
    fun pairingIdOf(text: String): String? =
        text.lineSequence()
            .firstOrNull { it.startsWith("pairing_id=") }
            ?.removePrefix("pairing_id=")
            ?.trim()
            ?.takeIf { PAIRING_ID.matches(it) }
}
