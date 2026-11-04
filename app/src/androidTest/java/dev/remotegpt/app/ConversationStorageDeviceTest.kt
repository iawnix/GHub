package dev.remotegpt.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.data.*
import dev.remotegpt.client.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationStorageDeviceTest {
    @Test fun versionTwoMigratesAndItemPatchesAreAtomicAndRemoveShortenedChunks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "cache-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open("dev.remotegpt.app.data.AppDatabase/2.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val original = Conversation("thread", listOf(
            TimelineItem("old", "t", "agentMessage", "Saved 中文😀".repeat(10_000), true),
            TimelineItem("live", "t", "agentMessage", "Streaming".repeat(10_000))))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { db.execSQL(setup.getString(it)) }
            db.version = 2
            encodeConversationCache(original).forEachIndexed { i, json ->
                db.execSQL("INSERT INTO conversation_chunks VALUES (?, ?, ?, ?)", arrayOf<Any>("host", "thread", i, json))
            }
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_2_3).build()
        try {
            val dao = database.workbench()
            assertEquals(original, decodeStoredConversation(dao.conversationSnapshot("host", "thread")))
            suspend fun write(patch: ConversationCachePatch) {
                dao.patchConversation("host", "thread", patch.header, patch.reset, patch.removed,
                    patch.changed.flatMap { (key, chunks) -> chunks.mapIndexed { index, json ->
                        ConversationItemRow("host", "thread", key, index, json)
                    } })
            }
            val first = planConversationCache(null, original)
            write(first)
            assertEquals(original, decodeStoredConversation(dao.conversationSnapshot("host", "thread")))
            // The second INSERT fails after the first item was already replaced.
            // Room must roll back the whole patch, including deletes and metadata.
            database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_live BEFORE INSERT ON conversation_items
                WHEN NEW.itemKey = '${original.items.last().identityKey}' BEGIN SELECT RAISE(ABORT, 'fixture'); END""")
            val changed = original.copy(items = original.items.map { it.copy(text = "replacement", complete = true) })
            try { write(planConversationCache(first.snapshot, changed)); fail("Expected transaction failure") }
            catch (_: android.database.SQLException) { }
            assertEquals(original, decodeStoredConversation(dao.conversationSnapshot("host", "thread")))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_live")
            val shorter = original.copy(items = listOf(original.items.last().copy(text = "final", complete = true)))
            write(planConversationCache(first.snapshot, shorter))
            val disk = dao.conversationSnapshot("host", "thread")
            assertEquals(1, disk.items.size)
            assertEquals(shorter, decodeStoredConversation(disk))
            dao.deleteWorkspace("host")
            assertTrue(dao.conversationSnapshot("host", "thread").items.isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
