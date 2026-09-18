package com.strivacity.android.pushnotification.model

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Exercises [EnrollmentGrant]'s own logic directly.
 * [EnrollmentGrant.parse] is covered separately in [EnrollmentGrantParseTest].
 */
class EnrollmentGrantTest {
    @Test
    fun `toString does not contain the one-time token`() {
        val grant =
            EnrollmentGrant(
                issuer = "https://issuer.example.com",
                oneTimeToken = "super-secret-ott",
                accountId = "account-1",
                displayUserName = "Jane",
            )

        assertFalse(grant.toString().contains("super-secret-ott"))
    }
}
