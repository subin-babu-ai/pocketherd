package dev.pocketherd.core.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * An SSH identity whose private key lives in the Android Keystore and never leaves it.
 *
 * The key is generated on-device and marked non-exportable, so a stolen phone yields no key
 * material and there is no passphrase to enter or leak. SSHJ only ever asks the key to
 * sign, which the Keystore does through its own `Signature` provider; it never reads the
 * bytes, which is what makes this work at all.
 *
 * ECDSA P-256 rather than Ed25519 because Keystore support for Ed25519 starts at API 33 and
 * this app targets API 29. The trade is one curve, not a weaker posture.
 *
 * The user pastes [openSshPublicKey] into `~/.ssh/authorized_keys` on each machine. That is
 * the one manual step, and it is the same step any new SSH client needs.
 */
class KeystoreIdentity private constructor(
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
) : KeyProvider {

    override fun getPrivate(): PrivateKey = privateKey
    override fun getPublic(): PublicKey = publicKey
    override fun getType(): KeyType = KeyType.fromKey(publicKey)

    /** `ecdsa-sha2-nistp256 AAAA... pocketherd` — ready to paste into authorized_keys. */
    val openSshPublicKey: String
        get() {
            val ec = publicKey as ECPublicKey
            val blob = Buffer.PlainBuffer().apply {
                putString(KeyType.ECDSA256.toString())
                putString("nistp256")
                putBytes(encodePoint(ec))
            }.compactData
            return "${KeyType.ECDSA256} ${Base64.getEncoder().encodeToString(blob)} pocketherd"
        }

    /** Uncompressed SEC1 point: 0x04 || X || Y, each coordinate padded to 32 bytes. */
    private fun encodePoint(key: ECPublicKey): ByteArray {
        val fieldSize = 32
        val x = key.w.affineX.toByteArray().takeLast(fieldSize).toByteArray()
        val y = key.w.affineY.toByteArray().takeLast(fieldSize).toByteArray()
        return byteArrayOf(0x04) + x.padStart(fieldSize) + y.padStart(fieldSize)
    }

    private fun ByteArray.padStart(size: Int): ByteArray =
        if (this.size >= size) this else ByteArray(size - this.size) + this

    companion object {
        const val ALIAS = "pocketherd.ssh.identity"

        /** Returns the existing identity, generating one on first run. */
        fun loadOrCreate(alias: String = ALIAS): KeystoreIdentity {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                return KeystoreIdentity(entry.privateKey, entry.certificate.publicKey)
            }

            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    // No setUserAuthenticationRequired: the reconnect loop runs while the
                    // screen is off, and a key that needs a fingerprint every time would
                    // make background reconnects impossible.
                    .build(),
            )
            val pair = generator.generateKeyPair()
            return KeystoreIdentity(pair.private, pair.public)
        }

        /** Deletes the identity. The user must then remove the old line from authorized_keys. */
        fun delete(alias: String = ALIAS) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
