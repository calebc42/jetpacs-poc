// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.jetpacs.companion.host.CompanionTransport
import com.calebc42.jetpacs.companion.host.ConnectionSink
import com.calebc42.jetpacs.companion.host.HostLog
import com.calebc42.jetpacs.companion.host.ServeOutcome
import com.calebc42.jetpacs.companion.host.TransportStatus
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SPEC 5.6 `emacs-unix`: a daemon Emacs binds a filesystem Unix-domain
 * socket and this Companion dials it. Before every dial the socket file is
 * inspected and refused, terminally, when it is a symbolic link, not a
 * socket, or owned by another user. A missing or refusing socket, and any
 * close the Companion did not initiate, are followed by the SPEC 5.7
 * jittered redial ([RedialBackoff]); a close the Companion initiated on a
 * READY session is redialed at once. Emacs speaks first, so a dialed
 * connection that stays silent for [helloDeadlineMs] is closed and redialed
 * with backoff: the Companion sends nothing before `session.hello`, which
 * makes that abort free of side effects. `java.nio.channels` has no Android
 * variant, which is why this lives in the desktop module.
 */
class EmacsUnixDialer(
    private val path: Path,
    private val log: HostLog,
    private val ownerCheck: SocketOwnerCheck = SocketOwnerCheck.CurrentUser,
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val helloDeadlineMs: Long = HELLO_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : CompanionTransport {
    override val profile: String = "emacs-unix"
    override val address: String get() = path.toString()

    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> get() = _status

    @Volatile private var current: ChannelConnection? = null
    @Volatile private var stopping = false

    override fun run(running: () -> Boolean, sink: ConnectionSink) {
        stopping = false
        var attempt = 0
        try {
            while (running() && !stopping) {
                var connection: ChannelConnection? = null
                var outcome = ServeOutcome(reachedReady = false)
                try {
                    when (val verdict = ownerCheck.inspect(path)) {
                        is SocketVerdict.Refused -> {
                            // SPEC 5.6: terminal, never redialed, and visible.
                            _status.value = TransportStatus.Failed(verdict.reason)
                            log.e(TAG, "refusing to dial $path: ${verdict.reason}")
                            return
                        }
                        SocketVerdict.Absent, SocketVerdict.Dialable -> Unit
                    }
                    _status.value = TransportStatus.Dialing(attempt, null)
                    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                    try {
                        channel.connect(UnixDomainSocketAddress.of(path))
                    } catch (failure: Throwable) {
                        channel.runCatching { close() }
                        throw failure
                    }
                    val opened = ChannelConnection(channel, path.toString())
                    connection = opened
                    current = opened
                    _status.value = TransportStatus.Connected(path.toString())
                    val handle = sink.accepted(opened)
                    if (handle.join(helloDeadlineMs) == null && !opened.bytesSeen) {
                        log.w(TAG, "no session.hello from $path within $helloDeadlineMs ms; closing")
                        opened.abort("no session.hello within $helloDeadlineMs ms")
                    }
                    outcome = handle.join() ?: ServeOutcome(reachedReady = false)
                } catch (failure: IOException) {
                    // ENOENT, ECONNREFUSED or a close mid-connect: the redial follows.
                    log.d(TAG, "dial $path failed: $failure")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (failure: Exception) {
                    log.e(TAG, "dial loop error on $path", failure)
                } finally {
                    current = null
                }
                if (!running() || stopping) return
                if (outcome.reachedReady) attempt = 0
                val delay = if (connection?.closedByCompanion == true && outcome.reachedReady) {
                    0L
                } else {
                    RedialBackoff.delayMs(attempt++, random())
                }
                _status.value = TransportStatus.Dialing(attempt, clock() + delay)
                if (delay > 0) {
                    try {
                        sleep(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        } finally {
            if (_status.value !is TransportStatus.Failed) _status.value = TransportStatus.Idle
        }
    }

    override fun stop() {
        stopping = true
        current?.runCatching { close() }
    }

    companion object {
        const val HELLO_DEADLINE_MS = 10_000L
        private const val TAG = "EbpDialer"
    }
}

/** SPEC 5.7's jittered bounded redial: 1 s doubling to 15 s, scaled by a factor in [0.75, 1.0]. */
object RedialBackoff {
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 15_000L

    /** The delay before dial [attempt] (0-based), with [unit] in [0, 1] as the jitter draw. */
    fun delayMs(attempt: Int, unit: Double): Long {
        val base = min(MAX_DELAY_MS, INITIAL_DELAY_MS shl min(attempt, 20))
        return (base * (0.75 + 0.25 * unit.coerceIn(0.0, 1.0))).toLong()
    }
}

/** What inspecting the socket file concluded. */
sealed interface SocketVerdict {
    /** No file yet: Emacs has not bound; dial anyway and let the connect fail into the redial. */
    data object Absent : SocketVerdict

    data object Dialable : SocketVerdict

    /** SPEC 5.6: terminal. */
    data class Refused(val reason: String) : SocketVerdict
}

/** The inspection a dialer runs before every attempt. */
fun interface SocketOwnerCheck {
    fun inspect(path: Path): SocketVerdict

    companion object {
        /** `sun_path` holds 108 bytes on Linux and 104 on macOS, NUL included. */
        const val MAX_PATH_BYTES = 100

        /**
         * The production check: the file must exist as a socket, not through
         * a symbolic link, and be owned by the running user, the same
         * principal rule `ensurePrivateDirectory` applies to the data
         * directory.
         */
        val CurrentUser: SocketOwnerCheck = SocketOwnerCheck { path ->
            if (path.toString().toByteArray().size > MAX_PATH_BYTES) {
                return@SocketOwnerCheck SocketVerdict.Refused("socket path longer than $MAX_PATH_BYTES bytes: $path")
            }
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return@SocketOwnerCheck SocketVerdict.Absent
            } catch (failure: IOException) {
                return@SocketOwnerCheck SocketVerdict.Refused("cannot inspect $path: $failure")
            }
            if (attributes.isSymbolicLink) return@SocketOwnerCheck SocketVerdict.Refused("$path is a symbolic link")
            if (!attributes.isOther) return@SocketOwnerCheck SocketVerdict.Refused("$path is not a socket")
            val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
            val user = path.fileSystem.userPrincipalLookupService
                .lookupPrincipalByName(System.getProperty("user.name"))
            if (owner != user) {
                return@SocketOwnerCheck SocketVerdict.Refused("$path is owned by ${owner.name}, not ${user.name}")
            }
            SocketVerdict.Dialable
        }
    }
}

/**
 * ebp.el's default socket path (`ebp-unix-socket-directory` and
 * `ebp-unix-socket-name`), so a daemon Emacs and this Companion meet with no
 * configuration: `$XDG_RUNTIME_DIR/ebp/ebp.sock` when that variable is
 * absolute, else `$TMPDIR/ebp<uid>/ebp.sock` with `/tmp` for an unset
 * `TMPDIR`. Null where no uid can be read (Windows), where SPEC 5.6 is not
 * offered.
 */
object EmacsUnixSocketPath {
    const val DIRECTORY = "ebp"
    const val NAME = "ebp.sock"

    fun default(environment: (String) -> String?, uid: () -> Int?): Path? {
        val runtime = environment("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
            ?.let { java.nio.file.Paths.get(it) }?.takeIf { it.isAbsolute }
        if (runtime != null) return runtime.resolve(DIRECTORY).resolve(NAME)
        val id = uid() ?: return null
        val temporary = environment("TMPDIR")?.takeIf { it.isNotBlank() } ?: "/tmp"
        return java.nio.file.Paths.get(temporary, "ebp$id", NAME)
    }

    /** The running user's uid through the `unix` attribute view, or null where there is none. */
    fun currentUid(userHome: Path): Int? =
        runCatching { Files.getAttribute(userHome, "unix:uid") as? Int }.getOrNull()
}
