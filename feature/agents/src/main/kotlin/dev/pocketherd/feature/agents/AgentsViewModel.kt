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
import dev.pocketherd.domain.AgentGroup
import dev.pocketherd.domain.Triage
import dev.pocketherd.domain.blockedBySpace
import dev.pocketherd.domain.groupBySpace
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

enum class ListView { Inbox, Spaces }

data class AgentsUiState(
    val view: ListView = ListView.Inbox,
    /** Blocked agents grouped by space; what the dock renders. */
    val dock: List<AgentGroup> = emptyList(),
    /** Space cards for the Spaces view. */
    val spaces: List<AgentGroup> = emptyList(),
    val spaceFilter: String? = null,
    val spaceFilterLabel: String? = null,
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
    private val spaceFilter = MutableStateFlow<String?>(null)
    private val view = MutableStateFlow(ListView.Inbox)

    // Shells are a collapsed section now rather than a chip, so the use case is asked for
    // everything and the sectioning decides what surfaces.
    private val agents = observeAllAgents(agentsOnly = false)

    val uiState: StateFlow<AgentsUiState> =
        combine(
            agents,
            observeMachineStatus(),
            combine(machineFilter, spaceFilter, view) { m, sp, v -> Triple(m, sp, v) },
            herdr.lastSeenAt,
        ) { list, statuses, (filter, space, listView), lastSeen ->
            val realAgents = list.filter { it.pane.isAgent }
            val scopedForSpaces =
                if (filter == null) list else list.filter { it.machineId == filter }
            AgentsUiState(
                view = listView,
                dock = list.blockedBySpace(),
                spaces = scopedForSpaces.groupBySpace(),
                spaceFilter = space,
                spaceFilterLabel = space?.let { key ->
                    list.firstOrNull { it.spaceKey == key }
                        ?.let { "${it.workspace?.displayName ?: "space"} ${it.machineLabel}" }
                },
                triage = list.triage(machineFilter = filter, spaceFilter = space),
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

    fun setView(next: ListView) {
        view.value = next
    }

    /** "Show in inbox" from a space card: filter to it and switch views. */
    fun showSpaceInInbox(spaceKey: String) {
        spaceFilter.value = spaceKey
        view.value = ListView.Inbox
    }

    fun clearSpaceFilter() {
        spaceFilter.value = null
    }

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
