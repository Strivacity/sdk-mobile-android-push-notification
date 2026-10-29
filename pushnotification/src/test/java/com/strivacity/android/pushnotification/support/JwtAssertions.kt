package com.strivacity.android.pushnotification.support

import com.strivacity.android.pushnotification.util.Base64Url
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The raw text of this request's body — works for a plain `setBody(String)` or a serialized JSON body alike. */
internal fun HttpRequestData.bodyText(): String = (body as TextContent).text

/** The raw JWT string sent as this request's body — the SDK always sends a signed JWT as-is. */
internal fun HttpRequestData.jwtBody(): String = bodyText()

/** The JWT's header segment, decoded and parsed as JSON — e.g. to check `kid`/`alg`. */
internal fun HttpRequestData.jwtHeaderClaims(): JsonObject = jwtSegment(0)

/** The JWT's payload segment, decoded and parsed as JSON — the signed claims. */
internal fun HttpRequestData.jwtPayloadClaims(): JsonObject = jwtSegment(1)

private fun HttpRequestData.jwtSegment(index: Int): JsonObject {
    val segment = jwtBody().split(".")[index]
    return Json.parseToJsonElement(Base64Url.decode(segment).toString(Charsets.UTF_8)) as JsonObject
}
