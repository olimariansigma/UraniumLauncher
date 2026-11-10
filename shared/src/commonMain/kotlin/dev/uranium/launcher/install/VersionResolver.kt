package dev.uranium.launcher.install

import dev.uranium.launcher.mojang.MojangMeta
import dev.uranium.launcher.mojang.VersionJson

/** Flattens `inheritsFrom` chains (mod loader profile -> vanilla) into one launchable descriptor. */
class VersionResolver(private val meta: MojangMeta) {

    suspend fun resolve(id: String): VersionJson {
        val chain = ArrayList<VersionJson>()
        var next: String? = id
        while (next != null) {
            check(chain.none { it.id == next }) { "Version \"$id\" has a circular inheritsFrom chain" }
            val json = meta.versionJson(next)
            chain += json
            next = json.inheritsFrom
        }
        // Fold from the vanilla root outwards so every child overrides its parent.
        return chain.reversed().reduce { parent, child -> child.mergedOnto(parent) }
    }
}

/**
 * Child-wins merge. Libraries are concatenated child-first so that, once rules are applied and
 * duplicates dropped, a loader's newer copy of a library shadows vanilla's.
 */
fun VersionJson.mergedOnto(parent: VersionJson): VersionJson = VersionJson(
    id = id,
    inheritsFrom = null,
    // The client jar always belongs to the root of the chain unless a profile says otherwise.
    jar = jar ?: parent.jar ?: parent.id,
    type = type ?: parent.type,
    mainClass = mainClass ?: parent.mainClass,
    minecraftArguments = minecraftArguments ?: parent.minecraftArguments,
    arguments = when {
        arguments == null -> parent.arguments
        parent.arguments == null -> arguments
        else -> VersionJson.Arguments(
            game = parent.arguments.game + arguments.game,
            jvm = parent.arguments.jvm + arguments.jvm,
        )
    },
    assetIndex = assetIndex ?: parent.assetIndex,
    assets = assets ?: parent.assets,
    downloads = parent.downloads + downloads,
    javaVersion = javaVersion ?: parent.javaVersion,
    libraries = libraries + parent.libraries,
    logging = logging ?: parent.logging,
)
