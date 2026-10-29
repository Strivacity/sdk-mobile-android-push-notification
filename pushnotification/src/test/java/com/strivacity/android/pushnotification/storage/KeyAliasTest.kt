package com.strivacity.android.pushnotification.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [KeyAlias] directly — pure JVM logic, currently exercised only indirectly through
 * `DefaultPushNotificationStorage`. None of this needs a device.
 */
class KeyAliasTest {
    @Test
    fun `prefixFor is stable across calls for the same issuer and account`() {
        val first = KeyAlias.prefixFor("https://issuer.example.com", "account-1")
        val second = KeyAlias.prefixFor("https://issuer.example.com", "account-1")

        assertEquals(first, second)
    }

    @Test
    fun `prefixFor changes when the issuer or the account changes`() {
        val base = KeyAlias.prefixFor("https://issuer.example.com", "account-1")

        assertNotEquals(base, KeyAlias.prefixFor("https://a-different-issuer.example.com", "account-1"))
        assertNotEquals(base, KeyAlias.prefixFor("https://issuer.example.com", "a-different-account"))
    }

    @Test
    fun `prefixFor distinguishes issuer and account rather than just concatenating them`() {
        // Without a delimiter between the two, ("ab", "c") and ("a", "bc") would hash identically.
        val first = KeyAlias.prefixFor("ab", "c")
        val second = KeyAlias.prefixFor("a", "bc")

        assertNotEquals(first, second)
    }

    @Test
    fun `newKeyId never contains the separator character`() {
        repeat(1_000) {
            assertFalse(KeyAlias.newKeyId().contains("."))
        }
    }

    @Test
    fun `keyIdOf of round-trips the key id`() {
        val alias = KeyAlias.of("https://issuer.example.com", "account-1", keyId = "key-1")

        assertEquals("key-1", KeyAlias.keyIdOf(alias))
    }

    @Test
    fun `isOurs accepts an alias built by of`() {
        val alias = KeyAlias.of("https://issuer.example.com", "account-1", keyId = "key-1")

        assertTrue(KeyAlias.isOurs(alias))
    }

    @Test
    fun `isOurs rejects an alias belonging to the host app`() {
        assertFalse(KeyAlias.isOurs("some_app_owned_alias"))
    }
}
