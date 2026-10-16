package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.Tab
import java.time.Instant

/**
 * One herdr space on one machine, with its agents split by tab.
 *
 * Keyed by machine as well as space: every herdr numbers its spaces from `w1`, so merging
 * on the space id alone would interleave unrelated work from two boxes.
 */
data class AgentGroup(
    val key: String,
    val machineLabel: String,
    val spaceLabel: String,
    val worktree: String?,
    val agents: List<AgentEntry>,
    val tabs: List<TabGroup>,
) {
    val blocked: Int get() = agents.count { it.pane.agentStatus == AgentStatus.Blocked }
    val unread: Int get() = agents.count { it.unread && it.pane.isAgent }
    val working: Int get() = agents.count { it.pane.agentStatus == AgentStatus.Working }
    val shells: Int get() = agents.count { !it.pane.isAgent }
    val quiet: Int
        get() = agents.count {
            it.pane.isAgent && !it.unread &&
                (it.pane.agentStatus == AgentStatus.Idle ||
                    it.pane.agentStatus == AgentStatus.Done)
        }

    /** Oldest block in this space, which is what orders the cards and the dock. */
    val oldestBlock: Instant?
        get() = agents.filter { it.pane.agentStatus == AgentStatus.Blocked }
            .mapNotNull { it.statusSince }
            .minOrNull()

    /** "desktop · ⎇ hmi@sae-22650 · 2 tabs", with the worktree omitted when absent. */
    fun subtitle(): String = buildString {
        append(machineLabel)
        worktree?.takeIf { it.isNotBlank() }?.let { append(" · ⎇ $it") }
        append(" · ${tabs.size} tab${if (tabs.size == 1) "" else "s"}")
    }
}

data class TabGroup(val tab: Tab?, val agents: List<AgentEntry>) {
    /** "3 planner", or just the number when the tab was never named. */
    val label: String
        get() = listOfNotNull(tab?.number?.toString(), tab?.meaningfulLabel)
            .joinToString(" ")
            .ifBlank { "untabbed" }
}

/**
 * Groups agents into space cards.
 *
 * Card order is by what the space needs rather than its name: blocked first, then anything
 * unread, then working, then quiet. Alphabetical order would put a space with a blocked
 * agent at the bottom simply because it starts with "w".
 */
fun List<AgentEntry>.groupBySpace(): List<AgentGroup> =
    groupBy { it.spaceKey }
        .map { (key, agents) ->
            val first = agents.first()
            AgentGroup(
                key = key,
                machineLabel = first.machineLabel,
                spaceLabel = first.workspace?.displayName ?: "no space",
                worktree = first.workspace?.worktree,
                agents = agents,
                tabs = agents.groupBy { it.tab?.tabId }
                    .map { (_, inTab) -> TabGroup(inTab.first().tab, inTab) }
                    .sortedBy { it.tab?.number ?: Int.MAX_VALUE },
            )
        }
        .sortedWith(
            compareBy<AgentGroup> { group ->
                when {
                    group.blocked > 0 -> 0
                    group.unread > 0 -> 1
                    group.working > 0 -> 2
                    else -> 3
                }
            }
                .thenBy { it.oldestBlock ?: Instant.MAX }
                .thenBy { it.machineLabel }
                .thenBy { it.spaceLabel },
        )

/**
 * The dock's blocked agents, grouped by space.
 *
 * Groups are ordered by their oldest block and so are the rows inside them, so the agent
 * that has been waiting longest is always the first thing in the dock.
 */
fun List<AgentEntry>.blockedBySpace(): List<AgentGroup> =
    filter { it.pane.agentStatus == AgentStatus.Blocked }
        .groupBySpace()
        .sortedBy { it.oldestBlock ?: Instant.MAX }
        .map { group ->
            group.copy(
                agents = group.agents.sortedWith(compareBy(nullsLast()) { it.statusSince }),
            )
        }
