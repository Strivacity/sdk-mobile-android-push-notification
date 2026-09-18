package com.strivacity.android.pushnotification.fakes

import com.strivacity.android.pushnotification.model.AlreadyEnrolledException
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.Jwk
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.StorageException
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import com.strivacity.android.pushnotification.util.Base64Url
import java.util.concurrent.atomic.AtomicInteger

/** In-memory [PushNotificationStorage] fake. Keys are ES256 with predictable, incrementing ids. */
internal class FakePushNotificationStorage : PushNotificationStorage {
    private val entries = mutableMapOf<Pair<String, String>, Entry>()
    private val nextKeyId = AtomicInteger(1)

    override suspend fun getPublicKey(
        issuer: String,
        accountId: String,
    ): Jwk? = entries[issuer to accountId]?.key

    override suspend fun createSignature(
        issuer: String,
        accountId: String,
        keyId: String,
        data: String,
    ): String {
        val entry = entries[issuer to accountId]
        if (entry?.enrollment == null) {
            throw NotEnrolledException(accountId)
        }
        if (entry.key.kid != keyId) {
            throw StorageException("No key is stored for this key id")
        }
        // A real signature is base64url, per this method's contract — encoded here too, so a
        // signed-JWT's segments never accidentally pick up a literal '.' from the signing input.
        return Base64Url.encode("signature-of($data)")
    }

    override suspend fun createEnrollment(
        issuer: String,
        accountId: String,
        userName: String?,
        deviceName: String?,
    ): Jwk {
        if (entries.containsKey(issuer to accountId)) {
            throw AlreadyEnrolledException(issuer, accountId)
        }

        val key = newKey()
        val enrollment = Enrollment(issuer, accountId, key.kid, userName, deviceName)
        entries[issuer to accountId] = Entry(key, enrollment)
        return key
    }

    override suspend fun listEnrollments(): List<Enrollment> = entries.values.mapNotNull { it.enrollment }

    override suspend fun removeEnrollment(
        issuer: String,
        accountId: String,
    ) {
        entries.remove(issuer to accountId)
    }

    /** Stores a key with no enrollment record for this issuer and account. */
    fun storeKeyWithoutEnrollment(
        issuer: String,
        accountId: String,
    ): Jwk {
        val key = newKey()
        entries[issuer to accountId] = Entry(key, enrollment = null)
        return key
    }

    private fun newKey(): Jwk =
        Jwk(
            kid = "key-${nextKeyId.getAndIncrement()}",
            alg = Jwk.ALGORITHM_ES256,
            members = mapOf("kty" to "EC", "crv" to "P-256", "x" to "x-coord", "y" to "y-coord"),
        )

    private class Entry(
        val key: Jwk,
        val enrollment: Enrollment?,
    )
}
