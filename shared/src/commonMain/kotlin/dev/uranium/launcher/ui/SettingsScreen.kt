package dev.uranium.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.uranium.launcher.BuildInfo
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.instance.autoMemoryMb
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(launcher: Launcher, compact: Boolean) {
    val settings by launcher.settings.collectAsState()
    val platform = launcher.platform
    val pad = if (compact) 16.dp else 28.dp
    val column = Modifier.widthIn(max = 760.dp).fillMaxWidth()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Settings")

        Panel(column) {
            SectionTitle("Memory")
            val auto = settings.memoryMb <= 0
            val autoMb = autoMemoryMb(platform.totalMemoryMb)
            // Leave headroom for the OS; fall back to a sane ceiling when RAM is unknown.
            val maxMb = (if (platform.totalMemoryMb > 0) platform.totalMemoryMb else 8192L).coerceIn(2048, 32768).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allocate automatically")
                    Hint("Currently $autoMb MB of ${platform.totalMemoryMb} MB installed.")
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
            Panel(column) {
                SectionTitle("Java")
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

        Panel(column) {
            SectionTitle("Downloads")
            Text("Parallel downloads: ${settings.parallelDownloads}")
            Slider(
                value = settings.parallelDownloads.toFloat(),
                onValueChange = { value -> launcher.updateSettings { it.copy(parallelDownloads = value.roundToInt().coerceIn(1, 32)) } },
                valueRange = 1f..32f,
            )
        }

        Panel(column) {
            SectionTitle("Services")
            Hint("These override the credentials built into this copy of the launcher. Leave them empty to use the built-in ones.")
            OutlinedTextField(
                value = settings.curseForgeApiKey,
                onValueChange = { value -> launcher.updateSettings { it.copy(curseForgeApiKey = value.trim()) } },
                label = { Text("CurseForge API key") },
                placeholder = { Text(if (BuildInfo.CURSEFORGE_API_KEY.isNotEmpty()) "Using the built-in key" else "Not set") },
                supportingText = { Text("Needed to browse CurseForge. Request one at console.curseforge.com.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.msaClientId,
                onValueChange = { value -> launcher.updateSettings { it.copy(msaClientId = value.trim()) } },
                label = { Text("Microsoft application (client) id") },
                placeholder = { Text(if (BuildInfo.MSA_CLIENT_ID.isNotEmpty()) "Using the built-in id" else "Not set") },
                supportingText = { Text("Needed for Microsoft sign-in. See the README for how to register one.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Panel(column) {
            SectionTitle("Storage")
            Text("Game files and instances are stored in:")
            SelectionContainer { Hint(launcher.paths.root.toString()) }
            if (platform.os != OsType.ANDROID) {
                OutlinedButton(onClick = { platform.open(launcher.paths.root.toString()) }) { Text("Open folder") }
            }
        }

        Panel(column) {
            SectionTitle("About")
            Text("Uranium Launcher ${BuildInfo.VERSION}")
            Hint("Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
