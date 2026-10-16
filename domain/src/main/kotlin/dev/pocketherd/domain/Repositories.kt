package dev.pocketherd.domain

import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.Machine
import dev.pocketherd.core.model.PaneRead
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Saved SSH machines. Credentials never live here; see the keystore notes in :core:ssh. */
interface MachineRepository {
    fun observeMachines(): Flow<List<Machine>>
    suspend fun upsert(machine: Machine): Long
    suspend fun delete(id: Long)
    suspend fun get(id: Long): Machine?

    /** Records a host key the user approved. Only called after they have seen it. */
    suspend fun trustHostKey(id: Long, fingerprint: String)
}

/**
 * One machine's herdr session.
 *
 * [observeAgents] is the live view: a snapshot on connect, then incremental updates driven
 * by the event stream. It never completes while collected; a dropped connection surfaces as
 * a [ConnectionState] change rather than an error, because reconnect is expected on a phone
 * that switches between wifi and cellular.
 */
interface HerdrRepository {
    fun observeAgents(machineId: Long): Flow<List<AgentEntry>>
    fun observeConnection(machineId: Long): Flow<ConnectionState>

    /**
     * Reads a pane. [ansi] keeps SGR colour escapes.
     *
     * [source] is herdr's read source: "visible" for what is on screen, "detection" for the
     * slice herdr uses to spot a prompt, which is what the parser wants.
     */
    suspend fun readPane(
        machineId: Long,
        paneId: String,
        ansi: Boolean = true,
        source: String = "visible",
    ): PaneRead

    /** Emits each time herdr reports new output for this pane, carrying the new revision. */
    fun observePaneRevision(machineId: Long, paneId: String): Flow<Long>

    suspend fun sendText(machineId: Long, paneId: String, text: String)
    suspend fun sendKeys(machineId: Long, paneId: String, keys: String)

    /** Sends a prompt answer through `agent.prompt`, which waits for the agent to take it. */
    suspend fun promptAgent(machineId: Long, paneId: String, text: String)

    /**
     * Approves the host key a machine is currently blocked on and reconnects it.
     * The fingerprint is passed back in so approving a stale prompt cannot trust a key the
     * user never saw.
     */
    suspend fun approveHostKey(machineId: Long, fingerprint: String)

    /** Clears the unread flag for one agent, e.g. when its pane is opened. */
    suspend fun markSeen(machineId: Long, paneId: String)

    suspend fun markAllSeen()

    /** When the agent list was last on screen, used for "since you looked at HH:mm". */
    val lastSeenAt: Flow<Instant?>

    suspend fun recordLookedAt(at: Instant)
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    data class Connected(val herdrVersion: String, val protocol: Int, val relay: String) :
        ConnectionState

    /**
     * Waiting on the user to confirm a host key. The session stops here rather than
     * retrying, because no amount of backoff turns an unknown key into a known one.
     */
    data class NeedsHostKeyApproval(val hostname: String, val fingerprint: String) :
        ConnectionState

    /**
     * The stored host key no longer matches. Separate from [Failed] because this one is
     * never retried: it means the server was rebuilt or something is intercepting.
     */
    data class HostKeyChanged(
        val hostname: String,
        val expected: String,
        val actual: String,
    ) : ConnectionState

    data class Failed(val reason: String) : ConnectionState
}
