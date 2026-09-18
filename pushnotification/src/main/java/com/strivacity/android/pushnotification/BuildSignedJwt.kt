package com.strivacity.android.pushnotification

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.util.Base64URL

/**
 * Assembles a compact JWT: header, payload, and signature, base64url-encoded and dot-separated.
 *
 * @param sign signs the header-and-payload signing input and returns the signature as a
 *   base64url string.
 */
internal suspend fun buildSignedJwt(
    keyId: String,
    algorithm: String,
    payload: String,
    sign: suspend (signingInput: String) -> String,
): String {
    val header =
        JWSHeader
            .Builder(JWSAlgorithm.parse(algorithm))
            .keyID(keyId)
            .type(JOSEObjectType.JWT)
            .build()
    val unsigned = JWSObject(header, Payload(payload))

    // Nimbus's own signer is blocking, so the signature comes from `sign` and the signed object is
    // put together from its three parts.
    val signature = Base64URL(sign(String(unsigned.signingInput, Charsets.UTF_8)))
    return JWSObject(header.toBase64URL(), unsigned.payload.toBase64URL(), signature).serialize()
}
