package com.strivacity.android.pushnotification.model

import android.net.Uri
import com.strivacity.android.pushnotification.util.normalizeIssuer

/**
 * The payload authorizing this device to enroll, delivered out of band as a URL — scanned from a
 * QR code, or arriving as a deep link's intent data.
 */
public data class EnrollmentGrant(
    /** Normalized: scheme plus lowercased authority. */
    val issuer: String,
    val oneTimeToken: String,
    val accountId: String,
    val displayUserName: String,
) {
    /** Omits [oneTimeToken]. */
    override fun toString(): String =
        "EnrollmentGrant(issuer=$issuer, accountId=$accountId, displayUserName=$displayUserName)"

    public companion object {
        /** Wire name for [EnrollmentGrant.issuer]. */
        private const val PARAM_ISSUER = "issuer"
        private const val PARAM_ONE_TIME_TOKEN = "one_time_token"
        private const val PARAM_ACCOUNT_ID = "account_id"
        private const val PARAM_USER_NAME = "username"

        /**
         * Reads a grant from a scanned or deep-linked [Uri]. Converting a scanned string or
         * intent to a [Uri] is the app's responsibility.
         *
         * Unknown query parameters are ignored.
         *
         * @throws InvalidGrantException if a required parameter is missing, or the [Uri] has no
         *   query. The message never includes parameter values.
         * @throws InvalidIssuerException if the issuer parameter isn't an absolute `https` URL.
         */
        @JvmStatic
        public fun parse(payload: Uri): EnrollmentGrant {
            // An opaque Uri (no "//") has no query component.
            if (payload.isOpaque) {
                throw InvalidGrantException("Enrollment grant has no readable query parameters")
            }

            return EnrollmentGrant(
                issuer = normalizeIssuer(payload.required(PARAM_ISSUER)),
                oneTimeToken = payload.required(PARAM_ONE_TIME_TOKEN),
                accountId = payload.required(PARAM_ACCOUNT_ID),
                displayUserName = payload.required(PARAM_USER_NAME),
            )
        }

        private fun Uri.required(name: String): String =
            getQueryParameter(name)?.takeIf { it.isNotBlank() }
                ?: throw InvalidGrantException("Enrollment grant is missing the '$name' parameter")
    }
}
