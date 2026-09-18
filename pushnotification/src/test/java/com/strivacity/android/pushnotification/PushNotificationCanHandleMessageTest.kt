package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.support.ENVELOPE_KEY
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.envelopeMessageData
import com.strivacity.android.pushnotification.support.failIfCalled
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `canHandleMessage` through [PushNotificationInterface]. Pure parsing plus a storage
 * lookup — no backend call is ever expected. One test per independently-verifiable outcome.
 */
class PushNotificationCanHandleMessageTest {
    private val issuer = "https://issuer.example.com"
    private val accountId = "account-1"

    @Test
    fun `canHandleMessage returns false when there is no message data`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(null))
            assertFalse(sdk.canHandleMessage(emptyMap()))
        }

    @Test
    fun `canHandleMessage returns false when the envelope key is absent`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(mapOf("some.other.key" to "value")))
        }

    @Test
    fun `canHandleMessage returns false when message data carries only unrelated keys`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            val realisticNoise = mapOf("title" to "Hello", "body" to "You have a notification", "gcm.n.e" to "1")
            assertFalse(sdk.canHandleMessage(realisticNoise))
        }

    @Test
    fun `canHandleMessage returns false when the envelope value is not valid JSON`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(mapOf(ENVELOPE_KEY to "not json")))
        }

    @Test
    fun `canHandleMessage returns false for an unsupported envelope version`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId, version = "2")))
        }

    @Test
    fun `canHandleMessage returns false when a required envelope field is blank`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId, challengeId = "")))
            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId, challenge = "")))
            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId = "")))
            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer = "", accountId)))
        }

    @Test
    fun `canHandleMessage returns false when no key is stored for the issuer and account`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId)))
        }

    @Test
    fun `canHandleMessage returns true when a key is stored for the issuer and account`() =
        runTest {
            val sdk = sdkWithStoredKey()

            assertTrue(sdk.canHandleMessage(envelopeMessageData(issuer, accountId)))
        }

    @Test
    fun `canHandleMessage returns false for a different account under the same issuer`() =
        runTest {
            val sdk = sdkWithStoredKey()

            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, "a-different-account")))
        }

    @Test
    fun `canHandleMessage returns false for a different issuer than the one enrolled`() =
        runTest {
            val sdk = sdkWithStoredKey()

            assertFalse(sdk.canHandleMessage(envelopeMessageData("https://a-different-issuer.example.com", accountId)))
        }

    @Test
    fun `canHandleMessage returns true even when the envelope carries unknown extra fields`() =
        runTest {
            val sdk = sdkWithStoredKey()
            val envelopeWithExtraField =
                mapOf(
                    ENVELOPE_KEY to
                        """{"version":"1","challengeId":"challenge-1","challenge":"challenge-value",""" +
                        """"accountId":"$accountId","issuer":"$issuer","aFieldFromANewerSdk":"???"}""",
                )

            assertTrue(sdk.canHandleMessage(envelopeWithExtraField))
        }

    @Test
    fun `canHandleMessage returns false for a malformed issuer, without throwing`() =
        runTest {
            val sdk = sdkWithNoStoredKey()

            assertFalse(sdk.canHandleMessage(envelopeMessageData("not a url", accountId)))
        }

    private suspend fun sdkWithStoredKey(): PushNotification {
        val storage = FakePushNotificationStorage()
        storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
        return createTestPushNotificationSdk(storage) { failIfCalled() }
    }

    private fun sdkWithNoStoredKey(): PushNotification = createTestPushNotificationSdk { failIfCalled() }
}
