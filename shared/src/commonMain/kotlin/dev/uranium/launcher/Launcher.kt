package dev.uranium.launcher

import dev.uranium.launcher.account.Account
import dev.uranium.launcher.core.ConsoleBuffer
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.core.platform
import dev.uranium.launcher.install.GameInstaller
import dev.uranium.launcher.install.JavaRuntimeInstaller
import dev.uranium.launcher.install.VersionResolver
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.InstanceStore
import dev.uranium.launcher.instance.Settings
import dev.uranium.launcher.instance.SettingsStore
import dev.uranium.launcher.instance.autoMemoryMb
import dev.uranium.launcher.instance.isValidUsername
import dev.uranium.launcher.launch.GameLogParser
import dev.uranium.launcher.launch.LaunchOptions
import dev.uranium.launcher.launch.LaunchPlanner
import dev.uranium.launcher.launch.LaunchSpec
import dev.uranium.launcher.launch.splitArgs
import dev.uranium.launcher.modrinth.ModrinthApi
import dev.uranium.launcher.mojang.MojangMeta
import dev.uranium.launcher.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** What an instance is doing right now, as shown on its card. */
sealed interface InstanceActivity {
    data class Working(val progress: Progress) : InstanceActivity
    data object Running : InstanceActivity
    data class Failed(val message: String) : InstanceActivity

    /** Finished without starting the game (hosts that cannot launch yet). */
    data class Notice(val message: String) : InstanceActivity
}

/**
 * The launcher's application layer: owns the services, the instance list and the running games.
 * UI code only talks to this class and observes its flows.
 */
class Launcher(val platform: PlatformServices = dev.uranium.launcher.core.platform) {
    private val fs = FileSystem.SYSTEM
    val paths = LauncherPaths(platform.dataDir)
    val http = Http(platform.httpEngine)
    val meta = MojangMeta(http, paths)
    val modrinth = ModrinthApi(http)

    private val resolver = VersionResolver(meta)
    private val installer = GameInstaller(http, paths, resolver, platform)
    private val runtimes = JavaRuntimeInstaller(http, paths, platform)
    private val planner = LaunchPlanner(paths, platform)
    private val instanceStore = InstanceStore(paths)
    private val settingsStore = SettingsStore(paths)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val console = ConsoleBuffer(scope)

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> get() = _instances

    private val _activity = MutableStateFlow<Map<String, InstanceActivity>>(emptyMap())
    val activity: StateFlow<Map<String, InstanceActivity>> get() = _activity

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> get() = _settings

    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    init {
        paths.ensureCreated(fs)
        _settings.value = settingsStore.load()
        _instances.value = instanceStore.list()
    }

    val effectiveMemoryMb: Int
        get() = _settings.value.memoryMb.takeIf { it > 0 } ?: autoMemoryMb(platform.totalMemoryMb)

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.update(transform)
        val snapshot = _settings.value
        scope.launch(Dispatchers.IO) { runCatching { settingsStore.save(snapshot) } }
    }

    fun createInstance(name: String, mcVersion: String): Instance {
        val instance = instanceStore.create(name, mcVersion, platform.nowMillis())
        _instances.value = instanceStore.list()
        return instance
    }

    fun deleteInstance(id: String) {
        stop(id)
        scope.launch(Dispatchers.IO) {
            runCatching { instanceStore.delete(id) }
                .onFailure { console.append("[Uranium] Could not delete instance $id: ${it.message}") }
            _activity.update { it - id }
            _instances.value = instanceStore.list()
        }
    }

    fun isBusy(id: String): Boolean = jobs.value[id]?.isActive == true

    /** Installs whatever is missing for the instance, then starts the game. */
    fun play(id: String) {
        if (isBusy(id)) return
        val instance = _instances.value.firstOrNull { it.id == id } ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                setActivity(id, InstanceActivity.Working(Progress("Preparing")))
                val spec = prepareLaunch(instance) { setActivity(id, InstanceActivity.Working(it)) }

                val blocked = platform.launchUnavailableReason
                if (blocked != null) {
                    console.append("[Uranium] ${instance.name}: game files are installed. $blocked")
                    setActivity(id, InstanceActivity.Notice(blocked))
                    return@launch
                }

                console.append("[Uranium] Launching ${instance.name} (${instance.versionId}) with ${spec.javaExecutable}")
                markPlayed(instance)
                setActivity(id, InstanceActivity.Running)
                val log = GameLogParser(console::append)
                val exitCode = try {
                    platform.createGameRunner().run(spec, log::accept)
                } finally {
                    log.finish()
                }
                console.append("[Uranium] ${instance.name} exited with code $exitCode")
                if (exitCode == 0) {
                    _activity.update { it - id }
                } else {
                    setActivity(id, InstanceActivity.Failed("The game exited with code $exitCode. Open the console for details."))
                }
            } catch (e: CancellationException) {
                _activity.update { it - id }
                throw e
            } catch (e: Throwable) {
                console.append("[Uranium] ${instance.name} failed: ${e.message ?: e.toString()}")
                setActivity(id, InstanceActivity.Failed(e.message ?: e.toString()))
            } finally {
                jobs.update { it - id }
            }
        }
        jobs.update { it + (id to job) }
        job.start()
    }

    /** Cancels an install in progress, or kills the running game. */
    fun stop(id: String) {
        jobs.value[id]?.cancel()
    }

    fun dismissActivity(id: String) {
        if (!isBusy(id)) _activity.update { it - id }
    }

    /**
     * Downloads the version, its libraries/assets and a matching Java runtime, then builds the
     * process to start. Also used by the headless CLI.
     */
    suspend fun prepareLaunch(instance: Instance, progress: ProgressListener = {}): LaunchSpec {
        val settings = _settings.value
        val parallelism = settings.parallelDownloads
        val installed = installer.install(instance.versionId, parallelism, progress)
        val java = resolveJava(installed.version.javaVersion?.component ?: "jre-legacy", settings, progress)

        progress(Progress("Starting"))
        val username = settings.offlineUsername.takeIf(::isValidUsername) ?: "Player"
        val options = LaunchOptions(
            memoryMb = instance.memoryMb ?: effectiveMemoryMb,
            extraJvmArgs = splitArgs(instance.jvmArgs ?: settings.extraJvmArgs),
        )
        return withContext(Dispatchers.IO) {
            planner.prepare(instance, installed, Account.offline(username), java, options)
        }
    }

    private suspend fun resolveJava(component: String, settings: Settings, progress: ProgressListener): Path {
        if (settings.javaPath.isNotBlank()) {
            val custom = settings.javaPath.trim().toPath()
            check(fs.exists(custom)) { "The Java path set in Settings does not exist: $custom" }
            return custom
        }
        // Hosts that cannot start the game yet do not need a runtime either.
        if (platform.launchUnavailableReason != null) return "java".toPath()
        return runtimes.ensure(component, settings.parallelDownloads, progress)
            ?: platform.systemJavaCandidates().firstOrNull()
            ?: error("No Java runtime is available for this system. Set a Java path in Settings.")
    }

    private fun markPlayed(instance: Instance) {
        runCatching { instanceStore.save(instance.copy(lastPlayedAt = platform.nowMillis())) }
        _instances.value = instanceStore.list()
    }

    private fun setActivity(id: String, activity: InstanceActivity) {
        _activity.update { it + (id to activity) }
    }
}
