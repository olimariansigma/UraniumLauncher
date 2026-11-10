package dev.uranium.launcher.launch

import dev.uranium.launcher.BuildInfo
import dev.uranium.launcher.account.Account
import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.install.InstalledVersion
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.mojang.Rule
import dev.uranium.launcher.mojang.RuleEnv
import dev.uranium.launcher.mojang.VersionJson
import dev.uranium.launcher.mojang.rulesAllow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path

data class LaunchOptions(
    val memoryMb: Int,
    val extraJvmArgs: List<String> = emptyList(),
    val features: Map<String, Boolean> = emptyMap(),
)

/** Turns an installed version + instance + account into the exact process to start. */
class LaunchPlanner(
    private val paths: LauncherPaths,
    private val platform: PlatformServices,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    /** Unpacks natives and legacy assets for [instance], then builds its command line. */
    fun prepare(
        instance: Instance,
        installed: InstalledVersion,
        account: Account,
        java: Path,
        options: LaunchOptions,
    ): LaunchSpec {
        val gameDir = paths.gameDir(instance.id)
        val nativesDir = paths.nativesDir(instance.id)
        fs.createDirectories(gameDir)

        // Natives are tied to the version being launched; start clean so stale DLLs never linger.
        fs.deleteRecursively(nativesDir, mustExist = false)
        fs.createDirectories(nativesDir)
        for (archive in installed.natives) {
            platform.extractZip(archive.file, nativesDir, archive.exclude + "META-INF/")
        }

        val gameAssets = prepareLegacyAssets(installed, gameDir)
        return buildSpec(
            installed.version, installed.classpath, installed.assetIndexId, installed.logConfig,
            gameDir, nativesDir, gameAssets, account, java, options,
        )
    }

    /**
     * Pre-1.7.3 versions read assets by file name rather than by hash: "virtual" indexes from a
     * shared folder, "map_to_resources" ones from `<gameDir>/resources`.
     */
    private fun prepareLegacyAssets(installed: InstalledVersion, gameDir: Path): Path {
        val index = installed.assetIndex
        val target = when {
            index.mapToResources -> gameDir / "resources"
            index.virtual -> paths.assets / "virtual" / installed.assetIndexId
            else -> return paths.assets
        }
        for ((name, obj) in index.objects) {
            val dest = target / name
            if (fs.metadataOrNull(dest)?.size == obj.size) continue
            dest.parent?.let(fs::createDirectories)
            fs.copy(paths.assetObject(obj.hash), dest)
        }
        return target
    }

    /** Pure command-line construction; kept free of I/O so it can be unit tested. */
    fun buildSpec(
        version: VersionJson,
        classpath: List<Path>,
        assetIndexId: String,
        logConfig: Path?,
        gameDir: Path,
        nativesDir: Path,
        gameAssets: Path,
        account: Account,
        java: Path,
        options: LaunchOptions,
    ): LaunchSpec {
        val env = RuleEnv.of(platform)
        val separator = if (platform.os == OsType.WINDOWS) ";" else ":"
        val values = mapOf(
            "auth_player_name" to account.username,
            "auth_uuid" to account.uuid,
            "auth_access_token" to account.accessToken,
            "auth_session" to account.accessToken,
            "auth_xuid" to account.xuid,
            "clientid" to "",
            "user_type" to account.userType,
            "user_properties" to "{}",
            "version_name" to version.id,
            "version_type" to (version.type ?: "release"),
            "game_directory" to gameDir.toString(),
            "assets_root" to paths.assets.toString(),
            "game_assets" to gameAssets.toString(),
            "assets_index_name" to assetIndexId,
            "natives_directory" to nativesDir.toString(),
            "library_directory" to paths.libraries.toString(),
            "classpath_separator" to separator,
            "classpath" to classpath.joinToString(separator),
            "launcher_name" to BuildInfo.NAME,
            "launcher_version" to BuildInfo.VERSION,
        )
        fun fill(arg: String) = PLACEHOLDER.replace(arg) { match -> values[match.groupValues[1]] ?: match.value }

        val jvm = ArrayList<String>()
        jvm += "-Xmx${options.memoryMb}M"
        jvm += "-Xms${minOf(options.memoryMb, 512)}M"
        jvm += options.extraJvmArgs
        val declaredJvm = version.arguments?.jvm.orEmpty()
        if (declaredJvm.isNotEmpty()) {
            jvm += flatten(declaredJvm, env, options.features).map(::fill)
        } else {
            // Descriptors older than 1.13 predate `arguments.jvm`; these are the launcher's defaults.
            if (platform.os == OsType.MACOS) jvm += "-Xdock:name=Minecraft"
            jvm += "-Djava.library.path=$nativesDir"
            jvm += "-Dminecraft.launcher.brand=${BuildInfo.NAME}"
            jvm += "-Dminecraft.launcher.version=${BuildInfo.VERSION}"
            jvm += listOf("-cp", classpath.joinToString(separator))
        }
        val logging = version.logging?.client
        if (logging != null && logConfig != null) jvm += logging.argument.replace("\${path}", logConfig.toString())

        val game = when {
            version.arguments?.game?.isNotEmpty() == true -> flatten(version.arguments.game, env, options.features)
            else -> version.minecraftArguments.orEmpty().split(' ').filter { it.isNotBlank() }
        }.map(::fill)

        return LaunchSpec(
            javaExecutable = java,
            jvmArgs = jvm,
            mainClass = version.mainClass ?: error("Version \"${version.id}\" has no main class"),
            gameArgs = game,
            workingDir = gameDir,
        )
    }

    private fun flatten(args: List<JsonElement>, env: RuleEnv, features: Map<String, Boolean>): List<String> {
        val out = ArrayList<String>()
        for (arg in args) {
            when (arg) {
                is JsonPrimitive -> arg.contentOrNull?.let(out::add)
                is JsonObject -> {
                    val rules = arg["rules"]?.let { LauncherJson.decodeFromJsonElement(RULES, it) }
                    if (!rulesAllow(rules, env, features)) continue
                    when (val value = arg["value"]) {
                        is JsonPrimitive -> value.contentOrNull?.let(out::add)
                        is JsonArray -> value.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(out::add) }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private companion object {
        val PLACEHOLDER = Regex("""\$\{([a-z_]+)}""")
        val RULES = ListSerializer(Rule.serializer())
    }
}

/** Splits a user-supplied JVM argument string on whitespace, honouring double quotes. */
fun splitArgs(text: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    var pending = false
    for (c in text) {
        when {
            c == '"' -> { quoted = !quoted; pending = true }
            c.isWhitespace() && !quoted -> {
                if (pending || current.isNotEmpty()) out += current.toString()
                current.clear(); pending = false
            }
            else -> current.append(c)
        }
    }
    if (pending || current.isNotEmpty()) out += current.toString()
    return out
}
