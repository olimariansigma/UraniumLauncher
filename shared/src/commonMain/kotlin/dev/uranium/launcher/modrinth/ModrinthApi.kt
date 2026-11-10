package dev.uranium.launcher.modrinth

import dev.uranium.launcher.net.Http
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ModrinthSearchResult(
    val hits: List<ModrinthHit> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    @SerialName("total_hits") val totalHits: Int = 0,
)

@Serializable
data class ModrinthHit(
    @SerialName("project_id") val projectId: String,
    val slug: String = "",
    val title: String = "",
    val description: String = "",
    val author: String = "",
    @SerialName("project_type") val projectType: String = "mod",
    val categories: List<String> = emptyList(),
    @SerialName("display_categories") val displayCategories: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    val downloads: Long = 0,
    val follows: Long = 0,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("client_side") val clientSide: String = "unknown",
    @SerialName("server_side") val serverSide: String = "unknown",
    @SerialName("date_modified") val dateModified: String = "",
) {
    val pageUrl: String get() = "https://modrinth.com/$projectType/${slug.ifEmpty { projectId }}"
}

enum class ModrinthProjectType(val id: String, val label: String, val usesLoaders: Boolean) {
    MOD("mod", "Mods", true),
    MODPACK("modpack", "Modpacks", true),
    RESOURCE_PACK("resourcepack", "Resource packs", false),
    SHADER("shader", "Shaders", false),
}

enum class ModrinthSort(val id: String, val label: String) {
    RELEVANCE("relevance", "Relevance"),
    DOWNLOADS("downloads", "Downloads"),
    FOLLOWS("follows", "Followers"),
    NEWEST("newest", "Newest"),
    UPDATED("updated", "Recently updated"),
}

data class ModrinthQuery(
    val text: String = "",
    val projectType: ModrinthProjectType = ModrinthProjectType.MOD,
    val gameVersion: String? = null,
    /** Modrinth loader id such as "fabric"; ignored for project types that have no loaders. */
    val loader: String? = null,
    val sort: ModrinthSort = ModrinthSort.RELEVANCE,
    val offset: Int = 0,
    val limit: Int = 20,
)

/** Modrinth API v2 (https://docs.modrinth.com). No key needed; a descriptive User-Agent is. */
class ModrinthApi(private val http: Http, private val baseUrl: String = "https://api.modrinth.com/v2") {

    suspend fun search(query: ModrinthQuery): ModrinthSearchResult =
        http.getJson<ModrinthSearchResult>("$baseUrl/search") {
            if (query.text.isNotBlank()) parameter("query", query.text.trim())
            parameter("facets", facets(query))
            parameter("index", query.sort.id)
            parameter("offset", query.offset)
            parameter("limit", query.limit.coerceIn(1, 100))
        }

    companion object {
        /**
         * Facets are AND-ed across the outer array and OR-ed within each inner one, e.g.
         * `[["project_type:mod"],["versions:1.21.1"],["categories:fabric"]]`.
         */
        fun facets(query: ModrinthQuery): String {
            val groups = ArrayList<List<String>>()
            groups += listOf("project_type:${query.projectType.id}")
            query.gameVersion?.takeIf { it.isNotBlank() }?.let { groups += listOf("versions:$it") }
            if (query.projectType.usesLoaders) {
                query.loader?.takeIf { it.isNotBlank() }?.let { groups += listOf("categories:$it") }
            }
            return JsonArray(groups.map { group -> JsonArray(group.map(::JsonPrimitive)) }).toString()
        }
    }
}
