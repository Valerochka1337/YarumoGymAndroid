# Трекер

Базы: Android `449de43`, backend `1921b3f`. Обе локальные ветки `feat/planner-sequence-duration`; коммит/push/PR/merge/deploy не выполнялись. Backend worktree: `/private/tmp/ValerochkaGymBackend-planner-sequence-duration`.

- [x] T-001 / AC-001: путь capture/filter/prompt/result проверен синтетическими integration fixtures. Completed actual/legacy и даты сохранены; сводка последней нагрузки подписана как повторное представление, recent/weekly partition не менялся. Потеря истории конкретного пользователя не доказана.
- [x] T-002 / AC-002: prompt v3 учитывает цель, частоту, пожелания и последовательность; повтор разрешён осмысленно. Reason enum валидируются, backend вычисляет фокус/повторы/время. Никаких выводов о восстановлении по интервалу.
- [x] T-003 / AC-003: единый duration-v1 и побайтно одинаковые fixtures (30/60/90, ограниченный набор, timed/cardio, warmup, null rest). Недобор max(5 минут,20%); не более одной коррекции, общий deadline 45 секунд, вторая попытка ≤20 секунд.
- [x] T-004 / AC-004: отдельный optional endpoint, неизменные strict legacy DTO и approval bytes. Пояснение атомарно с версией предложения. UI отделяет желаемое время от оценки, не переносит исходное обоснование на редактирование. Owner/generation guards сохранены. В offline пояснение не кэшируется; план/очередь работают как прежде.
- [x] T-005 / AC-005: итоговые Android unit/debug и backend check/bootJar успешны. Два ожидания числа Liquibase changesets актуализированы (18→19).

## Проверки

- Android `:app:compileDebugKotlin`: pass.
- Android адресные тесты времени/DTO/редактора/AI-формы/списка программ: 47 tests, pass.
- Android `:app:testDebugUnitTest`: 1588 tests, 0 failures/errors, 1 штатный skip (`EmulatorV11CopyMigrationTest`, внешняя копия БД эмулятора не предоставлялась). Новый Compose-тест пояснения на 360dp/fontScale=2 без скриншотов включён.
- Android `:app:assembleDebug`: pass. APK `app/build/outputs/apk/debug/app-debug.apk`.
- Backend адресный `CalendarAiCaptureIntegrationTest` + `PlannerExplanationTest`: 49 tests, pass (локальный PostgreSQL/Colima).
- Backend первая полная `check bootJar`: 237 tests, 235 pass, 2 failures только из-за числа changesets. Оба ожидания исправлены; повторная полная `check bootJar` успешна: 237 tests, 0 failures/errors/skips.
- Spotless: обе стороны pass. Общие duration fixtures byte-identical; SHA-256 в eval-документе. `git diff --check`: pass.

## Ограничения и ручная проверка

Внешний inference не выполнялся; качество решения LLM тестами не доказано. Сравнительная рубрика, синтетические ответы, порядок backend→Android выката и ручная проверка — `planner-sequence-duration-eval.md` и `planner-sequence-duration-ai-fixtures.json`.

Версия Android `1.3.61 (69)` — один инкремент. Перед публикацией заново сверить main: соседняя updater-задача тоже повышает версию. `data/update` и её тесты не затронуты. Release/R8 не запускался: текущая конфигурация требует release-подпись; секреты не использовались.

Планирование/реализация/ревью выполнены одним владельцем согласно запросу, без цепочки агентов. Независимого агентского review нет; self-review проверил SQL atomicity (включая Hibernate flush до JDBC FK), strict legacy compatibility, budget/deadline, отсутствие автоматического принятия и fencing позднего optional GET.
