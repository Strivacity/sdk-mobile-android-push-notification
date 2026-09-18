package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.model.NetworkException
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.StorageException
import com.strivacity.android.pushnotification.model.UnenrollmentException
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import com.strivacity.android.pushnotification.support.assertThrows
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.failIfCalled
import com.strivacity.android.pushnotification.support.jwtHeaderClaims
import com.strivacity.android.pushnotification.support.jwtPayloadClaims
import com.strivacity.android.pushnotification.support.respondFailure
import com.strivacity.android.pushnotification.support.respondSuccess
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Exercises `unenrollDevice` through [PushNotificationInterface]. One test per
 * independently-verifiable outcome.
 */
class PushNotificationUnenrollDeviceTest {
    private val issuer = "https://issuer.example.com"
    private val accountId = "account-1"

    // --- backend accepts ---

    @Test
    fun `unenrollDevice sends an unenroll request to the backend`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val sdk =
                createTestPushNotificationSdk(enrolledStorage()) { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.unenrollDevice(issuer, accountId)

            val request = requireNotNull(capturedRequest) { "no request reached the backend" }
            assertEquals("$issuer/pushNotification/api/v1/unenroll", request.url.toString())
        }

    @Test
    fun `unenrollDevice signs only the account id as a claim and identifies the key in the header`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val storage = enrolledStorage()
            val keyId = requireNotNull(storage.getPublicKey(issuer, accountId)).kid
            val sdk =
                createTestPushNotificationSdk(storage) { request ->
                    capturedRequest = request
                    respondSuccess()
                }

            sdk.unenrollDevice(issuer, accountId)

            val request = requireNotNull(capturedRequest)
            assertEquals(setOf("accountId"), request.jwtPayloadClaims().keys)
            assertEquals(accountId, request.jwtPayloadClaims()["accountId"]?.jsonPrimitive?.content)
            assertEquals(keyId, request.jwtHeaderClaims()["kid"]?.jsonPrimitive?.content)
        }

    @Test
    fun `unenrollDevice calls the backend before removing the local key`() =
        runTest {
            val storage = enrolledStorage()
            var keyPresentWhenBackendCalled: Boolean? = null
            val sdk =
                createTestPushNotificationSdk(storage) {
                    keyPresentWhenBackendCalled = storage.getPublicKey(issuer, accountId) != null
                    respondSuccess()
                }

            sdk.unenrollDevice(issuer, accountId)

            assertEquals(true, keyPresentWhenBackendCalled)
        }

    @Test
    fun `unenrollDevice removes the local key`() =
        runTest {
            val storage = enrolledStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            sdk.unenrollDevice(issuer, accountId)

            assertNull("the local key should have been removed", storage.getPublicKey(issuer, accountId))
        }

    @Test
    fun `unenrollDevice removes the enrollment record`() =
        runTest {
            val storage = enrolledStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondSuccess() }

            sdk.unenrollDevice(issuer, accountId)

            assertTrue("the enrollment record should have been removed", storage.listEnrollments().isEmpty())
        }

    // --- backend refuses ---

    @Test
    fun `unenrollDevice removes the local enrollment even when the backend call fails`() =
        runTest {
            val storage = enrolledStorage()
            val sdk = createTestPushNotificationSdk(storage) { respondFailure() }

            runCatching { sdk.unenrollDevice(issuer, accountId) }

            assertNull(
                "the local key should have been removed regardless of the backend failure",
                storage.getPublicKey(issuer, accountId),
            )
            assertTrue("the enrollment record should have been removed too", storage.listEnrollments().isEmpty())
        }

    @Test
    fun `unenrollDevice still throws the backend's failure, with its status code, after removing locally`() =
        runTest {
            val sdk = createTestPushNotificationSdk(enrolledStorage()) { respondFailure() }

            val failure = assertThrows<UnenrollmentException> { sdk.unenrollDevice(issuer, accountId) }

            assertEquals(400, failure.status)
        }

    // --- network failure ---

    @Test
    fun `unenrollDevice throws NetworkException when the backend can't be reached`() =
        runTest {
            val sdk =
                createTestPushNotificationSdk(enrolledStorage()) { throw IOException("simulated connection failure") }

            assertThrows<NetworkException> { sdk.unenrollDevice(issuer, accountId) }
        }

    @Test
    fun `unenrollDevice removes the local enrollment even when a network failure occurs`() =
        runTest {
            val storage = enrolledStorage()
            val sdk = createTestPushNotificationSdk(storage) { throw IOException("simulated connection failure") }

            runCatching { sdk.unenrollDevice(issuer, accountId) }

            assertNull(
                "the local key should have been removed regardless of the network failure",
                storage.getPublicKey(issuer, accountId),
            )
            assertTrue("the enrollment record should have been removed too", storage.listEnrollments().isEmpty())
        }

    @Test
    fun `unenrollDevice removes the local enrollment even when cancelled mid-backend-call`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val storage = enrolledStorage()
            val sdk =
                createTestPushNotificationSdk(storage) {
                    started.complete(Unit)
                    release.await()
                    respondSuccess()
                }

            val job = launch { runCatching { sdk.unenrollDevice(issuer, accountId) } }
            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()

            assertNull(
                "the local key should have been removed regardless of the cancellation",
                storage.getPublicKey(issuer, accountId),
            )
            assertTrue("the enrollment record should have been removed too", storage.listEnrollments().isEmpty())
        }

    // --- not enrolled ---

    @Test
    fun `unenrollDevice throws NotEnrolledException when the account is not enrolled`() =
        runTest {
            val sdk = createTestPushNotificationSdk { failIfCalled() }

            assertThrows<NotEnrolledException> { sdk.unenrollDevice(issuer, accountId) }
        }

    // --- removing the local enrollment fails ---

    @Test
    fun `unenrollDevice throws the storage error when removing the local enrollment fails`() =
        runTest {
            val sdk =
                createTestPushNotificationSdk(StorageThrowingOnRemove(enrolledStorage())) { respondSuccess() }

            assertThrows<StorageException> { sdk.unenrollDevice(issuer, accountId) }
        }

    @Test
    fun `unenrollDevice throws the storage error rather than the backend's failure when both fail`() =
        runTest {
            val sdk =
                createTestPushNotificationSdk(StorageThrowingOnRemove(enrolledStorage())) { respondFailure() }

            assertThrows<StorageException> { sdk.unenrollDevice(issuer, accountId) }
        }

    @Test
    fun `unenrollDevice leaves the enrollment in place when removing the local enrollment fails`() =
        runTest {
            val fake = enrolledStorage()
            val sdk = createTestPushNotificationSdk(StorageThrowingOnRemove(fake)) { respondSuccess() }

            runCatching { sdk.unenrollDevice(issuer, accountId) }

            assertEquals("nothing was removed, so the call can be repeated", 1, fake.listEnrollments().size)
        }

    /** Storage holding an enrollment for [issuer] and [accountId]. */
    private suspend fun enrolledStorage(): FakePushNotificationStorage {
        val storage = FakePushNotificationStorage()
        storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
        return storage
    }
}

/** Throws instead of removing the local enrollment. */
private class StorageThrowingOnRemove(
    private val delegate: FakePushNotificationStorage,
) : PushNotificationStorage by delegate {
    override suspend fun removeEnrollment(
        issuer: String,
        accountId: String,
    ): Unit = throw StorageException("simulated removal failure")
}
