package dev.remotegpt.app

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.remotegpt.app.ui.ChatMathRenderer
import dev.remotegpt.app.ui.TimelineCard
import dev.remotegpt.app.ui.WorkbenchTheme
import dev.remotegpt.client.TimelineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathRenderingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeMathDrawsFractionsIntegralsMatricesAndUsesThemeAndFontSize() = runBlocking {
        val samples = listOf("\\(\\frac{a}{b}\\)", "\\[\\int_0^1 x^2 dx=\\frac{1}{3}\\]",
            "\\[\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}\\]", "\$x_i^2+\\alpha\$")
        for (source in samples) {
            val light = ChatMathRenderer.render(source, 40f, android.graphics.Color.BLACK)
            assertNotNull(source, light)
            assertSame(light, ChatMathRenderer.render(source, 40f, android.graphics.Color.BLACK))
            val dark = ChatMathRenderer.render(source, 40f, android.graphics.Color.WHITE)!!
            val large = ChatMathRenderer.render(source, 60f, android.graphics.Color.WHITE)!!
            assertTrue(large.intrinsicHeight > dark.intrinsicHeight)
            for ((drawable, expected) in listOf(light!! to 0x000000, dark to 0xffffff)) {
                val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
                drawable.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Formula must paint visible pixels", pixels.any { (it ushr 24) > 200 && (it and 0xffffff) == expected })
                bitmap.recycle()
            }
        }
        assertNull(ChatMathRenderer.render("\\(\\notARealCommand{x}\\)", 40f, -1))
        assertNull(ChatMathRenderer.render("\\(" + "{".repeat(100) + "x" + "}".repeat(100) + "\\)", 40f, -1))
        assertNull(ChatMathRenderer.render("\\(\\newcommand{\\a}{x}\\a\\)", 40f, -1))
    }

    @Test fun inlineTableListAndWideFormulasRenderAndDisplayCopyKeepsTex() {
        val inline = "\$x^2\$"
        val list = "\\(\\frac{a}{b}\\)"
        val cell = "\$|x|\$"
        val wide = "\\[" + (1..32).joinToString("+") { "x_{$it}" } + "\\]"
        val prices = "Prices: \$5 and \$10."
        var text by mutableStateOf("中文 $inline\n\n- $list\n\n| Value |\n| --- |\n| $cell |\n\n$prices\n\n$wide")
        compose.setContent {
            WorkbenchTheme("dark") {
                Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                    TimelineCard(TimelineItem("math", "turn", "agentMessage", text, false), {})
                }
            }
        }
        for (source in listOf(inline, list, cell, wide)) {
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(source, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Missing formula $source\n" + compose.onRoot(useUnmergedTree = true).printToString(), failure)
            }
            compose.onNodeWithContentDescription(source, useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText(prices).assertExists()
        // A later incomplete formula must not replace an earlier rendered one.
        compose.runOnIdle { text += "\n\nStreaming \\(\\frac{" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Streaming \\(\\frac{", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(inline, useUnmergedTree = true).assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onAllNodesWithText(context.getString(R.string.ui_copy)).onLast().performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(wide.drop(2).dropLast(2), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
