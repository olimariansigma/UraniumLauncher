package dev.uranium.launcher.mojang

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.core.writeTextAtomic
import dev.uranium.launcher.net.DownloadTask
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

class MojangMeta(
    private val http: Http,
    private val paths: LauncherPaths,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    private var cached: VersionManifest? = null
    private val manifestCache = paths.meta / "version_manifest_v2.json"

    /** The version list. Falls back to the last copy on disk so the launcher still opens offline. */
    suspend fun manifest(forceRefresh: Boolean = false): VersionManifest = withContext(Dispatchers.IO) {
        if (!forceRefresh) cached?.let { return@withContext it }
        val fresh = try {
            http.getText(MANIFEST_URL).also { fs.writeTextAtomic(manifestCache, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fs.readTextOrNull(manifestCache) ?: throw e
        }
        LauncherJson.decodeFromString<VersionManifest>(fresh).also { cached = it }
    }

    /**
     * Loads `versions/<id>/<id>.json`. Vanilla descriptors are downloaded from Mojang and refreshed
     * when Mojang republishes them (it does, e.g. to swap a vulnerable library); descriptors that
     * are not in the manifest (mod loader profiles) are used exactly as they are on disk.
     */
    suspend fun versionJson(id: String): VersionJson = withContext(Dispatchers.IO) {
        val file = paths.versionJson(id)
        val entry = runCatching { manifest() }.getOrNull()?.versions?.firstOrNull { it.id == id }
        if (!fs.exists(file)) {
            entry ?: throw IllegalArgumentException("Unknown Minecraft version \"$id\"")
            http.download(DownloadTask(entry.url, file, entry.sha1))
        } else if (entry?.sha1 != null && !entry.sha1.equals(sha1Of(file), ignoreCase = true)) {
            // Stale copy: try to update, but keep playing with the old one when offline.
            try {
                http.download(DownloadTask(entry.url, file, entry.sha1))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        LauncherJson.decodeFromString<VersionJson>(fs.read(file) { readUtf8() })
    }

    private fun sha1Of(file: Path): String = fs.read(file) { readByteString() }.sha1().hex()

    companion object {
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val LIBRARIES_URL = "https://libraries.minecraft.net/"
        const val RESOURCES_URL = "https://resources.download.minecraft.net/"
        const val JAVA_RUNTIMES_URL =
            "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"
    }
}
