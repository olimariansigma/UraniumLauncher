package dev.uranium.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.Launcher

/** One launcher per process: it owns running games, so it must outlive any single screen. */
object LauncherHolder {
    val launcher: Launcher by lazy { Launcher() }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    INSTANCES("Instances", Icons.Default.Home),
    MODS("Mods", Icons.Default.Search),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun App() {
    val launcher = remember { LauncherHolder.launcher }
    var tab by rememberSaveable { mutableStateOf(Tab.INSTANCES) }

    UraniumTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Side rail on desktop and tablets, bottom bar on phones.
                val wide = maxWidth >= 720.dp
                val content: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier) {
                        when (tab) {
                            Tab.INSTANCES -> InstancesScreen(launcher, compact = !wide)
                            Tab.MODS -> ModsScreen(launcher, compact = !wide)
                            Tab.SETTINGS -> SettingsScreen(launcher, compact = !wide)
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            header = {
                                Text(
                                    "U",
                                    Modifier.padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                )
                            },
                        ) {
                            Tab.entries.forEach { item ->
                                NavigationRailItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                        content(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        content(Modifier.weight(1f).fillMaxWidth())
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
