package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The one piece of logic that is genuinely cross-cutting: fan out over every saved machine,
 * merge their agents into a single list, and order it by who needs a human.
 *
 * This is what makes the app a herdrm equivalent rather than an SSH client. It lives in
 * :domain because three ViewModels would otherwise each reimplement the merge.
 */
class ObserveAllAgentsUseCase @Inject constructor(
    private val machines: MachineRepository,
    private val herdr: HerdrRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(agentsOnly: Boolean = true): Flow<List<AgentEntry>> =
        machines.observeMachines().flatMapLatest { saved ->
            if (saved.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(saved.map { herdr.observeAgents(it.id) }) { perMachine ->
                    perMachine.asSequence()
                        .flatMap { it.asSequence() }
                        .filter { !agentsOnly || it.pane.isAgent }
                        .sortedWith(AttentionOrder)
                        .toList()
                }
            }
        }

    /**
     * Blocked first, then done, working, idle. Within a status, group by machine then
     * workspace so the list does not reshuffle under the user's thumb on every event.
     */
    private object AttentionOrder : Comparator<AgentEntry> {
        override fun compare(a: AgentEntry, b: AgentEntry): Int {
            val byStatus = a.pane.agentStatus.attentionRank - b.pane.agentStatus.attentionRank
            if (byStatus != 0) return byStatus
            val byMachine = a.machineLabel.compareTo(b.machineLabel)
            if (byMachine != 0) return byMachine
            val byWorkspace = (a.workspace?.displayName ?: "").compareTo(b.workspace?.displayName ?: "")
            if (byWorkspace != 0) return byWorkspace
            return a.pane.paneId.compareTo(b.pane.paneId)
        }
    }
}

/** Counts for the dashboard header, derived rather than tracked separately. */
fun Flow<List<AgentEntry>>.attentionCounts(): Flow<AttentionCounts> = map { entries ->
    AttentionCounts(
        blocked = entries.count { it.pane.agentStatus.attentionRank == 0 },
        working = entries.count { it.pane.agentStatus.attentionRank == 2 },
        total = entries.size,
    )
}

data class AttentionCounts(val blocked: Int, val working: Int, val total: Int)
