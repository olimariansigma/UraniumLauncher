package dev.uranium.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.content.BlockedDownload
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.importer.ForeignInstance
import dev.uranium.launcher.importer.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface ImportPhase {
    data class Scanning(val progress: ScanProgress) : ImportPhase
    data object Choosing : ImportPhase
    data class Importing(val name: String, val progress: Progress, val position: Int, val count: Int) : ImportPhase
    data class Finished(val imported: Int, val failures: List<String>) : ImportPhase
}

/**
 * Finds Prism Launcher (and MultiMC / PolyMC) instances on this computer and copies the chosen
 * ones in. Closing the dialog cancels whatever is in progress.
 */
@Composable
fun ImportDialog(launcher: Launcher, onDismiss: () -> Unit) {
    var phase by remember { mutableStateOf<ImportPhase>(ImportPhase.Scanning(ScanProgress(0, 0, ""))) }
    var found by remember { mutableStateOf<List<ForeignInstance>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folder by remember { mutableStateOf("") }
    var folderNote by remember { mutableStateOf<String?>(null) }
    var scanRound by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(scanRound) {
        phase = ImportPhase.Scanning(ScanProgress(0, 0, ""))
        val result = try {
            launcher.scanForImports { progress -> phase = ImportPhase.Scanning(progress) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        found = result
        chosen = result.mapTo(HashSet()) { it.path.toString() }
        phase = ImportPhase.Choosing
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 600.dp).heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Import from Prism Launcher", style = MaterialTheme.typography.headlineSmall)
                when (val current = phase) {
                    is ImportPhase.Scanning -> {
                        Text(
                            "Searching this computer for Prism Launcher, MultiMC and PolyMC instances...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "${current.progress.foldersScanned} folders checked, ${current.progress.found} instances found",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            current.progress.current,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                        }
                    }

                    ImportPhase.Choosing -> {
                        Text(
                            if (found.isEmpty()) "No instances were found automatically. If yours are in an unusual place, paste the folder below."
                            else "Found ${found.size} ${if (found.size == 1) "instance" else "instances"}. Worlds, mods and settings are copied; the originals are left untouched.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (found.isNotEmpty()) {
                            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(found, key = { it.path.toString() }) { item ->
                                    val key = item.path.toString()
                                    val checked = key in chosen
                                    Row(
                                        Modifier.fillMaxWidth().clickable { chosen = if (checked) chosen - key else chosen + key }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = { on -> chosen = if (on) chosen + key else chosen - key })
                                        IconTile(item.name, Modifier.size(38.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "Minecraft ${item.mcVersion} · ${item.loader.displayName} · ${item.origin}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = folder,
                                onValueChange = { folder = it; folderNote = null },
                                label = { Text("Other folder") },
                                placeholder = { Text("Path to an instance or instances folder") },
                                supportingText = folderNote?.let { note -> { Text(note) } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                enabled = folder.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        val extra = runCatching { launcher.scanFolderForImports(folder) }.getOrDefault(emptyList())
                                        val known = found.mapTo(HashSet()) { it.path.toString() }
                                        val fresh = extra.filter { it.path.toString() !in known }
                                        found = found + fresh
                                        chosen = chosen + fresh.map { it.path.toString() }
                                        folderNote = if (extra.isEmpty()) "No instances in that folder." else "Added ${fresh.size}."
                                    }
                                },
                            ) { Text("Add") }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scanRound++ }) { Text("Search again") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = chosen.isNotEmpty(),
                                onClick = {
                                    val selection = found.filter { it.path.toString() in chosen }
                                    scope.launch {
                                        val failures = ArrayList<String>()
                                        var imported = 0
                                        selection.forEachIndexed { position, item ->
                                            phase = ImportPhase.Importing(item.name, Progress("Starting"), position + 1, selection.size)
                                            try {
                                                launcher.importInstance(item) { progress ->
                                                    phase = ImportPhase.Importing(item.name, progress, position + 1, selection.size)
                                                }
                                                imported++
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                failures += e.message ?: "Could not import ${item.name}"
                                            }
                                        }
                                        phase = ImportPhase.Finished(imported, failures)
                                    }
                                },
                            ) { Text("Import ${chosen.size}") }
                        }
                    }

                    is ImportPhase.Importing -> {
                        Text("Importing ${current.name} (${current.position} of ${current.count})", style = MaterialTheme.typography.titleMedium)
                        val fraction = current.progress.fraction
                        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            current.progress.stage + if (current.progress.total > 0) "  ${current.progress.done}/${current.progress.total} files" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                        }
                    }

                    is ImportPhase.Finished -> {
                        Text(
                            "Imported ${current.imported} ${if (current.imported == 1) "instance" else "instances"}. " +
                                "The first launch of each downloads its Minecraft version and loader.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        current.failures.forEach { failure ->
                            Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = onDismiss) { Text("Done") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Some CurseForge authors do not let third-party launchers download their files. This lists those
 * files with a link to each download page; the launcher picks them up from the Downloads folder.
 */
@Composable
fun BlockedDownloadsDialog(launcher: Launcher, blocked: List<BlockedDownload>, onDismiss: () -> Unit) {
    val watching = launcher.platform.downloadsDir != null
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 600.dp).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Manual download needed", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The authors of these files only allow downloads from the CurseForge website. Open each page and download the file" +
                        if (watching) "; it is added to the instance automatically as soon as it appears in your Downloads folder."
                        else ", then paste the path to the downloaded file below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(blocked, key = { it.key }) { item -> BlockedRow(launcher, item, watching) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (watching) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "  Watching your Downloads folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun BlockedRow(launcher: Launcher, item: BlockedDownload, watching: Boolean) {
    var path by remember(item.key) { mutableStateOf("") }
    var wrong by remember(item.key) { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.project.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.file.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { launcher.dismissBlocked(item.key) }) { Text("Skip") }
            Button(onClick = { item.file.manualUrl?.let(launcher.platform::open) }) { Text("Open page") }
        }
        if (!watching) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; wrong = false },
                    label = { Text("Downloaded file") },
                    isError = wrong,
                    supportingText = if (wrong) ({ Text("That is not the expected file.") }) else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(enabled = path.isNotBlank(), onClick = { wrong = !launcher.completeBlockedFrom(item.key, path) }) { Text("Use") }
            }
        }
    }
}

