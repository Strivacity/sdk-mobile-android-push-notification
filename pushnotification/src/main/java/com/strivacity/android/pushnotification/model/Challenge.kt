package com.strivacity.android.pushnotification.model

/** Identifies one challenge. Safe to log. */
public typealias ChallengeId = String

/**
 * A single-use value that must be presented, signed, when the challenge is resolved.
 *
 * Not exposed to the host app; absent from `toString` and error messages.
 */
internal typealias ChallengeValue = String

/** Fields shared by every [Challenge] variant. */
internal data class ChallengeContext(
    val challengeId: ChallengeId,
    val challengeValue: ChallengeValue,
    val issuer: String,
    val accountId: String,
    val expiresAt: String,
    val metadata: Map<String, String?>,
)

/**
 * An MFA request waiting on a decision.
 *
 * Subclasses cannot be constructed outside the SDK. A [ChallengeResolution] can only be obtained
 * from one of this class's methods.
 */
public sealed class Challenge private constructor(
    internal val context: ChallengeContext,
) {
    public val challengeId: ChallengeId get() = context.challengeId

    /** Which backend this challenge came from. A device may hold enrollments against several. */
    public val issuer: String get() = context.issuer

    public val accountId: String get() = context.accountId

    /**
     * When this challenge expires: an ISO 8601 / RFC 3339 instant string (e.g.
     * `2026-10-01T14:30:00Z`). The SDK enforces nothing; the backend does.
     */
    public val expiresAt: String get() = context.expiresAt

    /**
     * Backend-supplied context for the decision (e.g. device, location, time).
     *
     * Keys are not guaranteed and values may be null; ignore entries this version does not
     * recognise.
     */
    public val metadata: Map<String, String?> get() = context.metadata

    /** The user refused. A recorded refusal is a successful resolution, not an error. */
    public fun deny(): ChallengeResolution = resolution(ACTION_DENY)

    /** Omits the challenge value and [metadata]. */
    override fun toString(): String =
        "${this::class.simpleName}(challengeId=$challengeId, issuer=$issuer, " +
            "accountId=$accountId, expiresAt=$expiresAt)"

    internal fun resolution(action: String): ChallengeResolution =
        ChallengeResolution(
            challengeId = context.challengeId,
            challengeValue = context.challengeValue,
            issuer = context.issuer,
            accountId = context.accountId,
            action = action,
        )

    /** "Allow this sign-in?" — nothing to choose between. */
    public class Simple internal constructor(
        context: ChallengeContext,
    ) : Challenge(context) {
        public fun approve(): ChallengeResolution = resolution(ACTION_APPROVE)
    }

    /**
     * The user picks one of [options], shown to them alongside the same value on the device
     * signing in. The backend judges the answer.
     */
    public class ContextMatching internal constructor(
        context: ChallengeContext,
        public val options: List<String>,
    ) : Challenge(context) {
        /** @throws InvalidChallengeOptionException if [option] is not one of [options]. */
        public fun select(option: String): ChallengeResolution {
            if (option !in options) {
                throw InvalidChallengeOptionException(challengeId)
            }
            return resolution(option)
        }
    }

    internal companion object {
        const val ACTION_APPROVE: String = "approve"
        const val ACTION_DENY: String = "deny"
    }
}

/** A user's answer, ready to submit. Obtained from a [Challenge] and opaque to the app. */
public class ChallengeResolution internal constructor(
    internal val challengeId: ChallengeId,
    internal val challengeValue: ChallengeValue,
    internal val issuer: String,
    internal val accountId: String,
    /** An offered option, or a refusal. */
    internal val action: String,
) {
    override fun toString(): String = "ChallengeResolution(challengeId=$challengeId, action=$action)"
}

/** The outcome of a resolution. Detail beyond this lives in the backend's own logs. */
public enum class ResolutionResult {
    OK,
    NOT_OK,
}
