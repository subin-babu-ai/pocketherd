package dev.pocketherd.core.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets SSHJ's crypto working on Android against a non-exportable Keystore key.
 *
 * Two problems have to be solved at once, and the obvious fix for each breaks the other.
 *
 * 1. Android registers a stripped provider under the name `BC`, missing X25519. Modern
 *    OpenSSH negotiates `curve25519-sha256` by default, so key exchange dies with
 *    `NoSuchAlgorithmException: no such algorithm: X25519 for provider BC` once the TCP
 *    connection has already succeeded. Fixed by replacing that provider with the full
 *    BouncyCastle bundled in the APK.
 *
 * 2. SSHJ then pins *every* algorithm lookup to `BC`, including the signature used for
 *    public key auth. BouncyCastle cannot sign with this app's identity, because an
 *    AndroidKeyStore private key has no encoding to hand it: `InvalidKeyException: no
 *    encoding for EC private key`. That is the key being non-exportable working as
 *    intended, not a fault.
 *
 * So BouncyCastle is registered but SSHJ is told not to pin to it. Algorithm lookups then
 * walk the default provider chain: `Signature.getInstance` defers provider selection until
 * it sees the key and picks AndroidKeyStore for ours, while X25519, which nothing else
 * offers, still resolves to BouncyCastle.
 *
 * The provider is appended rather than inserted at position 1 for the same reason: taking
 * priority over AndroidOpenSSL and AndroidKeyStore would route signing back to BouncyCastle
 * and reintroduce problem 2.
 */
object SshSecurity {

    private val installed = AtomicBoolean(false)

    fun ensureInstalled() {
        if (!installed.compareAndSet(false, true)) return

        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())

        // Stop SSHJ pinning everything to "BC"; let each algorithm find its own provider.
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.setSecurityProvider(null)
    }
}
