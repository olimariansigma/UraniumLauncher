package dev.uranium.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.BuildInfo
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.account.Account

/** One launcher per process: it owns running games, so it must outlive any single screen. */
object LauncherHolder {
    val launcher: Launcher by lazy { Launcher() }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    BROWSE("Browse", Icons.Default.Search),
    ACCOUNTS("Accounts", Icons.Default.Person),
    CONSOLE("Console", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun App() {
    val launcher = remember { LauncherHolder.launcher }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // The instance whose detail page is open, and the one Browse installs into.
    var openInstance by rememberSaveable { mutableStateOf<String?>(null) }
    var browseTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showBlocked by remember { mutableStateOf(false) }

    val accounts by launcher.accounts.collectAsState()
    val blocked by launcher.blocked.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(launcher) { launcher.notices.collect { snackbar.showSnackbar(it) } }
    // Pop the manual-download dialog as soon as something lands in the queue.
    LaunchedEffect(blocked.size) { showBlocked = blocked.isNotEmpty() }

    UraniumTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(
                Modifier.fillMaxSize().drawBehind {
                    // A faint glow in the top-left corner gives the dark canvas some depth.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Palette.Glow.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width * 0.12f, 0f),
                            radius = size.maxDimension * 0.55f,
                        )
                    )
                }.windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // Side bar on desktop and tablets, bottom bar on phones.
                val wide = maxWidth >= 760.dp
                val content: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier) {
                        when (tab) {
                            Tab.HOME -> {
                                val instanceId = openInstance
                                if (instanceId != null) {
                                    InstanceScreen(
                                        launcher, instanceId, compact = !wide,
                                        onBack = { openInstance = null },
                                        onBrowse = { browseTarget = instanceId; tab = Tab.BROWSE },
                                    )
                                } else {
                                    HomeScreen(launcher, compact = !wide, onOpen = { openInstance = it })
                                }
                            }
                            Tab.BROWSE -> BrowseScreen(launcher, compact = !wide, target = browseTarget, onTargetChange = { browseTarget = it })
                            Tab.ACCOUNTS -> AccountsScreen(launcher, compact = !wide)
                            Tab.CONSOLE -> ConsoleScreen(launcher, compact = !wide)
                            Tab.SETTINGS -> SettingsScreen(launcher, compact = !wide)
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        SideBar(tab, accounts.selected, onSelect = { tab = it })
                        content(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        content(Modifier.weight(1f).fillMaxWidth())
                        NavigationBar(containerColor = Palette.Panel, windowInsets = WindowInsets(0, 0, 0, 0)) {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 16.dp else 88.dp))
            }
        }

        if (showBlocked && blocked.isNotEmpty()) {
            BlockedDownloadsDialog(launcher, blocked, onDismiss = { showBlocked = false })
        }
    }
}

@Composable
private fun SideBar(current: Tab, account: Account?, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.width(228.dp).fillMaxHeight().background(Palette.Panel.copy(alpha = 0.92f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(38.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Uranium", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "LAUNCHER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Tab.entries.forEach { item ->
            val selected = item == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onSelect(Tab.ACCOUNTS) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(account?.username ?: "?", online = account?.isOnline == true, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account?.username ?: "No account",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (account?.isOnline == true) "Microsoft account" else "Offline profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "v${BuildInfo.VERSION}",
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
