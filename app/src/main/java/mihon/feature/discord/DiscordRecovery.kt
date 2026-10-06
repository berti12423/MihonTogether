package mihon.feature.discord

/**
 * When the SDK's RPC link to the Discord app breaks (ErrorType 9, RPCError), the client is rebuilt and the last status
 * resent. Platform-free so the decision is unit-tested.
 */
object DiscordRecovery {
    /** The native bridge prefixes RPC failures with this; other failures use "error:". */
    const val RPC_PREFIX = "rpcerror:"

    const val BACKOFF_MS = 3_000L
    const val MAX_ATTEMPTS = 3
    const val WINDOW_MS = 60_000L

    sealed interface Decision {
        /** Rebuild the client after waiting [waitMs]. */
        data class Retry(val waitMs: Long) : Decision

        /** Too many recent attempts: show the error. */
        data object GiveUp : Decision
    }

    /** Whether an update result ("ok", "error:…" or "rpcerror:…") means the RPC connection itself failed. */
    fun isRpcFailure(result: String): Boolean {
        if (result == "ok") return false
        return result.startsWith(RPC_PREFIX) ||
            result.contains("RPCError", ignoreCase = true) ||
            ERROR_TYPE_9.containsMatchIn(result)
    }

    private val ERROR_TYPE_9 = Regex("""ErrorType\D{0,3}9(?!\d)""")

    /** [attemptTimes] are the start times (epoch ms) of earlier recovery attempts. */
    fun decide(now: Long, attemptTimes: List<Long>): Decision {
        val recent = attemptTimes.filter { now - it in 0 until WINDOW_MS }
        if (recent.size >= MAX_ATTEMPTS) return Decision.GiveUp
        val last = recent.maxOrNull() ?: return Decision.Retry(0)
        return Decision.Retry((last + BACKOFF_MS - now).coerceIn(0, BACKOFF_MS))
    }
}
