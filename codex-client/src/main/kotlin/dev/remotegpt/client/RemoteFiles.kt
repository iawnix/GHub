package dev.remotegpt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.coroutines.coroutineContext

class RemoteFiles(private val ssh: SshConnection) {
    suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        ssh.client.newSFTPClient().use { sftp -> sftp.ls(path).filter { it.name !in setOf(".", "..") }.map {
            RemoteFile(it.name, it.path, it.isDirectory, it.attributes.size, it.attributes.mtime.toLong())
        }.sortedWith(remoteFileOrder) }
    }
    /** Verify retained attachments without changing or downloading them. */
    suspend fun readableFile(path: String): Boolean = readableFiles(listOf(path)) == null

    /** Returns the first unreadable path, or null when all attachments are regular readable files. */
    suspend fun readableFiles(paths: List<String>): String? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        ssh.client.newSFTPClient().use { sftp ->
            for (path in paths) {
                ensureActive()
                if (!path.startsWith('/') || '\u0000' in path) return@use path
                val attributes = sftp.statExistence(path) ?: return@use path
                if (attributes.type != FileMode.Type.REGULAR) return@use path
                val readable = runCatching { sftp.open(path, setOf(OpenMode.READ)).use { true } }.getOrDefault(false)
                if (!readable) return@use path
            }
            null
        }
    }

    suspend fun home(): String = withContext(Dispatchers.IO) { ssh.client.newSFTPClient().use { it.canonicalize(".") } }

    /** Idempotent directory creation over SFTP; paths are never interpreted by a shell. */
    suspend fun ensureDirectory(path: String) = withContext(Dispatchers.IO) {
        if (!path.startsWith('/') || '\u0000' in path || '\n' in path) {
            throw ClientArgumentException(ClientErrorCode.INVALID_REMOTE_DIRECTORY, "Choose a valid absolute remote path")
        }
        ssh.client.newSFTPClient().use { sftp ->
            sftp.mkdirs(path)
            if (sftp.stat(path).type != FileMode.Type.DIRECTORY) {
                throw ClientArgumentException(ClientErrorCode.PATH_OCCUPIED_BY_FILE, "Path is occupied by a file")
            }
        }
    }

    suspend fun readText(path: String): TextFile = withContext(Dispatchers.IO) {
        ssh.client.newSFTPClient().use { sftp ->
            val resolved = sftp.canonicalize(path)
            if (sftp.stat(resolved).size > 1_048_576) {
                throw ClientArgumentException(ClientErrorCode.TEXT_TOO_LARGE_TO_EDIT, "Text editing supports files up to 1 MiB")
            }
            val bytes = read(sftp, resolved)
            if (bytes.any { it == 0.toByte() }) {
                throw ClientArgumentException(ClientErrorCode.NOT_EDITABLE_TEXT, "Binary file cannot be edited as text")
            }
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) {
                    throw ClientStateException(ClientErrorCode.UNSUPPORTED_TEXT_ENCODING, "Only UTF-8 text files are supported")
                }
            TextFile(resolved, text, digest(bytes))
        }
    }

    suspend fun saveText(file: TextFile, text: String, overwrite: Boolean = false): TextFile = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray()
        if (bytes.size > 1_048_576) {
            throw ClientArgumentException(ClientErrorCode.TEXT_EXCEEDS_SAVE_LIMIT, "Text exceeds 1 MiB")
        }
        ssh.client.newSFTPClient().use { sftp ->
            if (!overwrite) {
                val stat = sftp.statExistence(file.path) ?: throw FileConflict()
                // A file that grew past the edit limit since readText() was definitely modified externally.
                if (stat.size > 1_048_576 || digest(read(sftp, file.path)) != file.sha256) throw FileConflict()
            }
            atomicWrite(sftp, file.path, bytes.inputStream())
            TextFile(file.path, text, digest(bytes))
        }
    }

    suspend fun upload(cwd: String, filename: String, source: InputStream): String = withContext(Dispatchers.IO) {
        if (!cwd.startsWith('/')) {
            throw ClientArgumentException(ClientErrorCode.INVALID_REMOTE_DIRECTORY, "Choose a remote working directory")
        }
        val safeName = filename.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120).ifBlank { "attachment" }
        val directory = cwd.trimEnd('/') + "/.remote-gpt/attachments"
        val target = "$directory/${UUID.randomUUID()}-$safeName"
        ssh.client.newSFTPClient().use { sftp -> sftp.mkdirs(directory); atomicWrite(sftp, target, source) }
        target
    }

    suspend fun download(path: String, target: OutputStream, maxBytes: Long = Long.MAX_VALUE) = withContext(Dispatchers.IO) {
        ssh.client.newSFTPClient().use { sftp ->
            val size = sftp.stat(path).size
            if (size > maxBytes) {
                throw ClientArgumentException(ClientErrorCode.PREVIEW_TOO_LARGE, "File exceeds preview size limit")
            }
            sftp.open(path).use { remote ->
                remote.RemoteFileInputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > maxBytes) {
                            throw ClientArgumentException(ClientErrorCode.PREVIEW_TOO_LARGE, "File exceeds preview size limit")
                        }
                        target.write(buffer, 0, count)
                    }
                    target.flush()
                }
            }
        }
    }

    private fun read(sftp: SFTPClient, path: String): ByteArray {
        if (sftp.stat(path).size > 1_048_576) {
            throw ClientArgumentException(ClientErrorCode.TEXT_TOO_LARGE_TO_EDIT, "File exceeds text edit size limit")
        }
        return sftp.open(path).use { file -> file.RemoteFileInputStream().use { readLimited(it, 1_048_576) } }
    }

    private suspend fun atomicWrite(sftp: SFTPClient, path: String, source: InputStream) {
        val temp = "$path.upload-${UUID.randomUUID()}"
        try {
            val permissions = sftp.statExistence(path)?.mode?.mask?.and(0x1ff) ?: 0x180
            sftp.open(temp, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { remote ->
                val buffer = ByteArray(32768); var offset = 0L
                while (true) { coroutineContext.ensureActive(); val n = source.read(buffer); if (n < 0) break; remote.write(offset, buffer, 0, n); offset += n }
            }
            sftp.chmod(temp, permissions)
            sftp.rename(temp, path, setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
        } finally { runCatching { sftp.rm(temp) } }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Folders first, then files; within each, hidden (dot) entries after visible ones, by name. */
val remoteFileOrder: Comparator<RemoteFile> = compareByDescending<RemoteFile> { it.directory }
    .thenBy { it.name.startsWith('.') }.thenBy { it.name.lowercase() }
