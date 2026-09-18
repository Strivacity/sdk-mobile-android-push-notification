package com.strivacity.android.pushnotification.model

/**
 * The relationship between one account, this device, and one keypair.
 *
 * Carries nothing secret.
 */
public data class Enrollment(
    val issuer: String,
    val accountId: String,
    val keyId: String,
    val userName: String?,
    val deviceName: String?,
)
