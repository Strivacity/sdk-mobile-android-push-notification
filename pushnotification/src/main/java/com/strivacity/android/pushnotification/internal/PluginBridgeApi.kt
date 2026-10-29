package com.strivacity.android.pushnotification.internal

/**
 * Marks an API that exists for the Flutter plugin. It is not a supported application API and may
 * change without notice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Used by the Flutter plugin only. Not a supported API; may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class PluginBridgeApi
