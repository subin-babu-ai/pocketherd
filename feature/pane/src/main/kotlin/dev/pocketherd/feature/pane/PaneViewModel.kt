package dev.pocketherd.feature.pane

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.PromptKind
import dev.pocketherd.core.model.PromptOption
import dev.pocketherd.domain.HerdrRepository
import dev.pocketherd.domain.ObserveAllAgentsUseCase
import dev.pocketherd.domain.PromptParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/** What the reply sheet is showing. */
sealed interface ReplyPhase {
    /** Taking input. */
    data object Idle : ReplyPhase

    /** The answer went out; waiting to see it take effect. */
    data class Sent(val answer: String) : ReplyPhase

    /** The agent left Blocked after we answered. */
    data class Resumed(val answer: String) : ReplyPhase
}

data class PaneUiState(
    val title: String = "",
    val meta: String = "",
    val status: AgentStatus = AgentStatus.Unknown,
    val ageMinutes: Long? = null,
    val ageExact: Boolean = false,
    val text: String = "",
    val snapshotAt: Instant? = null,
    val prompt: PromptKind? = null,
    val phase: ReplyPhase = ReplyPhase.Idle,
    /** True when the user chose to type instead of picking an option. */
    val composing: Boolean = false,
    val composerQuestion: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
    val nextBlocked: AgentEntry? = null,
    val blockedRemaining: Int = 0,
) {
    val isBlocked: Boolean get() = status == AgentStatus.Blocked
}

/**
 * Drives one pane: render the snapshot, work out what the agent is asking, send the answer.
 *
 * Not a PTY attach. herdr reserves real terminal attach for a separate binary protocol, so
 * this reads a composed screen and writes answers back. Everything here is built so nothing
 * implies character-level liveness the transport does not have.
 */
@HiltViewModel(assistedFactory = PaneViewModel.Factory::class)
class PaneViewModel @AssistedInject constructor(
    @Assisted private val machineId: Long,
    @Assisted private val paneId: String,
    private val herdr: HerdrRepository,
    observeAllAgents: ObserveAllAgentsUseCase,
) : ViewModel() {

    /**
     * Assisted rather than read from SavedStateHandle, because on a large screen the pane
     * is rendered beside the list rather than as a navigation destination, so there is no
     * back stack entry to carry its arguments.
     */
    @AssistedFactory
    interface Factory {
        fun create(machineId: Long, paneId: String): PaneViewModel
    }

    private val _uiState = MutableStateFlow(PaneUiState())
    val uiState: StateFlow<PaneUiState> = _uiState.asStateFlow()

    init {
        // Header and status come from the live agent list, so the title tracks the task as
        // the agent renames it and the status pill flips the moment the agent moves on.
        viewModelScope.launch {
            observeAllAgents(agentsOnly = false)
                .map { all -> all.firstOrNull { it.key == "$machineId:$paneId" } to all }
                .distinctUntilChanged()
                .collect { (entry, all) ->
                    if (entry == null) return@collect
                    val wasBlocked = _uiState.value.isBlocked
                    val blocked = all.filter {
                        it.pane.agentStatus == AgentStatus.Blocked && it.key != entry.key
                    }.sortedWith(compareBy(nullsLast()) { it.statusSince })

                    _uiState.value = _uiState.value.copy(
                        title = entry.pane.displayName,
                        meta = entry.paneMeta(),
                        status = entry.pane.agentStatus,
                        ageMinutes = entry.statusSince?.let {
                            (Instant.now().toEpochMilli() - it.toEpochMilli())
                                .coerceAtLeast(0) / 60_000
                        },
                        ageExact = entry.statusSinceExact,
                        nextBlocked = blocked.firstOrNull(),
                        blockedRemaining = blocked.size,
                    )

                    // The agent moving off Blocked after we answered is the only
                    // confirmation available that the answer landed.
                    val phase = _uiState.value.phase
                    if (wasBlocked && entry.pane.agentStatus != AgentStatus.Blocked &&
                        phase is ReplyPhase.Sent
                    ) {
                        _uiState.value = _uiState.value.copy(
                            phase = ReplyPhase.Resumed(phase.answer),
                        )
                    }
                    if (entry.pane.agentStatus == AgentStatus.Blocked && !wasBlocked) {
                        refreshPrompt()
                    }
                }
        }

        // Polled, because herdr offers no way to be told that a pane changed.
        //
        // `pane.output_changed` is a delivered event type with no subscription filter, so
        // it can never arrive. `pane.output_matched` fires once at subscribe time against
        // existing content and never again. `pane.wait_for_output` does block, but only
        // until its pattern matches, and a catch-all pattern matches what is already on
        // screen, so it returns instantly. `pane.read` reports revision 0 every time, so
        // that is no use for change detection either. All four were tried against herdr
        // 0.9.0 before settling on this.
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { herdr.readPane(machineId, paneId, ansi = true, source = "visible") }
                .onSuccess { read ->
                    // Only touch state when the screen actually changed. A poll that
                    // rewrites identical text recomposes the terminal and fights the
                    // user's scroll position every couple of seconds.
                    if (read.text == _uiState.value.text) {
                        _uiState.value = _uiState.value.copy(error = null)
                        return@onSuccess
                    }
                    _uiState.value = _uiState.value.copy(
                        text = read.text,
                        snapshotAt = Instant.now(),
                        error = null,
                    )
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(error = t.message ?: "read failed")
                }
            if (_uiState.value.isBlocked) refreshPrompt()
        }
    }

    /**
     * Re-reads the detection slice and re-parses the prompt.
     *
     * Uses `detection` rather than `visible` because that is the text herdr itself uses to
     * decide an agent is blocked, so it is already trimmed to the question.
     */
    private fun refreshPrompt() {
        viewModelScope.launch {
            runCatching {
                herdr.readPane(machineId, paneId, ansi = false, source = "detection")
            }.onSuccess { read ->
                _uiState.value = _uiState.value.copy(prompt = PromptParser.parse(read.text))
            }
        }
    }

    /** Sends an option key. Never the label: the label is shortened for display. */
    fun sendOption(option: PromptOption) {
        send(option.key, answerLabel = option.label, thenCompose = option.thenCompose)
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        send(text, answerLabel = text, thenCompose = false, viaPrompt = true)
    }

    private fun send(
        payload: String,
        answerLabel: String,
        thenCompose: Boolean,
        viaPrompt: Boolean = false,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sending = true)
            val result = runCatching {
                if (viaPrompt) {
                    herdr.promptAgent(machineId, paneId, payload)
                } else {
                    herdr.sendText(machineId, paneId, "$payload\n")
                }
            }
            _uiState.value = when {
                result.isFailure -> _uiState.value.copy(
                    sending = false,
                    error = result.exceptionOrNull()?.message ?: "send failed",
                )

                // "No, and tell Claude…" answers the menu and then wants prose, so the
                // sheet switches to the composer instead of reporting a completed send.
                thenCompose -> _uiState.value.copy(
                    sending = false,
                    composing = true,
                    composerQuestion = "Tell Claude what to do instead",
                )

                else -> _uiState.value.copy(
                    sending = false,
                    phase = ReplyPhase.Sent(answerLabel),
                    composing = false,
                )
            }
            refresh()
        }
    }

    fun sendKeys(keys: String) {
        viewModelScope.launch {
            runCatching { herdr.sendKeys(machineId, paneId, keys) }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(error = t.message ?: "send failed")
                }
            refresh()
        }
    }

    fun toggleComposing() {
        _uiState.value = _uiState.value.copy(
            composing = !_uiState.value.composing,
            composerQuestion = null,
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/**
 * How often an open pane is re-read.
 *
 * Two seconds is a compromise: one SSH exec channel per poll, only while a pane is on
 * screen, against output that feels current enough to answer an agent from.
 */
private const val POLL_INTERVAL_MS = 2_000L

/** "Permission · macbook · api-bugs › solver · ⎇ fix-branch" */
internal fun AgentEntry.paneMeta(): String = buildString {
    if (pane.agentStatus == AgentStatus.Blocked) {
        prompt?.label?.let { append("$it · ") }
    }
    append(machineLabel)
    workspace?.displayName?.let { append(" · $it") }
    tab?.meaningfulLabel?.let { append(" › $it") }
    workspace?.worktree?.takeIf { it.isNotBlank() }?.let { append(" · ⎇ $it") }
}
