package dev.uranium.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.InstanceActivity
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.instance.Instance

@Composable
fun HomeScreen(launcher: Launcher, compact: Boolean, onOpen: (String) -> Unit) {
    val instances by launcher.instances.collectAsState()
    val activity by launcher.activity.collectAsState()
    val accounts by launcher.accounts.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showRedeem by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Instance?>(null) }
    val pad = if (compact) 16.dp else 28.dp
    val canOpenFolder = launcher.platform.os != OsType.ANDROID

    Column(Modifier.fillMaxSize().padding(start = pad, end = pad, top = pad)) {
        ScreenHeader(
            title = if (compact) "Home" else "Welcome back, ${accounts.selected?.username ?: "Player"}",
            subtitle = when (instances.size) {
                0 -> "Create an instance to start playing."
                1 -> "1 instance ready."
                else -> "${instances.size} instances ready."
            },
        ) {
            if (compact) {
                // Phones get the secondary actions behind one button to keep the header on one line.
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Use a share code") }, onClick = { moreMenu = false; showRedeem = true })
                        if (launcher.canImport) {
                            DropdownMenuItem(text = { Text("Import from Prism") }, onClick = { moreMenu = false; showImport = true })
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = { showRedeem = true }) { Text("Use code") }
                if (launcher.canImport) OutlinedButton(onClick = { showImport = true }) { Text("Import") }
            }
            Button(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (compact) "New" else "New instance")
            }
        }
        Spacer(Modifier.height(20.dp))

        if (instances.isEmpty()) {
            EmptyHome(canImport = launcher.canImport, onCreate = { showCreate = true }, onImport = { showImport = true })
        } else {
            // The most recently played instance gets the hero spot; the rest follow in the grid.
            val featured = instances.first()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (compact) 280.dp else 250.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Hero(
                        instance = featured,
                        activity = activity[featured.id],
                        compact = compact,
                        onPlay = { launcher.play(featured.id) },
                        onStop = { launcher.stop(featured.id) },
                        onDismiss = { launcher.dismissActivity(featured.id) },
                        onOpen = { onOpen(featured.id) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "ALL INSTANCES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(instances, key = { it.id }) { instance ->
                    InstanceCard(
                        instance = instance,
                        activity = activity[instance.id],
                        canOpenFolder = canOpenFolder,
                        onPlay = { launcher.play(instance.id) },
                        onStop = { launcher.stop(instance.id) },
                        onDismiss = { launcher.dismissActivity(instance.id) },
                        onOpen = { onOpen(instance.id) },
                        onOpenFolder = { launcher.platform.open(launcher.paths.gameDir(instance.id).toString()) },
                        onDelete = { pendingDelete = instance },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateInstanceDialog(
            launcher = launcher,
            onDismiss = { showCreate = false },
            onCreate = { name, version, loader, loaderVersion ->
                showCreate = false
                launcher.createInstance(name, version, loader, loaderVersion)
            },
        )
    }
    if (showImport) ImportDialog(launcher, onDismiss = { showImport = false })
    if (showRedeem) RedeemDialog(launcher, onDismiss = { showRedeem = false }, onOpen = onOpen)

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
private fun EmptyHome(canImport: Boolean, onCreate: () -> Unit, onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        InstanceCover("uranium-welcome", Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(48.dp)
                Spacer(Modifier.height(4.dp))
                Text("Start your first world", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    "Pick any Minecraft version, add Fabric, Quilt, Forge or NeoForge if you like, and the launcher " +
                        "downloads everything it needs, Java included.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onCreate) { Text("Create an instance") }
                    if (canImport) FilledTonalButton(onClick = onImport) { Text("Import from Prism Launcher") }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    instance: Instance,
    activity: InstanceActivity?,
    compact: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val busy = activity is InstanceActivity.Working || activity is InstanceActivity.Running
    InstanceCover(
        seed = instance.id,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 170.dp else 200.dp)
            .clip(MaterialTheme.shapes.extraLarge).clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(if (compact) 20.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (instance.lastPlayedAt > 0) "JUMP BACK IN" else "READY TO PLAY",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                instance.name,
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("Minecraft ${instance.mcVersion}", Color.White, filled = true)
                LoaderTag(instance.loader, filled = true)
            }
            Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) {
                    FilledTonalButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (activity is InstanceActivity.Running) "Stop" else "Cancel")
                    }
                } else {
                    Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 30.dp, vertical = 14.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (activity != null) {
                    Box(Modifier.weight(1f).widthIn(max = 420.dp)) {
                        ActivityStatus(activity, "", onDismiss, onDark = true)
                    }
                }
            }
        }
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
    onOpen: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = activity is InstanceActivity.Running
    val busy = activity is InstanceActivity.Working || running
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant),
        // Clip first so the hover and press highlights follow the rounded corners.
        modifier = Modifier.clip(MaterialTheme.shapes.large).hoverable(interaction).clickable(onClick = onOpen),
    ) {
        Column {
            InstanceCover(instance.id, Modifier.fillMaxWidth().height(84.dp), shape = RoundedCornerShape(0.dp)) {
                LoaderTag(instance.loader, Modifier.align(Alignment.BottomStart).padding(12.dp), filled = true)
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${instance.name}", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Manage") }, onClick = { menu = false; onOpen() })
                        if (canOpenFolder) DropdownMenuItem(text = { Text("Open folder") }, onClick = { menu = false; onOpenFolder() })
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            enabled = !busy,
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text(instance.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        instance.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Fixed-height status slot so cards in a row keep the same size as state changes.
                Box(Modifier.fillMaxWidth().heightIn(min = 34.dp), contentAlignment = Alignment.CenterStart) {
                    ActivityStatus(activity, if (instance.lastPlayedAt > 0) "Ready to play" else "Not played yet", onDismiss)
                }
                if (busy) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Stop" else "Cancel")
                    }
                } else {
                    FilledTonalButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                }
            }
        }
    }
}
