package mihon.feature.social

/** One service tested by "Check connection" (Friends → Me). */
data class ConnectionProbe(val service: Service, val outcome: Outcome, val detail: String) {
    enum class Service { AUTH, FIRESTORE, FUNCTIONS, DATABASE, STORAGE }
    enum class Outcome { OK, TIMEOUT, ERROR }
}

object ConnectionDiagnosis {
    private val SEQUENTIAL = setOf(
        ConnectionProbe.Service.AUTH,
        ConnectionProbe.Service.FIRESTORE,
        ConnectionProbe.Service.DATABASE,
        ConnectionProbe.Service.STORAGE,
    )

    /**
     * Some mobile networks have a broken IPv6 route to Google. Functions (OkHttp, which tries IPv6 and IPv4 at
     * once) still works, but Firebase's sign-in, Firestore, Realtime Database and Storage libraries try IPv6 first
     * and time out (Berti's carrier, 2026-10-04; fixed by setting the APN protocol to IPv4). True when Functions
     * is OK and at least two of the others failed.
     */
    fun ipv6Suspected(probes: List<ConnectionProbe>): Boolean {
        val functionsOk = probes.any {
            it.service == ConnectionProbe.Service.FUNCTIONS && it.outcome == ConnectionProbe.Outcome.OK
        }
        val failed = probes.count { it.service in SEQUENTIAL && it.outcome != ConnectionProbe.Outcome.OK }
        return functionsOk && failed >= 2
    }
}
