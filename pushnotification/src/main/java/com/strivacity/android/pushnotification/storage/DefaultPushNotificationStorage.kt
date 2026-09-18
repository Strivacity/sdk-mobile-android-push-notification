package com.strivacity.android.pushnotification.storage

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.strivacity.android.pushnotification.model.AlreadyEnrolledException
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.Jwk
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.PushNotificationException
import com.strivacity.android.pushnotification.model.SoftwareBackedKeyException
import com.strivacity.android.pushnotification.model.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The shipped [PushNotificationStorage]: P-256 keys in the Android Keystore, each recorded in a
 * [SharedPreferences] instance together with the enrollment it belongs to.
 *
 * Needs no [android.content.Context] — the caller builds and owns the [SharedPreferences]
 * instance, including whether it is encrypted (e.g. via `EncryptedSharedPreferences`, which is
 * not a dependency of this SDK). Alias scheme:
 *
 * ```
 * alias = "sty" . base64url(sha256(issuer ‖ 0x00 ‖ accountId)) . keyId
 * ```
 *
 * A Keystore entry holds nothing but its key, so an enrollment's details live in
 * [preferences], keyed by [KeyAlias.prefixFor] — the same per-(issuer, account) hash the alias
 * scheme uses. The key anchors the enrollment: a record whose key is gone is not an enrollment
 * and is not listed, and a key without a record is a leftover that the next enrollment replaces.
 * Each enrollment is created key first, record second, and removed record first, key second, so
 * an interruption between the two steps can only leave a key without a record, never the reverse.
 *
 * @param preferences where enrollments' details are recorded.
 * @param allowSoftwareBackedKeys if `false` (default), a key that could only be created in
 *   software is refused.
 */
@Suppress("TooManyFunctions") // Deliberately self-contained: the Keystore and record code stay in one class.
public class DefaultPushNotificationStorage
    @JvmOverloads
    public constructor(
        private val preferences: SharedPreferences,
        private val allowSoftwareBackedKeys: Boolean = false,
    ) : PushNotificationStorage {
        private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

        override suspend fun getPublicKey(
            issuer: String,
            accountId: String,
        ): Jwk? =
            withContext(Dispatchers.IO) {
                // Only a single key should exist for issuer/accountId combination
                aliasesFor(issuer, accountId)
                    .firstOrNull()
                    ?.let { alias -> Jwk.fromEcPublicKey(publicKeyOf(alias), KeyAlias.keyIdOf(alias)) }
            }

        override suspend fun createSignature(
            issuer: String,
            accountId: String,
            keyId: String,
            data: String,
        ): String =
            withContext(Dispatchers.IO) {
                // The record is checked first: with none recorded the account isn't enrolled,
                // whatever the Keystore holds.
                if (!preferences.contains(KeyAlias.prefixFor(issuer, accountId))) {
                    throw NotEnrolledException(accountId)
                }

                val alias = KeyAlias.of(issuer, accountId, keyId)
                if (!keyStore.containsAlias(alias)) {
                    throw StorageException("No key is stored for this key id")
                }

                val privateKey = keyStore.getKey(alias, null) as PrivateKey
                // No explicit provider: an AndroidKeyStore-backed key needs the JCA's own
                // provider-reassignment-on-initSign to route to the Keystore's Signature engine.
                // Pinning jcaContext.provider to keyStore.provider bypasses that and fails instead —
                // that Provider object doesn't expose a "SHA256withECDSA" service directly.
                val signer = ECDSASigner(privateKey, Curve.P_256)

                signer.sign(JWSHeader(JWSAlgorithm.ES256), data.toByteArray(Charsets.UTF_8)).toString()
            }

        /** The key and its record are written one after the other, so a failure in between undoes the first. */
        @Suppress("TooGenericExceptionCaught")
        override suspend fun createEnrollment(
            issuer: String,
            accountId: String,
            userName: String?,
            deviceName: String?,
        ): Jwk =
            withContext(Dispatchers.IO) {
                // Not a single atomic operation — check narrows the race window against a concurrent
                // call for the same issuer and account, but doesn't eliminate it.
                if (aliasesFor(issuer, accountId).isNotEmpty()) {
                    throw AlreadyEnrolledException(issuer, accountId)
                }

                val keyId = KeyAlias.newKeyId()
                val alias = KeyAlias.of(issuer, accountId, keyId)
                createKeystoreEntry(alias)

                try {
                    // Generated first; discarded if it fails the policy.
                    if (!allowSoftwareBackedKeys && KeySecurity.isSoftwareBacked(keyStore, alias)) {
                        throw SoftwareBackedKeyException()
                    }

                    record(Enrollment(issuer, accountId, keyId, userName, deviceName))
                    Jwk.fromEcPublicKey(publicKeyOf(alias), keyId)
                } catch (failure: Exception) {
                    runCatching { forgetRecord(issuer, accountId) }
                    runCatching { keyStore.deleteEntry(alias) }
                    throw failure
                }
            }

        override suspend fun listEnrollments(): List<Enrollment> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val aliases = keyStore.aliases().toList().toSet()
                    preferences.all
                        .filterKeys(KeyAlias::isOurs)
                        .values
                        .mapNotNull { value ->
                            runCatching { format.decodeFromString<EnrollmentRecord>(value as String).toEnrollment() }
                                .getOrNull()
                        }.filter { KeyAlias.of(it.issuer, it.accountId, it.keyId) in aliases }
                }.getOrDefault(emptyList())
            }

        /**
         * Both removals always run — a failure removing the record doesn't skip the key, nor the
         * other way round — and then the first failure is thrown, with the second attached to it as
         * suppressed. The record goes first, so an interruption between the two can only leave a key
         * without a record. Also removes a record whose key is gone, so that nothing is left to block
         * a new enrollment.
         */
        override suspend fun removeEnrollment(
            issuer: String,
            accountId: String,
        ) {
            withContext(Dispatchers.IO) {
                val recordFailure = runCatching { forgetRecord(issuer, accountId) }.exceptionOrNull()
                val keyFailure = runCatching { forgetKeys(issuer, accountId) }.exceptionOrNull()

                val failure = recordFailure ?: keyFailure
                if (failure != null) {
                    if (recordFailure != null && keyFailure != null) failure.addSuppressed(keyFailure)
                    throw failure
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun forgetKeys(
            issuer: String,
            accountId: String,
        ) {
            try {
                aliasesFor(issuer, accountId).forEach { keyStore.deleteEntry(it) }
            } catch (failure: PushNotificationException) {
                throw failure
            } catch (failure: Exception) {
                throw StorageException("The key could not be removed", failure)
            }
        }

        /** Falls back to the TEE if StrongBox is unavailable. */
        private fun createKeystoreEntry(alias: String) {
            val attempt = runCatching { createKeystoreEntry(alias, strongBox = true) }
            if (attempt.exceptionOrNull() is StrongBoxUnavailableException) {
                createKeystoreEntry(alias, strongBox = false)
            } else {
                attempt.getOrThrow()
            }
        }

        private fun createKeystoreEntry(
            alias: String,
            strongBox: Boolean,
        ) {
            val spec =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    // No user presence required.
                    .setUserAuthenticationRequired(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build()

            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply { initialize(spec) }
                .generateKeyPair()
        }

        private fun publicKeyOf(alias: String): ECPublicKey = keyStore.getCertificate(alias).publicKey as ECPublicKey

        private fun aliasesFor(
            issuer: String,
            accountId: String,
        ): List<String> =
            keyStore
                .aliases()
                .toList()
                .filter { it.startsWith(KeyAlias.prefixFor(issuer, accountId)) }

        @SuppressLint("UseKtx")
        private fun record(enrollment: Enrollment) {
            val key = KeyAlias.prefixFor(enrollment.issuer, enrollment.accountId)
            val json = format.encodeToString(enrollment.toRecord())
            // commit(), not apply() — apply() is fire-and-forget and reports no result.
            if (!preferences.edit().putString(key, json).commit()) {
                throw StorageException("The enrollment could not be recorded")
            }
        }

        @SuppressLint("UseKtx")
        private fun forgetRecord(
            issuer: String,
            accountId: String,
        ) {
            if (!preferences.edit().remove(KeyAlias.prefixFor(issuer, accountId)).commit()) {
                throw StorageException("The enrollment record could not be removed")
            }
        }

        private companion object {
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val CURVE = "secp256r1"
        }
    }

/** Tolerant of a stored record from an older or newer SDK version: unknown keys are ignored. */
private val format = Json { ignoreUnknownKeys = true }

/** The persisted shape of an enrollment, kept separate from the public [Enrollment] domain type. */
@Serializable
private data class EnrollmentRecord(
    val issuer: String,
    val accountId: String,
    val keyId: String,
    val userName: String? = null,
    val deviceName: String? = null,
)

private fun Enrollment.toRecord(): EnrollmentRecord = EnrollmentRecord(issuer, accountId, keyId, userName, deviceName)

private fun EnrollmentRecord.toEnrollment(): Enrollment = Enrollment(issuer, accountId, keyId, userName, deviceName)
