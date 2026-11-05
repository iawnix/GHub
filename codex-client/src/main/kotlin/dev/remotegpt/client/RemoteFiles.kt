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
    suspend fun readableFile(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!path.startsWith('/') || '\u0000' in path) return@withContext false
        ssh.client.newSFTPClient().use { sftp ->
            val attributes = sftp.statExistence(path) ?: return@use false
            if (attributes.type != FileMode.Type.REGULAR) return@use false
            sftp.open(path, setOf(OpenMode.READ)).use { true }
        }
    }

    suspend fun home(): String = withContext(Dispatchers.IO) { ssh.client.newSFTPClient().use { it.canonicalize(".") } }

    /** Idempotent directory creation over SFTP; paths are never interpreted by a shell. */
    suspend fun ensureDirectory(path: String) = withContext(Dispatchers.IO) {
        require(path.startsWith('/') && '\u0000' !in path && '\n' !in path) { "请选择有效的远端绝对路径" }
        ssh.client.newSFTPClient().use { sftp ->
            sftp.mkdirs(path)
            require(sftp.stat(path).type == FileMode.Type.DIRECTORY) { "该路径已被文件占用，请选择目录路径。" }
        }
    }

    suspend fun readText(path: String): TextFile = withContext(Dispatchers.IO) {
        ssh.client.newSFTPClient().use { sftp ->
            val resolved = sftp.canonicalize(path)
            require(sftp.stat(resolved).size <= 1_048_576) { "仅支持编辑 1 MiB 以内的文本，请下载此文件。" }
            val bytes = read(sftp, resolved)
            require(bytes.none { it == 0.toByte() }) { "这不是可编辑的文本文件，请下载后打开。" }
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) { error("仅支持 UTF-8 文本，请下载后使用对应编码打开。") }
            TextFile(resolved, text, digest(bytes))
        }
    }

    suspend fun saveText(file: TextFile, text: String, overwrite: Boolean = false): TextFile = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray()
        require(bytes.size <= 1_048_576) { "文本超过 1 MiB" }
        ssh.client.newSFTPClient().use { sftp ->
            if (!overwrite && (sftp.statExistence(file.path) == null || digest(read(sftp, file.path)) != file.sha256)) throw FileConflict()
            atomicWrite(sftp, file.path, bytes.inputStream())
            TextFile(file.path, text, digest(bytes))
        }
    }

    suspend fun upload(cwd: String, filename: String, source: InputStream): String = withContext(Dispatchers.IO) {
        require(cwd.startsWith('/')) { "请选择远端工作目录" }
        val safeName = filename.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120).ifBlank { "attachment" }
        val directory = cwd.trimEnd('/') + "/.remote-gpt/attachments"
        val target = "$directory/${UUID.randomUUID()}-$safeName"
        ssh.client.newSFTPClient().use { sftp -> sftp.mkdirs(directory); atomicWrite(sftp, target, source) }
        target
    }

    suspend fun download(path: String, target: OutputStream, maxBytes: Long = Long.MAX_VALUE) = withContext(Dispatchers.IO) {
        ssh.client.newSFTPClient().use { sftp ->
            val size = sftp.stat(path).size
            require(size <= maxBytes) { "文件超过预览大小限制，请下载到手机后打开" }
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
                        require(total <= maxBytes) { "文件超过预览大小限制，请下载到手机后打开" }
                        target.write(buffer, 0, count)
                    }
                    target.flush()
                }
            }
        }
    }

    private fun read(sftp: SFTPClient, path: String): ByteArray {
        require(sftp.stat(path).size <= 1_048_576) { "文件超过文本编辑大小限制" }
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
