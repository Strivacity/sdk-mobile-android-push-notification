package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.model.AlreadyEnrolledException
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.ChallengeResolution
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentException
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.InvalidDeviceNameException
import com.strivacity.android.pushnotification.model.InvalidIssuerException
import com.strivacity.android.pushnotification.model.NetworkException
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.ResolutionResult
import com.strivacity.android.pushnotification.model.SoftwareBackedKeyException
import com.strivacity.android.pushnotification.model.StorageException
import com.strivacity.android.pushnotification.model.UnenrollmentException
import com.strivacity.android.pushnotification.model.UnsupportedAlgorithmException
import com.strivacity.android.pushnotification.model.UnsupportedChallengeTypeException
import com.strivacity.android.pushnotification.model.UpdateTokenException

/**
 * Push notification enrollment and challenge resolution.
 *
 * Provides asynchronous methods to enroll devices, manage enrollments, handle and resolve
 * authentication challenges delivered in push notifications, and query enrollment state.
 * The host app is responsible for QR code scanning, obtaining and rotating push tokens,
 * managing its messaging service, presenting notifications and UI.
 *
 * Every method suspends. Java callers use the `*Async` variants in [PushNotificationCompat].
 *
 * The SDK does not serialize calls for the same issuer and account: making overlapping calls
 * for the same (issuer, accountId) — e.g. two concurrent [enrollDevice] calls from a double-tap
 * — is the caller's responsibility to prevent, not something this interface guards against.
 *
 * Whatever the storage throws is rethrown unchanged — [StorageException] from the default storage,
 * though a platform exception it doesn't wrap can also escape. The `*Async` variants report such
 * an exception as `UnexpectedException`.
 */
public interface PushNotificationInterface {
    /**
     * Enrolls this device for the account named in [grant].
     *
     * The key and enrollment record are stored before the backend is called, and removed again if
     * it fails. If the app is terminated mid-call the enrollment stays recorded; [unenrollDevice]
     * removes it.
     *
     * @param grant the enrollment payload
     * @param deviceToken the current push token, owned by the app.
     * @param deviceTokenType what kind of token [deviceToken] is.
     * @param deviceName a label for this device, 1-64 characters after trimming.
     * @throws InvalidDeviceNameException
     * @throws InvalidIssuerException
     * @throws AlreadyEnrolledException un-enroll first to replace it.
     * @throws SoftwareBackedKeyException unless the storage allows software-backed keys.
     * @throws StorageException from storage, or for a key the SDK can't accept.
     * @throws UnsupportedAlgorithmException for a key the SDK can't accept.
     * @throws EnrollmentException the backend refused. Not retryable; obtain a fresh grant.
     * @throws NetworkException the backend couldn't be reached.
     *
     * A failed rollback replaces the exception that caused it.
     */
    public suspend fun enrollDevice(
        grant: EnrollmentGrant,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
        deviceName: String,
    ): Enrollment

    /**
     * Removes this device's enrollment for an account.
     *
     * The backend is called first. The local key and record are then removed whether or not that
     * succeeded, or this call is cancelled, so a backend failure never blocks re-enrolling —
     * though the backend may still hold the enrollment, with no key left to retry.
     *
     * @throws InvalidIssuerException
     * @throws NotEnrolledException
     * @throws StorageException looking up or signing with the key, before the backend is
     *   contacted: nothing has changed. Or removing locally: replaces any backend failure, which is
     *   lost. The default storage runs both its removals and throws the first failure, the second
     *   attached as suppressed.
     * @throws UnenrollmentException the backend refused; thrown after the local removal.
     * @throws NetworkException the backend couldn't be reached; thrown after the local removal.
     */
    public suspend fun unenrollDevice(
        issuer: String,
        accountId: String,
    )

    /**
     * Whether an enrollment is recorded for this issuer and account — the check [enrollDevice]
     * makes. An unreadable store reads as not enrolled.
     *
     * @throws InvalidIssuerException
     */
    public suspend fun hasEnrollment(
        issuer: String,
        accountId: String,
    ): Boolean

    /**
     * Every enrollment this device holds, across issuers and accounts, including ones the app
     * never initiated. Lets the app decide how to report a rotated push token, via
     * [updateDeviceToken].
     *
     * Doesn't throw: an unreadable store reads as empty.
     */
    public suspend fun listEnrollments(): List<Enrollment>

    /**
     * Reports a new push token for an existing enrollment. Call it once per enrollment when the
     * platform delivers a new token — the SDK doesn't detect rotation.
     *
     * @throws InvalidIssuerException
     * @throws NotEnrolledException
     * @throws StorageException looking up or signing with the key.
     * @throws UpdateTokenException the backend refused.
     * @throws NetworkException the backend couldn't be reached.
     */
    public suspend fun updateDeviceToken(
        issuer: String,
        accountId: String,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
    )

    /**
     * Whether this push payload is a challenge for a key this device holds.
     *
     * Returns `false`, not an exception, for a missing or unrecognised envelope (version included)
     * or a malformed issuer — both come from an untrusted payload.
     *
     * @throws StorageException looking up the key.
     */
    public suspend fun canHandleMessage(messageData: Map<String, String>?): Boolean

    /**
     * Fetches the challenge the payload points at. Makes a network call. Call [canHandleMessage]
     * first.
     *
     * @throws InvalidIssuerException
     * @throws NotEnrolledException no key is held for the issuer and account the payload names.
     * @throws StorageException looking up the key.
     * @throws ChallengeException no usable envelope, backend refusal, or a response it can't read.
     * @throws UnsupportedChallengeTypeException
     * @throws NetworkException the backend couldn't be reached.
     */
    public suspend fun handleMessage(messageData: Map<String, String>?): Challenge

    /**
     * Submits the user's answer. A recorded refusal returns [ResolutionResult.NOT_OK]; it isn't an
     * exception. The resolution's issuer is used as given, not normalised.
     *
     * @throws NotEnrolledException
     * @throws StorageException looking up or signing with the key.
     * @throws ChallengeException a backend response that is neither acceptance nor denial.
     * @throws NetworkException the backend couldn't be reached.
     */
    public suspend fun resolveChallenge(resolution: ChallengeResolution): ResolutionResult
}
