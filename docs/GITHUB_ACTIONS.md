# Cloud Android builds

The [Android release APK workflow](https://github.com/AlekseyVinokurov/Velora/actions/workflows/android-release.yml)
builds Velora on a disposable GitHub-hosted `ubuntu-24.04` runner. No local
Gradle invocation, Android SDK installation, Docker build or self-hosted runner
is needed. This page is the current signing-in-CI procedure; older local-only
instructions in the release guide are superseded for cloud builds.

## One-time owner setup

Use the **existing** `~/.config/velora/signing/velora-release.p12`. Never generate
a replacement key. Its certificate SHA-256 must be:

`a32f7e8239e47cc061ebabf226a88ab7dc3cf6224625f98ac6166b2966ea1842`

In your own unrecorded interactive terminal, with `gh` authenticated as a
repository administrator and an existing JDK providing `keytool`, run:

```bash
python3 scripts/configure-github-secrets.py
```

The script verifies the public certificate and exercises the existing private
key using hidden password input. It creates/restricts the GitHub environment
`velora-release` to the **branch** `main` (not tags) and uploads these environment
Secrets directly through `gh` standard input:

| GitHub Secret | Runner use |
| --- | --- |
| `VELORA_KEYSTORE_BASE64` | Decode existing keystore in runner temporary directory |
| `VELORA_KEYSTORE_PASSWORD` | Existing Gradle `RELEASE_STORE_PASSWORD` |
| `VELORA_KEY_ALIAS` | Existing Gradle `RELEASE_KEY_ALIAS` |
| `VELORA_KEY_PASSWORD` | Existing Gradle `RELEASE_KEY_PASSWORD` |
| `TELEGRAM_API_ID` | Your Telegram API ID |
| `TELEGRAM_API_HASH` | Your Telegram API hash |

Do not add duplicate repository Secrets or `RELEASE_*` Secrets. The keystore
Base64 exists only in process memory and GitHub Secrets; it is never written to
a local file. Passwords and key bytes must never enter chat, Git, logs, Issues,
artifacts or command arguments. The original key is preserved outside Git.
Keep its existing encrypted backups. Partial uploads can be replaced by rerunning
the script; it does not start a build.

Check names and scope without exposing values:

```bash
gh secret list --repo AlekseyVinokurov/Velora --env velora-release
gh api repos/AlekseyVinokurov/Velora/environments/velora-release/deployment-branch-policies
```

## Manual cloud build

Open Actions → Android release APK → Run workflow, select `main`, and choose
`universal` (ARM64 + ARMv7) or `arm64`. Alternatively:

```bash
gh workflow run android-release.yml --repo AlekseyVinokurov/Velora --ref main -f abi=universal
gh run list --repo AlekseyVinokurov/Velora --workflow android-release.yml --limit 5
```

Only manual runs of trusted `main` in this repository may sign. There are no PR,
push, tag or reusable-workflow triggers. Checkout uses the exact dispatched
commit and all 19 pinned submodules recursively. The signing environment also
rejects other branches and tags. Review changes to `main` before dispatching.

The runner installs JDK 21, SDK 36, Build Tools 36.0.0, NDK 26.3.11579264 and
CMake 3.22.1. It reports disk/RAM, frees fixed preinstalled tool directories only
when needed, and checks available disk before checkout and after SDK setup.
One build runs at a time. The existing `scripts/build-stable.sh --ci --low-memory`
limits Gradle and Ninja memory/concurrency and disables configuration caching
and build scans. No build caches are uploaded or restored.

The keystore is decoded outside Git under `$RUNNER_TEMP/velora-signing` (directory
0700, file 0600), used through the existing `RELEASE_*` environment contract, and
removed by an exit trap and an `always()` cleanup step. Runner disposal provides
the final cleanup if a machine is forcibly terminated. Secrets are injected only
into the preflight and build steps.

## Acceptance artifact and verification

The existing release verifier invokes `aapt2` and `apksigner verify --verbose
--print-certs`. A successful build must have:

- applicationId `io.github.alekseyvinokurov.velora`;
- versionCode and versionName exactly matching committed `gradle.properties`
  (currently `100000` / `0.1.0`), minSdk 24;
- one cryptographically verified RSA signer of at least 4096 bits, APK v2/v3,
  matching the permanent certificate SHA-256 above;
- no embedded keystore or private configuration files.

Only the verified `Velora-<version>-<code>.apk` and public `stable-build.json`
receipt are uploaded. The receipt contains source commit, APK SHA-256 and verified
identity. Find the download link in the run summary or its Artifacts section;
retention is seven days. GitHub authentication is required to download an artifact.
In a public repository, signed-in readers can access Actions artifacts: this is
the requested acceptance handoff, not a private distribution mechanism.

No GitHub Release, release asset, updater metadata or tag is created. Keep public
release publication as a separate step after APK acceptance; updater behavior and
music functionality are unchanged by this CI setup.

## If resources are insufficient

Use the exact failed step/log: disk guard/free bytes or `No space left on device`,
exit 137 plus kernel OOM evidence, or the Actions timeout. Resource reports run
even on failure. A nonzero compiler exit is not automatically a resource problem.
Choose `arm64` if an ARM64-only acceptance APK is appropriate. For full ABI
coverage requiring more resources, use a GitHub-hosted larger Ubuntu runner
with more RAM/disk, subject to account availability and billing, and retain the
same manual-main/environment protections. Never register the laptop as a runner.

## Публикация уже проверенного APK 0.1.1

Отдельный ручной workflow **Publish verified Velora 0.1.1**
(`publish-existing-release.yml`) публикует только разрешённый `v0.1.1 (100001)`
из run `42773454999`, artifact `13743927737`, source
`1c73e72079023fd7ed14fbdb7f0ee83ac13752a3`. Параметров для других версий нет.
Запуск — Actions → Publish verified Velora 0.1.1 → Run workflow → main.
Нужны `actions: read`, `contents: write`; используется штатный `GITHUB_TOKEN`,
без ключа подписи и Telegram Secrets.

APK/ZIP скачиваются только на GitHub-hosted Ubuntu. Проверяются успешный run,
неизменный SHA-256 архива GitHub, SHA-256 и размер APK, строгая квитанция CI,
applicationId, обе версии, source commit, minSdk и закреплённый signer pin.
Подпись APK криптографически проверена исходным build workflow; точное совпадение
байтов APK и неизменной квитанции связывает публикацию с этой проверкой.
Android SDK/NDK/Gradle и повторная сборка не используются.

Метаданные проходят существующий `scripts/release.py validate_metadata` и
production Java parser updater. Оба файла сначала загружаются в draft, размеры,
SHA-256 и URLs проверяются через API; только затем снимается draft и выставляется
stable/latest. Затем публичный Releases API и production `GitHubReleaseClient.latest`
на host JVM должны обнаружить обновление с 100000 до 100001. Это проверка
обнаружения, а не фактической установки на телефоне.

Существующие tag/release не перезаписываются и не пересоздаются. Единственное
разрешённое продолжение — уже созданный этим workflow полный неопубликованный
draft `440073389`, без изменения его notes или assets. Неизвестные draft запрещены.
При ошибке до публикации draft остаётся непубличным; автоматического удаления или
повторной загрузки поверх существующих assets нет. Результат — в summary workflow.

Первый запуск публикации #42777945153 остановился до снятия draft: GitHub выдаёт
для draft временные URLs `untagged-*`. Проверка теперь различает эти URLs и
обязательные канонические URLs публичного релиза. SHA-256 обоих загруженных
assets уже совпали; продолжение проверяет их повторно, не загружая поверх них.

Публикация завершена: [run #42778395972](https://github.com/AlekseyVinokurov/Velora/actions/runs/42778395972)
— success; [v0.1.1](https://github.com/AlekseyVinokurov/Velora/releases/tag/v0.1.1)
— stable/latest. Шесть host-тестов promotion прошли; production Java updater
проверен на публичном API. Тег совпадает с исходным commit APK. Последующий запуск
не изменяет опубликованную версию. Промежуточный run #42778304951 отменён до
мутаций: поиск draft исправлен на список releases (по тегу draft возвращает 404).

### Короткое описание обновления (с 0.1.3)

Для нового релиза подготовьте `release/notes-vX.Y.Z.md` с заголовком
`## Что нового` и 1–4 короткими пунктами `- …` о фактически выполненных изменениях.
Пример: [notes-v0.1.3.md](../release/notes-v0.1.3.md). Не вставляйте полный git
changelog, номера Actions, хеши или отчёты о сборке в пользовательские notes;
технические результаты остаются в Actions и документации.

Окно обновления показывает только этот раздел (также поддерживается английский
`## What's new`), максимум четыре пункта по 160 символов. Технические пункты,
ссылки, HTML-комментарии с квитанциями публикации и блоки кода скрываются. При
пустом/старом описании без этого раздела показывается нейтральное сообщение,
без выдуманных изменений. Это только фильтр отображения: `velora-release.json`,
проверки подписи/хеша и служебная квитанция публикации не меняются.

Лёгкая проверка форматирования без Android SDK: `python3 tests/release/run_notes.py`.
