package com.strivacity.android.pushnotification

import android.util.Log

/**
 * Logging interface used by the SDK.
 *
 * **Thread safety:** Implementations of this interface must be thread-safe. Methods will be called
 * concurrently from multiple threads.
 *
 * **Exception handling:** Implementations should handle exceptions internally and not throw from
 * these methods, as that could disrupt the SDK's operation.
 */
public interface Logging {
    public fun debug(body: String)

    public fun info(body: String)

    public fun warn(body: String)

    public fun error(body: String)
}

/**
 * Default implementation of the Logging interface that logs to Android's Logcat.
 *
 * Logcat is thread-safe, so this implementation is also thread-safe.
 */
public class DefaultLogging : Logging {
    override fun debug(body: String) {
        Log.d(
            "PushNotification",
            body,
        )
    }

    override fun info(body: String) {
        Log.i(
            "PushNotification",
            body,
        )
    }

    override fun warn(body: String) {
        Log.w(
            "PushNotification",
            body,
        )
    }

    override fun error(body: String) {
        Log.e(
            "PushNotification",
            body,
        )
    }
}
