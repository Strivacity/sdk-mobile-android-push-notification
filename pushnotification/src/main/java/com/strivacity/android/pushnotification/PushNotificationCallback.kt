package com.strivacity.android.pushnotification

/**
 * Reports the result of an `*Async` call.
 *
 * Exactly one of [onResult] or [onError] is called, on the supplied `Executor`. Neither is
 * called if the call is cancelled.
 */
public interface PushNotificationCallback<R, E : Throwable> {
    public fun onResult(result: R)

    public fun onError(error: E)
}
