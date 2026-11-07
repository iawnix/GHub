package dev.remotegpt.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotegpt.app.ui.WorkbenchIcons

/** Instantiates every production vector, including glyphs on infrequently used screens. */
@Composable internal fun IconGallery() {
    val icons = remember {
        WorkbenchIcons.javaClass.methods
            .filter { it.name.startsWith("get") && it.returnType == ImageVector::class.java && it.parameterCount == 0 }
            .sortedBy { it.name }
            .map { it.name.removePrefix("get") to (it.invoke(WorkbenchIcons) as ImageVector) }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            Text("Phosphor Bold · ${icons.size} glyphs · 16 / 20dp", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.weight(1f)) {
                items(icons, key = { it.first }) { (name, icon) ->
                    Column(Modifier.fillMaxWidth().height(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(icon, name, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(name, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                            textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}
