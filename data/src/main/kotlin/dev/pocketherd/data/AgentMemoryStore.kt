package dev.pocketherd.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketherd.core.model.AgentStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agentMemory by preferencesDataStore("agent_memory")

/**
 * Remembers what each agent's status was and when it changed, across process death.
 *
 * Without this, force-stopping the app or Android reclaiming it would reset every age to
 * "just connected" and re-surface everything as unread. The user would be told five agents
 * newly blocked when none of them had changed.
 *
 * Keyed `machineId:paneId`, because pane ids restart at `w1` on every machine.
 */
@Singleton
class AgentMemoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    @Serializable
    data class Record(
        val status: String,
        val sinceEpochMs: Long,
        val exact: Boolean,
        val seen: Boolean,
    )

    private val recordsKey = stringPreferencesKey("records")
    private val lastSeenKey = longPreferencesKey("last_seen_at")

    private val json = Json { ignoreUnknownKeys = true }

    val lastSeenAt: Flow<Instant?> = context.agentMemory.data.map { prefs ->
        prefs[lastSeenKey]?.let(Instant::ofEpochMilli)
    }

    suspend fun records(): Map<String, Record> = read(context.agentMemory.data.first())

    private fun read(prefs: Preferences): Map<String, Record> =
        prefs[recordsKey]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, Record>>(raw) }.getOrNull()
        } ?: emptyMap()

    /**
     * Writes what the client currently believes, for every pane it can see.
     *
     * Panes absent from [current] are dropped rather than kept forever; a pane that no
     * longer exists cannot become unread again, and the store would otherwise grow without
     * bound across months of use.
     */
    suspend fun put(current: Map<String, Record>) {
        context.agentMemory.edit { prefs ->
            prefs[recordsKey] = json.encodeToString(current)
        }
    }

    suspend fun setLastSeenAt(at: Instant) {
        context.agentMemory.edit { prefs -> prefs[lastSeenKey] = at.toEpochMilli() }
    }

    companion object {
        fun key(machineId: Long, paneId: String) = "$machineId:$paneId"

        fun statusOf(record: Record): AgentStatus =
            runCatching { AgentStatus.valueOf(record.status) }.getOrDefault(AgentStatus.Unknown)
    }
}
