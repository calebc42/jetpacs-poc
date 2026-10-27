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
${transportForms(port, socketPath)}
(setq jetpacs-credentials-file
      (expand-file-name "jetpacs/var/pairing" user-emacs-directory))
(add-to-list 'load-path (expand-file-name "jetpacs/emacs" user-emacs-directory))
(require 'jetpacs)
;;; <<< Jetpacs managed: composition root <<<
"""

    private fun transportForms(port: Int, socketPath: String?): String =
        if (socketPath == null) "(setq jetpacs-port $port)"
        else "(setq jetpacs-transport 'loopback-unix\n      jetpacs-socket \"${elispString(socketPath)}\")"

    /** A path as an Elisp string literal body: backslashes and quotes escaped. */
    private fun elispString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

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
