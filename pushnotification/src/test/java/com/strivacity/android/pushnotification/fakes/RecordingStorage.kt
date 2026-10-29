package com.strivacity.android.pushnotification.fakes

import com.strivacity.android.pushnotification.model.Jwk
import com.strivacity.android.pushnotification.storage.PushNotificationStorage

/** The order in which storage operations were called. */
internal class CallLog {
    val entries = mutableListOf<String>()

    fun record(entry: String) {
        entries += entry
    }
}

/** Delegates to another [PushNotificationStorage], recording each call that changes what is stored. */
internal class RecordingStorage(
    private val delegate: PushNotificationStorage,
    private val log: CallLog,
) : PushNotificationStorage by delegate {
    override suspend fun createEnrollment(
        issuer: String,
        accountId: String,
        userName: String?,
        deviceName: String?,
    ): Jwk {
        log.record("createEnrollment")
        return delegate.createEnrollment(issuer, accountId, userName, deviceName)
    }

    override suspend fun removeEnrollment(
        issuer: String,
        accountId: String,
    ) {
        log.record("removeEnrollment")
        delegate.removeEnrollment(issuer, accountId)
    }
}
