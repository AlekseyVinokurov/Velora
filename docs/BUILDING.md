# Building Velora

This guide describes the existing Gradle targets, not a guarantee of a successful build on an unprepared machine. See [VELORA_STATUS.md](VELORA_STATUS.md) for the actual checks performed.

## Toolchain

| Component | Primary standalone / regular target |
| --- | --- |
| JDK | 17 or 21; this workspace has OpenJDK 21 |
| Gradle | 8.13 through `./gradlew` |
| Android Gradle Plugin | 8.13.2, pinned in the existing project |
| Android SDK / Build Tools | 36 / 36.0.0 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 |
| Device | Android 7.0+ (API 24), ARM64 or ARMv7 |

The Huawei, HockeyApp and test modules also retain an NDK 27.2.12479018 declaration in `defaultConfig`; prepare that toolchain when building those targets. No dependency versions or submodule revisions are changed by this rebrand. The current Gradle scripts do not declare a Chaquopy build plugin; the inherited blanket requirement to install Python 3.11 for every APK build has been removed.

Use your local `JAVA_HOME`. No other developer's JDK path belongs in `gradle.properties`. Existing Gradle JVM settings reserve up to 8 GiB of heap; adjust them locally if the WSL instance has less available memory.

## Source and submodules

For a fresh checkout:

```bash
git clone --recursive https://github.com/AlekseyVinokurov/Velora.git
cd Velora
```

For a clean checkout whose submodules have not yet been initialized:

```bash
git submodule update --init --recursive
```

Do not run this to overwrite modified submodules. Preserve and inspect existing local changes first. Never use `--remote` for this build: use the recorded commits.

The rebrand branch was created as a separate worktree at `/home/agat/code/velora-rebrand` because `/home/agat/code/velora/TMessagesProj/jni/wallet-engine` already contained staged deletions. Submodule checkouts are not automatically populated in a new worktree. The original submodules were neither moved nor updated. An isolated verification temporarily referenced their existing directories, then removed those links; the new worktree still needs its own initialized submodules for a normal build. A fresh recursive clone of the local repository is another option:

```bash
git clone --recurse-submodules --branch feature/velora-rebrand /home/agat/code/velora /home/agat/code/velora-build
```

This creates a separate checkout of recorded commits without modifying the original dirty submodule. This local branch has not been pushed to GitHub.

Native CMake targets reference existing archives and generated wrappers under `TMessagesProj/jni/prebuild`. Keep those matching files with the source; do not upgrade their submodules independently. Pine is supplied by the existing JNI files, not by a Pine submodule listed in `.gitmodules`.

## Local configuration

```bash
cp private.properties.example private.properties
```

Set your own `TELEGRAM_API_ID` (positive integer) and `TELEGRAM_API_HASH` (32 hexadecimal characters) from [my.telegram.org](https://my.telegram.org). Without validly formatted values Gradle stops with a configuration error; it no longer substitutes inherited credentials. Successful formatting does not verify that Telegram issued the pair.

Environment variables take precedence. Telegram build fields also retain compatibility with Gradle project properties and `local.properties`; prefer the ignored `private.properties` file or environment variables. Never pass secrets in public logs, commit them or send them in a pull request.

Set `ANDROID_HOME` to your actual Android SDK directory, or add `sdk.dir` to the ignored `local.properties`. If Android command-line tools are already installed, prepare exactly these components:

```bash
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;26.3.11579264' 'cmake;3.22.1'
```

This command can download substantial files; it was not run during the rebrand. SDK installation and license acceptance remain prerequisites in an environment without an SDK. Dependency resolution also needs network access on the first build.

## APK variants and signing

For development, Gradle uses its local Android debug key, not any inherited release keystore:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug
```

Output: `TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/app.apk`.

For the primary standalone distribution variant:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Output: `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`. Configure all four signing values in `private.properties` or the environment with your own keystore:

```properties
RELEASE_STORE_FILE=/absolute/path/to/your/velora-release.jks
RELEASE_STORE_PASSWORD=
RELEASE_KEY_ALIAS=
RELEASE_KEY_PASSWORD=
```

Fill the blank fields locally. No release key or password is supplied. Without a complete configuration pointing to an existing key, no release signing identity is configured; a generated unsigned APK is not installable. Debug and standalone currently share the primary applicationId, so their different signing keys prevent replacing one with the other without uninstalling it (and losing its private data). Keep your release key stable for future upgrades.

The inherited regular target remains available:

```bash
./gradlew :TMessagesProj_App:assembleAfatRelease
```

Output: `TMessagesProj_App/build/outputs/apk/afat/release/app.apk`. It has the same release signing requirements. Existing `-PngArm64Only` narrows the shared library ABI list; application flavors retain their declarations. Do not describe the resulting APK as verified ARM64-only without checking its contents.

## Application identity

| Target / build type | applicationId |
| --- | --- |
| Standalone debug / standalone; regular release; Huawei | `io.github.alekseyvinokurov.velora` |
| Regular debug; HockeyApp variants | `io.github.alekseyvinokurov.velora.beta` |
| Regular standalone; test debug / standalone | `io.github.alekseyvinokurov.velora.web` |

Core namespace stays `org.telegram.messenger`; app namespaces remain `.regular`, `.web`, `.huawei` and `.test` under it. FileProvider and notification/call providers already derive authorities from `${applicationId}` and runtime package names. The Android account authenticator, contact MIME types and sync adapter now also use the final applicationId, including variant suffixes, to avoid claiming another client's Android accounts and contact actions. Telegram account/session logic is unchanged. Variant resources use the [Android Gradle Plugin resource API](https://developer.android.com/reference/tools/gradle-api/8.12/com/android/build/api/variant/HasAndroidResources).

Existing Telegram/TON deep links, JNI class names, preference keys, backup magic, external storage paths and music-channel discovery identifiers are preserved. Existing SoundGram backups remain readable. A new applicationId creates separate app storage and does not migrate login sessions automatically.

## Optional services

- Firebase/Google service configuration must belong to the final applicationId and your signing certificate. Supply your own ignored `google-services.json` in the application module if enabling it. Without it, do not expect Firebase push or other configured Google integrations to work. Regular and standalone apply the Google plugin conditionally; Huawei keeps its existing Google/Huawei plugin setup and requires matching service configuration.
- Use your own Huawei `agconnect-services.json` and integration parameters for Huawei builds. Google Maps, OAuth and other optional keys must be yours too.
- `VELORA_UPDATE_CHANNEL` is an optional Telegram channel username hosting the inherited `#update` metadata format. Blank disables remote metadata requests; the UI explains that updates are unconfigured. No channel or update endpoint is invented. APK signature, hash and package checks remain intact.
- `VELORA_BADGES_URL` is an optional owned URL for the inherited `id:badge` mapping. Blank disables that remote fetch. Velora offers no configured paid badges or donation service.
- Existing optional `NIMARKO_*` property names remain in the template for compatibility; this rebrand does not provision backend services or assert that all inherited placeholder settings are wired up.
- Translation scripts under `tools/` and `scripts/` are inherited maintenance utilities, not Gradle build prerequisites. Review their paths, translation sources and attribution before regenerating resources.

## Current blockers

The recorded offline build stops resolving `org.gradle.kotlin.kotlin-dsl:5.2.0` in `buildSrc`; that plugin is absent from the local cache. Android compilation has not started. The inspected environment also has no configured/discovered Android SDK/NDK, no Telegram API pair and no release signing material. After preparing the checkout and local prerequisites, rerun the commands above without `--offline`. Do not interpret Java/XML syntax checks as a successful Android build or a device test.
