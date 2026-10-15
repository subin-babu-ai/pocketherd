package dev.pocketherd.data.di

import dev.pocketherd.core.database.MachineDao
import dev.pocketherd.core.model.AuthMethod
import dev.pocketherd.core.model.Machine
import dev.pocketherd.core.protocol.HerdrCodec
import dev.pocketherd.core.protocol.Methods
import dev.pocketherd.core.protocol.Pong
import dev.pocketherd.core.ssh.KeystoreIdentity
import dev.pocketherd.core.ssh.SshAuth
import dev.pocketherd.core.ssh.SshConnection
import dev.pocketherd.core.ssh.SshTransport
import dev.pocketherd.core.ssh.StoredHostKeyVerifier
import dev.pocketherd.data.HerdrClient
import dev.pocketherd.data.HerdrRepositoryImpl
import dev.pocketherd.data.MachineRepositoryImpl
import dev.pocketherd.data.SshConnector
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pocketherd.domain.HerdrRepository
import dev.pocketherd.domain.MachineRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun machineRepository(impl: MachineRepositoryImpl): MachineRepository

    @Binds
    @Singleton
    abstract fun herdrRepository(impl: HerdrRepositoryImpl): HerdrRepository

    @Binds
    @Singleton
    abstract fun sshConnector(impl: SshConnectorImpl): SshConnector
}

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @Singleton
    fun io(): CoroutineDispatcher = Dispatchers.IO
}

/**
 * Builds an authenticated [HerdrClient] for a machine.
 *
 * The relay probe doubles as the health check: a candidate only wins if `ping` comes back
 * as a parseable pong, which proves the host has a working relay, the socket path is right,
 * and herdr is actually running. The winner is written back to the database so the next
 * connect skips straight to it.
 */
@Singleton
class SshConnectorImpl @Inject constructor(
    private val dao: MachineDao,
    private val io: CoroutineDispatcher,
) : SshConnector {

    override suspend fun connect(machine: Machine): HerdrClient = withContext(io) {
        val row = dao.get(machine.id)
        val transport = openTransport(machine, visited = emptySet())

        val connection = SshConnection.connect(
            transport = transport,
            host = machine.host,
            socketPath = SshConnector.socketPathFor(machine),
            preferredRelay = row?.lastRelay,
        ) { channel ->
            val request = HerdrCodec.request(Methods.PING)
            channel.writeLine(HerdrCodec.encode(request))
            val line = channel.readLine() ?: return@connect false
            runCatching {
                val pong: Pong = HerdrCodec.decodeResult(HerdrCodec.decodeResponse(line))
                pong.type == "pong"
            }.getOrDefault(false)
        }

        Log.i("PocketHerd", "${machine.label}: relay=${connection.relayName}, hops=${transport.hops}")
        if (connection.relayName != row?.lastRelay) {
            dao.rememberRelay(machine.id, connection.relayName)
        }
        HerdrClient(connection, io)
    }

    /**
     * Opens the SSH transport for [machine], recursively opening any jump host first.
     *
     * [visited] breaks reference cycles. Two machines each set as the other's jump host is
     * an easy thing to configure by accident and would otherwise recurse until the stack
     * blew, with no hint of why.
     */
    private suspend fun openTransport(machine: Machine, visited: Set<Long>): SshTransport {
        check(machine.id !in visited) {
            "Jump-host loop through ${machine.label}. A machine cannot be reached via itself."
        }

        val jump = machine.via?.let { viaId ->
            val viaMachine = dao.get(viaId)?.let { row ->
                Machine(
                    id = row.id,
                    label = row.label,
                    host = row.host,
                    port = row.port,
                    username = row.username,
                    socketPath = row.socketPath,
                    auth = runCatching { AuthMethod.valueOf(row.authMethod) }
                        .getOrDefault(AuthMethod.Keystore),
                    via = row.viaMachineId,
                )
            } ?: error("${machine.label} is configured to connect via a machine that was deleted")

            openTransport(viaMachine, visited + machine.id)
        }

        val row = dao.get(machine.id)
        return SshConnection.openTransport(
            host = machine.host,
            port = machine.port,
            username = machine.username,
            auth = authFor(machine),
            // Each hop is verified against its own stored fingerprint.
            verifier = StoredHostKeyVerifier(row?.hostKeyFingerprint),
            via = jump,
        )
    }

    private fun authFor(machine: Machine): SshAuth = when (machine.auth) {
        AuthMethod.Keystore -> SshAuth.Key(KeystoreIdentity.loadOrCreate())
        AuthMethod.Password -> error(
            "Password auth needs a prompt; wire it to the UI before enabling it.",
        )
    }
}
