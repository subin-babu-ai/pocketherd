package dev.pocketherd.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MachineHostsTest {

    private fun machine(
        host: String = "10.0.1.20",
        fallbacks: List<String> = emptyList(),
        lastGood: String? = null,
    ) = Machine(
        id = 1,
        label = "desktop",
        host = host,
        username = "subin",
        fallbackHosts = fallbacks,
        lastGoodHost = lastGood,
    )

    @Test
    fun `with one address there is one candidate`() {
        assertEquals(listOf("10.0.1.20"), machine().candidateHosts())
    }

    @Test
    fun `fallbacks follow the primary, in order`() {
        val m = machine(fallbacks = listOf("100.64.0.5", "desktop.local"))
        assertEquals(listOf("10.0.1.20", "100.64.0.5", "desktop.local"), m.candidateHosts())
    }

    @Test
    fun `the address that worked last time is tried first`() {
        // Otherwise every reconnect on the network where the fallback wins pays a
        // timeout on the primary before getting there.
        val m = machine(fallbacks = listOf("100.64.0.5"), lastGood = "100.64.0.5")
        assertEquals(listOf("100.64.0.5", "10.0.1.20"), m.candidateHosts())
    }

    @Test
    fun `a remembered address is not tried twice`() {
        val m = machine(fallbacks = listOf("100.64.0.5"), lastGood = "10.0.1.20")
        assertEquals(listOf("10.0.1.20", "100.64.0.5"), m.candidateHosts())
    }

    @Test
    fun `blank and duplicate entries are dropped`() {
        val m = machine(fallbacks = listOf("  ", "10.0.1.20", " 100.64.0.5 "))
        assertEquals(listOf("10.0.1.20", "100.64.0.5"), m.candidateHosts())
    }

    @Test
    fun `a stale remembered address still leaves the real ones reachable`() {
        // The remembered host can be one the user has since removed from the list.
        val m = machine(fallbacks = listOf("100.64.0.5"), lastGood = "old.example")
        assertEquals(listOf("old.example", "10.0.1.20", "100.64.0.5"), m.candidateHosts())
    }
}
