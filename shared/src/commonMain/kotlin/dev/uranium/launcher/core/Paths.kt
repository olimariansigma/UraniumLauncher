package dev.uranium.launcher.core

import okio.FileSystem
import okio.Path

/** On-disk layout. Game files are shared between instances; each instance owns its game dir. */
class LauncherPaths(val root: Path) {
    val meta: Path = root / "meta"
    val versions: Path = root / "versions"
    val libraries: Path = root / "libraries"
    val assets: Path = root / "assets"
    val runtimes: Path = root / "runtimes"
    val instances: Path = root / "instances"
    val settingsFile: Path = root / "settings.json"

    fun versionDir(id: String): Path = versions / id
    fun versionJson(id: String): Path = versions / id / "$id.json"
    fun versionJar(id: String): Path = versions / id / "$id.jar"

    fun assetIndex(id: String): Path = assets / "indexes" / "$id.json"
    fun assetObject(hash: String): Path = assets / "objects" / hash.take(2) / hash

    fun instanceDir(id: String): Path = instances / id
    fun instanceFile(id: String): Path = instances / id / "instance.json"
    fun gameDir(id: String): Path = instances / id / "minecraft"
    fun nativesDir(id: String): Path = instances / id / "natives"

    fun ensureCreated(fs: FileSystem = FileSystem.SYSTEM) {
        listOf(root, meta, versions, libraries, assets, runtimes, instances).forEach(fs::createDirectories)
    }
}

/** Writes via a sibling temp file so a crash mid-write never leaves a truncated file behind. */
fun FileSystem.writeTextAtomic(path: Path, text: String) {
    path.parent?.let(::createDirectories)
    val tmp = path.parent!! / "${path.name}.tmp"
    write(tmp) { writeUtf8(text) }
    atomicMove(tmp, path)
}

fun FileSystem.readTextOrNull(path: Path): String? =
    if (exists(path)) runCatching { read(path) { readUtf8() } }.getOrNull() else null
