package com.strivacity.android.pushnotification

import android.os.CancellationSignal
import com.strivacity.android.pushnotification.model.Challenge
import com.strivacity.android.pushnotification.model.ChallengeResolution
import com.strivacity.android.pushnotification.model.DeviceTokenType
import com.strivacity.android.pushnotification.model.Enrollment
import com.strivacity.android.pushnotification.model.EnrollmentGrant
import com.strivacity.android.pushnotification.model.PushNotificationException
import com.strivacity.android.pushnotification.model.ResolutionResult
import com.strivacity.android.pushnotification.model.UnexpectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * `*Async` entry points for Java callers, wrapping a [PushNotificationInterface].
 *
 * ```java
 * PushNotificationCompat sdk = new PushNotificationCompat(new PushNotification(storage));
 * ```
 *
 * The [Executor] is used for exactly one thing: delivering the callback. All internal work runs
 * on the SDK's own dispatcher, never on the caller's [Executor]. If the [Executor] will not
 * accept the callback delivery — including because it was already shut down before the call was
 * made — the call is silently abandoned: neither callback method is invoked, and nothing is
 * thrown or reported.
 */
public class PushNotificationCompat(
    private val delegate: PushNotificationInterface,
) {
    @Suppress("LongParameterList")
    public fun enrollDeviceAsync(
        grant: EnrollmentGrant,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
        deviceName: String,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<Enrollment, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) {
            delegate.enrollDevice(grant, deviceToken, deviceTokenType, deviceName)
        }
    }

    public fun unenrollDeviceAsync(
        issuer: String,
        accountId: String,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<Unit, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) { delegate.unenrollDevice(issuer, accountId) }
    }

    public fun hasEnrollmentAsync(
        issuer: String,
        accountId: String,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<Boolean, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) { delegate.hasEnrollment(issuer, accountId) }
    }

    public fun listEnrollmentsAsync(
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<List<Enrollment>, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) { delegate.listEnrollments() }
    }

    @Suppress("LongParameterList")
    public fun updateDeviceTokenAsync(
        issuer: String,
        accountId: String,
        deviceToken: String,
        deviceTokenType: DeviceTokenType,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<Unit, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) {
            delegate.updateDeviceToken(issuer, accountId, deviceToken, deviceTokenType)
        }
    }

    public fun canHandleMessageAsync(
        messageData: Map<String, String>?,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<Boolean, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) { delegate.canHandleMessage(messageData) }
    }

    public fun handleMessageAsync(
        messageData: Map<String, String>?,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<Challenge, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) { delegate.handleMessage(messageData) }
    }

    public fun resolveChallengeAsync(
        resolution: ChallengeResolution,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<ResolutionResult, PushNotificationException>,
    ) {
        run(cancellationSignal, executor, callback) { delegate.resolveChallenge(resolution) }
    }

    /**
     * Runs one suspending call and reports the result to [callback] via [executor].
     *
     * Cancelling [cancellationSignal] cancels the call; neither callback method is then invoked.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <R> run(
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<R, PushNotificationException>,
        block: suspend () -> R,
    ) {
        // Checked before launching: Dispatchers.IO can start the coroutine before the listener
        // below is even registered, so a signal cancelled before this call began must never be
        // allowed to race a dispatch — it has to be caught here instead.
        if (cancellationSignal?.isCanceled == true) return

        val scope = CoroutineScope(Dispatchers.IO)

        val job =
            scope.launch {
                val outcome =
                    try {
                        CallOutcome.Success(block())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: PushNotificationException) {
                        CallOutcome.Failure(failure)
                    } catch (unexpected: Throwable) {
                        CallOutcome.Failure(
                            UnexpectedException("The SDK failed in a way it does not describe", unexpected),
                        )
                    }
                deliver(cancellationSignal, executor, callback, outcome)
            }

        cancellationSignal?.setOnCancelListener { job.cancel() }
    }

    /**
     * Delivers [outcome] to [callback] via [executor]; if [executor] rejects it, the call is
     * abandoned silently.
     *
     * [cancellationSignal] is checked again immediately before invoking [callback] — not just
     * before this call — since block() finishing leaves no further suspension point for
     * cancellation to interrupt, and executor.execute itself can queue the delivery for a moment.
     */
    private fun <R> deliver(
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: PushNotificationCallback<R, PushNotificationException>,
        outcome: CallOutcome<R>,
    ) {
        try {
            executor.execute {
                if (cancellationSignal?.isCanceled != true) {
                    when (outcome) {
                        is CallOutcome.Success -> callback.onResult(outcome.value)
                        is CallOutcome.Failure -> callback.onError(outcome.error)
                    }
                }
            }
        } catch (ignoredRejection: RejectedExecutionException) {
            // The executor won't accept the callback delivery — nothing left we're able to do.
        }
    }

    private sealed class CallOutcome<out R> {
        class Success<R>(
            val value: R,
        ) : CallOutcome<R>()

        class Failure(
            val error: PushNotificationException,
        ) : CallOutcome<Nothing>()
    }
}
