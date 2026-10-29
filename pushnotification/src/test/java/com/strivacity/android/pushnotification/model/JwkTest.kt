package com.strivacity.android.pushnotification.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Exercises [Jwk.fromEcPublicKey] directly — an isolated unit test: it's pure JCA
 * logic needing no Android runtime, but it's also only ever called from
 * `DefaultPushNotificationStorage`, which does need one, so it's not reachable through the public
 * interface without an instrumented test.
 */
class JwkTest {
    private val publicKey: ECPublicKey =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
            .public as ECPublicKey

    @Test
    fun `fromEcPublicKey carries the given key id and ES256 as the algorithm`() {
        val jwk = Jwk.fromEcPublicKey(publicKey, kid = "key-1")

        assertEquals("key-1", jwk.kid)
        assertEquals(Jwk.ALGORITHM_ES256, jwk.alg)
    }

    @Test
    fun `fromEcPublicKey's members describe an EC P-256 public key, and carry no kid or alg`() {
        val jwk = Jwk.fromEcPublicKey(publicKey, kid = "key-1")

        assertEquals("EC", jwk.members["kty"])
        assertEquals("P-256", jwk.members["crv"])
        assertEquals(setOf("kty", "crv", "x", "y"), jwk.members.keys)
    }

    @Test
    fun `fromEcPublicKey never includes private key material`() {
        val jwk = Jwk.fromEcPublicKey(publicKey, kid = "key-1")

        assertFalse(jwk.members.keys.any { it in Jwk.PRIVATE_MEMBERS })
    }
}
