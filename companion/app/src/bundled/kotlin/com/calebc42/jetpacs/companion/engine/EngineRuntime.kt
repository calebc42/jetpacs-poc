// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import com.calebc42.jetpacs.companion.BuildConfig
import java.io.File
import java.io.FileInputStream
import org.gnu.emacs.EmacsNative
import org.gnu.emacs.EmacsService

/**
 * Runs GNU Emacs inside the `:emacs` process the way upstream's
 * `EmacsNoninteractive.main1` does, with no `EmacsService` object: Emacs
 * then never initializes its Android display, creates no window and no
 * activity, and nothing can write into its native event queue, which only a
 * display terminal drains (docs/PLAN-bundled-emacs.md, "Headless mode").
 * `--fg-daemon` keeps the process interactive (timers, processes, sockets)
 * without an initial frame; on the first start Emacs's own loadup dumps
 * itself into HOME, and later starts load that dump.
 *
 * Everything here is Jetpacs code calling Emacs's public static entry
 * points; the Emacs sources in the engine AAR are unmodified.
 */
object EngineRuntime {
    private const val TAG = "JetpacsEngine"

    @Volatile private var thread: Thread? = null

    /** Start Emacs once per process; later calls are no-ops. */
    fun start(context: Context) {
        synchronized(this) {
            if (thread != null) return
            val app = context.applicationContext
            thread = Thread({ run(app) }, "Emacs main thread").also { it.start() }
        }
    }

    private fun run(app: Context) {
        try {
            val home = EngineProvisioner.prepare(app)
            pipeStandardStreamsToLog()
            // With no service object Emacs sets neither LANG nor the logcat
            // pipe itself (android.c, emacs_service_object); supply both.
            Os.setenv("LANG", "en_US.utf8", true)
            // The dump holds Emacs's own preloaded Lisp, nothing of the kit, so it
            // is keyed on the engine alone: a Companion-only update keeps it.
            val dump = locateDump(home, BuildConfig.ENGINE_ID)
            val classPath = app.applicationInfo.sourceDir
            // classPath doubles as the APK whose mtime stamps every /assets file.
            EmacsNative.setEmacsParams(
                app.assets,
                home.root.canonicalPath,
                EmacsService.getLibraryDirectory(app),
                app.cacheDir.canonicalPath,
                0.0f, 0.0f, 0.0f, 0,
                classPath,
                null,
                Build.VERSION.SDK_INT,
            )
            val argv = arrayOf("libandroid-emacs.so", "--fg-daemon=jetpacs")
            Log.i(TAG, "initEmacs ${argv.toList()} home=${home.root} dump=${dump ?: "(none, loadup will dump)"}")
            EmacsNative.initEmacs(argv, dump)
            // initEmacs returns only if Emacs's main loop ends without exit().
            Log.w(TAG, "initEmacs returned; ending the engine process")
        } catch (t: Throwable) {
            Log.e(TAG, "engine failed to start", t)
        }
        Process.killProcess(Process.myPid())
    }

    /**
     * The dump Emacs's loadup wrote into HOME as `emacs-<fingerprint>.pdmp`,
     * if it belongs to this engine build: the fingerprint covers the native
     * build, the stamp the engine's Lisp assets (and, once the kit is
     * preloaded into the dump, the kit). Any other `.pdmp` in HOME is stale
     * and removed.
     */
    private fun locateDump(home: EngineHome, stamp: String): String? {
        val wanted = "emacs-${EmacsNative.getFingerprint()}.pdmp"
        home.root.listFiles()?.forEach { f ->
            if (f.name.endsWith(".pdmp") && f.name != wanted) f.delete()
        }
        val dump = File(home.root, wanted)
        val dumpStamp = File(home.root, "dump.stamp")
        if (dump.isFile && dumpStamp.takeIf { it.isFile }?.readText() == stamp) return dump.path
        dump.delete()
        // loadup dumps on this start; the stamp names what it will contain.
        dumpStamp.writeText(stamp)
        return null
    }

    /** What android.c does when a service object is present: stderr and stdout into logcat. */
    private fun pipeStandardStreamsToLog() {
        val fds = Os.pipe()
        Os.dup2(fds[1], 1)
        Os.dup2(fds[1], 2)
        Os.close(fds[1])
        Thread({
            FileInputStream(fds[0]).bufferedReader().forEachLine { Log.i("Emacs", it) }
        }, "Emacs stderr").apply { isDaemon = true }.start()
    }
}
