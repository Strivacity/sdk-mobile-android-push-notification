package com.strivacity.android.pushnotification.util

import com.strivacity.android.pushnotification.model.InvalidDeviceNameException

private const val MAX_DEVICE_NAME_LENGTH = 64

/**
 * Validates that the trimmed device name is between 1 and 64 characters long, with no other
 * restriction. Characters are counted as Unicode code points, so an emoji counts as one.
 *
 * @throws InvalidDeviceNameException if the trimmed [deviceName] is empty or exceeds 64 characters.
 *   The check applies to the trimmed value; the original, untrimmed string is what the SDK sends to
 *   the backend and stores.
 */
internal fun validateDeviceName(deviceName: String) {
    val trimmed = deviceName.trim()
    val length = trimmed.codePointCount(0, trimmed.length)
    if (length !in 1..MAX_DEVICE_NAME_LENGTH) {
        throw InvalidDeviceNameException()
    }
}
