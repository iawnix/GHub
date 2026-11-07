package dev.remotegpt.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colors shared by status indicators and interactive edges. */
@Immutable
internal data class WorkbenchSemanticColors(
    val success: Color,
    val warning: Color,
    val interactiveOutline: Color,
)

private val LightSemanticColors = WorkbenchSemanticColors(
    success = Color(0xFF137B65),
    warning = Color(0xFF916018),
    interactiveOutline = Color(0xFF7E838B),
)
private val DarkSemanticColors = WorkbenchSemanticColors(
    success = Color(0xFF7CD5BE),
    warning = Color(0xFFEAC17D),
    interactiveOutline = Color(0xFF8D949F),
)

/** Resolved once for Compose surfaces, system bars and embedded native views. */
internal val LocalWorkbenchDarkTheme = staticCompositionLocalOf { false }

internal val LocalWorkbenchSemanticColors = staticCompositionLocalOf { LightSemanticColors }

// Soft neutral surfaces keep functional icons, actions, and status legible.
private val Light = lightColorScheme(
    primary = Color(0xFF4B6590), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EDF4), onPrimaryContainer = Color(0xFF344B6D),
    secondary = Color(0xFF626873), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEDEF), onSecondaryContainer = Color(0xFF41464E),
    tertiary = Color(0xFF626873), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECEDEF), onTertiaryContainer = Color(0xFF41464E),
    error = Color(0xFFAB3542), onError = Color.White,
    errorContainer = Color(0xFFFBE8EA), onErrorContainer = Color(0xFF78232D),
    inverseSurface = Color(0xFF303338), inverseOnSurface = Color(0xFFE7E8EB),
    inversePrimary = Color(0xFFB0C1DF),
    surfaceBright = Color.White, surfaceDim = Color(0xFFD9DDE0),
    scrim = Color.Black,
    // Keep the canvas just a shade deeper than content surfaces so grouped
    // controls have a quiet edge without turning every control into a card.
    background = Color(0xFFF5F6F6), onBackground = Color(0xFF292B2F),
    surface = Color.White, surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F1F2), surfaceContainer = Color(0xFFE9EBED),
    surfaceContainerHigh = Color(0xFFE2E5E7), surfaceContainerHighest = Color(0xFFD9DDE0),
    surfaceVariant = Color(0xFFE9EBED), onSurface = Color(0xFF292B2F),
    onSurfaceVariant = Color(0xFF5D6168), outline = Color(0xFF7E838B),
    outlineVariant = Color(0xFFE2E4E6), surfaceTint = Color.Transparent,
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB0C1DF), onPrimary = Color(0xFF233650),
    primaryContainer = Color(0xFF303B4B), onPrimaryContainer = Color(0xFFD7E1F1),
    secondary = Color(0xFFB9BDC5), onSecondary = Color(0xFF30343B),
    secondaryContainer = Color(0xFF303338), onSecondaryContainer = Color(0xFFDEE0E5),
    tertiary = Color(0xFFB9BDC5), onTertiary = Color(0xFF30343B),
    tertiaryContainer = Color(0xFF303338), onTertiaryContainer = Color(0xFFDEE0E5),
    error = Color(0xFFF0A7AE), onError = Color(0xFF5A1E26),
    errorContainer = Color(0xFF512C33), onErrorContainer = Color(0xFFFFDADF),
    inverseSurface = Color(0xFFE7E8EB), inverseOnSurface = Color(0xFF292B2F),
    inversePrimary = Color(0xFF4B6590),
    surfaceBright = Color(0xFF41464D), surfaceDim = Color(0xFF17191C),
    scrim = Color.Black,
    background = Color(0xFF17191C), onBackground = Color(0xFFE7E8EB),
    surface = Color(0xFF222529), surfaceContainerLowest = Color(0xFF17191C),
    surfaceContainerLow = Color(0xFF282B2F), surfaceContainer = Color(0xFF303338),
    surfaceContainerHigh = Color(0xFF383C42), surfaceContainerHighest = Color(0xFF41464D),
    surfaceVariant = Color(0xFF303338), onSurface = Color(0xFFE7E8EB),
    onSurfaceVariant = Color(0xFFB0B4BC), outline = Color(0xFF8D949F),
    outlineVariant = Color(0xFF3B3E43), surfaceTint = Color.Transparent,
)

@Composable fun WorkbenchTheme(mode: String = "system", content: @Composable () -> Unit) {
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        dev.remotegpt.app.AppStrings.initialize(androidx.compose.ui.platform.LocalContext.current)
    }
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    androidx.compose.runtime.CompositionLocalProvider(
        LocalWorkbenchSemanticColors provides if (dark) DarkSemanticColors else LightSemanticColors,
        LocalWorkbenchDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            typography = Typography(
                headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
                titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
                titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            ), content = content,
        )
    }
}
