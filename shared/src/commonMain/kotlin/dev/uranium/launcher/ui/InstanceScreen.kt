package dev.uranium.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.InstanceActivity
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.content.ContentType
import dev.uranium.launcher.content.LocalContent
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.LoaderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class InstanceTab(val label: String, val type: ContentType?) {
    MODS("Mods", ContentType.MOD),
    RESOURCE_PACKS("Resource packs", ContentType.RESOURCE_PACK),
    SHADERS("Shaders", ContentType.SHADER),
    SETTINGS("Settings", null),
}

@Composable
fun InstanceScreen(launcher: Launcher, instanceId: String, compact: Boolean, onBack: () -> Unit, onBrowse: () -> Unit) {
    val instances by launcher.instances.collectAsState()
    val activityMap by launcher.activity.collectAsState()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        // Deleted while open (or from another screen): nothing to show, go back.
        CenteredMessage("This instance no longer exists") { Button(onClick = onBack) { Text("Back to Home") } }
        return
    }
    val activity = activityMap[instance.id]
    val busy = activity is InstanceActivity.Working || activity is InstanceActivity.Running
    var tab by rememberSaveable { mutableStateOf(InstanceTab.MODS) }
    val pad = if (compact) 16.dp else 28.dp

    Column(Modifier.fillMaxSize().padding(start = pad, end = pad, top = pad), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InstanceCover(instance.id, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(if (compact) 16.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home", tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            instance.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Tag("Minecraft ${instance.mcVersion}", Color.White, filled = true)
                            LoaderTag(instance.loader, filled = true)
                        }
                    }
                    if (busy) {
                        FilledTonalButton(onClick = { launcher.stop(instance.id) }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (activity is InstanceActivity.Running) "Stop" else "Cancel")
                        }
                    } else {
                        Button(onClick = { launcher.play(instance.id) }, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                    }
                }
                if (activity != null) {
                    Box(Modifier.padding(start = 8.dp).widthIn(max = 520.dp)) {
                        ActivityStatus(activity, "", { launcher.dismissActivity(instance.id) }, onDark = true)
                    }
                }
            }
        }

        Segmented(InstanceTab.entries, tab, { it.label }, { tab = it })

        val type = tab.type
        if (type != null) {
            ContentTab(launcher, instance, type, onBrowse)
        } else {
            InstanceSettings(launcher, instance, busy, onDeleted = onBack)
        }
    }
}

@Composable
private fun ContentTab(launcher: Launcher, instance: Instance, type: ContentType, onBrowse: () -> Unit) {
    val revision by launcher.contentRevision.collectAsState()
    val items by produceState<List<LocalContent>?>(null, instance.id, type, revision) {
        value = withContext(Dispatchers.IO) { runCatching { launcher.content.list(instance.id, type) }.getOrDefault(emptyList()) }
    }
    val list = items
    val needsLoader = type == ContentType.MOD && instance.loader == LoaderType.VANILLA

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    list == null -> "Loading..."
                    list.isEmpty() -> "Nothing installed"
                    else -> "${list.size} installed, ${list.count { it.enabled }} enabled"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!needsLoader) {
                FilledTonalButton(onClick = onBrowse) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add ${type.label.lowercase()}")
                }
            }
        }
        when {
            needsLoader -> CenteredMessage(
                "Vanilla instances cannot load mods",
                "Create an instance with Fabric, Quilt, Forge or NeoForge to use mods. Resource packs and shaders work here.",
            )
            list != null && list.isEmpty() -> CenteredMessage(
                "No ${type.label.lowercase()} yet",
                "Browse Modrinth or CurseForge and install with one click. Dependencies are added automatically.",
            ) { Button(onClick = onBrowse) { Text("Browse ${type.label.lowercase()}") } }
            list != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.fileName }) { item ->
                    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(item.title, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(item.known?.provider?.label, item.fileName.removeSuffix(".disabled"), formatSize(item.size))
                                        .joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { launcher.setContentEnabled(instance.id, type, item.fileName, it) },
                            )
                            IconButton(onClick = { launcher.removeContent(instance.id, type, item.fileName) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove ${item.title}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceSettings(launcher: Launcher, instance: Instance, busy: Boolean, onDeleted: () -> Unit) {
    val platform = launcher.platform
    var name by remember(instance.id) { mutableStateOf(instance.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val maxMb = (if (platform.totalMemoryMb > 0) platform.totalMemoryMb else 8192L).coerceIn(2048, 32768).toInt()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Text("General", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { input ->
                    name = input.take(48)
                    if (name.isNotBlank()) launcher.updateInstance(instance.copy(name = name.trim()))
                },
                label = { Text("Name") },
                singleLine = true,
                isError = name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(instance.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Panel(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Text("Memory", style = MaterialTheme.typography.titleMedium)
            val override = instance.memoryMb
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use the global setting")
                    Text(
                        "Currently ${launcher.effectiveMemoryMb} MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = override == null, onCheckedChange = { global ->
                    launcher.updateInstance(instance.copy(memoryMb = if (global) null else launcher.effectiveMemoryMb))
                })
            }
            if (override != null) {
                Text("Maximum heap for this instance: $override MB")
                Slider(
                    value = override.toFloat(),
                    onValueChange = { value ->
                        val snapped = ((value / 256f).roundToInt() * 256).coerceIn(1024, maxMb)
                        launcher.updateInstance(instance.copy(memoryMb = snapped))
                    },
                    valueRange = 1024f..maxMb.toFloat(),
                )
            }
        }

        if (platform.os != OsType.ANDROID) {
            Panel(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                Text("Java", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = instance.jvmArgs.orEmpty(),
                    onValueChange = { value -> launcher.updateInstance(instance.copy(jvmArgs = value.ifBlank { null })) },
                    label = { Text("JVM arguments for this instance") },
                    placeholder = { Text("Use the global arguments") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { platform.open(launcher.paths.gameDir(instance.id).toString()) }) { Text("Open instance folder") }
            }
        }

        Panel(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(
                "Deleting removes this instance's worlds, mods and settings for good.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (confirmDelete) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete \"${instance.name}\"?", modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                    Button(onClick = { launcher.deleteInstance(instance.id); onDeleted() }) { Text("Delete forever") }
                }
            } else {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete instance") }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
