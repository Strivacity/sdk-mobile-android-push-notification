package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.ChallengeResolution
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.ResolutionResult
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.failIfCalled
import com.strivacity.android.pushnotification.support.jwtBody
import com.strivacity.android.pushnotification.support.jwtHeaderClaims
import com.strivacity.android.pushnotification.support.jwtPayloadClaims
import com.strivacity.android.pushnotification.support.respondFailure
import com.strivacity.android.pushnotification.support.respondSuccess
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises `resolveChallenge` through [PushNotificationInterface]. One test per
 * independently-verifiable outcome.
 *
 * A [ChallengeResolution] is built directly here (its constructor is `internal`, visible from
 * this module) rather than via `Challenge.deny()`/`approve()`/`select()` — those build the right
 * `action` string, which is a separate concern from what `resolveChallenge` itself does with a
 * resolution it's handed.
 */
class PushNotificationResolveChallengeTest {
    private val issuer = "https://issuer.example.com"
    private val accountId = "account-1"
    private val challengeId = "challenge-1"
    private val challengeValue = "challenge-value"

    // --- not enrolled ---

    @Test
    fun `resolveChallenge throws NotEnrolledException when no key is stored for the account`() =
        runTest {
            val sdk = createTestPushNotificationSdk { failIfCalled() }

            assertThrows<NotEnrolledException> { sdk.resolveChallenge(resolution(action = "approve")) }
        }

    // --- backend accepts ---

    @Test
    fun `resolveChallenge sends a resolution request to the backend`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val sdk =
                enrolledSdk { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.resolveChallenge(resolution(action = "approve"))

            val request = requireNotNull(capturedRequest) { "no request reached the backend" }
            assertEquals("$issuer/pushNotification/api/v1/actions/$challengeId/submit", request.url.toString())
            assertEquals("application/jwt", request.body.contentType.toString())
            assertEquals(
                "the request body should be nothing but the JWT",
                3,
                request.jwtBody().split(".").size,
            )
        }

    @Test
    fun `resolveChallenge signs the challenge id, challenge value, and action as claims`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val storage = FakePushNotificationStorage()
            val storedKey = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            val sdk =
                createTestPushNotificationSdk(storage) { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.resolveChallenge(resolution(action = "approve"))

            val request = requireNotNull(capturedRequest)
            val payload = request.jwtPayloadClaims()
            assertEquals(challengeId, payload["challengeId"]?.jsonPrimitive?.content)
            assertEquals(challengeValue, payload["challenge"]?.jsonPrimitive?.content)
            assertEquals("approve", payload["action"]?.jsonPrimitive?.content)
            assertEquals(storedKey.kid, request.jwtHeaderClaims()["kid"]?.jsonPrimitive?.content)
        }

    @Test
    fun `resolveChallenge uses the resolution's issuer as-is, without normalizing it`() =
        runTest {
            val storage = FakePushNotificationStorage()
            // Stored under a normalized issuer, as a real enrollment always would be.
            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            // A differently-cased issuer would normalize to the same value elsewhere in the SDK
            // (e.g. hasEnrollment), but resolveChallenge trusts the resolution's issuer as
            // given — a ChallengeResolution only ever comes from the SDK's own Challenge, whose
            // issuer was already normalized when it was built.
            assertThrows<NotEnrolledException> {
                sdk.resolveChallenge(resolution(issuer = "https://ISSUER.EXAMPLE.COM", action = "approve"))
            }
        }

    @Test
    fun `resolveChallenge returns OK when the backend accepts a denial`() =
        runTest {
            val sdk = enrolledSdk { respondSuccess() }

            val result = sdk.resolveChallenge(resolution(action = "deny"))

            assertEquals(ResolutionResult.OK, result)
        }

    @Test
    fun `resolveChallenge returns NOT_OK when the backend records a refusal`() =
        runTest {
            for (rejectionStatus in listOf(
                HttpStatusCode.BadRequest,
                HttpStatusCode.Unauthorized,
                HttpStatusCode.NotFound,
            )) {
                val sdk = enrolledSdk { respondFailure(rejectionStatus) }

                val result = sdk.resolveChallenge(resolution(action = "approve"))

                assertEquals("status ${rejectionStatus.value} should map to NOT_OK", ResolutionResult.NOT_OK, result)
            }
        }

    // --- backend fails unexpectedly ---

    @Test
    fun `resolveChallenge throws ChallengeException when the backend fails unexpectedly`() =
        runTest {
            val sdk = enrolledSdk { respondFailure(HttpStatusCode.InternalServerError) }

            assertThrows<ChallengeException> { sdk.resolveChallenge(resolution(action = "approve")) }
        }

    @Test
    fun `resolveChallenge makes exactly one request even when the backend fails`() =
        runTest {
            var requestCount = 0
            val sdk =
                enrolledSdk {
                    requestCount++
                    respondFailure(HttpStatusCode.InternalServerError)
                }

            runCatching { sdk.resolveChallenge(resolution(action = "approve")) }

            assertEquals("the SDK does not retry — retrying is the caller's decision", 1, requestCount)
        }

    private fun resolution(
        issuer: String = this.issuer,
        action: String,
    ): ChallengeResolution = ChallengeResolution(challengeId, challengeValue, issuer, accountId, action)

    private suspend fun enrolledSdk(
        handleRequest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PushNotification {
        val storage = FakePushNotificationStorage()
        storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
        return createTestPushNotificationSdk(storage, handleRequest)
    }
}
