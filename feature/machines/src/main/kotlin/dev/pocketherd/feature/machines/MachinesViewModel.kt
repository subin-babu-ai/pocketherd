package dev.pocketherd.feature.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketherd.core.model.AuthMethod
import dev.pocketherd.core.model.Machine
import dev.pocketherd.core.ssh.KeystoreIdentity
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.domain.ConnectionState
import dev.pocketherd.domain.MachineRepository
import dev.pocketherd.domain.ObserveAllAgentsUseCase
import dev.pocketherd.domain.ObserveMachineStatusUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The add/edit form. [editingId] of 0 means a new machine; anything else is an edit of the
 * row with that id, which keeps Save a single code path instead of two near-identical ones.
 */
data class MachineForm(
    val editingId: Long = 0,
    val label: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val socketPath: String = "",
    /** Machine id to tunnel through, or null for a direct connection. */
    val via: Long? = null,
    /** One address per line, tried when the primary fails. */
    val fallbackHosts: String = "",
    val open: Boolean = false,
) {
    val isEditing: Boolean get() = editingId != 0L

    /** The form is a screen of its own, so it needs an explicit open flag. */
    val isOpen: Boolean get() = open
    val canSave: Boolean get() = host.isNotBlank() && username.isNotBlank()

    fun toMachine() = Machine(
        id = editingId,
        label = label.trim().ifBlank { host.trim() },
        host = host.trim(),
        port = port.toIntOrNull() ?: 22,
        username = username.trim(),
        socketPath = socketPath.trim().ifBlank { null },
        auth = AuthMethod.Keystore,
        via = via,
        fallbackHosts = fallbackHosts.lines().map { it.trim() }.filter { it.isNotEmpty() },
    )

    companion object {
        fun of(machine: Machine) = MachineForm(
            editingId = machine.id,
            label = machine.label,
            host = machine.host,
            port = machine.port.toString(),
            username = machine.username,
            socketPath = machine.socketPath.orEmpty(),
            via = machine.via,
            fallbackHosts = machine.fallbackHosts.joinToString("\n"),
        )
    }
}

/** A machine plus everything the card shows about it. */
data class MachineCard(
    val machine: Machine,
    val state: ConnectionState,
    val blocked: Int,
    val agents: Int,
    val shells: Int,
)

@HiltViewModel
class MachinesViewModel @Inject constructor(
    private val repository: MachineRepository,
    observeMachineStatus: ObserveMachineStatusUseCase,
    observeAllAgents: ObserveAllAgentsUseCase,
) : ViewModel() {

    val machines: StateFlow<List<Machine>> = repository.observeMachines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The cards. Counts come from the same agent stream the inbox uses, so a machine's
     * card and its rows in the inbox can never disagree.
     */
    val cards: StateFlow<List<MachineCard>> =
        combine(observeMachineStatus(), observeAllAgents(agentsOnly = false)) { statuses, all ->
            statuses.map { status ->
                val mine = all.filter { it.machineId == status.machine.id }
                MachineCard(
                    machine = status.machine,
                    state = status.state,
                    blocked = mine.count {
                        it.pane.isAgent && it.pane.agentStatus == AgentStatus.Blocked
                    },
                    agents = mine.count { it.pane.isAgent },
                    shells = mine.count { !it.pane.isAgent },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(MachineForm())
    val form: StateFlow<MachineForm> = _form.asStateFlow()

    private val _publicKey = MutableStateFlow("")
    val publicKey: StateFlow<String> = _publicKey.asStateFlow()

    init {
        // Generated on first read. The user copies this into authorized_keys on each host;
        // the matching private key stays in the Keystore and cannot be exported.
        viewModelScope.launch {
            _publicKey.value = runCatching {
                KeystoreIdentity.loadOrCreate().openSshPublicKey
            }.getOrElse { "Could not read the device key: ${it.message}" }
        }
    }

    fun update(form: MachineForm) {
        _form.value = form
    }

    fun edit(machine: Machine) {
        _form.value = MachineForm.of(machine).copy(open = true)
    }

    fun addMachine() {
        _form.value = MachineForm(open = true)
    }

    fun cancelEdit() {
        _form.value = MachineForm()
    }

    fun save() {
        val form = _form.value
        if (!form.canSave) return
        viewModelScope.launch {
            repository.upsert(form.toMachine())
            _form.value = MachineForm()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            // Deleting the row being edited would otherwise leave the form pointing at an
            // id that no longer exists, and Save would resurrect it.
            if (_form.value.editingId == id) _form.value = MachineForm()
        }
    }
}
