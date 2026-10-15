package dev.pocketherd.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyVerificationTest {

    @Test
    fun `findCause reaches an exception SSHJ wrapped during key exchange`() {
        // This is the shape that matters: SSHJ catches whatever the verifier throws and
        // re-raises it as a transport error, so the top-level type is never ours.
        val wrapped = RuntimeException(
            "Could not verify host key",
            IllegalStateException(
                "kex failed",
                UnknownHostKeyException("mac", 22, "SHA256:abc"),
            ),
        )
        val found = wrapped.findCause<UnknownHostKeyException>()
        assertEquals("SHA256:abc", found?.fingerprint)
        assertEquals("mac", found?.hostname)
    }

    @Test
    fun `findCause returns null when the type is absent`() {
        val unrelated = RuntimeException("connection reset", java.io.IOException("EOF"))
        assertNull(unrelated.findCause<UnknownHostKeyException>())
    }

    @Test
    fun `findCause finds the exception when it is thrown directly`() {
        val direct = HostKeyChangedException("mac", "old", "new")
        assertEquals(direct, direct.findCause<HostKeyChangedException>())
    }

    @Test
    fun `a self-referencing cause does not spin forever`() {
        // Throwable.initCause permits this, and a naive while(cause != null) loop hangs.
        val looping = RuntimeException("a")
        val other = RuntimeException("b", looping)
        looping.initCause(other)
        assertEquals(2, looping.causeChain().count())
        assertNull(looping.findCause<UnknownHostKeyException>())
    }

    private val theKey = "SHA256:RhTfm/6X+FGRyAin0xkNRsIcCddRoldqloCghmvCWoQ"

    private fun verifier(expected: String?) =
        StoredHostKeyVerifier(expectedFingerprint = expected, fingerprintOf = { theKey })

    @Test
    fun `an unknown host is refused rather than silently trusted`() {
        val thrown = assertThrows(UnknownHostKeyException::class.java) {
            verifier(expected = null).verify("mac", 22, FakeKey)
        }
        assertTrue(thrown.message!!.contains("not a known host"))
        assertEquals(theKey, thrown.fingerprint)
    }

    @Test
    fun `a matching fingerprint verifies`() {
        assertTrue(verifier(expected = theKey).verify("mac", 22, FakeKey))
    }

    @Test
    fun `a changed fingerprint throws instead of re-prompting`() {
        val thrown = assertThrows(HostKeyChangedException::class.java) {
            verifier(expected = "SHA256:something-else").verify("mac", 22, FakeKey)
        }
        assertEquals(theKey, thrown.actual)
        assertEquals("SHA256:something-else", thrown.expected)
    }

    @Test
    fun `an explicit approval callback trusts without throwing`() {
        val approving = StoredHostKeyVerifier(
            expectedFingerprint = null,
            onUnknownHost = { true },
            fingerprintOf = { theKey },
        )
        assertTrue(approving.verify("mac", 22, FakeKey))
    }

    private object FakeKey : java.security.PublicKey {
        override fun getAlgorithm() = "ssh-ed25519"
        override fun getFormat() = "X.509"
        override fun getEncoded() = byteArrayOf(1, 2, 3, 4, 5)
    }
}
