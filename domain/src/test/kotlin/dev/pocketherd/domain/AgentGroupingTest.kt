package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGroupingTest {

    private fun entry(
        machineId: Long,
        machineLabel: String,
        workspaceId: String,
        workspaceLabel: String,
        paneId: String,
        status: AgentStatus,
    ) = AgentEntry(
        machineId = machineId,
        machineLabel = machineLabel,
        pane = Pane(paneId = paneId, workspaceId = workspaceId, agent = "claude", agentStatus = status),
        workspace = Workspace(workspaceId = workspaceId, label = workspaceLabel),
    )

    @Test
    fun `the space holding a blocked agent sorts first`() {
        val groups = listOf(
            entry(1, "mac", "w1", "aaa-idle", "w1:p1", AgentStatus.Idle),
            entry(1, "mac", "w2", "zzz-blocked", "w2:p1", AgentStatus.Blocked),
            entry(1, "mac", "w3", "mmm-working", "w3:p1", AgentStatus.Working),
        ).groupBySpace()

        // Alphabetically zzz is last; urgency must beat the label.
        assertEquals(listOf("zzz-blocked", "mmm-working", "aaa-idle"), groups.map { it.spaceLabel })
    }

    @Test
    fun `identical space ids on different machines stay separate`() {
        // Every herdr instance numbers its spaces from w1, so ids collide across machines.
        val groups = listOf(
            entry(1, "mac", "w1", "api", "w1:p1", AgentStatus.Idle),
            entry(2, "desktop", "w1", "api", "w1:p1", AgentStatus.Idle),
        ).groupBySpace()

        assertEquals(2, groups.size)
        assertEquals(listOf("desktop", "mac"), groups.map { it.machineLabel })
    }

    @Test
    fun `a group reports its most urgent status and blocked count`() {
        val groups = listOf(
            entry(1, "mac", "w1", "bugs", "w1:p1", AgentStatus.Idle),
            entry(1, "mac", "w1", "bugs", "w1:p2", AgentStatus.Blocked),
            entry(1, "mac", "w1", "bugs", "w1:p3", AgentStatus.Blocked),
        ).groupBySpace()

        assertEquals(1, groups.size)
        assertEquals(AgentStatus.Blocked, groups[0].status)
        assertEquals(2, groups[0].blockedCount)
        assertEquals(3, groups[0].agents.size)
    }

    @Test
    fun `agents with no space are kept rather than dropped`() {
        val orphan = AgentEntry(
            machineId = 1,
            machineLabel = "mac",
            pane = Pane(paneId = "w9:p1", agent = "claude", agentStatus = AgentStatus.Working),
            workspace = null,
        )
        val groups = listOf(orphan).groupBySpace()
        assertEquals(1, groups.size)
        assertEquals("no space", groups[0].spaceLabel)
    }

    @Test
    fun `an empty list groups to nothing`() {
        assertEquals(emptyList<AgentGroup>(), emptyList<AgentEntry>().groupBySpace())
    }
}
