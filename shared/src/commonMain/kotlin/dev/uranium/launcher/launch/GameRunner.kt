package dev.uranium.launcher.launch

import okio.Path

/** A fully resolved process invocation for one game session. */
data class LaunchSpec(
    val javaExecutable: Path,
    val jvmArgs: List<String>,
    val mainClass: String,
    val gameArgs: List<String>,
    val workingDir: Path,
    val env: Map<String, String> = emptyMap(),
) {
    val commandLine: List<String> get() = listOf(javaExecutable.toString()) + jvmArgs + mainClass + gameArgs

    /** Command line that is safe to show or log: session tokens are masked. */
    fun redactedCommandLine(): List<String> {
        val out = commandLine.toMutableList()
        for (i in 0 until out.lastIndex) {
            if (out[i] == "--accessToken" || out[i] == "--session") out[i + 1] = "<redacted>"
        }
        return out
    }
}

interface GameRunner {
    /** Runs the game until it exits and returns the exit code. Cancelling the caller kills the game. */
    suspend fun run(spec: LaunchSpec, onOutput: (String) -> Unit): Int
}
