package dev.uranium.launcher.install

import dev.uranium.launcher.core.Arch
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.core.writeTextAtomic
import dev.uranium.launcher.mojang.JavaRuntimeIndex
import dev.uranium.launcher.mojang.JavaRuntimeManifest
import dev.uranium.launcher.mojang.MojangMeta
import dev.uranium.launcher.net.DownloadTask
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Installs the exact Java runtime Mojang ships for a version (`javaVersion.component`), so players
 * never have to install or pick a JDK. Returns null on hosts Mojang publishes no runtime for.
 */
class JavaRuntimeInstaller(
    private val http: Http,
    private val paths: LauncherPaths,
    private val platform: PlatformServices,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    suspend fun ensure(component: String, parallelism: Int = 12, progress: ProgressListener = {}): Path? =
        withContext(Dispatchers.IO) {
            val key = platformKey(platform.os, platform.arch) ?: return@withContext null
            val home = paths.runtimes / component
            val marker = home / ".uranium-runtime"
            val java = javaExecutable(home, platform.os)

            val installedVersion = fs.readTextOrNull(marker)
            // An installed runtime keeps working offline; only look for updates when we can reach Mojang.
            val index = runCatching { http.getJson<JavaRuntimeIndex>(MojangMeta.JAVA_RUNTIMES_URL) }.getOrElse {
                if (installedVersion != null && fs.exists(java)) return@withContext java
                throw it
            }
            val build = index[key]?.get(component)?.firstOrNull() ?: return@withContext null
            if (installedVersion == build.version.name && fs.exists(java)) return@withContext java

            progress(Progress("Preparing Java ${build.version.name}"))
            val manifest = http.getJson<JavaRuntimeManifest>(build.manifest.url)
            fs.createDirectories(home)

            val tasks = ArrayList<DownloadTask>()
            val links = ArrayList<Pair<Path, String>>()
            for ((relative, entry) in manifest.files) {
                val dest = home / relative
                when (entry.type) {
                    "directory" -> fs.createDirectories(dest)
                    "file" -> entry.downloads["raw"]?.let { raw ->
                        tasks += DownloadTask(raw.url, dest, raw.sha1, raw.size, entry.executable)
                    }
                    "link" -> entry.target?.let { links += dest to it }
                }
            }
            http.downloadAll(tasks, "Downloading Java ${build.version.name}", parallelism, progress) { task ->
                if (task.executable) platform.markExecutable(task.dest)
            }
            // Files that were already present still need their mode when a previous run was interrupted.
            tasks.filter { it.executable }.forEach { platform.markExecutable(it.dest) }
            for ((link, target) in links) {
                if (fs.metadataOrNull(link) == null) {
                    link.parent?.let(fs::createDirectories)
                    runCatching { fs.createSymlink(link, target.toPath()) }
                }
            }

            check(fs.exists(java)) { "Java runtime $component is missing $java after install" }
            fs.writeTextAtomic(marker, build.version.name)
            java
        }

    companion object {
        fun platformKey(os: OsType, arch: Arch): String? = when (os) {
            OsType.WINDOWS -> when (arch) {
                Arch.X64 -> "windows-x64"
                Arch.X86 -> "windows-x86"
                Arch.ARM64 -> "windows-arm64"
                else -> null
            }
            OsType.LINUX -> when (arch) {
                Arch.X64 -> "linux"
                Arch.X86 -> "linux-i386"
                else -> null
            }
            OsType.MACOS -> when (arch) {
                Arch.X64 -> "mac-os"
                Arch.ARM64 -> "mac-os-arm64"
                else -> null
            }
            OsType.ANDROID -> null
        }

        fun javaExecutable(home: Path, os: OsType): Path = when (os) {
            // javaw: no console window, stdout/stderr still reach us through the pipes.
            OsType.WINDOWS -> home / "bin" / "javaw.exe"
            OsType.MACOS -> home / "jre.bundle" / "Contents" / "Home" / "bin" / "java"
            else -> home / "bin" / "java"
        }
    }
}
