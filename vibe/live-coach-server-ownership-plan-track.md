# Трекер переноса Live Coach

- [x] Атомарный новый message API, серверная история, модель сессии.
- [x] Серверный semantic fingerprint, монотонность и устойчивые acknowledgements.
- [x] Receipts с причиной и снимком, память решений и дедупликация импорта.
- [x] Существующие worker/timer, блокировки инициативы, свежесть перед запуском/публикацией.
- [x] Общий SSE с журналом, origin, heartbeat и проверкой авторизации.
- [x] Изолированная серверная проверка модели.
- [x] Android: новый контракт, независимые доставка/поток, Room 35, USER/COACH отображение.
- [x] Playground и API-документация.
- [x] Финальный backend check bootJar с PostgreSQL: 288 тестов, 0 ошибок.
- [x] Финальный Android testDebugUnitTest assembleDebug: 1690 тестов, 0 ошибок, 1 пропущен.

Публикация и развёртывание не выполнялись. Ветки обоих репозиториев: feat/live-coach-server-ownership.

## Проверки 2026-09-19

- Backend: `./gradlew spotlessApply check bootJar` — успешно, PostgreSQL Testcontainers через Colima.
- Android: `./gradlew spotlessApply :app:testDebugUnitTest :app:assembleDebug` — успешно.
- Playground: `node --test src/test/js/coach-playground.test.cjs` — 9/9.
- Проверены атомарный message/snapshot rollback, неизменный replay, переставленные sequence,
  серверный fingerprint, receipt memory, свежесть при публикации и сохранение пользовательских задач.
- HTTP SSE: подключение без задач, появление новой задачи, продолжение после completed,
  Last-Event-ID и отзыв сессии. Android: cursor+draft, дубликаты, скрытые COACH-ошибки,
  сохранение USER-статуса, coalescing, отсутствие отправки на тиках, lost-ack/restart.
- PostgreSQL и Room миграции сохраняют существующие записи; проверено открытие старых Android баз.
- Артефакты: backend `build/libs/app.jar`, Android `app/build/outputs/apk/debug/app-debug.apk`.
