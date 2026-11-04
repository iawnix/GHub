package dev.remotegpt.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Entity(tableName = "hosts") data class HostRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "threads", primaryKeys = ["hostId", "threadId"])
data class ThreadRow(val hostId: String, val threadId: String, val json: String)
@Entity(tableName = "conversations", primaryKeys = ["hostId", "threadId"])
data class ConversationRow(val hostId: String, val threadId: String, val json: String)
@Entity(tableName = "conversation_chunks", primaryKeys = ["hostId", "threadId", "chunkIndex"])
data class ConversationChunkRow(val hostId: String, val threadId: String, val chunkIndex: Int, val json: String)
@Entity(tableName = "conversation_items", primaryKeys = ["hostId", "threadId", "itemKey", "chunkIndex"])
data class ConversationItemRow(val hostId: String, val threadId: String, val itemKey: String, val chunkIndex: Int, val json: String)
data class ConversationCacheRows(val header: List<ConversationChunkRow>, val items: List<ConversationItemRow>)
@Entity(tableName = "drafts", primaryKeys = ["hostId", "threadId"])
data class DraftRow(val hostId: String, val threadId: String, val json: String)

@Dao interface WorkbenchDao {
    @Query("SELECT * FROM hosts") fun hosts(): Flow<List<HostRow>>
    @Upsert suspend fun putHost(row: HostRow)
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun deleteHost(id: String)
    @Query("SELECT * FROM threads WHERE hostId = :host") suspend fun threads(host: String): List<ThreadRow>
    @Upsert suspend fun putThreads(rows: List<ThreadRow>)
    @Query("DELETE FROM threads WHERE hostId = :host AND threadId = :thread") suspend fun deleteThread(host: String, thread: String)
    @Query("SELECT * FROM conversations WHERE hostId = :host AND threadId = :thread") suspend fun conversation(host: String, thread: String): ConversationRow?
    @Upsert suspend fun putConversation(row: ConversationRow)
    @Query("DELETE FROM conversations WHERE hostId = :host AND threadId = :thread") suspend fun deleteConversation(host: String, thread: String)
    @Query("SELECT * FROM conversation_chunks WHERE hostId = :host AND threadId = :thread ORDER BY chunkIndex") suspend fun conversationChunks(host: String, thread: String): List<ConversationChunkRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConversationChunks(rows: List<ConversationChunkRow>)
    @Query("DELETE FROM conversation_chunks WHERE hostId = :host AND threadId = :thread") suspend fun deleteConversationChunks(host: String, thread: String)
    @Transaction suspend fun replaceConversationChunks(host: String, thread: String, rows: List<ConversationChunkRow>) {
        deleteConversationChunks(host, thread)
        putConversationChunks(rows)
        // Remove the pre-0.1.8 single-row cache after the chunked copy is durable.
        deleteConversation(host, thread)
    }
    @Query("SELECT * FROM conversation_items WHERE hostId = :host AND threadId = :thread ORDER BY itemKey, chunkIndex")
    suspend fun conversationItems(host: String, thread: String): List<ConversationItemRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConversationItems(rows: List<ConversationItemRow>)
    @Query("DELETE FROM conversation_items WHERE hostId = :host AND threadId = :thread AND itemKey IN (:keys)")
    suspend fun deleteConversationItems(host: String, thread: String, keys: List<String>)
    @Query("DELETE FROM conversation_items WHERE hostId = :host AND threadId = :thread")
    suspend fun deleteConversationItems(host: String, thread: String)
    @Query("DELETE FROM conversation_items WHERE hostId = :host") suspend fun deleteConversationItems(host: String)
    @Query("DELETE FROM conversation_items") suspend fun clearConversationItems()
    @Transaction suspend fun conversationSnapshot(host: String, thread: String): ConversationCacheRows =
        ConversationCacheRows(conversationChunks(host, thread), conversationItems(host, thread))
    @Transaction suspend fun patchConversation(host: String, thread: String, header: String?,
        reset: Boolean, removed: List<String>, changed: List<ConversationItemRow>) {
        if (reset) deleteConversationItems(host, thread)
        else {
            val keys = (removed + changed.map { it.itemKey }).distinct()
            if (keys.isNotEmpty()) deleteConversationItems(host, thread, keys)
        }
        if (changed.isNotEmpty()) putConversationItems(changed)
        if (header != null) replaceConversationChunks(host, thread, listOf(ConversationChunkRow(host, thread, 0, header)))
    }
    @Query("SELECT * FROM drafts WHERE hostId = :host AND threadId = :thread") suspend fun draft(host: String, thread: String): DraftRow?
    @Upsert suspend fun putDraft(row: DraftRow)
    @Query("DELETE FROM threads WHERE hostId = :host") suspend fun deleteThreads(host: String)
    @Query("DELETE FROM conversations WHERE hostId = :host") suspend fun deleteConversations(host: String)
    @Query("DELETE FROM conversation_chunks WHERE hostId = :host") suspend fun deleteConversationChunks(host: String)
    @Query("DELETE FROM drafts WHERE hostId = :host") suspend fun deleteDrafts(host: String)
    @Transaction suspend fun deleteWorkspace(host: String) {
        deleteHost(host); deleteThreads(host); deleteConversations(host); deleteConversationChunks(host); deleteConversationItems(host); deleteDrafts(host)
    }
    @Query("DELETE FROM conversations") suspend fun clearConversations()
    @Query("DELETE FROM conversation_chunks") suspend fun clearConversationChunks()
    @Query("DELETE FROM threads") suspend fun clearThreads()
    @Query("DELETE FROM drafts") suspend fun clearDrafts()
}

@Database(entities = [HostRow::class, ThreadRow::class, ConversationRow::class, ConversationChunkRow::class, ConversationItemRow::class, DraftRow::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() { abstract fun workbench(): WorkbenchDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_chunks (`hostId` TEXT NOT NULL, `threadId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`hostId`, `threadId`, `chunkIndex`))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_items (`hostId` TEXT NOT NULL, `threadId` TEXT NOT NULL, `itemKey` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`hostId`, `threadId`, `itemKey`, `chunkIndex`))")
    }
}
