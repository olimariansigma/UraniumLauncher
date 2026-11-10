package dev.uranium.launcher

import dev.uranium.launcher.account.Account
import dev.uranium.launcher.account.AccountKind
import dev.uranium.launcher.account.AccountsState
import dev.uranium.launcher.account.MicrosoftAuth
import dev.uranium.launcher.content.ContentManager
import dev.uranium.launcher.content.ContentQuery
import dev.uranium.launcher.content.ContentSort
import dev.uranium.launcher.content.ContentType
import dev.uranium.launcher.content.CurseForgeSource
import dev.uranium.launcher.content.ModrinthSource
import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.LauncherPaths
import dev.uranium.launcher.importer.PrismImporter
import dev.uranium.launcher.install.GameInstaller
import dev.uranium.launcher.install.mergedOnto
import dev.uranium.launcher.instance.InstanceStore
import dev.uranium.launcher.instance.LoaderType
import dev.uranium.launcher.instance.autoMemoryMb
import dev.uranium.launcher.instance.isValidUsername
import dev.uranium.launcher.launch.GameLogParser
import dev.uranium.launcher.launch.splitArgs
import dev.uranium.launcher.loader.LoaderService
import dev.uranium.launcher.loader.LoaderVersion
import dev.uranium.launcher.mojang.MavenCoordinate
import dev.uranium.launcher.mojang.Rule
import dev.uranium.launcher.mojang.RuleEnv
import dev.uranium.launcher.mojang.VersionJson
import dev.uranium.launcher.mojang.rulesAllow
import dev.uranium.launcher.ui.compactCount
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val windows = RuleEnv("windows", "x64", "10.0")
    private val linux = RuleEnv("linux", "x64", "6.8")
    private val mac = RuleEnv("osx", "arm64", "14.5")

    @Test
    fun rulesFollowLastMatch() {
        assertTrue(rulesAllow(null, windows))
        val notMac = listOf(Rule("allow"), Rule("disallow", Rule.Os(name = "osx")))
        assertTrue(rulesAllow(notMac, windows))
        assertFalse(rulesAllow(notMac, mac))
        val onlyMac = listOf(Rule("allow", Rule.Os(name = "osx")))
        assertFalse(rulesAllow(onlyMac, linux))
        assertTrue(rulesAllow(onlyMac, mac))
    }

    @Test
    fun rulesCheckFeaturesAndOsVersion() {
        val demo = listOf(Rule("allow", features = mapOf("is_demo_user" to true)))
        assertFalse(rulesAllow(demo, windows))
        assertTrue(rulesAllow(demo, windows, mapOf("is_demo_user" to true)))
        val win10 = listOf(Rule("allow", Rule.Os(name = "windows", version = "^10\\.")))
        assertTrue(rulesAllow(win10, windows))
        assertFalse(rulesAllow(win10, windows.copy(osVersion = "6.1")))
    }

    @Test
    fun mavenCoordinatesMapToRepositoryPaths() {
        assertEquals(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar",
            MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.3:natives-windows").path,
        )
        assertEquals("net/fabricmc/intermediary/1.21/intermediary-1.21.jar", MavenCoordinate.parse("net.fabricmc:intermediary:1.21").path)
        assertEquals("a/b/c/1/c-1-x.zip", MavenCoordinate.parse("a.b:c:1:x@zip").path)
    }

    @Test
    fun offlineUuidMatchesVanillaServers() {
        // The well-known offline-mode UUID for "Notch".
        assertEquals("b50ad385829d3141a2167e7d7539ba7f", Account.offline("Notch").uuid)
    }

    @Test
    fun accountListKeepsASelection() {
        val steve = Account.offline("Steve")
        val alex = Account(AccountKind.MICROSOFT, "Alex", "00aa", accessToken = "t", expiresAt = 5)
        var state = AccountsState().with(steve).with(alex)
        assertEquals(alex.id, state.selected?.id)
        // Re-adding an account (a token refresh) replaces it instead of duplicating it.
        state = state.with(alex.copy(accessToken = "t2"), select = false)
        assertEquals(2, state.accounts.size)
        assertEquals("t2", state.selected?.accessToken)
        state = state.without(alex.id)
        assertEquals(steve.id, state.selected?.id)
        assertNull(state.without(steve.id).selected)
        assertEquals("msa", alex.userType)
        assertEquals("legacy", steve.userType)
    }

    @Test
    fun xboxErrorsAreExplained() {
        assertTrue(MicrosoftAuth.xboxErrorMessage("2148916233")!!.contains("Xbox profile"))
        assertTrue(MicrosoftAuth.xboxErrorMessage("2148916238")!!.contains("child"))
        assertNull(MicrosoftAuth.xboxErrorMessage("1"))
        assertNull(MicrosoftAuth.xboxErrorMessage(null))
    }

    @Test
    fun libraryPlanHandlesModernLegacyAndLoaderEntries() {
        val json = """
        {"id":"t","libraries":[
          {"name":"com.mojang:brigadier:1.0","downloads":{"artifact":{"path":"com/mojang/brigadier/1.0/brigadier-1.0.jar","url":"https://l/b.jar","sha1":"aa","size":3}}},
          {"name":"org.lwjgl:lwjgl:3.3.3:natives-windows","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar","url":"https://l/w.jar"}},"rules":[{"action":"allow","os":{"name":"windows"}}]},
          {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar","url":"https://l/l.jar"}},"rules":[{"action":"allow","os":{"name":"linux"}}]},
          {"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4","downloads":{"classifiers":{"natives-windows":{"path":"p/natives-windows.jar","url":"https://l/n.jar"}}},"natives":{"windows":"natives-windows","linux":"natives-linux"},"extract":{"exclude":["META-INF/"]}},
          {"name":"net.fabricmc:fabric-loader:0.16.0","url":"https://maven.fabricmc.net/"},
          {"name":"net.minecraftforge:forge:1.21.1-52.0.1:client","downloads":{"artifact":{"path":"net/minecraftforge/forge/1.21.1-52.0.1/forge-1.21.1-52.0.1-client.jar","url":""}}},
          {"name":"com.mojang:brigadier:0.9","downloads":{"artifact":{"path":"com/mojang/brigadier/0.9/brigadier-0.9.jar","url":"https://l/old.jar"}}}
        ]}
        """.trimIndent()
        val version = LauncherJson.decodeFromString<VersionJson>(json)
        val paths = LauncherPaths("/data".toPath())
        val plan = GameInstaller.planLibraries(version, windows, paths, is64Bit = true)

        val names = plan.classpath.map { it.name }
        assertEquals(
            listOf("brigadier-1.0.jar", "lwjgl-3.3.3-natives-windows.jar", "fabric-loader-0.16.0.jar", "forge-1.21.1-52.0.1-client.jar"),
            names,
        )
        assertEquals(listOf("natives-windows.jar"), plan.natives.map { it.file.name })
        assertEquals(listOf("META-INF/"), plan.natives.single().exclude)
        assertTrue(plan.downloads.any { it.url == "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.0/fabric-loader-0.16.0.jar" })
        assertFalse(plan.downloads.any { it.url.endsWith("l.jar") })
        // Jars the Forge installer produces locally are on the classpath but never downloaded.
        assertFalse(plan.downloads.any { it.dest.name.contains("forge") })
    }

    @Test
    fun childVersionOverridesParent() {
        val parent = LauncherJson.decodeFromString<VersionJson>(
            """{"id":"1.21","mainClass":"net.minecraft.client.main.Main","assets":"17","libraries":[{"name":"a:b:1"}],
               "arguments":{"game":["--username"],"jvm":["-cp"]},"downloads":{"client":{"url":"https://c"}}}"""
        )
        val child = LauncherJson.decodeFromString<VersionJson>(
            """{"id":"fabric-1.21","inheritsFrom":"1.21","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
               "libraries":[{"name":"a:b:2"}],"arguments":{"game":[],"jvm":["-DFabric=1"]}}"""
        )
        val merged = child.mergedOnto(parent)
        assertEquals("fabric-1.21", merged.id)
        assertEquals("1.21", merged.jar)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", merged.mainClass)
        assertEquals(listOf("a:b:2", "a:b:1"), merged.libraries.map { it.name })
        assertEquals(2, merged.arguments!!.jvm.size)
        assertEquals("https://c", merged.downloads["client"]!!.url)
        // Fabric never names the game jar; Forge-style loaders do and need their own copy.
        assertFalse(GameInstaller.needsOwnJar(merged, "1.21"))
        val forge = LauncherJson.decodeFromString<VersionJson>(
            """{"id":"1.21-forge","inheritsFrom":"1.21","arguments":{"jvm":["-DignoreList=asm-,${'$'}{version_name}.jar"]}}"""
        ).mergedOnto(parent)
        assertTrue(GameInstaller.needsOwnJar(forge, "1.21"))
    }

    @Test
    fun logParserUnwrapsLog4jEvents() {
        val out = ArrayList<String>()
        val parser = GameLogParser(out::add)
        listOf(
            "plain line",
            """  <log4j:Event logger="net.minecraft.client.Minecraft" timestamp="1" level="INFO" thread="Render thread">""",
            """    <log4j:Message><![CDATA[Setting user: Player]]></log4j:Message>""",
            """  </log4j:Event>""",
            """<log4j:Event logger="x" timestamp="2" level="ERROR" thread="main">""",
            """<log4j:Message><![CDATA[Boom]]></log4j:Message>""",
            """<log4j:Throwable><![CDATA[java.lang.IllegalStateException: no""",
            """	at a.b(C.java:1)]]></log4j:Throwable>""",
            """</log4j:Event>""",
            "<log4j:Event level=\"WARN\" thread=\"t\">",
            "cut off",
        ).forEach(parser::accept)
        parser.finish()
        assertEquals(
            listOf(
                "plain line",
                "[Render thread/INFO]: Setting user: Player",
                "[main/ERROR]: Boom",
                "java.lang.IllegalStateException: no",
                "\tat a.b(C.java:1)",
                "<log4j:Event level=\"WARN\" thread=\"t\">",
                "cut off",
            ),
            out,
        )
    }

    @Test
    fun modrinthFacetsAreAndOfOrGroups() {
        assertEquals(
            """[["project_type:mod"],["versions:1.21.1"],["categories:fabric"]]""",
            ModrinthSource.facets(ContentQuery(gameVersion = "1.21.1", loader = LoaderType.FABRIC)),
        )
        // Quilt runs Fabric mods too, so either tag is acceptable.
        assertEquals(
            """[["project_type:mod"],["categories:quilt","categories:fabric"]]""",
            ModrinthSource.facets(ContentQuery(loader = LoaderType.QUILT)),
        )
        // Resource packs have no loader, so a stale loader filter must not hide every result.
        assertEquals(
            """[["project_type:resourcepack"]]""",
            ModrinthSource.facets(ContentQuery(type = ContentType.RESOURCE_PACK, loader = LoaderType.FABRIC)),
        )
    }

    @Test
    fun curseForgeIdsMatchTheApi() {
        assertEquals(6, CurseForgeSource.classId(ContentType.MOD))
        assertEquals(12, CurseForgeSource.classId(ContentType.RESOURCE_PACK))
        assertEquals(6552, CurseForgeSource.classId(ContentType.SHADER))
        assertEquals(1, CurseForgeSource.loaderType(LoaderType.FORGE))
        assertEquals(4, CurseForgeSource.loaderType(LoaderType.FABRIC))
        assertEquals(6, CurseForgeSource.loaderType(LoaderType.NEOFORGE))
        assertNull(CurseForgeSource.loaderType(LoaderType.VANILLA))
        assertEquals(6, CurseForgeSource.sortField(ContentSort.DOWNLOADS))
    }

    @Test
    fun neoForgeVersionPrefixes() {
        assertEquals("21.1.", LoaderService.neoForgePrefix("1.21.1"))
        assertEquals("21.0.", LoaderService.neoForgePrefix("1.21"))
        assertEquals("20.4.", LoaderService.neoForgePrefix("1.20.4"))
        assertEquals("26.3.0.", LoaderService.neoForgePrefix("26.3"))
        assertEquals("26.1.2.", LoaderService.neoForgePrefix("26.1.2"))
        // NeoForge did not exist as its own artifact before 1.20.2; snapshots have no builds.
        assertNull(LoaderService.neoForgePrefix("1.20.1"))
        assertNull(LoaderService.neoForgePrefix("1.12.2"))
        assertNull(LoaderService.neoForgePrefix("26.4-snapshot-8"))
    }

    @Test
    fun defaultLoaderVersionPrefersRecommendedThenStable() {
        val beta = LoaderVersion("3-beta", stable = false)
        val stable = LoaderVersion("2")
        val recommended = LoaderVersion("1", recommended = true)
        assertEquals("1", LoaderService.pickDefault(listOf(beta, stable, recommended))?.version)
        assertEquals("2", LoaderService.pickDefault(listOf(beta, stable))?.version)
        assertEquals("3-beta", LoaderService.pickDefault(listOf(beta))?.version)
        assertNull(LoaderService.pickDefault(emptyList()))
    }

    @Test
    fun prismInstanceConfigIsParsed() {
        val config = PrismImporter.parseConfig(
            """
            [General]
            name=My Pack
            OverrideMemory=true
            MaxMemAlloc=6144
            JvmArgs="-XX:+UseZGC -Dfoo=a b"
            ; comment
            iconKey=default
            """.trimIndent()
        )
        assertEquals("My Pack", config["name"])
        assertEquals("6144", config["MaxMemAlloc"])
        assertEquals("-XX:+UseZGC -Dfoo=a b", config["JvmArgs"])
        assertFalse("[General]" in config.keys)
    }

    @Test
    fun remoteFileNamesCannotEscapeTheirFolder() {
        assertEquals("sodium.jar", ContentManager.safeFileName("sodium.jar"))
        assertEquals("evil.jar", ContentManager.safeFileName("../../evil.jar"))
        assertEquals("evil.jar", ContentManager.safeFileName("..\\..\\evil.jar"))
        assertFailsWith<IllegalArgumentException> { ContentManager.safeFileName("mods/..") }
        assertFailsWith<IllegalArgumentException> { ContentManager.safeFileName("  ") }
        assertEquals(listOf("Cool Mod.jar", "Cool+Mod.jar", "Cool_Mod.jar"), Launcher.downloadCandidates("Cool Mod.jar"))
        assertEquals(listOf("mod.jar"), Launcher.downloadCandidates("mod.jar"))
    }

    @Test
    fun smallHelpers() {
        assertEquals(listOf("-Xss2M", "-Dname=a b", ""), splitArgs("""  -Xss2M  "-Dname=a b" "" """))
        assertEquals("my-pack-1.21.1", InstanceStore.slug("  My Pack! 1.21.1 "))
        assertEquals("", InstanceStore.slug("../.."))
        assertTrue(isValidUsername("Steve_01"))
        assertFalse(isValidUsername("ab"))
        assertFalse(isValidUsername("has space"))
        assertEquals(2048, autoMemoryMb(4096))
        assertEquals(4096, autoMemoryMb(16384))
        assertEquals(8192, autoMemoryMb(65536))
        assertEquals(1024, autoMemoryMb(2048))
        assertEquals("999", compactCount(999))
        assertEquals("1.2K", compactCount(1234))
        assertEquals("12.3M", compactCount(12_345_678))
        assertEquals("250M", compactCount(250_747_268))
    }
}
