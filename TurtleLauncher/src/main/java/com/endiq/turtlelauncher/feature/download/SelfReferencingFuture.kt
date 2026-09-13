package com.endiq.turtlelauncher.feature.download

import com.endiq.turtlelauncher.feature.log.Logging.i
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class SelfReferencingFuture(private val mFutureInterface: FutureInterface) {
    private val mFutureLock = Any()
    private var mMyFuture: Future<*>? = null

    fun startOnExecutor(executorService: ExecutorService): Future<*> {
        val future = executorService.submit { this.run() }
        synchronized(mFutureLock) {
            mMyFuture = future
            // Kotlin's Any doesn't expose Object.wait()/notify() directly - this used to
            // borrow OkHttp's internal (non-public, unstable) `notify()`/`wait()` extension
            // functions for the syntax sugar. Cast to java.lang.Object instead so this has
            // zero dependency on OkHttp internals that can move/vanish across major versions.
            (mFutureLock as java.lang.Object).notify()
        }
        return future
    }

    private fun run() {
        try {
            synchronized(mFutureLock) {
                // Loop: Object.wait() can return spuriously, and an interrupt that
                // arrives (caught below) must also not let us proceed with a null Future.
                while (mMyFuture == null) (mFutureLock as java.lang.Object).wait()
            }
            mMyFuture?.let { mFutureInterface.run(it) }
        } catch (e: InterruptedException) {
            i("SelfReferencingFuture", "Interrupted while acquiring own Future")
        }
    }

    interface FutureInterface {
        fun run(myFuture: Future<*>)
    }
}
