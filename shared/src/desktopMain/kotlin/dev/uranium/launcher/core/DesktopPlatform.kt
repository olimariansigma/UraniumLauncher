package dev.uranium.launcher.core

import dev.uranium.launcher.launch.GameRunner
import dev.uranium.launcher.launch.ProcessGameRunner
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.awt.Desktop
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI

object DesktopPlatform : JvmPlatformServices() {

    override val os: OsType = System.getProperty("os.name").orEmpty().lowercase().let { name ->
        when {
            name.contains("win") -> OsType.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OsType.MACOS
            else -> OsType.LINUX
        }
    }

    override val arch: Arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "amd64", "x86_64" -> Arch.X64
        "aarch64", "arm64" -> Arch.ARM64
        "x86", "i386", "i486", "i586", "i686" -> Arch.X86
        else -> if (System.getProperty("os.arch").orEmpty().startsWith("arm")) Arch.ARM32 else Arch.X64
    }

    override val osVersion: String = System.getProperty("os.version").orEmpty()

    override val dataDir: Path by lazy {
        val override = System.getenv("URANIUM_HOME")?.takeIf { it.isNotBlank() }
        val home = System.getProperty("user.home")
        val dir = when {
            override != null -> File(override)
            os == OsType.WINDOWS -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "UraniumLauncher")
            os == OsType.MACOS -> File(home, "Library/Application Support/UraniumLauncher")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "UraniumLauncher")
        }
        dir.absoluteFile.toOkioPath()
    }

    override val totalMemoryMb: Long by lazy {
        runCatching {
            val bean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            bean.totalMemorySize / (1024 * 1024)
        }.getOrDefault(0L)
    }

    override val launchUnavailableReason: String? get() = null

    override fun open(target: String): Boolean = runCatching {
        val desktop = Desktop.getDesktop()
        if (target.startsWith("http://") || target.startsWith("https://")) {
            desktop.browse(URI(target))
        } else {
            desktop.open(File(target))
        }
        true
    }.getOrDefault(false)

    override fun createGameRunner(): GameRunner = ProcessGameRunner()

    override fun systemJavaCandidates(): List<Path> {
        val exe = if (os == OsType.WINDOWS) "java.exe" else "java"
        val homes = listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"))
        val fromHomes = homes.map { File(it, "bin/$exe") }
        val fromPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it, exe) }
        return (fromHomes + fromPath).filter { it.isFile }.map { it.absolutePath.toPath() }.distinct()
    }
}

actual val platform: PlatformServices get() = DesktopPlatform
