package dev.uranium.launcher.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Runs the game as a child process and streams its merged stdout/stderr line by line. */
class ProcessGameRunner : GameRunner {

    override suspend fun run(spec: LaunchSpec, onOutput: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(spec.commandLine)
            .directory(spec.workingDir.toFile())
            .redirectErrorStream(true)
            .apply { environment().putAll(spec.env) }
            .start()
        try {
            // Reading blocks until the stream closes, which happens when the process dies.
            val pump = launch {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onOutput) }
            }
            val exitCode = runInterruptible { process.waitFor() }
            pump.join()
            exitCode
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
