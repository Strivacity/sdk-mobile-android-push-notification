package com.strivacity.android.pushnotification.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [EnrollmentGrant.parse].
 */
@RunWith(RobolectricTestRunner::class)
class EnrollmentGrantParseTest {
    @Test
    fun `parse returns a grant with every field populated`() {
        val grant = EnrollmentGrant.parse(grantUri())

        assertEquals("https://issuer.example.com", grant.issuer)
        assertEquals("ott-1", grant.oneTimeToken)
        assertEquals("account-1", grant.accountId)
        assertEquals("Jane", grant.displayUserName)
    }

    @Test
    fun `parse throws InvalidGrantException naming the missing parameter when issuer is missing`() {
        val failure = assertThrows(InvalidGrantException::class.java) { EnrollmentGrant.parse(grantUri(issuer = null)) }

        assertTrue(failure.message.orEmpty().contains("issuer"))
    }

    @Test
    fun `parse throws InvalidGrantException naming the missing parameter when one_time_token is missing`() {
        val failure =
            assertThrows(InvalidGrantException::class.java) {
                EnrollmentGrant.parse(grantUri(oneTimeToken = null))
            }

        assertTrue(failure.message.orEmpty().contains("one_time_token"))
    }

    @Test
    fun `parse throws InvalidGrantException naming the missing parameter when account_id is missing`() {
        val failure =
            assertThrows(InvalidGrantException::class.java) {
                EnrollmentGrant.parse(grantUri(accountId = null))
            }

        assertTrue(failure.message.orEmpty().contains("account_id"))
    }

    @Test
    fun `parse treats a present but empty required parameter as missing`() {
        val failure = assertThrows(InvalidGrantException::class.java) { EnrollmentGrant.parse(grantUri(issuer = "")) }

        assertTrue(failure.message.orEmpty().contains("issuer"))
    }

    @Test
    fun `parse ignores unknown extra query parameters`() {
        val uri = grantUri(extraParams = mapOf("utm_source" to "email"))

        val grant = EnrollmentGrant.parse(uri)

        assertEquals("account-1", grant.accountId)
    }

    @Test
    fun `parse throws InvalidGrantException naming the missing parameter when username is missing`() {
        val failure1 =
            assertThrows(InvalidGrantException::class.java) { EnrollmentGrant.parse(grantUri(username = null)) }
        assertTrue(failure1.message.orEmpty().contains("username"))

        val failure2 =
            assertThrows(InvalidGrantException::class.java) { EnrollmentGrant.parse(grantUri(username = "   ")) }
        assertTrue(failure2.message.orEmpty().contains("username"))
    }

    @Test
    fun `parse throws InvalidGrantException, not UnsupportedOperationException, for an opaque Uri`() {
        // An opaque Uri (e.g. "mailto:x", no "//") has no query component to read at all —
        // Uri.getQueryParameter throws UnsupportedOperationException on one; the SDK must not
        // let that leak through as-is.
        val opaqueUri = Uri.parse("mailto:someone@example.com")

        assertThrows(InvalidGrantException::class.java) { EnrollmentGrant.parse(opaqueUri) }
    }

    @Test
    fun `parse propagates InvalidIssuerException when the issuer parameter is not a valid https URL`() {
        assertThrows(InvalidIssuerException::class.java) { EnrollmentGrant.parse(grantUri(issuer = "not-a-url")) }
    }

    @Test
    fun `parse uses the first occurrence of a repeated query parameter`() {
        val uri =
            Uri
                .parse("https://app.example.com/enroll")
                .buildUpon()
                .appendQueryParameter("issuer", "https://issuer.example.com")
                .appendQueryParameter("one_time_token", "first-token")
                .appendQueryParameter("one_time_token", "second-token")
                .appendQueryParameter("account_id", "account-1")
                .appendQueryParameter("username", "Jane")
                .build()

        val grant = EnrollmentGrant.parse(uri)

        assertEquals("first-token", grant.oneTimeToken)
    }

    private fun grantUri(
        issuer: String? = "https://issuer.example.com",
        oneTimeToken: String? = "ott-1",
        accountId: String? = "account-1",
        username: String? = "Jane",
        extraParams: Map<String, String> = emptyMap(),
    ): Uri {
        val builder = Uri.parse("https://app.example.com/enroll").buildUpon()
        issuer?.let { builder.appendQueryParameter("issuer", it) }
        oneTimeToken?.let { builder.appendQueryParameter("one_time_token", it) }
        accountId?.let { builder.appendQueryParameter("account_id", it) }
        username?.let { builder.appendQueryParameter("username", it) }
        extraParams.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build()
    }
}
