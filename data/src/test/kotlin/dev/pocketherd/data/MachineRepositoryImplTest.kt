package dev.pocketherd.data

import dev.pocketherd.core.database.MachineDao
import dev.pocketherd.core.database.MachineEntity
import dev.pocketherd.core.model.AuthMethod
import dev.pocketherd.core.model.Machine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Editing a machine must not carry a trusted host key across a change of host. Doing so
 * would mean a machine the user never approved connects without a prompt.
 */
class MachineRepositoryImplTest {

    private class FakeDao(private var row: MachineEntity?) : MachineDao {
        var saved: MachineEntity? = null
        override fun observeAll(): Flow<List<MachineEntity>> = flowOf(listOfNotNull(row))
        override suspend fun get(id: Long): MachineEntity? = row?.takeIf { it.id == id }
        override suspend fun upsert(machine: MachineEntity): Long {
            saved = machine; row = machine; return machine.id
        }
        override suspend fun delete(id: Long) { row = null }
        override suspend fun rememberRelay(id: Long, relay: String) = Unit
        override suspend fun rememberHost(id: Long, host: String) = Unit
        override suspend fun trustHostKey(id: Long, fingerprint: String) = Unit
    }

    private val existing = MachineEntity(
        id = 1,
        label = "mac",
        host = "10.0.0.5",
        port = 22,
        username = "subin",
        socketPath = null,
        authMethod = "Keystore",
        lastRelay = "python3",
        hostKeyFingerprint = "SHA256:original",
    )

    private fun machine(host: String = "10.0.0.5", port: Int = 22, label: String = "mac") =
        Machine(
            id = 1,
            label = label,
            host = host,
            port = port,
            username = "subin",
            socketPath = null,
            auth = AuthMethod.Keystore,
        )

    @Test
    fun `renaming keeps the trusted key and the remembered relay`() = runTest {
        val dao = FakeDao(existing)
        MachineRepositoryImpl(dao).upsert(machine(label = "macbook"))

        assertEquals("macbook", dao.saved?.label)
        assertEquals("SHA256:original", dao.saved?.hostKeyFingerprint)
        assertEquals("python3", dao.saved?.lastRelay)
    }

    @Test
    fun `changing the host clears the trusted key so it must be approved again`() = runTest {
        val dao = FakeDao(existing)
        MachineRepositoryImpl(dao).upsert(machine(host = "10.0.0.99"))

        assertEquals("10.0.0.99", dao.saved?.host)
        assertNull(dao.saved?.hostKeyFingerprint)
        assertNull(dao.saved?.lastRelay)
    }

    @Test
    fun `changing only the port also clears it`() = runTest {
        val dao = FakeDao(existing)
        MachineRepositoryImpl(dao).upsert(machine(port = 2222))
        assertNull(dao.saved?.hostKeyFingerprint)
    }

    @Test
    fun `the jump host survives a round trip`() = runTest {
        val dao = FakeDao(existing)
        MachineRepositoryImpl(dao).upsert(machine().copy(via = 7))
        assertEquals(7L, dao.saved?.viaMachineId)
        // And comes back out as the domain field, not silently dropped.
        assertEquals(7L, MachineRepositoryImpl(dao).get(1)?.via)
    }

    @Test
    fun `clearing the jump host writes null rather than leaving the old one`() = runTest {
        val dao = FakeDao(existing.copy(viaMachineId = 7))
        MachineRepositoryImpl(dao).upsert(machine().copy(via = null))
        assertNull(dao.saved?.viaMachineId)
    }

    @Test
    fun `changing the host forgets which address worked`() = runTest {
        // The remembered address belongs to the old endpoint and would be tried first.
        val dao = FakeDao(existing.copy(lastGoodHost = "10.0.0.5"))
        MachineRepositoryImpl(dao).upsert(machine(host = "10.0.0.99"))
        assertNull(dao.saved?.lastGoodHost)
    }

    @Test
    fun `renaming keeps the remembered address`() = runTest {
        val dao = FakeDao(existing.copy(lastGoodHost = "10.0.0.5"))
        MachineRepositoryImpl(dao).upsert(machine(label = "macbook"))
        assertEquals("10.0.0.5", dao.saved?.lastGoodHost)
    }

    @Test
    fun `fallback addresses survive a round trip`() = runTest {
        val dao = FakeDao(existing)
        MachineRepositoryImpl(dao).upsert(
            machine().copy(fallbackHosts = listOf("100.64.0.5", "box.local")),
        )
        assertEquals("100.64.0.5\nbox.local", dao.saved?.fallbackHosts)
        assertEquals(
            listOf("100.64.0.5", "box.local"),
            MachineRepositoryImpl(dao).get(1)?.fallbackHosts,
        )
    }

    @Test
    fun `a brand new machine starts with nothing trusted`() = runTest {
        val dao = FakeDao(null)
        MachineRepositoryImpl(dao).upsert(machine().copy(id = 0))
        assertNull(dao.saved?.hostKeyFingerprint)
        assertNull(dao.saved?.lastRelay)
    }
}
