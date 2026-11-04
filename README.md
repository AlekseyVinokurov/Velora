# Velora

[Русский](README.ru.md) · [Build instructions](docs/BUILDING.md) · [Issues](https://github.com/AlekseyVinokurov/Velora/issues)

Velora is an independent, open-source, unofficial Telegram client for Android. Development starts from the existing [SoundGram](https://github.com/soundgram-project/SoundGram) codebase, itself based on [Telegram for Android](https://github.com/DrKLO/Telegram). This is continued development of that client, preserving its architecture and inherited functionality. Velora is not affiliated with or endorsed by Telegram.

The current code includes:

- Telegram messaging, accounts, calls and media workflows.
- An integrated music library and player, playlists, track import, audio effects and a DSP equalizer.
- Appearance controls, Monet colors, icon packs, wallpapers and text animations.
- Additional privacy and chat settings, backup/import of client preferences and plugin integration.
- MTProto proxy settings and an inherited local WebSocket proxy integration.

These are existing code paths, not newly implemented or device-tested features in this rebrand. External music providers and optional services depend on their availability. See [the current validation status](docs/VELORA_STATUS.md).

## Build

The primary target is `TMessagesProj_AppStandalone`, supporting Android 7.0+ (API 24) and both ARM64 and ARMv7. Use JDK 17 or 21, Gradle 8.13 (included wrapper), Android SDK 36, Build Tools 36.0.0, NDK 26.3.11579264 and CMake 3.22.1.

```bash
git clone --recursive https://github.com/AlekseyVinokurov/Velora.git
cd Velora
cp private.properties.example private.properties
```

Enter your own `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` from [my.telegram.org](https://my.telegram.org) in `private.properties`, then set `ANDROID_HOME` or `sdk.dir` in `local.properties`. Do not commit local configuration or credentials.

For a locally signed development APK:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug
```

Output: `TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/app.apk`.

For the project's standalone distribution variant:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Output: `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`. Configure your own release key before distributing or installing this variant; the repository supplies no release signing identity. See [docs/BUILDING.md](docs/BUILDING.md) for signing, optional integrations and other variants. Compilation and APK creation have not been confirmed in the current environment.

## Identity and compatibility

The primary application ID is `io.github.alekseyvinokurov.velora`. Internal Java packages and Gradle namespaces stay unchanged for compatibility with Telegram code, JNI and plugins. This is a separate Android application: it does not update SoundGram or the official Telegram installation, and their private app data is not migrated automatically.

Original launcher and alternate icon resources are temporarily retained; a dedicated Velora icon design is a separate next step. Legacy preference keys, backup identifiers, storage paths and music-channel identifiers remain compatible. Automatic updates and remote supporter badges require explicitly configured Velora services; upstream donation and update services are not used by default. No Velora domain, donation account or store listing is claimed.

## Project layout

| Path | Purpose |
| --- | --- |
| `TMessagesProj` | Shared Telegram client, inherited extensions and Android resources |
| `TMessagesProj_AppStandalone` | Primary standalone Android application |
| `TMessagesProj_App` | Regular application, including release and beta variants |
| `TMessagesProj_AppHuawei`, `TMessagesProj_AppHockeyApp`, `TMessagesProj_AppTests` | Inherited alternative application/test targets |
| `buildSrc` | Resource and localization build plugins |
| `TMessagesProj/jni`, `TMessagesProj_Modules/media`, `TMessagesProj/lib/jlatexmath` | Native code and pinned component submodules |

## Credits and license

Velora builds on the work of SoundGram and Telegram for Android, including Telegram's original authors. Existing copyright headers, notices, component licenses and Git history are retained. Inherited contributions also credit:

- [SoundGram](https://github.com/soundgram-project/SoundGram)
- [Telegram for Android](https://github.com/DrKLO/Telegram)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Nagram](https://github.com/NextAlone/Nagram) / [Nekogram](https://github.com/Nekogram/Nekogram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)

The project is distributed under [GPL-2.0](LICENSE); individual components retain their respective licenses and notices. Rebranding does not transfer authorship of their code. See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before contributing or reporting vulnerabilities.
