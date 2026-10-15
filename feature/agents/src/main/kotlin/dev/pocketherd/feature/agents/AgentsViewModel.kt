package dev.pocketherd.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.domain.AgentGroup
import dev.pocketherd.domain.AttentionCounts
import dev.pocketherd.domain.HerdrRepository
import dev.pocketherd.domain.MachineStatus
import dev.pocketherd.domain.ObserveAllAgentsUseCase
import dev.pocketherd.domain.ObserveMachineStatusUseCase
import dev.pocketherd.domain.blockedMachines
import dev.pocketherd.domain.groupBySpace
import dev.pocketherd.domain.pendingHostKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentsUiState(
    val agents: List<AgentEntry> = emptyList(),
    val counts: AttentionCounts = AttentionCounts(0, 0, 0),
    val showShells: Boolean = false,
    val loading: Boolean = true,
    val hasMachines: Boolean = false,
    /** At most one host key prompt at a time, so the dialog cannot stack. */
    val pendingHostKey: MachineStatus? = null,
    val blocked: List<MachineStatus> = emptyList(),
    /** Every machine with its live state, so an empty list can explain itself. */
    val machines: List<MachineStatus> = emptyList(),
    val groupBySpace: Boolean = true,
    val groups: List<AgentGroup> = emptyList(),
    /** Machine id the list is restricted to, or null for all of them. */
    val machineFilter: Long? = null,
)

@HiltViewModel
class AgentsViewModel @Inject constructor(
    observeAllAgents: ObserveAllAgentsUseCase,
    observeMachineStatus: ObserveMachineStatusUseCase,
    private val herdr: HerdrRepository,
) : ViewModel() {

    private val showShells = MutableStateFlow(false)
    private val groupBySpace = MutableStateFlow(true)
    private val machineFilter = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val agents = showShells.flatMapLatest { shells ->
        observeAllAgents(agentsOnly = !shells)
    }

    val uiState: StateFlow<AgentsUiState> =
        combine(
            agents,
            observeMachineStatus(),
            showShells,
            groupBySpace,
            machineFilter,
        ) { list, statuses, shells, grouped, machine ->
            // Filter before counting, so the header describes what is on screen rather
            // than what exists. A header saying "3 blocked" above a list with none is
            // worse than no header.
            val visible =
                if (machine == null) list else list.filter { it.machineId == machine }

            AgentsUiState(
                agents = visible,
                // Counted here rather than from a second collection of `agents`, which
                // would subscribe to every machine's state flow twice over.
                counts = AttentionCounts(
                    blocked = visible.count { it.pane.agentStatus.attentionRank == 0 },
                    working = visible.count { it.pane.agentStatus.attentionRank == 2 },
                    total = visible.size,
                ),
                showShells = shells,
                loading = false,
                hasMachines = statuses.isNotEmpty(),
                pendingHostKey = statuses.pendingHostKey(),
                blocked = statuses.blockedMachines(),
                machines = statuses,
                groupBySpace = grouped,
                // Only computed when it will be rendered; the flat list is already sorted.
                groups = if (grouped) visible.groupBySpace() else emptyList(),
                machineFilter = machine,
            )
        }.stateIn(
            scope = viewModelScope,
            // Keep the session warm for five seconds past a rotation so returning to the
            // list does not tear down the SSH connection and rebuild it.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AgentsUiState(),
        )

    fun toggleShells() {
        showShells.value = !showShells.value
    }

    fun toggleGrouping() {
        groupBySpace.value = !groupBySpace.value
    }

    /** Tapping the machine already selected clears the filter, so the chip acts as a toggle. */
    fun toggleMachine(machineId: Long) {
        machineFilter.value = if (machineFilter.value == machineId) null else machineId
    }

    fun clearMachineFilter() {
        machineFilter.value = null
    }

    /** Called only from the dialog, with the fingerprint the user actually saw. */
    fun approveHostKey(machineId: Long, fingerprint: String) {
        viewModelScope.launch { herdr.approveHostKey(machineId, fingerprint) }
    }
}
