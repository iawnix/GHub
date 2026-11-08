package dev.remotegpt.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The app's motion vocabulary. Durations follow Material 3: short for icons, colors and
 * small state changes, medium for pages. Compose scales every spec here by the system
 * animator duration scale, so "Remove animations" turns them off without extra checks.
 */
internal object Motion {
    const val SHORT = 120
    const val PRESS = 150
    const val STATE = 150
    const val ICON = 300
    const val ENTER = 200
    const val CONTENT = 160
    const val EXIT = 90
    const val EXPAND = 180
    const val COLLAPSE = 120
    const val PAGE = 260

    val Standard = FastOutSlowInEasing
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> short(): FiniteAnimationSpec<T> = tween(SHORT, easing = Standard)
}

/** Shared press response for prominent icon actions. Material's indication still supplies
 * the ripple; this adds the same restrained 0.96 scale everywhere this modifier is used. */
@Composable internal fun Modifier.workbenchPressScale(
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (enabled && pressed) .96f else 1f,
        tween(Motion.PRESS, easing = Motion.EmphasizedAccelerate),
        label = "press-scale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Moving down or up a hierarchy: the new page enters from the side it lives on, a short
 * distance, while the old one leaves the other way. Large slides read as slow on phones.
 */
internal fun sharedAxisX(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    // Keep the incoming page opaque from its first frame. The outgoing route can be
    // cleared as navigation completes, so a delayed fade-in would expose a blank frame.
    val enter = slideInHorizontally(tween(Motion.PAGE, easing = Motion.EmphasizedDecelerate)) { sign * it / 10 }
    val exit = slideOutHorizontally(tween(Motion.PAGE, easing = Motion.EmphasizedAccelerate)) { -sign * it / 10 } +
        fadeOut(tween(Motion.EXIT, easing = FastOutLinearInEasing))
    return ContentTransform(enter, exit, targetContentZIndex = if (forward) 1f else -1f, sizeTransform = null)
}

/** Switching between siblings (sessions, files, terminal; one server to another): no direction. */
internal fun fadeThrough(): ContentTransform = ContentTransform(
    // Sibling views do not keep a render snapshot of the old tab. Show the new view
    // immediately instead of fading through an empty frame between two state updates.
    scaleIn(tween(140, easing = LinearOutSlowInEasing), initialScale = .98f),
    fadeOut(tween(70, easing = FastOutLinearInEasing)),
    sizeTransform = null,
)

/** Icon or label swaps in place (send ↔ stop, status changes). */
internal fun swap(): ContentTransform = ContentTransform(
    fadeIn(Motion.short()) + scaleIn(Motion.short(), initialScale = .8f),
    fadeOut(tween(80)) + scaleOut(tween(80), targetScale = .8f),
    sizeTransform = null,
)

/** Content revealed under a header. Shared by projects, execution details and file changes. */
@Composable internal fun Disclosure(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(tween(Motion.EXPAND, easing = Motion.Standard), expandFrom = Alignment.Top) + fadeIn(tween(Motion.SHORT)),
        exit = shrinkVertically(tween(Motion.COLLAPSE, easing = Motion.Standard), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.EXIT)),
    ) { content() }
}

/**
 * One chevron that turns instead of swapping glyphs, so the eye follows the change.
 * [openDegrees] is 90 for a right chevron that points down, 180 for a down chevron that points up.
 */
@Composable internal fun DisclosureChevron(expanded: Boolean, icon: ImageVector, contentDescription: String?, tint: Color,
    modifier: Modifier = Modifier, openDegrees: Float = 90f) {
    val angle by animateFloatAsState(if (expanded) openDegrees else 0f,
        tween(if (expanded) Motion.EXPAND else Motion.COLLAPSE, easing = Motion.Standard), label = "disclosure-chevron")
    Icon(icon, contentDescription, modifier.size(16.dp).rotate(angle), tint = tint)
}

/** Floating prompts (pending approval, jump to latest) rise from the composer they sit on. */
@Composable internal fun RiseIn(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(tween(Motion.ENTER, easing = Motion.EmphasizedDecelerate)) { it / 3 } + fadeIn(tween(Motion.CONTENT)),
        exit = slideOutVertically(tween(Motion.SHORT, easing = Motion.EmphasizedAccelerate)) { it / 3 } + fadeOut(tween(Motion.EXIT)),
    ) { content() }
}

/**
 * Fades content in once, the first time it is composed with [animate] set. Used for
 * markdown blocks that arrive while a reply streams; history and recomposition never fade.
 */
@Composable internal fun AppearOnce(animate: Boolean, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (alpha.value < 1f) alpha.animateTo(1f, tween(140, easing = LinearOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * Keeps the last non-null value so content leaving with an exit animation does not flash
 * its empty state ("0 approvals") while it fades. Not state: reading it never recomposes.
 */
@Composable internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}
