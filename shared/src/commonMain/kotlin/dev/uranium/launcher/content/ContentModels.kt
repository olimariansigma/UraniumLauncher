package dev.uranium.launcher.content

import dev.uranium.launcher.instance.LoaderType
import kotlinx.serialization.Serializable

@Serializable
enum class ContentProvider(val label: String) {
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge"),
}

@Serializable
enum class ContentType(val label: String, val singular: String, val folder: String?, val usesLoaders: Boolean) {
    MOD("Mods", "mod", "mods", true),
    RESOURCE_PACK("Resource packs", "resource pack", "resourcepacks", false),
    SHADER("Shaders", "shader pack", "shaderpacks", false),

    /** Browsable, but installing whole packs is not supported yet. */
    MODPACK("Modpacks", "modpack", null, true),
}

enum class ContentSort(val label: String) {
    RELEVANCE("Relevance"),
    DOWNLOADS("Downloads"),
    UPDATED("Recently updated"),
    NEWEST("Newest"),
}

data class ContentQuery(
    val text: String = "",
    val type: ContentType = ContentType.MOD,
    val gameVersion: String? = null,
    val loader: LoaderType? = null,
    val sort: ContentSort = ContentSort.RELEVANCE,
    val offset: Int = 0,
    val limit: Int = 20,
)

data class ContentProject(
    val provider: ContentProvider,
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val iconUrl: String?,
    val downloads: Long,
    val categories: List<String>,
    val pageUrl: String,
    val type: ContentType,
) {
    val key: String get() = "${provider.name}:$id"
}

data class ContentPage(val hits: List<ContentProject>, val total: Int)

data class ContentDependency(val projectId: String, val fileId: String? = null)

data class ContentFile(
    val provider: ContentProvider,
    val projectId: String,
    val fileId: String,
    val fileName: String,
    val displayName: String,
    /** Null when the author does not allow third-party launchers to download the file. */
    val downloadUrl: String?,
    /** Page where the player can download the file themselves when [downloadUrl] is null. */
    val manualUrl: String?,
    val sha1: String?,
    val size: Long,
    val requiredDependencies: List<ContentDependency>,
)

/** One place to search and download content from. */
interface ContentSource {
    val provider: ContentProvider

    /** Null when usable, otherwise why not (e.g. a missing API key). */
    val unavailableReason: String?

    suspend fun search(query: ContentQuery): ContentPage

    suspend fun project(id: String): ContentProject

    /** The newest file of [projectId] that fits the instance, or null when nothing is compatible. */
    suspend fun bestFile(projectId: String, type: ContentType, gameVersion: String, loader: LoaderType): ContentFile?

    suspend fun file(projectId: String, fileId: String): ContentFile?
}

/** A file this launcher put into an instance, remembered so it can be updated, removed and shared. */
@Serializable
data class InstalledContent(
    val provider: ContentProvider,
    val projectId: String,
    val fileId: String,
    val type: ContentType,
    val title: String,
    val fileName: String,
    val sha1: String? = null,
) {
    val key: String get() = "${provider.name}:$projectId"
}

@Serializable
data class ContentIndex(val items: List<InstalledContent> = emptyList()) {
    fun has(provider: ContentProvider, projectId: String): Boolean =
        items.any { it.provider == provider && it.projectId == projectId }
}

/** A file in an instance folder, whether or not this launcher installed it. */
data class LocalContent(val fileName: String, val enabled: Boolean, val size: Long, val known: InstalledContent?) {
    val title: String get() = known?.title ?: fileName.removeSuffix(".disabled").substringBeforeLast('.')
}

data class PlannedFile(val project: ContentProject, val file: ContentFile, val isDependency: Boolean)

data class InstallPlan(val files: List<PlannedFile>, val unresolved: List<String>)

/** A file the player has to fetch in their browser because the author blocks launcher downloads. */
data class BlockedDownload(
    val instanceId: String,
    val project: ContentProject,
    val file: ContentFile,
) {
    val key: String get() = "$instanceId:${project.key}:${file.fileId}"
}

data class InstallOutcome(
    val installed: List<InstalledContent>,
    val blocked: List<BlockedDownload>,
    val unresolved: List<String>,
)
