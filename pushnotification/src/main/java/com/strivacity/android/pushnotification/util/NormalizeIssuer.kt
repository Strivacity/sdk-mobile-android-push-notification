package com.strivacity.android.pushnotification.util

import com.strivacity.android.pushnotification.model.InvalidIssuerException
import java.net.URI

private const val SCHEME_HTTPS = "https"

/**
 * Reduces an issuer to `https://authority` (scheme and lowercased authority).
 *
 * @throws InvalidIssuerException if the value is not an absolute `https` URL.
 */
internal fun normalizeIssuer(issuer: String): String {
    val uri = runCatching { URI(issuer.trim()) }.getOrNull()
    val authority = uri?.authority

    if (!uri?.scheme.equals(SCHEME_HTTPS, ignoreCase = true) || authority.isNullOrBlank() || uri.userInfo != null) {
        throw InvalidIssuerException(issuer)
    }

    return "$SCHEME_HTTPS://${authority.lowercase()}"
}
