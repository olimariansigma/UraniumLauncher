package dev.uranium.launcher.instance

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.PrettyJson
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.core.writeTextAtomic
import kotlinx.serialization.Serializable
import okio.FileSystem

@Serializable
data class Settings(
    /** Only read once, to turn the pre-accounts username into an offline account. */
    val offlineUsername: String = "Player",
    /** 0 = pick automatically from the machine's RAM. */
    val memoryMb: Int = 0,
    /** Blank = use the runtime Mojang ships for each version. */
    val javaPath: String = "",
    val extraJvmArgs: String = "",
    val parallelDownloads: Int = 12,
    /** Overrides for the credentials baked in at build time; blank = use the built-in ones. */
    val msaClientId: String = "",
    val curseForgeApiKey: String = "",
    /** Address of the share-code server (the Cloudflare Worker in `worker/`). */
    val shareServerUrl: String = "",
)

class SettingsStore(private val paths: LauncherPaths, private val fs: FileSystem = FileSystem.SYSTEM) {
    fun load(): Settings = fs.readTextOrNull(paths.settingsFile)
        ?.let { runCatching { LauncherJson.decodeFromString<Settings>(it) }.getOrNull() }
        ?: Settings()

    fun save(settings: Settings) =
        fs.writeTextAtomic(paths.settingsFile, PrettyJson.encodeToString(Settings.serializer(), settings))
}

/** Heap size when the user has not chosen one: a quarter of RAM, kept between 2 and 8 GB. */
fun autoMemoryMb(totalMemoryMb: Long): Int {
    if (totalMemoryMb <= 0) return 2048
    val quarter = (totalMemoryMb / 4).toInt()
    // Never ask for more than the machine can give without swapping.
    val ceiling = (totalMemoryMb - 1536).toInt().coerceAtLeast(1024)
    return quarter.coerceIn(2048, 8192).coerceAtMost(ceiling)
}

/** Minecraft usernames: 3-16 characters from [A-Za-z0-9_]. */
fun isValidUsername(name: String): Boolean = name.length in 3..16 && name.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }
