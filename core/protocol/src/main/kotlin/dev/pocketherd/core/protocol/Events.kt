package dev.pocketherd.core.protocol

import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.Workspace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subscription filters for `events.subscribe`.
 *
 * Two traps, both verified against herdr 0.9.0 rather than read off the docs:
 *
 *  1. The string you *subscribe* with is dotted ("pane.agent_status_changed"). The string
 *     that comes *back* on the event is underscored ("pane_agent_status_changed"). Sending
 *     the underscored form is rejected with `unknown variant`.
 *  2. `pane.agent_status_changed` requires a `pane_id` and has no wildcard. Agent status is
 *     the one thing this app exists to watch, so the client subscribes to every known pane
 *     by id and re-subscribes when `pane_created` announces a new one.
 */
@Serializable
data class Subscription(
    val type: String,
    @SerialName("pane_id") val paneId: String? = null,
)

/** Dotted names, used only when subscribing. */
object EventFilters {
    const val PANE_CREATED = "pane.created"
    const val PANE_CLOSED = "pane.closed"
    const val PANE_UPDATED = "pane.updated"
    const val PANE_EXITED = "pane.exited"
    const val PANE_AGENT_DETECTED = "pane.agent_detected"
    const val WORKSPACE_CREATED = "workspace.created"
    const val WORKSPACE_UPDATED = "workspace.updated"
    const val WORKSPACE_CLOSED = "workspace.closed"

    /** Requires a pane_id. */
    const val PANE_AGENT_STATUS_CHANGED = "pane.agent_status_changed"

    /** Accepted with no extra fields, so one entry each covers every pane. */
    val GLOBAL = listOf(
        PANE_CREATED,
        PANE_CLOSED,
        PANE_UPDATED,
        PANE_EXITED,
        PANE_AGENT_DETECTED,
        WORKSPACE_CREATED,
        WORKSPACE_UPDATED,
        WORKSPACE_CLOSED,
    )

    /** Every global filter plus one agent-status filter per pane we currently know about. */
    fun forPanes(paneIds: Collection<String>): List<Subscription> =
        GLOBAL.map { Subscription(it) } +
            paneIds.map { Subscription(PANE_AGENT_STATUS_CHANGED, paneId = it) }
}

/** Underscored names, as delivered on the wire. */
object EventTypes {
    const val PANE_CREATED = "pane_created"
    const val PANE_CLOSED = "pane_closed"
    const val PANE_UPDATED = "pane_updated"
    const val PANE_EXITED = "pane_exited"
    const val PANE_AGENT_DETECTED = "pane_agent_detected"
    const val PANE_AGENT_STATUS_CHANGED = "pane_agent_status_changed"
    const val PANE_OUTPUT_CHANGED = "pane_output_changed"
    const val WORKSPACE_CREATED = "workspace_created"
    const val WORKSPACE_UPDATED = "workspace_updated"
    const val WORKSPACE_CLOSED = "workspace_closed"
}

@Serializable
data class SubscribeParams(val subscriptions: List<Subscription>)

/**
 * A delivered event: `{"event":"pane_created","data":{"type":"pane_created","pane":{...}}}`.
 *
 * [EventData] is a flat union rather than a sealed hierarchy. herdr emits 26 event types and
 * this client reacts to 7 of them; a sealed class would need all 26 declared to avoid
 * blowing up on the rest. Every field is optional and unknown types fall through untouched.
 */
@Serializable
data class HerdrEventEnvelope(
    val event: String,
    val data: EventData,
)

@Serializable
data class EventData(
    val type: String,
    @SerialName("pane_id") val paneId: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    val pane: Pane? = null,
    val workspace: Workspace? = null,
    /** Present on pane_agent_status_changed, so status updates need no follow-up fetch. */
    @SerialName("agent_status") val agentStatus: AgentStatus? = null,
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    val title: String? = null,
    /** Present on pane_output_changed: re-read the pane only when this moves. */
    val revision: Long? = null,
)
