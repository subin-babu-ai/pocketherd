package dev.pocketherd.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.domain.HerdrRepository
import dev.pocketherd.domain.MachineStatus
import dev.pocketherd.domain.ObserveAllAgentsUseCase
import dev.pocketherd.domain.ObserveMachineStatusUseCase
import dev.pocketherd.domain.SinceSummary
import dev.pocketherd.domain.Triage
import dev.pocketherd.domain.blockedMachines
import dev.pocketherd.domain.pendingHostKey
import dev.pocketherd.domain.since
import dev.pocketherd.domain.triage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class AgentsUiState(
    val triage: Triage = Triage(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    val machines: List<MachineStatus> = emptyList(),
    val machineFilter: Long? = null,
    val lastSeenAt: Instant? = null,
    val since: SinceSummary = SinceSummary(0, 0),
    val loading: Boolean = true,
    val hasMachines: Boolean = false,
    val pendingHostKey: MachineStatus? = null,
    val blocked: List<MachineStatus> = emptyList(),
    /** Agent counts per machine, for the filter chips. */
    val countsByMachine: Map<Long, Int> = emptyMap(),
    val totalAgents: Int = 0,
) {
    val needsYouCount: Int get() = triage.needsYou.size
}

@HiltViewModel
class AgentsViewModel @Inject constructor(
    observeAllAgents: ObserveAllAgentsUseCase,
    observeMachineStatus: ObserveMachineStatusUseCase,
    private val herdr: HerdrRepository,
) : ViewModel() {

    private val machineFilter = MutableStateFlow<Long?>(null)

    // Shells are a collapsed section now rather than a chip, so the use case is asked for
    // everything and the sectioning decides what surfaces.
    private val agents = observeAllAgents(agentsOnly = false)

    val uiState: StateFlow<AgentsUiState> =
        combine(
            agents,
            observeMachineStatus(),
            machineFilter,
            herdr.lastSeenAt,
        ) { list, statuses, filter, lastSeen ->
            val realAgents = list.filter { it.pane.isAgent }
            AgentsUiState(
                triage = list.triage(machineFilter = filter),
                machines = statuses,
                machineFilter = filter,
                lastSeenAt = lastSeen,
                since = list.since(lastSeen),
                loading = false,
                hasMachines = statuses.isNotEmpty(),
                pendingHostKey = statuses.pendingHostKey(),
                blocked = statuses.blockedMachines(),
                countsByMachine = realAgents.groupingBy { it.machineId }.eachCount(),
                totalAgents = realAgents.size,
            )
        }.stateIn(
            scope = viewModelScope,
            // Keeps the SSH sessions warm across a rotation instead of reconnecting.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AgentsUiState(),
        )

    fun toggleMachine(machineId: Long) {
        machineFilter.value = if (machineFilter.value == machineId) null else machineId
    }

    fun clearMachineFilter() {
        machineFilter.value = null
    }

    fun markSeen(entry: AgentEntry) {
        viewModelScope.launch { herdr.markSeen(entry.machineId, entry.pane.paneId) }
    }

    fun markAllSeen() {
        viewModelScope.launch { herdr.markAllSeen() }
    }

    fun approveHostKey(machineId: Long, fingerprint: String) {
        viewModelScope.launch { herdr.approveHostKey(machineId, fingerprint) }
    }

    /**
     * Records that the list was on screen, which anchors "since you looked at HH:mm".
     *
     * Called when the list stops after having been visible long enough to count as a look.
     * A glance measured in milliseconds, or the screen merely rotating, must not reset the
     * mark and wipe out the summary the user came back for.
     */
    fun recordLookedAt() {
        viewModelScope.launch { herdr.recordLookedAt(Instant.now()) }
    }
}
