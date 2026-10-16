package dev.pocketherd.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * Saved SSH machines.
 *
 * No secret is stored here. Key-based auth uses a non-exportable Android Keystore key, so
 * there is nothing to persist beyond its alias; password auth keeps the password in
 * EncryptedSharedPreferences and this table only records which method to use. That is why
 * this database is plain Room rather than SQLCipher: it holds hostnames and usernames, the
 * same things already sitting in ~/.ssh/config.
 */
@Entity(tableName = "machines")
data class MachineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    /** Null means the default ~/.config/herdr/herdr.sock; set for named herdr sessions. */
    val socketPath: String?,
    val authMethod: String,
    /** Relay that worked last time, tried first on reconnect to skip the probe. */
    val lastRelay: String? = null,
    /** SSH host key fingerprint accepted by the user, checked on every connect. */
    val hostKeyFingerprint: String? = null,
    /** Id of the machine to tunnel through, or null for a direct connection. */
    val viaMachineId: Long? = null,
    /** Newline-separated alternative addresses. */
    val fallbackHosts: String? = null,
    val lastGoodHost: String? = null,
)

@Dao
interface MachineDao {
    @Query("SELECT * FROM machines ORDER BY label")
    fun observeAll(): Flow<List<MachineEntity>>

    @Query("SELECT * FROM machines WHERE id = :id")
    suspend fun get(id: Long): MachineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(machine: MachineEntity): Long

    @Query("DELETE FROM machines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE machines SET lastRelay = :relay WHERE id = :id")
    suspend fun rememberRelay(id: Long, relay: String)

    @Query("UPDATE machines SET hostKeyFingerprint = :fingerprint WHERE id = :id")
    suspend fun trustHostKey(id: Long, fingerprint: String)

    @Query("UPDATE machines SET lastGoodHost = :host WHERE id = :id")
    suspend fun rememberHost(id: Long, host: String)
}

@Database(entities = [MachineEntity::class], version = 3, exportSchema = false)
abstract class PocketHerdDatabase : RoomDatabase() {
    abstract fun machineDao(): MachineDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Adds the jump-host column. A real migration rather than
     * `fallbackToDestructiveMigration` so an upgrade does not silently drop the machines
     * and trusted host keys the user already set up.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE machines ADD COLUMN viaMachineId INTEGER")
        }
    }

    /** Several addresses per machine, plus the one that worked last. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE machines ADD COLUMN fallbackHosts TEXT")
            db.execSQL("ALTER TABLE machines ADD COLUMN lastGoodHost TEXT")
        }
    }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PocketHerdDatabase =
        Room.databaseBuilder(context, PocketHerdDatabase::class.java, "pocketherd.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun machineDao(database: PocketHerdDatabase): MachineDao = database.machineDao()
}
