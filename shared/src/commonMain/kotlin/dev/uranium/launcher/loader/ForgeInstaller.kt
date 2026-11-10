package dev.uranium.launcher.loader

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.core.writeTextAtomic
import dev.uranium.launcher.launch.LaunchSpec
import dev.uranium.launcher.mojang.Library
import dev.uranium.launcher.mojang.MavenCoordinate
import dev.uranium.launcher.mojang.VersionJson
import dev.uranium.launcher.net.DownloadTask
import dev.uranium.launcher.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Installs Forge and NeoForge from their installer jars without running the installer's GUI.
 *
 * Modern installers (Minecraft 1.13+) carry an `install_profile.json` with "processors": small
 * Java programs that patch the vanilla client into the loader's own jars. Those are run here with
 * the same arguments the official installer would pass. Older installers just embed a version
 * descriptor and the universal jar.
 */
class ForgeInstaller(
    private val http: Http,
    private val paths: LauncherPaths,
    private val platform: PlatformServices,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    @Serializable
    private data class InstallProfile(
        val json: String = "/version.json",
        val minecraft: String? = null,
        val data: Map<String, Map<String, String>> = emptyMap(),
        val processors: List<Processor> = emptyList(),
        val libraries: List<Library> = emptyList(),
    )

    @Serializable
    private data class Processor(
        val sides: List<String>? = null,
        val jar: String,
        val classpath: List<String> = emptyList(),
        val args: List<String> = emptyList(),
        val outputs: Map<String, String> = emptyMap(),
    )

    /** Returns the installed version id. [marker] names this exact loader build on disk. */
    suspend fun install(
        installerUrl: String,
        marker: String,
        mcVersion: String,
        java: Path?,
        progress: ProgressListener,
    ): String {
        val markerFile = paths.meta / "loaders" / "$marker.id"
        fs.readTextOrNull(markerFile)?.trim()?.let { id ->
            if (id.isNotEmpty() && fs.exists(paths.versionJson(id))) return id
        }

        val installer = paths.meta / "installers" / "$marker-installer.jar"
        http.downloadAll(listOf(DownloadTask(installerUrl, installer)), "Downloading installer", 1, progress)
        val work = paths.meta / "installers" / "$marker-work"
        fs.deleteRecursively(work, mustExist = false)
        try {
            progress(Progress("Unpacking installer"))
            platform.extractZip(installer, work)
            val profileText = fs.readTextOrNull(work / "install_profile.json")
                ?: error("The installer has no install_profile.json")
            val profileJson = LauncherJson.parseToJsonElement(profileText).jsonObject

            val id = if ("versionInfo" in profileJson) {
                installLegacy(profileJson, work, progress)
            } else {
                installModern(LauncherJson.decodeFromString<InstallProfile>(profileText), work, installer, mcVersion, java, progress)
            }
            fs.writeTextAtomic(markerFile, id)
            return id
        } finally {
            fs.deleteRecursively(work, mustExist = false)
        }
    }

    private suspend fun installModern(
        profile: InstallProfile,
        work: Path,
        installer: Path,
        mcVersion: String,
        java: Path?,
        progress: ProgressListener,
    ): String {
        check(profile.minecraft == null || profile.minecraft == mcVersion) {
            "This installer is for Minecraft ${profile.minecraft}, not $mcVersion"
        }
        val versionText = fs.readTextOrNull(work / profile.json.trimStart('/'))
            ?: error("The installer has no ${profile.json}")
        val version = LauncherJson.decodeFromString<VersionJson>(versionText)

        // Libraries the processors and the game need. Entries without a URL ship inside the installer.
        val downloads = ArrayList<DownloadTask>()
        for (lib in profile.libraries + version.libraries) {
            val artifact = lib.downloads?.artifact ?: continue
            val relative = artifact.path ?: MavenCoordinate.parse(lib.name).path
            val dest = paths.libraries / relative
            val bundled = work / "maven" / relative
            when {
                fs.exists(bundled) && !fs.exists(dest) -> {
                    dest.parent?.let(fs::createDirectories)
                    fs.copy(bundled, dest)
                }
                artifact.url.isNotBlank() -> downloads += DownloadTask(artifact.url, dest, artifact.sha1, artifact.size)
            }
        }
        http.downloadAll(downloads, "Downloading loader libraries", 8, progress)

        val clientProcessors = profile.processors.filter { it.sides == null || "client" in it.sides }
        if (clientProcessors.isNotEmpty()) {
            val javaPath = java ?: error("Installing this loader needs Java, which is not available on this device yet.")
            val values = HashMap<String, String>()
            for ((key, sides) in profile.data) {
                values[key] = resolveData(sides["client"] ?: continue, work)
            }
            values["SIDE"] = "client"
            values["MINECRAFT_JAR"] = paths.versionJar(mcVersion).toString()
            values["MINECRAFT_VERSION"] = mcVersion
            values["ROOT"] = paths.root.toString()
            values["INSTALLER"] = installer.toString()
            values["LIBRARY_DIR"] = paths.libraries.toString()

            clientProcessors.forEachIndexed { index, processor ->
                progress(Progress("Patching the game", index.toLong(), clientProcessors.size.toLong()))
                runProcessor(processor, values, javaPath, work)
            }
        }

        fs.writeTextAtomic(paths.versionJson(version.id), versionText)
        return version.id
    }

    private suspend fun runProcessor(processor: Processor, values: Map<String, String>, java: Path, work: Path) {
        // Outputs are declared with their expected hashes; when all are already right, skip the work.
        val outputs = processor.outputs.map { (file, hash) -> substitute(file, values) to substitute(hash, values).trim('\'') }
        if (outputs.isNotEmpty() && outputs.all { (file, hash) -> sha1OrNull(file) == hash }) return

        val jar = libraryPath(processor.jar)
        val mainClass = mainClassOf(jar) ?: error("Processor ${processor.jar} has no Main-Class")
        val separator = if (platform.os == OsType.WINDOWS) ";" else ":"
        val classpath = (listOf(jar) + processor.classpath.map(::libraryPath)).joinToString(separator)
        val args = processor.args.map { substitute(it, values) }

        val output = ArrayDeque<String>()
        val exit = platform.createGameRunner().run(
            LaunchSpec(java, listOf("-cp", classpath), mainClass, args, work),
        ) { line ->
            output.addLast(line)
            if (output.size > 40) output.removeFirst()
        }
        check(exit == 0) { "Loader install step ${processor.jar} failed (exit $exit):\n${output.joinToString("\n")}" }
        for ((file, hash) in outputs) {
            check(sha1OrNull(file) == hash) { "Loader install step ${processor.jar} produced an unexpected ${file.substringAfterLast('/')}" }
        }
    }

    /** `[maven:coord]` -> library path, `'text'` -> text, `/path` -> a file inside the installer. */
    private fun resolveData(value: String, work: Path): String = when {
        value.startsWith("[") && value.endsWith("]") -> libraryPath(value.substring(1, value.length - 1)).toString()
        value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1)
        else -> (work / value.trimStart('/')).toString()
    }

    private fun substitute(arg: String, values: Map<String, String>): String {
        if (arg.startsWith("[") && arg.endsWith("]")) return libraryPath(arg.substring(1, arg.length - 1)).toString()
        return TOKEN.replace(arg) { match -> values[match.groupValues[1]] ?: match.value }
    }

    private fun libraryPath(coordinate: String): Path = paths.libraries / MavenCoordinate.parse(coordinate).path

    private fun sha1OrNull(file: String): String? {
        val path = file.toPath()
        return if (fs.exists(path)) fs.read(path) { readByteString() }.sha1().hex() else null
    }

    private fun mainClassOf(jar: Path): String? {
        val manifest = platform.readZipEntry(jar, "META-INF/MANIFEST.MF")?.decodeToString() ?: return null
        // Manifest values wrap at 72 bytes; continuation lines start with a single space.
        val unfolded = manifest.replace("\r\n", "\n").replace("\n ", "")
        return unfolded.lineSequence().firstOrNull { it.startsWith("Main-Class:") }?.substringAfter(':')?.trim()
    }

    /**
     * Pre-1.13 installers: the descriptor is embedded as "versionInfo" and the loader itself is a
     * single universal jar. The descriptor still points at Forge's retired HTTP file host.
     */
    private fun installLegacy(profile: JsonObject, work: Path, progress: ProgressListener): String {
        progress(Progress("Installing loader"))
        val install = profile["install"]?.jsonObject ?: error("Unsupported installer format")
        val versionInfo = profile["versionInfo"]!!.jsonObject
        val id = versionInfo["id"]?.jsonPrimitive?.content ?: error("The installer's version has no id")

        val forgeCoord = MavenCoordinate.parse(install["path"]!!.jsonPrimitive.content)
        val universal = work / install["filePath"]!!.jsonPrimitive.content
        val dest = paths.libraries / forgeCoord.path
        dest.parent?.let(fs::createDirectories)
        fs.copy(universal, dest)

        val libraries = (versionInfo["libraries"] as? JsonArray).orEmpty().map { element ->
            val lib = element.jsonObject
            val url = lib["url"]?.jsonPrimitive?.content
            if (url != null && url.contains("files.minecraftforge.net")) {
                JsonObject(lib + ("url" to JsonPrimitive("${LoaderService.FORGE_MAVEN}/")))
            } else {
                lib
            }
        }
        val descriptor = buildJsonObject {
            versionInfo.forEach { (key, value) -> if (key != "libraries") put(key, value) }
            put("libraries", JsonArray(libraries))
        }
        fs.writeTextAtomic(paths.versionJson(id), descriptor.toString())
        return id
    }

    private companion object {
        val TOKEN = Regex("""\{([A-Z_0-9]+)}""")
    }
}
