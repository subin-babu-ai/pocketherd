package dev.pocketherd.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PongTest {

    /** Captured verbatim from `ping` against herdr 0.9.0. */
    private val realPong =
        """{"id":"req_1","result":{"type":"pong","version":"0.9.0","protocol":22,""" +
            """"capabilities":{"live_handoff":true,"detached_server_daemon":true,""" +
            """"endpoint_protocol_generation":1,"surface_interest":true,""" +
            """"health_check":true}}}"""

    @Test
    fun `the real pong decodes, mixed capability value types and all`() {
        val pong: Pong = HerdrCodec.decodeResult(HerdrCodec.decodeResponse(realPong))
        assertEquals("pong", pong.type)
        assertEquals("0.9.0", pong.version)
        assertEquals(22, pong.protocol)
        // endpoint_protocol_generation is a number while its neighbours are booleans.
        // Typing capabilities as Map<String, Boolean> made this throw, which the relay
        // probe reported as "no usable relay" on a host whose relay was fine.
        assertTrue(pong.capabilities.containsKey("endpoint_protocol_generation"))
    }

    @Test
    fun `protocol 22 is supported and older is not`() {
        assertTrue(Protocol.isSupported(22))
        assertTrue(Protocol.isSupported(23))
        assertFalse(Protocol.isSupported(21))
    }

    @Test
    fun `unknown result fields are ignored so a newer herdr still works`() {
        val future =
            """{"id":"x","result":{"type":"pong","version":"1.0.0","protocol":23,""" +
                """"capabilities":{},"something_new":{"nested":[1,2,3]}}}"""
        val pong: Pong = HerdrCodec.decodeResult(HerdrCodec.decodeResponse(future))
        assertEquals(23, pong.protocol)
    }
}
