package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.model.InvalidIssuerException
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.failIfCalled
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `hasEnrollment` through [PushNotificationInterface]. Pure storage lookup against
 * the recorded enrollments — no backend call is ever expected, so every test wires the SDK to
 * fail if one happens.
 */
class PushNotificationHasEnrollmentTest {
    private val issuer = "https://issuer.example.com"
    private val accountId = "account-1"

    @Test
    fun `hasEnrollment returns true when an enrollment is recorded for the issuer and account`() =
        runTest {
            val sdk = createTestPushNotificationSdk(enrolledStorage()) { failIfCalled() }

            assertTrue(sdk.hasEnrollment(issuer, accountId))
        }

    @Test
    fun `hasEnrollment returns false when no enrollment is recorded`() =
        runTest {
            val sdk = createTestPushNotificationSdk { failIfCalled() }

            assertFalse(sdk.hasEnrollment(issuer, accountId))
        }

    @Test
    fun `hasEnrollment returns false when only a key without an enrollment record is stored`() =
        runTest {
            val storage = FakePushNotificationStorage()
            storage.storeKeyWithoutEnrollment(issuer, accountId)
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            assertFalse(sdk.hasEnrollment(issuer, accountId))
        }

    @Test
    fun `hasEnrollment returns false for a different account under the same issuer`() =
        runTest {
            val sdk = createTestPushNotificationSdk(enrolledStorage()) { failIfCalled() }

            assertFalse(sdk.hasEnrollment(issuer, "a-different-account"))
        }

    @Test
    fun `hasEnrollment returns false for a different issuer with the same account`() =
        runTest {
            val sdk = createTestPushNotificationSdk(enrolledStorage()) { failIfCalled() }

            assertFalse(sdk.hasEnrollment("https://a-different-issuer.example.com", accountId))
        }

    @Test
    fun `hasEnrollment finds an enrollment regardless of the issuer's casing`() =
        runTest {
            val sdk = createTestPushNotificationSdk(enrolledStorage()) { failIfCalled() }

            assertTrue(sdk.hasEnrollment("https://ISSUER.EXAMPLE.COM", accountId))
        }

    @Test
    fun `hasEnrollment throws InvalidIssuerException for a malformed issuer`() =
        runTest {
            val sdk = createTestPushNotificationSdk { failIfCalled() }

            assertThrows<InvalidIssuerException> { sdk.hasEnrollment("not a url", accountId) }
        }

    /** Storage holding an enrollment for [issuer] and [accountId]. */
    private suspend fun enrolledStorage(): FakePushNotificationStorage {
        val storage = FakePushNotificationStorage()
        storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
        return storage
    }
}
