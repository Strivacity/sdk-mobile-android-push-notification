package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.envelope.PushEnvelope
import com.strivacity.android.pushnotification.model.AlreadyEnrolledException
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.ChallengeResolution
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.Jwk
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.ResolutionResult
import com.strivacity.android.pushnotification.model.StorageException
import com.strivacity.android.pushnotification.model.UnsupportedAlgorithmException
import com.strivacity.android.pushnotification.net.EnrollRequest
import com.strivacity.android.pushnotification.net.Platforms
import com.strivacity.android.pushnotification.net.PushNotificationClient
import com.strivacity.android.pushnotification.net.toDomain
import com.strivacity.android.pushnotification.net.toJsonObject
import com.strivacity.android.pushnotification.net.toWireValue
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import com.strivacity.android.pushnotification.util.normalizeIssuer
import com.strivacity.android.pushnotification.util.validateDeviceName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Core SDK implementation of the push notification enrollment and challenge API.
 *
 * One instance serves every issuer a device is enrolled against; the issuer is supplied with
 * each grant, challenge, and resolution. Instances are app-scoped and are not closed.
 */
public class PushNotification internal constructor(
    private val storage: PushNotificationStorage,
    private val client: PushNotificationClient,
) : PushNotificationInterface {
    public constructor(storage: PushNotificationStorage) : this(storage, PushNotificationClient())

    override suspend fun enrollDevice(
        grant: EnrollmentGrant,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
        deviceName: String,
    ): Enrollment {
        validateDeviceName(deviceName)
        val issuer = normalizeIssuer(grant.issuer)

        // The recorded enrollments are the source of truth, not the presence of a key
        if (isEnrolled(storage, issuer, grant.accountId)) {
            throw AlreadyEnrolledException(issuer, grant.accountId)
        }

        storage.removeEnrollment(issuer, grant.accountId)

        // Not atomic with the check above — a concurrent enrollDevice call for the same issuer
        // and account can still win the race. createEnrollment's own guard against that throws
        // AlreadyEnrolledException itself, so both the fast-path miss and the race-loser path
        // surface the same typed exception with no extra handling needed here.
        // NonCancellable: a cancellation arriving as this call completes would otherwise discard
        // the key of an enrollment already created, leaking it before enroll()'s rollback below
        // can cover it.
        val key =
            withContext(NonCancellable) {
                storage.createEnrollment(issuer, grant.accountId, grant.displayUserName, deviceName)
            }
        enroll(issuer, grant, key, deviceToken, deviceTokenType, deviceName)

        return Enrollment(issuer, grant.accountId, key.kid, grant.displayUserName, deviceName)
    }

    override suspend fun unenrollDevice(
        issuer: String,
        accountId: String,
    ) {
        val normalized = normalizeIssuer(issuer)
        val key = storage.getPublicKey(normalized, accountId) ?: throw NotEnrolledException(accountId)

        val assertion =
            sign(normalized, accountId, key) {
                put(CLAIM_ACCOUNT_ID, accountId)
            }

        // The local removal always runs, even if the backend call fails or is cancelled — see the
        // interface doc. NonCancellable so a cancellation that interrupts the backend call can't
        // also cut off the local cleanup this promises.
        val backendFailure = runCatching { client.unenroll(normalized, assertion) }.exceptionOrNull()
        withContext(NonCancellable) { storage.removeEnrollment(normalized, accountId) }

        backendFailure?.let { throw it }
    }

    override suspend fun updateDeviceToken(
        issuer: String,
        accountId: String,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
    ) {
        val normalized = normalizeIssuer(issuer)
        val key = storage.getPublicKey(normalized, accountId) ?: throw NotEnrolledException(accountId)

        val assertion =
            sign(normalized, accountId, key) {
                put(CLAIM_ACCOUNT_ID, accountId)
                put(CLAIM_DEVICE_TOKEN_TYPE, deviceTokenType.toWireValue())
                put(CLAIM_DEVICE_TOKEN, deviceToken)
            }

        client.updateToken(normalized, assertion)
    }

    override suspend fun hasEnrollment(
        issuer: String,
        accountId: String,
    ): Boolean = isEnrolled(storage, normalizeIssuer(issuer), accountId)

    override suspend fun listEnrollments(): List<Enrollment> = storage.listEnrollments()

    override suspend fun canHandleMessage(messageData: Map<String, String>?): Boolean {
        val envelope = PushEnvelope.parse(messageData)
        val issuer = envelope?.let { runCatching { normalizeIssuer(it.issuer) }.getOrNull() }

        return if (envelope == null || issuer == null) {
            false
        } else {
            storage.getPublicKey(issuer, envelope.accountId) != null
        }
    }

    override suspend fun handleMessage(messageData: Map<String, String>?): Challenge {
        val envelope =
            PushEnvelope.parse(messageData)
                ?: throw ChallengeException("Message carries no challenge this SDK version can handle")

        val issuer = normalizeIssuer(envelope.issuer)
        if (storage.getPublicKey(issuer, envelope.accountId) == null) {
            throw NotEnrolledException(envelope.accountId)
        }

        return client.challenge(issuer, envelope.challengeId).toDomain(envelope, issuer)
    }

    override suspend fun resolveChallenge(resolution: ChallengeResolution): ResolutionResult {
        val key =
            storage.getPublicKey(resolution.issuer, resolution.accountId)
                ?: throw NotEnrolledException(resolution.accountId)

        val assertion =
            sign(resolution.issuer, resolution.accountId, key) {
                put(CLAIM_CHALLENGE_ID, resolution.challengeId)
                put(CLAIM_CHALLENGE, resolution.challengeValue)
                put(CLAIM_ACTION, resolution.action)
            }

        val accepted = client.resolve(resolution.issuer, resolution.challengeId, assertion)
        return if (accepted) ResolutionResult.OK else ResolutionResult.NOT_OK
    }

    /** Rolls the key and its enrollment record back if enrollment fails. */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun enroll(
        normalizedIssuer: String,
        grant: EnrollmentGrant,
        key: Jwk,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
        deviceName: String?,
    ) {
        try {
            checkPrivateKeyMaterial(key)
            checkSupportedAlgorithm(key)

            client.enroll(
                normalizedIssuer,
                grant.oneTimeToken,
                EnrollRequest(
                    platform = Platforms.ANDROID,
                    deviceTokenType = deviceTokenType.toWireValue(),
                    deviceToken = deviceToken,
                    deviceName = deviceName,
                    publicKey = key.toJsonObject(),
                ),
            )
        } catch (failure: Exception) {
            // Broad catch deliberately: any failure here — not just a rejected/network
            // PushNotificationException, but also e.g. a request-encoding failure building
            // EnrollRequest, or cancellation — must still roll back the freshly generated key.
            // NonCancellable: without it, a cancellation-triggered rollback would itself
            // immediately throw at removeEnrollment's own entry point instead of running,
            // leaking the key.
            withContext(NonCancellable) {
                storage.removeEnrollment(normalizedIssuer, grant.accountId)
            }
            throw failure
        }
    }

    private suspend fun sign(
        normalizedIssuer: String,
        accountId: String,
        key: Jwk,
        claims: JsonObjectBuilder.() -> Unit,
    ): String =
        buildSignedJwt(
            keyId = key.kid,
            algorithm = key.alg,
            payload = buildJsonObject(claims).toString(),
        ) { signingInput -> storage.createSignature(normalizedIssuer, accountId, key.kid, signingInput) }

    private companion object {
        // Field names are placeholders pending the backend contract.
        const val CLAIM_CHALLENGE_ID = "challengeId"
        const val CLAIM_CHALLENGE = "challenge"
        const val CLAIM_ACTION = "action"
        const val CLAIM_ACCOUNT_ID = "accountId"
        const val CLAIM_DEVICE_TOKEN = "deviceToken"
        const val CLAIM_DEVICE_TOKEN_TYPE = "deviceTokenType"
    }
}

/** Accepted signing algorithms. */
private val ACCEPTED_ALGORITHMS = setOf(Jwk.ALGORITHM_ES256)

/** Whether storage records an enrollment for this issuer and account. */
private suspend fun isEnrolled(
    storage: PushNotificationStorage,
    normalizedIssuer: String,
    accountId: String,
): Boolean = storage.listEnrollments().any { it.issuer == normalizedIssuer && it.accountId == accountId }

/** Validates a key returned by [PushNotificationStorage.createEnrollment] before it reaches the backend. */
private fun checkPrivateKeyMaterial(key: Jwk) {
    val private = key.members.keys intersect Jwk.PRIVATE_MEMBERS
    if (private.isNotEmpty()) {
        throw StorageException("Storage supplied a public key still carrying private members: ${private.sorted()}")
    }
}

private fun checkSupportedAlgorithm(key: Jwk) {
    if (key.alg !in ACCEPTED_ALGORITHMS) {
        throw UnsupportedAlgorithmException(key.alg, ACCEPTED_ALGORITHMS)
    }
}
