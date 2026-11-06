package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationInterface
import com.strivacity.android.pushnotification.fakes.FakePushNotificationStorage
import com.strivacity.android.pushnotification.fakes.RecordingLogging
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.PushNotificationException
import com.strivacity.android.pushnotification.storage.PushNotificationStorage
import com.strivacity.android.pushnotification.support.createTestPushNotificationSdk
import com.strivacity.android.pushnotification.support.respondSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Exercises the log lines [PushNotificationCompat] emits through its [PushNotification]'s [Logging]. */
class PushNotificationCompatLoggingTest {
    private val exceptionMessage = "CANARY-EXCEPTION-MESSAGE"

    private val noopCallback =
        object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
            override fun onResult(result: List<Enrollment>) = Unit

            override fun onError(error: PushNotificationException) = Unit
        }

    @Test
    fun `a callback delivery the executor rejects is logged as a warning`() {
        val logging = RecordingLogging()
        val sdk = createTestPushNotificationSdk(logging = logging) { respondSuccess() }
        val rejecting = Executor { throw RejectedExecutionException() }

        PushNotificationCompat(sdk).listEnrollmentsAsync(null, rejecting, noopCallback)

        assertTrue(awaitLine(logging, "WARN Result dropped: the executor rejected the callback delivery"))
    }

    @Test
    fun `a failure the SDK does not describe is logged with its class name only`() {
        val logging = RecordingLogging()
        val failingStorage =
            object : PushNotificationStorage by FakePushNotificationStorage() {
                override suspend fun listEnrollments(): List<Enrollment> = throw IllegalStateException(exceptionMessage)
            }
        val sdk = createTestPushNotificationSdk(failingStorage, logging) { respondSuccess() }
        val delivered = CountDownLatch(1)

        PushNotificationCompat(sdk).listEnrollmentsAsync(
            null,
            { it.run() },
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) = Unit

                override fun onError(error: PushNotificationException) = delivered.countDown()
            },
        )

        assertTrue(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(
            listOf("ERROR The SDK failed in an unexpected way (java.lang.IllegalStateException)"),
            logging.lines,
        )
        assertFalse(logging.lines.any { it.contains(exceptionMessage) })
    }

    @Test
    fun `a delegate that is not a PushNotification logs nothing and still works`() {
        val delivered = CountDownLatch(1)
        val delegate = FakePushNotificationInterface(listEnrollments = { emptyList() })

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            null,
            { it.run() },
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) = delivered.countDown()

                override fun onError(error: PushNotificationException) = Unit
            },
        )

        assertTrue(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun awaitLine(
        logging: RecordingLogging,
        line: String,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (line in logging.lines) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val POLL_MILLIS = 10L
    }
}
