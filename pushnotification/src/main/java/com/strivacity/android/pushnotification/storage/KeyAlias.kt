package com.strivacity.android.pushnotification.storage

import com.strivacity.android.pushnotification.util.Base64Url
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Keystore alias scheme.
 *
 * ```
 * sty . base64url(sha256(issuer ‖ 0x00 ‖ accountId)) . keyId
 * ```
 *
 * `sty` separates the SDK's keys from the host app's own. The hashed segment is per issuer and
 * account. `keyId` is a random per-key id, also used as the JWT `kid`.
 */
internal object KeyAlias {
    private const val PREFIX = "sty"
    private const val SEPARATOR = "."
    private const val DIGEST = "SHA-256"
    private const val KEY_ID_SIZE = 16

    private val random = SecureRandom()

    /** A fresh, random key id. */
    fun newKeyId(): String = Base64Url.encode(ByteArray(KEY_ID_SIZE).also { random.nextBytes(it) })

    /** Everything up to and including the separator before the key id. */
    fun prefixFor(
        issuer: String,
        accountId: String,
    ): String {
        val digest =
            MessageDigest.getInstance(DIGEST).run {
                update(issuer.toByteArray(Charsets.UTF_8))
                // Delimiter between issuer and accountId.
                update(0)
                update(accountId.toByteArray(Charsets.UTF_8))
                digest()
            }
        return "$PREFIX$SEPARATOR${Base64Url.encode(digest)}$SEPARATOR"
    }

    fun of(
        issuer: String,
        accountId: String,
        keyId: String,
    ): String = prefixFor(issuer, accountId) + keyId

    fun keyIdOf(alias: String): String = alias.substringAfterLast(SEPARATOR)

    /** Whether this alias is one of the SDK's, rather than the host app's. */
    fun isOurs(alias: String): Boolean = alias.startsWith("$PREFIX$SEPARATOR")
}
