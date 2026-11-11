package dev.uranium.launcher.desktop

import dev.uranium.launcher.Launcher
import dev.uranium.launcher.content.ContentProvider
import dev.uranium.launcher.content.ContentQuery
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.launch.GameLogParser
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Headless entry point, mainly for smoke tests and CI:
 *
 *     --cli versions                                  list the newest releases
 *     --cli loaders <loader> <version>                list loader builds for a Minecraft version
 *     --cli install <version> [--loader <loader>]     download a version (and loader) and its Java
 *     --cli launch <version> [--loader <loader>] [--dry-run]
 *     --cli search <query> [--curseforge]             search Modrinth or CurseForge
 *     --cli scan                                      look for Prism Launcher instances
 *     --cli share <instance id> [--no-configs]        upload an instance and print its share code
 *     --cli redeem <code>                             build an instance from a share code
 *     --cli login                                     test Microsoft sign-in (prints a device code)
 */
object Cli {
    fun run(args: List<String>): Nothing {
        val launcher = Launcher()
        fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        fun loader(name: String?): LoaderType =
            LoaderType.entries.firstOrNull { it.name.equals(name ?: "vanilla", ignoreCase = true) }
                ?: error("Unknown loader \"$name\"")

        val code = runBlocking {
            when (args.firstOrNull()) {
                "versions" -> {
                    val manifest = launcher.meta.manifest()
                    println("Latest release ${manifest.latest.release}, snapshot ${manifest.latest.snapshot}")
                    manifest.versions.filter { it.isRelease }.take(15).forEach { println("  ${it.id}") }
                    0
                }
                "loaders" -> {
                    val versions = launcher.loaderVersions(loader(args.getOrNull(1)), args.getOrNull(2) ?: return@runBlocking usage())
                    println("${versions.size} builds")
                    versions.take(8).forEach { println("  ${it.version}${if (it.recommended) " (recommended)" else if (!it.stable) " (unstable)" else ""}") }
                    0
                }
                "install", "launch" -> {
                    val version = args.getOrNull(1) ?: return@runBlocking usage()
                    val loaderType = loader(option("--loader"))
                    val instance = Instance(
                        id = "cli-$version-${loaderType.name.lowercase()}", name = "CLI $version",
                        mcVersion = version, loader = loaderType, loaderVersion = option("--loader-version"),
                    )
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
                    val provider = if ("--curseforge" in args) ContentProvider.CURSEFORGE else ContentProvider.MODRINTH
                    val text = args.drop(1).filterNot { it.startsWith("--") }.joinToString(" ")
                    val result = launcher.content.source(provider).search(ContentQuery(text = text, limit = 10))
                    println("${result.total} results on ${provider.label}")
                    result.hits.forEach { println("  ${it.title} by ${it.author} - ${it.downloads} downloads (${it.pageUrl})") }
                    0
                }
                "share" -> {
                    val id = args.getOrNull(1) ?: return@runBlocking usage()
                    val instance = launcher.instances.value.firstOrNull { it.id == id } ?: error("No instance with id \"$id\"")
                    val preview = launcher.share.preview(instance, includeConfigs = "--no-configs" !in args)
                    println("${preview.pack.content.size} items, ${preview.configFiles} config files, ${preview.sizeBytes} bytes")
                    if (preview.unshareable.isNotEmpty()) println("Not shareable: ${preview.unshareable.joinToString()}")
                    val ticket = launcher.share.upload(preview.pack)
                    println("Share code: ${ticket.code} (expires ${ticket.expiresAt})")
                    0
                }
                "redeem" -> {
                    val pack = launcher.share.fetch(args.getOrNull(1) ?: return@runBlocking usage())
                    println("${pack.name}: Minecraft ${pack.mcVersion} ${pack.loader.displayName}, ${pack.content.size} items, ${pack.configs.size} config files")
                    val outcome = launcher.redeemPack(pack)
                    println("Created instance ${outcome.instanceId}: ${outcome.installed} installed, ${outcome.blocked} need a manual download")
                    outcome.skipped.forEach { println("  skipped $it") }
                    0
                }
                "login" -> {
                    val code = launcher.microsoft.requestDeviceCode()
                    println("Open ${code.verificationUri} and enter the code ${code.userCode}")
                    val account = launcher.microsoft.minecraftAccount(launcher.microsoft.awaitDeviceCode(code))
                    println("Signed in as ${account.username} (${account.uuid})")
                    0
                }
                "scan" -> {
                    val found = launcher.scanForImports { }
                    println("${found.size} instances found")
                    found.forEach { println("  ${it.name}: ${it.mcVersion} ${it.loader.displayName} ${it.loaderVersion.orEmpty()} (${it.path})") }
                    0
                }
                else -> usage()
            }
        }
        exitProcess(code)
    }

    private fun usage(): Int {
        System.err.println(
            "Usage: --cli versions | loaders <loader> <version> | install <version> [--loader <loader>] | " +
                "launch <version> [--loader <loader>] [--dry-run] | search <query> [--curseforge] | scan | share <instance id> | redeem <code> | login"
        )
        return 2
    }
}
