package dev.uranium.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.uranium.launcher.launch.GameRunner
import dev.uranium.launcher.launch.LaunchSpec
import okio.Path
import okio.Path.Companion.toOkioPath

object AndroidPlatform : JvmPlatformServices() {
    private lateinit var appContext: Context

    /** Must be called from Application.onCreate before anything touches [platform]. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override val os: OsType get() = OsType.ANDROID

    override val arch: Arch
        get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> Arch.ARM64
            "armeabi-v7a", "armeabi" -> Arch.ARM32
            "x86_64" -> Arch.X64
            "x86" -> Arch.X86
            else -> Arch.ARM64
        }

    override val osVersion: String get() = Build.VERSION.RELEASE.orEmpty()

    /** App-scoped external storage: needs no permission and is visible to file managers. */
    override val dataDir: Path by lazy {
        (appContext.getExternalFilesDir(null) ?: appContext.filesDir).absoluteFile.toOkioPath()
    }

    override val totalMemoryMb: Long by lazy {
        val info = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    }

    override val launchUnavailableReason: String
        get() = "Game files are downloaded. Starting the game on Android needs the bundled Java runtime " +
            "and GL translation layer, which arrive in the Android runtime phase."

    override fun open(target: String): Boolean {
        if (!target.startsWith("http://") && !target.startsWith("https://")) return false
        return runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    override fun createGameRunner(): GameRunner = object : GameRunner {
        override suspend fun run(spec: LaunchSpec, onOutput: (String) -> Unit): Int =
            throw UnsupportedOperationException(launchUnavailableReason)
    }

    override fun systemJavaCandidates(): List<Path> = emptyList()
}

actual val platform: PlatformServices get() = AndroidPlatform
