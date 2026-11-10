package dev.uranium.launcher.core

import dev.uranium.launcher.launch.GameRunner
import io.ktor.client.engine.HttpClientEngineFactory
import okio.Path

enum class OsType(val mojangName: String) {
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("osx"),

    /** Android runs the Linux flavour of the game through the bundled runtime (see Phase 6). */
    ANDROID("linux"),
}

enum class Arch(val mojangName: String, val is64Bit: Boolean) {
    X64("x64", true),
    X86("x86", false),
    ARM64("arm64", true),
    ARM32("arm32", false),
}

/**
 * Everything the shared core needs from the host. Both targets are JVMs, so most of this is
 * implemented once in `src/jvmShared`; only the genuinely platform-specific parts differ.
 */
interface PlatformServices {
    val os: OsType
    val arch: Arch
    val osVersion: String

    /** Root folder for versions, libraries, assets, runtimes and instances. */
    val dataDir: Path
    val totalMemoryMb: Long

    /** Null when the game can be started here, otherwise a user-facing reason why not. */
    val launchUnavailableReason: String?

    val httpEngine: HttpClientEngineFactory<*>

    fun nowMillis(): Long

    /** Extracts [zip] into [destDir], skipping entries whose path starts with any of [exclude]. */
    fun extractZip(zip: Path, destDir: Path, exclude: List<String> = emptyList())

    fun markExecutable(path: Path)

    /** Opens a URL or folder in the system browser / file manager. Returns false if unsupported. */
    fun open(target: String): Boolean

    fun createGameRunner(): GameRunner

    /** Candidate `java` executables already on this machine, best first. */
    fun systemJavaCandidates(): List<Path>
}

expect val platform: PlatformServices
