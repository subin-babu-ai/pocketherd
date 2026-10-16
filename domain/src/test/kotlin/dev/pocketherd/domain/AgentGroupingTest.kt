package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.Tab
import dev.pocketherd.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AgentGroupingTest {

    private val now: Instant = Instant.parse("2026-10-15T14:00:00Z")

    private fun entry(
        id: String,
        status: AgentStatus,
        minutesAgo: Long = 5,
        machineId: Long = 1,
        machine: String = "desktop",
        workspaceId: String = "w1",
        space: String = "api-bugs",
        worktree: String? = null,
        tabNumber: Int? = 1,
        tabLabel: String? = "planner",
        unread: Boolean = false,
        isShell: Boolean = false,
    ) = AgentEntry(
        machineId = machineId,
        machineLabel = machine,
        pane = Pane(
            paneId = id,
            workspaceId = workspaceId,
            tabId = tabNumber?.let { "$workspaceId:t$it" },
            agent = if (isShell) null else "claude",
            agentStatus = status,
            terminalTitleStripped = id,
        ),
        workspace = Workspace(workspaceId = workspaceId, label = space, worktree = worktree),
        tab = tabNumber?.let {
            Tab(tabId = "$workspaceId:t$it", number = it, label = tabLabel)
        },
        statusSince = now.minusSeconds(minutesAgo * 60),
        statusSinceExact = true,
        unread = unread,
    )

    @Test
    fun `cards are ordered by what the space needs, not its name`() {
        val groups = listOf(
            entry("quiet", AgentStatus.Idle, workspaceId = "wA", space = "aaa"),
            entry("working", AgentStatus.Working, workspaceId = "wB", space = "bbb"),
            entry("blocked", AgentStatus.Blocked, workspaceId = "wZ", space = "zzz"),
            entry("unread", AgentStatus.Done, workspaceId = "wC", space = "ccc", unread = true),
        ).groupBySpace()

        assertEquals(listOf("zzz", "ccc", "bbb", "aaa"), groups.map { it.spaceLabel })
    }

    @Test
    fun `the same space id on two machines stays two cards`() {
        val groups = listOf(
            entry("a", AgentStatus.Idle, machineId = 1, machine = "macbook"),
            entry("b", AgentStatus.Idle, machineId = 2, machine = "desktop"),
        ).groupBySpace()
        assertEquals(2, groups.size)
    }

    @Test
    fun `counts split agents from shells`() {
        val group = listOf(
            entry("blocked", AgentStatus.Blocked),
            entry("done-unread", AgentStatus.Done, unread = true),
            entry("working", AgentStatus.Working),
            entry("idle", AgentStatus.Idle),
            entry("shell", AgentStatus.Unknown, isShell = true),
        ).groupBySpace().single()

        assertEquals(1, group.blocked)
        assertEquals(1, group.unread)
        assertEquals(1, group.working)
        assertEquals(1, group.quiet)
        assertEquals(1, group.shells)
    }

    @Test
    fun `the subtitle omits a worktree that is not there`() {
        val plain = listOf(entry("a", AgentStatus.Idle)).groupBySpace().single()
        assertEquals("desktop · 1 tab", plain.subtitle())

        val withTree = listOf(
            entry("a", AgentStatus.Idle, worktree = "hmi@sae-22650"),
        ).groupBySpace().single()
        assertEquals("desktop · ⎇ hmi@sae-22650 · 1 tab", withTree.subtitle())
    }

    @Test
    fun `tabs are ordered by number and keep their agents`() {
        val group = listOf(
            entry("b", AgentStatus.Idle, tabNumber = 3, tabLabel = "review"),
            entry("a", AgentStatus.Idle, tabNumber = 1, tabLabel = "planner"),
        ).groupBySpace().single()

        assertEquals(listOf("1 planner", "3 review"), group.tabs.map { it.label })
    }

    @Test
    fun `an unnamed tab does not repeat its own number`() {
        // herdr labels a tab with its number by default, so "1 1" must not appear.
        val group = listOf(
            entry("a", AgentStatus.Idle, tabNumber = 1, tabLabel = "1"),
        ).groupBySpace().single()

        assertEquals("1", group.tabs.single().label)
        assertNull(group.tabs.single().tab?.meaningfulLabel)
        assertEquals("tab 1", group.tabs.single().tab?.display)
    }

    @Test
    fun `a named tab reads as tab number name`() {
        val group = listOf(entry("a", AgentStatus.Idle, tabNumber = 2, tabLabel = "hmi"))
            .groupBySpace().single()
        assertEquals("tab 2 hmi", group.tabs.single().tab?.display)
    }

    @Test
    fun `the dock groups blocked agents by space, oldest block first`() {
        val groups = listOf(
            entry("recent", AgentStatus.Blocked, 3, workspaceId = "wA", space = "aaa"),
            entry("ancient", AgentStatus.Blocked, 128, workspaceId = "wZ", space = "zzz"),
            entry("middle", AgentStatus.Blocked, 22, workspaceId = "wM", space = "mmm"),
            entry("not-blocked", AgentStatus.Working, 1, workspaceId = "wA", space = "aaa"),
        ).blockedBySpace()

        assertEquals(listOf("zzz", "mmm", "aaa"), groups.map { it.spaceLabel })
        // The working agent in space aaa must not ride along into the dock.
        assertEquals(listOf("recent"), groups.last().agents.map { it.pane.paneId })
    }

    @Test
    fun `within a dock group the longest wait comes first`() {
        val group = listOf(
            entry("newer", AgentStatus.Blocked, 5),
            entry("older", AgentStatus.Blocked, 90),
        ).blockedBySpace().single()
        assertEquals(listOf("older", "newer"), group.agents.map { it.pane.paneId })
    }
}
