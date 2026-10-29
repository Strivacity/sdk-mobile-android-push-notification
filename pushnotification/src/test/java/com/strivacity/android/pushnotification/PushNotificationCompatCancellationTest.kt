package com.strivacity.android.pushnotification

import android.os.CancellationSignal
import com.strivacity.android.pushnotification.fakes.FakePushNotificationInterface
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.PushNotificationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exercises [PushNotificationCompat]'s [CancellationSignal] handling — split out from
 * [PushNotificationCompatTest].
 */
@RunWith(RobolectricTestRunner::class)
class PushNotificationCompatCancellationTest {
    private val enrollment = Enrollment("https://issuer.example.com", "account-1", "key-1", "Jane", "My Phone")

    @Test
    fun `cancelling the signal while the delegate is mid-flight delivers neither callback`() {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val delegate =
            FakePushNotificationInterface(
                listEnrollments = {
                    started.countDown()
                    release.await()
                    listOf(enrollment)
                },
            )
        val signal = CancellationSignal()
        val called = AtomicBoolean(false)

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            signal,
            Executors.newSingleThreadExecutor(),
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) {
                    called.set(true)
                }

                override fun onError(error: PushNotificationException) {
                    called.set(true)
                }
            },
        )

        check(started.await(2, TimeUnit.SECONDS)) { "delegate never started" }
        signal.cancel()
        release.complete(Unit)

        Thread.sleep(300)
        assertFalse("no callback should have been delivered once cancelled", called.get())
    }

    @Test
    fun `cancelling while the delegate is suspended stops it from ever resuming`() {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val delegateFinished = AtomicBoolean(false)
        val delegate =
            FakePushNotificationInterface(
                listEnrollments = {
                    started.countDown()
                    release.await()
                    delegateFinished.set(true)
                    listOf(enrollment)
                },
            )
        val signal = CancellationSignal()
        val called = AtomicBoolean(false)

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            signal,
            Executors.newSingleThreadExecutor(),
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) {
                    called.set(true)
                }

                override fun onError(error: PushNotificationException) {
                    called.set(true)
                }
            },
        )

        check(started.await(2, TimeUnit.SECONDS)) { "delegate never started" }
        signal.cancel()
        release.complete(Unit)

        Thread.sleep(300)
        assertFalse(
            "the delegate should never resume past its suspension point once cancelled",
            delegateFinished.get(),
        )
        assertFalse("no callback should have been delivered once cancelled", called.get())
    }

    @Test
    fun `a signal already cancelled before the call is made never invokes the delegate`() {
        var delegateInvoked = false
        val delegate =
            FakePushNotificationInterface(
                listEnrollments = {
                    delegateInvoked = true
                    listOf(enrollment)
                },
            )
        val alreadyCancelledSignal = CancellationSignal().apply { cancel() }
        val called = AtomicBoolean(false)

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            alreadyCancelledSignal,
            Executors.newSingleThreadExecutor(),
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) {
                    called.set(true)
                }

                override fun onError(error: PushNotificationException) {
                    called.set(true)
                }
            },
        )

        Thread.sleep(300)
        assertFalse("no callback should have been delivered", called.get())
        assertFalse("the delegate should never have been invoked", delegateInvoked)
    }

    @Test
    fun `cancelling after the delegate completes but before delivery runs still suppresses the callback`() {
        val delegate = FakePushNotificationInterface(listEnrollments = { listOf(enrollment) })
        val signal = CancellationSignal()
        val called = AtomicBoolean(false)
        val queued = CountDownLatch(1)
        val queuedRunnables = mutableListOf<Runnable>()
        // Queues without running — lets the test control exactly when delivery executes, instead
        // of racing a real thread against signal.cancel().
        val controllableExecutor =
            Executor { runnable ->
                queuedRunnables.add(runnable)
                queued.countDown()
            }

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            signal,
            controllableExecutor,
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) {
                    called.set(true)
                }

                override fun onError(error: PushNotificationException) {
                    called.set(true)
                }
            },
        )

        check(queued.await(2, TimeUnit.SECONDS)) { "delivery was never queued on the executor" }
        signal.cancel()
        queuedRunnables.forEach { it.run() }

        assertFalse("no callback should have been delivered once cancelled", called.get())
    }
}
