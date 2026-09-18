package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.CallLog
import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.fakes.RecordingStorage
import com.strivacity.android.pushnotification.model.AlreadyEnrolledException
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentException
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.Jwk
import com.strivacity.android.pushnotification.model.NetworkException
import com.strivacity.android.pushnotification.model.StorageException
import com.strivacity.android.pushnotification.model.UnsupportedAlgorithmException
import com.strivacity.android.pushnotification.net.EnrollRequest
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.bodyText
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.failIfCalled
import com.strivacity.android.pushnotification.support.respondFailure
import com.strivacity.android.pushnotification.support.respondSuccess
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Exercises `enrollDevice` through [PushNotificationInterface].
 *
 * One test per independently-verifiable outcome, not one test asserting all of them.
 */
class PushNotificationEnrollDeviceTest {
    private val grant =
        EnrollmentGrant(
            issuer = "https://issuer.example.com",
            oneTimeToken = "ott-1",
            accountId = "account-1",
            displayUserName = "Jane",
        )

    // --- backend accepts ---

    @Test
    fun `enrollDevice generates a key for the account`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertNotNull(
                "a key should have been generated for this issuer and account",
                storage.getPublicKey(grant.issuer, grant.accountId),
            )
        }

    @Test
    fun `enrollDevice normalizes a non-canonical issuer so later calls can still find the key`() =
        runTest {
            // EnrollmentGrant's constructor is public and unvalidated — only .parse(Uri) normalizes,
            // so a grant built directly (as here) can carry a non-canonical issuer.
            val nonCanonicalGrant = grant.copy(issuer = "https://Issuer.Example.COM")
            val storage = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            sdk.enrollDevice(nonCanonicalGrant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertNotNull(
                "the key should be stored under the normalized issuer, findable the same way every " +
                    "other method looks it up",
                storage.getPublicKey("https://issuer.example.com", nonCanonicalGrant.accountId),
            )
        }

    @Test
    fun `enrollDevice sends an enroll request to the backend`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val sdk =
                createTestPushNotificationSdk { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            val request = requireNotNull(capturedRequest) { "no request reached the backend" }
            assertEquals("${grant.issuer}/pushNotification/api/v1/enroll", request.url.toString())
            assertEquals("Bearer ${grant.oneTimeToken}", request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `enrollDevice sends the platform and public key in the request body`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val storage = FakePushNotificationStorage()
            val sdk =
                createTestPushNotificationSdk(storage) { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            val body = Json.decodeFromString<EnrollRequest>(requireNotNull(capturedRequest).bodyText())
            val storedKey = requireNotNull(storage.getPublicKey(grant.issuer, grant.accountId))
            assertEquals("android", body.platform)
            assertEquals(storedKey.kid, body.publicKey["kid"]?.jsonPrimitive?.content)
            assertEquals(storedKey.alg, body.publicKey["alg"]?.jsonPrimitive?.content)
            for ((name, value) in storedKey.members) {
                assertEquals(value, body.publicKey[name]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `enrollDevice allows the same account under a different issuer`() =
        runTest {
            val storage = FakePushNotificationStorage()
            seedEnrollment(storage)
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }
            val differentIssuerGrant = grant.copy(issuer = "https://a-different-issuer.example.com")

            sdk.enrollDevice(differentIssuerGrant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertNotNull(storage.getPublicKey(differentIssuerGrant.issuer, differentIssuerGrant.accountId))
        }

    @Test
    fun `enrollDevice allows a different account under the same issuer`() =
        runTest {
            val storage = FakePushNotificationStorage()
            seedEnrollment(storage)
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }
            val differentAccountGrant = grant.copy(accountId = "a-different-account")

            sdk.enrollDevice(differentAccountGrant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertNotNull(storage.getPublicKey(differentAccountGrant.issuer, differentAccountGrant.accountId))
        }

    @Test
    fun `enrollDevice records the new enrollment`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            val enrollment =
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "My Phone")

            val keyId = requireNotNull(storage.getPublicKey(grant.issuer, grant.accountId)).kid
            assertEquals(Enrollment(grant.issuer, grant.accountId, keyId, "Jane", "My Phone"), enrollment)
            assertEquals(listOf(enrollment), storage.listEnrollments())
        }

    @Test
    fun `enrollDevice records the enrollment before calling the backend`() =
        runTest {
            val storage = FakePushNotificationStorage()
            var enrollmentsWhenBackendCalled: List<Enrollment>? = null
            val sdk =
                createTestPushNotificationSdk(storage) {
                    enrollmentsWhenBackendCalled = storage.listEnrollments()
                    respondSuccess()
                }

            val enrollment = sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertEquals(listOf(enrollment), enrollmentsWhenBackendCalled)
        }

    // --- backend refuses ---

    @Test
    fun `enrollDevice throws EnrollmentException carrying the status code when the backend refuses`() =
        runTest {
            val sdk = createTestPushNotificationSdk { respondFailure(HttpStatusCode.BadRequest) }

            val failure =
                assertThrows<EnrollmentException> {
                    sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
                }

            assertEquals(400, failure.status)
        }

    @Test
    fun `enrollDevice succeeds on a later attempt after an earlier backend refusal rolled back the enrollment`() =
        runTest {
            val storage = FakePushNotificationStorage()
            var shouldFail = true
            val sdk =
                createTestPushNotificationSdk(storage) { if (shouldFail) respondFailure() else respondSuccess() }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }
            shouldFail = false
            sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertNotNull(storage.getPublicKey(grant.issuer, grant.accountId))
        }

    @Test
    fun `enrollDevice removes the generated key when the backend refuses`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondFailure() }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }

            assertNull(
                "the generated key should have been rolled back",
                storage.getPublicKey(grant.issuer, grant.accountId),
            )
        }

    @Test
    fun `enrollDevice removes the recorded enrollment when the backend refuses`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondFailure() }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }

            assertTrue("the recorded enrollment should have been rolled back", storage.listEnrollments().isEmpty())
        }

    // --- network failure ---

    @Test
    fun `enrollDevice throws NetworkException when the backend can't be reached`() =
        runTest {
            val sdk = createTestPushNotificationSdk { throw IOException("simulated connection failure") }

            assertThrows<NetworkException> {
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
            }
        }

    @Test
    fun `enrollDevice rolls back the enrollment when a network failure occurs`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storage) { throw IOException("simulated connection failure") }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }

            assertNull(
                "the generated key should have been rolled back",
                storage.getPublicKey(grant.issuer, grant.accountId),
            )
            assertTrue("the recorded enrollment should have been rolled back", storage.listEnrollments().isEmpty())
        }

    @Test
    fun `enrollDevice rolls back the enrollment when cancelled mid-network-call`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val storage = FakePushNotificationStorage()
            val sdk =
                createTestPushNotificationSdk(storage) {
                    started.complete(Unit)
                    release.await()
                    respondSuccess()
                }

            val job =
                launch {
                    runCatching {
                        sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
                    }
                }
            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()

            assertNull(
                "the generated key should have been rolled back, not leaked, when cancelled",
                storage.getPublicKey(grant.issuer, grant.accountId),
            )
            assertTrue("the recorded enrollment should have been rolled back", storage.listEnrollments().isEmpty())
        }

    @Test
    fun `enrollDevice rolls back the enrollment when cancelled while it is being created`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = FakePushNotificationStorage()
            val storage =
                StorageReplacingCreateEnrollment(fake) { issuer, accountId ->
                    started.complete(Unit)
                    release.await()
                    fake.createEnrollment(issuer, accountId, userName = null, deviceName = null)
                }
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            val job =
                launch {
                    runCatching {
                        sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
                    }
                }
            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()

            assertNull(
                "the generated key should have been rolled back, not leaked, when cancelled",
                fake.getPublicKey(grant.issuer, grant.accountId),
            )
            assertTrue("the recorded enrollment should have been rolled back", fake.listEnrollments().isEmpty())
        }

    // --- already enrolled ---

    @Test
    fun `enrollDevice throws AlreadyEnrolledException when an enrollment is recorded for the account`() =
        runTest {
            val storage = FakePushNotificationStorage()
            seedEnrollment(storage)
            // Wired to fail the test if a network call happens — proves "no network call is
            // made" is true, not just unasserted.
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            assertThrows<AlreadyEnrolledException> {
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
            }
        }

    @Test
    fun `enrollDevice does not replace the existing key when already enrolled`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val existingKeyId = seedEnrollment(storage)
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }

            assertEquals(existingKeyId, storage.getPublicKey(grant.issuer, grant.accountId)?.kid)
        }

    @Test
    fun `enrollDevice throws AlreadyEnrolledException when creating the enrollment loses a race`() =
        runTest {
            val storage =
                StorageReplacingCreateEnrollment(FakePushNotificationStorage()) { issuer, accountId ->
                    throw AlreadyEnrolledException(issuer, accountId)
                }
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            val failure =
                assertThrows<AlreadyEnrolledException> {
                    sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
                }

            assertEquals(grant.issuer, failure.issuer)
            assertEquals(grant.accountId, failure.accountId)
        }

    // --- a key left behind with no enrollment record ---

    @Test
    fun `enrollDevice replaces a key stored without an enrollment record`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val leftoverKeyId = storage.storeKeyWithoutEnrollment(grant.issuer, grant.accountId).kid
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            val enrollment = sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertTrue(enrollment.keyId != leftoverKeyId)
            // The fake only signs with keys it still holds, so this proves the leftover is gone,
            // not just shadowed by the newer key.
            assertThrows<StorageException> {
                storage.createSignature(grant.issuer, grant.accountId, leftoverKeyId, "data")
            }
        }

    @Test
    fun `enrollDevice records the enrollment when replacing a key stored without an enrollment record`() =
        runTest {
            val storage = FakePushNotificationStorage()
            storage.storeKeyWithoutEnrollment(grant.issuer, grant.accountId)
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            val enrollment = sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertEquals(listOf(enrollment), storage.listEnrollments())
        }

    // --- order of storage operations ---

    @Test
    fun `enrollDevice clears whatever is stored for the account before creating the enrollment`() =
        runTest {
            val log = CallLog()
            val storage = RecordingStorage(FakePushNotificationStorage(), log)
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")

            assertEquals(listOf("removeEnrollment", "createEnrollment"), log.entries)
        }

    // --- creating the enrollment fails ---

    @Test
    fun `enrollDevice throws the storage failure without calling the backend when creating the enrollment fails`() =
        runTest {
            val storage =
                StorageReplacingCreateEnrollment(FakePushNotificationStorage()) { _, _ ->
                    throw StorageException("simulated failure")
                }
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            assertThrows<StorageException> {
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
            }
        }

    // --- storage returns a key the SDK can't accept ---

    @Test
    fun `enrollDevice throws StorageException when storage returns private key material`() =
        runTest {
            val sdk =
                createTestPushNotificationSdk(storageReturningPrivateKeyMaterial(FakePushNotificationStorage())) {
                    failIfCalled()
                }

            assertThrows<StorageException> {
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
            }
        }

    @Test
    fun `enrollDevice rolls back the enrollment when its key carries private key material`() =
        runTest {
            val fake = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storageReturningPrivateKeyMaterial(fake)) { failIfCalled() }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }

            assertNull(fake.getPublicKey(grant.issuer, grant.accountId))
            assertTrue(fake.listEnrollments().isEmpty())
        }

    @Test
    fun `enrollDevice throws UnsupportedAlgorithmException when storage returns an unaccepted algorithm`() =
        runTest {
            val sdk =
                createTestPushNotificationSdk(storageReturningUnsupportedAlgorithm(FakePushNotificationStorage())) {
                    failIfCalled()
                }

            assertThrows<UnsupportedAlgorithmException> {
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
            }
        }

    @Test
    fun `enrollDevice rolls back the enrollment when its key's algorithm is not accepted`() =
        runTest {
            val fake = FakePushNotificationStorage()
            val sdk = createTestPushNotificationSdk(storageReturningUnsupportedAlgorithm(fake)) { failIfCalled() }

            runCatching { sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device") }

            assertNull(fake.getPublicKey(grant.issuer, grant.accountId))
            assertTrue(fake.listEnrollments().isEmpty())
        }

    /** Records an enrollment for [grant]'s issuer and account. Returns its key's id. */
    private suspend fun seedEnrollment(storage: FakePushNotificationStorage): String =
        storage.createEnrollment(grant.issuer, grant.accountId, userName = null, deviceName = null).kid

    /** Creates enrollments through [fake], but returns their key with a private member added. */
    private fun storageReturningPrivateKeyMaterial(fake: FakePushNotificationStorage) =
        StorageReplacingCreateEnrollment(fake) { issuer, accountId ->
            val key = fake.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            key.copy(members = key.members + (Jwk.PRIVATE_MEMBERS.first() to "leaked-private-value"))
        }

    /** Creates enrollments through [fake], but returns their key under an algorithm the SDK does not accept. */
    private fun storageReturningUnsupportedAlgorithm(fake: FakePushNotificationStorage) =
        StorageReplacingCreateEnrollment(fake) { issuer, accountId ->
            fake.createEnrollment(issuer, accountId, userName = null, deviceName = null).copy(alg = "RS256")
        }
}

/** Delegates to a fake, except for `createEnrollment`, which [replacement] handles instead. */
private class StorageReplacingCreateEnrollment(
    private val delegate: FakePushNotificationStorage,
    private val replacement: suspend (issuer: String, accountId: String) -> Jwk,
) : PushNotificationStorage by delegate {
    override suspend fun createEnrollment(
        issuer: String,
        accountId: String,
        userName: String?,
        deviceName: String?,
    ): Jwk = replacement(issuer, accountId)
}
