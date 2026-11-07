package dev.remotegpt.app

import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalBackend
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.remotegpt.app.ui.*
import dev.remotegpt.terminal.SessionCallbacks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalPresentationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val bytes = ByteArrayOutputStream()
    private val sessions = mutableListOf<TerminalSession>()
    private fun session(): TerminalSession = TerminalSession(object : TerminalBackend {
        override fun write(bytes: ByteArray) { this@TerminalPresentationDeviceTest.bytes.write(bytes) }
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }, SessionCallbacks()).also { sessions += it }

    private fun content(extra: Boolean = false) {
        compose.setContent {
            val tabs = remember { listOf(TerminalTab("one", "Terminal 1", session())) }
            WorkbenchTheme {
                TerminalPane(tabs, "one", "project", "/tmp/project", ConnectionStatus.ONLINE, false, TerminalPreferences(extraKeys = extra),
                    {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
    }

    private fun view(): TerminalView {
        fun find(node: View): TerminalView? {
            if (node is TerminalView) return node
            if (node is ViewGroup) repeat(node.childCount) { find(node.getChildAt(it))?.let { return it } }
            return null
        }
        return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstNotNullOf { find(it.window.decorView) }
    }
    private fun output() = bytes.toString("UTF-8")
    private fun key(label: Int) = compose.onNodeWithContentDescription(tr(label)).performClick()

    @Test fun modifiersApplyOnceToImeTextAndCanBeLocked() {
        content()
        compose.onNodeWithContentDescription("Ctrl").performClick()
        compose.runOnIdle {
            bytes.reset()
            view().onCreateInputConnection(EditorInfo()).commitText("ab中文😀", 1)
            assertEquals("\u0001b中文😀", output())
        }
        compose.onNodeWithContentDescription("Ctrl").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, tr(R.string.terminal_modifier_off)))
        compose.onNodeWithContentDescription("Ctrl").performTouchInput { longClick() }
        compose.runOnIdle {
            bytes.reset()
            view().onCreateInputConnection(EditorInfo()).commitText("cd", 1)
            assertEquals("\u0003\u0004", output())
        }
        compose.onNodeWithContentDescription("Ctrl").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, tr(R.string.terminal_modifier_locked)))
        compose.onNodeWithContentDescription("Ctrl").performClick()
        compose.runOnIdle {
            bytes.reset()
            view().onKeyDown(KeyEvent.KEYCODE_E, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E))
            assertEquals("e", output())
        }
    }

    @Test fun arrowsHonorApplicationModeAndModifiedTabAndArrows() {
        content(extra = true)
        compose.runOnIdle { bytes.reset() }
        key(R.string.terminal_up)
        compose.runOnIdle { assertEquals("\u001b[A", output()); bytes.reset(); sessions.single().append("\u001b[?1h".toByteArray()) }
        key(R.string.terminal_up)
        compose.runOnIdle { assertEquals("\u001bOA", output()); bytes.reset() }
        compose.onNodeWithContentDescription("Ctrl").performClick()
        key(R.string.terminal_left)
        compose.runOnIdle { assertEquals("\u001b[1;5D", output()); bytes.reset() }
        compose.onNodeWithContentDescription("Shift").performClick()
        key(R.string.terminal_tab)
        compose.runOnIdle { assertEquals("\u001b[Z", output()); bytes.reset() }
        compose.onNodeWithContentDescription("Alt").performClick()
        key(R.string.terminal_right)
        compose.runOnIdle { assertEquals("\u001b[1;3C", output()); bytes.reset() }
        key(R.string.terminal_right)
        compose.runOnIdle { assertEquals("\u001bOC", output()) }
    }

    @Test fun narrowLargeTextKeepsEveryPrimaryKeyVisibleAndDistinct() {
        compose.setContent { WorkbenchTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) { TerminalKeyBar(remember { TerminalModifiers() }, false, true, {}, {}) }
            }
        } }
        val labels = listOf(tr(R.string.terminal_escape), tr(R.string.terminal_tab), "Ctrl", tr(R.string.terminal_left),
            tr(R.string.terminal_down), tr(R.string.terminal_up), tr(R.string.terminal_right))
        val bounds = labels.map { compose.onNodeWithContentDescription(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        bounds.forEach { assertTrue(it.right - it.left >= 48.dp && it.bottom - it.top >= 48.dp) }
        bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b ->
            assertFalse("Keys overlap: $a / $b", a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
        } }
    }

    @Test fun shortViewportKeepsTheCursorInsideTheVisibleTerminal() {
        content()
        compose.runOnIdle {
            val terminal = view()
            terminal.setTextSize(28)
            terminal.layout(0, 0, 600, 90)
            val emulator = sessions.single().getEmulator()!!
            assertEquals(2, emulator.mRows)
            sessions.single().append(("line\r\n".repeat(10) + "cursor_here").toByteArray())
            assertEquals(1, emulator.cursorRow)
            assertTrue(emulator.screen.transcriptText.contains("cursor_here"))
        }
    }

    @Test fun themeChangeRepaintsWithoutReplacingTerminalOrLosingHistoryAndInputState() {
        var mode by mutableStateOf("light")
        var expectedPalette = intArrayOf()
        val tab = TerminalTab("one", "Terminal 1", session())
        compose.setContent { WorkbenchTheme(mode) {
            expectedPalette = rememberTerminalPalette()
            TerminalPane(listOf(tab), "one", "project", "/tmp/project", ConnectionStatus.ONLINE, false, TerminalPreferences(),
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Ctrl").performTouchInput { longClick() }
        lateinit var originalView: TerminalView
        var transcript = ""
        var oldBackground = 0
        compose.runOnIdle {
            originalView = view()
            tab.session.append(("history line\r\n".repeat(100) + "prompt> ").toByteArray())
            originalView.topRow = -15
            transcript = tab.session.getEmulator()!!.screen.transcriptText
            oldBackground = originalView.mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
        }
        // Leave a modal open while its underlying terminal changes theme.
        compose.onNodeWithText("Terminal 1").performClick()
        compose.runOnIdle { mode = "dark" }
        compose.onNodeWithText(tr(R.string.terminal_sessions)).assertIsDisplayed()
        compose.onAllNodesWithText("Terminal 1").onLast().performClick()
        compose.runOnIdle {
            assertSame(originalView, view())
            assertSame(tab.session.getEmulator(), view().mEmulator)
            assertEquals(-15, view().topRow)
            assertEquals(transcript, view().mEmulator.screen.transcriptText)
            val actual = view().mEmulator.mColors.mCurrentColors
            assertNotEquals(oldBackground, actual[TextStyle.COLOR_INDEX_BACKGROUND])
            assertArrayEquals(expectedPalette, actual)
            bytes.reset()
            view().onCreateInputConnection(EditorInfo()).commitText("ab", 1)
            assertEquals("\u0001\u0002", output())
            mode = "light"
        }
        compose.runOnIdle {
            assertSame(originalView, view())
            assertEquals(-15, view().topRow)
            assertEquals(oldBackground, view().mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        }
    }

    @Test fun remoteColorsSurviveThemeChangesAndOscResetUsesLatestTheme() {
        var mode by mutableStateOf("light")
        var expectedPalette = intArrayOf()
        val tab = TerminalTab("one", "Terminal 1", session())
        // Remote output can arrive before the view initializes its emulator.
        tab.session.append("\u001b]11;#123456\u0007".toByteArray())
        compose.setContent { WorkbenchTheme(mode) {
            expectedPalette = rememberTerminalPalette()
            TerminalPane(listOf(tab), "one", "project", "/tmp/project", ConnectionStatus.ONLINE, false, TerminalPreferences(),
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        var explicitRed = 0
        compose.runOnIdle {
            assertEquals(0xFF123456.toInt(), view().mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            explicitRed = expectedPalette[1]
            // Explicitly setting a color to its current default still counts as an override.
            val hex = "%06x".format(explicitRed and 0xffffff)
            tab.session.append("\u001b]4;1;#$hex\u0007".toByteArray())
            mode = "dark"
        }
        compose.runOnIdle {
            val colors = view().mEmulator.mColors.mCurrentColors
            assertEquals(0xFF123456.toInt(), colors[TextStyle.COLOR_INDEX_BACKGROUND])
            assertEquals(explicitRed, colors[1])
            assertEquals(expectedPalette[TextStyle.COLOR_INDEX_FOREGROUND], colors[TextStyle.COLOR_INDEX_FOREGROUND])
            tab.session.append("\u001b]111\u0007\u001b]104;1\u0007".toByteArray())
            assertArrayEquals(expectedPalette, colors)
            // Another session must not inherit this session's theme defaults or overrides.
            assertArrayEquals(TerminalColors.COLOR_SCHEME.mDefaultColors, TerminalColors().mCurrentColors)
            tab.session.append("\u001b]10;#abcdef\u0007".toByteArray())
            tab.session.reset()
            assertArrayEquals(expectedPalette, colors)
        }
    }

    @Test fun switchingClearsModifiersAndClosingLastSessionLeavesNewAction() {
        var selected by mutableStateOf("one")
        lateinit var tabs: MutableState<List<TerminalTab>>
        compose.setContent {
            tabs = remember { mutableStateOf(listOf(TerminalTab("one", "Terminal 1", session()), TerminalTab("two", "Terminal 2", session()))) }
            WorkbenchTheme { TerminalPane(tabs.value, selected, "project", "/tmp/project", ConnectionStatus.ONLINE, false, TerminalPreferences(),
                {}, {}, { selected = it }, {}, { id -> tabs.value = tabs.value.filterNot { it.id == id } }, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("Ctrl").performClick()
        compose.onNodeWithText("Terminal 1").performClick()
        compose.onNodeWithText("Terminal 2").performClick()
        compose.onNodeWithContentDescription("Ctrl").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, tr(R.string.terminal_modifier_off)))
        compose.onNodeWithContentDescription(tr(R.string.ui_decrease_terminal_text_size)).assertDoesNotExist()
        compose.onNodeWithContentDescription(tr(R.string.ui_increase_terminal_text_size)).assertDoesNotExist()
        compose.runOnIdle { tabs.value = emptyList() }
        compose.onNodeWithText(tr(R.string.ui_new_terminal)).assertIsDisplayed().assertIsEnabled()
    }
}
