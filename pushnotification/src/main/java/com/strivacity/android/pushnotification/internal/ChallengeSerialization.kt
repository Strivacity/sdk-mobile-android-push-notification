package com.strivacity.android.pushnotification.internal

import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeContext
import com.strivacity.android.pushnotification.model.ChallengeException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encodes [challenge] as an opaque string that [deserializeChallenge] reads back.
 *
 * The string includes the challenge's single-use value. Treat it as a secret: do not log it.
 */
@PluginBridgeApi
public fun serializeChallenge(challenge: Challenge): String {
    val context = challenge.context
    val snapshot =
        ChallengeSnapshot(
            version = SNAPSHOT_VERSION,
            type = if (challenge is Challenge.ContextMatching) TYPE_CONTEXT_MATCHING else TYPE_SIMPLE,
            challengeId = context.challengeId,
            challengeValue = context.challengeValue,
            issuer = context.issuer,
            accountId = context.accountId,
            expiresAt = context.expiresAt,
            metadata = context.metadata,
            options = (challenge as? Challenge.ContextMatching)?.options,
        )

    return json.encodeToString(ChallengeSnapshot.serializer(), snapshot)
}

/**
 * Rebuilds a challenge from the output of [serializeChallenge].
 *
 * @throws ChallengeException if [serialized] is not a snapshot this SDK version can read.
 */
@PluginBridgeApi
public fun deserializeChallenge(serialized: String): Challenge =
    runCatching { json.decodeFromString(ChallengeSnapshot.serializer(), serialized) }
        .getOrNull()
        ?.toChallenge()
        ?: throw ChallengeException("The serialized challenge cannot be read")

private const val SNAPSHOT_VERSION = 1
private const val TYPE_SIMPLE = "simple"
private const val TYPE_CONTEXT_MATCHING = "contextMatching"

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class ChallengeSnapshot(
    val version: Int,
    val type: String,
    val challengeId: String,
    val challengeValue: String,
    val issuer: String,
    val accountId: String,
    val expiresAt: String,
    val metadata: Map<String, String?>,
    val options: List<String>? = null,
)

/** Returns `null` if the snapshot's version, fields or type/options combination is unusable. */
private fun ChallengeSnapshot.toChallenge(): Challenge? {
    val requiredFields = listOf(challengeId, challengeValue, issuer, accountId, expiresAt)
    if (version != SNAPSHOT_VERSION || requiredFields.any { it.isBlank() }) return null

    val context =
        ChallengeContext(
            challengeId = challengeId,
            challengeValue = challengeValue,
            issuer = issuer,
            accountId = accountId,
            expiresAt = expiresAt,
            metadata = metadata,
        )

    return when (type) {
        TYPE_SIMPLE -> if (options == null) Challenge.Simple(context) else null
        TYPE_CONTEXT_MATCHING -> options?.takeIf { it.isNotEmpty() }?.let { Challenge.ContextMatching(context, it) }
        else -> null
    }
}
