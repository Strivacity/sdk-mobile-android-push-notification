package com.strivacity.android.pushnotification.util

import com.strivacity.android.pushnotification.model.InvalidDeviceNameException
import org.junit.Assert
import org.junit.Test

class ValidateDeviceNameTest {
    @Test
    fun `letters, digits, underscore, hyphen, and spaces are accepted`() {
        validateDeviceName("Dani_s Pixel-7")
    }

    @Test
    fun `exactly the minimum length is accepted`() {
        validateDeviceName("abc")
    }

    @Test
    fun `exactly the maximum length is accepted`() {
        validateDeviceName("a".repeat(64))
    }

    @Test
    fun `all whitespace character is refused`() {
        Assert.assertThrows(InvalidDeviceNameException::class.java) { validateDeviceName("  ") }
    }

    @Test
    fun `longer than the maximum length is refused`() {
        Assert.assertThrows(InvalidDeviceNameException::class.java) { validateDeviceName("a".repeat(65)) }
    }

    @Test
    fun `empty is refused - shorter than the minimum length`() {
        Assert.assertThrows(InvalidDeviceNameException::class.java) { validateDeviceName("") }
    }

    @Test
    fun `surrounding whitespace is not counted`() {
        validateDeviceName("  " + "a".repeat(64) + "\n")
    }

    @Test
    fun `characters outside the basic plane count once`() {
        validateDeviceName("📱".repeat(64))
        Assert.assertThrows(InvalidDeviceNameException::class.java) { validateDeviceName("📱".repeat(65)) }
    }

    @Test
    fun `a line break inside the name is accepted`() {
        validateDeviceName("Pixel\n7")
    }

    @Test
    fun `accepts special characters`() {
        validateDeviceName("Someone's Pixel")
        validateDeviceName("Pixel #7")
        validateDeviceName("📱 Pixel")
    }
}
