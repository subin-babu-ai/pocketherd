package dev.pocketherd.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Encodes requests and decodes responses for the newline-delimited JSON socket protocol.
 *
 * `ignoreUnknownKeys` is what lets this client keep working against a herdr newer than the
 * protocol 22 it was built on.
 */
object HerdrCodec {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    private val counter = AtomicLong(0)

    fun nextId(): String = "req_${counter.incrementAndGet()}"

    /** Serialises to a single line, newline included. The protocol frames on newlines. */
    fun encode(request: HerdrRequest): String =
        json.encodeToString(HerdrRequest.serializer(), request) + "\n"

    fun request(method: String, params: JsonElement = JsonObject(emptyMap())): HerdrRequest =
        HerdrRequest(id = nextId(), method = method, params = params)

    fun decodeResponse(line: String): HerdrResponse =
        json.decodeFromString(HerdrResponse.serializer(), line)

    /** Unwraps a response, turning an error envelope into a thrown [HerdrRpcException]. */
    fun resultOrThrow(response: HerdrResponse): JsonElement {
        response.error?.let { throw HerdrRpcException(it.code, it.message) }
        return response.result
            ?: throw HerdrRpcException("empty_result", "response ${response.id} had no result")
    }

    inline fun <reified T> decodeResult(response: HerdrResponse): T =
        json.decodeFromJsonElement(resultOrThrow(response))

    /**
     * Pulls a nested object out of a result envelope. herdr wraps payloads one level deep:
     * `pane.read` returns `{"type":...,"read":{...}}`, not the read object directly.
     */
    inline fun <reified T> decodeResultField(response: HerdrResponse, field: String): T {
        val obj = resultOrThrow(response).jsonObject
        val value = obj[field]
            ?: throw HerdrRpcException("missing_field", "result has no field '$field'")
        return json.decodeFromJsonElement(value)
    }

    /**
     * True when a line is a delivered event rather than a reply. Subscription connections
     * carry both: the initial `{"result":{"type":"subscription_started"}}` and then events.
     */
    fun isEventLine(line: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(line).jsonObject
        obj.containsKey("event")
    }.getOrDefault(false)
}
