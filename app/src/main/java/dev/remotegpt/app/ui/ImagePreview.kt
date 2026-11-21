@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.remotegpt.app.ui

import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import dev.remotegpt.app.R
import dev.remotegpt.app.tr
import java.io.File

/** Read only image metadata off the UI thread, before allocating thumbnail space. */
private fun imageDimensions(path: String): IntSize? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    if (orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) IntSize(bounds.outHeight, bounds.outWidth)
    else IntSize(bounds.outWidth, bounds.outHeight)
}.getOrNull()

@Composable internal fun ConversationImage(
    path: String,
    local: String?,
    loading: Boolean,
    onLoad: () -> Unit,
    onOpen: () -> Unit,
) {
    val name = path.substringAfterLast('/').ifBlank { tr(R.string.ui_image) }
    var requested by remember(path) { mutableStateOf(false) }
    var decodeFailed by remember(local) { mutableStateOf(false) }
    var retryGeneration by remember(local) { mutableIntStateOf(0) }
    val dimensions by produceState<IntSize?>(null, local, retryGeneration) {
        value = null
        value = local?.let { withContext(Dispatchers.IO) { imageDimensions(it) ?: IntSize.Zero } }
    }
    val size = dimensions
    val busy = loading || (local != null && size == null)
    val invalid = decodeFailed || size == IntSize.Zero
    if (local != null && size != null && !invalid) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val naturalWidth = with(density) { size.width.toDp() }
            val naturalHeight = with(density) { size.height.toDp() }
            val factor = minOf(1f, maxWidth / naturalWidth, 260.dp / naturalHeight)
            val shape = RoundedCornerShape(10.dp)
            val edge = if (LocalWorkbenchDarkTheme.current) Color.White else Color.Black
            key(local, retryGeneration) {
                var decoding by remember { mutableStateOf(true) }
                Box(
                    Modifier.size(naturalWidth * factor, naturalHeight * factor)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, edge.copy(alpha = .1f), shape)
                        .clickable(role = Role.Button, onClickLabel = tr(R.string.ui_remote_image_preview), onClick = onOpen),
                ) {
                    AsyncImage(
                        model = File(local),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        onSuccess = { decoding = false },
                        onError = { decodeFailed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (decoding) CircularProgressIndicator(
                        Modifier.size(18.dp).align(Alignment.Center), strokeWidth = 2.dp,
                    )
                }
            }
        }
    } else {
        val retry = (requested && !busy) || invalid
        val action = tr(if (retry) R.string.ui_retry_now else R.string.ui_load_image_preview)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !busy, role = Role.Button, onClickLabel = action) {
                    requested = true
                    if (invalid) {
                        decodeFailed = false
                        retryGeneration++
                    } else onLoad()
                }
                .heightIn(min = 48.dp)
                .semantics { if (busy) stateDescription = tr(R.string.ui_image_preview_loading) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(WorkbenchIcons.Image, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            if (retry) Text(tr(R.string.ui_retry_now), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(if (retry) WorkbenchIcons.Refresh else WorkbenchIcons.Search, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ImageThumbnail(
    model: Any,
    name: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var state by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        AsyncImage(
            model = model,
            contentDescription = name,
            contentScale = contentScale,
            onState = { state = it },
            modifier = Modifier.fillMaxSize(),
        )
        if (state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.Center), strokeWidth = 2.dp)
        } else if (state is AsyncImagePainter.State.Error) {
            Icon(WorkbenchIcons.Image, tr(R.string.ui_image_unavailable), Modifier.size(20.dp).align(Alignment.Center),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun RemoteImageViewer(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offsetX by remember(path) { mutableFloatStateOf(0f) }
    var offsetY by remember(path) { mutableFloatStateOf(0f) }
    var state by remember(path) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val reset = {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    val zoomIn = { scale = (scale + .5f).coerceAtMost(6f) }
    val zoomOut = { scale = (scale - .5f).coerceAtLeast(1f); if (scale == 1f) reset() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding().clip(androidx.compose.ui.graphics.RectangleShape)) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(path)).build(),
                contentDescription = tr(R.string.ui_remote_image_preview),
                contentScale = ContentScale.Fit,
                onState = { state = it },
                modifier = Modifier.fillMaxSize()
                    .semantics {
                        stateDescription = tr(R.string.ui_image_zoom_percent, (scale * 100).toInt())
                        customActions = listOf(
                            CustomAccessibilityAction(tr(R.string.ui_zoom_in)) { zoomIn(); true },
                            CustomAccessibilityAction(tr(R.string.ui_zoom_out)) { zoomOut(); true },
                            CustomAccessibilityAction(tr(R.string.ui_reset_image_zoom)) { reset(); true },
                        )
                    }
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            val limit = (scale - 1f) * 720f
                            offsetX = (offsetX + pan.x).coerceIn(-limit, limit)
                            offsetY = (offsetY + pan.y).coerceIn(-limit, limit)
                        }
                    }
                    .pointerInput(path) { detectTapGestures(onDoubleTap = { reset() }) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
            if (state is AsyncImagePainter.State.Loading || state is AsyncImagePainter.State.Empty) {
                CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.Center), strokeWidth = 2.dp)
            }
            if (state is AsyncImagePainter.State.Error) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(WorkbenchIcons.Image, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr(R.string.ui_image_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                IconButton(onClick = onClose) { Icon(WorkbenchIcons.Close, tr(R.string.ui_close), Modifier.size(22.dp)) }
            }
            Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp), shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = zoomOut, enabled = scale > 1f) { Icon(WorkbenchIcons.TextDecrease, tr(R.string.ui_zoom_out)) }
                    TextButton(onClick = reset, modifier = Modifier.semantics { contentDescription = tr(R.string.ui_reset_image_zoom) }) { Text(tr(R.string.ui_image_zoom_percent, (scale * 100).toInt())) }
                    IconButton(onClick = zoomIn, enabled = scale < 6f) { Icon(WorkbenchIcons.TextIncrease, tr(R.string.ui_zoom_in)) }
                }
            }
        }
    }
}
