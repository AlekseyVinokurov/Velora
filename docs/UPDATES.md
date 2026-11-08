# Velora stable update protocol v1 / Протокол обновлений

This document describes the implemented contract. Full Velora build/device validation is still pending; see [VELORA_STATUS.md](VELORA_STATUS.md), [release guide EN](RELEASING.md) / [RU](RELEASING.ru.md).

## Source and format / Источник и формат

The only discovery endpoint is `https://api.github.com/repos/AlekseyVinokurov/Velora/releases/latest`. Requests are public, without a token. A response must be a non-draft, non-prerelease release tagged `v<versionName>`. The app reads the unique uploaded `velora-release.json` asset from that same release. APK and metadata initial URLs must exactly match this repository/tag/asset path. HTTPS redirects are restricted to GitHub's named release asset hosts; no arbitrary URL, Telegram channel, script or executable update plugin is accepted. The API behavior is documented by [GitHub](https://docs.github.com/en/rest/releases/releases).

Formal metadata: [release/metadata.schema.json](../release/metadata.schema.json). `scripts/release.py prepare` generates it from verified APK bytes. There is no fabricated release JSON with a test signer in the repository. Fields:

| Field | Meaning / Значение |
| --- | --- |
| `schemaVersion` | Exactly `1` |
| `repository`, `channel`, `applicationId` | Exactly `AlekseyVinokurov/Velora`, `stable`, `io.github.alekseyvinokurov.velora` |
| `versionName`, `versionCode` | Fixed semantic name; positive integer code up to 2,100,000,000 |
| `tag`, `sourceCommit`, `minSdk` | `v` + name; source commit; APK minimum API (at least 24) |
| `apk.name` | Exactly `Velora-<versionName>-<versionCode>.apk` |
| `apk.size` | Exact positive byte count, at most 512 MiB |
| `apk.sha256` | Lowercase SHA-256 of the entire APK |
| `apk.certificateSha256` | Lowercase SHA-256 of the single actual X.509 signing certificate |

Unknown/missing fields, duplicate JSON keys, non-integer codes, invalid names/digests, mismatched assets and oversized responses are rejected. The metadata certificate must equal the independently versioned pin compiled into the installed stable app; metadata cannot introduce or rotate a trusted key. If GitHub supplies an asset digest, it must also match. Release notes come from the release body and use the existing local Markdown renderer.

## Verification and installation / Проверка и установка

1. Only the stable package with a configured trusted certificate can check/install; DEV is manual.
2. Check when opening the update screen, and at foreground resume no more than once per 24 hours when automatic checks are enabled. No background alarm or continuous polling. Concurrent checks/transfers do not replace active metadata.
3. Offer only `remote.versionCode > installed.versionCode` and a compatible minSdk. Drafts/prereleases never become stable candidates.
4. Download into private app cache under `velora-updates/<versionCode>/`, with progress, cancellation, network limits and persisted resume parameters. Android may stop the process; reopen/retry/resume rather than assuming guaranteed background downloading.
5. Require the exact expected size and SHA-256. Read Android package identity and require the stable package, exact versionName/versionCode/minSdk, and a version greater than the currently installed one.
6. Require the installed signer and the incoming APK's **cryptographically verified** single signer to equal the compiled pin. Verification uses Google's small [apksig / ApkVerifier](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkVerifier.java) library with APK v2/v3 verification, not just certificate parsing. Signature rotation is not part of v1 of this process.
7. Repeat hash, identity and signature checks before installation. Hand the APK through the existing `${applicationId}.provider` FileProvider to Android's system package installer (`ACTION_VIEW`, APK MIME type, temporary read grant, and an explicit system-only handler). `REQUEST_INSTALL_PACKAGES` is declared; Android 8+ checks whether this app may request installs. If necessary, the existing system permission dialog is shown; the user retries Install after granting permission.
8. Android decides whether to accept the update and requires user confirmation. There is no root/shell installer, silent installation, downgrade flag, uninstall or data-clear operation. The narrow FileProvider cache entry is separate from account/settings storage.

Ошибочная подпись, пакет, версия или хеш приводят к отказу, а не к установке. Удаляться может только некорректный/отмененный updater-cache APK; аккаунты, настройки и пользовательские данные не очищаются. При нормальном обновлении одинаковые пакет и ключ позволяют Android сохранить данные, но фактическая проверка A→B на устройстве пока не проведена.

## Failure behavior / Ошибки

- HTTP 404 from the discovery endpoint: no public stable release; no download/install.
- Offline, timeout, HTTP 403/429 rate limit or server error: check failed; offer retry, not a false “up to date” result. Automatic attempts are throttled after failures too.
- Missing/invalid metadata or incomplete assets: reject that release. Do not fall back to a foreign channel or guess a checksum.
- Download interruption: show failure/paused state; retain only bounded private updater cache for retry/resume. Cancel is available in the screen even if Android notification permission is denied.
- Hash, certificate, package or version mismatch: no install. A different certificate requires investigation/restoring the correct key, not uninstalling the stable app.

The old `NimarkoUpdater`/sheet class names remain as internal compatibility names. Active discovery/downloads no longer use the inherited Telegram channel, and the legacy `UpdateHelper` entry point is explicitly disabled. MTProto, music playback, network engine and account/session data were not changed.
