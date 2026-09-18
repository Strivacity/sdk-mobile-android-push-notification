package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.model.InvalidIssuerException
import com.strivacity.android.pushnotification.util.normalizeIssuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NormalizeIssuerTest {
    @Test
    fun `reduces an issuer to scheme and authority`() {
        assertEquals("https://acme.example.com", normalizeIssuer("https://acme.example.com"))
    }

    @Test
    fun `spellings that mean the same backend normalise alike`() {
        val expected = "https://acme.example.com"

        assertEquals(expected, normalizeIssuer("https://acme.example.com/"))
        assertEquals(expected, normalizeIssuer("  https://acme.example.com  "))
        assertEquals(expected, normalizeIssuer("HTTPS://ACME.Example.com"))
        assertEquals(expected, normalizeIssuer("https://acme.example.com/some/path?q=1#x"))
    }

    @Test
    fun `a port is part of the identity and is kept`() {
        assertEquals("https://acme.example.com:8443", normalizeIssuer("https://acme.example.com:8443"))
    }

    @Test
    fun `anything but https is refused`() {
        assertThrows(InvalidIssuerException::class.java) { normalizeIssuer("http://acme.example.com") }
    }

    @Test
    fun `a value that is not an absolute url is refused`() {
        assertThrows(InvalidIssuerException::class.java) { normalizeIssuer("acme.example.com") }
        assertThrows(InvalidIssuerException::class.java) { normalizeIssuer("") }
        assertThrows(InvalidIssuerException::class.java) { normalizeIssuer("https://") }
    }
}
