package dev.uranium.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.uranium.launcher.Launcher
import dev.uranium.launcher.LoginState
import dev.uranium.launcher.account.Account

@Composable
fun AccountsScreen(launcher: Launcher, compact: Boolean) {
    val accounts by launcher.accounts.collectAsState()
    val login by launcher.login.collectAsState()
    val settings by launcher.settings.collectAsState()
    var showOffline by remember { mutableStateOf(false) }
    val pad = if (compact) 16.dp else 28.dp
    // Re-read when settings change so a client id added in Settings takes effect at once.
    val microsoftReady = remember(settings) { launcher.microsoft.isConfigured }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Accounts", if (compact) null else "The selected account is used whenever you press Play.")

        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            accounts.accounts.forEach { account ->
                AccountRow(
                    account = account,
                    selected = account.id == accounts.selected?.id,
                    canRemove = accounts.accounts.size > 1,
                    onSelect = { launcher.selectAccount(account.id) },
                    onRemove = { launcher.removeAccount(account.id) },
                )
            }
        }

        Panel(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Text("Add an account", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sign in with Microsoft to play online and on servers. Offline profiles work for single player and LAN.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { launcher.startMicrosoftLogin() }, enabled = microsoftReady) { Text("Sign in with Microsoft") }
                OutlinedButton(onClick = { showOffline = true }) { Text("Add offline profile") }
            }
            if (!microsoftReady) {
                Text(
                    "Microsoft sign-in needs an application (client) id. Add one under Settings > Services; the README explains how to register it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Warning,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (login != LoginState.Idle) MicrosoftLoginDialog(launcher, login)
    if (showOffline) OfflineAccountDialog(launcher, onDismiss = { showOffline = false })
}

@Composable
private fun AccountRow(account: Account, selected: Boolean, canRemove: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onSelect),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(account.username, online = account.isOnline, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (account.isOnline) Tag("Microsoft", Palette.Online) else Tag("Offline", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${account.username}")
            }
        }
    }
}

@Composable
private fun MicrosoftLoginDialog(launcher: Launcher, state: LoginState) {
    Dialog(onDismissRequest = { launcher.cancelMicrosoftLogin() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(
                Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sign in with Microsoft", style = MaterialTheme.typography.headlineSmall)
                when (state) {
                    is LoginState.AwaitingUser -> {
                        Text(
                            "Open the Microsoft sign-in page and enter this code:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            state.code.userCode,
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(14.dp))
                                .padding(vertical = 18.dp),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            letterSpacing = 6.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                launcher.platform.copyToClipboard(state.code.userCode)
                                launcher.platform.open(state.code.verificationUri)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Copy code and open sign-in page") }
                        Text(
                            state.code.verificationUri,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Waiting for you to finish in the browser...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is LoginState.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    else -> {
                        CircularProgressIndicator(Modifier.size(36.dp))
                        Text(
                            if (state == LoginState.Finishing) "Signing in to Xbox Live and Minecraft..." else "Contacting Microsoft...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state is LoginState.Failed) {
                        TextButton(onClick = { launcher.cancelMicrosoftLogin(); launcher.startMicrosoftLogin() }) { Text("Try again") }
                    }
                    TextButton(onClick = { launcher.cancelMicrosoftLogin() }) { Text(if (state is LoginState.Failed) "Close" else "Cancel") }
                }
            }
        }
    }
}

@Composable
private fun OfflineAccountDialog(launcher: Launcher, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Offline profile", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16); error = null },
                    label = { Text("Username") },
                    isError = error != null,
                    supportingText = { Text(error ?: "3-16 characters: letters, digits and underscores.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        error = launcher.addOfflineAccount(name)
                        if (error == null) onDismiss()
                    }) { Text("Add") }
                }
            }
        }
    }
}
