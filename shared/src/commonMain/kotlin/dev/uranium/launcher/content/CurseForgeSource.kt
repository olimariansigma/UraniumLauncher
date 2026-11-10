package dev.uranium.launcher.content

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.net.Http
import dev.uranium.launcher.net.HttpStatusException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/**
 * CurseForge Core API (https://docs.curseforge.com/rest-api/). Every request needs an API key,
 * which CurseForge issues per application; [apiKey] returns an empty string when none is set.
 */
class CurseForgeSource(
    private val http: Http,
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.curseforge.com",
) : ContentSource {
    override val provider: ContentProvider get() = ContentProvider.CURSEFORGE

    override val unavailableReason: String?
        get() = if (apiKey().isBlank()) {
            "CurseForge needs an API key. Add one in Settings, or build the launcher with CURSEFORGE_API_KEY set."
        } else {
            null
        }

    /** Projects seen so far, so a blocked file can be linked to its project page without a refetch. */
    private val projects = HashMap<String, ContentProject>()

    @Serializable
    private data class Envelope<T>(val data: T, val pagination: Pagination? = null)

    @Serializable
    private data class Pagination(val totalCount: Int = 0)

    @Serializable
    private data class Mod(
        val id: Long,
        val name: String = "",
        val slug: String = "",
        val summary: String = "",
        val downloadCount: Double = 0.0,
        val classId: Int? = null,
        val links: Links? = null,
        val logo: Logo? = null,
        val authors: List<Author> = emptyList(),
        val categories: List<Category> = emptyList(),
    )

    @Serializable
    private data class Links(val websiteUrl: String? = null)

    @Serializable
    private data class Logo(val thumbnailUrl: String? = null)

    @Serializable
    private data class Author(val name: String = "")

    @Serializable
    private data class Category(val name: String = "")

    @Serializable
    private data class CfFile(
        val id: Long,
        val modId: Long,
        val displayName: String = "",
        val fileName: String = "",
        val fileLength: Long = 0,
        val releaseType: Int = 1,
        val isAvailable: Boolean = true,
        val downloadUrl: String? = null,
        val hashes: List<Hash> = emptyList(),
        val dependencies: List<Dependency> = emptyList(),
    )

    @Serializable
    private data class Hash(val value: String, val algo: Int)

    @Serializable
    private data class Dependency(val modId: Long, val relationType: Int)

    override suspend fun search(query: ContentQuery): ContentPage {
        val result = get<Envelope<List<Mod>>>("/v1/mods/search") {
            parameter("gameId", MINECRAFT_GAME_ID)
            parameter("classId", classId(query.type))
            if (query.text.isNotBlank()) parameter("searchFilter", query.text.trim())
            query.gameVersion?.takeIf { it.isNotBlank() }?.let { version ->
                parameter("gameVersion", version)
                // The API only honours a loader filter together with a game version.
                if (query.type.usesLoaders) loaderType(query.loader)?.let { parameter("modLoaderType", it) }
            }
            parameter("sortField", sortField(query.sort))
            parameter("sortOrder", "desc")
            parameter("index", query.offset)
            parameter("pageSize", query.limit.coerceIn(1, 50))
        }
        val hits = result.data.map { it.toProject(query.type) }
        // The API refuses to page past 10,000 results.
        return ContentPage(hits, (result.pagination?.totalCount ?: hits.size).coerceAtMost(10_000))
    }

    override suspend fun project(id: String): ContentProject {
        projects[id]?.let { return it }
        return get<Envelope<Mod>>("/v1/mods/$id").data.toProject(null)
    }

    override suspend fun bestFile(projectId: String, type: ContentType, gameVersion: String, loader: LoaderType): ContentFile? {
        // Quilt loads Fabric mods, and most projects only tag their files as Fabric.
        val loaders: List<Int?> = when {
            !type.usesLoaders -> listOf(null)
            loader == LoaderType.QUILT -> listOf(LOADER_QUILT, LOADER_FABRIC)
            else -> listOf(loaderType(loader))
        }
        for (loaderId in loaders) {
            val files = get<Envelope<List<CfFile>>>("/v1/mods/$projectId/files") {
                parameter("gameVersion", gameVersion)
                loaderId?.let { parameter("modLoaderType", it) }
                parameter("pageSize", 20)
            }.data.filter { it.isAvailable }
            val chosen = files.firstOrNull { it.releaseType == RELEASE } ?: files.firstOrNull()
            if (chosen != null) return chosen.toFile()
        }
        return null
    }

    override suspend fun file(projectId: String, fileId: String): ContentFile? = try {
        get<Envelope<CfFile>>("/v1/mods/$projectId/files/$fileId").data.toFile()
    } catch (e: HttpStatusException) {
        if (e.status == 404) null else throw e
    }

    private suspend fun CfFile.toFile(): ContentFile {
        val blocked = downloadUrl.isNullOrBlank()
        // The file object does not say where its project lives; look that up only when it matters.
        val page = if (blocked) runCatching { project(modId.toString()).pageUrl }.getOrNull() else null
        return ContentFile(
            provider = provider,
            projectId = modId.toString(),
            fileId = id.toString(),
            fileName = fileName,
            displayName = displayName.ifBlank { fileName },
            downloadUrl = downloadUrl?.takeIf { it.isNotBlank() },
            manualUrl = if (blocked) "${page ?: "https://www.curseforge.com/projects/$modId"}/download/$id" else null,
            sha1 = hashes.firstOrNull { it.algo == HASH_SHA1 }?.value,
            size = fileLength,
            requiredDependencies = dependencies.filter { it.relationType == REQUIRED_DEPENDENCY }
                .map { ContentDependency(it.modId.toString()) },
        )
    }

    private fun Mod.toProject(queried: ContentType?): ContentProject {
        val type = queried ?: ContentType.entries.firstOrNull { classId(it) == classId } ?: ContentType.MOD
        return ContentProject(
            provider = provider,
            id = id.toString(),
            slug = slug,
            title = name,
            description = summary,
            author = authors.firstOrNull()?.name.orEmpty(),
            iconUrl = logo?.thumbnailUrl,
            downloads = downloadCount.toLong(),
            categories = categories.map { it.name }.distinct(),
            pageUrl = links?.websiteUrl?.trimEnd('/') ?: "https://www.curseforge.com/projects/$id",
            type = type,
        ).also { projects[it.id] = it }
    }

    private suspend inline fun <reified T> get(path: String, crossinline configure: HttpRequestBuilder.() -> Unit = {}): T {
        val key = apiKey().trim()
        check(key.isNotEmpty()) { unavailableReason.orEmpty() }
        val text = try {
            http.getText(baseUrl + path) {
                header("x-api-key", key)
                header("Accept", "application/json")
                configure()
            }
        } catch (e: HttpStatusException) {
            if (e.status == 403) throw IllegalStateException("CurseForge rejected the API key. Check it in Settings.")
            throw e
        }
        return LauncherJson.decodeFromString<T>(text)
    }

    companion object {
        const val MINECRAFT_GAME_ID = 432
        private const val LOADER_FABRIC = 4
        private const val LOADER_QUILT = 5
        private const val RELEASE = 1
        private const val HASH_SHA1 = 1
        private const val REQUIRED_DEPENDENCY = 3

        fun classId(type: ContentType): Int = when (type) {
            ContentType.MOD -> 6
            ContentType.MODPACK -> 4471
            ContentType.RESOURCE_PACK -> 12
            ContentType.SHADER -> 6552
        }

        fun loaderType(loader: LoaderType?): Int? = when (loader) {
            LoaderType.FORGE -> 1
            LoaderType.FABRIC -> LOADER_FABRIC
            LoaderType.QUILT -> LOADER_QUILT
            LoaderType.NEOFORGE -> 6
            LoaderType.VANILLA, null -> null
        }

        /** CurseForge has no text-relevance sort; popularity is the closest match. */
        fun sortField(sort: ContentSort): Int = when (sort) {
            ContentSort.RELEVANCE -> 2
            ContentSort.DOWNLOADS -> 6
            ContentSort.UPDATED -> 3
            ContentSort.NEWEST -> 11
        }
    }
}
