package dev.uranium.launcher.core

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import okio.Path
import java.io.File
import java.util.zip.ZipFile

/** The JDK-backed half of [PlatformServices], compiled into both the Android and desktop targets. */
abstract class JvmPlatformServices : PlatformServices {

    override val httpEngine: HttpClientEngineFactory<*> get() = OkHttp

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun extractZip(zip: Path, destDir: Path, exclude: List<String>) {
        val root = destDir.toFile().canonicalFile
        root.mkdirs()
        ZipFile(zip.toFile()).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory || exclude.any { entry.name.startsWith(it) }) continue
                val out = File(root, entry.name).canonicalFile
                // Zip-slip guard: never write outside the destination, whatever the entry is called.
                if (!out.path.startsWith(root.path + File.separator)) continue
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
            }
        }
    }

    override fun markExecutable(path: Path) {
        path.toFile().setExecutable(true, false)
    }
}
