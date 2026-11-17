package dev.remotegpt.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Physical app-owned storage grouped by the areas shown in Settings. */
data class StorageUsage(
    val databaseBytes: Long = 0,
    val imagePreviewBytes: Long = 0,
    val editorDraftBytes: Long = 0,
    val temporaryBytes: Long = 0,
    val diagnosticBytes: Long = 0,
) {
    val totalBytes: Long
        get() = databaseBytes + imagePreviewBytes + editorDraftBytes + temporaryBytes + diagnosticBytes

    companion object {
        suspend fun read(context: Context): StorageUsage = withContext(Dispatchers.IO) {
            val database = context.getDatabasePath("workbench.db")
            StorageUsage(
                databaseBytes = listOf(
                    database,
                    File("${database.path}-wal"),
                    File("${database.path}-shm"),
                    File("${database.path}-journal"),
                ).sumOf(::fileSize),
                imagePreviewBytes = directorySize(File(context.cacheDir, "remote-images")),
                editorDraftBytes = directorySize(File(context.filesDir, "editor-drafts")),
                temporaryBytes = directorySize(File(context.cacheDir, "shared")),
                diagnosticBytes = fileSize(File(context.filesDir, "last-crash.txt")),
            )
        }
    }
}

private fun fileSize(file: File): Long = if (file.isFile) file.length().coerceAtLeast(0) else 0

private fun directorySize(directory: File): Long {
    if (!directory.isDirectory) return 0
    return directory.walkTopDown().filter(File::isFile).sumOf(::fileSize)
}
