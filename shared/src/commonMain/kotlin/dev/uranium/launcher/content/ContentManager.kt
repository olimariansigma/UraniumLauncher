package dev.uranium.launcher.content

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.PrettyJson
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.core.writeTextAtomic
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.net.DownloadTask
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Installs mods, resource packs and shaders into instances, resolving required dependencies, and
 * keeps a per-instance index (`content.json`) of what came from where.
 */
class ContentManager(
    private val http: Http,
    private val paths: LauncherPaths,
    private val sources: Map<ContentProvider, ContentSource>,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    fun source(provider: ContentProvider): ContentSource = sources.getValue(provider)

    private fun indexFile(instanceId: String): Path = paths.instanceDir(instanceId) / "content.json"

    fun folder(instanceId: String, type: ContentType): Path =
        paths.gameDir(instanceId) / (type.folder ?: error("${type.label} cannot be installed into an instance"))

    fun index(instanceId: String): ContentIndex = fs.readTextOrNull(indexFile(instanceId))
        ?.let { runCatching { LauncherJson.decodeFromString<ContentIndex>(it) }.getOrNull() }
        ?: ContentIndex()

    private fun saveIndex(instanceId: String, index: ContentIndex) =
        fs.writeTextAtomic(indexFile(instanceId), PrettyJson.encodeToString(ContentIndex.serializer(), index))

    /**
     * Works out everything [project] needs in [instance]: its newest compatible file plus, breadth
     * first, every required dependency that is not installed yet. Dependencies that have no file
     * for this game version/loader are reported in [InstallPlan.unresolved] rather than failing.
     */
    suspend fun plan(instance: Instance, project: ContentProject): InstallPlan {
        val source = source(project.provider)
        val installed = index(instance.id)
        val files = ArrayList<PlannedFile>()
        val unresolved = ArrayList<String>()
        val seen = HashSet<String>()

        val root = source.bestFile(project.id, project.type, instance.mcVersion, instance.loader)
            ?: error(
                "${project.title} has no ${project.type.singular} file for Minecraft ${instance.mcVersion}" +
                    if (project.type.usesLoaders) " on ${instance.loader.displayName}." else "."
            )
        files += PlannedFile(project, root, isDependency = false)
        seen += project.id

        val queue = ArrayDeque(root.requiredDependencies)
        while (queue.isNotEmpty()) {
            val dependency = queue.removeFirst()
            if (!seen.add(dependency.projectId) || installed.has(project.provider, dependency.projectId)) continue
            try {
                val depProject = source.project(dependency.projectId)
                // An exact version pin is honoured only if it still exists; otherwise take the newest fit.
                val file = dependency.fileId?.let { source.file(dependency.projectId, it) }
                    ?: source.bestFile(dependency.projectId, depProject.type, instance.mcVersion, instance.loader)
                if (file == null) {
                    unresolved += depProject.title
                } else {
                    files += PlannedFile(depProject, file, isDependency = true)
                    queue += file.requiredDependencies
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unresolved += "project ${dependency.projectId}"
            }
        }
        return InstallPlan(files, unresolved)
    }

    /** Downloads every file of [plan]. Files the author blocks from launchers come back as "blocked". */
    suspend fun install(instance: Instance, plan: InstallPlan, progress: ProgressListener = {}): InstallOutcome =
        withContext(Dispatchers.IO) {
            val installed = ArrayList<InstalledContent>()
            val blocked = ArrayList<BlockedDownload>()
            plan.files.forEachIndexed { position, planned ->
                progress(Progress("Installing ${planned.project.title}", position.toLong(), plan.files.size.toLong()))
                val url = planned.file.downloadUrl
                if (url == null) {
                    blocked += BlockedDownload(instance.id, planned.project, planned.file)
                } else {
                    val dest = folder(instance.id, planned.project.type) / safeFileName(planned.file.fileName)
                    http.download(DownloadTask(url, dest, planned.file.sha1))
                    installed += record(instance.id, planned.project, planned.file)
                }
            }
            progress(Progress("Done", plan.files.size.toLong(), plan.files.size.toLong()))
            InstallOutcome(installed, blocked, plan.unresolved)
        }

    /**
     * Finishes a blocked download from a file the player fetched themselves. Returns false when
     * [candidate] is not the expected file (wrong hash), in which case nothing is changed.
     */
    fun completeBlocked(blocked: BlockedDownload, candidate: Path): Boolean {
        val expected = blocked.file.sha1
        if (expected != null) {
            val actual = fs.read(candidate) { readByteString() }.sha1().hex()
            if (!actual.equals(expected, ignoreCase = true)) return false
        } else if (blocked.file.size > 0 && fs.metadataOrNull(candidate)?.size != blocked.file.size) {
            // No hash to check against: at least make sure the browser has finished writing it.
            return false
        }
        val dest = folder(blocked.instanceId, blocked.project.type) / safeFileName(blocked.file.fileName)
        dest.parent?.let(fs::createDirectories)
        fs.copy(candidate, dest)
        record(blocked.instanceId, blocked.project, blocked.file)
        return true
    }

    /** Adds [file] to the index, removing the file of any older version of the same project. */
    private fun record(instanceId: String, project: ContentProject, file: ContentFile): InstalledContent {
        val entry = InstalledContent(
            project.provider, project.id, file.fileId, project.type, project.title, safeFileName(file.fileName), file.sha1,
        )
        val current = index(instanceId)
        for (old in current.items.filter { it.key == entry.key && it.fileName != entry.fileName }) {
            val dir = folder(instanceId, old.type)
            fs.delete(dir / old.fileName, mustExist = false)
            fs.delete(dir / "${old.fileName}.disabled", mustExist = false)
        }
        saveIndex(instanceId, ContentIndex(current.items.filterNot { it.key == entry.key } + entry))
        return entry
    }

    /** Everything in the instance's folder for [type], including files added by hand. */
    fun list(instanceId: String, type: ContentType): List<LocalContent> {
        val dir = folder(instanceId, type)
        val known = index(instanceId).items.filter { it.type == type }.associateBy { it.fileName }
        return fs.listOrNull(dir).orEmpty().mapNotNull { path ->
            val meta = fs.metadataOrNull(path) ?: return@mapNotNull null
            // Resource and shader packs may be folders; mods are always files.
            if (meta.isDirectory && type == ContentType.MOD) return@mapNotNull null
            val enabled = !path.name.endsWith(".disabled")
            LocalContent(path.name, enabled, meta.size ?: 0, known[path.name.removeSuffix(".disabled")])
        }.sortedBy { it.title.lowercase() }
    }

    /** Disabled files are renamed to `<name>.disabled`, the convention other launchers use too. */
    fun setEnabled(instanceId: String, type: ContentType, fileName: String, enabled: Boolean) {
        val dir = folder(instanceId, type)
        val base = safeFileName(fileName).removeSuffix(".disabled")
        val from = dir / if (enabled) "$base.disabled" else base
        val to = dir / if (enabled) base else "$base.disabled"
        if (fs.exists(from)) fs.atomicMove(from, to)
    }

    fun remove(instanceId: String, type: ContentType, fileName: String) {
        val name = safeFileName(fileName)
        fs.deleteRecursively(folder(instanceId, type) / name, mustExist = false)
        val base = name.removeSuffix(".disabled")
        val current = index(instanceId)
        saveIndex(instanceId, ContentIndex(current.items.filterNot { it.type == type && it.fileName == base }))
    }

    companion object {
        /** File names come from remote APIs: keep only the last path segment so they stay in their folder. */
        fun safeFileName(name: String): String {
            val leaf = name.substringAfterLast('/').substringAfterLast('\\').trim()
            require(leaf.isNotEmpty() && leaf != "." && leaf != "..") { "Invalid file name: $name" }
            return leaf
        }
    }
}
