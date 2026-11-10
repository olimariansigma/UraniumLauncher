package dev.uranium.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uranium.launcher.Launcher

@Composable
fun ConsoleScreen(launcher: Launcher, compact: Boolean) {
    val lines by launcher.console.lines.collectAsState()
    val listState = rememberLazyListState()
    // Follow the tail unless the player has scrolled up to read something.
    LaunchedEffect(lines.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lines.isNotEmpty() && lastVisible >= listState.layoutInfo.totalItemsCount - 3) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    val pad = if (compact) 16.dp else 28.dp
    Column(Modifier.fillMaxSize().padding(pad)) {
        ScreenHeader("Console", if (compact) null else "Output from the launcher and from running games.") {
            OutlinedButton(onClick = { launcher.platform.copyToClipboard(lines.joinToString("\n")) }, enabled = lines.isNotEmpty()) {
                Text("Copy all")
            }
            OutlinedButton(onClick = { launcher.console.clear() }, enabled = lines.isNotEmpty()) { Text("Clear") }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (lines.isEmpty()) {
                CenteredMessage("Nothing here yet", "Game output appears once an instance is started.")
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        items(lines.size) { index ->
                            val line = lines[index]
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = lineColor(line))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: String): Color = when {
    line.startsWith("[Uranium]") -> MaterialTheme.colorScheme.primary
    "/ERROR]" in line || "/FATAL]" in line || line.startsWith("Exception") || line.startsWith("Caused by") -> MaterialTheme.colorScheme.error
    "/WARN]" in line -> Palette.Warning
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
}
