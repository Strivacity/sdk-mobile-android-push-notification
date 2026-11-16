package com.strivacity.android.pushnotification.fakes

import com.strivacity.android.pushnotification.Logging
import java.util.concurrent.CopyOnWriteArrayList

/** A [Logging] that records each line, prefixed with its level. */
internal class RecordingLogging : Logging {
    private val recorded = CopyOnWriteArrayList<String>()

    val lines: List<String> get() = recorded.toList()

    override fun debug(body: String) {
        recorded += "DEBUG $body"
    }

    override fun info(body: String) {
        recorded += "INFO $body"
    }

    override fun warn(body: String) {
        recorded += "WARN $body"
    }

    override fun error(body: String) {
        recorded += "ERROR $body"
    }
}
