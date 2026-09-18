package com.strivacity.android.pushnotification.support

/**
 * Runs [block] and asserts it throws a [T], returning it so the caller can assert on it further.
 *
 * Fails if [block] returns normally, or if it throws anything that isn't a [T] — a mismatched
 * type propagates as-is, so JUnit reports it as the actual test failure rather than this
 * function's generic message.
 */
internal suspend inline fun <reified T : Throwable> assertThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw failure
    }
    throw AssertionError("Expected ${T::class.simpleName} to be thrown, but nothing was")
}
