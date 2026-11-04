# Velora

[English](README.md) · [Инструкция по сборке](docs/BUILDING.md) · [Issues](https://github.com/AlekseyVinokurov/Velora/issues)

Velora — самостоятельный открытый неофициальный клиент Telegram для Android. Разработка начинается с существующего кода [SoundGram](https://github.com/soundgram-project/SoundGram), основанного на [Telegram для Android](https://github.com/DrKLO/Telegram). Это продолжение развития клиента с сохранением архитектуры и унаследованной функциональности. Velora не связана с Telegram и не одобрена им.

В исходном коде уже присутствуют:

- Переписка, аккаунты, звонки и работа с медиа Telegram.
- Музыкальная библиотека и плеер, плейлисты, импорт треков, аудиоэффекты и DSP-эквалайзер.
- Настройки оформления, цвета Monet, наборы иконок, обои и анимации текста.
- Дополнительные настройки приватности и чатов, экспорт/импорт настроек клиента и интеграция плагинов.
- Настройки MTProto-прокси и унаследованная интеграция локального WebSocket-прокси.

Это существующие реализации, а не новые функции данного этапа. Внешние музыкальные провайдеры и дополнительные сервисы зависят от их доступности. Проверка на устройстве не выполнялась; фактические результаты проверки приведены в [docs/VELORA_STATUS.md](docs/VELORA_STATUS.md).

## Сборка

Основной модуль — `TMessagesProj_AppStandalone`: Android 7.0+ (API 24), ARM64 и ARMv7. Нужны JDK 17 или 21, Gradle 8.13 (wrapper включен), Android SDK 36, Build Tools 36.0.0, NDK 26.3.11579264 и CMake 3.22.1.

```bash
git clone --recursive https://github.com/AlekseyVinokurov/Velora.git
cd Velora
cp private.properties.example private.properties
```

В `private.properties` укажите собственные `TELEGRAM_API_ID` и `TELEGRAM_API_HASH` с [my.telegram.org](https://my.telegram.org). Задайте `ANDROID_HOME` или `sdk.dir` в `local.properties`. Локальные настройки и секреты не должны попадать в Git.

Сборка для разработки с локальным debug-ключом:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug
```

Результат: `TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/app.apk`.

Основная standalone-конфигурация проекта:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Результат: `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`. Для распространения и установки этой конфигурации настройте собственный release-ключ: готовая release-подпись не предоставляется. Подпись, дополнительные интеграции и остальные варианты описаны в [docs/BUILDING.md](docs/BUILDING.md). Успешная компиляция и создание APK в текущей среде пока не подтверждены.

## Идентификатор и совместимость

Основной applicationId — `io.github.alekseyvinokurov.velora`. Внутренние Java-пакеты и Gradle namespace сохранены для совместимости с кодом Telegram, JNI и плагинами. Это отдельное Android-приложение: оно не обновляет SoundGram или официальный Telegram, их приватные данные автоматически не переносятся.

До отдельного этапа дизайна сохранены технически корректные исходные и альтернативные иконки. Для совместимости оставлены старые ключи настроек, идентификаторы резервных копий, пути хранения и идентификаторы музыкальных каналов. Автообновления и удаленные значки поддержки требуют явной настройки сервисов Velora; чужие сервисы обновлений и пожертвований по умолчанию не используются. Собственный домен, платежные реквизиты и публикации в магазинах не заявляются.

## Структура проекта

| Путь | Назначение |
| --- | --- |
| `TMessagesProj` | Общий клиент Telegram, унаследованные расширения и ресурсы Android |
| `TMessagesProj_AppStandalone` | Основное standalone-приложение |
| `TMessagesProj_App` | Обычное приложение, в том числе release и beta |
| `TMessagesProj_AppHuawei`, `TMessagesProj_AppHockeyApp`, `TMessagesProj_AppTests` | Унаследованные альтернативные приложения и тестовый модуль |
| `buildSrc` | Плагины генерации ресурсов и локализации |
| `TMessagesProj/jni`, `TMessagesProj_Modules/media`, `TMessagesProj/lib/jlatexmath` | Нативный код и зафиксированные субмодули компонентов |

## Авторство и лицензия

Velora использует работу авторов SoundGram и Telegram для Android, включая первоначальных авторов Telegram. Сохранены copyright-заголовки, notices, лицензии компонентов и история Git. Унаследованные благодарности также включают:

- [SoundGram](https://github.com/soundgram-project/SoundGram)
- [Telegram for Android](https://github.com/DrKLO/Telegram)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Nagram](https://github.com/NextAlone/Nagram) / [Nekogram](https://github.com/Nekogram/Nekogram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)

Лицензия проекта — [GPL-2.0](LICENSE). У отдельных компонентов сохраняются собственные лицензии и уведомления; ребрендинг не меняет авторство их кода. Перед участием прочтите [CONTRIBUTING.md](CONTRIBUTING.md), перед сообщением об уязвимости — [SECURITY.md](SECURITY.md).
