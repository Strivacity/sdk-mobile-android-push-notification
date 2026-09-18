package com.strivacity.android.pushnotification.util

import java.util.Base64

/** Base64url without padding (RFC 7515, appendix C). */
internal object Base64Url {
    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

    fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

    fun decode(text: String): ByteArray = DECODER.decode(text)
}
