package com.strivacity.android.pushnotification.net

import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Jwk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// These mirror the backend's request and response shapes and are kept separate from the domain
// model. Resolution, un-enrollment, and token-update requests are a raw signed JWT string, sent
// as `application/jwt`, so they need no wire type here.
//
// Naming: a request body is `...Request`, a response body `...Response`; an embedded value
// carries no suffix.

/** Builds the wire JWK object from [Jwk.members], [Jwk.alg], and [Jwk.kid]. */
internal fun Jwk.toJsonObject(): JsonObject =
    buildJsonObject {
        members.forEach { (name, value) -> put(name, value) }
        put("alg", alg)
        put("kid", kid)
    }

/**
 * The one-time token travels as an `Authorization: Bearer <token>` header, not in this body.
 * No `accountId` either — the one-time token uniquely identifies the account backend-side.
 */
@Serializable
internal data class EnrollRequest(
    /** `android` */
    val platform: String,
    /** `fcmToken`, or `fcmFid`. */
    val deviceTokenType: String,
    val deviceToken: String,
    val deviceName: String? = null,
    /** Public key as a JWK object. */
    val publicKey: JsonObject,
)

/** A challenge's detail. */
@Serializable
internal data class ChallengeResponse(
    /** ISO 8601 / RFC 3339 instant string (e.g. `2026-10-01T14:30:00Z`). */
    val expireAt: String,
    /** Backend-supplied context. Values are mixed JSON types; flattened to strings during mapping. */
    val metadata: Map<String, JsonElement> = emptyMap(),
    val action: Action,
)

/**
 * [type] selects the challenge variant. [options] lists acceptable answers for context matching;
 * empty for approve/deny.
 */
@Serializable
internal data class Action(
    val type: String,
    val options: List<String> = emptyList(),
)

/** Action type identifiers, fixed by the backend contract. */
internal object ActionTypes {
    const val APPROVE_DENY = "simple"
    const val CONTEXT_MATCHING = "contextMatching"
}

internal object Platforms {
    const val ANDROID = "android"
}

/** Wire value for a [DeviceTokenType]. */
internal fun DeviceTokenType.toWireValue(): String =
    when (this) {
        DeviceTokenType.FCM_TOKEN -> "fcmToken"
        DeviceTokenType.FCM_FID -> "fcmFid"
    }
