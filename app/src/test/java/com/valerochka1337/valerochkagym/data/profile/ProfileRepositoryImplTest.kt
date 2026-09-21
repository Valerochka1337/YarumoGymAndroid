package com.valerochka1337.valerochkagym.data.profile

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccent
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentMarkerEntity
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentV2Entity
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreference
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreferenceEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEquipmentPreferenceEntity
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.PlannerExerciseAccentEdit
import com.valerochka1337.valerochkagym.domain.PlannerExerciseChoice
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.service.WallClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryImplTest : RoomDaoTest() {
  private class Store(initial: BackendTokens? = null) : BackendSessionStore {
    override val session = MutableStateFlow(initial)
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }
  }

  private object Server : BackendTransport {
    override val json = Json

    override suspend fun public(method: String, path: String, body: JsonElement?) = error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?) =
        error("unused")
  }

  private fun repository(
      store: Store,
      now: Long = 1_800_000_000_000L,
  ): Pair<ProfileRepositoryImpl, BackendSync> {
    val sync = BackendSync(db, Server, store)
    return ProfileRepositoryImpl(db, db.profileDao(), sync, store, WallClock { now }) to sync
  }

  @Test
  fun `first v2 accent edit applies its deletion to current legacy choices and invalid new choice rolls back`() =
      runTest {
        val (repository, _) = repository(Store())
        val target = requireNotNull(repository.openEditor()).target
        val removed = "11111111-1111-1111-1111-111111111111"
        val stale = "22222222-2222-2222-2222-222222222222"
        val invalid = "33333333-3333-3333-3333-333333333333"
        db.plannerExercisePreferenceDao()
            .upsert(
                listOf(
                    PlannerExercisePreferenceEntity(
                        "GUEST",
                        removed,
                        PlannerExercisePreference.LESS,
                    ),
                    PlannerExercisePreferenceEntity(
                        "GUEST",
                        stale,
                        PlannerExercisePreference.NEVER,
                    ),
                )
            )

        assertEquals(
            ProfileSaveResult.Saved,
            repository.saveWithStrength(
                target,
                BasicProfile(trainingGoal = TrainingGoal.ENDURANCE),
                emptyList(),
                plannerPreferences = null,
                plannerAccentEdits = listOf(PlannerExerciseAccentEdit(removed, null)),
            ),
        )
        assertTrue(db.plannerExerciseAccentV2Dao().hasMarker("GUEST"))
        assertEquals(
            listOf(stale),
            db.plannerExerciseAccentV2Dao().get("GUEST").map { it.exerciseSyncId },
        )
        assertEquals(TrainingGoal.ENDURANCE.name, db.profileDao().get("GUEST")?.trainingGoal)

        assertEquals(
            ProfileSaveResult.Invalid,
            repository.saveWithStrength(
                target,
                BasicProfile(trainingGoal = TrainingGoal.STRENGTH),
                emptyList(),
                plannerPreferences = null,
                plannerAccentEdits =
                    listOf(PlannerExerciseAccentEdit(invalid, PlannerExerciseAccent.NORMAL)),
            ),
        )
        assertEquals(TrainingGoal.ENDURANCE.name, db.profileDao().get("GUEST")?.trainingGoal)
        assertEquals(
            listOf(stale),
            db.plannerExerciseAccentV2Dao().get("GUEST").map { it.exerciseSyncId },
        )
      }

  @Test
  fun `first accent edit retains a legacy choice imported after the editor snapshot`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target
    val removed = "11111111-1111-1111-1111-111111111111"
    val importedAfterSnapshot = "22222222-2222-2222-2222-222222222222"
    db.plannerExercisePreferenceDao()
        .upsert(
            listOf(
                PlannerExercisePreferenceEntity("GUEST", removed, PlannerExercisePreference.LESS)
            )
        )

    // This emulates a sync import that completes after the UI projected the old legacy list.
    db.plannerExercisePreferenceDao()
        .upsert(
            listOf(
                PlannerExercisePreferenceEntity(
                    "GUEST",
                    importedAfterSnapshot,
                    PlannerExercisePreference.NEVER,
                )
            )
        )

    assertEquals(
        ProfileSaveResult.Saved,
        repository.saveWithStrength(
            target,
            BasicProfile(),
            emptyList(),
            plannerAccentEdits = listOf(PlannerExerciseAccentEdit(removed, null)),
        ),
    )

    assertEquals(
        listOf(importedAfterSnapshot to PlannerExerciseAccent.NEVER),
        db.plannerExerciseAccentV2Dao().get("GUEST").map { it.exerciseSyncId to it.preference },
    )
    assertTrue(db.plannerExerciseAccentV2Dao().hasMarker("GUEST"))
  }

  @Test
  fun `local accent delta retains a concurrently imported v2 choice`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target
    val imported = "11111111-1111-1111-1111-111111111111"
    val local = "22222222-2222-2222-2222-222222222222"
    db.exerciseDao()
        .insert(
            ExerciseEntity(
                name = "Жим",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
                syncId = local,
            )
        )
    val accents = db.plannerExerciseAccentV2Dao()
    accents.upsertMarker(PlannerExerciseAccentMarkerEntity("GUEST"))
    accents.upsertRows(
        listOf(PlannerExerciseAccentV2Entity("GUEST", imported, PlannerExerciseAccent.LESS))
    )

    assertEquals(
        ProfileSaveResult.Saved,
        repository.saveWithStrength(
            target,
            BasicProfile(),
            emptyList(),
            plannerAccentEdits =
                listOf(PlannerExerciseAccentEdit(local, PlannerExerciseAccent.NORMAL)),
        ),
    )

    assertEquals(
        listOf(imported to PlannerExerciseAccent.LESS, local to PlannerExerciseAccent.NORMAL),
        accents.get("GUEST").map { it.exerciseSyncId to it.preference },
    )
  }

  @Test
  fun `unrelated profile save does not adopt legacy accents`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target
    db.plannerExercisePreferenceDao()
        .upsert(
            listOf(
                PlannerExercisePreferenceEntity(
                    "GUEST",
                    "11111111-1111-1111-1111-111111111111",
                    PlannerExercisePreference.LESS,
                )
            )
        )

    assertEquals(
        ProfileSaveResult.Saved,
        repository.save(target, BasicProfile(trainingGoal = TrainingGoal.ENDURANCE)),
    )

    assertTrue(!db.plannerExerciseAccentV2Dao().hasMarker("GUEST"))
    assertTrue(db.plannerExerciseAccentV2Dao().get("GUEST").isEmpty())
  }

  @Test
  fun `rep preference validates persists clears and remains isolated by owner`() = runTest {
    val store = Store()
    val (repo, sync) = repository(store)
    val guest = repo.openEditor()!!.target
    for ((min, max) in listOf(0 to 8, 12 to 6, 6 to 51)) {
      assertEquals(
          ProfileSaveResult.Invalid,
          repo.save(guest, BasicProfile(preferredRepMin = min, preferredRepMax = max)),
      )
    }
    assertEquals(ProfileSaveResult.Invalid, repo.save(guest, BasicProfile(preferredRepMin = 6)))
    assertEquals(
        ProfileSaveResult.Saved,
        repo.save(guest, BasicProfile(preferredRepMin = 6, preferredRepMax = 12)),
    )
    assertEquals(6, repo.openEditor()!!.profile.preferredRepMin)
    assertEquals(12, repo.openEditor()!!.profile.preferredRepMax)
    assertEquals(ProfileSaveResult.Saved, repo.save(guest, BasicProfile()))
    assertNull(repo.openEditor()!!.profile.preferredRepMin)
    sync.claim("owner")
    store.save(BackendTokens("owner", "owner@example.com", "access", "refresh"))
    val owner = repo.openEditor()!!.target
    assertEquals(
        ProfileSaveResult.Saved,
        repo.save(owner, BasicProfile(preferredRepMin = 3, preferredRepMax = 6)),
    )
    assertEquals(
        ProfileSaveResult.StaleTarget,
        repo.save(guest, BasicProfile(preferredRepMin = 8, preferredRepMax = 15)),
    )
    assertEquals(3, repo.openEditor()!!.profile.preferredRepMin)
  }

  @Test
  fun `a session change during profile writes rolls back the entire profile`() = runTest {
    val store = Store()
    val sync = BackendSync(db, Server, store)
    sync.claim("owner")
    val tokens = BackendTokens("owner", "owner@example.com", "access", "refresh")
    store.save(tokens)
    var clockReads = 0
    val repo =
        ProfileRepositoryImpl(
            db,
            db.profileDao(),
            sync,
            store,
            WallClock {
              clockReads++
              if (clockReads == 2) store.save(tokens)
              1_800_000_000_000L
            },
        )
    val target = requireNotNull(repo.openEditor()).target
    assertEquals(
        ProfileSaveResult.StaleTarget,
        repo.save(target, BasicProfile(trainingGoal = TrainingGoal.STRENGTH)),
    )
    assertNull(db.profileDao().get("owner"))
  }

  @Test
  fun `empty guest editor remains valid without creating a profile row`() = runTest {
    val (repository, _) = repository(Store())

    val snapshot = repository.openEditor()

    assertEquals(BasicProfile(preferredRepMin = 8, preferredRepMax = 14), snapshot?.profile)
    assertEquals(0, tableCount("profiles"))
  }

  @Test
  fun `invalid combined strength draft leaves baseline profile unchanged`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target

    assertEquals(
        ProfileSaveResult.Invalid,
        repository.saveWithStrength(
            target,
            BasicProfile(trainingGoal = TrainingGoal.STRENGTH),
            listOf(KeyExerciseChoice(99L, "missing", KeyExercisePriority.HIGH)),
        ),
    )

    assertNull(db.profileDao().get("GUEST"))
    assertNull(db.strengthPlannerProfileDao().get("GUEST"))
  }

  @Test
  fun `key exercises save for every goal and never cannot contradict a key`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target
    val exerciseId =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    syncId = "00000000-0000-0000-0000-000000000001",
                )
            )
    val key =
        KeyExerciseChoice(
            exerciseId = exerciseId,
            exerciseSyncId = "00000000-0000-0000-0000-000000000001",
            priority = KeyExercisePriority.NORMAL,
        )

    assertEquals(
        ProfileSaveResult.Saved,
        repository.saveWithStrength(
            target,
            BasicProfile(trainingGoal = TrainingGoal.ENDURANCE),
            listOf(key),
        ),
    )
    assertEquals(
        listOf(key.exerciseSyncId),
        db.strengthPlannerProfileDao().keyExercises("GUEST").map { it.exerciseSyncId },
    )

    assertEquals(
        ProfileSaveResult.Invalid,
        repository.saveWithStrength(
            target,
            BasicProfile(trainingGoal = TrainingGoal.ENDURANCE),
            listOf(key),
            listOf(
                PlannerExerciseChoice(
                    exerciseId,
                    key.exerciseSyncId,
                    PlannerExercisePreference.NEVER,
                )
            ),
        ),
    )
    assertEquals(
        listOf(key.exerciseSyncId),
        db.strengthPlannerProfileDao().keyExercises("GUEST").map { it.exerciseSyncId },
    )
  }

  @Test
  fun `guest profile rekeys to deterministic owner identity during claim`() = runTest {
    val store = Store()
    val (repository, sync) = repository(store)
    val equipment = LocalEquipmentCatalog.entries.take(2).map { it.id }.toSet()
    assertEquals(2, equipment.size)
    val guest = requireNotNull(repository.openEditor())
    assertEquals(
        ProfileSaveResult.Saved,
        repository.save(
            guest.target,
            BasicProfile(trainingGoal = TrainingGoal.STRENGTH, equipmentIds = equipment),
        ),
    )
    db.plannerExerciseAccentV2Dao()
        .upsertMarker(
            com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentMarkerEntity(
                "GUEST"
            )
        )
    db.plannerExerciseAccentV2Dao()
        .upsertRows(
            listOf(
                com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentV2Entity(
                    "GUEST",
                    "11111111-1111-1111-1111-111111111111",
                    PlannerExerciseAccent.NORMAL,
                )
            )
        )

    sync.claim("owner-7")
    store.save(BackendTokens("owner-7", "owner@example.com", "access", "refresh"))

    val profile = requireNotNull(db.profileDao().get("owner-7"))
    assertEquals("9e27b903-8c27-34a4-82fa-164c43cf1212", profile.syncId)
    assertEquals(equipment.sorted(), db.profileDao().equipmentIds("owner-7"))
    assertNull(db.profileDao().get("GUEST"))
    assertEquals(emptyList<String>(), db.profileDao().equipmentIds("GUEST"))
    assertTrue(db.plannerExerciseAccentV2Dao().hasMarker("owner-7"))
    assertEquals(1, db.plannerExerciseAccentV2Dao().get("owner-7").size)
  }

  @Test
  fun `stale guest editor cannot save into claimed owner scope`() = runTest {
    val store = Store()
    val (repository, sync) = repository(store)
    val guest = requireNotNull(repository.openEditor())

    sync.claim("user-a")
    store.save(BackendTokens("user-a", "a@example.com", "access", "refresh"))

    assertEquals(
        ProfileSaveResult.StaleTarget,
        repository.save(guest.target, BasicProfile(trainingGoal = TrainingGoal.ENDURANCE)),
    )
    assertNull(db.profileDao().get("user-a"))
    assertNull(repository.observe(guest.target).first())
  }

  @Test
  fun `save rejects future birth date and unknown equipment`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target

    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(birthDate = "2030-01-01")),
    )
    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(equipmentIds = setOf("not-catalog"))),
    )
    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(birthDate = "1899-12-31")),
    )
    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(birthDate = "not-an-ISO-date")),
    )
    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(birthDate = "+02000-02-29")),
    )
    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(plannedSessionsPerWeek = 0)),
    )
    assertEquals(
        ProfileSaveResult.Invalid,
        repository.save(target, BasicProfile(preferredSessionDurationMinutes = 241)),
    )
    assertTrue(db.profileDao().get("GUEST") == null)
  }

  @Test
  fun `saving replaces equipment children and retains an explicitly empty profile`() = runTest {
    val (repository, _) = repository(Store())
    val target = requireNotNull(repository.openEditor()).target
    val equipment = LocalEquipmentCatalog.entries.take(2).map { it.id }.toSet()
    assertEquals(2, equipment.size)

    assertEquals(
        ProfileSaveResult.Saved,
        repository.save(
            target,
            BasicProfile(
                trainingGoal = TrainingGoal.MUSCLE_GAIN,
                plannedSessionsPerWeek = 7,
                preferredSessionDurationMinutes = 240,
                equipmentIds = equipment,
            ),
        ),
    )
    assertEquals(equipment.sorted(), db.profileDao().equipmentIds("GUEST"))

    assertEquals(ProfileSaveResult.Saved, repository.save(target, BasicProfile()))
    assertNull(db.profileDao().get("GUEST")?.trainingGoal)
    assertEquals(emptyList<String>(), db.profileDao().equipmentIds("GUEST"))
  }

  @Test
  fun `profile validation accepts boundary values and rejects overlong Unicode constraints`() =
      runTest {
        val (repository, _) = repository(Store())
        val target = requireNotNull(repository.openEditor()).target
        val withinLimit = "🚀".repeat(2_000)

        assertEquals(
            ProfileSaveResult.Saved,
            repository.save(
                target,
                BasicProfile(
                    birthDate = "1900-01-01",
                    plannedSessionsPerWeek = 1,
                    preferredSessionDurationMinutes = 10,
                    manualConstraints = withinLimit,
                ),
            ),
        )
        assertEquals(
            ProfileSaveResult.Invalid,
            repository.save(target, BasicProfile(plannedSessionsPerWeek = 8)),
        )
        assertEquals(
            ProfileSaveResult.Invalid,
            repository.save(target, BasicProfile(preferredSessionDurationMinutes = 9)),
        )
        assertEquals(
            ProfileSaveResult.Invalid,
            repository.save(target, BasicProfile(manualConstraints = "🚀".repeat(2_001))),
        )
        assertEquals(
            ProfileSaveResult.Saved,
            repository.save(target, BasicProfile(manualConstraints = "  \n  ")),
        )
        assertNull(db.profileDao().get("GUEST")?.manualConstraints)

        TrainingGoal.entries.forEach { goal ->
          assertEquals(
              ProfileSaveResult.Saved,
              repository.save(target, BasicProfile(trainingGoal = goal)),
          )
        }
        ProfileSex.entries.forEach { sex ->
          assertEquals(ProfileSaveResult.Saved, repository.save(target, BasicProfile(sex = sex)))
        }
        ExperienceLevel.entries.forEach { experience ->
          assertEquals(
              ProfileSaveResult.Saved,
              repository.save(target, BasicProfile(experienceLevel = experience)),
          )
        }
      }

  @Test
  fun `claim keeps existing owner profile instead of merging the guest singleton`() = runTest {
    val store = Store()
    val (repository, sync) = repository(store)
    val guestEquipment = LocalEquipmentCatalog.entries.take(2).map { it.id }.toSet()
    val ownerEquipment = LocalEquipmentCatalog.entries.drop(2).take(2).map { it.id }.toSet()
    assertEquals(2, guestEquipment.size)
    assertEquals(2, ownerEquipment.size)
    val guest = requireNotNull(repository.openEditor())
    assertEquals(
        ProfileSaveResult.Saved,
        repository.save(
            guest.target,
            BasicProfile(trainingGoal = TrainingGoal.STRENGTH, equipmentIds = guestEquipment),
        ),
    )
    db.profileDao()
        .upsert(
            ProfileEntity(
                scope = "owner-b",
                syncId = "server-authoritative-owner-b",
                trainingGoal = TrainingGoal.ENDURANCE.name,
                updatedAt = 7,
            ),
        )
    db.profileDao()
        .upsertEquipment(
            ownerEquipment.sorted().map { ProfileEquipmentPreferenceEntity("owner-b", it) }
        )

    sync.claim("owner-b")

    assertNull(db.profileDao().get("GUEST"))
    val owner = requireNotNull(db.profileDao().get("owner-b"))
    assertEquals("server-authoritative-owner-b", owner.syncId)
    assertEquals(TrainingGoal.ENDURANCE.name, owner.trainingGoal)
    assertEquals(ownerEquipment.sorted(), db.profileDao().equipmentIds("owner-b"))
    assertEquals(emptyList<String>(), db.profileDao().equipmentIds("GUEST"))
  }
}
