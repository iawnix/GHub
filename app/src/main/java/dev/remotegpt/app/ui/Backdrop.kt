package dev.remotegpt.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

// Only scrolling content is a source. Foreground controls never sample themselves.
internal val LocalWorkbenchBackdrop = staticCompositionLocalOf<HazeState?> { null }
internal val LocalWorkbenchTopInset = staticCompositionLocalOf { 0.dp }
internal val LocalComposerTextHeight = staticCompositionLocalOf { 144.dp }

@Composable internal fun Modifier.workbenchBackdropSource(): Modifier =
    LocalWorkbenchBackdrop.current?.let { hazeSource(it) } ?: this

@Composable internal fun Modifier.workbenchGlass(): Modifier {
    val colors = MaterialTheme.colorScheme
    val state = LocalWorkbenchBackdrop.current ?: return background(colors.surface)
    return hazeEffect(state, HazeStyle(
        backgroundColor = colors.background,
        tint = HazeTint(colors.surface.copy(alpha = .68f)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(colors.surface.copy(alpha = .97f)),
    )) { blurEnabled = Build.VERSION.SDK_INT >= 31 }
}

/** Top padding belongs to scroll content, allowing it to pass behind the bar. */
@Composable internal fun GlassWorkbenchScaffold(
    modifier: Modifier = Modifier,
    glassEnabled: Boolean = true,
    snackbarHost: @Composable () -> Unit = {},
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberHazeState(blurEnabled = Build.VERSION.SDK_INT >= 31)
    CompositionLocalProvider(LocalWorkbenchBackdrop provides backdrop.takeIf { glassEnabled }) {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = snackbarHost,
            topBar = topBar,
        ) { padding ->
            CompositionLocalProvider(LocalWorkbenchTopInset provides if (glassEnabled) padding.calculateTopPadding() else 0.dp) {
                content(if (glassEnabled) PaddingValues(bottom = padding.calculateBottomPadding(),
                    start = padding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                    end = padding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current)) else padding)
            }
        }
    }
}

/** Shared by live chat and previews: one IME owner, measured dock, no covered last message. */
@Composable internal fun ConversationViewport(
    modifier: Modifier = Modifier,
    onBottomControls: (Int) -> Unit = {},
    topInset: Dp = LocalWorkbenchTopInset.current,
    dock: @Composable () -> Unit,
    content: @Composable BoxScope.(padding: PaddingValues, dockHeight: Dp) -> Unit,
) {
    var dockPixels by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    SideEffect { onBottomControls(dockPixels + ime) }
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val dockHeight = with(density) { dockPixels.toDp() }
        content(PaddingValues(start = 20.dp, end = 20.dp,
            top = topInset + 16.dp, bottom = dockHeight + 16.dp), dockHeight)
        CompositionLocalProvider(LocalComposerTextHeight provides (maxHeight * .35f).coerceIn(48.dp, 144.dp)) {
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 760.dp).fillMaxWidth()
                .onSizeChanged { dockPixels = it.height }) { dock() }
        }
    }
}
