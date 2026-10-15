package dev.pocketherd.core.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import java.security.PrivateKey
import java.security.PublicKey

/**
 * ECDSA P-256 signing that works with a non-exportable Android Keystore key.
 *
 * SSHJ builds its signature objects with `Signature.getInstance(algorithm)` and no provider.
 * The JCE then picks a provider before it has seen the key, lands on BouncyCastle, and
 * BouncyCastle rejects a Keystore key with `InvalidKeyException: no encoding for EC private
 * key` — it wants the private bytes, which is exactly what the Keystore will never hand
 * over. The provider fallback that is supposed to try the next candidate does not rescue it
 * here, so the provider has to be named explicitly.
 *
 * Only signing needs this. Verification, signature encoding, and host key checks all work on
 * ordinary keys with encodings, so they delegate to SSHJ's own implementation rather than
 * being reimplemented.
 *
 * SSHJ reaches signatures through `KeyAlgorithm.newSignature()`, so [KeystoreKeyAlgorithm]
 * is what actually gets swapped into the config; see [SshConnection].
 */
class KeystoreEcdsaSignature : Signature {

    /** Handles verify, encode, and the host key path, all of which BouncyCastle does fine. */
    private val delegate: Signature = SignatureECDSA.Factory256().create()

    /** Non-null once this instance is signing rather than verifying. */
    private var signer: java.security.Signature? = null

    override fun getSignatureName(): String = delegate.signatureName

    override fun initVerify(publicKey: PublicKey) = delegate.initVerify(publicKey)

    override fun initSign(privateKey: PrivateKey) {
        signer = runCatching {
            // The provider that owns the key is the only one that can sign with it.
            java.security.Signature.getInstance(ALGORITHM, KEYSTORE_PROVIDER)
                .apply { initSign(privateKey) }
        }.getOrElse {
            // A key that is not Keystore-backed signs fine on the default chain.
            java.security.Signature.getInstance(ALGORITHM).apply { initSign(privateKey) }
        }
    }

    override fun update(data: ByteArray) {
        val active = signer
        if (active != null) active.update(data) else delegate.update(data)
    }

    override fun update(data: ByteArray, offset: Int, length: Int) {
        val active = signer
        if (active != null) active.update(data, offset, length) else delegate.update(data, offset, length)
    }

    override fun sign(): ByteArray =
        checkNotNull(signer) { "sign() called before initSign()" }.sign()

    override fun encode(signature: ByteArray): ByteArray = delegate.encode(signature)

    override fun verify(signature: ByteArray): Boolean = delegate.verify(signature)

    private companion object {
        const val ALGORITHM = "SHA256withECDSA"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

/**
 * The stock `ecdsa-sha2-nistp256` key algorithm with only its signer replaced.
 *
 * Key parsing and buffer encoding are untouched; delegating everything but
 * [newSignature] keeps this immune to changes in how SSHJ reads EC keys.
 */
class KeystoreKeyAlgorithm(
    private val delegate: KeyAlgorithm,
) : KeyAlgorithm by delegate {

    override fun newSignature(): Signature = KeystoreEcdsaSignature()

    object Factory : net.schmizz.sshj.common.Factory.Named<KeyAlgorithm> {
        override fun getName(): String = KeyType.ECDSA256.toString()
        override fun create(): KeyAlgorithm =
            KeystoreKeyAlgorithm(KeyAlgorithms.ECDSASHANistp256().create())
    }
}
