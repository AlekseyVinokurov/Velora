# Практическая карта Velora

Срез репозитория `main` на 16 ноября 2026 года. Карта помогает найти место для изменения,
понять соседние компоненты и выбрать объём помощи Codex. Это ограниченное описание
структуры, а не аудит или подтверждение работы всех функций.
Обновлять карту следует при существенном изменении структуры или зависимостей,
а не после каждого мелкого коммита.

## На какое состояние опирается карта

Основа — [README.ru.md](../README.ru.md), более поздние записи
[VELORA_STATUS.md](VELORA_STATUS.md), тематические инструкции и точечная проверка кода.
В отслеживаемой документации готовой карты архитектуры и `docs/FEATURES*.md` не найдено.
В `gradle.properties` сейчас указаны **0.1.3 / 100003** — это версия исходников.
Журнал отдельно подтверждает успешную облачную сборку и публикацию **0.1.1**;
для подготовки 0.1.3 описаны статические/host-проверки, без подтверждения итогового CI.
Ранние заявления README «полная сборка ещё не завершена» и «оставлены исходные иконки»
устарели относительно этих записей. README также описывает прежний запрет WS Proxy:
поздняя запись разрешает ручной запуск только на время процесса.
Здесь не проверялись текущие Releases/Actions через сеть.
Проверка сценариев на **Pixel 8 / Android 17**, включая обновление поверх установленного APK,
в использованных сведениях не подтверждена. Наличие реализации ниже означает наличие кода.
Windows и iOS остаются возможными будущими направлениями; карта их не проектирует.

## Как устроено приложение

```text
Android → manifest → LaunchActivity → главные вкладки и экраны
                                      ├─ чаты → контроллеры Telegram → сеть / хранилище
                                      └─ музыка → библиотека / поиск / Волна
                                                      ↓
                                          общий MediaController
                                                      ↓
                                      плеер / MusicPlayerService / уведомления
Ресурсы и темы → оформление всех экранов
Дополнительные функции → настройки и точки вмешательства в Telegram
Updater → GitHub Releases → проверка APK → системный установщик Android
GitHub Actions → Gradle + Java/Kotlin + native-компоненты → подписанный APK
```

- **Telegram:** `TMessagesProj/src/main/java/org/telegram/` содержит интерфейс,
  управление аккаунтами, сообщениями, файлами, звонками и сетью. Это изменённая
  унаследованная база, а не отдельная неизменяемая библиотека Telegram.
- **Музыка:** `TMessagesProj/src/main/java/soundgram/music/` содержит библиотеку,
  импорт, поиск, синхронизацию плейлистов и Волну. Часть музыкального интерфейса
  и сам механизм воспроизведения находятся внутри `org/telegram/`.
- **Интерфейс:** `org/telegram/ui/` и `soundgram/ui/` внутри того же дерева Java;
  многие экраны создают элементы программно, поэтому поиск только в `res/layout/` неполон.
- **Дополнения:** пакеты `app/nimarkogram/`, `com/radolyn/ayugram/`,
  `tw/nekomimi/nekogram/` в `TMessagesProj/src/main/java/` — унаследованные настройки
  и интеграции. Старое имя пакета не означает отдельное установленное приложение.
- **Ресурсы:** `TMessagesProj/src/main/res/`; дополнительные данные —
  `TMessagesProj/src/main/assets/`. Манифест объявляет компоненты и Android-интеграции.
- **Сборка:** общий модуль `TMessagesProj`, варианты приложений `TMessagesProj_App*`,
  вспомогательный `buildSrc/`; состав задаёт `settings.gradle`.
  Наличие Huawei/HockeyApp/Standalone/Tests-модулей не означает их текущий выпуск.
- **Native и зависимости:** `TMessagesProj/jni/` и `TMessagesProj_Modules/media/`.
  По `.gitmodules` закреплены 19 субмодулей: мультимедийные кодеки и обработка изображения,
  звонки, TLS, анимации, формулы и прочие унаследованные движки, включая TDLib/WASM/wallet.
  Это назначение зависимостей, а не утверждение, что каждая используется в каждом APK.
  Их содержимое для этой карты не обходилось.

## Хочу изменить → где искать

Пути ниже относительны корню. Для компактности таблицы введены точные префиксы:
**J** = `TMessagesProj/src/main/java/`, **R** = `TMessagesProj/src/main/res/`.
Запись `J/org/telegram/...` означает путь с заменой `J/` на этот префикс.

| Хочу изменить | Где искать | За что отвечает | Что может затронуть |
| --- | --- | --- | --- |
| Основную и круглую иконки | `R/mipmap-anydpi-v26/velora_launcher.xml`, `R/mipmap-anydpi-v26/velora_launcher_round.xml`; `R/mipmap-mdpi/velora_launcher.png` и плотностные варианты ниже | Иконка launcher для новых и старых Android | Маски launcher, список приложений, ярлыки |
| Adaptive / тематическую monochrome | `R/drawable/velora_icon_foreground.xml`, `R/drawable/velora_icon_background.xml`, `R/drawable/velora_icon_monochrome.xml`; `R/mipmap-anydpi-v33/velora_launcher.xml` и `velora_launcher_round.xml` в том же каталоге | Слои цветной и системной тематической иконки | Обрезка формы, светлая/тёмная системная тема |
| Выбор иконок и их применение | `J/org/telegram/ui/LauncherIconController.java`; `TMessagesProj/src/main/AndroidManifest.xml` | Launcher aliases, сохранённый выбор, общий выбор smallIcon | Запуск приложения, ярлыки и обновление иконки после установки |
| Значки уведомлений | `R/drawable/velora_notification.xml`, `R/drawable/notification.xml`; `R/drawable-mdpi/player.webp` и варианты плотности | Velora smallIcon, классический общий значок, классический плеер | Шторка и строка состояния; отдельные значки сервисов имеют своих потребителей |
| Название, тексты, переводы | `R/values/strings.xml`, `R/values-ru/strings.xml`, другие `values-*`; ключ `AppName` | Базовые строки и локализации | Подписи, форматирование чисел/имён, доступность; package ID меняется отдельно |
| Темы и цвета | `R/values/colors.xml`, `R/values/styles.xml`; `J/org/telegram/ui/ActionBar/Theme.java`; `J/soundgram/ui/SoundGramDesignTokens.java`, `J/soundgram/ui/SoundGramStyleManager.java` | XML-цвета, динамическая тема, стиль музыки | Общие экраны, контраст и ночная тема; одного colors.xml недостаточно |
| Навигацию и главные экраны | `J/org/telegram/ui/LaunchActivity.java`, `J/org/telegram/ui/MainTabsActivity.java`, `J/org/telegram/ui/DialogsActivity.java`; `J/app/nimarkogram/messenger/preferences/BottomTabsPreferencesActivity.java` | Вход в приложение, вкладки, список чатов, настройка вкладок | Возврат назад, открытие чата/музыки, переходы из внешних intents |
| Библиотеку и плейлисты | `J/soundgram/music/ChannelMusicFragment.java`, `J/soundgram/music/PublicLibraryManager.java`, `J/soundgram/music/PlaylistSyncManager.java` | Экран библиотеки, публичная библиотека, обмен плейлистами через канал | Файлы и сообщения аккаунта, обложки и локальные данные |
| Плеер, очередь, фон | `J/org/telegram/ui/Components/AudioPlayerAlert.java`, `J/org/telegram/messenger/MediaController.java`, `J/org/telegram/messenger/MusicPlayerService.java`, `J/org/telegram/messenger/MusicPlayerReceiver.java` | Панель плеера, состояние/очередь, media session и управление из уведомления | Другое Telegram-аудио, audio focus, фон и кнопки гарнитуры |
| Поиск и сетевые источники | `J/soundgram/music/MusicWebSearcher.java`, `J/soundgram/music/MusicNetworkHelper.java` | Поиск/разрешение треков и общая политика музыкальной сети | Внешние сайты/API, прокси и потоковое воспроизведение |
| Импорт, загрузки и кэш | `J/soundgram/music/MusicImporter.java`, `J/soundgram/music/MusicImportService.java`, `J/soundgram/music/ChannelMusicFragment.java`; `J/org/telegram/messenger/FileLoader.java`, `J/org/telegram/ui/CacheControlActivity.java` | Импорт, операции библиотеки, Telegram-файлы и экран очистки | Место на диске, доступность треков офлайн и общие вложения |
| Рекомендации / «Мою Волну» | `J/soundgram/music/SmartRadioEngine.java`; `J/soundgram/music/wave/` — `WaveQueueManager.java`, `SeedSelector.java`, `InnerTubeClient.java`, `WaveHistoryDao.java` | Сессия рекомендаций, подбор исходных треков, пополнение очереди, история | Внешние источники, дедупликация, переключения и продолжение музыки |
| Сообщения и чаты | `J/org/telegram/ui/ChatActivity.java`, `J/org/telegram/messenger/MessagesController.java`, `J/org/telegram/messenger/MessagesStorage.java`, `J/org/telegram/tgnet/ConnectionsManager.java` | Экран чата, события/состояние, локальное хранение, Telegram-соединение | Аккаунты, отправка/получение, история и зависимая музыкальная библиотека |
| Звонки и Telegram-уведомления | `J/org/telegram/messenger/voip/VoIPService.java`, `J/org/telegram/messenger/NotificationsController.java`; `J/org/telegram/ui/NotificationsSettingsActivity.java` | Жизненный цикл звонка, построение уведомлений и их настройки | Микрофон, audio focus, фоновые ограничения, переход в нужный чат |
| «Призрака» и отправку статуса | `J/app/nimarkogram/messenger/preferences/GhostPreferencesActivity.java`; `J/com/radolyn/ayugram/utils/AyuGhostPreferences.java`, `J/com/radolyn/ayugram/utils/AyuGhostUtils.java` | Выбор режима, чтение/статус через Telegram-запросы | Серверные отметки прочтения/online; это не только косметика |
| Историю статусов, изменений и удалений | `J/app/nimarkogram/messenger/utils/LastSeenTracker.java`; `J/com/radolyn/ayugram/database/`; `J/com/radolyn/ayugram/proprietary/AyuHistoryHook.java`, `J/com/radolyn/ayugram/ui/AyuMessageHistory.java` | Точки поиска наблюдений last seen, локального хранения и показа истории сообщений | Сопоставление аккаунта/чата/сообщения и схема данных; детали last seen не исследованы |
| Настройки приложения | `J/app/nimarkogram/messenger/preferences/MainPreferencesActivity.java` и соседние экраны; `J/app/nimarkogram/messenger/NimarkoConfig.java`, `J/soundgram/music/SoundGramConfig.java`; `J/org/telegram/ui/SettingsActivity.java` | Дополнительные, музыкальные и стандартные настройки | Сохранённые ключи, значения по умолчанию, поведение после перезапуска |
| Встроенные обновления | `J/app/nimarkogram/messenger/updater/` — `NimarkoUpdater.java`, `GitHubReleaseClient.java`, `VeloraApkSignature.java`, `NimarkoUpdaterSheet.java` | Проверка релиза, загрузка/проверка APK и интерфейс установки | Совместимость обновлений, подпись и временный кэш APK |
| Версии и подпись | `gradle.properties`, `telegram-base.properties`, `gradle/velora-release.gradle`, `release/`; `TMessagesProj_App/build.gradle` | Версия Velora отдельно от версии Telegram-базы, параметры APK и постоянный pin сертификата | Возможность обновить установленный STABLE с сохранением данных |
| Облачную сборку и публикацию | `.github/workflows/android-release.yml`, `.github/workflows/publish-existing-release.yml`; `scripts/build-stable.sh`, `scripts/release.py`, `scripts/publish-existing-release.py` | Сборка APK на GitHub-hosted runner и отдельная публикация готового артефакта | Toolchain, ABI, проверки и release assets; публикация не равна сборке |

## Связи, о которых полезно помнить

**Экран → состояние → Android.** `LaunchActivity` служит входом и контейнером;
`MainTabsActivity` создаёт в том числе `DialogsActivity` и `ChannelMusicFragment`.
Музыкальный экран и `AudioPlayerAlert` обращаются к общему `MediaController`.
Он управляет очередью/воспроизведением и запускает `MusicPlayerService`;
сервис связывает воспроизведение с системным управлением и уведомлением.
Правка панели плеера и правка механизма проигрывания — разные по риску задачи.
Изменение общего контроллера требует проверки также голосовых сообщений и другого аудио.

**Музыка ↔ Telegram.** Музыкальные операции используют `MessageObject`, аккаунт,
`MessagesController` и `FileLoader`; `PlaylistSyncManager` отправляет/удаляет сообщения
плейлистов в канале и загружает обложки. В библиотеке обнаружен `music_cache_v3.dat`.
Нельзя считать музыкальный кэш, скачанный аудиофайл и Telegram-хранилище одной сущностью.
Изменение идентификаторов сообщений, текущего аккаунта или формата плейлиста способно
нарушить библиотеку и синхронизацию, даже если обычный чат продолжает открываться.

**Сеть и Волна.** `ChannelMusicFragment` вызывает поиск и `SmartRadioEngine`;
результаты Волны передаются в очередь `MediaController`, последующие треки добавляются туда же.
`SmartRadioEngine` создаёт `WaveQueueManager`; тот использует InnerTube, подбор seed-треков
и локальную историю. В поиске найдены Hitmos/Hitmoz и Deezer; в Волне — YouTube Music/InnerTube.
Это найденные интеграции, не гарантия доступности или стабильности внешних сервисов.
`MusicNetworkHelper` устанавливает глобальные Java `ProxySelector`/`Authenticator`,
учитывает настройки Telegram-прокси; правила различаются для хостов и типов прокси.
Поэтому замена «всего одного URL» или сетевого helper может повлиять на несколько путей.
Отдельный `soundgram/wsproxy/` описан в [WS_PROXY.md](WS_PROXY.md): ручной process-only
запуск не следует путать с автоматическим запуском вместе с музыкой.

**Дополнительные функции ↔ ядро.** `AyuGhostUtils` использует Telegram-запросы чтения
и статуса. `AyuHistoryHook` объединяет локально сохранённые удаления с загружаемой
историей через Ayu-контроллер и Telegram-объекты. Экран настройки лишь включает режим;
основная логика находится глубже. Изменения могут затронуть серверные отметки и локальную БД.

**Ресурс → потребитель.** Иконку launcher задают манифест и aliases, выбор обслуживает
`LauncherIconController`; общая notification icon выбирается там же.
`MusicPlayerService` передаёт классический `player` как запасной вариант.
Темы также задаются Java-кодом, а не только XML. Строка или drawable с похожим именем
не обязательно используется нужным экраном: проверяйте ссылку на ресурс.

**Выпуск → обновление.** STABLE имеет постоянный applicationId и сертификат;
DEV с суффиксом `.dev` устанавливается отдельно и обновляется вручную.
Updater читает GitHub Releases и metadata, проверяет версию, размер, SHA-256, пакет
и криптографическую подпись APK по закреплённому pin; установку подтверждает Android.
Настройки updater вызываются из UI, автоматическая проверка ограничена возвращением
на передний план, без постоянного фонового опроса. Подробности: [UPDATES.md](UPDATES.md).
Сборки Android выполняются **только на GitHub-hosted runners через GitHub Actions**;
старые локальные команды в инструкциях не являются рабочим процессом владельца.
См. [GITHUB_ACTIONS.md](GITHUB_ACTIONS.md), [RELEASING.ru.md](RELEASING.ru.md),
[SIGNING_CERTIFICATE.md](SIGNING_CERTIFICATE.md). Новый ключ для обычного выпуска не создают.

## Что я могу менять самостоятельно

Небольшая правка удобна для самостоятельной работы, когда сохраняются имя ресурса,
его тип и все ключи хранения. Перед правкой смотрите `git diff`, после — только свой diff.

- **Текст и перевод:** меняйте значение существующей строки в `R/values/strings.xml`
  и `R/values-ru/strings.xml`; сохраняйте `%1$s`, `%d`, XML-экранирование и plural-формы.
  Ищите `R.string.<имя>` / `getString` / `LocaleController`, чтобы увидеть контекст.
  Переименование `AppName` не требует изменения applicationId или Java-пакетов.
- **Изображение:** заменяйте конкретный PNG/WebP/JPG в `R/drawable-*` с сохранением
  имени, формата, прозрачности и размеров существующего варианта; проверяйте другие плотности.
  XML в `drawable/` — описание вектора/формы, а не растровая картинка для вставки PNG внутрь.
- **Простые настройки:** безопаснее сначала использовать готовый экран приложения.
  В исходниках небольшая правка подписи или уже существующего списка вариантов возможна
  после поиска потребителя. Новый переключатель, смена preference-ключа или значения
  по умолчанию требуют проверки сохранения и применения; это уже изменение логики.
- **Цвет:** найдите реального потребителя в XML или `Theme`/музыкальном стиле;
  сравните светлую и тёмную темы. Замена одного цвета не перекрашивает весь клиент.

### Замена иконок без угадывания конфигурации

Полная инструкция — [ICONS.md](ICONS.md); исходная геометрия —
[design/velora-v-fusion.svg](design/velora-v-fusion.svg), генератор — `scripts/generate-velora-icons.py`.
Референс с фоном/свечением из `docs/design/` сам по себе не является готовым smallIcon.

| Вид | Реальные ресурсы относительно R | Требования текущего проекта |
| --- | --- | --- |
| Adaptive, API 26+ | `drawable/velora_icon_foreground.xml`, `drawable/velora_icon_background.xml`; оболочки в `mipmap-anydpi-v26/` | VectorDrawable, слой 108 × 108 dp; foreground внутри центрального безопасного круга диаметром 66 dp; фон заполняет слой, маску задаёт launcher |
| Тематическая, API 33+ | `drawable/velora_icon_monochrome.xml`; обе оболочки в `mipmap-anydpi-v33/` | Одноцветный силуэт с прозрачностью, подключён через `<monochrome>`; окраску задаёт система |
| Обычная и круглая, API 24–25 | `mipmap-mdpi/`, `mipmap-hdpi/`, `mipmap-xhdpi/`, `mipmap-xxhdpi/`, `mipmap-xxxhdpi/`: в каждом `velora_launcher.png` и `velora_launcher_round.png` | Соответственно 48, 72, 96, 144, 192 px по стороне, PNG с прозрачными углами; обновлять оба варианта во всех пяти плотностях |
| Velora в уведомлениях | `drawable/velora_notification.xml` | Отдельный VectorDrawable 24 × 24 dp: белая непрозрачная форма, прозрачный фон, без свечения/градиента/цветного квадрата |
| Классический общий / плеер | `drawable/notification.xml`; `drawable-mdpi/player.webp`, `drawable-hdpi/player.webp`, `drawable-xhdpi/player.webp`, `drawable-xxhdpi/player.webp` | Общий классический значок сейчас вектор 24 dp; плеер использует существующие растровые варианты, их размеры сохранять |

Проверять цепочку использования: `TMessagesProj/src/main/AndroidManifest.xml` →
`J/org/telegram/ui/LauncherIconController.java` → потребитель уведомления/экран выбора.
Для конкретного имени достаточно точечного поиска, например:
`rg -n 'velora_notification|velora_launcher' TMessagesProj/src/main/AndroidManifest.xml TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java`.
Не переименовывайте aliases ради картинки: они связаны с сохранённым выбором и ярлыками.
Лёгкие проверки ресурсов описаны в `tests/icons/check_resources.py`; host-проверка
controller — `tests/icons/run.py`. Это не Android-сборка и не приёмка на телефоне.
После облачной сборки отдельно проверяют launcher, системную тематическую иконку,
уведомления сообщений/плеера и переключение вариантов; готовый список есть в ICONS.md.

## Когда нужен Codex

Привлекайте Codex, если меняются очередь, crossfade, audio focus, фоновый сервис,
сетевые источники/прокси, алгоритм Волны или связь музыки с сообщениями Telegram.
Также — при изменении ядра Telegram, уведомлений/разрешений, ghost-логики, схем БД,
форматов кэша и сохранённых настроек. Для таких задач сначала нужен конкретный сценарий
и границы изменения, затем подходящая проверка; наличие класса не доказывает совместимость.
Подпись, package ID, versionCode, доверенный сертификат, metadata и механизм обновлений,
а также workflow выпуска меняются согласованно с существующими инструкциями.
Ключи и пароли остаются вне чата и отслеживаемых файлов.

## Короткий словарь

| Термин | Значение в этой карте |
| --- | --- |
| Activity | Android-компонент окна/точки входа; `LaunchActivity` размещает интерфейс клиента |
| Fragment | Часть интерфейса/экран внутри контейнера; Telegram использует собственный `BaseFragment`, имя `*Activity` не всегда означает Android Activity |
| Service | Компонент для работы без открытого экрана, например системного сопровождения плеера; Android ограничивает его жизненный цикл |
| resources | Ресурсы `res/`, доступные коду как `R.string`, `R.drawable`, `R.mipmap`; Android выбирает вариант по конфигурации |
| manifest | XML-декларация компонентов, разрешений и точек входа приложения |
| JNI | Граница между Java/Kotlin и native-кодом C/C++; важна для мультимедиа и сетевых движков |
| Gradle | Система сборки, объединяющая код, ресурсы, зависимости и варианты APK |
| module | Часть сборки, подключённая в `settings.gradle`; не обязательно отдельное приложение |
| submodule | Вложенный Git-репозиторий с закреплённым коммитом; не то же самое, что Gradle module |
| dependency | Внешняя или внутренняя библиотека, которой пользуется компонент |

## Не выяснено

- Не подтверждены актуальный публичный релиз и результат CI для 0.1.3 за пределами
  локального журнала; не выполнена приёмка Pixel 8, фонового воспроизведения и обновления A→B.
- Не проверены доступность музыкальных провайдеров и качество рекомендаций; найденные
  URL и InnerTube могут зависеть от внешних изменений и сети пользователя.
- Не составлена полная схема музыкальных загрузок/кэшей, форматов и миграций:
  указаны точки входа, но нельзя удалять или менять хранилище на основании этой карты.
- Для истории last seen найдены пути, но цепочка записи/чтения и отличие от отправки
  своего online-статуса подробно не исследованы. Полнота сохранения edits/deletes не проверена.
- Не установлено участие каждого из 19 субмодулей в каждом варианте APK;
  native-код, полный Telegram upstream и графы вызовов не исследовались.
- Потребители всех цветов, вариантов иконок и дополнительных настроек не перечислены:
  перед конкретной правкой нужен точечный поиск по выбранному ключу или ресурсу.
