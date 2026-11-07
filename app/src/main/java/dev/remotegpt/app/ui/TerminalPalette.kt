package dev.remotegpt.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TextStyle

/** Theme only the base ANSI colors; the 256-color cube and truecolor remain literal. */
@Composable internal fun rememberTerminalPalette(): IntArray {
    val dark = LocalWorkbenchDarkTheme.current
    val colors = MaterialTheme.colorScheme
    return remember(dark, colors) {
        TerminalColorScheme().mDefaultColors.clone().apply {
            val ansi = if (dark) intArrayOf(
                0x42484F, 0xD78085, 0x8AC6A0, 0xCFB276, 0x92ACD2, 0xB79BC6, 0x80BAC4, 0xD2D6DD,
                0x858D99, 0xE69AA0, 0xA5D7B6, 0xE0C38C, 0xABC4E8, 0xCDB4DC, 0x9BD1D9, 0xF0F1F3,
            ) else intArrayOf(
                0x292B2F, 0xA32935, 0x23734B, 0x85621C, 0x345B91, 0x80528D, 0x246F7C, 0x616975,
                0x686C73, 0xB33342, 0x317947, 0x8A651F, 0x456BA3, 0x915E9C, 0x267A87, 0x515761,
            )
            ansi.forEachIndexed { index, rgb -> this[index] = 0xFF000000.toInt() or rgb }
            this[TextStyle.COLOR_INDEX_FOREGROUND] = colors.onBackground.toArgb()
            this[TextStyle.COLOR_INDEX_BACKGROUND] = colors.background.toArgb()
            this[TextStyle.COLOR_INDEX_CURSOR] = colors.primary.toArgb()
        }
    }
}
