package dev.remotegpt.app

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.Context
import android.content.ClipboardManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.client.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import java.util.Base64

/** Run against the actual non-debuggable, R8-optimized release build. */
class ReleaseRuntimeDeviceTest {
    @Test fun optimizedReleaseStartsAndUsesEncryptedKeysSftpAndPty() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Requires isolated OpenSSH fixture", args.containsKey("releaseSshPort"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val command = "printf '%s\\n' \"\$HOME\"\n\tgit status --short"
        val output = (1..180).joinToString("\n", postfix = "\n") { "line $it: release output 中文" }
        val code = "def hello():\n    return \"release 中文\"\n"
        seedCodeHistory(command, output, code)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        assertFalse(activity.isFinishing)
        try {
            click("release-code")
            click("Release code fixture")
            click(label("ui_failed_operations_view_details", 1))
            click(label("ui_command_execution"))
            find(label("ui_exit_code", 7))
            click(label("ui_copy_command"))
            assertClipboard(command)
            // Capture the actual optimized UI, including syntax-colored text.
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(testOutputFolder(), "release-code.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            click(label("ui_view_full_content"))
            click(label("ui_copy_output"))
            assertClipboard(output)
            click(label("ui_back_to_session"))
            click(label("ui_copy_code"))
            assertClipboard(code)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            SQLiteDatabase.openDatabase(context.getDatabasePath("workbench.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for (table in listOf("threads", "conversations", "conversation_chunks", "conversation_items", "drafts")) db.delete(table, "hostId = ?", arrayOf("release-code-fixture"))
                db.delete("hosts", "id = ?", arrayOf("release-code-fixture"))
            }
        }
        val endpoint = SshEndpoint(args.getString("releaseSshHost", "10.0.2.2"), args.getString("releaseSshPort")!!.toInt(), args.getString("releaseSshUser")!!)
        val profile = HostProfile(name = "R8 fixture", endpoint = endpoint)
        assertEquals(profile, wireJson.decodeFromString<HostProfile>(wireJson.encodeToString(profile)))
        for (name in listOf("releaseEd25519", "releaseRsa")) {
            val prompts = object : SshPrompts {
                override suspend fun credential(id: String) = Credential(
                    privateKey = Base64.getDecoder().decode(args.getString(name)).decodeToString(), passphrase = "fixture-passphrase")
                override suspend fun verifyHostKey(host: String, port: Int, algorithm: String, fingerprint: String) = fingerprint == args.getString("releaseFingerprint")
                override suspend fun keyboard(prompt: KeyboardPrompt): List<String> = error("Unexpected challenge")
            }
            withContext(Dispatchers.IO) {
                SshConnection.connect(profile, prompts).use { ssh ->
                    assertEquals("RELEASE_OK", ssh.command("printf RELEASE_OK"))
                    val files = RemoteFiles(ssh)
                    val path = files.upload(args.getString("releaseRoot")!!, "release.txt", "R8 SFTP 中文".byteInputStream())
                    val original = files.readText(path)
                    assertEquals("R8 SFTP 中文", original.text)
                    files.saveText(original, "saved")
                    assertEquals("saved", files.readText(path).text)
                    ssh.terminal(cwd = args.getString("releaseRoot")!!).use { terminal ->
                        terminal.resize(100, 30)
                        terminal.write("printf 'RELEASE_%s_OK\\n' PTY\rpwd\rexit\r".toByteArray())
                        val output = terminal.input.bufferedReader().readText()
                        assertTrue(output, output.contains("RELEASE_PTY_OK"))
                        assertTrue(output, output.contains(args.getString("releaseRoot")!! + "\r\n"))
                        assertFalse(output, output.contains("cd --"))
                    }
                }
            }
        }
        Unit
    }

    /** Seed the documented disk format, then interact only through Android's
     * accessibility API. No UI classes are kept/unoptimized for this test. */
    private fun seedCodeHistory(command: String, output: String, code: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = context.getDatabasePath("workbench.db")
        file.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open("dev.remotegpt.app.data.AppDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val host = HostProfile(id = "release-code-fixture", name = "Release fixture", endpoint = SshEndpoint("offline.invalid", username = "fixture"))
        val thread = ThreadSummary("release-code-thread", "Release code fixture", "/tmp/release-code", "idle")
        val conversation = Conversation(thread.id, listOf(
            TimelineItem("command", "turn", "commandExecution", output, true, failed = true, executionStatus = "failed",
                commandExecution = CommandExecutionDetails(command, thread.cwd, 7, 123)),
            TimelineItem("reply", "turn", "agentMessage", "```python\n${code}```", true),
        ), status = "completed")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val table = entities.getJSONObject(index)
                db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { db.execSQL(setup.getString(it)) }
            db.version = schema.getInt("version")
            db.execSQL("INSERT OR REPLACE INTO hosts VALUES (?, ?)", arrayOf(host.id, wireJson.encodeToString(host)))
            db.execSQL("INSERT OR REPLACE INTO threads VALUES (?, ?, ?)", arrayOf(host.id, thread.id, wireJson.encodeToString(thread)))
            db.execSQL("INSERT OR REPLACE INTO conversations VALUES (?, ?, ?)", arrayOf(host.id, thread.id, wireJson.encodeToString(conversation)))
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try { automation.waitForIdle(100, 1_000) } catch (_: java.util.concurrent.TimeoutException) { /* Poll fresh nodes below. */ }
        val root = automation.rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh()) return
            found.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        visit(root)
        return found
    }

    private fun find(label: String, accept: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
        // The chat opens at its end. Search both directions, with fresh nodes
        // after every scroll; no coordinates or app-internal class names.
        repeat(2) { direction ->
            repeat(30) {
                val nodes = nodes()
                val matches = nodes.filter { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
                matches.firstOrNull(accept)?.let { return it }
                if (matches.isEmpty()) {
                    // A code block may itself scroll horizontally. Move the
                    // enclosing vertical list, and let its animation settle.
                    val action = if (direction == 0) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                                 else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                    val scroll = nodes.filter { it.isScrollable }
                        .maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
                    val actionId = if (scroll?.actionList?.contains(action) == true) action.id
                        else if (direction == 0) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    scroll?.performAction(actionId)
                }
                SystemClock.sleep(250)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(testOutputFolder(), "release-ui-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        throw AssertionError("Release UI element not found or not actionable: $label; visible nodes: " +
            nodes().filter { it.isVisibleToUser }.joinToString { "${it.text ?: it.contentDescription} (${it.className}, clickable=${it.isClickable}, scrollable=${it.isScrollable})" })
    }

    private fun testOutputFolder(): java.io.File = java.io.File(
        InstrumentationRegistry.getInstrumentation().targetContext.externalMediaDirs.first(), "additional_test_output",
    ).also { it.mkdirs() }

    private fun click(label: String) {
        // Scrolling/recomposition can invalidate an accessibility node between
        // lookup and ACTION_CLICK. Retry with fresh nodes only if rejected.
        find(label) { match ->
            var node: AccessibilityNodeInfo? = match
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(150)
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private fun label(name: String, vararg args: Any): String {
        // R8 inlines/removes R classes. Resolve names used by the production UI
        // instead of widening keep rules for instrumentation references.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "Missing UI resource: $name" }
        return context.getString(id, *args)
    }

    private fun assertClipboard(expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
