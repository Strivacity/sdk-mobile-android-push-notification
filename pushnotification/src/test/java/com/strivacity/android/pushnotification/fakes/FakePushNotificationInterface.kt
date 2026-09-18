package com.strivacity.android.pushnotification.fakes

import com.strivacity.android.pushnotification.PushNotificationInterface
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeResolution
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.ResolutionResult

/**
 * A [PushNotificationInterface] fake for testing `PushNotificationCompat`'s `*Async` proxying —
 * each method's behavior is a settable lambda, defaulting to failing loudly if a test invokes a
 * method it never configured. A test configures only the one method it's exercising; the closure
 * itself captures whatever arguments the call was made with, so no separate call-recording is
 * needed.
 */
@Suppress("LongParameterList")
internal class FakePushNotificationInterface(
    var enrollDevice: suspend (
        EnrollmentGrant,
        String,
        DeviceTokenType,
        String,
    ) -> Enrollment = { _, _, _, _ -> unconfigured() },
    var unenrollDevice: suspend (String, String) -> Unit = { _, _ -> unconfigured() },
    var hasEnrollment: suspend (String, String) -> Boolean = { _, _ -> unconfigured() },
    var listEnrollments: suspend () -> List<Enrollment> = { unconfigured() },
    var updateDeviceToken: suspend (String, String, String, DeviceTokenType) -> Unit = { _, _, _, _ -> unconfigured() },
    var canHandleMessage: suspend (Map<String, String>?) -> Boolean = { unconfigured() },
    var handleMessage: suspend (Map<String, String>?) -> Challenge = { unconfigured() },
    var resolveChallenge: suspend (ChallengeResolution) -> ResolutionResult = { unconfigured() },
) : PushNotificationInterface {
    override suspend fun enrollDevice(
        grant: EnrollmentGrant,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
        deviceName: String,
    ): Enrollment = enrollDevice.invoke(grant, deviceToken, deviceTokenType, deviceName)

    override suspend fun unenrollDevice(
        issuer: String,
        accountId: String,
    ) = unenrollDevice.invoke(issuer, accountId)

    override suspend fun hasEnrollment(
        issuer: String,
        accountId: String,
    ): Boolean = hasEnrollment.invoke(issuer, accountId)

    override suspend fun listEnrollments(): List<Enrollment> = listEnrollments.invoke()

    override suspend fun updateDeviceToken(
        issuer: String,
        accountId: String,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
    ) = updateDeviceToken.invoke(issuer, accountId, deviceToken, deviceTokenType)

    override suspend fun canHandleMessage(messageData: Map<String, String>?): Boolean =
        canHandleMessage.invoke(messageData)

    override suspend fun handleMessage(messageData: Map<String, String>?): Challenge = handleMessage.invoke(messageData)

    override suspend fun resolveChallenge(resolution: ChallengeResolution): ResolutionResult =
        resolveChallenge.invoke(resolution)
}

private fun unconfigured(): Nothing =
    throw UnsupportedOperationException("This fake method wasn't configured for this test")
