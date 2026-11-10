package dev.uranium.launcher.install

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.mojang.AssetIndex
import dev.uranium.launcher.mojang.Library
import dev.uranium.launcher.mojang.MavenCoordinate
import dev.uranium.launcher.mojang.MojangMeta
import dev.uranium.launcher.mojang.RuleEnv
import dev.uranium.launcher.mojang.VersionJson
import dev.uranium.launcher.mojang.rulesAllow
import dev.uranium.launcher.net.DownloadTask
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/** An archive whose contents are unpacked into the instance's natives folder before launch. */
data class NativeArchive(val file: Path, val exclude: List<String>)

/** Everything on disk that a flattened version needs in order to start. */
data class InstalledVersion(
    val version: VersionJson,
    val clientJar: Path,
    /** Libraries in load order, without the client jar. */
    val libraries: List<Path>,
    val natives: List<NativeArchive>,
    val assetIndexId: String,
    val assetIndex: AssetIndex,
    val logConfig: Path?,
) {
    val classpath: List<Path> get() = libraries + clientJar
}

/** The files a version needs, worked out purely from its descriptor (no I/O). */
data class LibraryPlan(val downloads: List<DownloadTask>, val classpath: List<Path>, val natives: List<NativeArchive>)

class GameInstaller(
    private val http: Http,
    private val paths: LauncherPaths,
    private val resolver: VersionResolver,
    private val platform: PlatformServices,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    /**
     * Makes sure the client jar, libraries, assets and log config of [versionId] are present and
     * intact. Safe to call before every launch: files that are already there are skipped.
     */
    suspend fun install(
        versionId: String,
        parallelism: Int = 12,
        progress: ProgressListener = {},
    ): InstalledVersion = withContext(Dispatchers.IO) {
        paths.ensureCreated(fs)
        progress(Progress("Resolving $versionId"))
        val version = resolver.resolve(versionId)

        val jarId = version.jar ?: version.id
        val vanillaJar = paths.versionJar(jarId)
        val client = version.downloads["client"]
            ?: error("Version \"$versionId\" has no client download")
        http.downloadAll(
            listOf(DownloadTask(client.url, vanillaJar, client.sha1, client.size)),
            "Downloading Minecraft $jarId", parallelism, progress,
        )
        val clientJar = if (needsOwnJar(version, jarId)) {
            // Forge-style loaders identify the game jar on the classpath as "<version id>.jar", so
            // give them the copy the official launcher would have made.
            paths.versionJar(version.id).also { own ->
                if (fs.metadataOrNull(own)?.size != fs.metadataOrNull(vanillaJar)?.size) {
                    own.parent?.let(fs::createDirectories)
                    fs.copy(vanillaJar, own)
                }
            }
        } else {
            vanillaJar
        }

        val plan = planLibraries(version, RuleEnv.of(platform), paths, platform.arch.is64Bit)
        http.downloadAll(plan.downloads, "Downloading libraries", parallelism, progress)

        val indexRef = version.assetIndex ?: error("Version \"$versionId\" has no asset index")
        val indexFile = paths.assetIndex(indexRef.id)
        http.downloadAll(
            listOf(DownloadTask(indexRef.url, indexFile, indexRef.sha1, indexRef.size)),
            "Downloading asset index", parallelism, progress,
        )
        val index = LauncherJson.decodeFromString<AssetIndex>(fs.read(indexFile) { readUtf8() })
        val assetTasks = index.objects.values.distinctBy { it.hash }.map { obj ->
            DownloadTask(
                url = "${MojangMeta.RESOURCES_URL}${obj.hash.take(2)}/${obj.hash}",
                dest = paths.assetObject(obj.hash),
                sha1 = obj.hash,
                size = obj.size,
            )
        }
        http.downloadAll(assetTasks, "Downloading assets", parallelism, progress)

        val logConfig = version.logging?.client?.file?.let { file ->
            val dest = paths.assets / "log_configs" / file.id
            http.downloadAll(listOf(DownloadTask(file.url, dest, file.sha1, file.size)), "Downloading log config", 1, progress)
            dest
        }

        InstalledVersion(version, clientJar, plan.classpath, plan.natives, indexRef.id, index, logConfig)
    }

    companion object {
        fun needsOwnJar(version: VersionJson, jarId: String): Boolean =
            version.id != jarId && version.arguments?.jvm.orEmpty().any { it.toString().contains("\${version_name}") }

        fun planLibraries(version: VersionJson, env: RuleEnv, paths: LauncherPaths, is64Bit: Boolean): LibraryPlan {
            val downloads = ArrayList<DownloadTask>()
            val classpath = LinkedHashMap<String, Path>()
            val natives = ArrayList<NativeArchive>()

            for (lib in version.libraries) {
                if (!rulesAllow(lib.rules, env)) continue
                val coord = runCatching { MavenCoordinate.parse(lib.name) }.getOrNull() ?: continue
                val nativeClassifier = lib.natives?.get(env.osName)?.replace("\${arch}", if (is64Bit) "64" else "32")

                // Pre-1.19 "natives only" entries (lwjgl-platform etc.) have no main artifact at all.
                val nativesOnly = lib.natives != null && lib.downloads?.artifact == null && lib.downloads != null
                if (!nativesOnly) {
                    val artifact = lib.downloads?.artifact
                    val dest = paths.libraries / (artifact?.path ?: coord.path)
                    val url = artifact?.url ?: mavenUrl(lib, coord)
                    // A blank URL means "produced locally" (Forge installers); it is on disk or not at all.
                    if (url.isNotBlank()) downloads += DownloadTask(url, dest, artifact?.sha1 ?: lib.sha1, artifact?.size)
                    // First one wins: loader profiles list their overrides ahead of vanilla's copy.
                    classpath.getOrPut(coord.key) { dest }
                }

                if (nativeClassifier != null) {
                    val nativeCoord = coord.withClassifier(nativeClassifier)
                    val classified = lib.downloads?.classifiers?.get(nativeClassifier)
                    val url = classified?.url ?: if (lib.downloads == null) mavenUrl(lib, nativeCoord) else null
                    if (url != null) {
                        val dest = paths.libraries / (classified?.path ?: nativeCoord.path)
                        downloads += DownloadTask(url, dest, classified?.sha1, classified?.size)
                        natives += NativeArchive(dest, lib.extract?.exclude.orEmpty())
                    }
                }
            }
            return LibraryPlan(downloads, classpath.values.toList(), natives)
        }

        private fun mavenUrl(lib: Library, coord: MavenCoordinate): String {
            val base = (lib.url ?: MojangMeta.LIBRARIES_URL).let { if (it.endsWith('/')) it else "$it/" }
            return base + coord.path
        }
    }
}
