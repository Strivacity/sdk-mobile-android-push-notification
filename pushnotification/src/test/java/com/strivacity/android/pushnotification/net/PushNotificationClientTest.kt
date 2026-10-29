package com.strivacity.android.pushnotification.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises paths not covered by higher-level tests:
 * - the constructor's default path (a real OkHttp engine).
 */
class PushNotificationClientTest {
    @Test
    fun `constructing with no engine succeeds`() {
        // No network call is made; constructing the client doesn't make one.
        PushNotificationClient()
    }

    // --- challengeId can't restructure the request ---

    @Test
    fun `a challengeId containing path and query syntax stays one opaque path segment`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val client =
                PushNotificationClient(
                    MockEngine { request ->
                        capturedRequest = request
                        respond(
                            """{"expireAt":"2026-10-01T14:30:00Z","metadata":{},"action":{"type":"simple"}}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val maliciousChallengeId = "../other-issuer/../../evil?injected=1#frag"

            // Only the outgoing request matters here, not the parsed response.
            runCatching { client.challenge("https://issuer.example.com", maliciousChallengeId) }

            val url = requireNotNull(capturedRequest).url
            assertEquals("issuer.example.com", url.host)
            assertEquals(
                "/pushNotification/api/v1/actions/..%2Fother-issuer%2F..%2F..%2Fevil%3Finjected=1%23frag",
                url.encodedPath,
            )
            assertTrue("no query parameter should have been injected", url.parameters.isEmpty())
            assertNull("no fragment should have been injected", url.fragment.ifEmpty { null })
        }
}
