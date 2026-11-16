package com.strivacity.android.pushnotification.util

import com.strivacity.android.pushnotification.model.NetworkException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Exercises [logLabel]. */
class LogLabelTest {
    @Test
    fun `an SDK failure is labelled by its code, not its message`() {
        assertEquals("network_failure", logLabel(NetworkException("secret message", IOException())))
    }

    @Test
    fun `any other failure is labelled by its full class name, not its message`() {
        assertEquals("java.lang.IllegalStateException", logLabel(IllegalStateException("secret message")))
    }
}
