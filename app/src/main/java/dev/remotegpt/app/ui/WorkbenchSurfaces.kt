package dev.remotegpt.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class WorkbenchAccent { BLUE, TEAL, AMBER }

/**
 * A restrained glass treatment for grouped controls. Android 10 does not
 * provide a reliable backdrop-blur API, so the effect uses translucency,
 * a hairline edge and a small shadow. It keeps the surface readable while
 * still allowing the canvas tone to come through at the edges.
 */
@Composable internal fun glassSurfaceColor(
    lightAlpha: Float = .90f,
    darkAlpha: Float = .88f,
): Color {
    val dark = LocalWorkbenchDarkTheme.current
    return MaterialTheme.colorScheme.surface.copy(alpha = if (dark) darkAlpha else lightAlpha)
}

@Composable internal fun glassBorderColor(): Color {
    val dark = LocalWorkbenchDarkTheme.current
    return if (dark) Color.White.copy(alpha = .12f)
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)
}

@Composable internal fun interactiveOutlineColor(): Color = LocalWorkbenchSemanticColors.current.interactiveOutline

@Composable internal fun accentColor(accent: WorkbenchAccent): Color {
    return when (accent) {
        WorkbenchAccent.BLUE -> MaterialTheme.colorScheme.primary
        WorkbenchAccent.TEAL -> LocalWorkbenchSemanticColors.current.success
        WorkbenchAccent.AMBER -> LocalWorkbenchSemanticColors.current.warning
    }
}

/** Grouped surfaces use a quiet glass edge and diffuse shadow for depth. */
@Composable fun WorkbenchSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val base = glassSurfaceColor()
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(20.dp)
    val border = BorderStroke(1.dp, glassBorderColor())
    val elevated = modifier.shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f),
        spotColor = Color.Black.copy(alpha = 0.08f))
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), content = content)
    }
    if (onClick == null) Surface(modifier = elevated, shape = shape, color = base,
        border = border, tonalElevation = 1.dp, content = body)
    else Surface(onClick = { haptic(false); onClick() }, modifier = elevated, shape = shape, color = base,
        border = border, tonalElevation = 1.dp, content = body)
}

@Composable internal fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 60.dp, end = 24.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}
