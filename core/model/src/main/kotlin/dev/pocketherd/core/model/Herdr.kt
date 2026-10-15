package dev.pocketherd.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain types for herdr protocol 22.
 *
 * ponytail: these double as the wire format. This app has exactly one data source, so a
 * separate DTO layer plus mappers would be ceremony. If protocol 23 diverges from what the
 * UI wants, add mappers in :data then, not before.
 *
 * Field names and enum values were taken from `herdr api schema --json` on herdr 0.9.0,
 * not from the published docs. Unknown fields are ignored by the codec so a newer server
 * does not break an older client.
 */

@Serializable
enum class AgentStatus {
    @SerialName("idle") Idle,
    @SerialName("working") Working,
    @SerialName("blocked") Blocked,
    @SerialName("done") Done,
    @SerialName("unknown") Unknown;

    /**
     * Attention order used by the agent list. Blocked agents need a human right now, so they
     * sort first; unknown panes are plain shells and sort last.
     */
    val attentionRank: Int
        get() = when (this) {
            Blocked -> 0
            Done -> 1
            Working -> 2
            Idle -> 3
            Unknown -> 4
        }
}

@Serializable
data class Pane(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("tab_id") val tabId: String? = null,
    val agent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    @SerialName("display_agent") val displayAgent: String? = null,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val label: String? = null,
    val title: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val focused: Boolean = false,
    val revision: Long = 0,
    val tokens: Long? = null,
) {
    /** An agent pane is one herdr has detected a coding agent in; the rest are plain shells. */
    val isAgent: Boolean get() = agent != null

    /**
     * Headline for this pane.
     *
     * The terminal's window title comes first because coding agents set it to the task
     * they are on ("GitHub token retirement", "flaky integration test"). With nineteen panes all
     * running `claude`, the agent name distinguishes nothing and the title is the whole
     * point. herdr already strips the status glyph it prefixes, so
     * [terminalTitleStripped] is used rather than [terminalTitle].
     */
    val displayName: String
        get() = terminalTitleStripped?.takeIf { it.isNotBlank() }
            ?: label?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: displayAgent
            ?: agent
            ?: paneId

    /** Which CLI is running, shown next to the title rather than instead of it. */
    val agentLabel: String? get() = displayAgent ?: agent
}

@Serializable
data class Tab(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String? = null,
    val label: String? = null,
    val number: Int? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    @SerialName("pane_count") val paneCount: Int = 0,
    val focused: Boolean = false,
)

@Serializable
data class Workspace(
    @SerialName("workspace_id") val workspaceId: String,
    val label: String? = null,
    val number: Int? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    val focused: Boolean = false,
    val worktree: String? = null,
) {
    val displayName: String get() = label ?: workspaceId
}

/** Result of `pane.read`. [text] is a rendered screen snapshot, not a stream. */
@Serializable
data class PaneRead(
    @SerialName("pane_id") val paneId: String,
    val text: String,
    val format: String,
    val source: String,
    val revision: Long = 0,
    val truncated: Boolean = false,
)

/** A machine running herdr, reachable over SSH. */
data class Machine(
    val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val socketPath: String? = null,
    val auth: AuthMethod = AuthMethod.Keystore,
    /**
     * Id of a machine to tunnel through, or null to connect directly.
     *
     * Matches OpenSSH's ProxyJump: the hop is only a transport, so it does not need herdr
     * running on it. Used when a box is unreachable from the phone but reachable from
     * something the phone can already get to.
     */
    val via: Long? = null,
)

enum class AuthMethod { Keystore, Password }

/** One agent, resolved against the machine it lives on. The agent list is a list of these. */
data class AgentEntry(
    val machineId: Long,
    val machineLabel: String,
    val pane: Pane,
    val workspace: Workspace?,
)
