package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.failIfCalled
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `listEnrollments` through [PushNotificationInterface]. Pure pass-through to
 * [com.strivacity.android.pushnotification.storage.PushNotificationStorage.listEnrollments] — no
 * backend call is ever expected.
 */
class PushNotificationListEnrollmentsTest {
    @Test
    fun `listEnrollments returns an empty list when nothing is enrolled`() =
        runTest {
            val sdk = createTestPushNotificationSdk { failIfCalled() }

            assertTrue(sdk.listEnrollments().isEmpty())
        }

    @Test
    fun `listEnrollments returns exactly what storage has recorded`() =
        runTest {
            val storage = FakePushNotificationStorage()
            val firstKey = storage.createEnrollment("https://issuer-a.example.com", "account-1", "Jane", "Pixel")
            val secondKey = storage.createEnrollment("https://issuer-b.example.com", "account-2", null, null)
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            assertEquals(
                setOf(
                    Enrollment("https://issuer-a.example.com", "account-1", firstKey.kid, "Jane", "Pixel"),
                    Enrollment("https://issuer-b.example.com", "account-2", secondKey.kid, null, null),
                ),
                sdk.listEnrollments().toSet(),
            )
        }

    @Test
    fun `listEnrollments omits a key stored without an enrollment record`() =
        runTest {
            val storage = FakePushNotificationStorage()
            storage.storeKeyWithoutEnrollment("https://issuer.example.com", "account-1")
            val sdk = createTestPushNotificationSdk(storage) { failIfCalled() }

            assertTrue(sdk.listEnrollments().isEmpty())
        }
}
