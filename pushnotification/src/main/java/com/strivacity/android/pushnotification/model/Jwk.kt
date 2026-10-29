package com.strivacity.android.pushnotification.model

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import java.security.interfaces.ECPublicKey

/**
 * A public key in JWK form (RFC 7517).
 *
 * [alg] and [kid] are tracked as fields; everything else is an entry in [members], the key's
 * remaining JWK members (`kty`, `crv`, `x` and `y` for a P-256 key):
 *
 * ```
 * Jwk(kid, "ES256", mapOf("kty" to "EC", "crv" to "P-256", "x" to x, "y" to y))
 * ```
 *
 * [members] must hold only the public half; see [PRIVATE_MEMBERS].
 */
public data class Jwk(
    public val kid: String,
    public val alg: String,
    public val members: Map<String, String>,
) {
    /** Omits member values. */
    override fun toString(): String = "Jwk(kid=$kid, alg=$alg, members=${members.keys.sorted()})"

    public companion object {
        public const val ALGORITHM_ES256: String = "ES256"

        private const val MEMBER_KEY_ID = "kid"
        private const val MEMBER_ALGORITHM = "alg"

        /** The private-key member of an EC key, `d` (RFC 7518 §6.2.2). Must not appear in [members]. */
        public val PRIVATE_MEMBERS: Set<String> = setOf("d")

        /** Builds the JWK for a P-256 public key. */
        public fun fromEcPublicKey(
            key: ECPublicKey,
            kid: String,
        ): Jwk {
            val ecKey =
                ECKey
                    .Builder(Curve.P_256, key)
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.ES256)
                    .build()

            return Jwk(
                kid = kid,
                alg = ALGORITHM_ES256,
                members =
                    ecKey
                        .toPublicJWK()
                        .toJSONObject()
                        .filterKeys { it != MEMBER_KEY_ID && it != MEMBER_ALGORITHM }
                        .mapValues { (_, value) -> value.toString() },
            )
        }
    }
}
