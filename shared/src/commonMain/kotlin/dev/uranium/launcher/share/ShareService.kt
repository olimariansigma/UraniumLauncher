package dev.uranium.launcher.share

import dev.uranium.launcher.content.BlockedDownload
import dev.uranium.launcher.content.ContentManager
import dev.uranium.launcher.content.ContentProject
import dev.uranium.launcher.content.ContentType
import dev.uranium.launcher.content.InstallPlan
import dev.uranium.launcher.content.PlannedFile
import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.net.Http
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path

/** Turns instances into share codes and back, through the Cloudflare Worker in `worker/`. */
class ShareService(
    private val http: Http,
    private val paths: LauncherPaths,
    private val content: ContentManager,
    private val serverUrl: () -> String,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    val isConfigured: Boolean get() = serverUrl().isNotBlank()

    /** Builds the manifest for [instance] without uploading anything, so the player can review it. */
    fun preview(instance: Instance, includeConfigs: Boolean): SharePreview {
        val shared = ArrayList<SharedContent>()
        val unshareable = ArrayList<String>()
        for (type in ContentType.entries) {
            if (type.folder == null) continue
            for (local in content.list(instance.id, type)) {
                val known = local.known
                if (known == null) {
                    unshareable += local.fileName.removeSuffix(".disabled")
                } else {
                    shared += SharedContent(
                        known.provider, known.projectId, known.fileId, known.type,
                        known.title, known.fileName, known.sha1, local.enabled,
                    )
                }
            }
        }
        val configs = if (includeConfigs) collectConfigs(paths.gameDir(instance.id)) else emptyMap()
        val pack = SharedPack(
            name = instance.name,
            mcVersion = instance.mcVersion,
            loader = instance.loader,
            loaderVersion = instance.loaderVersion,
            content = shared,
            configs = configs,
        )
        val size = LauncherJson.encodeToString(SharedPack.serializer(), pack).encodeToByteArray().size
        return SharePreview(pack, unshareable, configs.size, size)
    }

    suspend fun upload(pack: SharedPack): ShareTicket {
        val result = http.postJson("${baseUrl()}/packs", LauncherJson.encodeToString(SharedPack.serializer(), pack))
        if (!result.ok) throw IllegalStateException(errorMessage(result.body) ?: "The share server refused the pack (HTTP ${result.status}).")
        return LauncherJson.decodeFromString<ShareTicket>(result.body)
    }

    suspend fun fetch(code: String): SharedPack {
        val normalized = normalizeCode(code) ?: throw IllegalArgumentException("Share codes look like K7X-9QP.")
        val result = http.getResult("${baseUrl()}/packs/${normalized.encodeURLPathPart()}")
        if (result.status == 404) throw IllegalStateException("No pack with that code. It may have expired.")
        if (!result.ok) throw IllegalStateException(errorMessage(result.body) ?: "The share server answered HTTP ${result.status}.")
        val pack = LauncherJson.decodeFromString<SharedPack>(result.body)
        check(pack.format == SharedPack.FORMAT) { "This pack was made by a newer launcher. Update to use it." }
        return pack
    }

    /**
     * Downloads the pack's content into [instance] (which must match the pack's version and
     * loader) and writes its config files. Items that cannot be fetched are reported, not fatal.
     */
    suspend fun apply(
        pack: SharedPack,
        instance: Instance,
        progress: ProgressListener = {},
    ): Pair<RedeemOutcome, List<BlockedDownload>> = withContext(Dispatchers.IO) {
        val planned = ArrayList<PlannedFile>()
        val skipped = ArrayList<String>()
        pack.content.forEachIndexed { index, item ->
            progress(Progress("Looking up ${item.title}", index.toLong(), pack.content.size.toLong()))
            if (item.type.folder == null) return@forEachIndexed
            val source = content.source(item.provider)
            if (source.unavailableReason != null) {
                skipped += "${item.title} (${item.provider.label} is not set up)"
                return@forEachIndexed
            }
            try {
                val file = source.file(item.projectId, item.fileId)
                if (file == null) {
                    skipped += "${item.title} (no longer available)"
                } else {
                    // Trust the hash from the provider, but refuse a file that is not the shared one.
                    if (item.sha1 != null && file.sha1 != null && !item.sha1.equals(file.sha1, ignoreCase = true)) {
                        skipped += "${item.title} (file changed since it was shared)"
                    } else {
                        planned += PlannedFile(item.toProject(), file, isDependency = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped += "${item.title} (${e.message ?: "lookup failed"})"
            }
        }

        val outcome = content.install(instance, InstallPlan(planned, emptyList()), progress)
        for (item in pack.content.filterNot { it.enabled }) {
            runCatching { content.setEnabled(instance.id, item.type, item.fileName, enabled = false) }
        }

        val gameDir = paths.gameDir(instance.id)
        for ((relative, text) in pack.configs) {
            if (!isShareablePath(relative)) continue
            val dest = gameDir / relative
            dest.parent?.let(fs::createDirectories)
            fs.write(dest) { writeUtf8(text) }
        }
        RedeemOutcome(instance.id, outcome.installed.size, skipped, outcome.blocked.size) to outcome.blocked
    }

    /**
     * `options.txt` and the text files under `config/`. Anything that looks like it could hold a
     * login or token is left out, because whoever has the code can read every file in the pack.
     */
    private fun collectConfigs(gameDir: Path): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        fun consider(file: Path) {
            val relative = file.relativeTo(gameDir).segments.joinToString("/")
            if (!isShareablePath(relative) || looksSensitive(relative)) return
            val size = fs.metadataOrNull(file)?.size ?: return
            if (size > MAX_CONFIG_FILE_BYTES || total + size > MAX_CONFIG_TOTAL_BYTES || out.size >= MAX_CONFIG_FILES) return
            val text = runCatching { fs.read(file) { readByteString() }.utf8() }.getOrNull() ?: return
            // Binary files decode to text full of replacement or control characters; skip those.
            if (text.any { it == '�' || it == '\u0000' }) return
            out[relative] = text
            total += size.toInt()
        }
        (gameDir / "options.txt").takeIf(fs::exists)?.let(::consider)
        val configDir = gameDir / "config"
        if (fs.metadataOrNull(configDir)?.isDirectory == true) {
            fs.listRecursively(configDir)
                .filter { fs.metadataOrNull(it)?.isRegularFile == true }
                .sortedBy { it.toString() }
                .forEach(::consider)
        }
        return out
    }

    private fun SharedContent.toProject() = ContentProject(
        provider = provider, id = projectId, slug = "", title = title, description = "", author = "",
        iconUrl = null, downloads = 0, categories = emptyList(), pageUrl = "", type = type,
    )

    private fun baseUrl(): String = serverUrl().trim().trimEnd('/').ifEmpty {
        throw IllegalStateException("Share codes are not set up: no share server address is configured. See Settings.")
    }

    private fun errorMessage(body: String): String? =
        runCatching { LauncherJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        private const val CODE_LENGTH = 6
        private const val MAX_CONFIG_FILE_BYTES = 64 * 1024
        private const val MAX_CONFIG_TOTAL_BYTES = 300 * 1024
        private const val MAX_CONFIG_FILES = 300
        private val TEXT_EXTENSIONS = setOf("txt", "toml", "json", "json5", "jsonc", "properties", "cfg", "conf", "ini", "yml", "yaml", "snbt")
        private val SENSITIVE_WORDS = listOf("token", "secret", "password", "passwd", "credential", "auth", "account", "session", "apikey", "api_key", "api-key", "webhook", "login",
            // Not logins, but values that identify one player or machine.
            "fingerprint", "telemetry", "uuid", "cookie", "license",
        )

        /** "k7x-9qp", "K7X9QP" and " k7x 9qp " all become "K7X-9QP"; null when it is not a code. */
        fun normalizeCode(raw: String): String? {
            val code = raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()
            if (code.length != CODE_LENGTH || code.any { it !in CODE_ALPHABET }) return null
            return code.substring(0, 3) + "-" + code.substring(3)
        }

        /**
         * Only `options.txt` and text files below `config/` travel in a pack. The same check
         * guards writing a received pack, so a hostile manifest cannot place files anywhere else.
         */
        fun isShareablePath(relative: String): Boolean {
            if (relative.isEmpty() || relative.length > 240) return false
            if (relative.startsWith("/") || '\\' in relative || ':' in relative || '\u0000' in relative) return false
            val segments = relative.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
            if (relative == "options.txt") return true
            return segments.size >= 2 && segments[0] == "config" && segments.last().substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        }

        fun looksSensitive(relative: String): Boolean {
            val lower = relative.lowercase()
            return SENSITIVE_WORDS.any { it in lower }
        }
    }
}
