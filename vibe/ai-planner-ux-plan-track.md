# Трекер UX AI-планировщика

База origin/main a20a77b (повторно проверена перед финальными gates).
Ветка feat/ai-planner-ux, worktree /Users/raul/.codex/worktrees/c482/ValerochkaGym.

- T-001 / AC-001 — выполнено. ExcludedExercises показывает выбранные строки с удалением,
  PlanningChoiceSheet ищет только невыбранные и неархивные записи. Compose regression:
  500 упражнений, compact 360dp/fontScale2, пустой поиск, отмена, выбор без дублей,
  удаление архивной записи с сохранением имени.
- T-002 / AC-002 — выполнено. Явная замена, stable key по exerciseId, сохранение позиции,
  общего числа подходов и отдыха. PlanningChoiceSheet + общий поток
  GymRepository.observeAvailableExercises (как RoutineEditor) ограничивают выбор залами.
  PlannedSetFields извлечён из RoutineEditor и используется в обоих редакторах.
  Тип каталога определяет поля; incompatible значения очищаются без выдуманных значений.
  Общий авторский редактор тренера также передаёт типы упражнений, сохраняя совместимость.
  Regressions: ProposalExerciseEditingTest, TrainingProposalComposeTest,
  RoutineEditorScreenTest, PlanningChoiceComposeTest.
- T-003 / AC-003 — выполнено. Material date/time picker без поля зоны;
  rememberDeviceTimeZone реагирует на broadcast/resume. PlanningDateTime сохраняет instant,
  seconds/millis и later overlap offset; gap и прошедшая дата отклоняются.
  SavedStateHandle сохраняет форму с owner и process/epoch guard; перезапуск процесса
  того же аккаунта не теряет черновик из-за сброса runtime epoch.
  Regressions: PlanningDateTimeTest, CalendarAiViewModelTest.
- T-004 / AC-004 — выполнено. Локальный proposal draft допускает незаполненные поля после
  замены; строгая canonical validation остаётся перед approval. Regression сохраняет и
  повторно открывает incomplete draft без отправки подтверждения.
  WorkManager, durable enqueue, offline очередь, backend контракт, owner/generation guards,
  версия Room и журнал подготовки не изменены. Их регрессии прошли в полном прогоне.
- T-005 / AC-005 — выполнено. Адресный набор: 52 теста; 51 прошёл сразу, один test race
  исправлен ожиданием saved event вместо раннего чтения БД и отдельно перепроверен.
  После стабильного diff: `./gradlew spotlessCheck :app:testDebugUnitTest` — PASS,
  1584 теста в 252 классах: 1583 PASS, 1 SKIP, 0 failures/errors.
  Единственный SKIP — существующий EmulatorV11CopyMigrationTest, требующий внешней копии.
  `./gradlew :app:assembleDebug` — PASS. JDK 21, локальный Android SDK.
  `git diff --check` — PASS. Семантика и fontScale2 проверены без скриншотов/телефона.
- T-006 — выполнено. main 1.3.59 (67) → 1.3.60 (68), один инкремент.
  APK: app/build/outputs/apk/debug/app-debug.apk; версия проверена по output-metadata.json.

Read-only проверка timezone slice и интеграционная проверка diff завершены.
Исправлены найденные пограничные случаи: смена зоны перед UI-синхронизацией, выбор
неизменённого времени при осеннем DST, сброс process epoch, сохранение incomplete proposal.
Открытых известных дефектов нет. Проверки на устройстве и визуальные снимки не выполнялись
по условиям задачи. Результат локальный, без commit/push/PR/merge/release.
