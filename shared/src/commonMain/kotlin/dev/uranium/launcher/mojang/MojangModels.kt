package dev.uranium.launcher.mojang

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VersionManifest(val latest: Latest, val versions: List<VersionEntry>) {
    @Serializable
    data class Latest(val release: String, val snapshot: String)
}

@Serializable
data class VersionEntry(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String = "",
    val sha1: String? = null,
) {
    val isRelease: Boolean get() = type == "release"
    val isSnapshot: Boolean get() = type == "snapshot"
    val isOld: Boolean get() = type == "old_beta" || type == "old_alpha"
}

/**
 * A version descriptor. Vanilla ones come from Mojang; mod loaders ship partial ones that point at
 * a vanilla parent through [inheritsFrom] and are flattened by [VersionResolver].
 */
@Serializable
data class VersionJson(
    val id: String,
    val inheritsFrom: String? = null,
    val jar: String? = null,
    val type: String? = null,
    val mainClass: String? = null,
    val minecraftArguments: String? = null,
    val arguments: Arguments? = null,
    val assetIndex: AssetIndexRef? = null,
    val assets: String? = null,
    val downloads: Map<String, Download> = emptyMap(),
    val javaVersion: JavaVersion? = null,
    val libraries: List<Library> = emptyList(),
    val logging: Logging? = null,
) {
    @Serializable
    data class Arguments(val game: List<JsonElement> = emptyList(), val jvm: List<JsonElement> = emptyList())

    @Serializable
    data class AssetIndexRef(val id: String, val url: String, val sha1: String? = null, val size: Long? = null)

    @Serializable
    data class JavaVersion(val component: String = "jre-legacy", val majorVersion: Int = 8)

    @Serializable
    data class Logging(val client: Client? = null) {
        @Serializable
        data class Client(val argument: String, val file: LogFile, val type: String? = null)

        @Serializable
        data class LogFile(val id: String, val url: String, val sha1: String? = null, val size: Long? = null)
    }
}

@Serializable
data class Download(val url: String, val sha1: String? = null, val size: Long? = null, val path: String? = null)

@Serializable
data class Library(
    val name: String,
    val downloads: Downloads? = null,
    /** Maven repository base for loader libraries that omit [downloads]. */
    val url: String? = null,
    val sha1: String? = null,
    val natives: Map<String, String>? = null,
    val rules: List<Rule>? = null,
    val extract: Extract? = null,
) {
    @Serializable
    data class Downloads(val artifact: Download? = null, val classifiers: Map<String, Download>? = null)

    @Serializable
    data class Extract(val exclude: List<String> = emptyList())
}

@Serializable
data class Rule(val action: String, val os: Os? = null, val features: Map<String, Boolean>? = null) {
    @Serializable
    data class Os(val name: String? = null, val arch: String? = null, val version: String? = null)
}

@Serializable
data class AssetIndex(
    val objects: Map<String, AssetObject> = emptyMap(),
    val virtual: Boolean = false,
    @SerialName("map_to_resources") val mapToResources: Boolean = false,
) {
    @Serializable
    data class AssetObject(val hash: String, val size: Long = 0)
}

/** `all.json` of Mojang's Java runtime listing: platform -> component -> builds. */
typealias JavaRuntimeIndex = Map<String, Map<String, List<JavaRuntimeBuild>>>

@Serializable
data class JavaRuntimeBuild(val manifest: Download, val version: Version = Version()) {
    @Serializable
    data class Version(val name: String = "", val released: String = "")
}

@Serializable
data class JavaRuntimeManifest(val files: Map<String, Entry> = emptyMap()) {
    @Serializable
    data class Entry(
        val type: String,
        val executable: Boolean = false,
        val downloads: Map<String, Download> = emptyMap(),
        val target: String? = null,
    )
}
