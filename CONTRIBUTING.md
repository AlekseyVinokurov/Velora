# Contributing

Contributions should be based on the current `main` branch and focused on one problem or feature.

Issues and pull requests may be written in English, Russian or Chinese.

## Before opening an issue

- Search existing issues for duplicates.
- Reproduce the problem on the latest available build.
- Record the Android version, device model, Velora version/versionCode, STABLE or DEV, and exact steps.
- Remove account identifiers, chat content and other personal data from diagnostics.

## Pull requests

1. Fork the repository and create a focused branch from `main`.
2. Keep unrelated formatting or generated-file changes out of the patch.
3. Preserve upstream and third-party copyright and license notices.
4. Build the affected variant and test behavior on a real Android device when practical.
5. Explain the user-visible result and verification performed in the pull request.

Camera, media, gesture, plugin and native-code changes require particular care because behavior varies between vendors and Android releases. Include device coverage and fallback behavior in the pull request description.

Do not commit API credentials, signing keys, service configuration files, private endpoints, logs, APKs, crash dumps or user data.

## Build

Follow [docs/BUILDING.md](docs/BUILDING.md). Build the isolated DEV variant while developing:

```bash
./gradlew --no-daemon :TMessagesProj_AppStandalone:assembleAfatDebug
```

Stable release builds require the permanent external key and public certificate pin. Follow [RELEASING.md](docs/RELEASING.md); never put the release key in Actions or artifacts.

## Security

Do not disclose a vulnerability in a public issue. Follow [SECURITY.md](SECURITY.md) and use GitHub's private security advisory feature.
