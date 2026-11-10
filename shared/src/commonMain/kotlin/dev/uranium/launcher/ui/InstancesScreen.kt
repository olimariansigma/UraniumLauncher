package dev.uranium.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.uranium.launcher.InstanceActivity
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.mojang.VersionEntry
import dev.uranium.launcher.mojang.VersionManifest
import kotlinx.coroutines.CancellationException

@Composable
fun InstancesScreen(launcher: Launcher, compact: Boolean) {
    val instances by launcher.instances.collectAsState()
    val activity by launcher.activity.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showConsole by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Instance?>(null) }
    val pad = if (compact) 16.dp else 24.dp

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(start = pad, end = pad, top = pad)) {
            ScreenHeader(
                title = "Instances",
                subtitle = if (compact) null else "Each instance has its own worlds, mods and settings.",
            ) {
                OutlinedButton(onClick = { showConsole = !showConsole }) {
                    Text(if (showConsole) "Hide console" else "Console")
                }
                Button(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (compact) "New" else "New instance")
                }
            }
            Spacer(Modifier.height(16.dp))
            if (instances.isEmpty()) {
                CenteredMessage(
                    title = "No instances yet",
                    body = "Create one to download a Minecraft version and start playing.",
                ) {
                    Button(onClick = { showCreate = true }) { Text("Create your first instance") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(instances, key = { it.id }) { instance ->
                        InstanceCard(
                            instance = instance,
                            activity = activity[instance.id],
                            canOpenFolder = launcher.platform.os != OsType.ANDROID,
                            onPlay = { launcher.play(instance.id) },
                            onStop = { launcher.stop(instance.id) },
                            onDismiss = { launcher.dismissActivity(instance.id) },
                            onOpenFolder = { launcher.platform.open(launcher.paths.gameDir(instance.id).toString()) },
                            onDelete = { pendingDelete = instance },
                        )
                    }
                }
            }
        }
        if (showConsole) ConsolePanel(launcher, Modifier.fillMaxWidth().height(if (compact) 180.dp else 230.dp))
    }

    if (showCreate) {
        CreateInstanceDialog(
            launcher = launcher,
            onDismiss = { showCreate = false },
            onCreate = { name, version ->
                showCreate = false
                launcher.createInstance(name, version)
            },
        )
    }

    pendingDelete?.let { instance ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${instance.name}\"?") },
            text = { Text("This permanently removes the instance folder, including its worlds, mods and settings. Shared game files stay on disk.") },
            confirmButton = {
                TextButton(
                    onClick = { pendingDelete = null; launcher.deleteInstance(instance.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InstanceCard(
    instance: Instance,
    activity: InstanceActivity?,
    canOpenFolder: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    val working = activity as? InstanceActivity.Working
    val running = activity is InstanceActivity.Running
    val busy = working != null || running

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(instance.name, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Minecraft ${instance.mcVersion} · ${instance.loader.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${instance.name}")
                }
            }

            // Fixed-height status slot so cards in a row keep the same size as state changes.
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                when (activity) {
                    is InstanceActivity.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val progress = activity.progress
                        val counter = if (progress.total > 1) "  ${progress.done}/${progress.total}" else ""
                        Text(
                            progress.stage + counter,
                            style = MaterialTheme.typography.bodySmall,
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
                    InstanceActivity.Running -> Text(
                        "Running",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is InstanceActivity.Failed -> StatusNote(activity.message, MaterialTheme.colorScheme.error, onDismiss)
                    is InstanceActivity.Notice -> StatusNote(activity.message, MaterialTheme.colorScheme.onSurfaceVariant, onDismiss)
                    null -> Text(
                        if (instance.lastPlayedAt > 0) "Ready to play" else "Not played yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    OutlinedButton(onClick = onStop) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Stop" else "Cancel")
                    }
                } else {
                    Button(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (canOpenFolder) TextButton(onClick = onOpenFolder) { Text("Open folder") }
            }
        }
    }
}

@Composable
private fun StatusNote(message: String, color: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit) {
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

@Composable
private fun ConsolePanel(launcher: Launcher, modifier: Modifier = Modifier) {
    val lines by launcher.console.lines.collectAsState()
    val listState = rememberLazyListState()
    // Follow the tail unless the user has scrolled up to read something.
    LaunchedEffect(lines.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lines.isNotEmpty() && lastVisible >= listState.layoutInfo.totalItemsCount - 3) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Console", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { launcher.console.clear() }) { Text("Clear") }
            }
            if (lines.isEmpty()) {
                Text(
                    "Game output appears here once an instance is started.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        items(lines.size) { index ->
                            Text(lines[index], fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

private enum class VersionFilter(val label: String, val matches: (VersionEntry) -> Boolean) {
    RELEASES("Releases", { it.isRelease }),
    SNAPSHOTS("Snapshots", { it.isSnapshot }),
    OLD("Alpha & Beta", { it.isOld }),
}

@Composable
private fun CreateInstanceDialog(launcher: Launcher, onDismiss: () -> Unit, onCreate: (name: String, version: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VersionFilter.RELEASES) }
    var selected by remember { mutableStateOf<String?>(null) }
    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try {
            val loaded = launcher.meta.manifest(forceRefresh = attempt > 0)
            manifest = loaded
            if (selected == null) selected = loaded.latest.release
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load the version list."
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 520.dp).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New instance", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Name") },
                    placeholder = { Text(selected?.let { "Minecraft $it" } ?: "My instance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Find a version") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VersionFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
                    }
                }

                Box(Modifier.weight(1f, fill = false).heightIn(min = 160.dp).fillMaxWidth()) {
                    val loaded = manifest
                    when {
                        loaded != null -> {
                            val versions = remember(loaded, filter, search) {
                                loaded.versions.filter { filter.matches(it) && it.id.contains(search.trim(), ignoreCase = true) }
                            }
                            if (versions.isEmpty()) {
                                CenteredMessage("No versions match")
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(versions, key = { it.id }) { version ->
                                        VersionRow(
                                            version = version,
                                            selected = version.id == selected,
                                            latest = version.id == loaded.latest.release || version.id == loaded.latest.snapshot,
                                            onClick = { selected = version.id },
                                        )
                                    }
                                }
                            }
                        }
                        error != null -> CenteredMessage("Could not load versions", error) {
                            OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = selected != null,
                        onClick = { selected?.let { version -> onCreate(name.trim().ifEmpty { "Minecraft $version" }, version) } },
                    ) { Text("Create") }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(version: VersionEntry, selected: Boolean, latest: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(background, MaterialTheme.shapes.small).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            version.id,
            Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        if (latest) {
            Text("Latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            version.releaseTime.take(10),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
