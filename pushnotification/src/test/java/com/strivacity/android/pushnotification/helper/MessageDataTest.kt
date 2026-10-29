package com.strivacity.android.pushnotification.helper

import android.content.Intent
import com.strivacity.android.pushnotification.envelope.PushEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [MessageData.from].
 */
@RunWith(RobolectricTestRunner::class)
class MessageDataTest {
    @Test
    fun `from returns a single-entry map when extras carry the push key`() {
        val intent = Intent().putExtra(PushEnvelope.KEY, "envelope-json")

        assertEquals(mapOf(PushEnvelope.KEY to "envelope-json"), MessageData.from(intent))
    }

    @Test
    fun `from returns null when there are no extras at all`() {
        val intent = Intent()

        assertNull(MessageData.from(intent))
    }

    @Test
    fun `from returns null when extras exist but the push key is absent`() {
        val intent = Intent().putExtra("some.other.key", "value")

        assertNull(MessageData.from(intent))
    }

    @Test
    fun `from excludes other, unrelated extras from the returned map`() {
        val intent =
            Intent()
                .putExtra(PushEnvelope.KEY, "envelope-json")
                .putExtra("gcm.n.e", "1")
                .putExtra("collapse_key", "do_not_collapse")

        assertEquals(mapOf(PushEnvelope.KEY to "envelope-json"), MessageData.from(intent))
    }
}
