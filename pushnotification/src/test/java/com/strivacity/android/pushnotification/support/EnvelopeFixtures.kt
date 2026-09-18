package com.strivacity.android.pushnotification.support

/** The push envelope's key in a platform message payload — mirrors `PushEnvelope.KEY`. */
internal const val ENVELOPE_KEY = "com.strivacity"

/** A syntactically valid push envelope payload, as `canHandleMessage`/`handleMessage` expect it. */
internal fun envelopeMessageData(
    issuer: String,
    accountId: String,
    challengeId: String = "challenge-1",
    challenge: String = "challenge-value",
    version: String = "1",
): Map<String, String> =
    mapOf(
        ENVELOPE_KEY to
            """{"version":"$version","challengeId":"$challengeId","challenge":"$challenge",""" +
            """"accountId":"$accountId","issuer":"$issuer"}""",
    )

/**
 * A syntactically valid, non-empty `ChallengeResponse.metadata` body — the backend contract
 * guarantees metadata is never empty; a real response always has at least this much.
 */
private const val REALISTIC_METADATA_JSON = """{"loginTimestamp":"1700000000"}"""

/** A `ChallengeResponse` body for an approve/deny challenge — mirrors the backend's wire shape. */
internal fun approveDenyResponseBody(
    expireAt: String = FAR_FUTURE_EXPIRES_AT,
    metadataJson: String = REALISTIC_METADATA_JSON,
): String = """{"expireAt":"$expireAt","metadata":$metadataJson,"action":{"type":"simple"}}"""

/** A `ChallengeResponse` body for a context-matching challenge, with the given options. */
internal fun contextMatchingResponseBody(
    options: List<String>,
    expireAt: String = FAR_FUTURE_EXPIRES_AT,
): String {
    val optionsJson = options.joinToString(",") { "\"$it\"" }
    return """{"expireAt":"$expireAt","metadata":$REALISTIC_METADATA_JSON,""" +
        """"action":{"type":"contextMatching","options":[$optionsJson]}}"""
}

/** A `ChallengeResponse` body naming an action type this SDK version doesn't recognize. */
internal fun unsupportedChallengeResponseBody(expireAt: String = FAR_FUTURE_EXPIRES_AT): String =
    """{"expireAt":"$expireAt","metadata":$REALISTIC_METADATA_JSON,"action":{"type":"aFutureChallengeType"}}"""

private const val FAR_FUTURE_EXPIRES_AT = "9999-12-31T23:59:59Z"
