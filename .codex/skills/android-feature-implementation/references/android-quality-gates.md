# Android quality gates

Read only sections relevant to the changed behavior; routine edits do not require this reference. Project instructions and established local contracts override generic platform advice.

## Always

- Preserve the single `:app` module unless the user explicitly requests modularization.
- When an existing plan uses AC IDs, retain their verification evidence. Routine changes do not require new IDs or a traceability matrix.
- Prefer the smallest reliable test layer and add tests during implementation, not only after it.
- Follow AGENTS.md for final full unit/debug gates, reuse of test evidence, documentation-only exclusions and serialization of Gradle. Use targeted checks between internal stages.
- Do not add logs, mock libraries, destructive database fallback, or dependencies that the feature does not require.

## Architecture, Flow, and coroutines

- Keep Room or another established repository as the single source of truth for persisted data. UI must not bypass repositories to reach data sources.
- Preserve unidirectional flow: immutable state down, user events up, screen-level state in ViewModels, reusable composables driven by parameters/plain state holders.
- Preserve this project's `StateFlow` and `stateIn(WhileSubscribed(5000))` patterns and lifecycle-aware Compose collection.
- Make suspend work main-safe. Do not use `GlobalScope`, blocking Main calls, or hardcoded production dispatchers where an injected dispatcher is established.
- Route heavy Flow computation through `flowOn(@ComputeDispatcher)` and test cancellation, ordering, and races where observable behavior depends on them.

Primary references: [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations), [Compose state hoisting](https://developer.android.com/develop/ui/compose/state-hoisting), [coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices).

## Room and persisted data

- Use a handwritten `Migration`; register it and never use `fallbackToDestructiveMigration`.
- Preserve existing rows, null/default meaning, foreign keys, indexes, and transactional invariants.
- Keep entity, DAO, migration, database version, exported `app/schemas/` JSON, and migration tests under one owner.
- Test the incremental migration and the supported full migration path. Verify the generated newest schema is committed and matches the implementation.
- Keep multi-write invariants atomic with a Room transaction.

Primary reference: [Room database migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).

## Compose UI and navigation

- Read and follow `docs/design-system.md` as the source of truth.
- Keep UI strings hardcoded in Kotlin by project decision. Use only `MaterialTheme.colorScheme.*`, `GymMotion`, `GymHaptics`, established components, and `ChartPalette`/`ChartSpec` exceptions documented by the project.
- Cover loading, empty, content, validation, and error states. Preserve only minimal UI state with `rememberSaveable`/`SavedStateHandle`; persist domain state in Room.
- Verify compact, medium/expanded behavior, insets, navigation/back behavior, stable list identity where needed, and no expensive work inside composition.
- Verify `fontScale = 2.0`, meaningful semantics/state/action labels, non-color-only meaning, alternative actions for interactive Canvas/gestures, and touch targets of at least 48dp.
- For chart changes, run `AnalysisRenderTest` unless already covered by the full suite. Inspect its snapshots only when the user explicitly requests visual inspection, as required by AGENTS.md.

Primary references: [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics), [accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing), [state saving](https://developer.android.com/develop/ui/compose/state-saving), [adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps), [Compose performance](https://developer.android.com/develop/ui/compose/performance/bestpractices).

## WorkManager, services, and system integration

- Choose WorkManager for deferrable persistent work and a foreground service only for an immediate user-visible ongoing task.
- Make retried work idempotent. Review unique work names/policies, constraints, backoff, bounded retries, cancellation, duplicate scheduling, and process recreation.
- For manifests, permissions, exported components, intents, PendingIntents, files/URIs, credentials, and external APIs, test denied/degraded/error paths and protect private data.
- Request runtime permission in context and keep a usable denied path where the feature permits it.

Primary references: [manage WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work), [WorkManager integration testing](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing), [Android security tips](https://developer.android.com/privacy-and-security/security-tips).

## Build, DI, and dependencies

- Keep AGP 9 built-in Kotlin; never apply `kotlin.android`. Use KSP, never kapt. Put versions only in `gradle/libs.versions.toml`.
- Justify a new dependency against existing APIs, Compose BOM compatibility, the pinned Material3 alpha, APK/R8 impact, maintenance, and testability.
- Match Hilt scopes to Android lifetime. Prefer direct construction plus handwritten fakes for unit tests; use Hilt test bindings only for integration behavior.
- For dependency, R8, resource-shrinking, manifest, serialization, or other release-sensitive changes, additionally run `./gradlew :app:assembleRelease` when signing inputs are available or report the exact signing blocker once. A version-only bump does not require this gate. Do not retry a known blocker until its conditions change.

Primary references: [Hilt testing](https://developer.android.com/training/dependency-injection/hilt-testing), [Android testing strategies](https://developer.android.com/training/testing/fundamentals/strategies), [test doubles](https://developer.android.com/training/testing/fundamentals/test-doubles), [kotlinx-coroutines-test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/).
