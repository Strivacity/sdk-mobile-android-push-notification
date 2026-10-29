package com.strivacity.android.pushnotification.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Exercises [Challenge]/[ChallengeResolution] directly — an isolated unit test rather than one
 * through [com.strivacity.android.pushnotification.PushNotificationInterface]:
 * both classes have public constructors and need no fakes/mocks to exercise meaningfully.
 */
class ChallengeTest {
    private val context =
        ChallengeContext(
            challengeId = "challenge-1",
            challengeValue = "challenge-value",
            issuer = "https://issuer.example.com",
            accountId = "account-1",
            expiresAt = "2026-10-01T14:30:00Z",
            metadata = mapOf("place" to "New York"),
        )

    @Test
    fun `deny carries the challenge's fields through to the resolution, with action deny`() {
        val resolution = Challenge.Simple(context).deny()

        assertEquals(context.challengeId, resolution.challengeId)
        assertEquals(context.challengeValue, resolution.challengeValue)
        assertEquals(context.issuer, resolution.issuer)
        assertEquals(context.accountId, resolution.accountId)
        assertEquals("deny", resolution.action)
    }

    @Test
    fun `approve carries the challenge's fields through to the resolution, with action approve`() {
        val resolution = Challenge.Simple(context).approve()

        assertEquals(context.challengeId, resolution.challengeId)
        assertEquals(context.challengeValue, resolution.challengeValue)
        assertEquals("approve", resolution.action)
    }

    @Test
    fun `select with an offered option carries the challenge's fields through, with the option as the action`() {
        val challenge = Challenge.ContextMatching(context, options = listOf("12", "26", "84"))

        val resolution = challenge.select("26")

        assertEquals(context.challengeId, resolution.challengeId)
        assertEquals(context.challengeValue, resolution.challengeValue)
        assertEquals("26", resolution.action)
    }

    @Test
    fun `select with an option that was not offered throws InvalidChallengeOptionException`() {
        val challenge = Challenge.ContextMatching(context, options = listOf("12", "26", "84"))

        val failure = assertThrows(InvalidChallengeOptionException::class.java) { challenge.select("99") }

        assertEquals(context.challengeId, failure.challengeId)
    }

    @Test
    fun `Challenge toString does not contain the challenge value or metadata`() {
        val text = Challenge.Simple(context).toString()

        assertFalse(text.contains(context.challengeValue))
        assertFalse(text.contains("New York"))
    }

    @Test
    fun `ChallengeResolution toString does not contain the challenge value`() {
        val text = Challenge.Simple(context).deny().toString()

        assertFalse(text.contains(context.challengeValue))
    }
}
