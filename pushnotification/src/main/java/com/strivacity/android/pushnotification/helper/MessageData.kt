package com.strivacity.android.pushnotification.helper

import android.content.Intent
import com.strivacity.android.pushnotification.envelope.PushEnvelope

/** Helpers for reading the SDK's push envelope out of a delivered notification. */
public object MessageData {
    /**
     * The push envelope from [intent]'s extras, as the `Map<String, String>`
     * [com.strivacity.android.pushnotification.PushNotificationInterface.canHandleMessage] and
     * [com.strivacity.android.pushnotification.PushNotificationInterface.handleMessage] expect.
     *
     * For a message delivered as a launch intent — the case when the push also carries a
     * notification payload, so the platform starts the app instead of invoking the app's own
     * message handler.
     *
     * @return null if the intent's extras hold no entry for [PushEnvelope.KEY].
     */
    @JvmStatic
    public fun from(intent: Intent): Map<String, String>? {
        val envelope = intent.extras?.getString(PushEnvelope.KEY) ?: return null
        return mapOf(PushEnvelope.KEY to envelope)
    }
}
