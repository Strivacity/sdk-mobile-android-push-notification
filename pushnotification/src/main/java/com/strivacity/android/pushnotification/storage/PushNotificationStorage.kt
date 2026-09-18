package com.strivacity.android.pushnotification.storage

import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.Jwk

/**
 * Everything the SDK persists: each enrollment's key and the record of the enrollment itself. A
 * replaceable extension point — the SDK ships [DefaultPushNotificationStorage], backed by the
 * Android Keystore and a [android.content.SharedPreferences] instance.
 *
 * A replacement must:
 *
 * - use a key id unique to the key, not to the account;
 * - return the signature from [createSignature] as a base64url string;
 * - return a [Jwk] holding only the public half — see [Jwk.PRIVATE_MEMBERS];
 * - keep each enrollment's key and record together, so that one is never left without the other;
 * - throw [com.strivacity.android.pushnotification.model.StorageException] for a failure — the SDK
 *   rethrows it unchanged.
 *
 * Implementations may block; every method runs on a coroutine.
 */
public interface PushNotificationStorage {
    /**
     * The public key currently in use for this issuer and account, or `null` if none is stored.
     *
     * Only a single key exists for an issuer and account combination.
     */
    public suspend fun getPublicKey(
        issuer: String,
        accountId: String,
    ): Jwk?

    /**
     * Signs [data] with the key of this id, returning the signature as a base64url string.
     *
     * [issuer] and [accountId] identify the same key [getPublicKey] returned it for.
     *
     * @throws com.strivacity.android.pushnotification.model.NotEnrolledException if no enrollment
     *   is recorded for this issuer and account — checked first.
     * @throws com.strivacity.android.pushnotification.model.StorageException if an enrollment is
     *   recorded but no key of that id is stored.
     */
    public suspend fun createSignature(
        issuer: String,
        accountId: String,
        keyId: String,
        data: String,
    ): String

    /**
     * Generates a new keypair for this issuer and account, records the enrollment it belongs to,
     * and returns the key's public half. The recorded enrollment's [Enrollment.keyId] is the
     * returned key's [Jwk.kid].
     *
     * The SDK calls this before the backend has accepted the enrollment, and calls
     * [removeEnrollment] if the backend refuses it.
     *
     * The SDK calls [removeEnrollment] for this issuer and account first, so an existing key here
     * means a concurrent call created it in the meantime.
     *
     * @throws com.strivacity.android.pushnotification.model.AlreadyEnrolledException if a key
     *   already exists for this issuer and account — including when lost to a race against a
     *   concurrent call for the same issuer and account. The default implementation does not
     *   synchronize between concurrent calls.
     */
    public suspend fun createEnrollment(
        issuer: String,
        accountId: String,
        userName: String?,
        deviceName: String?,
    ): Jwk

    /** Every enrollment currently recorded. A read failure degrades to an empty list. */
    public suspend fun listEnrollments(): List<Enrollment>

    /**
     * Removes every key and enrollment record held for this issuer and account. Silently does
     * nothing if none is stored.
     */
    public suspend fun removeEnrollment(
        issuer: String,
        accountId: String,
    )
}
