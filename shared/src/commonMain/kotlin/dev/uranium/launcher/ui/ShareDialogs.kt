package dev.uranium.launcher.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.share.RedeemOutcome
import dev.uranium.launcher.share.SharePreview
import dev.uranium.launcher.share.ShareService
import dev.uranium.launcher.share.ShareTicket
import dev.uranium.launcher.share.SharedPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NOT_SET_UP =
    "Share codes need a share server. Deploy the small Cloudflare Worker from the worker folder (the README " +
        "walks through it) and paste its address under Settings > Services."

@Composable
private fun DialogFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 520.dp).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }
}

@Composable
private fun CodeDisplay(code: String) {
    Text(
        code,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(14.dp))
            .padding(vertical = 18.dp),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = 6.sp,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Uploads an instance's manifest and shows the resulting code. */
@Composable
fun ShareDialog(launcher: Launcher, instance: Instance, onDismiss: () -> Unit) {
    var includeConfigs by remember { mutableStateOf(true) }
    var uploading by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<ShareTicket?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val preview by produceState<SharePreview?>(null, instance.id, includeConfigs) {
        value = withContext(Dispatchers.IO) { runCatching { launcher.share.preview(instance, includeConfigs) }.getOrNull() }
    }

    DialogFrame("Share \"${instance.name}\"", onDismiss) {
        val done = ticket
        val current = preview
        when {
            !launcher.share.isConfigured -> {
                Muted(NOT_SET_UP)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Close") } }
            }
            done != null -> {
                Muted("Give this code to a friend. They choose \"Use a share code\" on their Home screen and the launcher rebuilds the instance for them.")
                CodeDisplay(done.code)
                if (done.expiresAt.isNotBlank()) Muted("Works until ${done.expiresAt.take(10)}.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { launcher.platform.copyToClipboard(done.code) }) { Text("Copy code") }
                }
            }
            current == null -> CircularProgressIndicator(Modifier.size(28.dp))
            else -> {
                Muted(
                    "The code stands for a small list: Minecraft ${current.pack.mcVersion}, ${current.pack.loader.displayName}, " +
                        "${current.pack.content.size} mods and packs from Modrinth or CurseForge" +
                        (if (current.configFiles > 0) ", and ${current.configFiles} config files" else "") +
                        ". No game files, worlds or account details are uploaded."
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include settings and mod configs")
                        Text(
                            "options.txt and text files in the config folder. Files that look like they hold logins or tokens are left out, but anyone with the code can read what is included.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = includeConfigs, onCheckedChange = { includeConfigs = it }, enabled = !uploading)
                }
                if (current.unshareable.isNotEmpty()) {
                    Text(
                        "${current.unshareable.size} ${if (current.unshareable.size == 1) "file was" else "files were"} added by hand and cannot be shared: " +
                            current.unshareable.take(6).joinToString() + if (current.unshareable.size > 6) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Warning,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !uploading,
                        onClick = {
                            uploading = true
                            error = null
                            scope.launch {
                                try {
                                    ticket = launcher.share.upload(current.pack)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not create a share code."
                                } finally {
                                    uploading = false
                                }
                            }
                        },
                    ) { Text("Create share code") }
                }
            }
        }
    }
}

private sealed interface RedeemPhase {
    data object Entering : RedeemPhase
    data object Fetching : RedeemPhase
    data class Found(val pack: SharedPack) : RedeemPhase
    data class Installing(val pack: SharedPack, val progress: Progress) : RedeemPhase
    data class Finished(val pack: SharedPack, val outcome: RedeemOutcome) : RedeemPhase
}

/** Looks a code up, shows what it contains, then builds the instance. */
@Composable
fun RedeemDialog(launcher: Launcher, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<RedeemPhase>(RedeemPhase.Entering) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DialogFrame("Use a share code", onDismiss) {
        if (!launcher.share.isConfigured) {
            Muted(NOT_SET_UP)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Close") } }
            return@DialogFrame
        }
        when (val current = phase) {
            RedeemPhase.Entering, RedeemPhase.Fetching -> {
                Muted("Enter the code a friend gave you. The launcher downloads the same Minecraft version, loader and mods.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(12).uppercase(); error = null },
                    label = { Text("Share code") },
                    placeholder = { Text("K7X-9QP") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (current == RedeemPhase.Fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = current == RedeemPhase.Entering && ShareService.normalizeCode(code) != null,
                        onClick = {
                            phase = RedeemPhase.Fetching
                            scope.launch {
                                phase = try {
                                    RedeemPhase.Found(launcher.share.fetch(code))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not look that code up."
                                    RedeemPhase.Entering
                                }
                            }
                        },
                    ) { Text("Find pack") }
                }
            }
            is RedeemPhase.Found -> {
                PackSummary(current.pack)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val pack = current.pack
                        phase = RedeemPhase.Installing(pack, Progress("Starting"))
                        scope.launch {
                            phase = try {
                                RedeemPhase.Finished(pack, launcher.redeemPack(pack) { phase = RedeemPhase.Installing(pack, it) })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not build the instance."
                                RedeemPhase.Found(pack)
                            }
                        }
                    }) { Text("Create instance") }
                }
            }
            is RedeemPhase.Installing -> {
                PackSummary(current.pack)
                val fraction = current.progress.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Muted(current.progress.stage)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Cancel") } }
            }
            is RedeemPhase.Finished -> {
                val outcome = current.outcome
                Text("\"${current.pack.name}\" is ready", style = MaterialTheme.typography.titleMedium)
                Muted(
                    "${outcome.installed} of ${current.pack.content.size} items were downloaded. " +
                        "Minecraft and the loader are downloaded the first time you press Play."
                )
                if (outcome.blocked > 0) {
                    Text(
                        "${outcome.blocked} need a manual download from CurseForge; the launcher will guide you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Warning,
                    )
                }
                outcome.skipped.take(8).forEach { skipped ->
                    Text("Skipped $skipped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (outcome.skipped.size > 8) Muted("...and ${outcome.skipped.size - 8} more.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onDismiss(); onOpen(outcome.instanceId) }) { Text("Open instance") }
                }
            }
        }
    }
}

@Composable
private fun PackSummary(pack: SharedPack) {
    Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(pack.name, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag("Minecraft ${pack.mcVersion}", MaterialTheme.colorScheme.onSurfaceVariant)
                    LoaderTag(pack.loader)
                }
            }
        }
        Muted(
            "${pack.content.size} mods and packs" +
                if (pack.configs.isNotEmpty()) ", ${pack.configs.size} config files" else ""
        )
    }
}
