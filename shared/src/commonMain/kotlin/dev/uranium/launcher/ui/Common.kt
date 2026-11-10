package dev.uranium.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uranium.launcher.InstanceActivity
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random

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

/** A project tile: the remote icon when it loads, otherwise a lettered placeholder. */
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
        val (from, to) = remember(label) { Palette.cover(label) }
        Box(shaped.background(Brush.linearGradient(listOf(from, to))), contentAlignment = Alignment.Center) {
            Text(
                label.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Generated artwork for an instance: a gradient unique to [seed] with a scatter of translucent
 * blocks. Gives every instance a recognisable look without shipping or downloading any images.
 */
@Composable
fun InstanceCover(
    seed: String,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val (from, to) = remember(seed) { Palette.cover(seed) }
    Box(
        modifier.clip(shape).drawBehind {
            drawRect(Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(size.width, size.height)))
            val random = Random(seed.hashCode())
            repeat(9) {
                val side = size.height * (0.22f + random.nextFloat() * 0.55f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.03f + random.nextFloat() * 0.06f),
                    topLeft = Offset(random.nextFloat() * size.width - side / 2, random.nextFloat() * size.height - side / 2),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.16f),
                )
            }
            // Darken towards the bottom so text placed on the cover stays readable.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f))))
        },
        content = content,
    )
}

/** The launcher's mark: a glowing rounded tile with a "U". */
@Composable
fun BrandMark(size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Palette.Glow, Palette.GlowDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Text("U", color = Color(0xFF0A1A07), fontWeight = FontWeight.Black, fontSize = (size.value * 0.56f).sp)
    }
}

@Composable
fun Avatar(name: String, online: Boolean, size: Dp = 40.dp) {
    Box(Modifier.size(size)) {
        val (from, to) = remember(name) { Palette.cover(name) }
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.3f)).background(Brush.linearGradient(listOf(from, to))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape)
                    .background(Palette.Online).border(2.dp, Palette.Panel, CircleShape),
            )
        }
    }
}

/** A small coloured tag such as "Fabric" or "Modrinth". */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .background(if (filled) Color.Black.copy(alpha = 0.45f) else color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun LoaderTag(loader: LoaderType, modifier: Modifier = Modifier, filled: Boolean = false) =
    Tag(loader.displayName, Palette.loader(loader), modifier, filled)

fun Instance.subtitle(): String = buildString {
    append("Minecraft ").append(mcVersion)
    if (loader != LoaderType.VANILLA) {
        append(" · ").append(loader.displayName)
        loaderVersion?.let { append(' ').append(it) }
    }
}

/** A bordered panel, the launcher's basic container. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A pill-shaped choice row, used for tabs and for switching content sources. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Text(
                label(option),
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
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
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = highlighted,
            enabled = enabled,
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
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Modifier.widthIn(max = 440.dp),
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

/** What an instance is doing, in one or two lines; shared by cards, the hero and the detail page. */
@Composable
fun ActivityStatus(activity: InstanceActivity?, idleText: String, onDismiss: () -> Unit, onDark: Boolean = false) {
    val muted = if (onDark) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    when (activity) {
        is InstanceActivity.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val progress = activity.progress
            val counter = if (progress.total > 1) "  ${progress.done}/${progress.total}" else ""
            Text(
                progress.stage + counter,
                style = MaterialTheme.typography.bodySmall,
                color = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val fraction = progress.fraction
            if (fraction != null && progress.total > 1) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        InstanceActivity.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Online))
            Text("  Running", style = MaterialTheme.typography.bodySmall, color = Palette.Online)
        }
        is InstanceActivity.Failed -> StatusNote(activity.message, MaterialTheme.colorScheme.error, onDismiss)
        is InstanceActivity.Notice -> StatusNote(activity.message, muted, onDismiss)
        null -> Text(idleText, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
    }
}

@Composable
private fun StatusNote(message: String, color: Color, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
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

fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${oneDecimal(bytes / 1_048_576.0)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
