package dev.pocketherd.data

import dev.pocketherd.core.model.Machine
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.PaneRead
import dev.pocketherd.core.model.Workspace
import dev.pocketherd.core.protocol.HerdrCodec
import dev.pocketherd.core.protocol.Methods
import dev.pocketherd.core.protocol.Pong
import dev.pocketherd.core.protocol.Protocol
import dev.pocketherd.core.protocol.Snapshot
import dev.pocketherd.core.ssh.HostKeyChangedException
import dev.pocketherd.core.ssh.UnknownHostKeyException
import dev.pocketherd.core.ssh.findCause
import android.util.Log
import dev.pocketherd.domain.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import java.time.Instant
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One machine's live session: connect, bootstrap from a snapshot, then follow events.
 *
 * The reconnect loop exists because this runs on a phone. Switching between wifi and
 * cellular drops the SSH transport, and the correct response is to rebuild state from a
 * fresh snapshot rather than to surface an error, so [state] stays authoritative.
 */
class MachineSession(
    private val machine: Machine,
    private val scope: CoroutineScope,
    private val connect: suspend (Machine) -> HerdrClient,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** Pane revision bumps, consumed by whichever terminal view is open. */
    private val _revisions = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)
    val revisions: SharedFlow<Pair<String, Long>> = _revisions

    private var client: HerdrClient? = null

    /** Loaded once before the first connect; see [bootstrapMeta]. */
    private var storedRecords: Map<String, AgentMemoryStore.Record> = emptyMap()

    fun seedMemory(records: Map<String, AgentMemoryStore.Record>) {
        storedRecords = records
    }

    /**
     * Wakes a session parked on a host key prompt. Conflated: tapping Trust twice is one
     * reconnect, and a signal sent before the session parks is not lost.
     */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    private var job: Job? = null

    fun start() {
        job = scope.launch { runLoop() }
    }

    /** Tears the session down. Called when the machine is edited or removed. */
    fun stop() {
        job?.cancel()
        job = null
        client?.close()
        client = null
        _connection.value = ConnectionState.Disconnected
    }

    fun retry() {
        retrySignal.trySend(Unit)
    }

    fun markSeen(paneId: String) {
        _state.value = _state.value.markSeen(paneId)
    }

    fun markAllSeen() {
        _state.value = _state.value.markAllSeen()
    }

    /**
     * Reconnects with capped exponential backoff, 1s doubling to 30s. The cap matters more
     * than the curve: a phone that wakes in a tunnel should not hammer SSH, but it also
     * should not take minutes to notice the network came back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            try {
                _connection.value = ConnectionState.Connecting
                val c = connect(machine).also { client = it }

                Log.i(TAG, "${machine.label}: connected, pinging")
                val pong: Pong = HerdrCodec.decodeResult(c.call(Methods.PING))
                check(Protocol.isSupported(pong.protocol)) {
                    "herdr ${pong.version} speaks protocol ${pong.protocol}; " +
                        "this client needs ${Protocol.VERIFIED} or newer"
                }

                val snapshot: Snapshot =
                    HerdrCodec.decodeResultField(c.call(Methods.SESSION_SNAPSHOT), "snapshot")
                val connectedAt = Instant.now()
                _state.value = SessionState(
                    panes = snapshot.panes.associateBy { it.paneId },
                    workspaces = snapshot.workspaces.associateBy { it.workspaceId },
                    tabs = snapshot.tabs.associateBy { it.tabId },
                    meta = bootstrapMeta(snapshot.panes, connectedAt),
                )
                Log.i(
                    TAG,
                    "${machine.label}: snapshot ${snapshot.panes.size} panes " +
                        "(${snapshot.panes.count { it.isAgent }} agents), " +
                        "${snapshot.workspaces.size} workspaces",
                )
                _connection.value = ConnectionState.Connected(
                    herdrVersion = pong.version,
                    protocol = pong.protocol,
                    relay = machine.label,
                )
                backoffMs = 1_000L

                follow(c)
            } catch (t: Throwable) {
                if (!scope.isActive) return
                client = null

                // SSHJ wraps verifier failures in a TransportException during key
                // exchange, so match on the cause chain rather than the thrown type.
                val unknownHost = t.findCause<UnknownHostKeyException>()
                val changedHost = t.findCause<HostKeyChangedException>()

                when {
                    changedHost != null -> {
                        // Never retried. A changed key means the server was rebuilt or
                        // someone is in the middle; both need a human, not a backoff.
                        _connection.value = ConnectionState.HostKeyChanged(
                            hostname = changedHost.hostname,
                            expected = changedHost.expected,
                            actual = changedHost.actual,
                        )
                        return
                    }

                    unknownHost != null -> {
                        _connection.value = ConnectionState.NeedsHostKeyApproval(
                            hostname = unknownHost.hostname,
                            fingerprint = unknownHost.fingerprint,
                        )
                        // Park until the user decides. Retrying on a timer would just
                        // rediscover the same unknown key forever.
                        retrySignal.receive()
                        backoffMs = 1_000L
                        continue
                    }

                    else -> {
                        Log.w(TAG, "${machine.label}: connection failed", t)
                        _connection.value =
                            ConnectionState.Failed(t.message ?: t::class.simpleName.orEmpty())
                    }
                }
            }
            client = null
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /**
     * Establishes status timing for panes present at connect.
     *
     * Three cases. A stored record whose status still matches is trusted outright, which is
     * what lets an age survive a restart. A stored record that disagrees means the status
     * changed while the app was not watching, so the change is dated to the connection and
     * marked inexact. A pane with no record at all gets the same treatment.
     *
     * Anything already Done before the first connect starts as seen rather than unread; a
     * fresh install should not open with twelve notifications about work that finished last
     * week.
     */
    private fun bootstrapMeta(panes: List<Pane>, connectedAt: Instant): Map<String, PaneMeta> =
        panes.associate { pane ->
            val stored = storedRecords[AgentMemoryStore.key(machine.id, pane.paneId)]
            val meta = when {
                stored != null && AgentMemoryStore.statusOf(stored) == pane.agentStatus ->
                    PaneMeta(
                        statusSince = Instant.ofEpochMilli(stored.sinceEpochMs),
                        exact = stored.exact,
                        unread = !stored.seen &&
                            (pane.agentStatus == AgentStatus.Done ||
                                pane.agentStatus == AgentStatus.Blocked),
                    )

                else -> PaneMeta(
                    statusSince = connectedAt,
                    exact = false,
                    // A status we never saw arrive is not news on a first connect.
                    unread = stored != null &&
                        (pane.agentStatus == AgentStatus.Done ||
                            pane.agentStatus == AgentStatus.Blocked),
                )
            }
            pane.paneId to meta
        }

    /**
     * Follows the event stream until it ends or a pane appears that the current
     * subscription does not cover. Returning on a new pane is deliberate: per-pane
     * agent-status filters are fixed when `events.subscribe` is called, so widening the set
     * means opening a new subscription.
     */
    private suspend fun follow(client: HerdrClient) {
        while (scope.isActive) {
            try {
                client.events(_state.value.panes.keys.toSet()).collect { envelope ->
                    val needsWiderSubscription = _state.value.needsResubscribe(envelope)
                    _state.value = _state.value.reduce(envelope)

                    val paneId = envelope.data.paneId ?: envelope.data.pane?.paneId
                    val revision = envelope.data.revision
                    if (paneId != null && revision != null) {
                        _revisions.tryEmit(paneId to revision)
                    }

                    if (needsWiderSubscription) throw ResubscribeSignal()
                }
                // The stream ended on its own, so the connection is gone. Let the caller
                // reconnect and rebuild from a fresh snapshot.
                return
            } catch (resubscribe: ResubscribeSignal) {
                // The SSH connection is healthy and state is current; only the filter set
                // is stale. Reopen just the subscription channel, no backoff, no snapshot.
                continue
            }
        }
    }

    suspend fun readPane(paneId: String, ansi: Boolean): PaneRead {
        val c = client ?: error("not connected to ${machine.label}")
        val params = buildJsonObject {
            put("pane_id", paneId)
            put("source", "visible")
            put("format", if (ansi) "ansi" else "text")
            put("strip_ansi", !ansi)
        }
        return HerdrCodec.decodeResultField(c.call(Methods.PANE_READ, params), "read")
    }

    suspend fun sendText(paneId: String, text: String) {
        val c = client ?: error("not connected to ${machine.label}")
        val params = buildJsonObject {
            put("pane_id", paneId)
            put("text", text)
        }
        HerdrCodec.resultOrThrow(c.call(Methods.PANE_SEND_TEXT, params))
    }

    suspend fun sendKeys(paneId: String, keys: String) {
        val c = client ?: error("not connected to ${machine.label}")
        val params = buildJsonObject {
            put("pane_id", paneId)
            put("keys", keys)
        }
        HerdrCodec.resultOrThrow(c.call(Methods.PANE_SEND_KEYS, params))
    }

    /** Thrown to unwind the event collector so the loop re-subscribes with a wider filter set. */
    private class ResubscribeSignal : Exception("pane added; widening subscription")

    private companion object {
        const val TAG = "PocketHerd"
    }
}
