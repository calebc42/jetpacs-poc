// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The OS keychain through java-keyring: the macOS Keychain, the Windows
 * Credential Manager, GNOME's Secret Service over D-Bus, or KWallet. Items
 * are stored under one service name with the key alias as the account.
 */
class JavaKeyringSecretStore private constructor(
    private val keyring: Keyring,
    private val service: String,
) : SecretStore {
    override val protection: CredentialProtection =
        CredentialProtection.Keystore(keyring.keyringStorageType.name)

    override fun get(account: String): String? = try {
        keyring.getPassword(service, account)
    } catch (_: PasswordAccessException) {
        null
    }

    override fun set(account: String, value: String) {
        keyring.setPassword(service, account, value)
    }

    override fun delete(account: String) {
        try {
            keyring.deletePassword(service, account)
        } catch (_: PasswordAccessException) {
            // Absent already.
        }
    }

    companion object {
        const val SERVICE = "jetpacs"

        /**
         * Opens the keychain and proves it works with a set, get and delete
         * of a throwaway account, within [timeoutMillis] so a hung daemon
         * cannot stall a host's start. Null, with the reason passed to
         * [report], means the caller falls back to the private file.
         */
        fun openOrNull(
            service: String = SERVICE,
            report: (String) -> Unit = {},
            timeoutMillis: Long = 5_000,
        ): JavaKeyringSecretStore? {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "jetpacs-keychain-probe").apply { isDaemon = true }
            }
            return try {
                executor.submit<JavaKeyringSecretStore?> { probe(service, report) }
                    .get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                report("the OS keychain did not answer within $timeoutMillis ms")
                null
            } finally {
                executor.shutdownNow()
            }
        }

        private fun probe(service: String, report: (String) -> Unit): JavaKeyringSecretStore? {
            val keyring = try {
                Keyring.create()
            } catch (failure: BackendNotSupportedException) {
                report("no OS keychain backend: " + failure.message)
                return null
            }
            val store = JavaKeyringSecretStore(keyring, service)
            val random = SecureRandom()
            val account = "probe-" + ByteArray(8).also(random::nextBytes).toLowerHex()
            val value = ByteArray(16).also(random::nextBytes).toLowerHex()
            return try {
                store.set(account, value)
                val read = store.get(account)
                store.delete(account)
                if (read == value && store.get(account) == null) {
                    store
                } else {
                    report(keyring.keyringStorageType.name + " did not store, return and delete a probe item")
                    null
                }
            } catch (failure: Exception) {
                report(keyring.keyringStorageType.name + " failed its probe: " + failure.message)
                null
            }
        }
    }
}
