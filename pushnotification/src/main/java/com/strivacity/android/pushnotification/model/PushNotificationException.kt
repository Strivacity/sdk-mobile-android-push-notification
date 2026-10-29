package com.strivacity.android.pushnotification.model

/** The stable, greppable identifiers of the failures this SDK reports, as [PushNotificationException.code]. */
public object ErrorCodes {
    public const val INVALID_GRANT: String = "invalid_grant"
    public const val INVALID_ISSUER: String = "invalid_issuer"
    public const val INVALID_DEVICE_NAME: String = "invalid_device_name"
    public const val ENROLLMENT_REJECTED: String = "enrollment_rejected"
    public const val ALREADY_ENROLLED: String = "already_enrolled"
    public const val UNENROLLMENT_REJECTED: String = "unenrollment_rejected"
    public const val TOKEN_UPDATE_REJECTED: String = "token_update_rejected"
    public const val NOT_ENROLLED: String = "not_enrolled"
    public const val CHALLENGE_UNAVAILABLE: String = "challenge_unavailable"
    public const val UNSUPPORTED_CHALLENGE_TYPE: String = "unsupported_challenge_type"
    public const val UNSUPPORTED_ALGORITHM: String = "unsupported_algorithm"
    public const val STORAGE_FAILURE: String = "storage_failure"
    public const val SOFTWARE_BACKED_KEY: String = "software_backed_key"
    public const val NETWORK_FAILURE: String = "network_failure"
    public const val UNEXPECTED: String = "unexpected"
    public const val INVALID_CHALLENGE_OPTION: String = "invalid_challenge_option"
}

/**
 * Base of everything this SDK throws.
 *
 * Exception messages reach release-build error reporting. Do not include one-time tokens, JWTs,
 * key material, challenge metadata, or anything identifying a user. Issuer URLs, key ids,
 * challenge ids, and HTTP status codes are safe to include.
 */
public abstract class PushNotificationException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The issuer is not an absolute `https` URL. */
public class InvalidIssuerException(
    public val issuer: String,
) : PushNotificationException(ErrorCodes.INVALID_ISSUER, "Issuer '$issuer' is not an absolute https URL")

/**
 * The device name doesn't satisfy the SDK's constraints.
 *
 * Must be 1-64 characters after trimming, with no other restriction. The offending value is
 * deliberately not included — it may identify a user.
 */
public class InvalidDeviceNameException :
    PushNotificationException(
        ErrorCodes.INVALID_DEVICE_NAME,
        "Device name must be 1-64 characters",
    )

/** The enrollment grant could not be read: a parameter is missing, or the URL is malformed. */
public class InvalidGrantException(
    message: String,
) : PushNotificationException(ErrorCodes.INVALID_GRANT, message)

/** Enrollment was refused. Not retryable; obtain a fresh grant. */
public class EnrollmentException(
    message: String,
    public val status: Int? = null,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.ENROLLMENT_REJECTED, message, cause)

/** Un-enrollment was refused. The local enrollment is removed regardless. */
public class UnenrollmentException(
    message: String,
    public val status: Int? = null,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.UNENROLLMENT_REJECTED, message, cause)

/** The backend refused a device token update. */
public class UpdateTokenException(
    message: String,
    public val status: Int? = null,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.TOKEN_UPDATE_REJECTED, message, cause)

/**
 * This device already holds an enrollment for this issuer and account.
 *
 * Un-enroll first to replace it.
 */
public class AlreadyEnrolledException(
    public val issuer: String,
    public val accountId: String,
) : PushNotificationException(
        ErrorCodes.ALREADY_ENROLLED,
        "Account '$accountId' is already enrolled on this device for issuer '$issuer'",
    )

/** No enrollment is held for the account in question. */
public class NotEnrolledException(
    public val accountId: String,
) : PushNotificationException(ErrorCodes.NOT_ENROLLED, "No enrollment is held for account '$accountId'")

/**
 * No usable challenge: none in the payload, or the backend refused it, returned one this SDK
 * can't read, or failed its resolution.
 */
public class ChallengeException(
    message: String,
    public val status: Int? = null,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.CHALLENGE_UNAVAILABLE, message, cause)

/** The backend sent a challenge type not supported by this SDK version. */
public class UnsupportedChallengeTypeException(
    public val challengeType: String,
) : PushNotificationException(
        ErrorCodes.UNSUPPORTED_CHALLENGE_TYPE,
        "Backend offered challenge type '$challengeType', which this SDK version cannot present",
    )

/** A storage implementation produced a key whose algorithm is not on the accepted list. */
public class UnsupportedAlgorithmException(
    public val algorithm: String,
    public val accepted: Set<String>,
) : PushNotificationException(
        ErrorCodes.UNSUPPORTED_ALGORITHM,
        "Storage produced a '$algorithm' key; accepted algorithms are ${accepted.joinToString()}",
    )

/**
 * The key could only be created in software, and software-backed keys are not allowed.
 *
 * Pass `allowSoftwareBackedKeys = true` to accept one.
 */
public class SoftwareBackedKeyException :
    PushNotificationException(
        ErrorCodes.SOFTWARE_BACKED_KEY,
        "The key could only be created in software. Set allowSoftwareBackedKeys = true to accept that",
    )

/** Key generation, lookup, or signing failed, or an enrollment could not be recorded or removed. */
public class StorageException(
    message: String,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.STORAGE_FAILURE, message, cause)

/** The backend could not be reached, or the response was unreadable. */
public class NetworkException(
    message: String,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.NETWORK_FAILURE, message, cause)

/** An unanticipated failure. Produced only by the `*Async` callback layer. */
public class UnexpectedException(
    message: String,
    cause: Throwable? = null,
) : PushNotificationException(ErrorCodes.UNEXPECTED, message, cause)

/** `Challenge.ContextMatching.select` was called with an option not among those the challenge offered. */
public class InvalidChallengeOptionException(
    public val challengeId: String,
) : PushNotificationException(
        ErrorCodes.INVALID_CHALLENGE_OPTION,
        "The selected option was not among those offered for challenge '$challengeId'",
    )
