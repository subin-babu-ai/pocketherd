package dev.pocketherd.core.ssh

import android.util.Log
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.BufferedReader
import java.io.Closeable
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * One live SSH connection to a machine running herdr.
 *
 * The connection is reused; individual requests are not. herdr's socket is
 * one-request-per-connection, which was verified by sending `ping` and `agent.list` down a
 * single pipe and getting exactly one response back. So every RPC opens its own exec
 * channel, and a subscription holds one open for as long as it is live. Channel setup is
 * cheap once the SSH transport is up, and the event stream carries most of the traffic
 * anyway, so this costs far less than the connection multiplexing it avoids.
 *
 * ponytail: no channel pooling. If per-request latency on cellular turns out to hurt,
 * the upgrade is a multiplexing relay script that frames requests over one channel.
 */
class SshConnection(
    private val transport: SshTransport,
    private val relay: RelayCommand.Relay,
    private val socketPath: String,
) : Closeable {

    private val client: SSHClient get() = transport.client

    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    /** Which relay won the probe, so the caller can skip straight to it next time. */
    val relayName: String get() = relay.name

    /**
     * Opens an exec channel running the relay and hands back a line-oriented pipe to herdr.
     * The caller owns the result and must close it.
     */
    fun openChannel(): HerdrChannel {
        val session = client.startSession()
        val command = session.exec(relay.render(socketPath))
        return HerdrChannel(session, command)
    }

    /** Closes this hop and every hop it was tunnelled through. */
    override fun close() = transport.close()

    companion object {
        /**
         * Connects, authenticates, then probes the relay candidates in order.
         *
         * The probe is the only way to know which of python3/socat/nc exists on a given box,
         * and it doubles as a protocol check: a relay that returns a parseable `pong` has
         * proved the socket path, the permissions, and that herdr is actually running.
         */
        /**
         * Opens an authenticated SSH transport, optionally tunnelled through [via].
         *
         * Separate from [connect] because a jump host is only a transport: it carries the
         * TCP connection and never needs herdr installed, so it must not be relay-probed.
         *
         * The host key of every hop is verified with that hop's own verifier. Tunnelling
         * does not make the far end's identity someone else's problem.
         */
        fun openTransport(
            host: String,
            port: Int,
            username: String,
            auth: SshAuth,
            verifier: HostKeyVerifier,
            connectTimeoutMs: Int = 15_000,
            via: SshTransport? = null,
        ): SshTransport {
            // Must run before SSHClient touches any algorithm, or key exchange picks
            // Android's stripped BC provider and fails on curve25519.
            SshSecurity.ensureInstalled()

            // Replace the stock ecdsa-sha2-nistp256 key algorithm with one whose signer
            // can use the Keystore key. Everything else in DefaultConfig is left alone.
            val config = DefaultConfig().apply {
                keyAlgorithms = keyAlgorithms.map { factory ->
                    if (factory.name == KeystoreKeyAlgorithm.Factory.name) {
                        KeystoreKeyAlgorithm.Factory
                    } else {
                        factory
                    }
                }
            }

            val client = SSHClient(config).apply {
                addHostKeyVerifier(verifier)
                connectTimeout = connectTimeoutMs
                timeout = connectTimeoutMs
                useCompression()
            }

            if (via == null) {
                client.connect(host, port)
            } else {
                // ProxyJump: ask the previous hop for a direct-tcpip channel to the target
                // and run this SSH session over it, exactly as `ssh -J` does.
                client.connectVia(via.client.newDirectConnection(host, port))
            }

            when (auth) {
                is SshAuth.Password -> client.authPassword(username, auth.password)
                is SshAuth.Key -> client.authPublickey(username, auth.keyProvider)
            }
            return SshTransport(client, via)
        }

        /**
         * Connects, authenticates, then probes the relay candidates in order.
         *
         * The probe is the only way to know which of python3/socat/nc exists on a given box,
         * and it doubles as a protocol check: a relay that returns a parseable `pong` has
         * proved the socket path, the permissions, and that herdr is actually running.
         */
        fun connect(
            transport: SshTransport,
            host: String,
            socketPath: String = RelayCommand.DEFAULT_SOCKET,
            preferredRelay: String? = null,
            probe: (HerdrChannel) -> Boolean,
        ): SshConnection {
            val candidates = buildList {
                preferredRelay?.let { name -> RelayCommand.byName(name)?.let(::add) }
                addAll(RelayCommand.CANDIDATES.filterNot { it.name == preferredRelay })
            }

            for (candidate in candidates) {
                val connection = SshConnection(transport, candidate, socketPath)
                var stderr = ""
                val ok = runCatching {
                    connection.openChannel().use { channel ->
                        val result = probe(channel)
                        if (!result) stderr = channel.stderr().take(400)
                        result
                    }
                }.onFailure { stderr = "${it::class.simpleName}: ${it.message}" }
                    .getOrDefault(false)

                if (ok) return connection
                // Without this the only symptom is "no usable relay", which hides whether
                // the interpreter is missing, the socket path is wrong, or herdr is down.
                Log.w("PocketHerd", "relay ${candidate.name} failed on $host: $stderr")
            }

            transport.close()
            error(
                "No usable relay on $host. herdr's socket needs python3, socat, or nc -U " +
                    "on the remote host, and herdr must be running."
            )
        }
    }
}

sealed interface SshAuth {
    data class Password(val password: CharArray) : SshAuth
    data class Key(val keyProvider: KeyProvider) : SshAuth
}

/**
 * A line-oriented pipe to herdr over one exec channel.
 *
 * Reads are blocking, so callers run this on an IO dispatcher. [readLine] returning null
 * means the far end closed, which for a subscription channel means the stream ended.
 */
class HerdrChannel(
    private val session: Session,
    private val command: Session.Command,
) : Closeable {

    private val writer = OutputStreamWriter(command.outputStream, Charsets.UTF_8)
    private val reader = BufferedReader(command.inputStream.reader(Charsets.UTF_8))

    fun writeLine(line: String) {
        writer.write(line)
        writer.flush()
    }

    fun readLine(): String? = reader.readLine()

    /** Reads whatever the relay wrote to stderr, used to explain a failed probe. */
    fun stderr(): String = runCatching {
        IOUtils.readFully(command.errorStream).toString()
    }.getOrDefault("")

    override fun close() {
        runCatching { writer.close() }
        runCatching { command.close() }
        runCatching { session.close() }
        runCatching { command.join(2, TimeUnit.SECONDS) }
    }
}
