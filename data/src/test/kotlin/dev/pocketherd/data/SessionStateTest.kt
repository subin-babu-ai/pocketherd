package dev.pocketherd.data

import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.protocol.HerdrCodec
import dev.pocketherd.core.protocol.HerdrEventEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event payloads here are the real wire shapes from `herdr api schema --json` on herdr
 * 0.9.0, not invented ones. If herdr changes the envelope, these fail rather than the UI
 * silently going stale.
 */
class SessionStateTest {

    private fun event(json: String): HerdrEventEnvelope =
        HerdrCodec.json.decodeFromString(HerdrEventEnvelope.serializer(), json)

    @Test
    fun `pane_created adds the pane`() {
        val state = SessionState().reduce(
            event(
                """{"event":"pane_created","data":{"type":"pane_created",
                   "pane":{"pane_id":"w3:p1","workspace_id":"w3","agent":"claude",
                   "agent_status":"idle","revision":1}}}""",
            ),
        )
        assertEquals(1, state.panes.size)
        assertEquals("claude", state.panes["w3:p1"]?.agent)
        assertEquals(AgentStatus.Idle, state.panes["w3:p1"]?.agentStatus)
    }

    @Test
    fun `agent status change updates in place without dropping the agent name`() {
        val created = SessionState().reduce(
            event(
                """{"event":"pane_created","data":{"type":"pane_created",
                   "pane":{"pane_id":"w3:p1","workspace_id":"w3","agent":"claude",
                   "agent_status":"working"}}}""",
            ),
        )
        // The status event carries no `agent` field; the existing name must survive.
        val blocked = created.reduce(
            event(
                """{"event":"pane_agent_status_changed","data":{
                   "type":"pane_agent_status_changed","pane_id":"w3:p1",
                   "workspace_id":"w3","agent_status":"blocked"}}""",
            ),
        )
        assertEquals(AgentStatus.Blocked, blocked.panes["w3:p1"]?.agentStatus)
        assertEquals("claude", blocked.panes["w3:p1"]?.agent)
    }

    @Test
    fun `pane_closed removes the pane`() {
        val state = SessionState()
            .reduce(
                event(
                    """{"event":"pane_created","data":{"type":"pane_created",
                       "pane":{"pane_id":"w3:p1","agent_status":"idle"}}}""",
                ),
            )
            .reduce(
                event(
                    """{"event":"pane_closed","data":{"type":"pane_closed","pane_id":"w3:p1"}}""",
                ),
            )
        assertTrue(state.panes.isEmpty())
    }

    @Test
    fun `output_changed moves only the revision`() {
        val created = SessionState().reduce(
            event(
                """{"event":"pane_created","data":{"type":"pane_created",
                   "pane":{"pane_id":"w3:p1","agent":"codex","agent_status":"working",
                   "revision":7}}}""",
            ),
        )
        val bumped = created.reduce(
            event(
                """{"event":"pane_output_changed","data":{"type":"pane_output_changed",
                   "pane_id":"w3:p1","workspace_id":"w3","revision":8}}""",
            ),
        )
        assertEquals(8L, bumped.panes["w3:p1"]?.revision)
        assertEquals(AgentStatus.Working, bumped.panes["w3:p1"]?.agentStatus)
        assertEquals("codex", bumped.panes["w3:p1"]?.agent)
    }

    @Test
    fun `workspace_closed drops the workspace and its panes`() {
        val state = SessionState()
            .reduce(
                event(
                    """{"event":"workspace_created","data":{"type":"workspace_created",
                       "workspace":{"workspace_id":"w3","label":"api"}}}""",
                ),
            )
            .reduce(
                event(
                    """{"event":"pane_created","data":{"type":"pane_created",
                       "pane":{"pane_id":"w3:p1","workspace_id":"w3","agent_status":"idle"}}}""",
                ),
            )
            .reduce(
                event(
                    """{"event":"workspace_closed","data":{"type":"workspace_closed",
                       "workspace_id":"w3"}}""",
                ),
            )
        assertTrue(state.workspaces.isEmpty())
        assertTrue(state.panes.isEmpty())
    }

    @Test
    fun `an unknown event type leaves state untouched`() {
        val before = SessionState().reduce(
            event(
                """{"event":"pane_created","data":{"type":"pane_created",
                   "pane":{"pane_id":"w3:p1","agent_status":"idle"}}}""",
            ),
        )
        // layout_updated is real but unhandled; a future herdr will add more like it.
        val after = before.reduce(
            event("""{"event":"layout_updated","data":{"type":"layout_updated"}}"""),
        )
        assertEquals(before, after)
    }

    @Test
    fun `a new pane triggers resubscribe because per-pane filters are fixed at subscribe time`() {
        val state = SessionState()
        val fresh = event(
            """{"event":"pane_created","data":{"type":"pane_created",
               "pane":{"pane_id":"w9:p1","agent_status":"idle"}}}""",
        )
        assertTrue(state.needsResubscribe(fresh))
        assertFalse(state.reduce(fresh).needsResubscribe(fresh))
    }

    @Test
    fun `status sorting puts blocked agents first`() {
        val order = listOf(
            AgentStatus.Idle,
            AgentStatus.Blocked,
            AgentStatus.Unknown,
            AgentStatus.Working,
            AgentStatus.Done,
        ).sortedBy { it.attentionRank }

        assertEquals(
            listOf(
                AgentStatus.Blocked,
                AgentStatus.Done,
                AgentStatus.Working,
                AgentStatus.Idle,
                AgentStatus.Unknown,
            ),
            order,
        )
    }

    @Test
    fun `error envelope decodes even with the empty id herdr sends for malformed requests`() {
        val response = HerdrCodec.decodeResponse(
            """{"id":"","error":{"code":"invalid_request","message":"missing field pane_id"}}""",
        )
        assertEquals("", response.id)
        assertEquals("invalid_request", response.error?.code)
        assertNull(response.result)
    }
}
