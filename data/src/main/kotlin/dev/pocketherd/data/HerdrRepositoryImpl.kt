package dev.pocketherd.data

import dev.pocketherd.core.database.MachineDao
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AuthMethod
import dev.pocketherd.core.model.Machine
import dev.pocketherd.core.model.PaneRead
import dev.pocketherd.core.ssh.RelayCommand
import dev.pocketherd.domain.ConnectionState
import dev.pocketherd.domain.HerdrRepository
import dev.pocketherd.domain.MachineRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MachineRepositoryImpl @Inject constructor(
    private val dao: MachineDao,
) : MachineRepository {

    override fun observeMachines(): Flow<List<Machine>> =
        dao.observeAll().map { rows -> rows.map(::toDomain) }

    override suspend fun upsert(machine: Machine): Long {
        val existing = if (machine.id != 0L) dao.get(machine.id) else null

        // Editing where a machine points invalidates what we learned about it: a different
        // host has a different key, and may not have the same relay. Carrying the old
        // fingerprint across a host change would silently trust a machine nobody approved.
        val sameEndpoint = existing != null &&
            existing.host == machine.host && existing.port == machine.port

        return dao.upsert(
            dev.pocketherd.core.database.MachineEntity(
                id = machine.id,
                label = machine.label,
                host = machine.host,
                port = machine.port,
                username = machine.username,
                socketPath = machine.socketPath,
                authMethod = machine.auth.name,
                viaMachineId = machine.via,
                fallbackHosts = machine.fallbackHosts.joinToString("\n").ifBlank { null },
                // A new address list invalidates which one worked.
                lastGoodHost = if (sameEndpoint) existing.lastGoodHost else null,
                lastRelay = if (sameEndpoint) existing.lastRelay else null,
                hostKeyFingerprint = if (sameEndpoint) existing.hostKeyFingerprint else null,
            ),
        )
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun trustHostKey(id: Long, fingerprint: String) =
        dao.trustHostKey(id, fingerprint)

    override suspend fun get(id: Long): Machine? = dao.get(id)?.let(::toDomain)

    private fun toDomain(row: dev.pocketherd.core.database.MachineEntity) = Machine(
        id = row.id,
        label = row.label,
        host = row.host,
        port = row.port,
        username = row.username,
        socketPath = row.socketPath,
        auth = runCatching { AuthMethod.valueOf(row.authMethod) }.getOrDefault(AuthMethod.Keystore),
        via = row.viaMachineId,
        fallbackHosts = row.fallbackHosts?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList(),
        lastGoodHost = row.lastGoodHost,
    )
}

/**
 * Owns one [MachineSession] per saved machine and keeps them alive across screen changes.
 *
 * Sessions are application-scoped on purpose. Rebuilding a session means a fresh SSH
 * handshake, a relay probe, and a full snapshot; tying that to a ViewModel would redo all of
 * it every time the user rotates the phone or walks back from the terminal to the list.
 */
@Singleton
class HerdrRepositoryImpl @Inject constructor(
    private val machines: MachineRepository,
    private val connector: SshConnector,
    private val memory: AgentMemoryStore,
    private val io: CoroutineDispatcher,
) : HerdrRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)

    /**
     * Cached against the [Machine] it was built from. Editing a machine changes that value,
     * so the next lookup tears the old session down instead of quietly talking to the old
     * host forever.
     */
    private val sessions = ConcurrentHashMap<Long, Pair<Machine, MachineSession>>()
    private val sessionLock = Mutex()

    private suspend fun session(machineId: Long): MachineSession {
        val machine = machines.get(machineId) ?: error("machine $machineId is not saved")
        // getOrPut on ConcurrentHashMap can run its lambda twice under contention, which
        // here would mean two SSH connections to the same box. A mutex is cheaper than the
        // duplicate handshake it prevents.
        return sessionLock.withLock {
            sessions[machineId]?.let { (built, session) ->
                if (built == machine) return@withLock session
                session.stop()
            }
            MachineSession(
                machine = machine,
                scope = scope,
                connect = { connector.connect(it) },
            ).also {
                it.seedMemory(memory.records())
                it.start()
                sessions[machineId] = machine to it
                // Persist whatever the session learns, so ages and unread survive a
                // restart instead of every agent looking newly changed.
                scope.launch { persistOnChange(machineId, machine, it) }
            }
        }
    }

    override fun observeAgents(machineId: Long): Flow<List<AgentEntry>> =
        flowOfSession(machineId) { machine, session ->
            session.state.map { state ->
                state.panes.values.map { pane ->
                    val meta = state.meta[pane.paneId]
                    AgentEntry(
                        machineId = machine.id,
                        machineLabel = machine.label,
                        pane = pane,
                        workspace = pane.workspaceId?.let(state.workspaces::get),
                        tab = pane.tabId?.let(state.tabs::get),
                        // An upstream timestamp, once herdr sends one, beats anything the
                        // client inferred.
                        statusSince = pane.statusChangedAt?.let(::parseInstant)
                            ?: meta?.statusSince,
                        statusSinceExact = pane.statusChangedAt != null || meta?.exact == true,
                        unread = meta?.unread == true,
                    )
                }
            }
        }

    private fun parseInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()

    private suspend fun persistOnChange(
        machineId: Long,
        machine: Machine,
        session: MachineSession,
    ) {
        session.state.collect { state ->
            memory.put(
                state.panes.values.associate { pane ->
                    val meta = state.meta[pane.paneId]
                    AgentMemoryStore.key(machineId, pane.paneId) to AgentMemoryStore.Record(
                        status = pane.agentStatus.name,
                        sinceEpochMs = (meta?.statusSince ?: Instant.now()).toEpochMilli(),
                        exact = meta?.exact == true,
                        seen = meta?.unread != true,
                    )
                },
            )
        }
    }

    override suspend fun markSeen(machineId: Long, paneId: String) {
        sessions[machineId]?.second?.markSeen(paneId)
    }

    override suspend fun markAllSeen() {
        sessions.values.forEach { (_, session) -> session.markAllSeen() }
    }

    override val lastSeenAt: Flow<Instant?> get() = memory.lastSeenAt

    override suspend fun recordLookedAt(at: Instant) = memory.setLastSeenAt(at)

    override fun observeConnection(machineId: Long): Flow<ConnectionState> =
        flowOfSession(machineId) { _, session -> session.connection }

    override fun observePaneRevision(machineId: Long, paneId: String): Flow<Long> =
        flowOfSession(machineId) { _, session ->
            session.revisions.filter { it.first == paneId }.map { it.second }
        }

    override suspend fun readPane(
        machineId: Long,
        paneId: String,
        ansi: Boolean,
        source: String,
    ): PaneRead = session(machineId).readPane(paneId, ansi, source)

    override suspend fun promptAgent(machineId: Long, paneId: String, text: String) =
        session(machineId).promptAgent(paneId, text)

    override suspend fun sendText(machineId: Long, paneId: String, text: String) =
        session(machineId).sendText(paneId, text)

    override suspend fun sendKeys(machineId: Long, paneId: String, keys: String) =
        session(machineId).sendKeys(paneId, keys)

    override suspend fun approveHostKey(machineId: Long, fingerprint: String) {
        // Store first, then wake the session. The reverse order races: the reconnect can
        // reach verification before the fingerprint lands and prompt all over again.
        machines.trustHostKey(machineId, fingerprint)
        session(machineId).retry()
    }

    /** Defers session creation until collection, so looking up a machine can suspend. */
    private fun <T> flowOfSession(
        machineId: Long,
        block: (Machine, MachineSession) -> Flow<T>,
    ): Flow<T> = kotlinx.coroutines.flow.flow {
        val machine = machines.get(machineId) ?: return@flow
        emitAll(block(machine, session(machineId)))
    }
}

/** Opens an authenticated [HerdrClient]. Implemented in the SSH DI module. */
interface SshConnector {
    suspend fun connect(machine: Machine): HerdrClient

    companion object {
        fun socketPathFor(machine: Machine): String =
            machine.socketPath ?: RelayCommand.DEFAULT_SOCKET
    }
}
