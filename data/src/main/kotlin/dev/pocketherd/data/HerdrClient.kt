package dev.pocketherd.data

import dev.pocketherd.core.protocol.EventFilters
import dev.pocketherd.core.protocol.HerdrCodec
import dev.pocketherd.core.protocol.HerdrEventEnvelope
import dev.pocketherd.core.protocol.HerdrRequest
import dev.pocketherd.core.protocol.HerdrResponse
import dev.pocketherd.core.protocol.SubscribeParams
import dev.pocketherd.core.ssh.HerdrChannel
import dev.pocketherd.core.ssh.SshConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Speaks herdr's socket protocol over an [SshConnection].
 *
 * Every call opens its own exec channel because herdr answers one request per connection.
 * That was measured, not assumed: two requests written to one pipe produce one response and
 * the server then stops listening.
 */
class HerdrClient(
    private val connection: SshConnection,
    private val io: CoroutineDispatcher,
) {

    /** Drops the SSH transport. Any channel still open dies with it. */
    fun close() = connection.close()

    suspend fun call(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
    ): HerdrResponse = withContext(io) {
        connection.openChannel().use { channel ->
            val request = HerdrCodec.request(method, params)
            channel.writeLine(HerdrCodec.encode(request))
            val line = channel.readLine()
                ?: error("herdr closed the channel without answering $method: ${channel.stderr()}")
            HerdrCodec.decodeResponse(line)
        }
    }

    /**
     * Opens a long-lived subscription and emits every event that arrives.
     *
     * The filter list is fixed for the life of the connection, so a new pane means a new
     * subscription. The caller watches for `pane_created` and restarts this flow with the
     * wider pane set; `events.subscribe` has no wildcard for per-pane filters, so there is
     * no cheaper way to do it.
     */
    fun events(paneIds: Collection<String>): Flow<HerdrEventEnvelope> = callbackFlow {
        val channel: HerdrChannel = connection.openChannel()
        val request = HerdrRequest(
            id = HerdrCodec.nextId(),
            method = dev.pocketherd.core.protocol.Methods.EVENTS_SUBSCRIBE,
            params = HerdrCodec.json.encodeToJsonElement(
                SubscribeParams.serializer(),
                SubscribeParams(EventFilters.forPanes(paneIds)),
            ),
        )
        channel.writeLine(HerdrCodec.encode(request))

        // First line is the {"result":{"type":"subscription_started"}} acknowledgement.
        val ack = channel.readLine()
            ?: error("subscription refused: ${channel.stderr()}")
        HerdrCodec.resultOrThrow(HerdrCodec.decodeResponse(ack))

        while (isActive) {
            val line = channel.readLine() ?: break
            // A malformed or unrecognised event must not take down the stream.
            runCatching {
                HerdrCodec.json.decodeFromString(HerdrEventEnvelope.serializer(), line)
            }.getOrNull()?.let { trySend(it) }
        }

        awaitClose { channel.close() }
    }.flowOn(io)
}
