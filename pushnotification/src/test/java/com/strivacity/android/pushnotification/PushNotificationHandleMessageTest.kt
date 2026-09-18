package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.NetworkException
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.UnsupportedChallengeTypeException
import com.strivacity.android.pushnotification.support.approveDenyResponseBody
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.contextMatchingResponseBody
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.envelopeMessageData
import com.strivacity.android.pushnotification.support.failIfCalled
import com.strivacity.android.pushnotification.support.respondFailure
import com.strivacity.android.pushnotification.support.respondJson
import com.strivacity.android.pushnotification.support.unsupportedChallengeResponseBody
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `handleMessage` through [PushNotificationInterface]. One test per
 * independently-verifiable outcome.
 */
class PushNotificationHandleMessageTest {
    private val issuer = "https://issuer.example.com"
    private val accountId = "account-1"
    private val challengeId = "challenge-1"

    // --- can't even reach the backend ---

    @Test
    fun `handleMessage throws ChallengeException when no envelope can be parsed`() =
        runTest {
            val sdk = sdkWithNoStoredKey { failIfCalled() }

            assertThrows<ChallengeException> { sdk.handleMessage(null) }
        }

    @Test
    fun `handleMessage throws NotEnrolledException when no key is stored for the account`() =
        runTest {
            val sdk = sdkWithNoStoredKey { failIfCalled() }

            assertThrows<NotEnrolledException> {
                sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
            }
        }

    // --- backend accepts ---

    @Test
    fun `handleMessage fetches the challenge from the backend`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val sdk =
                sdkWithStoredKey { request ->
                    capturedRequest = request
                    respondJson(approveDenyResponseBody())
                }

            sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            val request = requireNotNull(capturedRequest) { "no request reached the backend" }
            assertEquals("$issuer/pushNotification/api/v1/actions/$challengeId", request.url.toString())
        }

    @Test
    fun `handleMessage maps an approve-deny response to Challenge Simple`() =
        runTest {
            val expectedExpiresAt = "2026-10-01T14:30:00Z"
            val sdk = sdkWithStoredKey { respondJson(approveDenyResponseBody(expireAt = expectedExpiresAt)) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertTrue(challenge is Challenge.Simple)
            assertEquals(challengeId, challenge.challengeId)
            assertEquals(issuer, challenge.issuer)
            assertEquals(accountId, challenge.accountId)
            assertEquals(expectedExpiresAt, challenge.expiresAt)
        }

    @Test
    fun `handleMessage returns an already-past expiresAt as-is, without enforcing it`() =
        runTest {
            val alreadyPastInstant = "1970-01-01T00:00:01Z"
            val sdk = sdkWithStoredKey { respondJson(approveDenyResponseBody(expireAt = alreadyPastInstant)) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertEquals(alreadyPastInstant, challenge.expiresAt)
        }

    @Test
    fun `handleMessage maps a context-matching response to Challenge ContextMatching with its options`() =
        runTest {
            val sdk = sdkWithStoredKey { respondJson(contextMatchingResponseBody(listOf("12", "26", "84"))) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertTrue(challenge is Challenge.ContextMatching)
            assertEquals(listOf("12", "26", "84"), (challenge as Challenge.ContextMatching).options)
        }

    @Test
    fun `handleMessage throws ChallengeException when a context-matching challenge arrives with no options`() =
        runTest {
            // A `select` that could never succeed is a backend contract violation, not a shape
            // the SDK hands the App.
            val sdk = sdkWithStoredKey { respondJson(contextMatchingResponseBody(emptyList())) }

            assertThrows<ChallengeException> {
                sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
            }
        }

    @Test
    fun `handleMessage ignores unknown fields in the challenge response body`() =
        runTest {
            val bodyWithUnknownField =
                """{"expireAt":"9999-12-31T23:59:59Z","metadata":{"loginTimestamp":"1700000000"},""" +
                    """"action":{"type":"simple"},"aFieldFromANewerBackend":"???"}"""
            val sdk = sdkWithStoredKey { respondJson(bodyWithUnknownField) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertTrue(challenge is Challenge.Simple)
        }

    @Test
    fun `handleMessage throws ChallengeException when metadata is empty`() =
        runTest {
            val sdk = sdkWithStoredKey { respondJson(approveDenyResponseBody(metadataJson = "{}")) }

            assertThrows<ChallengeException> {
                sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
            }
        }

    @Test
    fun `handleMessage throws NetworkException when the backend can't be reached`() =
        runTest {
            val sdk = sdkWithStoredKey { throw java.io.IOException("simulated connection failure") }

            assertThrows<NetworkException> {
                sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
            }
        }

    @Test
    fun `handleMessage passes through string and null metadata values as-is`() =
        runTest {
            // The backend contract guarantees every metadata value is a JSON string or null.
            val metadataJson = """{"place":"New York","flag":null}"""
            val sdk = sdkWithStoredKey { respondJson(approveDenyResponseBody(metadataJson = metadataJson)) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertEquals(mapOf("place" to "New York", "flag" to null), challenge.metadata)
        }

    @Test
    fun `handleMessage skips metadata values that are neither a string nor null`() =
        runTest {
            val metadataJson =
                """{"place":"New York","count":5,"verified":true,"device":{"os":"Android"},""" +
                    """"tags":["a"],"flag":null,"digits":"5","blank":""}"""
            val sdk = sdkWithStoredKey { respondJson(approveDenyResponseBody(metadataJson = metadataJson)) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertEquals(
                mapOf("place" to "New York", "flag" to null, "digits" to "5", "blank" to ""),
                challenge.metadata,
            )
        }

    @Test
    fun `handleMessage returns empty metadata when every value is outside the contract`() =
        runTest {
            val metadataJson = """{"count":5,"device":{"os":"Android"}}"""
            val sdk = sdkWithStoredKey { respondJson(approveDenyResponseBody(metadataJson = metadataJson)) }

            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))

            assertEquals(emptyMap<String, String?>(), challenge.metadata)
        }

    @Test
    fun `handleMessage throws UnsupportedChallengeTypeException naming the unrecognized action type`() =
        runTest {
            val sdk = sdkWithStoredKey { respondJson(unsupportedChallengeResponseBody()) }

            val failure =
                assertThrows<UnsupportedChallengeTypeException> {
                    sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
                }

            assertEquals("aFutureChallengeType", failure.challengeType)
        }

    // --- backend refuses ---

    @Test
    fun `handleMessage throws ChallengeException carrying the status code when the backend refuses`() =
        runTest {
            val sdk = sdkWithStoredKey { respondFailure(HttpStatusCode.InternalServerError) }

            val failure =
                assertThrows<ChallengeException> {
                    sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
                }

            assertEquals(500, failure.status)
        }

    @Test
    fun `handleMessage throws ChallengeException with the cause attached when the response body cannot be parsed`() =
        runTest {
            val sdk = sdkWithStoredKey { respondJson("not json") }

            val failure =
                assertThrows<ChallengeException> {
                    sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId))
                }

            assertNotNull("the deserialization failure should be attached as the cause", failure.cause)
        }

    @Test
    fun `handleMessage makes exactly one request even when the backend fails`() =
        runTest {
            var requestCount = 0
            val sdk =
                sdkWithStoredKey {
                    requestCount++
                    respondFailure()
                }

            runCatching { sdk.handleMessage(envelopeMessageData(issuer, accountId, challengeId)) }

            assertEquals("the SDK does not retry — retrying is the caller's decision", 1, requestCount)
        }

    private suspend fun sdkWithStoredKey(
        handleRequest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PushNotification {
        val storage = FakePushNotificationStorage()
        storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
        return createTestPushNotificationSdk(storage, handleRequest)
    }

    private fun sdkWithNoStoredKey(
        handleRequest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PushNotification = createTestPushNotificationSdk(handleRequest = handleRequest)
}
