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
| 2 | Microsoft/Xbox device-code login, Fabric / Quilt / Forge / NeoForge installs | Planned |
| 3 | Mod browser installs: Modrinth + CurseForge, dependency resolution | Planned |
| 4 | Share codes (Cloudflare Worker + KV) | Planned |
| 5 | FPS optimisation: performance mods, JVM flag presets, options.txt presets | Planned |
| 6 | Android game runtime (PojavLauncher approach, LGPL-3.0) | Planned |

What works today:

- Create instances for any Minecraft version, from the latest release back to the 2009 alphas.
- Play: downloads the client, libraries, assets and the exact Java runtime Mojang ships for that
  version (no Java install needed), verifies SHA-1 hashes, then starts the game in offline mode.
- Each instance has its own game folder (worlds, mods, options). Game files are shared.
- Live game console, cancel/stop, per-launcher settings (username, memory, Java path, JVM args).
- Modrinth search with project type, Minecraft version, loader and sort filters.
- Android: the same UI and installer run, but the game cannot be started until phase 6.

## Layout

```
shared/        Kotlin Multiplatform module (Android + desktop JVM): core + Compose UI
  src/commonMain    models, HTTP/downloads, Mojang metadata, installer, launch planner, Modrinth, UI
  src/jvmShared     JDK-backed code compiled into both targets (zip extraction, HTTP engine)
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

### Headless CLI

The desktop app doubles as a small CLI, useful for smoke tests:

```
./gradlew :desktopApp:run --args="--cli versions"
./gradlew :desktopApp:run --args="--cli install 1.21.1"
./gradlew :desktopApp:run --args="--cli launch 1.21.1 --dry-run"
./gradlew :desktopApp:run --args="--cli search sodium"
```

## Where files go

| Platform | Location |
| --- | --- |
| Windows | `%APPDATA%\UraniumLauncher` |
| macOS | `~/Library/Application Support/UraniumLauncher` |
| Linux | `~/.local/share/UraniumLauncher` |
| Android | the app's external files folder (`Android/data/dev.uranium.launcher/files`) |

Set `URANIUM_HOME` to use a different folder on desktop. Instances live in `instances/<id>/`, with
the game directory in `instances/<id>/minecraft`.

## Third-party services

- Mojang metadata, libraries, assets and Java runtimes (`piston-meta.mojang.com` and friends).
- [Modrinth API v2](https://docs.modrinth.com). Requests carry a descriptive `User-Agent`, as the
  API requires.
