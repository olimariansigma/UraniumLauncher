package dev.uranium.launcher.net

import dev.uranium.launcher.BuildInfo
import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.buffer
import okio.use

/**
 * Identifies the launcher to the APIs it talks to. Modrinth requires a unique User-Agent of the
 * form `owner/project/version (contact)` and throttles generic ones.
 */
val USER_AGENT: String =
    "olimariansigma/UraniumLauncher/${BuildInfo.VERSION} (github.com/olimariansigma/UraniumLauncher)"

class HttpStatusException(val url: String, val status: Int, val bodySnippet: String) :
    Exception("HTTP $status for $url${if (bodySnippet.isNotBlank()) ": $bodySnippet" else ""}")

class ChecksumException(val url: String, val expected: String, val actual: String) :
    Exception("Checksum mismatch for $url (expected $expected, got $actual)")

data class HttpResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

data class DownloadTask(
    val url: String,
    val dest: Path,
    val sha1: String? = null,
    val size: Long? = null,
    val executable: Boolean = false,
)

class Http(engine: HttpClientEngineFactory<*>, private val fs: FileSystem = FileSystem.SYSTEM) {

    val client: HttpClient = HttpClient(engine) {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 10 * 60_000
        }
        defaultRequest { header(HttpHeaders.UserAgent, USER_AGENT) }
    }

    suspend fun getText(url: String, configure: HttpRequestBuilder.() -> Unit = {}): String = retrying(url) {
        val response = client.get(url, configure)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw HttpStatusException(url, response.status.value, body.take(200))
        body
    }

    suspend fun <T> getJson(
        url: String,
        deserializer: DeserializationStrategy<T>,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): T = LauncherJson.decodeFromString(deserializer, getText(url, configure))

    suspend inline fun <reified T> getJson(url: String, noinline configure: HttpRequestBuilder.() -> Unit = {}): T =
        getJson(url, serializer<T>(), configure)

    /** Requests where a non-2xx status is an expected, meaningful answer (OAuth polling etc.). */
    suspend fun getResult(url: String, headers: Map<String, String> = emptyMap()): HttpResult = retrying(url) {
        val response = client.get(url) { headers.forEach { (name, value) -> header(name, value) } }
        HttpResult(response.status.value, response.bodyAsText())
    }

    suspend fun postForm(url: String, fields: Map<String, String>): HttpResult = retrying(url) {
        val response = client.submitForm(url, parameters { fields.forEach { (name, value) -> append(name, value) } })
        HttpResult(response.status.value, response.bodyAsText())
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResult = retrying(url) {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json")
            headers.forEach { (name, value) -> header(name, value) }
            setBody(json)
        }
        HttpResult(response.status.value, response.bodyAsText())
    }

    suspend fun getBytes(url: String): ByteArray = retrying(url) {
        val response = client.get(url)
        if (!response.status.isSuccess()) throw HttpStatusException(url, response.status.value, "")
        val channel = response.bodyAsChannel()
        val out = okio.Buffer()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            out.write(chunk, 0, read)
        }
        out.readByteArray()
    }

    /** True when [task] is already on disk. Size is checked; hashes are verified on download. */
    fun isPresent(task: DownloadTask): Boolean {
        val meta = fs.metadataOrNull(task.dest) ?: return false
        if (!meta.isRegularFile) return false
        return task.size == null || task.size <= 0 || meta.size == task.size
    }

    /** Streams [task] to disk through a temp file and verifies its SHA-1 when one is known. */
    suspend fun download(task: DownloadTask, onBytes: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        task.dest.parent?.let(fs::createDirectories)
        val tmp = task.dest.parent!! / "${task.dest.name}.part"
        retrying(task.url) {
            client.prepareGet(task.url).execute { response ->
                if (!response.status.isSuccess()) throw HttpStatusException(task.url, response.status.value, "")
                val channel = response.bodyAsChannel()
                val hashing = HashingSink.sha1(fs.sink(tmp))
                hashing.buffer().use { sink ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val read = channel.readAvailable(chunk, 0, chunk.size)
                        if (read < 0) break
                        if (read > 0) {
                            sink.write(chunk, 0, read)
                            onBytes(read.toLong())
                        }
                    }
                }
                val actual = hashing.hash.hex()
                if (task.sha1 != null && !task.sha1.equals(actual, ignoreCase = true)) {
                    fs.delete(tmp)
                    throw ChecksumException(task.url, task.sha1, actual)
                }
            }
            fs.atomicMove(tmp, task.dest)
        }
    }

    /**
     * Downloads every missing task with bounded parallelism. Progress is reported per file because
     * many manifests (asset indexes aside) do not carry sizes.
     */
    suspend fun downloadAll(
        tasks: List<DownloadTask>,
        stage: String,
        parallelism: Int = 12,
        progress: ProgressListener = {},
        afterEach: (DownloadTask) -> Unit = {},
    ) {
        val pending = withContext(Dispatchers.IO) { tasks.distinctBy { it.dest }.filterNot(::isPresent) }
        if (pending.isEmpty()) return
        val total = pending.size.toLong()
        var done = 0L
        val lock = Mutex()
        progress(Progress(stage, 0, total))
        val gate = Semaphore(parallelism.coerceIn(1, 32))
        coroutineScope {
            for (task in pending) {
                launch {
                    gate.withPermit {
                        download(task)
                        afterEach(task)
                    }
                    val now = lock.withLock { ++done }
                    progress(Progress(stage, now, total))
                }
            }
        }
    }

    private suspend fun <T> retrying(url: String, attempts: Int = 4, block: suspend () -> T): T {
        var failure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                // Client errors will not fix themselves; only retry throttling and server errors.
                if (e.status != 429 && e.status < 500) throw e
                failure = e
            } catch (e: Exception) {
                failure = e
            }
            delay(400L * (1 shl attempt))
        }
        throw failure ?: IllegalStateException("Request failed: $url")
    }

    fun close() = client.close()
}
