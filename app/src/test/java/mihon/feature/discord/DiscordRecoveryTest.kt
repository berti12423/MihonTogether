package mihon.feature.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordRecoveryTest {
    @Test
    fun `rpc failures are recognised`() {
        assertTrue(DiscordRecovery.isRpcFailure("rpcerror:boom"))
        assertTrue(DiscordRecovery.isRpcFailure("error:RPCError: connection closed"))
        assertTrue(DiscordRecovery.isRpcFailure("error:ErrorType 9"))
        assertTrue(DiscordRecovery.isRpcFailure("error:ErrorType: 9"))
    }

    @Test
    fun `other results are not rpc failures`() {
        assertFalse(DiscordRecovery.isRpcFailure("ok"))
        assertFalse(DiscordRecovery.isRpcFailure("error:ErrorType 1"))
        assertFalse(DiscordRecovery.isRpcFailure("error:ErrorType 19"))
        assertFalse(DiscordRecovery.isRpcFailure("error:network down"))
    }

    @Test
    fun `first attempt retries at once`() {
        assertEquals(DiscordRecovery.Decision.Retry(0), DiscordRecovery.decide(10_000, emptyList()))
    }

    @Test
    fun `next attempt waits out the back-off`() {
        assertEquals(DiscordRecovery.Decision.Retry(2_000), DiscordRecovery.decide(11_000, listOf(10_000)))
        assertEquals(DiscordRecovery.Decision.Retry(0), DiscordRecovery.decide(14_000, listOf(10_000)))
    }

    @Test
    fun `gives up after too many attempts in the window`() {
        val attempts = listOf(10_000L, 13_000L, 16_000L)
        assertEquals(DiscordRecovery.Decision.GiveUp, DiscordRecovery.decide(20_000, attempts))
    }

    @Test
    fun `old attempts no longer count`() {
        val attempts = listOf(10_000L, 13_000L, 16_000L)
        assertEquals(DiscordRecovery.Decision.Retry(0), DiscordRecovery.decide(80_000, attempts))
    }
}
