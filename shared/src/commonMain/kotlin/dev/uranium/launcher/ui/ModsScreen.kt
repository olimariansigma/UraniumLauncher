package dev.uranium.launcher.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.modrinth.ModrinthHit
import dev.uranium.launcher.modrinth.ModrinthProjectType
import dev.uranium.launcher.modrinth.ModrinthQuery
import dev.uranium.launcher.modrinth.ModrinthSort
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20
private const val ANY = ""

@Composable
fun ModsScreen(launcher: Launcher, compact: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ModrinthProjectType.MOD) }
    var gameVersion by rememberSaveable { mutableStateOf(ANY) }
    var loader by rememberSaveable { mutableStateOf(ANY) }
    var sort by rememberSaveable { mutableStateOf(ModrinthSort.RELEVANCE) }

    var hits by remember { mutableStateOf<List<ModrinthHit>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun query(offset: Int) = ModrinthQuery(
        text = text,
        projectType = type,
        gameVersion = gameVersion.ifEmpty { null },
        loader = loader.ifEmpty { null },
        sort = sort,
        offset = offset,
        limit = PAGE_SIZE,
    )

    // Every filter change restarts the search; the delay debounces typing.
    LaunchedEffect(text, type, gameVersion, loader, sort, attempt) {
        loading = true
        error = null
        delay(300)
        try {
            val result = launcher.modrinth.search(query(0))
            hits = result.hits
            total = result.totalHits
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hits = emptyList()
            total = 0
            error = e.message ?: "Search failed."
        }
        loading = false
    }

    val releases by produceState(initialValue = emptyList<String>()) {
        value = runCatching { launcher.meta.manifest().versions.filter { it.isRelease }.map { it.id } }.getOrDefault(emptyList())
    }

    val pad = if (compact) 16.dp else 24.dp
    Column(Modifier.fillMaxSize().padding(start = pad, end = pad, top = pad)) {
        ScreenHeader(title = "Browse Modrinth", subtitle = if (compact) null else "Search mods, modpacks, resource packs and shaders.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search ${type.label.lowercase()}") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DropdownChip("Type", ModrinthProjectType.entries, type, { it.label }, { type = it }, highlighted = true)
            DropdownChip(
                "Minecraft", listOf(ANY) + releases, gameVersion, { it.ifEmpty { "Any" } }, { gameVersion = it },
                highlighted = gameVersion.isNotEmpty(),
            )
            if (type.usesLoaders) {
                val loaders = listOf(ANY) + LoaderType.entries.mapNotNull { it.modrinthId }
                DropdownChip(
                    "Loader", loaders, loader,
                    { id -> LoaderType.entries.firstOrNull { it.modrinthId == id }?.displayName ?: "Any" },
                    { loader = it },
                    highlighted = loader.isNotEmpty(),
                )
            }
            DropdownChip("Sort", ModrinthSort.entries, sort, { it.label }, { sort = it })
        }

        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val failure = error
        when {
            failure != null -> CenteredMessage("Could not reach Modrinth", failure) {
                OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
            }
            hits.isEmpty() && !loading -> CenteredMessage("No results", "Try a different search or loosen the filters.")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        if (total > 0) "$total results" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(hits, key = { it.projectId }) { hit ->
                    ProjectCard(hit, launcher.http, compact) { launcher.platform.open(hit.pageUrl) }
                }
                if (hits.size < total) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (loadingMore) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            } else {
                                OutlinedButton(onClick = {
                                    loadingMore = true
                                    scope.launch {
                                        try {
                                            val more = launcher.modrinth.search(query(hits.size))
                                            val known = hits.mapTo(HashSet()) { it.projectId }
                                            hits = hits + more.hits.filter { it.projectId !in known }
                                            total = more.totalHits
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = e.message ?: "Search failed."
                                        } finally {
                                            loadingMore = false
                                        }
                                    }
                                }) { Text("Load more") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectCard(hit: ModrinthHit, http: Http, compact: Boolean, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            IconTile(hit.title, Modifier.size(if (compact) 48.dp else 64.dp), url = hit.iconUrl, http = http)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "by ${hit.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    hit.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tags = (hit.displayCategories.ifEmpty { hit.categories }).take(if (compact) 2 else 4)
                    Text(
                        (listOf("${compactCount(hit.downloads)} downloads") + tags).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpen) { Text("View page") }
                }
            }
        }
    }
}
