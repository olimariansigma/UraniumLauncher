package dev.uranium.launcher

import dev.uranium.launcher.account.Account
import dev.uranium.launcher.account.AccountStore
import dev.uranium.launcher.account.AccountsState
import dev.uranium.launcher.account.DeviceCode
import dev.uranium.launcher.account.MicrosoftAuth
import dev.uranium.launcher.content.BlockedDownload
import dev.uranium.launcher.content.ContentManager
import dev.uranium.launcher.content.ContentProject
import dev.uranium.launcher.content.ContentProvider
import dev.uranium.launcher.content.ContentType
import dev.uranium.launcher.content.CurseForgeSource
import dev.uranium.launcher.content.ModrinthSource
import dev.uranium.launcher.core.ConsoleBuffer
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.core.PlatformServices
import dev.uranium.launcher.core.Progress
import dev.uranium.launcher.core.ProgressListener
import dev.uranium.launcher.importer.ForeignInstance
import dev.uranium.launcher.importer.PrismImporter
import dev.uranium.launcher.importer.ScanProgress
import dev.uranium.launcher.install.GameInstaller
import dev.uranium.launcher.install.JavaRuntimeInstaller
import dev.uranium.launcher.install.VersionResolver
import dev.uranium.launcher.instance.Instance
import dev.uranium.launcher.instance.InstanceStore
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.instance.Settings
import dev.uranium.launcher.instance.SettingsStore
import dev.uranium.launcher.instance.autoMemoryMb
import dev.uranium.launcher.instance.isValidUsername
import dev.uranium.launcher.launch.GameLogParser
import dev.uranium.launcher.launch.LaunchOptions
import dev.uranium.launcher.launch.LaunchPlanner
import dev.uranium.launcher.launch.LaunchSpec
import dev.uranium.launcher.launch.splitArgs
import dev.uranium.launcher.loader.LoaderService
import dev.uranium.launcher.loader.LoaderVersion
import dev.uranium.launcher.mojang.MojangMeta
import dev.uranium.launcher.net.Http
import dev.uranium.launcher.share.RedeemOutcome
import dev.uranium.launcher.share.ShareService
import dev.uranium.launcher.share.SharedPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

/** Progress of a Microsoft sign-in, driven by [Launcher.startMicrosoftLogin]. */
sealed interface LoginState {
    data object Idle : LoginState
    data object Starting : LoginState
    data class AwaitingUser(val code: DeviceCode) : LoginState
    data object Finishing : LoginState
    data class Failed(val message: String) : LoginState
}

/** State of one "install this project into that instance" request. */
sealed interface ContentJob {
    data class Working(val progress: Progress) : ContentJob
    data class Failed(val message: String) : ContentJob
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

    private val resolver = VersionResolver(meta)
    private val installer = GameInstaller(http, paths, resolver, platform)
    private val runtimes = JavaRuntimeInstaller(http, paths, platform)
    private val planner = LaunchPlanner(paths, platform)
    private val instanceStore = InstanceStore(paths)
    private val settingsStore = SettingsStore(paths)
    private val accountStore = AccountStore(paths.accountsFile)
    private val loaders = LoaderService(http, paths, platform)
    private val importer = PrismImporter(paths, instanceStore)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val console = ConsoleBuffer(scope)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> get() = _settings

    val microsoft = MicrosoftAuth(http, clientId = ::msaClientId, nowMillis = platform::nowMillis)

    val content = ContentManager(
        http, paths,
        mapOf(
            ContentProvider.MODRINTH to ModrinthSource(http),
            ContentProvider.CURSEFORGE to CurseForgeSource(http, apiKey = ::curseForgeApiKey),
        ),
    )

    val share = ShareService(http, paths, content, serverUrl = ::shareServerUrl)

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> get() = _instances

    private val _activity = MutableStateFlow<Map<String, InstanceActivity>>(emptyMap())
    val activity: StateFlow<Map<String, InstanceActivity>> get() = _activity

    private val _accounts = MutableStateFlow(AccountsState())
    val accounts: StateFlow<AccountsState> get() = _accounts

    private val _login = MutableStateFlow<LoginState>(LoginState.Idle)
    val login: StateFlow<LoginState> get() = _login

    private val _contentJobs = MutableStateFlow<Map<String, ContentJob>>(emptyMap())
    val contentJobs: StateFlow<Map<String, ContentJob>> get() = _contentJobs

    private val _blocked = MutableStateFlow<List<BlockedDownload>>(emptyList())
    val blocked: StateFlow<List<BlockedDownload>> get() = _blocked

    /** Bumped whenever any instance's installed content changes, so lists know to reload. */
    private val _contentRevision = MutableStateFlow(0)
    val contentRevision: StateFlow<Int> get() = _contentRevision

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Short one-off messages for the UI to show as a snackbar. */
    val notices: SharedFlow<String> get() = _notices

    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private var loginJob: Job? = null
    private var blockedWatcher: Job? = null

    init {
        paths.ensureCreated(fs)
        _settings.value = settingsStore.load()
        _instances.value = instanceStore.list()
        _accounts.value = accountStore.load().let { loaded ->
            // First run (or an upgrade from the username-only build): start with an offline profile.
            if (loaded.accounts.isNotEmpty()) loaded
            else AccountsState().with(Account.offline(_settings.value.offlineUsername.takeIf(::isValidUsername) ?: "Player"))
        }
    }

    // ---- Settings and credentials -------------------------------------------------------------

    val effectiveMemoryMb: Int
        get() = _settings.value.memoryMb.takeIf { it > 0 } ?: autoMemoryMb(platform.totalMemoryMb)

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.update(transform)
        val snapshot = _settings.value
        scope.launch(Dispatchers.IO) { runCatching { settingsStore.save(snapshot) } }
    }

    /** Settings override, then the runtime environment, then the id baked in at build time. */
    fun msaClientId(): String = _settings.value.msaClientId.trim().ifEmpty {
        platform.env("URANIUM_MSA_CLIENT_ID") ?: BuildInfo.MSA_CLIENT_ID
    }

    fun curseForgeApiKey(): String = _settings.value.curseForgeApiKey.trim().ifEmpty {
        platform.env("CURSEFORGE_API_KEY") ?: BuildInfo.CURSEFORGE_API_KEY
    }

    fun shareServerUrl(): String = _settings.value.shareServerUrl.trim().ifEmpty {
        platform.env("URANIUM_SHARE_URL") ?: BuildInfo.SHARE_URL
    }

    // ---- Accounts -----------------------------------------------------------------------------

    private fun setAccounts(state: AccountsState) {
        _accounts.value = state
        scope.launch(Dispatchers.IO) { runCatching { accountStore.save(state) } }
    }

    /** Returns an error message, or null when the account was added. */
    fun addOfflineAccount(username: String): String? {
        val name = username.trim()
        if (!isValidUsername(name)) return "Usernames are 3-16 characters: letters, digits and underscores."
        setAccounts(_accounts.value.with(Account.offline(name)))
        return null
    }

    fun selectAccount(id: String) {
        if (_accounts.value.accounts.any { it.id == id }) setAccounts(_accounts.value.copy(selectedId = id))
    }

    fun removeAccount(id: String) = setAccounts(_accounts.value.without(id))

    /** Starts the device-code flow; progress is published through [login]. */
    fun startMicrosoftLogin() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch {
            try {
                _login.value = LoginState.Starting
                val code = microsoft.requestDeviceCode()
                _login.value = LoginState.AwaitingUser(code)
                val tokens = microsoft.awaitDeviceCode(code)
                _login.value = LoginState.Finishing
                val account = microsoft.minecraftAccount(tokens)
                setAccounts(_accounts.value.with(account))
                _login.value = LoginState.Idle
                _notices.tryEmit("Signed in as ${account.username}")
            } catch (e: CancellationException) {
                _login.value = LoginState.Idle
                throw e
            } catch (e: Throwable) {
                _login.value = LoginState.Failed(e.message ?: "Sign-in failed.")
            }
        }
    }

    fun cancelMicrosoftLogin() {
        loginJob?.cancel()
        _login.value = LoginState.Idle
    }

    /** The selected account with a session that is valid right now. */
    private suspend fun accountForLaunch(): Account {
        val selected = _accounts.value.selected ?: Account.offline("Player")
        val fresh = microsoft.ensureFresh(selected)
        if (fresh != selected) setAccounts(_accounts.value.with(fresh, select = false).copy(selectedId = fresh.id))
        return fresh
    }

    // ---- Instances ----------------------------------------------------------------------------

    suspend fun loaderVersions(loader: LoaderType, mcVersion: String): List<LoaderVersion> =
        loaders.versions(loader, mcVersion)

    fun createInstance(
        name: String,
        mcVersion: String,
        loader: LoaderType = LoaderType.VANILLA,
        loaderVersion: String? = null,
    ): Instance {
        val instance = instanceStore.create(name, mcVersion, platform.nowMillis(), loader, loaderVersion)
        _instances.value = instanceStore.list()
        return instance
    }

    /** Saves edits to an instance's name or overrides. The id and game version never change. */
    fun updateInstance(instance: Instance) {
        runCatching { instanceStore.save(instance) }
        _instances.value = instanceStore.list()
    }

    fun deleteInstance(id: String) {
        stop(id)
        scope.launch(Dispatchers.IO) {
            runCatching { instanceStore.delete(id) }
                .onFailure { console.append("[Uranium] Could not delete instance $id: ${it.message}") }
            _activity.update { it - id }
            _blocked.update { list -> list.filterNot { it.instanceId == id } }
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

                console.append("[Uranium] Launching ${instance.name} with ${spec.javaExecutable}")
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
     * Downloads the version, its libraries/assets and a matching Java runtime, installs the mod
     * loader if the instance has one, then builds the process to start. Also used by the CLI.
     */
    suspend fun prepareLaunch(instance: Instance, progress: ProgressListener = {}): LaunchSpec {
        val settings = _settings.value
        val parallelism = settings.parallelDownloads
        val vanilla = installer.install(instance.mcVersion, parallelism, progress)
        val java = resolveJava(vanilla.version.javaVersion?.component ?: "jre-legacy", settings, progress)

        val installed = if (instance.loader == LoaderType.VANILLA) {
            vanilla
        } else {
            val loaderVersion = instance.loaderVersion
                ?: LoaderService.pickDefault(loaders.versions(instance.loader, instance.mcVersion))?.version
                ?: error("${instance.loader.displayName} is not available for Minecraft ${instance.mcVersion}")
            val versionId = loaders.ensureInstalled(
                instance.loader, instance.mcVersion, loaderVersion,
                java.takeIf { platform.launchUnavailableReason == null }, progress,
            )
            installer.install(versionId, parallelism, progress)
        }

        progress(Progress("Starting"))
        val account = accountForLaunch()
        val options = LaunchOptions(
            memoryMb = instance.memoryMb ?: effectiveMemoryMb,
            extraJvmArgs = splitArgs(instance.jvmArgs ?: settings.extraJvmArgs),
        )
        return withContext(Dispatchers.IO) {
            planner.prepare(instance, installed, account, java, options)
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
        // Re-read so edits made while the install ran are not overwritten.
        val current = _instances.value.firstOrNull { it.id == instance.id } ?: instance
        runCatching { instanceStore.save(current.copy(lastPlayedAt = platform.nowMillis())) }
        _instances.value = instanceStore.list()
    }

    private fun setActivity(id: String, activity: InstanceActivity) {
        _activity.update { it + (id to activity) }
    }

    // ---- Content (mods, resource packs, shaders) ----------------------------------------------

    fun contentJobKey(instanceId: String, project: ContentProject): String = "$instanceId|${project.key}"

    /** Installs [project] and its required dependencies into the instance. */
    fun installContent(instanceId: String, project: ContentProject) {
        val key = contentJobKey(instanceId, project)
        if (_contentJobs.value[key] is ContentJob.Working) return
        val instance = _instances.value.firstOrNull { it.id == instanceId } ?: return
        _contentJobs.update { it + (key to ContentJob.Working(Progress("Resolving dependencies"))) }
        scope.launch {
            try {
                val plan = content.plan(instance, project)
                val outcome = content.install(instance, plan) { progress ->
                    _contentJobs.update { it + (key to ContentJob.Working(progress)) }
                }
                _contentJobs.update { it - key }
                _contentRevision.update { it + 1 }
                if (outcome.blocked.isNotEmpty()) addBlocked(outcome.blocked)

                val extras = outcome.installed.size - 1
                val summary = buildString {
                    if (outcome.installed.isNotEmpty()) {
                        append("Installed ${project.title}")
                        if (extras > 0) append(" and $extras ${if (extras == 1) "dependency" else "dependencies"}")
                    }
                    if (outcome.blocked.isNotEmpty()) {
                        if (isNotEmpty()) append(". ")
                        append("${outcome.blocked.size} ${if (outcome.blocked.size == 1) "file needs" else "files need"} a manual download")
                    }
                    if (outcome.unresolved.isNotEmpty()) {
                        if (isNotEmpty()) append(". ")
                        append("No compatible version of: ${outcome.unresolved.joinToString()}")
                    }
                }
                if (summary.isNotEmpty()) _notices.tryEmit(summary)
            } catch (e: CancellationException) {
                _contentJobs.update { it - key }
                throw e
            } catch (e: Throwable) {
                _contentJobs.update { it + (key to ContentJob.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun dismissContentJob(key: String) = _contentJobs.update { it - key }

    fun setContentEnabled(instanceId: String, type: ContentType, fileName: String, enabled: Boolean) {
        runCatching { content.setEnabled(instanceId, type, fileName, enabled) }
        _contentRevision.update { it + 1 }
    }

    fun removeContent(instanceId: String, type: ContentType, fileName: String) {
        runCatching { content.remove(instanceId, type, fileName) }
        _contentRevision.update { it + 1 }
    }

    private fun addBlocked(items: List<BlockedDownload>) {
        _blocked.update { current -> current + items.filter { new -> current.none { it.key == new.key } } }
        if (blockedWatcher?.isActive != true) blockedWatcher = scope.launch(Dispatchers.IO) { watchDownloads() }
    }

    fun dismissBlocked(key: String) = _blocked.update { list -> list.filterNot { it.key == key } }

    /**
     * While manual downloads are pending, watches the Downloads folder and moves each expected file
     * into its instance as soon as the browser finishes writing it.
     */
    private suspend fun watchDownloads() {
        val downloads = platform.downloadsDir ?: return
        while (scope.isActive && _blocked.value.isNotEmpty()) {
            for (item in _blocked.value) {
                val candidate = downloadCandidates(item.file.fileName).map { downloads / it }.firstOrNull(fs::exists) ?: continue
                val done = runCatching { content.completeBlocked(item, candidate) }.getOrDefault(false)
                if (done) {
                    dismissBlocked(item.key)
                    _contentRevision.update { it + 1 }
                    _notices.tryEmit("Added ${item.project.title} from your Downloads folder")
                }
            }
            delay(1500)
        }
    }

    /** Lets the player point at a manually downloaded file when it is not in Downloads. */
    fun completeBlockedFrom(key: String, file: String): Boolean {
        val item = _blocked.value.firstOrNull { it.key == key } ?: return false
        val path = file.trim().trim('"').toPath()
        if (!fs.exists(path)) return false
        val done = runCatching { content.completeBlocked(item, path) }.getOrDefault(false)
        if (done) {
            dismissBlocked(key)
            _contentRevision.update { it + 1 }
        }
        return done
    }

    // ---- Import from other launchers ----------------------------------------------------------

    val canImport: Boolean get() = platform.importSearchRoots().isNotEmpty()

    suspend fun scanForImports(onProgress: (ScanProgress) -> Unit): List<ForeignInstance> =
        importer.scan(platform.importSearchRoots(), onProgress = onProgress)

    /** Reads a folder the player picked: either one instance or a folder full of them. */
    suspend fun scanFolderForImports(folder: String): List<ForeignInstance> {
        val path = folder.trim().trim('"').toPath()
        return if (fs.metadataOrNull(path)?.isDirectory == true) importer.scan(listOf(path), maxDepth = 4) else emptyList()
    }

    suspend fun importInstance(foreign: ForeignInstance, progress: ProgressListener = {}): Instance {
        val instance = importer.import(foreign, platform.nowMillis(), progress)
        _instances.value = instanceStore.list()
        return instance
    }

    // ---- Share codes -------------------------------------------------------------------------

    /**
     * Creates a new instance from a shared pack and downloads its content. The instance is kept
     * even when some items could not be fetched; the outcome says which.
     */
    suspend fun redeemPack(pack: SharedPack, progress: ProgressListener = {}): RedeemOutcome {
        val instance = createInstance(pack.name.take(48), pack.mcVersion, pack.loader, pack.loaderVersion)
        try {
            val (outcome, blocked) = share.apply(pack, instance, progress)
            if (blocked.isNotEmpty()) addBlocked(blocked)
            _contentRevision.update { it + 1 }
            return outcome
        } catch (e: CancellationException) {
            // Cancelled half way: do not leave a partial instance lying around.
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { instanceStore.delete(instance.id) } }
            _instances.value = instanceStore.list()
            throw e
        }
    }

    companion object {
        /** Browsers sometimes swap spaces for '+' or '_' when saving a file. */
        fun downloadCandidates(fileName: String): List<String> =
            listOf(fileName, fileName.replace(' ', '+'), fileName.replace(' ', '_')).distinct()
    }
}
