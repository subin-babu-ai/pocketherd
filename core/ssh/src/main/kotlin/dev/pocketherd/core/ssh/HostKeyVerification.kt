package dev.pocketherd.core.ssh

import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * Trust-on-first-use host key checking, with the first use made explicit.
 *
 * Accepting any host key would hand every keystroke to whoever answers on port 22, and this
 * app sends prompts to coding agents that can run commands. So an unknown host is a hard
 * stop: [UnknownHostKeyException] carries the fingerprint up to the UI, the user approves
 * it, and the fingerprint is stored. A key that changes after that is a failure, not a
 * second prompt.
 */
class StoredHostKeyVerifier(
    private val expectedFingerprint: String?,
    private val onUnknownHost: (fingerprint: String) -> Boolean = { false },
    /**
     * Injectable so the decision logic can be tested on the JVM. SSHJ's digest reaches
     * android.util classes, which the stubbed android.jar in unit tests refuses to run.
     */
    private val fingerprintOf: (PublicKey) -> String = SecurityUtils::getFingerprint,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = fingerprintOf(key)
        return when (expectedFingerprint) {
            null -> onUnknownHost(fingerprint) ||
                throw UnknownHostKeyException(hostname, port, fingerprint)

            fingerprint -> true

            else -> throw HostKeyChangedException(hostname, expectedFingerprint, fingerprint)
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

class UnknownHostKeyException(
    val hostname: String,
    val port: Int,
    val fingerprint: String,
) : Exception("$hostname:$port is not a known host (fingerprint $fingerprint)")

class HostKeyChangedException(
    val hostname: String,
    val expected: String,
    val actual: String,
) : Exception(
    "Host key for $hostname changed. Expected $expected but got $actual. " +
        "Either the server was rebuilt or the connection is being intercepted.",
)

/**
 * Walks a throwable's cause chain, including itself.
 *
 * SSHJ raises host key failures during key exchange and wraps whatever the verifier threw
 * in a `TransportException`, so catching [UnknownHostKeyException] directly never fires.
 * Callers search the chain instead of matching on the top-level type.
 *
 * Guards against a self-referencing cause, which `Throwable.initCause` permits and which
 * would otherwise spin forever.
 */
fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

inline fun <reified T : Throwable> Throwable.findCause(): T? =
    causeChain().filterIsInstance<T>().firstOrNull()
