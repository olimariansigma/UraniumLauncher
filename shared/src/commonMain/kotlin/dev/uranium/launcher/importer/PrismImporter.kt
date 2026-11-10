package dev.uranium.launcher.importer

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.InstanceStore
import dev.uranium.launcher.instance.LoaderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path

/** An instance of Prism Launcher (or MultiMC / PolyMC, which share the format) found on disk. */
data class ForeignInstance(
    val name: String,
    val path: Path,
    val gameDir: Path?,
    val mcVersion: String,
    val loader: LoaderType,
    val loaderVersion: String?,
    val memoryMb: Int?,
    val jvmArgs: String?,
) {
    /** Which launcher it belongs to, guessed from the folder it lives in. */
    val origin: String
        get() {
            val text = path.toString().lowercase()
            return when {
                "prism" in text -> "Prism Launcher"
                "polymc" in text -> "PolyMC"
                "multimc" in text -> "MultiMC"
                else -> "Prism-format launcher"
            }
        }
}

data class ScanProgress(val foldersScanned: Int, val found: Int, val current: String)

/**
 * Finds Prism-format instances anywhere on the machine and copies them into this launcher.
 *
 * An instance is any folder holding both `instance.cfg` and `mmc-pack.json`, so portable copies
 * and relocated instance folders are found as well as the default data directories.
 */
class PrismImporter(
    private val paths: LauncherPaths,
    private val instances: InstanceStore,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    @Serializable
    private data class Pack(val components: List<Component> = emptyList())

    @Serializable
    private data class Component(val uid: String = "", val version: String? = null)

    /** Breadth-first search below [roots]; bounded in depth and folder count so it always ends. */
    suspend fun scan(
        roots: List<Path>,
        maxDepth: Int = 6,
        maxFolders: Int = 150_000,
        onProgress: (ScanProgress) -> Unit = {},
    ): List<ForeignInstance> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, ForeignInstance>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<Path, Int>>()
        roots.forEach { queue += it to 0 }
        var scanned = 0

        while (queue.isNotEmpty() && scanned < maxFolders) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = queue.removeFirst()
            if (!visited.add(dir.toString())) continue
            val children = runCatching { fs.listOrNull(dir) }.getOrNull() ?: continue
            scanned++
            if (scanned % 200 == 0) onProgress(ScanProgress(scanned, found.size, dir.toString()))

            if (children.any { it.name == "instance.cfg" } && children.any { it.name == "mmc-pack.json" }) {
                parse(dir)?.let { found[dir.toString()] = it }
                onProgress(ScanProgress(scanned, found.size, dir.toString()))
                continue // an instance's own folders hold game files, not more instances
            }
            if (depth >= maxDepth) continue
            for (child in children) {
                if (child.name in SKIPPED || child.name.startsWith("$")) continue
                // Hidden folders are skipped, except the ones launchers are known to live under.
                if (child.name.startsWith(".") && child.name !in HIDDEN_ALLOWED) continue
                val meta = runCatching { fs.metadataOrNull(child) }.getOrNull() ?: continue
                if (meta.isDirectory && meta.symlinkTarget == null) queue += child to depth + 1
            }
        }
        onProgress(ScanProgress(scanned, found.size, ""))
        found.values.sortedBy { it.name.lowercase() }
    }

    /** Reads one instance folder; null when it is not a usable Prism-format instance. */
    fun parse(dir: Path): ForeignInstance? {
        val pack = fs.readTextOrNull(dir / "mmc-pack.json")
            ?.let { runCatching { LauncherJson.decodeFromString<Pack>(it) }.getOrNull() } ?: return null
        val mcVersion = pack.components.firstOrNull { it.uid == "net.minecraft" }?.version ?: return null
        val loaderComponent = pack.components.firstOrNull { it.uid in LOADER_UIDS }
        val config = parseConfig(fs.readTextOrNull(dir / "instance.cfg").orEmpty())

        // Prism prefers "minecraft" when both exist, and falls back to ".minecraft".
        val gameDir = listOf(dir / "minecraft", dir / ".minecraft").firstOrNull { fs.metadataOrNull(it)?.isDirectory == true }
        return ForeignInstance(
            name = config["name"]?.takeIf { it.isNotBlank() } ?: dir.name,
            path = dir,
            gameDir = gameDir,
            mcVersion = mcVersion,
            loader = loaderComponent?.let { LOADER_UIDS[it.uid] } ?: LoaderType.VANILLA,
            loaderVersion = loaderComponent?.version,
            memoryMb = config["MaxMemAlloc"]?.toIntOrNull()?.takeIf { config["OverrideMemory"] == "true" && it >= 512 },
            jvmArgs = config["JvmArgs"]?.takeIf { config["OverrideJavaArgs"] == "true" && it.isNotBlank() },
        )
    }

    /**
     * Creates a new instance from [foreign] and copies its game folder (worlds, mods, configs,
     * options). Logs and crash reports are left behind. The original is never modified.
     */
    suspend fun import(foreign: ForeignInstance, now: Long, progress: ProgressListener = {}): Instance =
        withContext(Dispatchers.IO) {
            val created = instances.create(foreign.name, foreign.mcVersion, now)
            val instance = created.copy(
                loader = foreign.loader,
                loaderVersion = foreign.loaderVersion,
                memoryMb = foreign.memoryMb,
                jvmArgs = foreign.jvmArgs,
            )
            try {
                instances.save(instance)
                val source = foreign.gameDir
                if (source != null) {
                    progress(Progress("Counting files"))
                    val files = fs.listRecursively(source).filter { file ->
                        val top = file.relativeTo(source).segments.firstOrNull()
                        top !in NOT_COPIED && fs.metadataOrNull(file)?.isRegularFile == true
                    }.toList()
                    val target = paths.gameDir(instance.id)
                    files.forEachIndexed { index, file ->
                        currentCoroutineContext().ensureActive()
                        val dest = target / file.relativeTo(source)
                        dest.parent?.let(fs::createDirectories)
                        fs.copy(file, dest)
                        if (index % 25 == 0) progress(Progress("Copying ${foreign.name}", index.toLong(), files.size.toLong()))
                    }
                }
                instance
            } catch (e: Throwable) {
                // Never leave a half-copied instance behind, whether it failed or was cancelled.
                runCatching { instances.delete(instance.id) }
                if (e is CancellationException) throw e
                throw IllegalStateException("Could not import ${foreign.name}: ${e.message}", e)
            }
        }

    companion object {
        private val LOADER_UIDS = mapOf(
            "net.fabricmc.fabric-loader" to LoaderType.FABRIC,
            "org.quiltmc.quilt-loader" to LoaderType.QUILT,
            "net.minecraftforge" to LoaderType.FORGE,
            "net.neoforged" to LoaderType.NEOFORGE,
        )

        /** System and bulk folders that never hold a launcher and are slow to walk. */
        private val SKIPPED = setOf(
            "Windows", "Program Files", "Program Files (x86)", "ProgramData", "System Volume Information",
            "node_modules", "Temp", "tmp", "proc", "sys", "dev", "run", "boot", "lib", "lib64", "usr", "bin", "sbin",
            "etc", "var", "snap", "System", "Library", "Applications", "Caches", "cache", "Cache",
            "libraries", "assets", "versions",
        )
        private val HIDDEN_ALLOWED = setOf(".local", ".var", ".minecraft")
        private val NOT_COPIED = setOf("logs", "crash-reports", ".cache")

        /** `instance.cfg` is an INI file; newer Prism versions add a `[General]` section header. */
        fun parseConfig(text: String): Map<String, String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("#") && !it.startsWith(";") && '=' in it }
            .associate { line ->
                val value = line.substringAfter('=').trim()
                line.substringBefore('=').trim() to
                    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value
            }
    }
}
