package dev.pocketherd.feature.pane

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketherd.domain.HerdrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaneUiState(
    val text: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    /** The agent's window title, which tracks what it is working on. */
    val title: String = "",
    val subtitle: String = "",
)

/**
 * Drives one pane view: read the screen, follow output, send input back.
 *
 * This is a snapshot view rather than a PTY attach. herdr's real terminal attach uses a
 * separate binary protocol reserved for same-install clients, so it is not reachable over
 * the JSON socket API. `pane.read` plus `pane.send_text` gets close enough to unblock an
 * agent from a phone, which is what this screen is for.
 */
@HiltViewModel
class PaneViewModel @Inject constructor(
    private val herdr: HerdrRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val machineId: Long = checkNotNull(savedStateHandle["machineId"])
    private val paneId: String = checkNotNull(savedStateHandle["paneId"])

    private val _uiState = MutableStateFlow(PaneUiState())
    val uiState: StateFlow<PaneUiState> = _uiState.asStateFlow()

    init {
        // The title is taken live rather than passed through the nav route, because the
        // agent rewrites it as the task changes and a route argument would freeze it.
        viewModelScope.launch {
            herdr.observeAgents(machineId)
                .map { entries -> entries.firstOrNull { it.pane.paneId == paneId } }
                .distinctUntilChanged()
                .collect { entry ->
                    if (entry != null) {
                        _uiState.value = _uiState.value.copy(
                            title = entry.pane.displayName,
                            subtitle = buildString {
                                append(entry.machineLabel)
                                entry.pane.agentLabel?.let { append(" · $it") }
                                append(" · ${entry.pane.paneId}")
                            },
                        )
                    }
                }
        }

        refresh()
        // herdr pushes a revision on every output change; re-read only when it moves,
        // instead of polling the pane on a timer.
        viewModelScope.launch {
            herdr.observePaneRevision(machineId, paneId).collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { herdr.readPane(machineId, paneId, ansi = true) }
                .onSuccess { read ->
                    _uiState.value = _uiState.value.copy(text = read.text, error = null)
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(error = t.message ?: "read failed")
                }
        }
    }

    /** Sends a line to the agent. [submit] appends the Enter the agent is waiting on. */
    fun send(text: String, submit: Boolean = true) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sending = true)
            runCatching {
                herdr.sendText(machineId, paneId, if (submit) "$text\n" else text)
            }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(error = t.message ?: "send failed")
                }
            _uiState.value = _uiState.value.copy(sending = false)
            refresh()
        }
    }

    /** Named keys for the things a soft keyboard cannot type: Enter, Esc, Ctrl-C, arrows. */
    fun sendKeys(keys: String) {
        viewModelScope.launch {
            runCatching { herdr.sendKeys(machineId, paneId, keys) }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(error = t.message ?: "send failed")
                }
            refresh()
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
