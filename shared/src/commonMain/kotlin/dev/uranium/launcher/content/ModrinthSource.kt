package dev.uranium.launcher.content

import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.net.Http
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Modrinth API v2 (https://docs.modrinth.com). No key needed; a descriptive User-Agent is. */
class ModrinthSource(private val http: Http, private val baseUrl: String = "https://api.modrinth.com/v2") : ContentSource {
    override val provider: ContentProvider get() = ContentProvider.MODRINTH
    override val unavailableReason: String? get() = null

    @Serializable
    private data class SearchResult(val hits: List<Hit> = emptyList(), @SerialName("total_hits") val totalHits: Int = 0)

    @Serializable
    private data class Hit(
        @SerialName("project_id") val projectId: String,
        val slug: String = "",
        val title: String = "",
        val description: String = "",
        val author: String = "",
        @SerialName("project_type") val projectType: String = "mod",
        val categories: List<String> = emptyList(),
        @SerialName("display_categories") val displayCategories: List<String> = emptyList(),
        val downloads: Long = 0,
        @SerialName("icon_url") val iconUrl: String? = null,
    )

    @Serializable
    private data class Project(
        val id: String,
        val slug: String = "",
        val title: String = "",
        val description: String = "",
        @SerialName("project_type") val projectType: String = "mod",
        val categories: List<String> = emptyList(),
        val downloads: Long = 0,
        @SerialName("icon_url") val iconUrl: String? = null,
    )

    @Serializable
    private data class Version(
        val id: String,
        @SerialName("project_id") val projectId: String,
        val name: String = "",
        @SerialName("version_number") val versionNumber: String = "",
        @SerialName("version_type") val versionType: String = "release",
        val files: List<VersionFile> = emptyList(),
        val dependencies: List<Dependency> = emptyList(),
    )

    @Serializable
    private data class VersionFile(
        val url: String,
        val filename: String,
        val primary: Boolean = false,
        val size: Long = 0,
        val hashes: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Dependency(
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("version_id") val versionId: String? = null,
        @SerialName("dependency_type") val type: String = "required",
    )

    override suspend fun search(query: ContentQuery): ContentPage {
        val result = http.getJson<SearchResult>("$baseUrl/search") {
            if (query.text.isNotBlank()) parameter("query", query.text.trim())
            parameter("facets", facets(query))
            parameter("index", sortIndex(query.sort))
            parameter("offset", query.offset)
            parameter("limit", query.limit.coerceIn(1, 100))
        }
        return ContentPage(
            hits = result.hits.map { hit ->
                ContentProject(
                    provider, hit.projectId, hit.slug, hit.title, hit.description, hit.author, hit.iconUrl, hit.downloads,
                    hit.displayCategories.ifEmpty { hit.categories }.filterNot(LOADER_IDS::contains),
                    pageUrl(hit.projectType, hit.slug.ifEmpty { hit.projectId }), typeOf(hit.projectType),
                )
            },
            total = result.totalHits,
        )
    }

    override suspend fun project(id: String): ContentProject {
        val project = http.getJson<Project>("$baseUrl/project/${id.encodeURLPathPart()}")
        return ContentProject(
            provider, project.id, project.slug, project.title, project.description, "", project.iconUrl, project.downloads,
            project.categories.filterNot(LOADER_IDS::contains),
            pageUrl(project.projectType, project.slug.ifEmpty { project.id }), typeOf(project.projectType),
        )
    }

    override suspend fun bestFile(projectId: String, type: ContentType, gameVersion: String, loader: LoaderType): ContentFile? {
        val versions = http.getJson<List<Version>>("$baseUrl/project/${projectId.encodeURLPathPart()}/version") {
            parameter("game_versions", jsonArray(listOf(gameVersion)))
            if (type.usesLoaders) loaderIds(loader).takeIf { it.isNotEmpty() }?.let { parameter("loaders", jsonArray(it)) }
        }
        // Newest first; prefer a proper release over betas when both exist.
        val chosen = versions.firstOrNull { it.versionType == "release" } ?: versions.firstOrNull()
        return chosen?.toFile()
    }

    override suspend fun file(projectId: String, fileId: String): ContentFile? =
        runCatching { http.getJson<Version>("$baseUrl/version/${fileId.encodeURLPathPart()}") }.getOrNull()?.toFile()

    private fun Version.toFile(): ContentFile? {
        val file = files.firstOrNull { it.primary } ?: files.firstOrNull() ?: return null
        return ContentFile(
            provider = provider,
            projectId = projectId,
            fileId = id,
            fileName = file.filename,
            displayName = name.ifBlank { versionNumber },
            downloadUrl = file.url,
            manualUrl = null,
            sha1 = file.hashes["sha1"],
            size = file.size,
            requiredDependencies = dependencies.filter { it.type == "required" && it.projectId != null }
                .map { ContentDependency(it.projectId!!, it.versionId) },
        )
    }

    companion object {
        private val LOADER_IDS = setOf("fabric", "quilt", "forge", "neoforge", "minecraft", "datapack")

        /**
         * Facets are AND-ed across the outer array and OR-ed within each inner one, e.g.
         * `[["project_type:mod"],["versions:1.21.1"],["categories:fabric"]]`.
         */
        fun facets(query: ContentQuery): String {
            val groups = ArrayList<List<String>>()
            groups += listOf("project_type:${typeId(query.type)}")
            query.gameVersion?.takeIf { it.isNotBlank() }?.let { groups += listOf("versions:$it") }
            if (query.type.usesLoaders && query.loader != null) {
                loaderIds(query.loader).takeIf { it.isNotEmpty() }?.let { ids -> groups += ids.map { "categories:$it" } }
            }
            return JsonArray(groups.map { group -> JsonArray(group.map(::JsonPrimitive)) }).toString()
        }

        /** Quilt loads Fabric mods too, so a Quilt instance may use either. */
        fun loaderIds(loader: LoaderType): List<String> = when (loader) {
            LoaderType.QUILT -> listOf("quilt", "fabric")
            else -> listOfNotNull(loader.modrinthId)
        }

        private fun jsonArray(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

        private fun typeId(type: ContentType) = when (type) {
            ContentType.MOD -> "mod"
            ContentType.MODPACK -> "modpack"
            ContentType.RESOURCE_PACK -> "resourcepack"
            ContentType.SHADER -> "shader"
        }

        private fun typeOf(id: String) = when (id) {
            "modpack" -> ContentType.MODPACK
            "resourcepack" -> ContentType.RESOURCE_PACK
            "shader" -> ContentType.SHADER
            else -> ContentType.MOD
        }

        private fun sortIndex(sort: ContentSort) = when (sort) {
            ContentSort.RELEVANCE -> "relevance"
            ContentSort.DOWNLOADS -> "downloads"
            ContentSort.UPDATED -> "updated"
            ContentSort.NEWEST -> "newest"
        }

        private fun pageUrl(type: String, slug: String) = "https://modrinth.com/$type/$slug"
    }
}
