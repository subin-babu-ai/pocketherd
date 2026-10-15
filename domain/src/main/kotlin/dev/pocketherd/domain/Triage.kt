package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import java.time.Instant

/**
 * The agent list as four sections plus a dock.
 *
 * The old list sorted everything by urgency into one scroll. That buried the two agents
 * that needed a human under seventeen that did not, and the answer to "did anything block
 * while I was out?" required reading all nineteen rows. Splitting by what the user can act
 * on makes the question answerable without scrolling.
 */
data class Triage(
    /** Blocked. Lives in the bottom dock, never scrolls away, never filtered by machine. */
    val needsYou: List<AgentEntry>,
    /** Finished or blocked since the user last looked, newest first. */
    val new: List<AgentEntry>,
    val working: List<AgentEntry>,
    /** Idle, plus done and already seen. Collapsed by default. */
    val quiet: List<AgentEntry>,
    val shells: List<AgentEntry>,
) {
    val quietDoneCount: Int get() = quiet.count { it.pane.agentStatus == AgentStatus.Done }
    val quietIdleCount: Int get() = quiet.count { it.pane.agentStatus == AgentStatus.Idle }
}

/**
 * Splits agents into sections.
 *
 * [machineFilter] deliberately does not apply to [Triage.needsYou]. Hiding a blocked agent
 * because you were looking at a different machine is how you miss the thing the app exists
 * to surface; the dock always shows everything.
 */
fun List<AgentEntry>.triage(machineFilter: Long? = null): Triage {
    val blocked = filter { it.pane.agentStatus == AgentStatus.Blocked }
        // Oldest first: the agent that has been waiting longest is the one to answer.
        // Entries with no known time sort last rather than pretending to be ancient.
        .sortedWith(compareBy(nullsLast()) { it.statusSince })

    val scoped = if (machineFilter == null) this else filter { it.machineId == machineFilter }

    val agents = scoped.filter { it.pane.isAgent && it.pane.agentStatus != AgentStatus.Blocked }
    val newest = compareByDescending<AgentEntry> { it.statusSince ?: Instant.EPOCH }

    return Triage(
        needsYou = blocked,
        new = agents.filter { it.unread && it.pane.agentStatus == AgentStatus.Done }
            .sortedWith(newest),
        working = agents.filter { it.pane.agentStatus == AgentStatus.Working }
            .sortedWith(newest),
        quiet = agents.filter {
            it.pane.agentStatus == AgentStatus.Idle ||
                (it.pane.agentStatus == AgentStatus.Done && !it.unread)
        }.sortedWith(
            compareBy<AgentEntry> { it.machineLabel }
                .thenBy { it.workspace?.displayName ?: "" },
        ),
        shells = scoped.filter { !it.pane.isAgent }
            .sortedWith(
                compareBy<AgentEntry> { it.machineLabel }.thenBy { it.pane.displayName },
            ),
    )
}

/** Minutes a status has been in effect, or null when nothing is known. */
fun AgentEntry.ageMinutes(now: Instant): Long? =
    statusSince?.let { (now.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0) / 60_000 }

/** Counts for "Since you looked at HH:mm: X newly blocked, Y finished". */
data class SinceSummary(val newlyBlocked: Int, val finished: Int) {
    val any: Boolean get() = newlyBlocked > 0 || finished > 0
}

fun List<AgentEntry>.since(lastSeenAt: Instant?): SinceSummary {
    if (lastSeenAt == null) return SinceSummary(0, 0)
    val after = filter { it.statusSince?.isAfter(lastSeenAt) == true }
    return SinceSummary(
        newlyBlocked = after.count { it.pane.agentStatus == AgentStatus.Blocked },
        finished = after.count { it.pane.agentStatus == AgentStatus.Done },
    )
}
