package com.strivacity.android.pushnotification.net

import com.strivacity.android.pushnotification.Logging
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.EnrollmentException
import com.strivacity.android.pushnotification.model.NetworkException
import com.strivacity.android.pushnotification.model.UnenrollmentException
import com.strivacity.android.pushnotification.model.UpdateTokenException
import com.strivacity.android.pushnotification.util.logLabel
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Coordinates network communication with the backend.
 *
 * Paths are rooted at the issuer, under `/pushNotification/api/v1`. Resolution, un-enrollment,
 * and token updates send a signed JWT as the entire request body, `Content-Type: application/jwt`.
 *
 * Every call is attempted once. A failure — transport or backend — is surfaced immediately as
 * that call's exception; retrying, if wanted, is the caller's decision.
 *
 * Request and response bodies are not logged.
 */
internal class PushNotificationClient(
    engine: HttpClientEngine? = null,
    private val logging: Logging? = null,
) {
    private val client: HttpClient =
        if (engine == null) {
            HttpClient(CIO) { configure() }
        } else {
            HttpClient(engine) { configure() }
        }

    suspend fun enroll(
        normalizedIssuer: String,
        oneTimeToken: String,
        request: EnrollRequest,
    ) {
        val response =
            execute("enrollment") {
                client.post("${normalizedIssuer.pushNotificationApi()}/enroll") {
                    header(HttpHeaders.Authorization, "Bearer $oneTimeToken")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }

        if (!response.status.isSuccess()) {
            logging?.warn("Enrollment refused by the backend (status ${response.status.value})")
            throw EnrollmentException("Enrollment was refused by the backend", response.status.value)
        }
    }

    suspend fun unenroll(
        normalizedIssuer: String,
        unenrollJwt: String,
    ) {
        val response =
            execute("un-enrollment") {
                client.post("${normalizedIssuer.pushNotificationApi()}/unenroll") {
                    contentType(JWT_CONTENT_TYPE)
                    setBody(unenrollJwt)
                }
            }

        if (!response.status.isSuccess()) {
            logging?.warn("Un-enrollment refused by the backend (status ${response.status.value})")
            throw UnenrollmentException("Un-enrollment was refused by the backend", response.status.value)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun challenge(
        normalizedIssuer: String,
        challengeId: String,
    ): ChallengeResponse {
        // challengeId is opaque to the SDK — backend-generated, never validated.
        // appendPathSegments treats it as one percent-encoded path segment, so a value containing
        // '/', '..', '?', or '#' can't restructure the request the way string interpolation would.
        val response =
            execute("challenge retrieval") {
                client.get(normalizedIssuer.pushNotificationApi()) {
                    url { appendPathSegments("actions", challengeId, encodeSlash = true) }
                }
            }

        if (!response.status.isSuccess()) {
            logging?.warn("Challenge '$challengeId' retrieval refused by the backend (status ${response.status.value})")
            throw ChallengeException("Challenge '$challengeId' could not be retrieved", response.status.value)
        }

        return try {
            response.body<ChallengeResponse>()
        } catch (cancellation: CancellationException) {
            // Rethrown as-is, not wrapped: a CancellationException must stay recognizable as one
            // to the coroutine machinery above this call, or cancellation stops propagating.
            throw cancellation
        } catch (failure: Exception) {
            logging?.error("Challenge '$challengeId' response could not be read (${logLabel(failure)})")
            throw ChallengeException(
                "Challenge '$challengeId' was returned in a shape this SDK cannot read",
                response.status.value,
                failure,
            )
        }
    }

    /**
     * @return whether the backend accepted the resolution. A recorded denial is accepted.
     */
    suspend fun resolve(
        normalizedIssuer: String,
        challengeId: String,
        resolutionJwt: String,
    ): Boolean {
        // See challenge()'s comment: appendPathSegments encodes challengeId as one opaque segment.
        val response =
            execute("resolution") {
                client.post(normalizedIssuer.pushNotificationApi()) {
                    url { appendPathSegments("actions", challengeId, "submit", encodeSlash = true) }
                    contentType(JWT_CONTENT_TYPE)
                    setBody(resolutionJwt)
                }
            }

        return when {
            response.status.isSuccess() -> {
                true
            }

            response.status.value in REJECTION_STATUSES -> {
                logging?.debug(
                    "Challenge '$challengeId' resolution rejected by the backend (status ${response.status.value})",
                )
                false
            }

            else -> {
                logging?.warn(
                    "Challenge '$challengeId' resolution failed at the backend (status ${response.status.value})",
                )
                throw ChallengeException(
                    "Resolution of challenge '$challengeId' failed",
                    response.status.value,
                )
            }
        }
    }

    suspend fun updateToken(
        normalizedIssuer: String,
        updateTokenJwt: String,
    ) {
        val response =
            execute("token update") {
                client.post("${normalizedIssuer.pushNotificationApi()}/updateToken") {
                    contentType(JWT_CONTENT_TYPE)
                    setBody(updateTokenJwt)
                }
            }

        if (!response.status.isSuccess()) {
            logging?.warn("Token update refused by the backend (status ${response.status.value})")
            throw UpdateTokenException("Token update was refused by the backend", response.status.value)
        }
    }

    private suspend fun execute(
        operation: String,
        block: suspend () -> HttpResponse,
    ): HttpResponse =
        try {
            block()
        } catch (failure: IOException) {
            logging?.error("The backend could not be reached during $operation (${logLabel(failure)})")
            throw NetworkException("The backend could not be reached during $operation", failure)
        }

    /** The issuer's base URL with the fixed API path rooted onto it. */
    private fun String.pushNotificationApi(): String = "$this/pushNotification/api/v1"

    private companion object {
        /** A recorded denial, not an error — see [resolve]'s doc comment. */
        val REJECTION_STATUSES = setOf(400, 401, 404)
        val JWT_CONTENT_TYPE = ContentType("application", "jwt")
    }
}

private fun HttpClientConfig<*>.configure() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
    expectSuccess = false
}

private const val REQUEST_TIMEOUT_MILLIS = 30_000L
private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val SOCKET_TIMEOUT_MILLIS = 30_000L
