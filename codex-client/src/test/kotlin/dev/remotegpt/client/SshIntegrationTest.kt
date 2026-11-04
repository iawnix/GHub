package dev.remotegpt.client

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

class SshIntegrationTest {
    private fun fixture(): Pair<HostProfile, SshPrompts> {
        assumeTrue(System.getenv("SSH_FIXTURE_PORTS") != null, "Run scripts/ssh_fixture.py --jvm for isolated OpenSSH tests")
        val ports = System.getenv("SSH_FIXTURE_PORTS").split(',').map(String::toInt)
        val endpoints = ports.map { SshEndpoint("127.0.0.1", it, System.getenv("SSH_FIXTURE_USER")) }
        val profile = HostProfile(name = "fixture", endpoint = endpoints.last(), jumps = endpoints.dropLast(1), codexPath = System.getenv("SSH_FIXTURE_CODEX"))
        val prompts = object : SshPrompts {
            override suspend fun credential(id: String) = Credential(privateKey = File(System.getenv("SSH_FIXTURE_KEY")).readText())
            override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = fingerprint == System.getenv("SSH_FIXTURE_FINGERPRINT")
            override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = error("Unexpected keyboard challenge")
        }
        return profile to prompts
    }

    @Test fun `two SSH hops carry official WebSocket proxy and reconnect to same daemon`() = runBlocking {
        val (profile, prompts) = fixture()
        SshConnection.connect(profile, prompts).use { ssh ->
            assertTrue(Regex("\\\"appServerVersion\\\"\\s*:\\s*\\\"0\\.\\d+\\.\\d+\\\"").containsMatchIn(ssh.version()))
            repeat(2) {
                RpcClient(ProxyTransport(ssh)).use { rpc ->
                    assertNotNull(rpc.connect().string("userAgent"))
                    assertNotNull(rpc.request("thread/loaded/list")["data"])
                }
            }
        }
    }

    @Test fun `SFTP uploads downloads and refuses conflicting edits`() = runBlocking {
        val (profile, prompts) = fixture()
        SshConnection.connect(profile, prompts).use { ssh ->
            val fs = RemoteFiles(ssh)
            val cwd = System.getenv("SSH_FIXTURE_ROOT")
            val path = fs.upload(cwd, "中文.txt", "first".byteInputStream())
            val original = fs.readText(path)
            val saved = fs.saveText(original, "second")
            assertEquals("second", saved.text)
            assertFailsWith<FileConflict> { fs.saveText(original, "stale") }
            val target = ByteArrayOutputStream(); fs.download(path, target)
            assertEquals("second", target.toString())
            assertTrue(fs.list("$cwd/.remote-gpt/attachments").any { it.path == path })
        }
    }

    @Test fun `SFTP creates workspace parents literally and preserves existing contents`() = runBlocking {
        val (profile, prompts) = fixture()
        val root = File(System.getenv("SSH_FIXTURE_ROOT"), "workspace-check")
        val directory = root.resolve("project ' 中文 \$(literal)/nested")
        SshConnection.connect(profile, prompts).use { ssh ->
            val fs = RemoteFiles(ssh)
            fs.ensureDirectory(directory.path)
            assertTrue(directory.isDirectory)
            val sentinel = directory.resolve("keep.txt").apply { writeText("keep") }
            fs.ensureDirectory(directory.path)
            assertEquals("keep", sentinel.readText())
            assertFails { fs.ensureDirectory(sentinel.path) }
            assertFails { fs.ensureDirectory(sentinel.resolve("child").path) }
            assertEquals("keep", sentinel.readText())
            val locked = root.resolve("locked").apply { mkdirs(); setWritable(false, false) }
            try {
                assertFails { fs.ensureDirectory(locked.resolve("child").path) }
                assertFalse(locked.resolve("child").exists())
            } finally { locked.setWritable(true, true) }
            assertFailsWith<IllegalArgumentException> { fs.ensureDirectory("relative/path") }
        }
        Unit
    }

    @Test fun `PTY can run commands and resize without affecting proxy`() = runBlocking {
        val (profile, prompts) = fixture()
        SshConnection.connect(profile, prompts).use { ssh ->
            ssh.terminal().use { terminal ->
                terminal.resize(100, 40)
                terminal.write("printf 'PTY_OK\\n'; exit\r".toByteArray())
                val text = withContext(Dispatchers.IO) { terminal.input.readBytes().decodeToString() }
                assertTrue(text.contains("PTY_OK"))
            }
            assertTrue(ssh.version().contains("running"))
        }
    }

    @Test fun `project PTY starts in quoted directory without typed initialization and still resizes`() = runBlocking {
        val (profile, prompts) = fixture()
        val directory = File(System.getenv("SSH_FIXTURE_ROOT"), "project ' 中文").apply { mkdirs() }
        SshConnection.connect(profile, prompts).use { ssh ->
            ssh.terminal(cwd = directory.path).use { terminal ->
                terminal.resize(100, 40)
                terminal.write("printf '\\n'; pwd; stty size; exit\r".toByteArray())
                val text = withContext(Dispatchers.IO) { terminal.input.readBytes().decodeToString() }
                assertTrue(text.contains(directory.path + "\r\n"), text)
                assertTrue(text.contains("40 100"), text)
                assertFalse(text.contains("cd --"), text)
            }
            ssh.terminal(cwd = directory.resolve("missing").path).use { terminal ->
                val text = withContext(Dispatchers.IO) { terminal.input.readBytes().decodeToString() }
                assertTrue(text.contains("missing"), text)
            }
        }
    }

    @Test fun `encrypted OpenSSH Ed25519 and PEM RSA keys authenticate`() = runBlocking {
        val (profile, prompts) = fixture()
        for (variable in listOf("SSH_FIXTURE_ED25519", "SSH_FIXTURE_RSA")) {
            val encrypted = object : SshPrompts by prompts {
                override suspend fun credential(id: String) = Credential(privateKey = File(System.getenv(variable)).readText(), passphrase = "fixture-only-passphrase")
            }
            SshConnection.connect(profile.copy(jumps = emptyList()), encrypted).use { assertTrue(it.version().contains("running")) }
        }
    }

    @Test fun `wrong private key passphrase never authenticates`() = runBlocking {
        val (profile, prompts) = fixture()
        val encrypted = object : SshPrompts by prompts {
            override suspend fun credential(id: String) = Credential(privateKey = File(System.getenv("SSH_FIXTURE_ED25519")).readText(), passphrase = "wrong")
        }
        assertFails { SshConnection.connect(profile.copy(jumps = emptyList()), encrypted) }
        Unit
    }

    @Test fun `host key rejection aborts before authentication`() = runBlocking {
        val (profile, prompts) = fixture()
        val reject = object : SshPrompts by prompts {
            override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = false
            override suspend fun credential(id: String): Credential = error("Credentials must not be requested after rejected host key")
        }
        assertFails { SshConnection.connect(profile.copy(jumps = emptyList()), reject) }
        Unit
    }
}
