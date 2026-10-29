package com.strivacity.android.pushnotification.storage

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey

/** Reports whether a generated key is held in secure hardware or in software. */
internal object KeySecurity {
    /**
     * Whether the key at [alias] is held in software rather than secure hardware.
     *
     * Uses `KeyInfo.isInsideSecureHardware` below Android 12, `KeyInfo.securityLevel` from 12.
     * `SECURITY_LEVEL_UNKNOWN_SECURE` counts as hardware; `SECURITY_LEVEL_UNKNOWN` counts as
     * software.
     */
    @Suppress("DEPRECATION")
    fun isSoftwareBacked(
        keyStore: KeyStore,
        alias: String,
    ): Boolean {
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val info =
            KeyFactory
                .getInstance(privateKey.algorithm, keyStore.provider)
                .getKeySpec(privateKey, KeyInfo::class.java)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_SOFTWARE ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_UNKNOWN
        } else {
            !info.isInsideSecureHardware
        }
    }
}
