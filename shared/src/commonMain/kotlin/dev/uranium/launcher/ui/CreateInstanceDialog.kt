package dev.uranium.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.loader.LoaderService
import dev.uranium.launcher.loader.LoaderVersion
import dev.uranium.launcher.mojang.VersionEntry
import dev.uranium.launcher.mojang.VersionManifest
import kotlinx.coroutines.CancellationException

private enum class VersionFilter(val label: String, val matches: (VersionEntry) -> Boolean) {
    RELEASES("Releases", { it.isRelease }),
    SNAPSHOTS("Snapshots", { it.isSnapshot }),
    OLD("Alpha & Beta", { it.isOld }),
}

/** What the loader lookup for the chosen game version came back with. */
private sealed interface LoaderLookup {
    data object Loading : LoaderLookup
    data class Ready(val versions: List<LoaderVersion>) : LoaderLookup
    data class Failed(val message: String) : LoaderLookup
}

@Composable
fun CreateInstanceDialog(
    launcher: Launcher,
    onDismiss: () -> Unit,
    onCreate: (name: String, version: String, loader: LoaderType, loaderVersion: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VersionFilter.RELEASES) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loader by remember { mutableStateOf(LoaderType.VANILLA) }
    var loaderVersion by remember { mutableStateOf<String?>(null) }
    var lookup by remember { mutableStateOf<LoaderLookup>(LoaderLookup.Ready(emptyList())) }
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

    // Loader builds depend on the game version, so look them up again whenever either changes.
    LaunchedEffect(loader, selected) {
        val version = selected
        loaderVersion = null
        if (loader == LoaderType.VANILLA || version == null) {
            lookup = LoaderLookup.Ready(emptyList())
            return@LaunchedEffect
        }
        lookup = LoaderLookup.Loading
        lookup = try {
            val versions = launcher.loaderVersions(loader, version)
            loaderVersion = LoaderService.pickDefault(versions)?.version
            LoaderLookup.Ready(versions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoaderLookup.Failed(e.message ?: "Could not load ${loader.displayName} versions.")
        }
    }

    val loaderReady = loader == LoaderType.VANILLA || loaderVersion != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 560.dp).heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New instance", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Name") },
                    placeholder = { Text(defaultName(selected, loader)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("MOD LOADER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoaderType.entries.forEach { option ->
                        FilterChip(selected = loader == option, onClick = { loader = option }, label = { Text(option.displayName) })
                    }
                }
                if (loader != LoaderType.VANILLA) {
                    when (val state = lookup) {
                        LoaderLookup.Loading -> Text(
                            "Looking up ${loader.displayName} versions...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is LoaderLookup.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        is LoaderLookup.Ready -> if (state.versions.isEmpty()) {
                            Text(
                                "${loader.displayName} has no build for Minecraft ${selected ?: ""} yet. Pick another version or loader.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Warning,
                            )
                        } else {
                            DropdownChip(
                                label = "${loader.displayName} version",
                                options = state.versions.take(60),
                                selected = state.versions.firstOrNull { it.version == loaderVersion } ?: state.versions.first(),
                                optionLabel = { it.version + if (it.recommended) "  (recommended)" else if (!it.stable) "  (beta)" else "" },
                                onSelect = { loaderVersion = it.version },
                            )
                        }
                    }
                }

                Text("MINECRAFT VERSION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Find a version") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VersionFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
                    }
                }

                Box(Modifier.weight(1f, fill = false).heightIn(min = 140.dp).fillMaxWidth()) {
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
                        enabled = selected != null && loaderReady,
                        onClick = {
                            selected?.let { version ->
                                onCreate(name.trim().ifEmpty { defaultName(version, loader) }, version, loader, loaderVersion)
                            }
                        },
                    ) { Text("Create") }
                }
            }
        }
    }
}

private fun defaultName(version: String?, loader: LoaderType): String = when {
    version == null -> "My instance"
    loader == LoaderType.VANILLA -> "Minecraft $version"
    else -> "${loader.displayName} $version"
}

@Composable
private fun VersionRow(version: VersionEntry, selected: Boolean, latest: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(background, MaterialTheme.shapes.small).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            version.id,
            Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (latest) {
            Tag("Latest", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            version.releaseTime.take(10),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
