package dev.uranium.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.BuildInfo
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.instance.autoMemoryMb
import dev.uranium.launcher.instance.isValidUsername
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(launcher: Launcher, compact: Boolean) {
    val settings by launcher.settings.collectAsState()
    val platform = launcher.platform
    val pad = if (compact) 16.dp else 24.dp

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Settings")

        Section("Account") {
            // Edit a local copy so an invalid name is never saved or launched with.
            var name by remember { mutableStateOf(settings.offlineUsername) }
            val valid = isValidUsername(name)
            OutlinedTextField(
                value = name,
                onValueChange = { input ->
                    name = input.take(16)
                    if (isValidUsername(name)) launcher.updateSettings { it.copy(offlineUsername = name) }
                },
                label = { Text("Offline username") },
                isError = !valid,
                supportingText = {
                    Text(
                        if (valid) "Used for offline play. Microsoft sign-in arrives in the next phase."
                        else "3-16 characters: letters, digits and underscores."
                    )
                },
                singleLine = true,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        }

        Section("Memory") {
            val auto = settings.memoryMb <= 0
            val autoMb = autoMemoryMb(platform.totalMemoryMb)
            // Leave headroom for the OS; fall back to a sane ceiling when RAM is unknown.
            val maxMb = (if (platform.totalMemoryMb > 0) platform.totalMemoryMb else 8192L).coerceIn(2048, 32768).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allocate automatically")
                    Text(
                        "Currently $autoMb MB of ${platform.totalMemoryMb} MB installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = auto, onCheckedChange = { on ->
                    launcher.updateSettings { it.copy(memoryMb = if (on) 0 else autoMb) }
                })
            }
            if (!auto) {
                Text("Maximum heap: ${settings.memoryMb} MB")
                Slider(
                    value = settings.memoryMb.toFloat(),
                    onValueChange = { value ->
                        val snapped = (value / 256f).roundToInt() * 256
                        launcher.updateSettings { it.copy(memoryMb = snapped.coerceIn(1024, maxMb)) }
                    },
                    valueRange = 1024f..maxMb.toFloat(),
                )
            }
        }

        if (platform.os != OsType.ANDROID) {
            Section("Java") {
                OutlinedTextField(
                    value = settings.javaPath,
                    onValueChange = { value -> launcher.updateSettings { it.copy(javaPath = value) } },
                    label = { Text("Java executable") },
                    placeholder = { Text("Automatic") },
                    supportingText = { Text("Leave empty to use the Java runtime Mojang ships for each Minecraft version.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.extraJvmArgs,
                    onValueChange = { value -> launcher.updateSettings { it.copy(extraJvmArgs = value) } },
                    label = { Text("Extra JVM arguments") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Section("Downloads") {
            Text("Parallel downloads: ${settings.parallelDownloads}")
            Slider(
                value = settings.parallelDownloads.toFloat(),
                onValueChange = { value -> launcher.updateSettings { it.copy(parallelDownloads = value.roundToInt().coerceIn(1, 32)) } },
                valueRange = 1f..32f,
            )
        }

        Section("Storage") {
            Text("Game files and instances are stored in:")
            SelectionContainer {
                Text(
                    launcher.paths.root.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (platform.os != OsType.ANDROID) {
                OutlinedButton(onClick = { platform.open(launcher.paths.root.toString()) }) { Text("Open folder") }
            }
        }

        Section("About") {
            Text("Uranium Launcher ${BuildInfo.VERSION}")
            Text(
                "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
