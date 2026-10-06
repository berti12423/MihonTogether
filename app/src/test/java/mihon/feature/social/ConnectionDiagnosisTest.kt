package mihon.feature.social

import mihon.feature.social.ConnectionProbe.Outcome
import mihon.feature.social.ConnectionProbe.Service
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionDiagnosisTest {
    private fun probes(vararg outcomes: Pair<Service, Outcome>) = outcomes.map { (s, o) -> ConnectionProbe(s, o, "") }
    private val ok = Outcome.OK

    @Test
    fun `functions ok while the others time out points to the IPv6 problem`() {
        // Berti's mobile data, 2026-10-04.
        assertTrue(
            ConnectionDiagnosis.ipv6Suspected(
                probes(
                    Service.AUTH to Outcome.TIMEOUT,
                    Service.FIRESTORE to Outcome.ERROR,
                    Service.FUNCTIONS to ok,
                    Service.DATABASE to Outcome.TIMEOUT,
                    Service.STORAGE to Outcome.TIMEOUT,
                ),
            ),
        )
    }

    @Test
    fun `no hint when everything works, nothing works, or just one service fails`() {
        assertFalse(
            ConnectionDiagnosis.ipv6Suspected(
                probes(
                    Service.AUTH to ok,
                    Service.FIRESTORE to ok,
                    Service.FUNCTIONS to ok,
                    Service.DATABASE to ok,
                    Service.STORAGE to ok,
                ),
            ),
        )
        assertFalse(
            ConnectionDiagnosis.ipv6Suspected(
                probes(
                    Service.AUTH to Outcome.TIMEOUT,
                    Service.FIRESTORE to Outcome.TIMEOUT,
                    Service.FUNCTIONS to Outcome.TIMEOUT,
                    Service.DATABASE to Outcome.TIMEOUT,
                    Service.STORAGE to Outcome.TIMEOUT,
                ),
            ),
        )
        assertFalse(
            ConnectionDiagnosis.ipv6Suspected(
                probes(
                    Service.AUTH to ok,
                    Service.FIRESTORE to ok,
                    Service.FUNCTIONS to ok,
                    Service.DATABASE to ok,
                    Service.STORAGE to Outcome.ERROR,
                ),
            ),
        )
    }
}
