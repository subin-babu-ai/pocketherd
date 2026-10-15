package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus

/**
 * Agents belonging to one herdr space on one machine.
 *
 * Keyed by machine as well as space because space ids are only unique within a machine;
 * two boxes both have a `w1`, and merging them would silently interleave unrelated work.
 */
data class AgentGroup(
    val key: String,
    val machineLabel: String,
    val spaceLabel: String,
    val agents: List<AgentEntry>,
) {
    /** The most urgent status in the group, which decides where the group sorts. */
    val status: AgentStatus
        get() = agents.minByOrNull { it.pane.agentStatus.attentionRank }
            ?.pane?.agentStatus
            ?: AgentStatus.Unknown

    val blockedCount: Int get() = agents.count { it.pane.agentStatus == AgentStatus.Blocked }
}

/**
 * Groups agents by space, ordering groups by whichever contains the most urgent agent.
 *
 * Grouping on its own would bury a blocked agent inside the alphabetically-last space.
 * Sorting groups by their worst status keeps the thing that needs a human at the top of
 * the screen, which is the whole reason this list exists.
 *
 * Input is assumed to be in attention order already, so agents keep that order inside each
 * group without re-sorting.
 */
fun List<AgentEntry>.groupBySpace(): List<AgentGroup> =
    groupBy { entry -> entry.machineId to (entry.workspace?.workspaceId ?: "") }
        .map { (key, agents) ->
            val (machineId, workspaceId) = key
            val first = agents.first()
            AgentGroup(
                key = "$machineId:$workspaceId",
                machineLabel = first.machineLabel,
                spaceLabel = first.workspace?.displayName ?: "no space",
                agents = agents,
            )
        }
        .sortedWith(
            compareBy<AgentGroup> { it.status.attentionRank }
                .thenBy { it.machineLabel }
                .thenBy { it.spaceLabel },
        )
