// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.calebc42.jetpacs.companion.BuildConfig
import com.calebc42.jetpacs.companion.EngineFlavor
import java.io.File

/**
 * The engine's private filesystem (docs/PLAN-bundled-emacs.md, "Target
 * architecture"): a HOME of its own under the app's files directory so
 * dumps, `.emacs.d` and `site-lisp` stay out of the Companion's files root.
 */
class EngineHome(val root: File) {
    val emacsD: File = File(root, ".emacs.d")
    val initFile: File = File(emacsD, "init.el")

    /** Today's `~/.emacs.d/jetpacs/` install root, layout unchanged. */
    val installRoot: File = File(emacsD, "jetpacs")
    val kit: File = File(installRoot, "emacs")
    val org: File = File(installRoot, "org")
    val apps: File = File(installRoot, "apps")
    val variable: File = File(installRoot, "var")

    /** Which engine and which APK provisioned this HOME. */
    val stampFile: File = File(root, "engine.stamp")

    companion object {
        fun of(context: Context): EngineHome = EngineHome(File(context.filesDir, "emacs"))
    }
}

/**
 * Puts the managed Elisp kit and an init file into the engine's HOME, once
 * per engine build and APK install. Emacs cannot load the kit from the APK
 * directly: its asset filesystem stats files through the `directory-tree`
 * index the engine build produced, so the Companion's own assets are
 * invisible to it. The kit is the same `jetpacs-onboarding/payload` the
 * standalone flavor stages for the user to install by hand.
 *
 * User state in `apps/`, `apps.el`, `var/` and `user.el` is never touched;
 * only distribution-owned trees are replaced.
 */
object EngineProvisioner {
    private const val TAG = "JetpacsEngine"
    private const val PAYLOAD = "jetpacs-onboarding/payload"

    /**
     * The managed block, the same shape onboarding asks a user to paste, plus
     * the bundled Companion's port (the flavor's, not SPEC 5.2's default).
     */
    private val INIT_EL: String = """;;; init.el --- Jetpacs bundled engine -*- lexical-binding: t; -*-
;; Written by the Jetpacs engine supervisor. Personal overrides belong in
;; jetpacs/user.el, which loads last and survives updates.
;;; >>> Jetpacs managed: composition root >>>
(setq jetpacs-port ${EngineFlavor.loopbackPort})
(add-to-list 'load-path (expand-file-name "jetpacs/emacs" user-emacs-directory))
(require 'jetpacs)
;;; <<< Jetpacs managed: composition root <<<
"""

    fun prepare(context: Context): EngineHome {
        val home = EngineHome.of(context)
        for (dir in listOf(home.root, home.emacsD, home.installRoot, home.apps, home.variable)) {
            check(dir.isDirectory || dir.mkdirs()) { "cannot create ${dir.path}" }
            dir.setReadable(false, false); dir.setExecutable(false, false)
            dir.setReadable(true, true); dir.setExecutable(true, true)
        }
        val stamp = currentStamp(context)
        if (home.stampFile.takeIf { it.isFile }?.readText() != stamp) {
            Log.i(TAG, "provisioning kit for $stamp into ${home.installRoot}")
            home.kit.deleteRecursively()
            home.org.deleteRecursively()
            copyAssetTree(context.assets, "$PAYLOAD/emacs", home.kit)
            copyAssetTree(context.assets, "$PAYLOAD/org", home.org)
            home.stampFile.writeText(stamp)
        }
        if (!home.initFile.isFile || home.initFile.readText() != INIT_EL) {
            home.initFile.writeText(INIT_EL)
        }
        return home
    }

    /** Engine build plus APK install time: either changing re-provisions. */
    fun currentStamp(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${BuildConfig.ENGINE_ID}:${info.lastUpdateTime}"
    }

    private fun copyAssetTree(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // A leaf: AssetManager lists nothing for files.
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        check(target.isDirectory || target.mkdirs()) { "cannot create ${target.path}" }
        for (child in children) copyAssetTree(assets, "$path/$child", File(target, child))
    }
}
