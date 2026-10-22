// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import java.nio.file.Path

/**
 * Where a desktop keeps secret text by account name. Values are short
 * base64url strings; the store never interprets them.
 */
interface SecretStore {
    val protection: CredentialProtection

    /** The stored value, or null when the account has none. */
    fun get(account: String): String?

    fun set(account: String, value: String)

    /** Removes the account; an absent account is not an error. */
    fun delete(account: String)
}

/**
 * The desktop's secret store: the OS keychain when one answers a probe,
 * otherwise SPEC §9.1's floor, `credentials.json` inside [dataDirectory].
 * [report] receives the reason whenever the keychain is passed over, so the
 * host can show it with the reduced-protection notice.
 */
fun openSecretStore(
    dataDirectory: Path,
    service: String = JavaKeyringSecretStore.SERVICE,
    report: (String) -> Unit = {},
): SecretStore =
    JavaKeyringSecretStore.openOrNull(service, report)
        ?: PrivateFileSecretStore(dataDirectory.resolve(PrivateFileSecretStore.FILE_NAME))
