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
| 4 | Share codes (Cloudflare Worker + KV), GitHub releases | Done |
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
- **Share codes**: turn an instance into a short code such as `K7X-9QP`; a friend enters it and
  the launcher rebuilds the instance, downloading the same mods itself.
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
worker/        Cloudflare Worker that stores share codes (see worker/README.md)
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

### Publishing a release

On GitHub, open **Actions > Build > Run workflow**, tick **Publish the APK, MSI and EXE as a GitHub
release** and run it. The files appear under **Releases** as version `v<uranium.version>` (set in
`gradle.properties`). Running it again for the same version replaces the files.

## Setup you do yourself

Three features talk to services that need something only the project owner can obtain. None of
them is required: without them the launcher still installs and runs the game with offline
profiles and Modrinth.

| Feature | What it needs | Is it secret? | Where it goes |
| --- | --- | --- | --- |
| Share codes | Address of your share server | No | Settings, or Actions variable `URANIUM_SHARE_URL` |
| Microsoft sign-in | Microsoft application (client) id | No | Settings, or Actions variable `URANIUM_MSA_CLIENT_ID` |
| CurseForge browsing | CurseForge API key | **Yes** | Settings only |

Values under **Settings** in the launcher are stored only on that computer, in `settings.json`.
Values set as Actions variables (repository **Settings > Secrets and variables > Actions**) are
compiled into the builds that CI produces, which is why only the two non-secret ones are read
there. Never put any of them in a file inside this repository.

### Share codes

Deploy the worker by following [worker/README.md](worker/README.md) and use the address it prints.

### Microsoft sign-in

The launcher needs its own application id from Microsoft. An application id only identifies the
app; it is not a password and there is no client secret (do not create one).

1. Registering an app needs a Microsoft Entra directory. A personal Microsoft account does not
   have one until you sign up for a free Azure account at <https://azure.microsoft.com/free>,
   which asks for a phone number and a payment card for identity verification.
2. Open <https://entra.microsoft.com> and go to **Entra ID > App registrations > New
   registration**.
3. Enter a name, choose **Personal accounts only** under *Supported account types*, leave the
   redirect URI empty and press **Register**.
4. In the new registration open **Authentication** and switch **Allow public client flows** on.
   The device-code sign-in the launcher uses does not work without it.
5. Copy the **Application (client) ID** from the Overview page into the launcher's Settings, or
   into the Actions variable `URANIUM_MSA_CLIENT_ID`.
6. Ask Mojang to allow the new id: fill in the form at <https://aka.ms/mce-reviewappid>. Until it
   is approved, sign-in reaches Xbox Live and then stops with a message that the application id
   was rejected. See also <https://aka.ms/AppRegInfo>.

`./gradlew :desktopApp:run --args="--cli login"` runs the whole flow in a terminal, which is handy
for checking a new id.

Accounts, including their session tokens, are stored unencrypted in `accounts.json` in the data
folder, the same way the game's own launcher profile files are. Never share that file.

### CurseForge API key

CurseForge only issues keys for third-party launchers on request: apply through the form linked
from <https://support.curseforge.com/support/solutions/articles/9000208346> and the key arrives
by email once the request is approved. CurseForge's terms say the key may not be disclosed to
anyone else, so:

- paste it only into **Settings > Services** on your own computer;
- do not commit it, post it or send it to anyone;
- CI does not build it into the published apps, because anything inside a downloadable app can be
  read out again. People who use your builds add their own key the same way, or use Modrinth,
  which needs no key.

For a private build of your own you can still compile it in by setting `CURSEFORGE_API_KEY` (or
`uranium.curseforgeApiKey` in `~/.gradle/gradle.properties`) when you run Gradle yourself.

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
./gradlew :desktopApp:run --args="--cli share <instance id>"
./gradlew :desktopApp:run --args="--cli redeem K7X-9QP"
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
- Your own share server, if you set one up.
