**English** · [Русский](README.ru.md)

# Velora

Velora is an independent Android Telegram client focused on music, performance, usability and a clean user experience.

This is a development focus, not a measured claim of greater speed, security or reliability. Velora is unofficial and is not affiliated with or endorsed by Telegram.

## Existing features

The inherited client includes Telegram messaging, multiple accounts, calls and media; a music library and player, playlists, imports, audio effects and a DSP equalizer; themes, Monet colors, icon packs, wallpapers and text animations; additional chat/privacy preferences, configuration backups and plugin integration. Existing proxy integrations remain in the code. External providers and services may be unavailable.

The release infrastructure now implements a separate DEV identity, permanent-key validation and a GitHub Releases updater. **The permanent release certificate is pinned; a complete Velora APK build and device upgrade validation are still pending.** See [current results and blockers](docs/VELORA_STATUS.md). Original icon resources remain until a dedicated design is ready.

WS Proxy is disabled in this release, including legacy saved auto-start settings, boot restoration and the detector. Native/JNI sources remain for a later explicit opt-in. Telegram proxy settings and the user’s VPN are not changed automatically. Music HTTPS uses Android certificate and hostname validation.

## Compatibility and testing

**Primary testing device: Google Pixel 8, Android 17.** Device testing has not been performed in this preparation session. Other devices have not been tested.

The primary build currently declares Android 7.0+ (API 24), ARM64 and ARMv7. A declared minimum SDK is not evidence of successful testing on all those devices. Please help test other devices and report problems through [GitHub Issues](https://github.com/AlekseyVinokurov/Velora/issues).

## Installation

When a stable signed APK is available, obtain it from [Velora Releases](https://github.com/AlekseyVinokurov/Velora/releases), verify its checksum/certificate, and follow Android's installation confirmation. The actual trusted certificate, once created, is documented in [SIGNING_CERTIFICATE.md](docs/SIGNING_CERTIFICATE.md). Do not install a purported stable APK while that identity is still unconfigured.

- STABLE: `io.github.alekseyvinokurov.velora`, signed with the single permanent release key.
- DEV: `io.github.alekseyvinokurov.velora.dev`, signed with a separate local Android debug key.

DEV can coexist with STABLE and does not replace it. Private app data is separate; accounts are not copied automatically from Telegram, SoundGram or DEV. Never uninstall STABLE as an update procedure: uninstalling removes its private data.

## Updates inside the app

In configured signed STABLE builds, open **Check for updates** in Velora settings. The updater checks the public Releases API of `AlekseyVinokurov/Velora`, ignores drafts/prereleases and requires a greater `versionCode`. Automatic checks run on foreground resume at most once per 24 hours, when enabled; there is no continuous background polling.

The app displays the version/release notes and downloads the APK with progress and cancellation. It checks metadata, size, SHA-256, package/version and the pinned certificate using the Android APK signature verifier. Unknown signers, mismatched or older APKs are rejected. Installation is handed to Android's system package installer, with the necessary permission and user confirmation. There is no silent install, uninstall or data-reset operation.

Keeping the same package and key with increasing version codes is the basis for retaining accounts, settings and local data during an update; **the two-build device test is not yet confirmed**. DEV updates are manual. A missing release, offline connection or GitHub API rate limit must not be treated as a valid update. See [the protocol](docs/UPDATES.md).

## Bugs and suggestions

Use [GitHub Issues](https://github.com/AlekseyVinokurov/Velora/issues) and include:

- Device model and Android version.
- Velora version, preferably including `versionCode`, and STABLE/DEV.
- What happened, what you expected, and exact reproduction steps.

If **Issues** are disabled, the repository owner can enable them in GitHub settings. Remove personal data, tokens, phone numbers, account identifiers and message content from public logs or screenshots. Report vulnerabilities according to [SECURITY.md](SECURITY.md), rather than posting secrets publicly.

## Build on GitHub Actions

Use the [Android release APK workflow](https://github.com/AlekseyVinokurov/Velora/actions/workflows/android-release.yml).
It runs manually on trusted `main`, checks out all 19 submodules, installs the
pinned Android toolchain on a GitHub-hosted Ubuntu runner, builds through the
existing release script, and verifies the resulting APK before saving an artifact.
No local Android build or SDK installation is required.

The owner first uploads the **existing** permanent key and Telegram credentials
through `python3 scripts/configure-github-secrets.py` in their own terminal.
Secrets stay in the `velora-release` environment, restricted to branch `main`.
Never generate a replacement key or send passwords through chat.

```bash
gh workflow run android-release.yml --repo AlekseyVinokurov/Velora --ref main -f abi=universal
```

See [cloud setup and acceptance artifacts](docs/GITHUB_ACTIONS.md) and
[current results and blockers](docs/VELORA_STATUS.md). No GitHub Release or
public release asset is created automatically. Device acceptance and subsequent
release publication remain separate steps.

## License and Credits

Velora continues independent development using the open-source [SoundGram](https://github.com/soundgram-project/SoundGram) codebase, based on [Telegram for Android](https://github.com/DrKLO/Telegram). Existing copyright, license notices and Git history are retained. Rebranding does not transfer authorship of inherited code.

Credits: [SoundGram](https://github.com/soundgram-project/SoundGram), [Telegram for Android](https://github.com/DrKLO/Telegram) and its original authors, [AyuGram](https://github.com/AyuGram/AyuGram4A), [Cherrygram](https://github.com/arsLan4k1390/Cherrygram), [Nagram](https://github.com/NextAlone/Nagram) / [Nekogram](https://github.com/Nekogram/Nekogram), and [exteraGram](https://github.com/exteraSquad/exteraGram).

The project uses [GPL-2.0](LICENSE); components retain their own licenses and notices. Keep corresponding source, pinned component revisions and notices available with distributed builds. See [CONTRIBUTING.md](CONTRIBUTING.md).

The updater also uses Android’s [apksig](https://android.googlesource.com/platform/tools/apksig/) verifier; its Apache-2.0 license is retained in `TMessagesProj/src/main/assets/third_party_licenses/apksig-LICENSE.txt`.
