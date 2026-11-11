package dev.uranium.launcher.share

import dev.uranium.launcher.content.ContentProvider
import dev.uranium.launcher.content.ContentType
import dev.uranium.launcher.instance.LoaderType
import kotlinx.serialization.Serializable

/**
 * What a share code stands for: enough to rebuild an instance on another machine without sending
 * any game or mod files. Mods are named by provider, project and exact file, so the receiving
 * launcher downloads them from Modrinth/CurseForge itself and verifies their hashes.
 */
@Serializable
data class SharedPack(
    val format: Int = FORMAT,
    val name: String,
    val mcVersion: String,
    val loader: LoaderType = LoaderType.VANILLA,
    val loaderVersion: String? = null,
    val content: List<SharedContent> = emptyList(),
    /** Small text config files, keyed by path relative to the game folder. */
    val configs: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = 1
    }
}

@Serializable
data class SharedContent(
    val provider: ContentProvider,
    val projectId: String,
    val fileId: String,
    val type: ContentType,
    val title: String,
    val fileName: String,
    val sha1: String? = null,
    val enabled: Boolean = true,
)

/** A manifest ready to upload, plus what had to be left out and why. */
data class SharePreview(
    val pack: SharedPack,
    /** Files in the instance that did not come from Modrinth/CurseForge and so cannot be shared. */
    val unshareable: List<String>,
    val configFiles: Int,
    val sizeBytes: Int,
)

@Serializable
data class ShareTicket(val code: String, val expiresAt: String = "")

/** What happened when a shared pack was turned into an instance. */
data class RedeemOutcome(
    val instanceId: String,
    val installed: Int,
    /** Titles that could not be fetched, each with a short reason. */
    val skipped: List<String>,
    val blocked: Int,
)
