package dev.pocketherd.data

import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.Workspace
import dev.pocketherd.core.protocol.EventTypes
import dev.pocketherd.core.protocol.HerdrEventEnvelope

/**
 * The client-side mirror of one machine's herdr session.
 *
 * Built once from `session.snapshot`, then advanced by events. Keeping it immutable makes
 * the reducer a pure function, which is the only reason the event handling can be tested
 * without an SSH connection or a running herdr.
 */
data class SessionState(
    val panes: Map<String, Pane> = emptyMap(),
    val workspaces: Map<String, Workspace> = emptyMap(),
) {
    /**
     * Applies one event. Unknown event types return `this` unchanged, which is what keeps a
     * newer herdr from breaking an older client.
     */
    fun reduce(envelope: HerdrEventEnvelope): SessionState {
        val data = envelope.data
        return when (data.type) {
            EventTypes.PANE_CREATED,
            EventTypes.PANE_UPDATED,
            EventTypes.PANE_AGENT_DETECTED,
            -> data.pane?.let { copy(panes = panes + (it.paneId to it)) } ?: this

            EventTypes.PANE_CLOSED, EventTypes.PANE_EXITED ->
                data.paneId?.let { copy(panes = panes - it) } ?: this

            // Carries the new status inline, so no follow-up fetch is needed.
            EventTypes.PANE_AGENT_STATUS_CHANGED -> {
                val id = data.paneId ?: return this
                val status = data.agentStatus ?: return this
                val existing = panes[id] ?: Pane(paneId = id, workspaceId = data.workspaceId)
                copy(
                    panes = panes + (id to existing.copy(
                        agentStatus = status,
                        agent = data.agent ?: existing.agent,
                        displayAgent = data.displayAgent ?: existing.displayAgent,
                        title = data.title ?: existing.title,
                    )),
                )
            }

            // Only the revision moves; the pane list is unchanged. Terminal views watch this.
            EventTypes.PANE_OUTPUT_CHANGED -> {
                val id = data.paneId ?: return this
                val revision = data.revision ?: return this
                val existing = panes[id] ?: return this
                copy(panes = panes + (id to existing.copy(revision = revision)))
            }

            EventTypes.WORKSPACE_CREATED, EventTypes.WORKSPACE_UPDATED ->
                data.workspace?.let { copy(workspaces = workspaces + (it.workspaceId to it)) }
                    ?: this

            EventTypes.WORKSPACE_CLOSED ->
                data.workspaceId?.let { closed ->
                    copy(
                        workspaces = workspaces - closed,
                        panes = panes.filterValues { it.workspaceId != closed },
                    )
                } ?: this

            else -> this
        }
    }

    /**
     * True when the event introduced a pane the current subscription does not cover.
     * Per-pane agent-status filters are fixed at subscribe time, so this is the signal to
     * tear the subscription down and open a wider one.
     */
    fun needsResubscribe(envelope: HerdrEventEnvelope): Boolean =
        envelope.data.type == EventTypes.PANE_CREATED &&
            envelope.data.pane?.paneId?.let { it !in panes } == true
}
