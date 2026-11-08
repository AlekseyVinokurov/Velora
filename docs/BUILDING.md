**English** · [Русский](BUILDING.ru.md)

# Building Velora

Actual verification results are in [VELORA_STATUS.md](VELORA_STATUS.md). A Gradle invocation or a host policy test is not a successful Android app build.

## Environment

| Component | Primary standalone build |
| --- | --- |
| JDK | 21 recommended; project supports 17 |
| Gradle / Android Gradle Plugin | 8.13 wrapper / 8.13.2 |
| SDK / Build Tools | 36 / 36.0.0 |
| NDK / CMake | 26.3.11579264 / 3.22.1 |
| Device minimum / ABIs | API 24; ARM64 and ARMv7 |

Use a recursive checkout of the recorded submodule commits:

```bash
git clone --recursive https://github.com/AlekseyVinokurov/Velora.git
cd Velora
python3 scripts/setup-android.py
```

The setup uses Google's official Android repository and installs packages into ignored `.local-build/android-sdk`, then writes `sdk.dir` to ignored `local.properties`. Review SDK license prompts when running it yourself. It downloads approximately 1 GB before extraction. It does not install an emulator/system image. Set your JDK with `JAVA_HOME` if needed; no developer-specific JDK path is committed.

For a clean checkout with missing submodules, use `git submodule update --init --recursive`, never `--remote`. Do not use it to overwrite modified submodules. Existing native archives and generated wrappers under `TMessagesProj/jni/prebuild` must remain matched to the source.

Legacy Huawei/HockeyApp/test targets retain their own NDK 27.2.12479018 declaration. They are not the stable release target covered by the release scripts. No Python/Chaquopy Gradle plugin is declared for the primary APK; Python 3 is used by local tooling, not to replace Android compilation.

For interactive first-time local setup, run `python3 scripts/configure-local-release.py`. It preserves an existing key or creates the permanent key outside Git, pins its public certificate, and saves your own Telegram/signing parameters in ignored `private.properties` with mode `0600`. Password input is hidden. Back up the exact key twice and keep passwords separately.

## Local Telegram configuration

Copy the template only if the local file does not already exist:

```bash
umask 077
test -e private.properties || cp private.properties.example private.properties
```

Open `private.properties` in a local editor and set your own `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` from [my.telegram.org](https://my.telegram.org). The ID must be a positive integer and the hash 32 hexadecimal characters. The format check does not prove that Telegram issued the pair. No fallback credentials are supplied. Do not paste these values into shell commands, Issues, screenshots or Git.

Environment variables take precedence; existing Gradle project/local properties remain supported. The optional service parameters in the template do not provision any backend. Do not use `--debug`, `--info`, build scans or a configuration cache when handling release secrets.

## DEV APK

```bash
./gradlew --no-daemon :TMessagesProj_AppStandalone:assembleAfatDebug
```

Output: `TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/app.apk`.

Application ID: `io.github.alekseyvinokurov.velora.dev`. The label is **Velora DEV**, with the Android-generated local debug key. Stable updates are disabled in DEV; replace DEV manually using the same local debug key, or uninstall it only after accepting loss of its DEV-only data. It can coexist with STABLE.

## STABLE APK

Create and back up the real permanent key, then record/commit its public certificate pin as described in [RELEASING.md](RELEASING.md). Commit reviewed source before a stable build:

```bash
scripts/build-stable.sh
```

This prompts locally for the external keystore path, alias and passwords, and for Telegram parameters if no local configuration exists. Secrets are passed through the short-lived build environment, not command arguments; the script uses a one-shot daemon and disables the configuration cache. Do not run it under tracing or record its terminal session.

After the interactive local setup above, use `scripts/build-stable.sh --local` to build from the ignored local configuration without entering secrets again. The same clean-source and certificate checks apply.

On a host with about 8 GiB RAM shared with an IDE, use `scripts/build-stable.sh --local --low-memory`. It limits the Gradle heap to 3 GiB, runs one Gradle worker, compiles Kotlin in-process, and uses CMake/Ninja job pools (two native compile jobs, one link job). `--max-workers` alone does not limit Ninja. This changes build concurrency, not the application features or signing identity.

For the Pixel 8 ARM64 APK, add `--arm64-only`: `scripts/build-stable.sh --local --low-memory --arm64-only`. This applies the existing `ngArm64Only` option to both the library and standalone app ABI filters; the APK then supports ARM64 only. Omitting the option retains the hybrid ARM64/ARMv7 build. Package, version and permanent signer are the same in either mode.

The underlying task is `:TMessagesProj_AppStandalone:assembleAfatStandalone`; output is `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`. Application ID remains **`io.github.alekseyvinokurov.velora`**. Gradle refuses stable builds without the pinned external RSA-4096 key and a valid certificate originally lasting at least 30 years. The key's certificate must match `release/certificate.sha256`.

The wrapper verifies the finished APK and writes a public build receipt to ignored `.local-build/stable-build.json`. This identifies the APK digest, version, certificate and clean source commit for release preparation. No keystore is copied. A build receipt is generated only after a real APK has been verified.

## Identity, versions and integrations

`APP_VERSION_NAME` and `APP_VERSION_CODE` in `gradle.properties` are the fixed version source. Current planned first version: `0.1.0` / `100000`. No Gradle invocation increments them and no ABI suffix is added to the APK version code. Edit and commit both for a new published version; never rebuild/replace an already published tag with different assets.

Internal namespaces/Java packages stay unchanged. Debug variants use `.dev`; inherited regular/test `.web` variants are not the primary STABLE distribution. Providers, Android account type and contact MIME types derive from the final applicationId, including `.dev`; Telegram/TON deep links and JNI names are preserved. Changing the applicationId does not migrate another application's private data.

Google/Firebase and Huawei service files must be yours and match the package and certificate. Standalone/regular Google configuration is optional; without matching service JSON do not expect configured push, OAuth or other service integrations to work. Huawei retains its inherited plugin requirements. Files and API keys must not enter Git. Remote badge configuration remains optional and unrelated to releases; the old Telegram update channel is disabled.

The build is defined by pinned source/submodules, fixed versions and tools, external signing identity and explicit commands. Byte-for-byte APK reproducibility has not been demonstrated; do not claim it from this setup alone.

## Verification

```bash
python3 tests/release/run.py
python3 scripts/release.py verify TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk
```

The first command compiles production updater policy classes and runs host tests against tiny disposable fixture APKs signed by temporary random test keys. It verifies metadata rejection and actual signature/hash failures. It does not build or install Velora, and does not create the permanent key.

The second command uses SDK `aapt2` and `apksigner` to inspect a real stable APK, compare version/package/certificate with project settings, calculate SHA-256 and reject embedded signing/configuration files. See [RELEASING.md](RELEASING.md) for staging and device validation.
