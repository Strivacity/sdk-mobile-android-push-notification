package com.strivacity.android.pushnotification

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.strivacity.android.pushnotification.util.Base64Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Exercises [buildSignedJwt] directly — pure logic, a stub `sign` callback, no fakes needed. */
class BuildSignedJwtTest {
    @Test
    fun `returns three dot-separated segments`() =
        runTest {
            val jwt = buildSignedJwt("key-1", "ES256", """{"a":"b"}""") { "signature" }

            assertEquals(3, jwt.split(".").size)
        }

    @Test
    fun `the header segment decodes to exactly alg, kid, and typ JWT`() =
        runTest {
            val jwt = buildSignedJwt("key-1", "ES256", """{"a":"b"}""") { "signature" }

            val header = decodeSegment(jwt, 0)
            assertEquals(setOf("alg", "kid", "typ"), header.keys)
            assertEquals("ES256", header["alg"]?.jsonPrimitive?.content)
            assertEquals("key-1", header["kid"]?.jsonPrimitive?.content)
            assertEquals("JWT", header["typ"]?.jsonPrimitive?.content)
        }

    @Test
    fun `the algorithm passed in is used verbatim, not a constant`() =
        runTest {
            val jwt = buildSignedJwt("key-1", "RS256", """{"a":"b"}""") { "signature" }

            assertEquals("RS256", decodeSegment(jwt, 0)["alg"]?.jsonPrimitive?.content)
        }

    @Test
    fun `the payload segment decodes to exactly the JSON string passed in`() =
        runTest {
            val jwt = buildSignedJwt("key-1", "ES256", """{"a":"b","c":1}""") { "signature" }

            val payloadSegment = jwt.split(".")[1]
            assertEquals("""{"a":"b","c":1}""", Base64Url.decode(payloadSegment).toString(Charsets.UTF_8))
        }

    @Test
    fun `the sign callback receives exactly header dot payload`() =
        runTest {
            var received: String? = null

            val jwt =
                buildSignedJwt("key-1", "ES256", """{"a":"b"}""") { signingInput ->
                    received = signingInput
                    "signature"
                }

            assertEquals(jwt.substringBeforeLast("."), received)
        }

    @Test
    fun `the final segment is exactly what sign returned, unmodified`() =
        runTest {
            val jwt = buildSignedJwt("key-1", "ES256", """{"a":"b"}""") { "not-even-base64url-shaped!!" }

            assertEquals("not-even-base64url-shaped!!", jwt.substringAfterLast("."))
        }

    @Test
    fun `a jwt signed with a real EC key verifies and carries the header and payload`() =
        runTest {
            val keyPair = ecKeyPair()
            val payload = """{"a":"b","c":1}"""

            val jwt = buildSignedJwt("key-1", "ES256", payload) { signWith(keyPair, it) }

            val parsed = JWSObject.parse(jwt)
            assertTrue(parsed.verify(ECDSAVerifier(keyPair.public as ECPublicKey)))
            assertEquals(JWSAlgorithm.ES256, parsed.header.algorithm)
            assertEquals("key-1", parsed.header.keyID)
            assertEquals(JOSEObjectType.JWT, parsed.header.type)
            assertEquals(payload, parsed.payload.toString())
        }

    @Test
    fun `a jwt whose payload was changed after signing does not verify`() =
        runTest {
            val keyPair = ecKeyPair()
            val jwt = buildSignedJwt("key-1", "ES256", """{"a":"b"}""") { signWith(keyPair, it) }
            val (header, _, signature) = jwt.split(".")
            val tampered = "$header.${Base64Url.encode("""{"a":"evil"}""")}.$signature"

            assertFalse(JWSObject.parse(tampered).verify(ECDSAVerifier(keyPair.public as ECPublicKey)))
        }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun signWith(
        keyPair: KeyPair,
        signingInput: String,
    ): String =
        ECDSASigner(keyPair.private as ECPrivateKey)
            .sign(JWSHeader(JWSAlgorithm.ES256), signingInput.toByteArray(Charsets.UTF_8))
            .toString()

    private fun decodeSegment(
        jwt: String,
        index: Int,
    ): JsonObject =
        Json.parseToJsonElement(Base64Url.decode(jwt.split(".")[index]).toString(Charsets.UTF_8)) as JsonObject
}
