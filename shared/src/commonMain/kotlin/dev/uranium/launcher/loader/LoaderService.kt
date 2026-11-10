package dev.uranium.launcher.loader

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.core.writeTextAtomic
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.mojang.VersionJson
import dev.uranium.launcher.net.Http
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path

data class LoaderVersion(val version: String, val stable: Boolean = true, val recommended: Boolean = false)

/**
 * Lists and installs mod loader versions. Installing produces a version descriptor under
 * `versions/<id>/` that inherits from the vanilla version; the normal installer then downloads the
 * loader's libraries like any other.
 */
class LoaderService(
    private val http: Http,
    private val paths: LauncherPaths,
    private val platform: PlatformServices,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    private val forge = ForgeInstaller(http, paths, platform, fs)
    private val cache = HashMap<String, List<LoaderVersion>>()

    /** Loader versions available for [mcVersion], newest first. Empty when the loader has none. */
    suspend fun versions(loader: LoaderType, mcVersion: String): List<LoaderVersion> {
        val key = "${loader.name}:$mcVersion"
        cache[key]?.let { return it }
        val versions = when (loader) {
            LoaderType.VANILLA -> emptyList()
            LoaderType.FABRIC -> fabricLike(FABRIC_META, "v2", mcVersion)
            LoaderType.QUILT -> fabricLike(QUILT_META, "v3", mcVersion)
            LoaderType.FORGE -> forgeVersions(mcVersion)
            LoaderType.NEOFORGE -> neoForgeVersions(mcVersion)
        }
        cache[key] = versions
        return versions
    }

    /**
     * Makes sure the loader is installed and returns the id of its version descriptor.
     * [java] runs Forge/NeoForge's install processors; the vanilla version must already be installed.
     */
    suspend fun ensureInstalled(
        loader: LoaderType,
        mcVersion: String,
        loaderVersion: String,
        java: Path?,
        progress: ProgressListener = {},
    ): String = withContext(Dispatchers.IO) {
        when (loader) {
            LoaderType.VANILLA -> mcVersion
            LoaderType.FABRIC -> installProfile(
                "$FABRIC_META/v2/versions/loader/${mcVersion.seg()}/${loaderVersion.seg()}/profile/json", progress,
            )
            LoaderType.QUILT -> installProfile(
                "$QUILT_META/v3/versions/loader/${mcVersion.seg()}/${loaderVersion.seg()}/profile/json", progress,
            )
            LoaderType.FORGE -> {
                val full = if (loaderVersion.startsWith("$mcVersion-")) loaderVersion else "$mcVersion-$loaderVersion"
                forge.install(
                    installerUrl = "$FORGE_MAVEN/net/minecraftforge/forge/$full/forge-$full-installer.jar",
                    marker = "forge-$full", mcVersion = mcVersion, java = java, progress = progress,
                )
            }
            LoaderType.NEOFORGE -> forge.install(
                installerUrl = "$NEOFORGE_MAVEN/releases/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar",
                marker = "neoforge-$loaderVersion", mcVersion = mcVersion, java = java, progress = progress,
            )
        }
    }

    /** Fabric and Quilt publish ready-made launcher profiles; saving one is the whole install. */
    private suspend fun installProfile(url: String, progress: ProgressListener): String {
        progress(Progress("Fetching loader profile"))
        val text = http.getText(url)
        val id = LauncherJson.decodeFromString<VersionJson>(text).id
        fs.writeTextAtomic(paths.versionJson(id), text)
        return id
    }

    /**
     * Both metas return the same loader list for any game version string, so support has to be
     * checked against their list of game versions first.
     */
    private suspend fun fabricLike(base: String, api: String, mcVersion: String): List<LoaderVersion> {
        val games = http.getJson<List<GameVersion>>("$base/$api/versions/game")
        if (games.none { it.version == mcVersion }) return emptyList()
        return http.getJson<List<LoaderBuild>>("$base/$api/versions/loader").map { build ->
            // Fabric's "stable" flag only marks its single newest release and Quilt has no flag at
            // all, so judge by the version string: pre-releases carry a "-beta"/"-pre" suffix.
            LoaderVersion(build.version, stable = !build.version.contains('-'))
        }
    }

    private suspend fun forgeVersions(mcVersion: String): List<LoaderVersion> {
        val all = http.getJson<Map<String, List<String>>>("$FORGE_FILES/net/minecraftforge/forge/maven-metadata.json")
        val promos = runCatching {
            http.getJson<JsonObject>("$FORGE_FILES/net/minecraftforge/forge/promotions_slim.json")["promos"]?.jsonObject
        }.getOrNull()
        val recommended = promos?.get("$mcVersion-recommended")?.jsonPrimitive?.content
        return all[mcVersion].orEmpty().reversed().map { full ->
            val short = full.removePrefix("$mcVersion-")
            LoaderVersion(short, stable = true, recommended = short == recommended)
        }
    }

    private suspend fun neoForgeVersions(mcVersion: String): List<LoaderVersion> {
        val prefix = neoForgePrefix(mcVersion) ?: return emptyList()
        val listing = http.getJson<JsonObject>("$NEOFORGE_MAVEN/api/maven/versions/releases/net/neoforged/neoforge")
        return listing["versions"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            .filter { it.startsWith(prefix) }
            .reversed()
            .map { LoaderVersion(it, stable = !it.contains("beta") && !it.contains("alpha")) }
    }

    private fun String.seg(): String = encodeURLPathPart()

    @Serializable
    private data class GameVersion(val version: String)

    @Serializable
    private data class LoaderBuild(val version: String)

    companion object {
        const val FABRIC_META = "https://meta.fabricmc.net"
        const val QUILT_META = "https://meta.quiltmc.org"
        const val FORGE_FILES = "https://files.minecraftforge.net"
        const val FORGE_MAVEN = "https://maven.minecraftforge.net"
        const val NEOFORGE_MAVEN = "https://maven.neoforged.net"

        /**
         * NeoForge drops Minecraft's leading "1.": 1.21.1 -> "21.1.", 1.21 -> "21.0.". The
         * year-based versions keep all their parts: 26.3 -> "26.3.0.", 26.1.2 -> "26.1.2.".
         * 1.20.1 predates this scheme (it shipped as a Forge fork) and is not offered.
         */
        fun neoForgePrefix(mcVersion: String): String? {
            val parts = mcVersion.split('.')
            if (parts.any { it.toIntOrNull() == null }) return null
            return if (parts[0] == "1") {
                val minor = parts.getOrNull(1)?.toInt() ?: return null
                if (minor < 20 || (minor == 20 && (parts.getOrNull(2)?.toInt() ?: 0) < 2)) return null
                "$minor.${parts.getOrNull(2) ?: "0"}."
            } else {
                (parts + List((3 - parts.size).coerceAtLeast(0)) { "0" }).joinToString(".") + "."
            }
        }

        /** The loader version to preselect: recommended, else newest stable, else newest. */
        fun pickDefault(versions: List<LoaderVersion>): LoaderVersion? =
            versions.firstOrNull { it.recommended } ?: versions.firstOrNull { it.stable } ?: versions.firstOrNull()
    }
}
