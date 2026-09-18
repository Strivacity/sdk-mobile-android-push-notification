package com.strivacity.android.pushnotification.support

internal suspend inline fun <reified T : Throwable> assertThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw failure
    }
    throw AssertionError("Expected ${T::class.simpleName} to be thrown, but nothing was")
}
