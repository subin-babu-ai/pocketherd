package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TriageTest {

    private val now: Instant = Instant.parse("2026-10-15T14:00:00Z")

    private fun agent(
        id: String,
        status: AgentStatus,
        minutesAgo: Long,
        machineId: Long = 1,
        machine: String = "macbook",
        space: String = "~",
        unread: Boolean = false,
        isShell: Boolean = false,
    ) = AgentEntry(
        machineId = machineId,
        machineLabel = machine,
        pane = Pane(
            paneId = id,
            workspaceId = "w1",
            agent = if (isShell) null else "claude",
            agentStatus = status,
            terminalTitleStripped = id,
        ),
        workspace = Workspace(workspaceId = "w1", label = space),
        statusSince = now.minusSeconds(minutesAgo * 60),
        statusSinceExact = true,
        unread = unread,
    )

    @Test
    fun `the dock is oldest first, because the longest wait is the one to answer`() {
        val triage = listOf(
            agent("recent", AgentStatus.Blocked, 3),
            agent("ancient", AgentStatus.Blocked, 128),
            agent("middle", AgentStatus.Blocked, 22),
        ).triage()

        assertEquals(listOf("ancient", "middle", "recent"), triage.needsYou.map { it.pane.paneId })
    }

    @Test
    fun `a machine filter never hides a blocked agent`() {
        val entries = listOf(
            agent("blocked-elsewhere", AgentStatus.Blocked, 10, machineId = 2, machine = "desktop"),
            agent("working-here", AgentStatus.Working, 5, machineId = 1),
        )
        val triage = entries.triage(machineFilter = 1)

        // The working row is filtered out of its section...
        assertEquals(listOf("working-here"), triage.working.map { it.pane.paneId })
        // ...but the blocked agent on the other machine still shows.
        assertEquals(listOf("blocked-elsewhere"), triage.needsYou.map { it.pane.paneId })
    }

    @Test
    fun `new is done and unread, newest first`() {
        val triage = listOf(
            agent("older", AgentStatus.Done, 51, unread = true),
            agent("newer", AgentStatus.Done, 18, unread = true),
            agent("seen", AgentStatus.Done, 2, unread = false),
        ).triage()

        assertEquals(listOf("newer", "older"), triage.new.map { it.pane.paneId })
        // A done agent already seen belongs in Quiet, not New.
        assertEquals(listOf("seen"), triage.quiet.map { it.pane.paneId })
    }

    @Test
    fun `quiet mixes idle with done-and-seen and reports both counts`() {
        val triage = listOf(
            agent("idle-1", AgentStatus.Idle, 240),
            agent("done-seen", AgentStatus.Done, 190),
            agent("idle-2", AgentStatus.Idle, 175),
        ).triage()

        assertEquals(3, triage.quiet.size)
        assertEquals(1, triage.quietDoneCount)
        assertEquals(2, triage.quietIdleCount)
    }

    @Test
    fun `shells are separated from agents and never reach the dock`() {
        val triage = listOf(
            agent("zsh", AgentStatus.Unknown, 10, isShell = true),
            agent("real", AgentStatus.Blocked, 10),
        ).triage()

        assertEquals(listOf("zsh"), triage.shells.map { it.pane.paneId })
        assertEquals(listOf("real"), triage.needsYou.map { it.pane.paneId })
        assertTrue(triage.quiet.isEmpty())
    }

    @Test
    fun `an agent with no known status time sorts last rather than looking ancient`() {
        val unknown = agent("unknown", AgentStatus.Blocked, 0).copy(statusSince = null)
        val triage = listOf(unknown, agent("known", AgentStatus.Blocked, 5)).triage()
        assertEquals(listOf("known", "unknown"), triage.needsYou.map { it.pane.paneId })
    }

    @Test
    fun `age is floored to whole minutes and never negative`() {
        val entry = agent("a", AgentStatus.Working, 0)
            .copy(statusSince = now.plusSeconds(30))
        // A clock skew that puts the change in the future must not render "-1m".
        assertEquals(0L, entry.ageMinutes(now))
        assertEquals(128L, agent("b", AgentStatus.Blocked, 128).ageMinutes(now))
    }

    @Test
    fun `the since summary counts only changes after the user last looked`() {
        val lastSeen = now.minusSeconds(30 * 60)
        val summary = listOf(
            agent("blocked-after", AgentStatus.Blocked, 10),
            agent("blocked-before", AgentStatus.Blocked, 90),
            agent("done-after", AgentStatus.Done, 5, unread = true),
            agent("working-after", AgentStatus.Working, 2),
        ).since(lastSeen)

        assertEquals(1, summary.newlyBlocked)
        assertEquals(1, summary.finished)
        assertTrue(summary.any)
    }

    @Test
    fun `with no record of a previous visit the summary stays silent`() {
        val summary = listOf(agent("x", AgentStatus.Blocked, 5)).since(null)
        assertEquals(SinceSummary(0, 0), summary)
        assertTrue(!summary.any)
    }
}
