package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.fakes.RecordingLogging
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.Jwk
import com.strivacity.android.pushnotification.model.PushNotificationException
import com.strivacity.android.pushnotification.model.ResolutionResult
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import com.strivacity.android.pushnotification.support.approveDenyResponseBody
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.envelopeMessageData
import com.strivacity.android.pushnotification.support.respondFailure
import com.strivacity.android.pushnotification.support.respondJson
import com.strivacity.android.pushnotification.support.respondSuccess
import com.strivacity.android.pushnotification.support.unsupportedChallengeResponseBody
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Exercises the log lines the SDK emits through [Logging]. */
class PushNotificationLoggingTest {
    private val issuer = "https://tenant-canary.example.com"
    private val accountId = "CANARY-ACCOUNT"
    private val oneTimeToken = "CANARY-ONE-TIME-TOKEN"
    private val deviceToken = "CANARY-DEVICE-TOKEN"
    private val deviceName = "CANARY-DEVICE-NAME"
    private val userName = "CANARY-USER-NAME"
    private val challengeValue = "CANARY-CHALLENGE-VALUE"
    private val exceptionMessage = "CANARY-EXCEPTION-MESSAGE"
    private val untrustedType = "aFutureChallengeType"
    private val untrustedVersion = "CANARY-VERSION"

    private val secrets =
        listOf(
            issuer,
            "tenant-canary",
            accountId,
            oneTimeToken,
            deviceToken,
            deviceName,
            userName,
            challengeValue,
            exceptionMessage,
            untrustedType,
            untrustedVersion,
            "CANARY-METADATA",
            "://",
        )

    private val grant = EnrollmentGrant(issuer, oneTimeToken, accountId, userName)

    private fun route(request: HttpRequestData): String = "${request.method.value} ${request.url.encodedPath}"

    // --- no sensitive values in any line ---

    @Test
    fun `no log line carries a secret, personal value or URL across the whole flow`() =
        runTest {
            val logging = RecordingLogging()
            val sdk =
                createTestPushNotificationSdk(logging = logging) { request ->
                    when {
                        route(request).endsWith("/actions/challenge-1") -> {
                            respondJson(approveDenyResponseBody(metadataJson = """{"k":"CANARY-METADATA"}"""))
                        }

                        else -> {
                            respondSuccess()
                        }
                    }
                }

            sdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            sdk.updateDeviceToken(issuer, accountId, deviceToken, DeviceTokenType.FCM_TOKEN)
            val message = envelopeMessageData(issuer, accountId, challenge = challengeValue)
            sdk.canHandleMessage(message)
            val challenge = sdk.handleMessage(message) as Challenge.Simple
            sdk.resolveChallenge(challenge.approve())
            sdk.unenrollDevice(issuer, accountId)

            assertNoSecretsIn(logging)
        }

    @Test
    fun `no log line carries a secret, personal value or URL across failure paths`() =
        runTest {
            val logging = RecordingLogging()
            val throwingStorage =
                object : PushNotificationStorage by FakePushNotificationStorage() {
                    override suspend fun createEnrollment(
                        issuer: String,
                        accountId: String,
                        userName: String?,
                        deviceName: String?,
                    ): Jwk = throw IllegalStateException(exceptionMessage)
                }
            val failingStorageSdk = createTestPushNotificationSdk(throwingStorage, logging) { respondSuccess() }
            assertThrows<IllegalStateException> {
                failingStorageSdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            }

            val refusingSdk = createTestPushNotificationSdk(logging = logging) { respondFailure() }
            assertThrows<PushNotificationException> {
                refusingSdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            }

            val unreachableSdk =
                createTestPushNotificationSdk(logging = logging) { throw IOException(exceptionMessage) }
            assertThrows<PushNotificationException> {
                unreachableSdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            }

            val unsupportedSdk =
                createTestPushNotificationSdk(enrolledStorage(), logging) { request ->
                    if (route(request).contains("/actions/")) {
                        respondJson(unsupportedChallengeResponseBody())
                    } else {
                        respondSuccess()
                    }
                }
            assertThrows<PushNotificationException> {
                unsupportedSdk.handleMessage(envelopeMessageData(issuer, accountId, challenge = challengeValue))
            }
            assertFalse(
                unsupportedSdk.canHandleMessage(
                    envelopeMessageData(issuer, accountId, challenge = challengeValue, version = untrustedVersion),
                ),
            )
            assertFalse(
                unsupportedSdk.canHandleMessage(
                    mapOf(ENVELOPE_KEY to """{"version":"1","challengeId":"$exceptionMessage"}"""),
                ),
            )
            assertThrows<PushNotificationException> {
                unsupportedSdk.unenrollDevice("http://user:$oneTimeToken@tenant-canary.example.com", accountId)
            }

            assertTrue(logging.lines.isNotEmpty())
            assertNoSecretsIn(logging)
        }

    // --- breadcrumb content ---

    @Test
    fun `a successful enrollment logs its start and its outcome with the key id`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(logging = logging) { respondSuccess() }

            val enrollment = sdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)

            assertTrue(logging.lines.contains("INFO Enrollment started"))
            assertTrue(logging.lines.contains("INFO Enrollment succeeded (key ${enrollment.keyId})"))
        }

    @Test
    fun `a refused enrollment logs the backend status and the rollback`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(logging = logging) { respondFailure() }

            assertThrows<PushNotificationException> {
                sdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            }

            assertTrue(logging.lines.contains("WARN Enrollment refused by the backend (status 400)"))
            assertTrue(
                logging.lines.any {
                    it.startsWith("ERROR Enrollment failed (enrollment_rejected); rolling back key")
                },
            )
        }

    @Test
    fun `an unreachable backend is logged as an error with the exception class only`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(logging = logging) { throw IOException(exceptionMessage) }

            assertThrows<PushNotificationException> {
                sdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            }

            assertTrue(
                logging.lines.contains(
                    "ERROR The backend could not be reached during enrollment (java.io.IOException)",
                ),
            )
        }

    @Test
    fun `enrolling an already enrolled account is logged as a warning`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(enrolledStorage(), logging) { respondSuccess() }

            assertThrows<PushNotificationException> {
                sdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)
            }

            assertTrue(logging.lines.contains("WARN Enrollment refused: this account is already enrolled"))
        }

    @Test
    fun `un-enrollment logs a backend failure and that the local enrollment was removed anyway`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(enrolledStorage(), logging) { respondFailure() }

            assertThrows<PushNotificationException> { sdk.unenrollDevice(issuer, accountId) }

            assertTrue(
                logging.lines.contains(
                    "WARN Un-enrollment failed at the backend (unenrollment_rejected); " +
                        "removing the local enrollment anyway",
                ),
            )
            assertTrue(logging.lines.any { it.startsWith("INFO Local enrollment removed") })
        }

    @Test
    fun `a challenge resolution the backend does not accept is logged as not accepted`() =
        runTest {
            val logging = RecordingLogging()
            val sdk =
                createTestPushNotificationSdk(enrolledStorage(), logging) { request ->
                    if (route(request).contains("/actions/challenge-1/submit")) {
                        respondFailure()
                    } else {
                        respondJson(approveDenyResponseBody())
                    }
                }
            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId)) as Challenge.Simple

            val result = sdk.resolveChallenge(challenge.approve())

            assertEquals(ResolutionResult.NOT_OK, result)
            assertTrue(logging.lines.contains("INFO Resolution not accepted"))
            assertTrue(logging.lines.contains("DEBUG Resolution rejected by the backend (status 400)"))
        }

    @Test
    fun `each call that logs nothing else logs that it was made`() =
        runTest {
            val logging = RecordingLogging()
            val sdk =
                createTestPushNotificationSdk(enrolledStorage(), logging) { request ->
                    val isChallengeCall = route(request).contains("/actions/")
                    if (isChallengeCall) respondJson(approveDenyResponseBody()) else respondSuccess()
                }

            sdk.hasEnrollment(issuer, accountId)
            sdk.listEnrollments()
            sdk.canHandleMessage(envelopeMessageData(issuer, accountId))
            val challenge = sdk.handleMessage(envelopeMessageData(issuer, accountId)) as Challenge.Simple
            sdk.resolveChallenge(challenge.approve())

            assertEquals(
                listOf(
                    "DEBUG Checking enrollment",
                    "DEBUG Listing enrollments",
                    "DEBUG Checking whether a message can be handled",
                    "DEBUG Handling message",
                    "DEBUG Resolving challenge",
                ),
                logging.lines.filter { it.startsWith("DEBUG") },
            )
        }

    @Test
    fun `canHandleMessage logs why a message is not handled`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(logging = logging) { respondSuccess() }

            assertFalse(sdk.canHandleMessage(null))
            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId, version = "9")))
            assertFalse(sdk.canHandleMessage(envelopeMessageData(issuer, accountId)))

            assertEquals(
                listOf(
                    "DEBUG Checking whether a message can be handled",
                    "DEBUG Message carries no push envelope",
                    "DEBUG Checking whether a message can be handled",
                    "WARN Push envelope version is not supported by this SDK version",
                    "DEBUG Checking whether a message can be handled",
                    "DEBUG Message cannot be handled: no enrollment matches it",
                ),
                logging.lines,
            )
        }

    @Test
    fun `an unreadable envelope is logged with the exception class only`() =
        runTest {
            val logging = RecordingLogging()
            val sdk = createTestPushNotificationSdk(logging = logging) { respondSuccess() }

            assertFalse(sdk.canHandleMessage(mapOf(ENVELOPE_KEY to "not json")))

            val line = logging.lines.single { it.startsWith("WARN") }
            assertTrue(line, line.startsWith("WARN Push envelope could not be read ("))
            assertFalse(line, line.contains("not json"))
        }

    // --- no Logging ---

    @Test
    fun `an operation completes without a Logging`() =
        runTest {
            val sdk = createTestPushNotificationSdk(logging = null) { respondSuccess() }

            val enrollment = sdk.enrollDevice(grant, deviceToken, DeviceTokenType.FCM_TOKEN, deviceName)

            assertEquals(accountId, enrollment.accountId)
        }

    private suspend fun enrolledStorage(): FakePushNotificationStorage =
        FakePushNotificationStorage().also { it.createEnrollment(issuer, accountId, userName, deviceName) }

    private fun assertNoSecretsIn(logging: RecordingLogging) {
        for (line in logging.lines) {
            for (secret in secrets) {
                assertFalse("'$secret' leaked in: $line", line.contains(secret, ignoreCase = true))
            }
        }
    }
}

private const val ENVELOPE_KEY = "com.strivacity"
