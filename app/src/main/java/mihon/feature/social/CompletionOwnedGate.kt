package mihon.feature.social

import kotlinx.coroutines.sync.Mutex

/** Serializes underlying operations without extending a cancelled caller's lifetime. */
internal class CompletionOwnedGate {
    private val mutex = Mutex()

    suspend fun <Handle, Result> await(
        create: () -> Handle,
        onComplete: (Handle, () -> Unit) -> Unit,
        wait: suspend (Handle) -> Result,
    ): Result {
        mutex.lock()
        val handle = try {
            create()
        } catch (error: Throwable) {
            mutex.unlock()
            throw error
        }
        onComplete(handle) { mutex.unlock() }
        return wait(handle)
    }
}
