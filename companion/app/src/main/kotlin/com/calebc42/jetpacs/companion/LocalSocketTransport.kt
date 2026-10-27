// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.system.Os
import com.calebc42.jetpacs.companion.host.CompanionConnection
import com.calebc42.jetpacs.companion.host.CompanionTransport
import com.calebc42.jetpacs.companion.host.ConnectionSink
import com.calebc42.jetpacs.companion.host.HostLog
import com.calebc42.jetpacs.companion.host.TransportStatus
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SPEC 5.3 `loopback-unix` for Android: the Companion binds a
 * filesystem-namespace Unix socket under its private files directory (never
 * the abstract namespace, which carries no permission bits), the directory
 * 0700 and the socket 0600, a stale socket of ours unlinked before the bind
 * and a connectable one refused. Every accepted peer's credentials are
 * checked against this process's UID, stronger than the filesystem floor,
 * which is what makes one socket safe between the Companion process and the
 * engine process of the bundled flavor (docs/PLAN-bundled-emacs.md, E4).
 * Lives in `:app` because `:host` may not name `android.*`; the mechanics
 * were proved by ebp-java's `LocalSocketServer`.
 */
internal class LocalSocketTransport(
    private val socketFile: File,
    private val log: HostLog,
) : CompanionTransport {
    override val profile: String = "loopback-unix"
    override val address: String get() = socketFile.path

    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> get() = _status

    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var bound: LocalSocket? = null

    override fun run(running: () -> Boolean, sink: ConnectionSink) {
        while (running()) {
            try {
                bind()
                _status.value = TransportStatus.ListeningPath(socketFile.path)
                while (running()) {
                    val socket = checkNotNull(server).accept()
                    val uid = runCatching { socket.peerCredentials.uid }.getOrNull()
                    if (uid != Process.myUid()) {
                        // SPEC 5.3 floor plus the process-credential check.
                        log.w(TAG, "refusing peer uid $uid on ${socketFile.path}")
                        socket.runCatching { close() }
                        continue
                    }
                    sink.accepted(LocalConnection(socket, socketFile.path))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (!running()) return
                log.w(TAG, "Unix listener restarting", e)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            } finally {
                closeListener()
                _status.value = TransportStatus.Idle
            }
        }
    }

    override fun stop() {
        closeListener()
    }

    private fun bind() {
        val dir = socketFile.absoluteFile.parentFile ?: throw IOException("no directory for $socketFile")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        Os.chmod(dir.path, "700".toInt(8))
        if (socketFile.exists()) {
            if (connectable(socketFile)) throw IOException("socket in use: $socketFile")
            if (!socketFile.delete()) throw IOException("cannot unlink stale socket $socketFile")
        }
        val local = LocalSocket()
        local.bind(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
        bound = local
        server = LocalServerSocket(local.fileDescriptor)
        Os.chmod(socketFile.path, "600".toInt(8))
    }

    private fun closeListener() {
        server?.runCatching { close() }
        bound?.runCatching { close() }
        server = null
        bound = null
        socketFile.delete()
    }

    private fun connectable(path: File): Boolean {
        val probe = LocalSocket()
        return try {
            probe.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
            true
        } catch (_: IOException) {
            false
        } finally {
            probe.runCatching { close() }
        }
    }

    /** A same-UID peer on the socket. The stream getters throw for a closed socket exactly where the socket did. */
    private class LocalConnection(private val socket: LocalSocket, path: String) : CompanionConnection {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override val description: String = "unix:$path"

        @Volatile override var closedByCompanion: Boolean = false
            private set

        override fun abort(reason: String) {
            closedByCompanion = true
            close()
        }

        override fun close() {
            socket.runCatching { close() }
        }
    }

    private companion object {
        const val TAG = "EbpBridge"
    }
}
