package com.strivacity.android.pushnotification.net

import com.strivacity.android.pushnotification.envelope.ChallengeEnvelope
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeContext
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.UnsupportedChallengeTypeException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps backend responses to domain types.
 *
 * [toDomain] combines the fetched [ChallengeResponse] with the push [ChallengeEnvelope]: the
 * response describes what is being asked; the envelope supplies the challenge id and value.
 */
internal fun ChallengeResponse.toDomain(
    envelope: ChallengeEnvelope,
    normalizedIssuer: String,
): Challenge {
    checkMetadataNotEmpty()

    val context =
        ChallengeContext(
            challengeId = envelope.challengeId,
            challengeValue = envelope.challenge,
            issuer = normalizedIssuer,
            accountId = envelope.accountId,
            expiresAt = expireAt,
            metadata = metadata.toDomain(),
        )

    return when (action.type) {
        ActionTypes.APPROVE_DENY -> {
            Challenge.Simple(context)
        }

        ActionTypes.CONTEXT_MATCHING -> {
            // The backend contract guarantees at least one option for a context-matching
            // challenge — a `select` that could never succeed is a contract violation, not a
            // shape the SDK should hand the App.
            if (action.options.isEmpty()) {
                throw ChallengeException(
                    "Context-matching challenge arrived with no options, contrary to the backend contract",
                )
            }
            Challenge.ContextMatching(context, action.options)
        }

        else -> {
            throw UnsupportedChallengeTypeException(action.type)
        }
    }
}

/** The backend contract guarantees metadata is never empty — a timestamp is always present at minimum. */
private fun ChallengeResponse.checkMetadataNotEmpty() {
    if (metadata.isEmpty()) {
        throw ChallengeException("Challenge metadata was empty, contrary to the backend contract")
    }
}

/**
 * The backend contract guarantees every metadata value is a string or null. A value of any other
 * type breaks that contract and is left out, so the rest of the challenge still reaches the App.
 */
private fun Map<String, JsonElement>.toDomain(): Map<String, String?> =
    buildMap {
        for ((key, value) in this@toDomain) {
            when {
                value is JsonNull -> put(key, null)
                value is JsonPrimitive && value.isString -> put(key, value.content)
            }
        }
    }
