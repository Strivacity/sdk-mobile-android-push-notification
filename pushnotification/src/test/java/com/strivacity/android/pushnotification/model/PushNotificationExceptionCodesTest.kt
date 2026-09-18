package com.strivacity.android.pushnotification.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every typed exception carries the code from [ErrorCodes] its own constructor sets — a regression guard
 * against a copy-paste mistake wiring the wrong code to a new or existing exception type.
 */
class PushNotificationExceptionCodesTest {
    @Test
    fun `every exception type carries its documented error code`() {
        val casesToCodes =
            listOf(
                InvalidIssuerException("https://issuer.example.com") to ErrorCodes.INVALID_ISSUER,
                InvalidDeviceNameException() to ErrorCodes.INVALID_DEVICE_NAME,
                InvalidGrantException("message") to ErrorCodes.INVALID_GRANT,
                EnrollmentException("message") to ErrorCodes.ENROLLMENT_REJECTED,
                UnenrollmentException("message") to ErrorCodes.UNENROLLMENT_REJECTED,
                UpdateTokenException("message") to ErrorCodes.TOKEN_UPDATE_REJECTED,
                AlreadyEnrolledException("https://issuer.example.com", "account-1") to ErrorCodes.ALREADY_ENROLLED,
                NotEnrolledException("account-1") to ErrorCodes.NOT_ENROLLED,
                ChallengeException("message") to ErrorCodes.CHALLENGE_UNAVAILABLE,
                UnsupportedChallengeTypeException("aType") to ErrorCodes.UNSUPPORTED_CHALLENGE_TYPE,
                UnsupportedAlgorithmException("RS256", setOf("ES256")) to ErrorCodes.UNSUPPORTED_ALGORITHM,
                SoftwareBackedKeyException() to ErrorCodes.SOFTWARE_BACKED_KEY,
                StorageException("message") to ErrorCodes.STORAGE_FAILURE,
                NetworkException("message") to ErrorCodes.NETWORK_FAILURE,
                UnexpectedException("message") to ErrorCodes.UNEXPECTED,
                InvalidChallengeOptionException("challenge-1") to ErrorCodes.INVALID_CHALLENGE_OPTION,
            )

        for ((exception, expectedCode) in casesToCodes) {
            assertEquals("${exception::class.simpleName} should carry $expectedCode", expectedCode, exception.code)
        }
    }
}
