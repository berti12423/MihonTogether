package mihon.feature.social

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionOwnedGateTest {
    @Test
    fun `cancelled caller exits while underlying operation keeps writer gate`() = runTest {
        val gate = CompletionOwnedGate()
        val pending = PendingOperation()
        val second = PendingOperation()
        var secondStarted = false
        var revoked = false
        val caller = launch { gate.await({ pending }, PendingOperation::onComplete, PendingOperation::await) }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(caller.isCompleted)

        val next = launch {
            gate.await({
                secondStarted = true
                second
            }, PendingOperation::onComplete, PendingOperation::await)
        }
        launch { revoked = true }
        runCurrent()
        assertTrue(revoked, "Independent privacy revocation must not wait for an abandoned SDK task")
        assertFalse(secondStarted, "A new writer must wait for actual completion")

        pending.complete()
        runCurrent()
        assertTrue(secondStarted)
        second.complete()
        next.join()
    }

    @Test
    fun `synchronous startup failure releases writer gate`() = runTest {
        val gate = CompletionOwnedGate()
        try {
            gate.await<PendingOperation, Unit>(
                { error("SDK startup failed") },
                PendingOperation::onComplete,
                PendingOperation::await,
            )
        } catch (_: IllegalStateException) {
            // The next operation still acquires the gate.
        }
        var acquired = false
        gate.await(
            create = { acquired = true },
            onComplete = { _, completed -> completed() },
            wait = {},
        )
        assertTrue(acquired)
    }

    private class PendingOperation {
        private val result = CompletableDeferred<Unit>()
        private lateinit var completion: () -> Unit

        fun onComplete(callback: () -> Unit) {
            completion = callback
        }
        suspend fun await() = result.await()
        fun complete() {
            result.complete(Unit)
            completion()
        }
    }
}
