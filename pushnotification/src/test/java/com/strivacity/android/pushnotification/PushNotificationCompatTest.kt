package com.strivacity.android.pushnotification

import com.strivacity.android.pushnotification.fakes.FakePushNotificationInterface
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeContext
import com.strivacity.android.pushnotification.model.ChallengeResolution
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.NotEnrolledException
import com.strivacity.android.pushnotification.model.PushNotificationException
import com.strivacity.android.pushnotification.model.ResolutionResult
import com.strivacity.android.pushnotification.model.UnexpectedException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exercises [PushNotificationCompat] — the `*Async` layer for Java callers. Per its own scope,
 * this only checks that it correctly proxies to a [PushNotificationInterface] delegate (the
 * right method, the right arguments, the result or error delivered on the given callback); the
 * business logic behind each SDK method is exercised elsewhere, through
 * [PushNotificationInterface] itself.
 *
 * `CancellationSignal`-based scenarios are covered separately in
 * [PushNotificationCompatCancellationTest].
 */
class PushNotificationCompatTest {
    private val grant = EnrollmentGrant("https://issuer.example.com", "ott-1", "account-1", "Jane")
    private val enrollment = Enrollment("https://issuer.example.com", "account-1", "key-1", "Jane", "My Phone")
    private val challenge =
        Challenge.Simple(
            ChallengeContext(
                "challenge-1",
                "challenge-value",
                "https://issuer.example.com",
                "account-1",
                "2026-10-01T14:30:00Z",
                emptyMap(),
            ),
        )
    private val resolution = challenge.deny()

    // --- proxying: the right delegate method is called, and its result is delivered ---

    @Test
    fun `enrollDeviceAsync delegates to enrollDevice and delivers the result`() {
        var captured: List<Any?>? = null
        val delegate =
            FakePushNotificationInterface(
                enrollDevice = { g, token, type, name ->
                    captured = listOf(g, token, type, name)
                    enrollment
                },
            )

        val outcome =
            awaitCallback<Enrollment> { callback ->
                PushNotificationCompat(delegate)
                    .enrollDeviceAsync(
                        grant,
                        "token-abc",
                        DeviceTokenType.FCM_TOKEN,
                        "My Phone",
                        null,
                        executor(),
                        callback,
                    )
            }

        assertEquals(listOf(grant, "token-abc", DeviceTokenType.FCM_TOKEN, "My Phone"), captured)
        assertEquals(CallbackOutcome.Success(enrollment), outcome)
    }

    @Test
    fun `unenrollDeviceAsync delegates to unenrollDevice and delivers the result`() {
        var captured: List<Any?>? = null
        val delegate =
            FakePushNotificationInterface(unenrollDevice = { issuer, accountId ->
                captured =
                    listOf(issuer, accountId)
            })

        val outcome =
            awaitCallback<Unit> { callback ->
                PushNotificationCompat(delegate)
                    .unenrollDeviceAsync("https://issuer.example.com", "account-1", null, executor(), callback)
            }

        assertEquals(listOf("https://issuer.example.com", "account-1"), captured)
        assertEquals(CallbackOutcome.Success(Unit), outcome)
    }

    @Test
    fun `hasEnrollmentAsync delegates to hasEnrollment and delivers the result`() {
        var captured: List<Any?>? = null
        val delegate =
            FakePushNotificationInterface(hasEnrollment = { issuer, accountId ->
                captured =
                    listOf(issuer, accountId)
                ; true
            })

        val outcome =
            awaitCallback<Boolean> { callback ->
                PushNotificationCompat(delegate)
                    .hasEnrollmentAsync("https://issuer.example.com", "account-1", null, executor(), callback)
            }

        assertEquals(listOf("https://issuer.example.com", "account-1"), captured)
        assertEquals(CallbackOutcome.Success(true), outcome)
    }

    @Test
    fun `listEnrollmentsAsync delegates to listEnrollments and delivers the result`() {
        val delegate = FakePushNotificationInterface(listEnrollments = { listOf(enrollment) })

        val outcome =
            awaitCallback<List<Enrollment>> { callback ->
                PushNotificationCompat(delegate).listEnrollmentsAsync(null, executor(), callback)
            }

        assertEquals(CallbackOutcome.Success(listOf(enrollment)), outcome)
    }

    @Test
    fun `updateDeviceTokenAsync delegates to updateDeviceToken and delivers the result`() {
        var captured: List<Any?>? = null
        val delegate =
            FakePushNotificationInterface(
                updateDeviceToken = { issuer, accountId, token, type ->
                    captured =
                        listOf(issuer, accountId, token, type)
                },
            )

        val outcome =
            awaitCallback<Unit> { callback ->
                PushNotificationCompat(delegate).updateDeviceTokenAsync(
                    "https://issuer.example.com",
                    "account-1",
                    "token-abc",
                    DeviceTokenType.FCM_TOKEN,
                    null,
                    executor(),
                    callback,
                )
            }

        assertEquals(
            listOf("https://issuer.example.com", "account-1", "token-abc", DeviceTokenType.FCM_TOKEN),
            captured,
        )
        assertEquals(CallbackOutcome.Success(Unit), outcome)
    }

    @Test
    fun `canHandleMessageAsync delegates to canHandleMessage and delivers the result`() {
        var captured: Map<String, String>? = null
        val messageData = mapOf("com.strivacity.push" to "{}")
        val delegate =
            FakePushNotificationInterface(canHandleMessage = { data ->
                captured = data
                true
            })

        val outcome =
            awaitCallback<Boolean> { callback ->
                PushNotificationCompat(delegate).canHandleMessageAsync(messageData, null, executor(), callback)
            }

        assertEquals(messageData, captured)
        assertEquals(CallbackOutcome.Success(true), outcome)
    }

    @Test
    fun `handleMessageAsync delegates to handleMessage and delivers the result`() {
        var captured: Map<String, String>? = null
        val messageData = mapOf("com.strivacity.push" to "{}")
        val delegate =
            FakePushNotificationInterface(handleMessage = { data ->
                captured = data
                challenge
            })

        val outcome =
            awaitCallback<Challenge> { callback ->
                PushNotificationCompat(delegate).handleMessageAsync(messageData, null, executor(), callback)
            }

        assertEquals(messageData, captured)
        assertEquals(CallbackOutcome.Success(challenge), outcome)
    }

    @Test
    fun `resolveChallengeAsync delegates to resolveChallenge and delivers the result`() {
        var captured: ChallengeResolution? = null
        val delegate =
            FakePushNotificationInterface(resolveChallenge = { r ->
                captured = r
                ResolutionResult.OK
            })

        val outcome =
            awaitCallback<ResolutionResult> { callback ->
                PushNotificationCompat(delegate).resolveChallengeAsync(resolution, null, executor(), callback)
            }

        assertSame(resolution, captured)
        assertEquals(CallbackOutcome.Success(ResolutionResult.OK), outcome)
    }

    // --- proxying an error ---

    @Test
    fun `a PushNotificationException from the delegate is delivered via onError unchanged`() {
        val expected = NotEnrolledException("account-1")
        val delegate = FakePushNotificationInterface(enrollDevice = { _, _, _, _ -> throw expected })

        val outcome =
            awaitCallback<Enrollment> { callback ->
                PushNotificationCompat(delegate)
                    .enrollDeviceAsync(
                        grant,
                        "token-abc",
                        DeviceTokenType.FCM_TOKEN,
                        "device",
                        null,
                        executor(),
                        callback,
                    )
            }

        assertSame(expected, (outcome as CallbackOutcome.Failure).error)
    }

    @Test
    fun `a non-PushNotificationException from the delegate is delivered via onError wrapped as UnexpectedException`() {
        val original = IllegalStateException("boom")
        val delegate = FakePushNotificationInterface(enrollDevice = { _, _, _, _ -> throw original })

        val outcome =
            awaitCallback<Enrollment> { callback ->
                PushNotificationCompat(delegate)
                    .enrollDeviceAsync(
                        grant,
                        "token-abc",
                        DeviceTokenType.FCM_TOKEN,
                        "device",
                        null,
                        executor(),
                        callback,
                    )
            }

        val failure = (outcome as CallbackOutcome.Failure).error
        assertTrue(failure is UnexpectedException)
        assertSame(original, failure.cause)
    }

    // --- delivery mechanics ---

    @Test
    fun `the result is delivered on the supplied Executor's thread`() {
        val namedExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "compat-test-thread") }
        try {
            val delegate = FakePushNotificationInterface(listEnrollments = { listOf(enrollment) })
            val latch = CountDownLatch(1)
            var deliveryThreadName: String? = null

            PushNotificationCompat(delegate).listEnrollmentsAsync(
                null,
                namedExecutor,
                object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                    override fun onResult(result: List<Enrollment>) {
                        deliveryThreadName = Thread.currentThread().name
                        latch.countDown()
                    }

                    override fun onError(error: PushNotificationException) {
                        latch.countDown()
                    }
                },
            )

            check(latch.await(2, TimeUnit.SECONDS)) { "callback never fired" }
            // kotlinx.coroutines' debug mode appends " @coroutine#N" to the thread name; the prefix
            // is what identifies the actual thread.
            assertTrue(
                "expected delivery on \"compat-test-thread\", was \"$deliveryThreadName\"",
                deliveryThreadName?.startsWith("compat-test-thread") == true,
            )
        } finally {
            namedExecutor.shutdown()
        }
    }

    @Test
    fun `exactly one of onResult or onError is called, exactly once`() {
        val delegate = FakePushNotificationInterface(listEnrollments = { listOf(enrollment) })
        var resultCalls = 0
        var errorCalls = 0
        val latch = CountDownLatch(1)

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            null,
            executor(),
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) {
                    resultCalls++
                    latch.countDown()
                }

                override fun onError(error: PushNotificationException) {
                    errorCalls++
                    latch.countDown()
                }
            },
        )

        check(latch.await(2, TimeUnit.SECONDS)) { "callback never fired" }
        assertEquals(1, resultCalls)
        assertEquals(0, errorCalls)
    }

    @Test
    fun `several concurrent calls against a single-threaded Executor all complete`() {
        val callCount = 20
        val sharedExecutor = Executors.newSingleThreadExecutor()
        try {
            val delegate = FakePushNotificationInterface(listEnrollments = { listOf(enrollment) })
            val completions = CountDownLatch(callCount)
            val compat = PushNotificationCompat(delegate)

            repeat(callCount) {
                compat.listEnrollmentsAsync(
                    null,
                    sharedExecutor,
                    object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                        override fun onResult(result: List<Enrollment>) = completions.countDown()

                        override fun onError(error: PushNotificationException) = completions.countDown()
                    },
                )
            }

            assertTrue("expected all $callCount calls to complete", completions.await(5, TimeUnit.SECONDS))
        } finally {
            sharedExecutor.shutdown()
        }
    }

    /**
     * Internal work runs on the SDK's own dispatcher, never on the caller's `Executor` — shutting
     * the `Executor` down mid-call doesn't affect the delegate call in progress at all. Only the
     * final callback delivery ever touches it, and that's the one point where a dead `Executor`
     * causes the call to be silently abandoned.
     */
    @Test
    fun `an Executor shut down before delivery leaves the call silently abandoned`() {
        val executorService = Executors.newSingleThreadExecutor()
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
        val called = AtomicBoolean(false)

        PushNotificationCompat(delegate).listEnrollmentsAsync(
            null,
            executorService,
            object : PushNotificationCallback<List<Enrollment>, PushNotificationException> {
                override fun onResult(result: List<Enrollment>) = called.set(true)

                override fun onError(error: PushNotificationException) = called.set(true)
            },
        )
        // Shut the executor down, and only then let the (suspended, not yet finished) delegate
        // call proceed — guaranteeing the executor is already dead by the time delivery is ever
        // attempted, rather than racing shutdown() against it.
        check(started.await(2, TimeUnit.SECONDS)) { "delegate never started" }
        executorService.shutdown()
        release.complete(Unit)

        Thread.sleep(200)
        assertTrue("no callback should have been delivered", !called.get())
    }

    // --- a rejecting executor still lets the delegate run; only delivery is affected ---

    /**
     * The delegate call proceeds even against an already-dead `Executor` — it's never touched
     * until delivery, the one point a dead `Executor` can affect.
     */
    @Test
    fun `a shut down executor still lets the delegate run, but delivers nothing`() {
        val executor = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val delegateRan = AtomicBoolean(false)
        val delegate =
            FakePushNotificationInterface(canHandleMessage = {
                delegateRan.set(true)
                true
            })
        val called = AtomicBoolean(false)

        PushNotificationCompat(delegate).canHandleMessageAsync(
            emptyMap(),
            null,
            executor,
            RecordingCallback(called),
        )

        // Give the delegate call, which now runs entirely off `executor`, a chance to complete.
        Thread.sleep(200)
        assertTrue("delegate should still run even though the executor is already dead", delegateRan.get())
        assertTrue("no callback should have been delivered", !called.get())
    }

    private class RecordingCallback(
        private val called: AtomicBoolean,
    ) : PushNotificationCallback<Boolean, PushNotificationException> {
        override fun onResult(result: Boolean) {
            called.set(true)
        }

        override fun onError(error: PushNotificationException) {
            called.set(true)
        }
    }

    // Runs the callback wherever `execute` is invoked from (some coroutine dispatcher thread,
    // never the test thread) — these callers don't care which thread delivery lands on, only
    // that it happens, so there's no need for a real (leaked, non-daemon) thread pool here.
    private fun executor(): Executor = Executor { it.run() }

    /**
     * Runs [call] against a real callback, blocking until it fires, and returns what it
     * delivered.
     */
    private fun <R> awaitCallback(
        call: (PushNotificationCallback<R, PushNotificationException>) -> Unit,
    ): CallbackOutcome<R> {
        val latch = CountDownLatch(1)
        var outcome: CallbackOutcome<R>? = null

        call(
            object : PushNotificationCallback<R, PushNotificationException> {
                override fun onResult(result: R) {
                    outcome = CallbackOutcome.Success(result)
                    latch.countDown()
                }

                override fun onError(error: PushNotificationException) {
                    outcome = CallbackOutcome.Failure(error)
                    latch.countDown()
                }
            },
        )

        check(latch.await(2, TimeUnit.SECONDS)) { "callback never fired" }
        return checkNotNull(outcome)
    }

    private sealed class CallbackOutcome<out R> {
        data class Success<R>(
            val result: R,
        ) : CallbackOutcome<R>()

        data class Failure(
            val error: PushNotificationException,
        ) : CallbackOutcome<Nothing>()
    }
}
