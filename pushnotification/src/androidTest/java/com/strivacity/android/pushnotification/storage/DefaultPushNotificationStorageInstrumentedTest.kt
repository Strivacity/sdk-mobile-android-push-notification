package com.strivacity.android.pushnotification.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.Base64URL
import com.strivacity.android.pushnotification.PushNotification
import com.strivacity.android.pushnotification.fakes.FakeSharedPreferences
import com.strivacity.android.pushnotification.model.AlreadyEnrolledException
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentException
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.StorageException
import com.strivacity.android.pushnotification.net.PushNotificationClient
import com.strivacity.android.pushnotification.support.assertThrows
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/**
 * Exercises [DefaultPushNotificationStorage] against the real Android Keystore and a real
 * [SharedPreferences] instance. These are instrumented tests rather than unit tests because they
 * exercise the real Android Keystore implementation. Each test uses a unique issuer/account pair
 * to avoid colliding with keys left over from other runs, and cleans up after itself in
 * [tearDown].
 */
@RunWith(AndroidJUnit4::class)
class DefaultPushNotificationStorageInstrumentedTest {
    private val fileName = "test-enrollments-${UUID.randomUUID()}"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences: SharedPreferences get() = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val createdAccounts = mutableListOf<Pair<String, String>>()

    private fun newStorage(allowSoftwareBackedKeys: Boolean = true) =
        DefaultPushNotificationStorage(preferences, allowSoftwareBackedKeys)

    @After
    fun tearDown() =
        runTest {
            val storage = newStorage()
            createdAccounts.forEach { (issuer, accountId) -> storage.removeEnrollment(issuer, accountId) }
            preferences.edit().clear().commit()
        }

    /** createEnrollment twice for one account throws for the second attempt. */
    @Test
    fun createEnrollmentTwiceThrowsForSecondAttempt() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()

            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)

            assertThrows<AlreadyEnrolledException> {
                storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            }
        }

    /** getPublicKey returns the key created for that issuer and account, and for no other. */
    @Test
    fun getPublicKeyReturnsTheKeyCreatedForThatIssuerAndAccount() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val (otherIssuer, otherAccountId) = uniqueAccount()

            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            val otherKey = storage.createEnrollment(otherIssuer, otherAccountId, userName = null, deviceName = null)

            assertEquals(key.kid, storage.getPublicKey(issuer, accountId)?.kid)
            assertEquals(otherKey.kid, storage.getPublicKey(otherIssuer, otherAccountId)?.kid)
            assertNotEquals(key.kid, otherKey.kid)
        }

    /** The enrollment is listed with its key id and details, read back through a fresh instance. */
    @Test
    fun createdEnrollmentIsListedByAFreshInstanceOnTheSameFile() =
        runTest {
            val (issuer, accountId) = uniqueAccount()
            val key = newStorage().createEnrollment(issuer, accountId, userName = "Jane", deviceName = "Pixel")

            val listed = newStorage().listEnrollments().filter { it.issuer == issuer }

            assertEquals(listOf(Enrollment(issuer, accountId, key.kid, "Jane", "Pixel")), listed)
        }

    /** commit()'s return value reflects a real write, which is also visible in the underlying store. */
    @Test
    fun theEnrollmentRecordIsWrittenToTheRealSharedPreferences() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()

            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)

            assertTrue(preferences.contains(KeyAlias.prefixFor(issuer, accountId)))
        }

    /** removeEnrollment removes the key and the record, leaving other accounts alone. */
    @Test
    fun removeEnrollmentRemovesOnlyTheNamedAccount() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            val (issuer2, accountId2) = uniqueAccount()
            val toKeep = storage.createEnrollment(issuer2, accountId2, userName = null, deviceName = null)

            storage.removeEnrollment(issuer, accountId)

            assertNull(storage.getPublicKey(issuer, accountId))
            // Both accounts share one issuer, so what's left is told apart by account.
            assertEquals(listOf(accountId2), storage.listEnrollments().map { it.accountId })
            assertEquals(toKeep.kid, storage.getPublicKey(issuer2, accountId2)?.kid)
        }

    /** createSignature for an enrolled account but an unknown key id throws StorageException. */
    @Test
    fun createSignatureWithUnknownKeyIdThrows() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)

            assertThrows<StorageException> {
                storage.createSignature(issuer, accountId, "no-such-key", "data")
            }
        }

    /** createSignature when nothing is recorded for the account throws NotEnrolledException. */
    @Test
    fun createSignatureForAnAccountThatIsNotEnrolledThrowsNotEnrolled() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()

            assertThrows<NotEnrolledException> {
                storage.createSignature(issuer, accountId, "no-such-key", "data")
            }
        }

    /** createSignature with a valid key id but the wrong account throws NotEnrolledException. */
    @Test
    fun createSignatureWithWrongAccountThrowsNotEnrolled() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val otherAccountId = uniqueAccountId()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)

            val failure =
                assertThrows<NotEnrolledException> {
                    storage.createSignature(issuer, otherAccountId, key.kid, "data")
                }
            assertEquals(otherAccountId, failure.accountId)
        }

    /** The record is checked first: a key without one can't sign, and it's NotEnrolledException that says so. */
    @Test
    fun createSignatureWithAKeyButNoRecordThrowsNotEnrolled() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            preferences.edit().remove(KeyAlias.prefixFor(issuer, accountId)).commit()

            assertThrows<NotEnrolledException> {
                storage.createSignature(issuer, accountId, key.kid, "data")
            }
        }

    /** createSignature returns a signature that verifies against the returned public key. */
    @Test
    fun createSignatureVerifiesAgainstReturnedPublicKey() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            val data = "the exact signing input"

            val signature = storage.createSignature(issuer, accountId, key.kid, data)

            val publicKey =
                ECKey
                    .Builder(Curve.P_256, Base64URL(key.members.getValue("x")), Base64URL(key.members.getValue("y")))
                    .build()
            val verified =
                ECDSAVerifier(publicKey).verify(
                    JWSHeader(JWSAlgorithm.ES256),
                    data.toByteArray(Charsets.UTF_8),
                    Base64URL.from(signature),
                )

            assertTrue("the signature should verify against the key's own public JWK", verified)
        }

    /** A key rejected for being software-backed is not left behind. */
    @Test
    fun softwareBackedKeyRejectionLeavesNoOrphan() =
        runTest {
            val strictStorage = newStorage(allowSoftwareBackedKeys = false)
            val (issuer, accountId) = uniqueAccount()

            // On most emulators keys are software-backed by default, so this is expected to
            // throw there; on hardware with StrongBox/TEE it may not.
            runCatching { strictStorage.createEnrollment(issuer, accountId, userName = null, deviceName = null) }
            val key = newStorage().getPublicKey(issuer, accountId)

            if (key == null) {
                assertTrue(newStorage().listEnrollments().none { it.issuer == issuer })
                assertTrue(!preferences.contains(KeyAlias.prefixFor(issuer, accountId)))
            }
        }

    /** allowSoftwareBackedKeys true accepts a software-backed key without throwing. */
    @Test
    fun allowSoftwareBackedKeysTrueAcceptsSoftwareBackedKey() =
        runTest {
            val storage = newStorage(allowSoftwareBackedKeys = true)
            val (issuer, accountId) = uniqueAccount()

            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)

            assertEquals(key.kid, storage.getPublicKey(issuer, accountId)?.kid)
        }

    /** enrollDevice rolls back a real Keystore key and its record when the backend refuses. */
    @Test
    fun enrollDeviceRollsBackTheRealEnrollmentOnBackendRefusal() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val grant = EnrollmentGrant(issuer, "ott-1", accountId, displayUserName = "TestUsername")
            val client = PushNotificationClient(MockEngine { respond("", HttpStatusCode.BadRequest) })
            val sdk = PushNotification(storage, client)

            assertThrows<EnrollmentException> {
                sdk.enrollDevice(grant, "token-abc", DeviceTokenType.FCM_TOKEN, deviceName = "device")
            }

            assertNull(
                "the key generated for this enrollment attempt should have been rolled back",
                storage.getPublicKey(issuer, accountId),
            )
            assertTrue(storage.listEnrollments().none { it.issuer == issuer })
        }

    // --- listing ---

    /** An enrollment record whose key is gone is not an enrollment. */
    @Test
    fun listEnrollmentsOmitsARecordWhoseKeyIsGone() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            keyStore().deleteEntry(KeyAlias.of(issuer, accountId, key.kid))

            assertTrue(storage.listEnrollments().none { it.issuer == issuer })
        }

    /** A key without a record is not an enrollment, though its key is still found. */
    @Test
    fun listEnrollmentsOmitsAKeyStoredWithoutAnEnrollmentRecord() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            preferences.edit().remove(KeyAlias.prefixFor(issuer, accountId)).commit()

            assertTrue(storage.listEnrollments().none { it.issuer == issuer })
            assertEquals(key.kid, storage.getPublicKey(issuer, accountId)?.kid)
        }

    /** A corrupt record is dropped, and the other enrollments survive. */
    @Test
    fun listEnrollmentsDropsACorruptRecordAndKeepsTheOthers() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val (otherIssuer, otherAccountId) = uniqueAccount()
            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            storage.createEnrollment(otherIssuer, otherAccountId, userName = null, deviceName = null)
            preferences.edit().putString(KeyAlias.prefixFor(issuer, accountId), "not json").commit()

            val listed = storage.listEnrollments().filter { it.issuer == issuer }

            assertEquals(listOf(otherAccountId), listed.map { it.accountId })
        }

    /** A record from a newer SDK version, carrying a field this one doesn't know, is still read. */
    @Test
    fun listEnrollmentsIgnoresAnUnknownFieldFromANewerSdkVersion() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            val futureJson =
                """{"issuer":"$issuer","accountId":"$accountId","keyId":"${key.kid}","fromTheFuture":"???"}"""
            preferences.edit().putString(KeyAlias.prefixFor(issuer, accountId), futureJson).commit()

            assertEquals(
                listOf(Enrollment(issuer, accountId, key.kid, null, null)),
                storage.listEnrollments().filter { it.issuer == issuer },
            )
        }

    /** An unreadable store reads as "nothing enrolled" rather than throwing. */
    @Test
    fun listEnrollmentsDegradesToAnEmptyListWhenTheRecordsCantBeRead() =
        runTest {
            val unreadable = FakeSharedPreferences(failGetAll = true)
            val (issuer, accountId) = uniqueAccount()
            val storage = DefaultPushNotificationStorage(unreadable, allowSoftwareBackedKeys = true)
            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)

            assertTrue(storage.listEnrollments().isEmpty())
        }

    // --- failure paths ---

    /** A key whose record can't be written is not left behind. */
    @Test
    fun createEnrollmentStoresNothingWhenTheRecordCantBeWritten() =
        runTest {
            val failing = FakeSharedPreferences(commitResult = false)
            val storage = DefaultPushNotificationStorage(failing, allowSoftwareBackedKeys = true)
            val (issuer, accountId) = uniqueAccount()

            assertThrows<StorageException> {
                storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            }

            assertNull("the key should have been rolled back", storage.getPublicKey(issuer, accountId))
            assertTrue(failing.keys.isEmpty())
        }

    /** Both removals always run: a record that can't be removed doesn't keep the key alive. */
    @Test
    fun removeEnrollmentStillRemovesTheKeyWhenTheRecordCantBeRemoved() =
        runTest {
            val flaky = FakeSharedPreferences()
            val storage = DefaultPushNotificationStorage(flaky, allowSoftwareBackedKeys = true)
            val (issuer, accountId) = uniqueAccount()
            storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            flaky.commitResult = false

            assertThrows<StorageException> { storage.removeEnrollment(issuer, accountId) }

            assertNull("the key should have been removed regardless", storage.getPublicKey(issuer, accountId))
            assertTrue("a record without its key is not an enrollment", storage.listEnrollments().isEmpty())
        }

    /** removeEnrollment also clears a record whose key is gone, so nothing blocks a new enrollment. */
    @Test
    fun removeEnrollmentAlsoRemovesARecordWhoseKeyIsGone() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()
            val key = storage.createEnrollment(issuer, accountId, userName = null, deviceName = null)
            keyStore().deleteEntry(KeyAlias.of(issuer, accountId, key.kid))

            storage.removeEnrollment(issuer, accountId)

            assertTrue(!preferences.contains(KeyAlias.prefixFor(issuer, accountId)))
        }

    /** removeEnrollment when nothing is stored does nothing. */
    @Test
    fun removeEnrollmentWhenNothingIsStoredDoesNothing() =
        runTest {
            val storage = newStorage()
            val (issuer, accountId) = uniqueAccount()

            storage.removeEnrollment(issuer, accountId)

            assertNull(storage.getPublicKey(issuer, accountId))
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun uniqueAccount(): Pair<String, String> {
        val account = "https://issuer.example.com" to uniqueAccountId()
        createdAccounts.add(account)
        return account
    }

    private fun uniqueAccountId(): String = "account-${UUID.randomUUID()}"
}
