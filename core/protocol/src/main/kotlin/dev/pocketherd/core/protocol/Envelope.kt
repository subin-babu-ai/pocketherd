package dev.pocketherd.core.protocol

import kotlinx.serialization.SerialName
import dev.pocketherd.core.model.Pane
import dev.pocketherd.core.model.Tab
import dev.pocketherd.core.model.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The herdr socket envelope: one newline-delimited JSON object per message.
 *
 * Request:  {"id":"req_1","method":"ping","params":{}}
 * Success:  {"id":"req_1","result":{"type":"pong",...}}
 * Failure:  {"id":"req_1","error":{"code":"not_found","message":"pane not found"}}
 *
 * Responses echo the request id. A malformed request comes back with an empty id, which is
 * why [HerdrError] has to be usable without a correlation id.
 */
@Serializable
data class HerdrRequest(
    val id: String,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class HerdrResponse(
    val id: String = "",
    val result: JsonElement? = null,
    val error: HerdrError? = null,
)

@Serializable
data class HerdrError(
    val code: String,
    val message: String,
)

class HerdrRpcException(val code: String, message: String) : Exception("$code: $message")

/** Response to `ping`. The protocol number gates everything else the client does. */
@Serializable
data class Pong(
    val type: String,
    val version: String,
    val protocol: Int,
    /**
     * Left as raw JSON on purpose. The values are not all the same type: herdr 0.9.0 sends
     * booleans alongside `endpoint_protocol_generation`, which is a number. Typing this as
     * `Map<String, Boolean>` made every ping throw, and because the relay probe treats any
     * failure as "this relay does not work", the symptom was a host with a perfectly good
     * relay reporting that it had none.
     */
    val capabilities: JsonObject = JsonObject(emptyMap()),
)

/**
 * The `snapshot` object inside a `session.snapshot` result.
 *
 * Lives here rather than in :data because `@Serializable` needs the kotlinx-serialization
 * compiler plugin, which only the pure-Kotlin protocol modules apply. Declared in a module
 * without the plugin it compiles fine and then fails at runtime with
 * "Serializer for class 'Snapshot' is not found".
 */
@Serializable
data class Snapshot(
    val version: String = "",
    val protocol: Int = 0,
    val workspaces: List<Workspace> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val panes: List<Pane> = emptyList(),
)

object Protocol {
    /** The protocol this client was written and verified against (herdr 0.9.0). */
    const val VERIFIED = 22

    /**
     * Older servers may be missing methods we call, so refuse them. Newer servers are
     * accepted: the codec ignores unknown fields, and herdr's docs ask clients to tolerate
     * additive changes rather than pin an exact version.
     */
    fun isSupported(protocol: Int): Boolean = protocol >= VERIFIED
}

object Methods {
    const val PING = "ping"
    const val SESSION_SNAPSHOT = "session.snapshot"
    const val PANE_LIST = "pane.list"
    const val PANE_READ = "pane.read"
    const val PANE_SEND_TEXT = "pane.send_text"
    const val PANE_SEND_KEYS = "pane.send_keys"
    const val WORKSPACE_LIST = "workspace.list"
    const val AGENT_LIST = "agent.list"
    const val AGENT_PROMPT = "agent.prompt"
    const val EVENTS_SUBSCRIBE = "events.subscribe"
}
