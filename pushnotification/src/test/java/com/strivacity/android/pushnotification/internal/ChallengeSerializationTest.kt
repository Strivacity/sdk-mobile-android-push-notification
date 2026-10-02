package com.strivacity.android.pushnotification.internal

import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeContext
import com.strivacity.android.pushnotification.model.ChallengeException
import com.strivacity.android.pushnotification.model.ErrorCodes
import com.strivacity.android.pushnotification.model.InvalidChallengeOptionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises [serializeChallenge] and [deserializeChallenge]. */
@OptIn(PluginBridgeApi::class)
class ChallengeSerializationTest {
    private val context =
        ChallengeContext(
            challengeId = "challenge-1",
            challengeValue = "challenge-value",
            issuer = "https://issuer.example.com",
            accountId = "account-1",
            expiresAt = "2026-10-01T14:30:00Z",
            metadata = mapOf("place" to "New York", "device" to null),
        )

    private val options = listOf("12", "26", "84")

    @Test
    fun `a simple challenge round-trips to a simple challenge`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.Simple(context)))

        assertTrue(restored is Challenge.Simple)
    }

    @Test
    fun `a context-matching challenge round-trips to a context-matching challenge with its options`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.ContextMatching(context, options)))

        assertEquals(options, (restored as Challenge.ContextMatching).options)
    }

    @Test
    fun `a restored challenge keeps its public fields`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.Simple(context)))

        assertEquals(
            listOf(context.challengeId, context.issuer, context.accountId, context.expiresAt, context.metadata),
            listOf(restored.challengeId, restored.issuer, restored.accountId, restored.expiresAt, restored.metadata),
        )
    }

    @Test
    fun `a restored challenge keeps null metadata values`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.Simple(context)))

        assertTrue(restored.metadata.containsKey("device") && restored.metadata["device"] == null)
    }

    @Test
    fun `a restored challenge resolves with the original challenge value`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.Simple(context)))

        assertEquals(context.challengeValue, (restored as Challenge.Simple).approve().challengeValue)
    }

    @Test
    fun `a restored context-matching challenge still rejects an option that was not offered`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.ContextMatching(context, options)))

        assertThrows(InvalidChallengeOptionException::class.java) {
            (restored as Challenge.ContextMatching).select("99")
        }
    }

    @Test
    fun `text that is not a snapshot throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge("not json") }
    }

    @Test
    fun `an unreadable snapshot fails with the challenge unavailable code`() {
        val failure = assertThrows(ChallengeException::class.java) { deserializeChallenge("not json") }

        assertEquals(ErrorCodes.CHALLENGE_UNAVAILABLE, failure.code)
    }

    @Test
    fun `an unsupported snapshot version throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("version" to "2")) }
    }

    @Test
    fun `an unknown challenge type throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("type" to text("other"))) }
    }

    @Test
    fun `a blank challenge id throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("challengeId" to text(" "))) }
    }

    @Test
    fun `a blank challenge value throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("challengeValue" to text(""))) }
    }

    @Test
    fun `a blank issuer throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("issuer" to text(""))) }
    }

    @Test
    fun `a blank account id throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("accountId" to text(""))) }
    }

    @Test
    fun `a blank expiry throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("expiresAt" to text(""))) }
    }

    @Test
    fun `a simple snapshot that carries options throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) { deserializeChallenge(snapshot("options" to """["1"]""")) }
    }

    @Test
    fun `a context-matching snapshot without options throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) {
            deserializeChallenge(snapshot("type" to text("contextMatching")))
        }
    }

    @Test
    fun `a context-matching snapshot with empty options throws ChallengeException`() {
        assertThrows(ChallengeException::class.java) {
            deserializeChallenge(snapshot("type" to text("contextMatching"), "options" to "[]"))
        }
    }

    @Test
    fun `an unreadable snapshot's exception message does not contain the snapshot text`() {
        val serialized = snapshot("version" to "2")

        val failure = assertThrows(ChallengeException::class.java) { deserializeChallenge(serialized) }

        assertFalse(failure.message.orEmpty().contains("challenge-value"))
    }

    @Test
    fun `a restored challenge's toString does not contain the challenge value`() {
        val restored = deserializeChallenge(serializeChallenge(Challenge.Simple(context)))

        assertFalse(restored.toString().contains(context.challengeValue))
    }

    @Test
    fun `the shared snapshot fixture decodes to the expected context-matching challenge`() {
        val restored = deserializeChallenge(SHARED_FIXTURE) as Challenge.ContextMatching

        assertEquals(
            listOf("challenge-1", "https://issuer.example.com", "account-1", "2026-10-01T14:30:00Z"),
            listOf(restored.challengeId, restored.issuer, restored.accountId, restored.expiresAt),
        )
    }

    @Test
    fun `the shared snapshot fixture keeps its options and metadata`() {
        val restored = deserializeChallenge(SHARED_FIXTURE) as Challenge.ContextMatching

        assertEquals(
            Pair(options, mapOf("place" to "New York", "device" to null)),
            Pair(restored.options, restored.metadata),
        )
    }

    @Test
    fun `the shared snapshot fixture keeps its challenge value`() {
        val restored = deserializeChallenge(SHARED_FIXTURE) as Challenge.ContextMatching

        assertEquals("challenge-value", restored.select("12").challengeValue)
    }

    /** A simple-challenge snapshot; each override replaces a field's raw JSON value. */
    private fun snapshot(vararg overrides: Pair<String, String>): String {
        val fields =
            linkedMapOf(
                "version" to "1",
                "type" to text("simple"),
                "challengeId" to text("challenge-1"),
                "challengeValue" to text("challenge-value"),
                "issuer" to text("https://issuer.example.com"),
                "accountId" to text("account-1"),
                "expiresAt" to text("2026-10-01T14:30:00Z"),
                "metadata" to "{}",
            )
        fields.putAll(overrides)

        return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "\"$key\":$value"
        }
    }

    private fun text(value: String): String = "\"$value\""

    private companion object {
        /** A context-matching snapshot; the iOS SDK's tests decode the same text. */
        const val SHARED_FIXTURE =
            """{"version":1,"type":"contextMatching","challengeId":"challenge-1",""" +
                """"challengeValue":"challenge-value","issuer":"https://issuer.example.com",""" +
                """"accountId":"account-1","expiresAt":"2026-10-01T14:30:00Z",""" +
                """"metadata":{"place":"New York","device":null},"options":["12","26","84"]}"""
    }
}
