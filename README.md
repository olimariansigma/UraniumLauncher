# Uranium Launcher

A Minecraft: Java Edition launcher for Windows and Android, written in Kotlin with Compose
Multiplatform. One shared module holds the launcher core and the UI; thin app modules wrap it for
each platform.

> Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

## Status

The project is built in phases. Each phase compiles, is tested and is committed on its own.

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project scaffold, vanilla install + launch on desktop, Modrinth search, CI | Done |
| 2 | Microsoft/Xbox device-code login, Fabric / Quilt / Forge / NeoForge installs | Done |
| 3 | Mod browser installs from Modrinth + CurseForge with dependency resolution, Prism import | Done |
| 4 | Share codes (Cloudflare Worker + KV) | Planned |
| 5 | FPS optimisation: performance mods, JVM flag presets, options.txt presets | Planned |
| 6 | Android game runtime (PojavLauncher approach, LGPL-3.0) | Planned |

What works today:

- **Instances** for any Minecraft version, from the latest release back to the 2009 alphas, each
  with its own game folder. Vanilla, Fabric, Quilt, Forge and NeoForge (Forge back to 1.7.10).
- **Play**: downloads the client, libraries, assets, the mod loader and the exact Java runtime
  Mojang ships for that version (no Java install needed), verifies SHA-1 hashes, then starts the game.
- **Accounts**: Microsoft sign-in through the device-code flow, plus offline profiles.
- **Browse** Modrinth and CurseForge: search and filter mods, resource packs and shaders, and
  install into an instance with one click. Required dependencies are resolved automatically.
  CurseForge files whose authors block third-party downloads are handled by opening the download
  page and picking the file up from the Downloads folder.
- **Manage** installed content per instance: enable, disable, remove.
- **Import** from Prism Launcher, MultiMC and PolyMC: the launcher searches the computer for their
  instances and copies worlds, mods and settings.
- **Android**: the same UI, downloads and mod browser run, but the game cannot be started until
  phase 6 (and Forge/NeoForge cannot be installed there until then, as their installers need Java).

## Layout

```
shared/        Kotlin Multiplatform module (Android + desktop JVM): core + Compose UI
  src/commonMain    accounts, HTTP/downloads, Mojang metadata, installers, loaders, content, UI
  src/jvmShared     JDK-backed code compiled into both targets (zip handling, HTTP engine)
  src/desktopMain   process launching, OS paths
  src/androidMain   Android storage and device info
desktopApp/    Compose Desktop entry point, Windows MSI/EXE packaging, headless CLI
androidApp/    Android application
```

## Building

Requires JDK 17 or newer (CI uses 21). The Android build also needs the Android SDK
(`ANDROID_HOME` or `local.properties`).

```
./gradlew :desktopApp:run                      # run the desktop launcher
./gradlew :desktopApp:packageMsi               # Windows installer (run on Windows)
./gradlew :desktopApp:packageExe               # Windows setup .exe (run on Windows)
./gradlew :androidApp:assembleDebug            # Android APK
./gradlew :shared:allTests                     # unit tests
```

Every push runs `.github/workflows/build.yml`, which runs the tests and uploads the APK, MSI and
EXE as workflow artifacts.

## Service credentials

Two features need credentials that cannot be committed to the repository. Each can be supplied in
three ways, checked in this order:

1. In the launcher: **Settings > Services** (stored in `settings.json`).
2. At runtime, as an environment variable.
3. At build time, as an environment variable or a property in `~/.gradle/gradle.properties`. The
   value is compiled into the app, so everyone using that build gets it.

| Feature | Environment variable | Gradle property |
| --- | --- | --- |
| CurseForge browsing | `CURSEFORGE_API_KEY` | `uranium.curseforgeApiKey` |
| Microsoft sign-in | `URANIUM_MSA_CLIENT_ID` | `uranium.msaClientId` |

For CI builds, add repository secrets with the same names as the environment variables
(Settings > Secrets and variables > Actions). Anything compiled into an app can be extracted from
it, so treat a built-in key as public and only use credentials that are meant for client apps.

### CurseForge API key

Request a key for a third-party client at <https://console.curseforge.com>. Modrinth needs no key.

### Microsoft sign-in

Microsoft sign-in needs an application id of your own:

1. Open the [Azure portal](https://portal.azure.com) and go to **Microsoft Entra ID > App
   registrations > New registration**.
2. Give it a name, choose **Personal Microsoft accounts only** as the supported account type, and
   leave the redirect URI empty.
3. In the new registration, open **Authentication** and set **Allow public client flows** to
   **Yes**. The device-code flow does not work without it.
4. Copy the **Application (client) ID** and supply it in one of the three ways above.
5. New application ids must be approved by Mojang before Minecraft services accept them. Apply at
   <https://aka.ms/mce-reviewappid>. Until then sign-in gets as far as Xbox Live and then fails
   with a message saying the application id was rejected.

`./gradlew :desktopApp:run --args="--cli login"` runs the whole flow in a terminal, which is handy
for checking a new id.

Accounts, including their session tokens, are stored unencrypted in `accounts.json` in the data
folder, the same way the game's own launcher profile files are.

### Headless CLI

The desktop app doubles as a small CLI, useful for smoke tests:

```
./gradlew :desktopApp:run --args="--cli versions"
./gradlew :desktopApp:run --args="--cli loaders neoforge 1.21.1"
./gradlew :desktopApp:run --args="--cli install 1.21.1 --loader fabric"
./gradlew :desktopApp:run --args="--cli launch 1.21.1 --loader forge --dry-run"
./gradlew :desktopApp:run --args="--cli search sodium"
./gradlew :desktopApp:run --args="--cli search jei --curseforge"
./gradlew :desktopApp:run --args="--cli scan"
```

## Where files go

| Platform | Location |
| --- | --- |
| Windows | `%APPDATA%\UraniumLauncher` |
| macOS | `~/Library/Application Support/UraniumLauncher` |
| Linux | `~/.local/share/UraniumLauncher` |
| Android | the app's external files folder (`Android/data/dev.uranium.launcher/files`) |

Set `URANIUM_HOME` to use a different folder on desktop. Instances live in `instances/<id>/`, with
the game directory in `instances/<id>/minecraft` and the list of launcher-installed content in
`instances/<id>/content.json`.

## Third-party services

- Mojang metadata, libraries, assets and Java runtimes (`piston-meta.mojang.com` and friends).
- Microsoft, Xbox Live and Minecraft services, for sign-in.
- Fabric, Quilt, Forge and NeoForge metadata and Maven repositories.
- [Modrinth API v2](https://docs.modrinth.com). Requests carry a descriptive `User-Agent`, as the
  API requires.
- [CurseForge Core API](https://docs.curseforge.com/rest-api/).
