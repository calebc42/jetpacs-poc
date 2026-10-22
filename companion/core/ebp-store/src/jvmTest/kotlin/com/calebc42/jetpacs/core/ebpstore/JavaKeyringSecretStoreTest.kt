// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The OS keychain itself. Skipped, with the reason, on a host without a
 * usable backend (a headless Linux session without a Secret Service daemon);
 * run by hand on a desktop with a keyring before D5 relies on it.
 */
class JavaKeyringSecretStoreTest {
    @Test
    fun theKeychainStoresReturnsAndDeletesASecret() {
        val reasons = mutableListOf<String>()
        val store = JavaKeyringSecretStore.openOrNull(service = "jetpacs-test", report = reasons::add)
        assumeTrue("no usable OS keychain on this host: $reasons", store != null)
        store!!
        val account = "test-" + ByteArray(8).also(SecureRandom()::nextBytes).toLowerHex()

        try {
            assertNull(store.get(account))
            store.set(account, "AAECAwQFBgcICQoLDA0ODw")
            assertEquals("AAECAwQFBgcICQoLDA0ODw", store.get(account))
            assertTrue(store.protection is CredentialProtection.Keystore)
        } finally {
            store.delete(account)
        }
        assertNull(store.get(account))
    }
}
