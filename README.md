<div align="center">

  <img src="docs/branding/yarumo-coach/app-icon-source.png" alt="Логотип Yarumo coach" width="128" height="128">

  <h1>Yarumo coach</h1>

  <p><strong>Планируй тренировки. Следи за нагрузкой. Наблюдай прогресс.</strong></p>
  <p>Персональный дневник силовых тренировок для Android с аналитикой и AI-тренером.</p>

  <p>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/releases/latest"><img src="https://img.shields.io/github/v/release/Valerochka1337/ValerochkaGym?style=for-the-badge&amp;label=release&amp;color=80A9F9&amp;labelColor=121212" alt="Последний релиз"></a>
    <a href="#установка"><img src="https://img.shields.io/badge/Android-16%2B-80A9F9?style=for-the-badge&amp;logo=android&amp;logoColor=white&amp;labelColor=121212" alt="Android 16 и новее"></a>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/actions/workflows/android-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/Valerochka1337/ValerochkaGym/android-ci.yml?branch=main&amp;style=for-the-badge&amp;label=Android%20CI&amp;labelColor=121212" alt="Статус Android CI в main"></a>
  </p>

  <p>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/releases/latest"><strong>Скачать APK</strong></a> ·
    <a href="docs/user-guide.md">Руководство</a> ·
    <a href="https://github.com/Valerochka1337/ValerochkaGym/issues">Сообщить об ошибке</a>
  </p>

</div>

---

Yarumo coach объединяет программы, запись подходов, календарь и анализ тренировок.
Приложение хранит данные локально и синхронизирует их с аккаунтом на сервере.
Проект развивается как персональное приложение и находится в бете. Интерфейс — на русском языке.

**Навигация:** [Возможности](#возможности) · [Установка](#установка) ·
[Сборка](#сборка-из-исходников) · [Стек](#технологии) ·
[Участие](#участие-в-разработке) · [Статистика](#статистика-проекта) · [Документация](#документация)

## Возможности

| | Что можно делать |
| :--- | :--- |
| **Программы и залы** | Собирать программы из каталога упражнений с учётом оборудования выбранных залов. |
| **Активная тренировка** | Записывать вес, повторы и время, менять порядок упражнений, пользоваться таймером отдыха. |
| **Календарь** | Просматривать историю и планировать занятия. |
| **Аналитика** | Отслеживать объём, нагрузку по мышцам, динамику результатов и личные рекорды. |
| **Карточки упражнений** | Смотреть карту мышц, прошлые подходы и график прогресса; редактировать свои упражнения. |
| **AI-помощь** | Готовить будущую тренировку, общаться с Live Coach, создавать упражнения по описанию и распознавать InBody. |
| **Аккаунт и синхронизация** | Входить через Google или email, сохранять данные на сервере и управлять устройствами. |
| **Персонализация** | Выбирать акцент интерфейса и иконки, настраивать таймер и тактильный отклик. |

Подробности сценариев и настройки интеграций — в [руководстве](docs/user-guide.md).

## Установка

1. Откройте [последний релиз](https://github.com/Valerochka1337/ValerochkaGym/releases/latest).
2. В разделе **Assets** скачайте `ValerochkaGym-v<version>.apk`.
3. Установите APK на устройство с **Android 16+ (API 36)**, разрешив установку из выбранного источника.
4. Войдите через Google или зарегистрируйтесь по email.

Release-сборка проверяет обновления при запуске. Ручная проверка доступна в настройках приложения.

<details>
<summary><strong>Аккаунт, работа без сети и интеграции</strong></summary>

- Для входа и синхронизации используется backend `api.valerochkagym.tech`.
- После входа локальные изменения сохраняются в Room и отправляются при доступной сети.
  Во время активной тренировки синхронизация отложена до её завершения.
- При входе в другой аккаунт локальные данные предыдущего пользователя и очередь отправки
  удаляются с устройства. Уже синхронизированная история остаётся в прежнем аккаунте.
- AI-функциям нужны сеть и соответствующая настройка
  backend или собственного API — в зависимости от сценария.

</details>

## Сборка из исходников

Нужны **JDK 21**, **Android SDK Platform 37** и доступ к сети для загрузки зависимостей.
Для запуска нужен эмулятор или устройство с API 36+. Можно использовать Android Studio
с поддержкой версии AGP из [каталога зависимостей](gradle/libs.versions.toml).

```bash
git clone https://github.com/Valerochka1337/ValerochkaGym.git
cd ValerochkaGym
```

Откройте проект в Android Studio и задайте путь к Android SDK. При сборке из терминала
укажите `sdk.dir` в локальном `local.properties` или настройте `ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
Gradle Wrapper включён в репозиторий. Для компиляции задан toolchain JDK 17,
для unit-тестов — JDK 21; загрузка toolchain настроена через Foojay.

<details>
<summary><strong>Настройка сервисов и release-сборки</strong></summary>

В этом репозитории находится Android-клиент; исходники backend сюда не входят.
Адрес сервера задан в
[`BackendApi.kt`](app/src/main/java/com/valerochka1337/valerochkagym/data/backend/BackendApi.kt).
Сборка APK сама по себе не настраивает сервер, SMTP и AI-функции.

Для собственной Google-интеграции настройте OAuth client ID и привязку package name + SHA-1
по [руководству](docs/user-guide.md#настройка-google-интеграции).
Технический application ID — `com.valerochka1337.valerochkagym`.

Release требует настроенной подписи. После настройки
[keystore](docs/release-signing.md) выполните:

```bash
./gradlew :app:assembleRelease
```

</details>

## Технологии

| Слой | Инструменты |
| :--- | :--- |
| Интерфейс | Kotlin, Jetpack Compose, Material 3 Expressive, Material You |
| Состояние и логика | ViewModel, Coroutines, Flow |
| Данные | Room с рукописными миграциями, DataStore, Android Keystore |
| Сеть и фоновые задачи | Retrofit, OkHttp, Kotlin Serialization, WorkManager |
| Внедрение зависимостей | Hilt + KSP |
| Графики | Compose Canvas |
| Проверки | JUnit4, Robolectric, Compose UI tests, Spotless |

Один модуль `:app`, разделение на `ui`, `domain`, `data`, `di`, `service` и `worker`.
Версии зависимостей — в [Version Catalog](gradle/libs.versions.toml),
устройство приложения — в [ARCHITECTURE.md](ARCHITECTURE.md).

## Участие в разработке

Нашли ошибку или придумали улучшение? Создайте
[issue](https://github.com/Valerochka1337/ValerochkaGym/issues).
Для ошибки укажите версию приложения, устройство, версию Android, шаги воспроизведения
и ожидаемый результат. Крупную доработку лучше сначала обсудить в issue.

Для pull request:

1. Прочитайте [правила проекта](AGENTS.md), а для UI — [дизайн-систему](docs/design-system.md).
2. Создайте ветку от актуального `main`: `feat/`, `fix/`, `chore/` или `docs/`.
3. Внесите изменения и проверьте затронутые сценарии. Для правок кода или сборочной конфигурации выполните:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug
   ```

4. В PR опишите проблему, результат и выполненные проверки. Сообщения коммитов — на русском
   со смысловым префиксом, например `docs: обновить руководство`.

Для изменений только документации Gradle-проверки и новая версия приложения не нужны.
Правила версионирования фич и исправлений описаны в [AGENTS.md](AGENTS.md).

## Статистика проекта

<div align="center">

  <p>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/stargazers"><img src="https://img.shields.io/github/stars/Valerochka1337/ValerochkaGym?style=for-the-badge&amp;logo=github&amp;label=stars&amp;color=80A9F9&amp;labelColor=121212" alt="Количество звёзд"></a>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/forks"><img src="https://img.shields.io/github/forks/Valerochka1337/ValerochkaGym?style=for-the-badge&amp;logo=github&amp;label=forks&amp;color=B98AE7&amp;labelColor=121212" alt="Количество форков"></a>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/releases"><img src="https://img.shields.io/github/downloads/Valerochka1337/ValerochkaGym/total?style=for-the-badge&amp;label=asset%20downloads&amp;color=80A9F9&amp;labelColor=121212" alt="Скачивания всех файлов релизов"></a>
  </p>
  <p>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/issues"><img src="https://img.shields.io/github/issues/Valerochka1337/ValerochkaGym?style=flat-square&amp;label=issues&amp;color=B98AE7&amp;labelColor=121212" alt="Открытые issues"></a>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/pulls"><img src="https://img.shields.io/github/issues-pr/Valerochka1337/ValerochkaGym?style=flat-square&amp;label=pull%20requests&amp;color=80A9F9&amp;labelColor=121212" alt="Открытые pull requests"></a>
    <a href="https://github.com/Valerochka1337/ValerochkaGym/commits/main"><img src="https://img.shields.io/github/last-commit/Valerochka1337/ValerochkaGym/main?style=flat-square&amp;label=last%20commit&amp;color=B98AE7&amp;labelColor=121212" alt="Последний коммит в main"></a>
  </p>

</div>

Бейджи используют шаблоны [Shields.io](https://shields.io/badges/git-hub-repo-stars)
и обновляются с учётом кеша сервиса. Нажмите на показатель, чтобы открыть подробности на GitHub.
Счётчик скачиваний учитывает [все файлы релизов](https://shields.io/badges/git-hub-downloads-all-assets-all-releases),
включая контрольные суммы; это не число установок приложения.

## Документация

| Документ | Содержание |
| :--- | :--- |
| [Руководство](docs/user-guide.md) | Сценарии приложения, аккаунт, AI и Google OAuth |
| [Архитектура](ARCHITECTURE.md) | Слои, потоки данных, синхронизация и принятые решения |
| [Дизайн-система](docs/design-system.md) | Компоненты, цвета, анимации и хаптика |
| [Правила разработки](AGENTS.md) | Соглашения по коду, тестам, Git и версиям |
| [Подпись релизов](docs/release-signing.md) | Подготовка keystore и выпуск APK |
| [История изменений](CHANGELOG.md) | Changelog; свежие выпуски также доступны в [Releases](https://github.com/Valerochka1337/ValerochkaGym/releases) |
| [Бренд](docs/branding/yarumo-coach/README.md) | Логотип и исходники иконок |

## Лицензия

Лицензия проекта пока не указана: файла `LICENSE` в репозитории нет.
Вопросы о лицензировании можно адресовать [автору](https://github.com/Valerochka1337).
