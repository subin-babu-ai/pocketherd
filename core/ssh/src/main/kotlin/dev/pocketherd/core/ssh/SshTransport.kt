package dev.pocketherd.core.ssh

import net.schmizz.sshj.SSHClient
import java.io.Closeable

/**
 * One authenticated SSH hop, plus the hop it was reached through.
 *
 * A jump chain is a linked list rather than a flat list so closing the far end tears down
 * every hop behind it. Leaking the intermediate connection would keep an SSH session alive
 * on the jump host with nothing using it.
 */
class SshTransport(
    val client: SSHClient,
    private val via: SshTransport? = null,
) : Closeable {

    /** Hops between the phone and this machine. 0 for a direct connection. */
    val hops: Int get() = (via?.hops ?: -1) + 1

    override fun close() {
        runCatching { client.disconnect() }
        via?.close()
    }
}
