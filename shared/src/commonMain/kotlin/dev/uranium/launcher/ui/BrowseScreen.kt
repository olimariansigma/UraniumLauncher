package dev.uranium.launcher.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
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
import dev.uranium.launcher.ContentJob
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.content.ContentIndex
import dev.uranium.launcher.content.ContentProject
import dev.uranium.launcher.content.ContentProvider
import dev.uranium.launcher.content.ContentQuery
import dev.uranium.launcher.content.ContentSort
import dev.uranium.launcher.content.ContentType
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 20
private const val ANY = ""

/**
 * Search Modrinth or CurseForge. With a [target] instance selected, results are filtered to what
 * that instance can run and every result gets an Install button.
 */
@Composable
fun BrowseScreen(launcher: Launcher, compact: Boolean, target: String?, onTargetChange: (String?) -> Unit) {
    val instances by launcher.instances.collectAsState()
    val jobs by launcher.contentJobs.collectAsState()
    val revision by launcher.contentRevision.collectAsState()
    val settings by launcher.settings.collectAsState()
    val instance = instances.firstOrNull { it.id == target }

    var provider by rememberSaveable { mutableStateOf(ContentProvider.MODRINTH) }
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ContentType.MOD) }
    var freeVersion by rememberSaveable { mutableStateOf(ANY) }
    var freeLoader by rememberSaveable { mutableStateOf(ANY) }
    var sort by rememberSaveable { mutableStateOf(ContentSort.RELEVANCE) }

    var hits by remember { mutableStateOf<List<ContentProject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // An instance pins the game version and loader; without one the player filters freely.
    val gameVersion = instance?.mcVersion ?: freeVersion.ifEmpty { null }
    val loader = instance?.loader?.takeIf { it != LoaderType.VANILLA }
        ?: LoaderType.entries.firstOrNull { it.name == freeLoader }
    val source = launcher.content.source(provider)
    // Read through `settings` so adding an API key in Settings re-enables the source immediately.
    val unavailable = remember(provider, settings) { source.unavailableReason }

    fun query(offset: Int) = ContentQuery(text, type, gameVersion, loader, sort, offset, PAGE_SIZE)

    // Every filter change restarts the search; the delay debounces typing.
    LaunchedEffect(provider, text, type, gameVersion, loader, sort, attempt, unavailable) {
        error = null
        if (unavailable != null) {
            hits = emptyList(); total = 0; loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(300)
        try {
            val result = source.search(query(0))
            hits = result.hits
            total = result.total
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
    val index by produceState(ContentIndex(), instance?.id, revision) {
        value = instance?.let { withContext(Dispatchers.IO) { launcher.content.index(it.id) } } ?: ContentIndex()
    }

    val pad = if (compact) 16.dp else 28.dp
    Column(Modifier.fillMaxSize().padding(start = pad, end = pad, top = pad)) {
        ScreenHeader(
            title = "Browse",
            subtitle = if (compact) null else "Mods, resource packs and shaders from Modrinth and CurseForge.",
        ) {
            Segmented(ContentProvider.entries, provider, { it.label }, { provider = it })
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search ${type.label.lowercase()} on ${provider.label}") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DropdownChip(
                "Install to",
                listOf<Instance?>(null) + instances,
                instance,
                { it?.name ?: "No instance" },
                { onTargetChange(it?.id) },
                highlighted = instance != null,
            )
            DropdownChip("Type", ContentType.entries, type, { it.label }, { type = it })
            if (instance == null) {
                DropdownChip(
                    "Minecraft", listOf(ANY) + releases, freeVersion, { it.ifEmpty { "Any" } }, { freeVersion = it },
                    highlighted = freeVersion.isNotEmpty(),
                )
                if (type.usesLoaders) {
                    DropdownChip(
                        "Loader",
                        listOf(ANY) + LoaderType.entries.filter { it != LoaderType.VANILLA }.map { it.name },
                        freeLoader,
                        { id -> LoaderType.entries.firstOrNull { it.name == id }?.displayName ?: "Any" },
                        { freeLoader = it },
                        highlighted = freeLoader.isNotEmpty(),
                    )
                }
            }
            DropdownChip("Sort", ContentSort.entries, sort, { it.label }, { sort = it })
        }
        if (instance != null) {
            Text(
                "Showing ${type.label.lowercase()} for ${instance.subtitle()}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val failure = error
        when {
            unavailable != null -> CenteredMessage("${provider.label} is not set up", unavailable)
            failure != null -> CenteredMessage("Could not reach ${provider.label}", failure) {
                OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
            }
            hits.isEmpty() && !loading -> CenteredMessage("No results", "Try a different search or loosen the filters.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Text(
                        if (total > 0) "${compactCount(total.toLong())} results" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(hits, key = { it.key }) { hit ->
                    val jobKey = instance?.let { launcher.contentJobKey(it.id, hit) }
                    ProjectCard(
                        hit = hit,
                        http = launcher.http,
                        compact = compact,
                        action = installAction(instance, hit, index, jobKey?.let { jobs[it] }),
                        onInstall = { instance?.let { launcher.installContent(it.id, hit) } },
                        onDismissError = { jobKey?.let(launcher::dismissContentJob) },
                        onOpen = { launcher.platform.open(hit.pageUrl) },
                    )
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
                                            val more = source.search(query(hits.size))
                                            val known = hits.mapTo(HashSet()) { it.key }
                                            hits = hits + more.hits.filter { it.key !in known }
                                            total = more.total
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
            }
        }
    }
}

/** What the button on a result card should offer. */
private sealed interface InstallAction {
    data object None : InstallAction
    data class Unavailable(val reason: String) : InstallAction
    data object Install : InstallAction
    data class Working(val stage: String) : InstallAction
    data object Installed : InstallAction
    data class Failed(val message: String) : InstallAction
}

private fun installAction(instance: Instance?, hit: ContentProject, index: ContentIndex, job: ContentJob?): InstallAction = when {
    instance == null -> InstallAction.None
    job is ContentJob.Working -> InstallAction.Working(job.progress.stage)
    job is ContentJob.Failed -> InstallAction.Failed(job.message)
    index.has(hit.provider, hit.id) -> InstallAction.Installed
    hit.type.folder == null -> InstallAction.Unavailable("Modpacks cannot be installed yet")
    hit.type == ContentType.MOD && instance.loader == LoaderType.VANILLA -> InstallAction.Unavailable("Needs a mod loader")
    else -> InstallAction.Install
}

@Composable
private fun ProjectCard(
    hit: ContentProject,
    http: Http,
    compact: Boolean,
    action: InstallAction,
    onInstall: () -> Unit,
    onDismissError: () -> Unit,
    onOpen: () -> Unit,
) {
    Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(Modifier.fillMaxWidth()) {
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
                    if (hit.author.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "by ${hit.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    hit.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (listOf("${compactCount(hit.downloads)} downloads") + hit.categories.take(if (compact) 2 else 4)).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpen) { Text("View page") }
                    when (action) {
                        InstallAction.None -> Unit
                        InstallAction.Install -> Button(onClick = onInstall) { Text("Install") }
                        InstallAction.Installed -> Row(
                            Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Palette.Online, modifier = Modifier.size(18.dp))
                            Text("  Installed", style = MaterialTheme.typography.labelLarge, color = Palette.Online)
                        }
                        is InstallAction.Working -> Row(
                            Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Installing", style = MaterialTheme.typography.labelLarge)
                        }
                        is InstallAction.Unavailable -> Text(
                            action.reason,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is InstallAction.Failed -> OutlinedButton(onClick = onInstall) { Text("Retry") }
                    }
                }
                if (action is InstallAction.Failed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            action.message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

