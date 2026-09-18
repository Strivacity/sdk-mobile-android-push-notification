package com.strivacity.android.pushnotification.support

import com.strivacity.android.pushnotification.PushNotification
import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.net.PushNotificationClient
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Builds a [PushNotification] wired to the given storage, with a Ktor [MockEngine] standing in
 * for the backend.
 *
 * Takes the [PushNotificationStorage] interface, not the concrete fake, so a test can pass a
 * small delegating wrapper around a fake to make one specific operation throw or return bad data.
 */
internal fun createTestPushNotificationSdk(
    storage: PushNotificationStorage = FakePushNotificationStorage(),
    handleRequest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): PushNotification = PushNotification(storage, PushNotificationClient(MockEngine(handleRequest)))

/** A generic 2xx response, for a test that doesn't care about the response body. */
internal fun MockRequestHandleScope.respondSuccess(): HttpResponseData = respond("", HttpStatusCode.OK)

/** A generic non-2xx response, for a test exercising the backend-refusal path. */
internal fun MockRequestHandleScope.respondFailure(
    status: HttpStatusCode = HttpStatusCode.BadRequest,
): HttpResponseData = respond("", status, headersOf(HttpHeaders.ContentType, "application/json"))

/** For a test where the call under test should never reach the backend at all. */
internal fun MockRequestHandleScope.failIfCalled(): HttpResponseData = error("no backend call was expected")

/** A 200 response carrying a JSON body, for a test asserting on the deserialized result. */
internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
