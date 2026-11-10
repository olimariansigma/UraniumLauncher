package dev.uranium.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Decodes PNG/JPEG/WebP bytes, or returns null for formats the platform cannot read. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/** Small in-memory icon cache. Only touched from the UI thread. */
private object ImageCache {
    private const val MAX_ENTRIES = 300
    private val entries = LinkedHashMap<String, ImageBitmap>()

    operator fun get(url: String): ImageBitmap? = entries[url]

    operator fun set(url: String, image: ImageBitmap) {
        if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        entries[url] = image
    }
}

/** A project/instance tile: the remote icon when it loads, otherwise a lettered placeholder. */
@Composable
fun IconTile(label: String, modifier: Modifier = Modifier, url: String? = null, http: Http? = null) {
    val image by produceState(initialValue = url?.let { ImageCache[it] }, url) {
        if (value != null || url.isNullOrBlank() || http == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { decodeImageBitmap(http.getBytes(url)) }.getOrNull()
        }?.also { ImageCache[url] = it }
    }
    val shaped = modifier.clip(MaterialTheme.shapes.medium)
    val loaded = image
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = shaped, contentScale = ContentScale.Crop)
    } else {
        Box(shaped.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(
                label.trim().take(1).uppercase().ifEmpty { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A chip that opens a menu of [options]; the chip itself shows the current choice. */
@Composable
fun <T> DropdownChip(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    highlighted: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = highlighted,
            onClick = { open = true },
            label = { Text("$label: ${optionLabel(selected)}", maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { open = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
fun CenteredMessage(title: String, body: String? = null, action: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            action()
        }
    }
}

/** 12345678 -> "12.3M": download counts need to be scannable, not exact. */
fun compactCount(value: Long): String = when {
    value >= 1_000_000_000 -> "${oneDecimal(value / 1_000_000_000.0)}B"
    value >= 1_000_000 -> "${oneDecimal(value / 1_000_000.0)}M"
    value >= 1_000 -> "${oneDecimal(value / 1_000.0)}K"
    else -> value.toString()
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).toLong()
    return if (tenths % 10 == 0L || tenths >= 1000) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
