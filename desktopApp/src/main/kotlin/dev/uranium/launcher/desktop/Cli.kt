package dev.uranium.launcher.desktop

import dev.uranium.launcher.Launcher
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.launch.GameLogParser
import dev.uranium.launcher.modrinth.ModrinthQuery
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Headless entry point, mainly for smoke tests and CI:
 *
 *     --cli versions                     list the newest releases
 *     --cli install <version>            download a version and its Java runtime
 *     --cli launch <version> [--dry-run] install, then start (or just print the command)
 *     --cli search <query>               search Modrinth
 */
object Cli {
    fun run(args: List<String>): Nothing {
        val launcher = Launcher()
        val code = runBlocking {
            when (args.firstOrNull()) {
                "versions" -> {
                    val manifest = launcher.meta.manifest()
                    println("Latest release ${manifest.latest.release}, snapshot ${manifest.latest.snapshot}")
                    manifest.versions.filter { it.isRelease }.take(15).forEach { println("  ${it.id}") }
                    0
                }
                "install", "launch" -> {
                    val version = args.getOrNull(1) ?: return@runBlocking usage()
                    val instance = Instance(id = "cli-$version", name = "CLI $version", mcVersion = version)
                    var lastStage = ""
                    val spec = launcher.prepareLaunch(instance) { progress: Progress ->
                        // One line per stage plus a coarse percentage keeps CI logs readable.
                        val percent = progress.fraction?.let { (it * 10).toInt() * 10 }
                        val line = if (percent != null) "${progress.stage} $percent%" else progress.stage
                        if (line != lastStage) { lastStage = line; println(line) }
                    }
                    println("Java: ${spec.javaExecutable}")
                    println("Main class: ${spec.mainClass}")
                    if (args[0] == "install") return@runBlocking 0
                    if ("--dry-run" in args) {
                        println(spec.redactedCommandLine().joinToString(" "))
                        return@runBlocking 0
                    }
                    val log = GameLogParser(::println)
                    launcher.platform.createGameRunner().run(spec, log::accept).also { log.finish() }
                }
                "search" -> {
                    val result = launcher.modrinth.search(ModrinthQuery(text = args.drop(1).joinToString(" "), limit = 10))
                    println("${result.totalHits} results")
                    result.hits.forEach { println("  ${it.title} by ${it.author} - ${it.downloads} downloads (${it.pageUrl})") }
                    0
                }
                else -> usage()
            }
        }
        exitProcess(code)
    }

    private fun usage(): Int {
        System.err.println("Usage: --cli versions | install <version> | launch <version> [--dry-run] | search <query>")
        return 2
    }
}
