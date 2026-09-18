package com.strivacity.android.pushnotification.envelope

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The push payload's envelope.
 *
 * [challengeId] identifies the challenge and is used to fetch its detail. [challenge] is a
 * single-use value that must accompany [challengeId] when the challenge is resolved; it does not
 * appear in the fetched detail.
 */
@Serializable
internal data class ChallengeEnvelope(
    val version: String,
    val challengeId: String,
    val challenge: String,
    val accountId: String,
    val issuer: String,
)

/**
 * Reads the envelope out of a platform message payload.
 *
 * Returns `null` if no envelope is present, it cannot be parsed, or its version is not
 * [SUPPORTED_VERSION].
 */
internal object PushEnvelope {
    /** The envelope's key in the message payload. */
    const val KEY = "com.strivacity"

    /** The only envelope version this SDK implements. */
    const val SUPPORTED_VERSION = "1"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(messageData: Map<String, String>?): ChallengeEnvelope? {
        val raw = messageData?.get(KEY) ?: return null

        return runCatching { json.decodeFromString<ChallengeEnvelope>(raw) }
            .getOrNull()
            ?.takeIf { it.isUsable() }
    }

    private fun ChallengeEnvelope.isUsable(): Boolean =
        version == SUPPORTED_VERSION &&
            challengeId.isNotBlank() &&
            challenge.isNotBlank() &&
            accountId.isNotBlank() &&
            issuer.isNotBlank()
}
