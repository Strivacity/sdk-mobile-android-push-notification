package com.strivacity.android.pushnotification.model

/**
 * Identifies the type of push token being supplied to [enrollDevice] or [updateDeviceToken].
 *
 * The app supplies this explicitly; the SDK does not infer it from the platform because
 * multiple token types can be valid on a single platform (e.g., FCM is supported on both
 * Android and iOS).
 */
public enum class DeviceTokenType {
    FCM_TOKEN,
    FCM_FID,
}
