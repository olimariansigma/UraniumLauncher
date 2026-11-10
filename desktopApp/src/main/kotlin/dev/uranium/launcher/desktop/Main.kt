package dev.uranium.launcher.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.uranium.launcher.ui.App

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--cli") Cli.run(args.drop(1))

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Uranium Launcher",
            state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
        ) {
            App()
        }
    }
}
