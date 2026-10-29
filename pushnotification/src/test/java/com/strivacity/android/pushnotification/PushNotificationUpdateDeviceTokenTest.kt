package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.UpdateTokenException
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.failIfCalled
import com.strivacity.android.pushnotification.support.jwtPayloadClaims
import com.strivacity.android.pushnotification.support.respondFailure
import com.strivacity.android.pushnotification.support.respondSuccess
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises `updateDeviceToken` through [PushNotificationInterface]. One test per
 * independently-verifiable outcome.
 */
class PushNotificationUpdateDeviceTokenTest {
    private val issuer = "https://issuer.example.com"
    private val accountId = "account-1"

    // --- backend accepts ---

    @Test
    fun `updateDeviceToken sends an updateToken request to the backend`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val sdk =
                enrolledSdk { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.updateDeviceToken(issuer, accountId, "token-abc", DeviceTokenType.FCM_TOKEN)

            val request = requireNotNull(capturedRequest) { "no request reached the backend" }
            assertEquals("$issuer/pushNotification/api/v1/updateToken", request.url.toString())
            assertEquals("application/jwt", request.body.contentType.toString())
        }

    @Test
    fun `updateDeviceToken signs the account id, device token type, and device token as claims`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val sdk =
                enrolledSdk { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            for (deviceTokenType in listOf(DeviceTokenType.FCM_TOKEN, DeviceTokenType.FCM_FID)) {
                sdk.updateDeviceToken(issuer, accountId, "token-for-$deviceTokenType", deviceTokenType)

                val claims = requireNotNull(capturedRequest).jwtPayloadClaims()
                assertEquals(accountId, claims["accountId"]?.jsonPrimitive?.content)
                assertEquals(deviceTokenType.wireValue(), claims["deviceTokenType"]?.jsonPrimitive?.content)
                assertEquals("token-for-$deviceTokenType", claims["deviceToken"]?.jsonPrimitive?.content)
            }
        }

    // --- backend refuses ---

    @Test
    fun `updateDeviceToken throws UpdateTokenException carrying the status code when the backend refuses`() =
        runTest {
            val sdk = enrolledSdk { respondFailure() }

            val failure =
                assertThrows<UpdateTokenException> {
                    sdk.updateDeviceToken(issuer, accountId, "token-abc", DeviceTokenType.FCM_TOKEN)
                }

            assertEquals(400, failure.status)
        }

    @Test
    fun `updateDeviceToken makes exactly one request even when the backend fails`() =
        runTest {
            var requestCount = 0
            val sdk =
                enrolledSdk {
                    requestCount++
                    respondFailure()
                }

            runCatching { sdk.updateDeviceToken(issuer, accountId, "token-abc", DeviceTokenType.FCM_TOKEN) }

            assertEquals("the SDK does not retry — retrying is the caller's decision", 1, requestCount)
        }

    // --- not enrolled ---

    @Test
    fun `updateDeviceToken throws NotEnrolledException when no key is stored for the account`() =
        runTest {
            val sdk =
                createTestPushNotificationSdk { failIfCalled() }

            assertThrows<NotEnrolledException> {
                sdk.updateDeviceToken(issuer, accountId, "token-abc", DeviceTokenType.FCM_TOKEN)
            }
        }

    private suspend fun enrolledSdk(
        handleRequest: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ): PushNotification {
        val storage = FakePushNotificationStorage()
        storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
        return createTestPushNotificationSdk(storage, handleRequest)
    }

    private fun DeviceTokenType.wireValue(): String =
        when (this) {
            DeviceTokenType.FCM_TOKEN -> "fcmToken"
            DeviceTokenType.FCM_FID -> "fcmFid"
        }
}
