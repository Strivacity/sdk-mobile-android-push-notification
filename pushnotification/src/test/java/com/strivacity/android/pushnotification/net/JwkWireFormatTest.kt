package com.strivacity.android.pushnotification.net

import com.strivacity.android.pushnotification.model.Jwk
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [toJsonObject] directly — pure logic, no fakes needed. The allowlist restricting
 * which algorithms `enrollDevice` accepts lives elsewhere (`PushNotification.kt`); this wire
 * function itself makes no assumption about the key shape.
 */
class JwkWireFormatTest {
    @Test
    fun `includes every member plus alg and kid`() {
        val jwk =
            Jwk(
                kid = "key-1",
                alg = "ES256",
                members = mapOf("kty" to "EC", "crv" to "P-256", "x" to "x-coord", "y" to "y-coord"),
            )

        val json = jwk.toJsonObject()

        assertEquals("EC", json["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", json["crv"]?.jsonPrimitive?.content)
        assertEquals("x-coord", json["x"]?.jsonPrimitive?.content)
        assertEquals("y-coord", json["y"]?.jsonPrimitive?.content)
        assertEquals("ES256", json["alg"]?.jsonPrimitive?.content)
        assertEquals("key-1", json["kid"]?.jsonPrimitive?.content)
    }

    @Test
    fun `alg and kid win over conflicting values in members`() {
        val jwk = Jwk(kid = "real-kid", alg = "ES256", members = mapOf("alg" to "spoofed-alg", "kid" to "spoofed-kid"))

        val json = jwk.toJsonObject()

        assertEquals("ES256", json["alg"]?.jsonPrimitive?.content)
        assertEquals("real-kid", json["kid"]?.jsonPrimitive?.content)
    }

    @Test
    fun `serializes an RSA-shaped key, even though today's allowlist rejects it`() {
        val jwk = Jwk(kid = "key-1", alg = "RS256", members = mapOf("kty" to "RSA", "n" to "modulus", "e" to "AQAB"))

        val json = jwk.toJsonObject()

        assertEquals("RSA", json["kty"]?.jsonPrimitive?.content)
        assertEquals("modulus", json["n"]?.jsonPrimitive?.content)
        assertEquals("AQAB", json["e"]?.jsonPrimitive?.content)
    }
}
