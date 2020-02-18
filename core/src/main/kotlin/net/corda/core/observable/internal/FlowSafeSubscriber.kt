package net.corda.core.observable.internal

import net.corda.core.internal.VisibleForTesting
import rx.Observer
import rx.Subscriber
import rx.exceptions.CompositeException
import rx.exceptions.Exceptions
import rx.exceptions.OnErrorFailedException
import rx.exceptions.OnErrorNotImplementedException
import rx.internal.util.ActionSubscriber
import rx.observers.SafeSubscriber
import rx.plugins.RxJavaHooks
import rx.plugins.RxJavaPlugins
import rx.subjects.Subject

/**
 * Extends [SafeSubscriber] to override [SafeSubscriber.onNext], [SafeSubscriber.onError] and [SafeSubscriber._onError].
 *
 * [FlowSafeSubscriber] will not set [SafeSubscriber.done] flag to true nor will call [SafeSubscriber.unsubscribe] upon
 * error inside [Observer.onNext]. This way, the [FlowSafeSubscriber] will not get unsubscribed and therefore the underlying [Observer]
 * will not get removed.
 *
 * An [Observer] that will not get removed due to errors in [onNext] events becomes useful when an unsubscribe could
 * lead to a malfunctioning CorDapp, due to a single isolated error. If the [Observer] gets removed,
 * it will no longer be available the next time any events are pushed from the base [Subject].
 */
@VisibleForTesting
class FlowSafeSubscriber<T>(actual: Subscriber<in T>) : SafeSubscriber<T>(actual) {

    /**
     * Duplicate of [SafeSubscriber.onNext]. However, it ignores [SafeSubscriber.done] flag.
     * It only delegates to [SafeSubscriber.onError] if it wraps an [ActionSubscriber] which is
     * a leaf in an Subscribers' tree structure.
     */
    override fun onNext(t: T) {
        try {
            actual.onNext(t)
        } catch (e: Throwable) {
            if (actual is ActionSubscriber) {
                // this Subscriber wraps an ActionSubscriber which is always a leaf Observer, then call user-defined onError
                Exceptions.throwOrReport(e, this)
            } else {
                // this Subscriber may wrap a non leaf Observer. In case the wrapped Observer is a PublishSubject then we
                // should not call onError because PublishSubjectState.onError will shut down all of the Observers under it
                throw OnNextFailedException(
                    "Observer.onNext failed, this is a non leaf FlowSafeSubscriber, therefore onError will be skipped", e
                )
            }
        }
    }

    /**
     * Duplicate of [SafeSubscriber.onError]. However, it will not set [SafeSubscriber.done] flag to true.
     */
    override fun onError(e: Throwable) {
        Exceptions.throwIfFatal(e)
        _onError(e)
    }

    /**
     * Duplicate of [SafeSubscriber._onError]. However, it will not call [Subscriber.unsubscribe].
     */
    override fun _onError(e: Throwable) {
        RxJavaPlugins.getInstance().errorHandler.handleError(e)
        try {
            actual.onError(e)
        } catch (e: OnErrorNotImplementedException) {
            throw e
        } catch (e2: Throwable) {
            RxJavaHooks.onError(e2)
            throw OnErrorFailedException(
                "Error occurred when trying to propagate error to Observer.onError", CompositeException(listOf(e, e2))
            )
        }
    }
}

/**
 * We throw [OnNextFailedException] to pass the exception back through the preceding [Subscriber] chain
 * without triggering any [SafeSubscriber.onError]s. Since we are extending an [OnErrorNotImplementedException]
 * the exception will be re-thrown at [Exceptions.throwOrReport].
 */
@VisibleForTesting
class OnNextFailedException(message: String, cause: Throwable) : OnErrorNotImplementedException(message, cause)