package com.valerochka1337.valerochkagym.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.CoachChangeIntent
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkoutEditorTest : RoomDaoTest() {
  @Test
  fun `calculated proposal survives recreation applies atomically and undo restores RIR and targets`() =
      runTest {
        val workout = insertWorkout("autoregulated")
        val section = insertWorkoutExercise(workout, exercise("Press"))
        val doneId = insertSet(section, 0, weightKg = 100.0, reps = 8, isCompleted = true)
        val nextId = insertSet(section, 1, weightKg = 100.0, reps = 8)
        val done =
            db.workoutDao()
                .getSet(doneId)!!
                .copy(
                    completedAt = 1000,
                    setType = "WORK",
                    targetReps = 8,
                    targetWeightKg = 100.0,
                    legacyTargetRir = 9,
                    actualRir = 1,
                    reportedFeelingsJson = "[\"HARDER_THAN_EXPECTED\"]",
                )
        val next =
            db.workoutDao()
                .getSet(nextId)!!
                .copy(setType = "WORK", targetReps = 8, targetWeightKg = 100.0)
        db.workoutDao().updateSet(done)
        db.workoutDao().updateSet(next)
        val timer = RestTimerEngine(backgroundScope) { 0L }
        val editor = coordinator(timer)
        val proposal =
            assertIsSaved(
                editor.saveModelProposalResult(
                    "user",
                    workout,
                    0,
                    listOf(
                        CoachChangeIntent.Autoregulate(
                            com.valerochka1337.valerochkagym.domain.autoregulation
                                .AutoregulationOptions()
                        )
                    ),
                    Long.MAX_VALUE,
                )
            )
        assertEquals(8, db.workoutDao().getSet(nextId)!!.reps)
        val recreated = coordinator(timer)
        assertEquals(
            CommandResult.APPLIED,
            recreated.confirmProposal("user", proposal.id, "calculated").result,
        )
        assertEquals(7, db.workoutDao().getSet(nextId)!!.reps)
        assertEquals(done, db.workoutDao().getSet(doneId))
        val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
        assertEquals(
            CommandResult.APPLIED,
            recreated.submit("user", workout, "undo-calculated", 1, undo, authority(undo)).result,
        )
        assertEquals(8, db.workoutDao().getSet(nextId)!!.reps)
        assertEquals(1, db.workoutDao().getSet(doneId)!!.actualRir)
        assertEquals(9, db.workoutDao().getSet(doneId)!!.legacyTargetRir)
      }

  @Test
  fun `changed feedback replaces a pending calculation and rejects the old confirmation`() =
      runTest {
        val workout = insertWorkout("refresh")
        val section = insertWorkoutExercise(workout, exercise("Press"))
        val doneId = insertSet(section, 0, weightKg = 100.0, reps = 8, isCompleted = true)
        val nextId = insertSet(section, 1, weightKg = 100.0, reps = 8)
        db.workoutDao()
            .updateSet(
                db.workoutDao()
                    .getSet(doneId)!!
                    .copy(
                        completedAt = 1000,
                        setType = "WORK",
                        targetReps = 8,
                        actualRir = 1,
                        reportedFeelingsJson = "[\"HARDER_THAN_EXPECTED\"]",
                    )
            )
        db.workoutDao()
            .updateSet(db.workoutDao().getSet(nextId)!!.copy(setType = "WORK", targetReps = 8))
        val editor = coordinator(RestTimerEngine(backgroundScope) { 0L })
        val old =
            assertIsSaved(
                editor.saveModelProposalResult(
                    "user",
                    workout,
                    0,
                    listOf(
                        CoachChangeIntent.Autoregulate(
                            com.valerochka1337.valerochkagym.domain.autoregulation
                                .AutoregulationOptions()
                        )
                    ),
                    Long.MAX_VALUE,
                )
            )
        // Even a data correction without a revision bump must not preserve the old evidence.
        db.workoutDao().updateSet(db.workoutDao().getSet(doneId)!!.copy(actualRir = 5))
        editor.refreshAutoregulationProposal("user", workout, null)
        val replacement = db.coachDao().pendingProposal(workout)!!
        assertTrue(old.id != replacement.id)
        assertEquals(CommandResult.STALE, editor.confirmProposal("user", old.id, "old").result)
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", replacement.id, "new").result,
        )
        assertEquals(7, db.workoutDao().getSet(nextId)!!.reps)
      }

  @Test
  fun `model estimates a weight step as a proposal without applying it`() = runTest {
    val workout = insertWorkout("guard")
    val section = insertWorkoutExercise(workout, exercise("Press"))
    val set = insertSet(section, 0, weightKg = 50.0, reps = 8)
    val editor = coordinator(RestTimerEngine(backgroundScope) { 0L })
    val proposal =
        assertIsSaved(
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(
                    CoachChangeIntent.EditSet(
                        db.workoutDao().getSet(set)!!.syncId,
                        com.valerochka1337.valerochkagym.data.ai.CoachSetValues(
                            setOf("weight_kg"),
                            weightKg = 45.0,
                        ),
                        false,
                    )
                ),
                Long.MAX_VALUE,
            )
        )
    assertEquals(50.0, db.workoutDao().getSet(set)!!.weightKg!!, 0.0)
    assertEquals(
        CommandResult.APPLIED,
        editor.confirmProposal("user", proposal.id, "confirm-estimate").result,
    )
    assertEquals(45.0, db.workoutDao().getSet(set)!!.weightKg!!, 0.0)
  }

  @Test
  fun `model addition without history creates one empty set and rejects invalid insertion positions`() =
      runTest {
        val workout = insertWorkout("active")
        val id = exercise("New")
        val syncId = db.exerciseDao().getById(id)!!.syncId
        val editor = coordinator(RestTimerEngine(backgroundScope) { 0L })
        assertEquals(
            ModelProposalSaveResult.Invalid,
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(CoachChangeIntent.AddExercise(syncId, 1)),
                Long.MAX_VALUE,
            ),
        )
        assertNull(db.coachDao().pendingProposal(workout))
        val proposal =
            assertIsSaved(
                editor.saveModelProposalResult(
                    "user",
                    workout,
                    0,
                    listOf(CoachChangeIntent.AddExercise(syncId)),
                    Long.MAX_VALUE,
                )
            )
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", proposal.id, "add-empty").result,
        )
        val set = workoutFull(workout).exercises.single().sets.single()
        assertFalse(set.isCompleted)
        assertNull(set.weightKg)
        assertNull(set.reps)
      }

  @Test
  fun `model addition prefills cardio values including flat incline without copying actual results`() =
      runTest {
        val id = exercise("Treadmill", ExerciseType.CARDIO)
        val past = insertWorkoutExercise(insertWorkout("past", finishedAt = 2000), id)
        insertSet(past, 0, durationSec = 300, speedKmh = 8.0, inclinePct = 0.0, isCompleted = true)
        val workout = insertWorkout("active")
        val editor = coordinator(RestTimerEngine(backgroundScope) { 0L })
        val proposal =
            assertIsSaved(
                editor.saveModelProposalResult(
                    "user",
                    workout,
                    0,
                    listOf(CoachChangeIntent.AddExercise(db.exerciseDao().getById(id)!!.syncId)),
                    Long.MAX_VALUE,
                )
            )
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", proposal.id, "add-cardio").result,
        )
        val set = workoutFull(workout).exercises.single().sets.single()
        assertEquals(300, set.targetDurationSec)
        assertEquals(8.0, set.targetSpeedKmh!!, 0.0)
        assertEquals(0.0, set.targetInclinePct!!, 0.0)
        assertNull(set.actualDurationSec)
        assertFalse(set.isCompleted)
      }

  @Test
  fun `model addition freezes full history and inserts unfinished sets at the requested position`() =
      runTest {
        val exerciseId = exercise("Historical press")
        val historical = insertWorkoutExercise(insertWorkout("past", finishedAt = 2000), exerciseId)
        val oldIds =
            (0..31).map { index ->
              insertSet(
                  historical,
                  index,
                  weightKg = 20.0 + index,
                  reps = 12 - index % 4,
                  isCompleted = true,
              )
            }
        insertSet(historical, 32, weightKg = 999.0, reps = 1)
        val warmup = db.workoutDao().getSet(oldIds.first())!!
        db.workoutDao()
            .updateSet(
                warmup.copy(setType = "WARMUP", actualWeightKg = 15.0, note = "Private note")
            )
        val workout = insertWorkout("active")
        insertSet(insertWorkoutExercise(workout, exercise("Existing")), 0, reps = 10)
        val editor =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val proposal =
            assertIsSaved(
                editor.saveModelProposalResult(
                    "user",
                    workout,
                    0,
                    listOf(
                        CoachChangeIntent.AddExercise(
                            db.exerciseDao().getById(exerciseId)!!.syncId,
                            0,
                        )
                    ),
                    Long.MAX_VALUE,
                )
            )
        assertEquals(1, workoutFull(workout).exercises.size)
        assertTrue(proposal.afterSummary.contains("15"))
        assertTrue(proposal.afterSummary.contains("51"))
        // A later historical edit must not change the package the user reviewed.
        db.workoutDao().updateSet(warmup.copy(weightKg = 777.0))
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", proposal.id, "add-history").result,
        )
        val added = workoutFull(workout).exercises.sortedBy { it.workoutExercise.position }.first()
        assertEquals(exerciseId, added.workoutExercise.exerciseId)
        val sets = added.sets.sortedBy { it.setIndex }
        assertEquals(32, sets.size)
        assertEquals(15.0, sets.first().weightKg!!, 0.0)
        assertEquals(51.0, sets.last().targetWeightKg!!, 0.0)
        assertEquals("WARMUP", sets.first().setType)
        assertTrue(
            sets.all {
              !it.isCompleted &&
                  it.completedAt == null &&
                  it.actualWeightKg == null &&
                  it.note.isEmpty()
            }
        )
        assertTrue(
            sets.none { it.syncId in oldIds.map { id -> db.workoutDao().getSet(id)!!.syncId } }
        )
      }

  @Test
  fun `completing a set marks it done and starts rest from settings`() = runTest {
    val setId = seedActiveSet()
    val engine = RestTimerEngine(backgroundScope) { 0L }

    completionEditor(engine, settingsWithRest(90)).completeSetFromUser(setId)

    assertTrue(db.workoutDao().getSet(setId)!!.isCompleted)
    assertEquals(90, (engine.state.value as? RestTimerState.Timed)?.totalSec)
    assertEquals(90_000L, (engine.state.value as? RestTimerState.Timed)?.endsAtMillis)
  }

  @Test
  fun `completing the final set does not start rest`() = runTest {
    val workout = insertWorkout("active", startedAt = 1_000)
    val setId = insertSet(insertWorkoutExercise(workout, exercise("Active")), 0, reps = 8)
    val engine = RestTimerEngine(backgroundScope) { 0L }

    completionEditor(engine, settingsWithRest(90)).completeSetFromUser(setId)

    assertTrue(db.workoutDao().getSet(setId)!!.isCompleted)
    assertNull(engine.state.value)
  }

  @Test
  fun `routine rest overrides the configured default when completing a set`() = runTest {
    val exerciseId = exercise("Routine")
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Routine"))
    db.routineDao()
        .insertRoutineExercises(
            listOf(
                RoutineExerciseEntity(
                    routineId = routineId,
                    exerciseId = exerciseId,
                    position = 0,
                    restSeconds = 45,
                )
            )
        )
    val workoutId = insertWorkout("active", startedAt = 1_000)
    db.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET routineId=? WHERE id=?",
        arrayOf<Any?>(routineId, workoutId),
    )
    val workoutExercise = insertWorkoutExercise(workoutId, exerciseId)
    val setId = insertSet(workoutExercise, 0, reps = 8)
    insertSet(workoutExercise, 1, reps = 8)
    val engine = RestTimerEngine(backgroundScope) { 0L }

    completionEditor(engine, settingsWithRest(90)).completeSetFromUser(setId)

    assertEquals(45, (engine.state.value as? RestTimerState.Timed)?.totalSec)
  }

  @Test
  fun `completing an inactive set leaves it unchanged and does not start rest`() = runTest {
    seedActiveSet()
    val staleSetId = seedFinishedSet()
    val engine = RestTimerEngine(backgroundScope) { 0L }

    completionEditor(engine, settingsWithRest(90)).completeSetFromUser(staleSetId)

    assertFalse(db.workoutDao().getSet(staleSetId)!!.isCompleted)
    assertNull(engine.state.value)
  }

  @Test
  fun `completing an already completed set does not restart rest`() = runTest {
    val setId = seedActiveSet()
    val engine = RestTimerEngine(backgroundScope) { 0L }
    val editor = completionEditor(engine, settingsWithRest(90))
    editor.completeSetFromUser(setId)
    val firstStartId = engine.currentStartId()
    engine.skip()

    editor.completeSetFromUser(setId)

    assertTrue(firstStartId != null)
    assertNull(engine.state.value)
  }

  @Test
  fun `autostart disabled completes the set without starting rest`() = runTest {
    val setId = seedActiveSet()
    val engine = RestTimerEngine(backgroundScope) { 0L }
    val settings =
        SettingsRepository(
            FakeDataStore(mutablePreferencesOf(booleanPreferencesKey("rest_autostart") to false))
        )

    completionEditor(engine, settings).completeSetFromUser(setId)

    assertTrue(db.workoutDao().getSet(setId)!!.isCompleted)
    assertNull(engine.state.value)
  }

  @Test
  fun `heart rate rest snapshots its configured threshold when completing a set`() = runTest {
    val setId = seedActiveSet()
    val engine = RestTimerEngine(backgroundScope) { 0L }
    val settings =
        SettingsRepository(
            FakeDataStore(
                mutablePreferencesOf(
                    booleanPreferencesKey("heart_rate_rest_enabled") to true,
                    intPreferencesKey("heart_rate_rest_threshold_bpm") to 110,
                )
            )
        )

    completionEditor(engine, settings).completeSetFromUser(setId)

    assertEquals(RestTimerState.HeartRate(110, 10, 0), engine.state.value)
  }

  @Test
  fun `guest completion writes the active set and starts rest`() = runTest {
    val setId = seedActiveSet()
    session.save(null)
    val engine = RestTimerEngine(backgroundScope) { 0L }

    completionEditor(engine, settingsWithRest(30)).completeSetFromUser(setId)

    assertTrue(db.workoutDao().getSet(setId)!!.isCompleted)
    assertEquals(30, (engine.state.value as? RestTimerState.Timed)?.totalSec)
  }

  @Test
  fun `proposal packet and legacy undo fixtures stay compatible`() = runTest {
    val workoutId = insertWorkout("fixture")
    val exerciseId = exercise()
    val sectionId =
        db.workoutDao()
            .insertWorkoutExercise(
                com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity(
                    workoutId = workoutId,
                    exerciseId = exerciseId,
                    sectionId = "section-fixture",
                    position = 0,
                )
            )
    db.workoutDao()
        .insertSet(
            com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity(
                workoutExerciseId = sectionId,
                setIndex = 0,
                syncId = "set-fixture",
                weightKg = 50.0,
                reps = 8,
            )
        )
    val originalSetId = db.workoutDao().getSetsForWorkoutExercise(sectionId).single().id
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val packet =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet("set-fixture", reps = 6)))
    val proposal =
        requireNotNull(coordinator.saveProposal("user", workoutId, packet, 0, Long.MAX_VALUE))
    val packetFixture =
        """{"operations":[{"type":"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.EditSet","setSyncId":"set-fixture","weightKg":null,"reps":6,"durationSec":null,"speedKmh":null,"inclinePct":null,"completed":null,"clearFields":[],"recordResult":false}]}"""
    assertEquals(packetFixture, db.coachDao().pendingProposalForId(proposal.id)!!.packetJson)

    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "fixture-edit", 0, packet, authority(packet)).result,
    )
    val undoFixture =
        """{"packet":{"operations":[{"type":"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.RestoreWorkout","sections":[{"sectionId":"section-fixture","exerciseId":1,"position":0,"sets":[{"syncId":"set-fixture","setIndex":0,"weightKg":50.0,"reps":8,"durationSec":null,"speedKmh":null,"inclinePct":null,"isCompleted":false,"completedAt":null,"originalWeightKg":null,"originalReps":null,"originalDurationSec":null,"originalSpeedKmh":null,"originalInclinePct":null,"targetWeightKg":null,"targetReps":null,"targetDurationSec":null,"targetSpeedKmh":null,"targetInclinePct":null,"actualWeightKg":null,"actualReps":null,"actualDurationSec":null,"actualSpeedKmh":null,"actualInclinePct":null,"setType":"UNKNOWN","reportedFeelingsJson":"[]","restSnapshotJson":null,"coachMutationRevision":0,"note":""}]}]}]},"context":{"availableTimeMinutes":null,"availableTimeEndsAtMillis":null,"futureRestSeconds":null,"occupiedEquipmentJson":"[]","excludedExerciseIdsJson":"[]"}}"""
    val currentUndoFixture = undoFixture.replace(",\"occupiedEquipmentJson\":\"[]\"", "")
    assertEquals(currentUndoFixture, db.coachDao().context(workoutId)!!.lastUndoPacketJson)

    val legacyFixture = currentUndoFixture.replace(",\"note\":\"\"", "")
    db.coachDao()
        .saveContext(
            db.coachDao()
                .context(workoutId)!!
                .copy(lastUndoPacketJson = legacyFixture, lastUndoRevision = 1)
        )
    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "fixture-undo", 1, undo, authority(undo)).result,
    )
    assertEquals(8, workoutFull(workoutId).exercises.single().sets.single().reps)
    assertEquals(sectionId, workoutFull(workoutId).exercises.single().workoutExercise.id)
    assertEquals(originalSetId, workoutFull(workoutId).exercises.single().sets.single().id)
  }

  @Test
  fun `invalid later operation rolls back the entire packet and does not start rest`() = runTest {
    val workoutId = insertWorkout("workout")
    val sectionId = insertWorkoutExercise(workoutId, exerciseId = exercise())
    val setId = insertSet(sectionId, 0, reps = 8, isCompleted = true)
    val before = db.workoutDao().getSet(setId)!!
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val coordinator = coordinator(timer)
    val packet =
        WorkoutChangeSet.Packet(
            listOf(
                WorkoutChangeSet.Operation.Rest(RestAction.START, null, 60),
                WorkoutChangeSet.Operation.EditSet(before.syncId, reps = 10),
                WorkoutChangeSet.Operation.DeleteSet(before.syncId),
            )
        )

    val receipt = coordinator.submit("user", workoutId, "operation", 0, packet, authority(packet))

    assertEquals(CommandResult.INVALID, receipt.result)
    assertEquals(8, db.workoutDao().getSet(setId)!!.reps)
    assertEquals(0, workoutFull(workoutId).workout.coachRevision)
    assertNull(timer.state.value)
  }

  @Test
  fun `set values reject fields outside the exercise type before changing the packet`() = runTest {
    val workoutId = insertWorkout("workout")
    val timedSection = insertWorkoutExercise(workoutId, exercise(type = ExerciseType.TIMED))
    val timedId = insertSet(timedSection, 0, durationSec = 60)
    val timed = db.workoutDao().getSet(timedId)!!
    val foreign =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.EditSet(timed.syncId, weightKg = 30.0))
        )

    assertEquals(
        CommandResult.INVALID,
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
            .submit("user", workoutId, "foreign", 0, foreign, authority(foreign))
            .result,
    )
    assertNull(db.workoutDao().getSet(timedId)!!.weightKg)
    assertEquals(60, db.workoutDao().getSet(timedId)!!.durationSec)

    val cardioSection = insertWorkoutExercise(workoutId, exercise(type = ExerciseType.CARDIO))
    val cardioId = insertSet(cardioSection, 0, durationSec = 60, speedKmh = 8.0)
    val cardio = db.workoutDao().getSet(cardioId)!!
    val invalid =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.EditSet(cardio.syncId, speedKmh = -1.0))
        )

    assertEquals(
        CommandResult.INVALID,
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
            .submit("user", workoutId, "negative", 0, invalid, authority(invalid))
            .result,
    )
    assertEquals(8.0, db.workoutDao().getSet(cardioId)!!.speedKmh!!, 0.0)
  }

  @Test
  fun `oversized proposal journal rolls back its durable proposal without changing the workout`() =
      runTest {
        val workoutId = insertWorkout("workout")
        val sectionId = insertWorkoutExercise(workoutId, exercise())
        val setId = insertSet(sectionId, 0, reps = 8)
        val oversizedSetId = "x".repeat(70_000)
        db.workoutDao().updateSet(db.workoutDao().getSet(setId)!!.copy(syncId = oversizedSetId))
        val coordinator =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val packet =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.EditSet(oversizedSetId, reps = 6))
            )

        try {
          coordinator.saveProposal(
              accountId = "user",
              workoutId = workoutId,
              packet = packet,
              expectedRevision = 0,
              expiresAt = Long.MAX_VALUE,
          )
          fail("Expected the journal byte limit to reject the proposal")
        } catch (_: IllegalArgumentException) {
          // The entity guard runs inside the Room transaction.
        }

        assertNull(db.coachDao().pendingProposal(workoutId))
        assertEquals(0L, workoutFull(workoutId).workout.coachRevision)
        assertNull(db.coachDao().receipt("proposal"))
      }

  @Test
  fun `receipt replay is idempotent and undo restores the prior workout snapshot`() = runTest {
    val workoutId = insertWorkout("workout")
    val section = insertWorkoutExercise(workoutId, exercise())
    insertSet(section, 0, reps = 8)
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val sectionId = db.workoutDao().getWorkoutExercises(workoutId).single().sectionId
    val add = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.AddSet(sectionId)))

    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "add", 0, add, authority(add)).result,
    )
    assertEquals(2, workoutFull(workoutId).exercises.single().sets.size)
    val added = workoutFull(workoutId).exercises.single().sets.maxBy { it.setIndex }
    assertEquals(8, added.originalReps)
    assertEquals(8, added.targetReps)
    assertEquals(1, added.coachMutationRevision)
    assertEquals(
        CommandResult.REPLAYED,
        coordinator.submit("user", workoutId, "add", 0, add, authority(add)).result,
    )
    assertEquals(1, workoutFull(workoutId).workout.coachRevision)

    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "undo", 1, undo, authority(undo)).result,
    )
    assertEquals(1, workoutFull(workoutId).exercises.single().sets.size)
    assertEquals(2, workoutFull(workoutId).workout.coachRevision)
  }

  @Test
  fun `concurrent packets at the same revision apply exactly once and stale the other`() = runTest {
    val workoutId = insertWorkout("workout")
    val section = insertWorkoutExercise(workoutId, exercise())
    val set = db.workoutDao().getSet(insertSet(section, 0, reps = 8))!!
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val first =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 9)))
    val second =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 10)))

    val results = coroutineScope {
      listOf(
              async {
                coordinator.submit("user", workoutId, "first", 0, first, authority(first)).result
              },
              async {
                coordinator.submit("user", workoutId, "second", 0, second, authority(second)).result
              },
          )
          .awaitAll()
    }

    assertEquals(setOf(CommandResult.APPLIED, CommandResult.STALE), results.toSet())
    assertEquals(1L, workoutFull(workoutId).workout.coachRevision)
    assertTrue(db.workoutDao().getSet(set.id)!!.reps in setOf(9, 10))
  }

  @Test
  fun `a note saved after a command makes its undo stale and keeps the note`() = runTest {
    val workoutId = insertWorkout("workout")
    val section = insertWorkoutExercise(workoutId, exercise())
    val setId = insertSet(section, 0, reps = 8)
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val set = db.workoutDao().getSet(setId)!!
    val change =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 9)))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "edit", 0, change, authority(change)).result,
    )

    val updated = db.workoutDao().getSet(setId)!!
    db.workoutDao().updateSet(updated.copy(note = "локальная заметка"))
    db.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workoutId),
    )

    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.STALE,
        coordinator.submit("user", workoutId, "undo", 2, undo, authority(undo)).result,
    )
    assertEquals("локальная заметка", db.workoutDao().getSet(setId)!!.note)
  }

  @Test
  fun `confirming a proposal after an independent write is stale and does not apply it`() =
      runTest {
        val workoutId = insertWorkout("workout")
        val section = insertWorkoutExercise(workoutId, exercise())
        val set = db.workoutDao().getSet(insertSet(section, 0, reps = 8))!!
        val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
        val coordinator = coordinator(timer)
        val proposed =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 6))
            )
        val proposal =
            requireNotNull(
                coordinator.saveProposal(
                    "user",
                    workoutId,
                    proposed,
                    0,
                    Long.MAX_VALUE,
                ),
            )
        val independent =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 9))
            )

        assertEquals(
            CommandResult.APPLIED,
            coordinator
                .submit("user", workoutId, "screen", 0, independent, authority(independent))
                .result,
        )
        assertEquals(
            CommandResult.STALE,
            coordinator.confirmProposal("user", proposal.id, "confirm").result,
        )

        assertEquals(9, db.workoutDao().getSet(set.id)!!.reps)
        assertEquals(1L, workoutFull(workoutId).workout.coachRevision)
        assertNull(timer.state.value)
        db.openHelper.writableDatabase
            .query("SELECT state FROM coach_proposals WHERE id=?", arrayOf<Any>(proposal.id))
            .use {
              assertTrue(it.moveToFirst())
              assertEquals("STALE", it.getString(0))
            }
      }

  @Test
  fun `undo restores reversible coach context instead of reporting a no-op`() = runTest {
    val workoutId = insertWorkout("workout")
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val change =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.SetAvailableTime(20)),
        )
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "time", 0, change, authority(change)).result,
    )
    assertEquals(20, db.coachDao().context(workoutId)!!.availableTimeMinutes)

    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "undo-context", 1, undo, authority(undo)).result,
    )
    assertNull(db.coachDao().context(workoutId)!!.availableTimeMinutes)
  }

  @Test
  fun `missing workout and foreign account are stale without foreign key receipts`() = runTest {
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val packet = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))

    assertEquals(
        CommandResult.STALE,
        coordinator.submit("user", "gone", "missing", 0, packet, authority(packet)).result,
    )
    assertEquals(0, tableCount("coach_command_receipts"))

    session.save(BackendTokens("other", "other@example.com", "access", "refresh"))
    assertEquals(
        CommandResult.STALE,
        coordinator.submit("user", "gone", "foreign", 0, packet, authority(packet)).result,
    )
    assertEquals(0, tableCount("coach_command_receipts"))
  }

  @Test
  fun `model replacement removes unstarted exercise preserves order and restores it on undo`() =
      runTest {
        val workout = insertWorkout("workout")
        val first = insertWorkoutExercise(workout, exercise("First"), position = 0)
        val sourceExercise = exercise("Source")
        val source = insertWorkoutExercise(workout, sourceExercise, position = 1)
        val last = insertWorkoutExercise(workout, exercise("Last"), position = 2)
        insertSet(first, 0, reps = 10)
        val setId = insertSet(source, 0, weightKg = 60.0, reps = 8)
        insertSet(source, 1, weightKg = 60.0, reps = 8)
        insertSet(last, 0, reps = 10)
        val replacement = exercise("New exercise without history")
        val replacementSyncId = requireNotNull(db.exerciseDao().getById(replacement)).syncId
        val before = workoutFull(workout).exercises.sortedBy { it.workoutExercise.position }
        val original = before[1]
        val editor =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val proposal =
            assertIsSaved(
                editor.saveModelProposalResult(
                    "user",
                    workout,
                    0,
                    listOf(
                        CoachChangeIntent.Replace(
                            original.workoutExercise.sectionId,
                            replacementSyncId,
                            original.sets.map { it.syncId },
                            25.0,
                        )
                    ),
                    Long.MAX_VALUE,
                )
            )
        assertEquals(
            before,
            workoutFull(workout).exercises.sortedBy { it.workoutExercise.position },
        )
        assertTrue(proposal.afterSummary.contains("Заменить «Source»"))
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", proposal.id, "replace-unstarted").result,
        )
        val after = workoutFull(workout).exercises.sortedBy { it.workoutExercise.position }
        assertEquals(
            listOf(
                before[0].workoutExercise.exerciseId,
                replacement,
                before[2].workoutExercise.exerciseId,
            ),
            after.map { it.workoutExercise.exerciseId },
        )
        assertEquals(listOf(0, 1, 2), after.map { it.workoutExercise.position })
        assertEquals(
            original.sets.map { it.syncId }.toSet(),
            after[1].sets.map { it.syncId }.toSet(),
        )
        assertEquals(2, after[1].sets.size)
        assertTrue(
            after[1].sets.all {
              it.weightKg == 25.0 && it.targetWeightKg == 25.0 && !it.isCompleted
            }
        )
        assertNotNull(db.workoutDao().getSet(setId))
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
        val undo =
            requireNotNull(
                editor.saveProposal(
                    "user",
                    workout,
                    WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast)),
                    1,
                    Long.MAX_VALUE,
                )
            )
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", undo.id, "undo-unstarted").result,
        )
        val restored = workoutFull(workout).exercises.sortedBy { it.workoutExercise.position }
        assertEquals(
            before.map { it.workoutExercise.sectionId },
            restored.map { it.workoutExercise.sectionId },
        )
        assertEquals(
            before.map { it.workoutExercise.exerciseId },
            restored.map { it.workoutExercise.exerciseId },
        )
        assertEquals(
            original.sets.map { it.syncId }.toSet(),
            restored[1].sets.map { it.syncId }.toSet(),
        )
        assertTrue(restored[1].sets.all { it.weightKg == 60.0 && it.reps == 8 })
      }

  @Test
  fun `replacement uses a resolved history weight and creates a distinct section`() = runTest {
    val oldExercise = exercise("Old")
    val replacement = exercise("New")
    val history = insertWorkout("history", finishedAt = 2_000)
    val historySection = insertWorkoutExercise(history, replacement)
    insertSet(historySection, 0, weightKg = 42.5, isCompleted = true)
    val workout = insertWorkout("workout")
    val source = insertWorkoutExercise(workout, oldExercise)
    insertSet(source, 0, weightKg = null, reps = 8, isCompleted = true)
    val unfinishedId = insertSet(source, 1, weightKg = null, reps = 8)
    val sourceRow = db.workoutDao().getWorkoutExercises(workout).single()
    val unfinished = db.workoutDao().getSet(unfinishedId)!!
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val packet =
        WorkoutChangeSet.Packet(
            listOf(
                WorkoutChangeSet.Operation.ReplaceRemaining(
                    sourceRow.sectionId,
                    "c2fd43d1-0603-4a80-95d9-81686e6d5eaf",
                    replacement,
                    listOf(unfinished.syncId),
                    42.5,
                )
            )
        )

    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workout, "replace", 0, packet, authority(packet)).result,
    )
    val sections = workoutFull(workout).exercises
    assertEquals(2, sections.size)
    assertTrue(
        sections.single { it.workoutExercise.exerciseId == oldExercise }.sets.single().isCompleted
    )
    val moved = sections.single { it.workoutExercise.exerciseId == replacement }.sets.single()
    assertEquals(42.5, moved.weightKg!!, 0.0)
    assertFalse(moved.isCompleted)
  }

  @Test
  fun `model replacement without history keeps source load out of the new strength sets`() =
      runTest {
        val sourceExercise = exercise("Source")
        val replacement = exercise("Replacement")
        val workout = insertWorkout("workout")
        val source = insertWorkoutExercise(workout, sourceExercise)
        insertSet(source, 0, weightKg = 80.0, reps = 8, isCompleted = true)
        val unfinishedId = insertSet(source, 1, weightKg = 60.0, reps = 6)
        val sourceRow = db.workoutDao().getWorkoutExercises(workout).single()
        val unfinished = requireNotNull(db.workoutDao().getSet(unfinishedId))
        val replacementSyncId = requireNotNull(db.exerciseDao().getById(replacement)).syncId
        val editor =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))

        val result =
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(
                    CoachChangeIntent.Replace(
                        sourceRow.sectionId,
                        replacementSyncId,
                        listOf(unfinished.syncId),
                        null,
                    )
                ),
                Long.MAX_VALUE,
            )

        val proposal = assertIsSaved(result)
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", proposal.id, "confirm-null-replacement").result,
        )
        val sections = workoutFull(workout).exercises
        assertTrue(
            sections
                .single { it.workoutExercise.exerciseId == sourceExercise }
                .sets
                .single()
                .isCompleted
        )
        val moved = sections.single { it.workoutExercise.exerciseId == replacement }.sets.single()
        assertEquals(unfinished.syncId, moved.syncId)
        assertFalse(moved.isCompleted)
        assertEquals(6, moved.reps)
        assertNull(moved.weightKg)
        assertNull(moved.originalWeightKg)
        assertNull(moved.targetWeightKg)
        assertNull(moved.actualWeightKg)
      }

  @Test
  fun `model replacement resolves its history but explicit weight wins`() = runTest {
    val sourceExercise = exercise("Source")
    val replacement = exercise("Replacement")
    val history = insertWorkout("history", finishedAt = 2_000)
    val historySection = insertWorkoutExercise(history, replacement)
    insertSet(historySection, 0, weightKg = 42.5, reps = 8, isCompleted = true)
    val workout = insertWorkout("workout")
    val source = insertWorkoutExercise(workout, sourceExercise)
    val unfinishedId = insertSet(source, 0, reps = 8)
    val sourceRow = db.workoutDao().getWorkoutExercises(workout).single()
    val unfinished = requireNotNull(db.workoutDao().getSet(unfinishedId))
    val replacementSyncId = requireNotNull(db.exerciseDao().getById(replacement)).syncId
    val editor =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))

    val fallback =
        assertIsSaved(
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(
                    CoachChangeIntent.Replace(
                        sourceRow.sectionId,
                        replacementSyncId,
                        listOf(unfinished.syncId),
                        null,
                    )
                ),
                Long.MAX_VALUE,
            )
        )
    assertEquals(42.5, requireNotNull(replacementWeight(fallback)), 0.0)
    assertTrue(editor.cancelProposal("user", fallback.id))

    val explicit =
        assertIsSaved(
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(
                    CoachChangeIntent.Replace(
                        sourceRow.sectionId,
                        replacementSyncId,
                        listOf(unfinished.syncId),
                        55.0,
                    )
                ),
                Long.MAX_VALUE,
            )
        )
    assertEquals(55.0, requireNotNull(replacementWeight(explicit)), 0.0)
  }

  @Test
  fun `model proposal distinguishes live revision conflict invalid intent and unavailable request`() =
      runTest {
        val exercise = exercise("Source")
        val workout = insertWorkout("workout")
        val section = insertWorkoutExercise(workout, exercise)
        val editor =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val sectionId = db.workoutDao().getWorkoutExercises(workout).single().sectionId

        assertEquals(
            ModelProposalSaveResult.Stale,
            editor.saveModelProposalResult(
                "user",
                workout,
                1,
                listOf(CoachChangeIntent.AddSet(sectionId)),
                Long.MAX_VALUE,
            ),
        )
        assertEquals(
            ModelProposalSaveResult.Invalid,
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(CoachChangeIntent.AddExercise("00000000-0000-0000-0000-000000000000")),
                Long.MAX_VALUE,
            ),
        )
        assertEquals(
            ModelProposalSaveResult.Unavailable,
            editor.saveModelProposalResult(
                "user",
                workout,
                0,
                listOf(CoachChangeIntent.AddSet(sectionId)),
                Long.MAX_VALUE,
            ) {
              false
            },
        )
        assertNull(db.coachDao().pendingProposal(workout))
        assertTrue(db.coachDao().pendingJournal("user", 100).isEmpty())
        assertEquals(0, tableCount("coach_command_receipts"))
      }

  @Test
  fun `replacement permits an explicitly weightless cardio exercise without inventing a load`() =
      runTest {
        val sourceExercise = exercise("Source")
        val cardio =
            db.exerciseDao()
                .insert(
                    ExerciseEntity(
                        name = "Кардио",
                        muscleGroup = MuscleGroup.CARDIO,
                        type = ExerciseType.CARDIO,
                    ),
                )
        val workout = insertWorkout("workout")
        val source = insertWorkoutExercise(workout, sourceExercise)
        val remainingId = insertSet(source, 0, reps = 8, durationSec = 300)
        val sourceRow = db.workoutDao().getWorkoutExercises(workout).single()
        val remaining = db.workoutDao().getSet(remainingId)!!
        val coordinator =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val packet =
            WorkoutChangeSet.Packet(
                listOf(
                    WorkoutChangeSet.Operation.ReplaceRemaining(
                        sourceRow.sectionId,
                        "b917e99d-6b03-4e16-96d8-a2f4a7599878",
                        cardio,
                        listOf(remaining.syncId),
                        null,
                    )
                )
            )

        assertEquals(
            CommandResult.APPLIED,
            coordinator.submit("user", workout, "cardio", 0, packet, authority(packet)).result,
        )
        val moved =
            workoutFull(workout)
                .exercises
                .single { it.workoutExercise.exerciseId == cardio }
                .sets
                .single()
        assertNull(moved.weightKg)
        assertNull(moved.reps)
        assertNull(moved.durationSec)
        assertNull(moved.targetReps)
        assertNull(moved.targetDurationSec)
      }

  @Test
  fun `snapshot exposes portable identities planned actual values context and rest`() = runTest {
    val workout = insertWorkout("workout", startedAt = 1_000)
    val exerciseId = exercise("Snapshot")
    val section = insertWorkoutExercise(workout, exerciseId)
    val setId = insertSet(section, 0, weightKg = 50.0, reps = 8, speedKmh = 5.0)
    val original = db.workoutDao().getSet(setId)!!
    db.workoutDao()
        .updateSet(
            original.copy(
                originalWeightKg = 45.0,
                targetWeightKg = 50.0,
                actualWeightKg = 47.5,
                reportedFeelingsJson = "[\"FATIGUE\"]",
            )
        )
    db.coachDao()
        .saveContext(
            CoachSessionContextEntity(
                workout,
                "user",
                availableTimeMinutes = 35,
                futureRestSeconds = 90,
            )
        )
    val timer = RestTimerEngine(backgroundScope, WallClock { 0L })
    timer.start(60)
    assertNotNull(timer.currentStartId())
    assertNotNull(timer.state.value)
    coordinator(timer)
    val service = CoachWorkoutReader(db, timer, session)

    val snapshot = service.snapshot("user", workout)!!

    assertEquals(35, snapshot.availableTimeMinutes)
    assertEquals(setOf("FATIGUE"), snapshot.feelings)
    assertEquals(original.syncId, snapshot.exercises.single().sets.single().syncId)
    assertEquals(45.0, snapshot.exercises.single().sets.single().originalWeightKg!!, 0.0)
    assertEquals(47.5, snapshot.exercises.single().sets.single().actualWeightKg!!, 0.0)
    assertTrue(snapshot.rest!!.remainingSeconds in 0..60)
  }

  @Test
  fun `snapshot uses the latest completion as previous anchor and carries a fresh pulse timestamp`() =
      runTest {
        val workout = insertWorkout("workout")
        val section = insertWorkoutExercise(workout, exercise())
        val latestCompletedId = insertSet(section, 0, reps = 8, isCompleted = true)
        val earlierCompletedId = insertSet(section, 1, reps = 8, isCompleted = true)
        val currentId = insertSet(section, 2, reps = 8)
        val latest = db.workoutDao().getSet(latestCompletedId)!!
        db.workoutDao().updateSet(latest.copy(completedAt = 300L))
        val earlier = db.workoutDao().getSet(earlierCompletedId)!!
        db.workoutDao().updateSet(earlier.copy(completedAt = 100L))
        val pulseAt = System.currentTimeMillis()
        val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
        coordinator(timer)
        val service =
            CoachWorkoutReader(
                db,
                timer,
                session,
                FakeHeartRateMonitor(HeartRateReading(132, pulseAt)),
            )

        val snapshot = service.snapshot("user", workout)!!

        assertEquals(latest.syncId, snapshot.previousSetId)
        assertEquals(db.workoutDao().getSet(currentId)!!.syncId, snapshot.currentSetId)
        assertEquals(132, snapshot.pulse!!.bpm)
        assertEquals(pulseAt, snapshot.pulse!!.measuredAtMillis)
      }

  @Test
  fun `snapshot and local parser skip completed sets when anchoring the next set`() = runTest {
    val workout = insertWorkout("workout")
    val section = insertWorkoutExercise(workout, exercise())
    val currentId = insertSet(section, 0, reps = 8)
    insertSet(section, 1, reps = 8, isCompleted = true)
    val nextId = insertSet(section, 2, reps = 8)
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    coordinator(timer)
    val snapshot = CoachWorkoutReader(db, timer, session).snapshot("user", workout)!!

    assertEquals(db.workoutDao().getSet(currentId)!!.syncId, snapshot.currentSetId)
    assertEquals(db.workoutDao().getSet(nextId)!!.syncId, snapshot.nextSetId)
    val packet =
        requireNotNull(
                LocalWorkoutCommandParser.parse(
                    "поставь в следующем подходе 6 повторений",
                    snapshot,
                )
            )
            .packet
    assertEquals(
        db.workoutDao().getSet(nextId)!!.syncId,
        (packet.operations.single() as WorkoutChangeSet.Operation.EditSet).setSyncId,
    )
  }

  @Test
  fun `correcting a completed result refreshes actual values without restarting rest`() = runTest {
    val workout = insertWorkout("workout")
    val section = insertWorkoutExercise(workout, exercise())
    val setId = insertSet(section, 0, reps = 8, isCompleted = true)
    val before = db.workoutDao().getSet(setId)!!
    db.workoutDao().updateSet(before.copy(completedAt = 123L, actualReps = 8))
    val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val timer = RestTimerEngine(timerScope, WallClock { 0L })
    try {
      timer.start(60)
      val startId = timer.currentStartId()
      val coordinator = coordinator(timer)
      val packet =
          WorkoutChangeSet.Packet(
              listOf(
                  WorkoutChangeSet.Operation.EditSet(before.syncId, reps = 6, recordResult = true)
              )
          )

      assertEquals(
          CommandResult.APPLIED,
          coordinator.submit("user", workout, "correct", 0, packet, authority(packet)).result,
      )

      val stored = db.workoutDao().getSet(setId)!!
      assertEquals(6, stored.reps)
      assertEquals(6, stored.actualReps)
      assertEquals(1, stored.coachMutationRevision)
      assertEquals(123L, stored.completedAt)
      assertTrue(stored.isCompleted)
      assertEquals(startId, timer.currentStartId())
    } finally {
      timerScope.cancel()
    }
  }

  @Test
  fun `composite replacement preview confirmation and undo preserve identities and full set data`() =
      runTest {
        val workout = insertWorkout("composite")
        val sourceId = insertWorkoutExercise(workout, exercise("Жим"))
        val source = db.workoutDao().getWorkoutExercises(workout).single()
        val doneId = insertSet(sourceId, 0, weightKg = 60.0, reps = 8, isCompleted = true)
        val nextId = insertSet(sourceId, 1, weightKg = 60.0, reps = 8)
        val done =
            db.workoutDao()
                .getSet(doneId)!!
                .copy(note = "результат", actualReps = 8, originalReps = 10)
        val next =
            db.workoutDao()
                .getSet(nextId)!!
                .copy(
                    note = "техника",
                    originalReps = 10,
                    targetReps = 8,
                    restSnapshotJson = "{\"seconds\":90}",
                    setType = "WORKING",
                )
        db.workoutDao().updateSet(done)
        db.workoutDao().updateSet(next)
        val cardio =
            db.exerciseDao()
                .insert(
                    ExerciseEntity(
                        name = "Дорожка",
                        type = ExerciseType.CARDIO,
                        muscleGroup = MuscleGroup.CHEST,
                    )
                )
        val timer = RestTimerEngine(backgroundScope) { 0L }
        val editor = coordinator(timer)
        val packet =
            WorkoutChangeSet.Packet(
                listOf(
                    WorkoutChangeSet.Operation.EditSet(
                        next.syncId,
                        clearFields = setOf("weight_kg", "reps"),
                    ),
                    WorkoutChangeSet.Operation.ReplaceRemaining(
                        source.sectionId,
                        "replacement",
                        cardio,
                        listOf(next.syncId),
                        null,
                    ),
                    WorkoutChangeSet.Operation.EditSet(
                        next.syncId,
                        durationSec = 120,
                        speedKmh = 6.0,
                    ),
                    WorkoutChangeSet.Operation.ReportFeelings(next.syncId, setOf("FATIGUE")),
                    WorkoutChangeSet.Operation.AddSet("replacement"),
                )
            )
        val before = workoutFull(workout)
        val proposal =
            requireNotNull(editor.saveProposal("user", workout, packet, 0, Long.MAX_VALUE))
        assertEquals(before, workoutFull(workout))
        assertNull(timer.state.value)
        assertTrue(proposal.afterSummary.contains("120 с · 6 км/ч"))
        assertFalse(proposal.afterSummary.substringAfter("Шаг 2:").contains("8 повт."))
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", proposal.id, "apply-composite").result,
        )
        val moved = db.workoutDao().getSet(nextId)!!
        assertEquals(next.syncId, moved.syncId)
        assertEquals(next.note, moved.note)
        assertEquals(next.restSnapshotJson, moved.restSnapshotJson)
        assertEquals(next.setType, moved.setType)
        assertEquals(120, moved.targetDurationSec)
        assertNull(moved.originalReps)
        assertNull(moved.actualDurationSec)
        assertEquals(done, db.workoutDao().getSet(doneId))
        assertEquals(
            2,
            workoutFull(workout)
                .exercises
                .single { it.workoutExercise.sectionId == "replacement" }
                .sets
                .size,
        )
        val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
        val undoProposal =
            requireNotNull(editor.saveProposal("user", workout, undo, 1, Long.MAX_VALUE))
        assertTrue(undoProposal.afterSummary.contains("8 повт. · 60 кг"))
        assertEquals(
            CommandResult.APPLIED,
            editor.confirmProposal("user", undoProposal.id, "undo-composite").result,
        )
        assertEquals(source, db.workoutDao().getWorkoutExercises(workout).single())
        assertEquals(next.copy(coachMutationRevision = 2), db.workoutDao().getSet(nextId))
        assertEquals(done, db.workoutDao().getSet(doneId))
      }

  @Test
  fun `manual transform waiting behind confirmation reads the confirmed value`() = runTest {
    val workout = insertWorkout("race")
    val section = insertWorkoutExercise(workout, exercise())
    val setId = insertSet(section, 0, reps = 8)
    val set = db.workoutDao().getSet(setId)!!
    val timer = RestTimerEngine(backgroundScope) { 0L }
    val writes = WorkoutWriteQueue()
    val editor = coordinator(timer, writes)
    val repository =
        com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl(
            db,
            db.workoutDao(),
            db.routineDao(),
            writes = writes,
        )
    val packet =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 6)))
    val proposal = requireNotNull(editor.saveProposal("user", workout, packet, 0, Long.MAX_VALUE))
    lateinit var confirm: kotlinx.coroutines.Deferred<CommandReceipt>
    lateinit var manual: kotlinx.coroutines.Deferred<Boolean>
    writes.write {
      confirm =
          async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            editor.confirmProposal("user", proposal.id, "confirm")
          }
      manual =
          async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repository.mutateSet(setId) { it.copy(reps = it.reps!! + 1) }
          }
      assertEquals(8, db.workoutDao().getSet(setId)!!.reps)
    }
    assertEquals(CommandResult.APPLIED, confirm.await().result)
    assertTrue(manual.await())
    assertEquals(7, db.workoutDao().getSet(setId)!!.reps)
    assertEquals(2L, workoutFull(workout).workout.coachRevision)
  }

  @Test
  fun `completion queued behind workout finish cannot start rest or modify the finished set`() =
      runTest {
        val setId = seedActiveSet()
        val workout = db.workoutDao().getActiveWorkoutId()!!
        val writes = WorkoutWriteQueue()
        val timer = RestTimerEngine(backgroundScope) { 0L }
        val settings = settingsWithRest(90)
        val editor =
            WorkoutEditor(
                db,
                db.workoutDao(),
                db.coachDao(),
                timer,
                session,
                writes,
                RestDurationResolver(db.routineDao(), settings),
                settings,
            )
        val repository =
            com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl(
                db,
                db.workoutDao(),
                db.routineDao(),
                writes = writes,
            )
        lateinit var finish: kotlinx.coroutines.Deferred<Unit>
        lateinit var complete: kotlinx.coroutines.Deferred<Unit>
        writes.write {
          finish =
              async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                repository.finish(workout)
              }
          complete =
              async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                editor.completeSetFromUser(setId)
              }
        }
        finish.await()
        complete.await()
        assertNotNull(workoutFull(workout).workout.finishedAt)
        assertNull(timer.state.value)
        assertTrue(db.workoutDao().getSet(setId)?.isCompleted != true)
      }

  @Test
  fun `completion does not replace rest started while it waits for the workout lock`() = runTest {
    val setId = seedActiveSet()
    val writes = WorkoutWriteQueue()
    val timer = RestTimerEngine(backgroundScope) { 0L }
    val settings = settingsWithRest(90)
    val editor =
        WorkoutEditor(
            db,
            db.workoutDao(),
            db.coachDao(),
            timer,
            session,
            writes,
            RestDurationResolver(db.routineDao(), settings),
            settings,
        )
    lateinit var complete: kotlinx.coroutines.Deferred<Unit>
    var startId: String? = null
    writes.write {
      complete =
          async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            editor.completeSetFromUser(setId)
          }
      timer.start(30)
      startId = timer.currentStartId()
    }
    complete.await()
    assertTrue(db.workoutDao().getSet(setId)!!.isCompleted)
    assertEquals(startId, timer.currentStartId())
    assertEquals(30, (timer.state.value as RestTimerState.Timed).totalSec)
  }

  @Test
  fun `proposal invalidated after journal preparation rolls back all durable rows`() = runTest {
    val workout = insertWorkout("stale-proposal")
    val editor = coordinator(RestTimerEngine(backgroundScope) { 0L })
    var checks = 0
    assertNull(
        editor.saveProposal(
            "user",
            workout,
            WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.SetAvailableTime(20))),
            0,
            Long.MAX_VALUE,
            isCurrent = { ++checks < 4 },
        )
    )
    assertNull(db.coachDao().pendingProposal(workout))
    assertTrue(db.coachDao().pendingJournal("user", 100).isEmpty())
    assertEquals(0L, workoutFull(workout).workout.coachRevision)
  }

  private val session = FakeSession()

  private fun coordinator(
      timer: RestTimerEngine,
      writes: WorkoutWriteQueue = WorkoutWriteQueue(),
  ): WorkoutEditor {
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)",
    )
    return WorkoutEditor(
        db,
        db.workoutDao(),
        db.coachDao(),
        timer,
        session,
        writes,
    )
  }

  private fun completionEditor(timer: RestTimerEngine, settings: SettingsRepository) =
      WorkoutEditor(
          db,
          db.workoutDao(),
          db.coachDao(),
          timer,
          session,
          WorkoutWriteQueue(),
          RestDurationResolver(db.routineDao(), settings),
          settings,
      )

  private suspend fun seedActiveSet(): Long {
    val workout = insertWorkout("active", startedAt = 1_000)
    val workoutExercise = insertWorkoutExercise(workout, exercise("Active"))
    return insertSet(workoutExercise, 0, reps = 8).also { insertSet(workoutExercise, 1, reps = 8) }
  }

  private suspend fun seedFinishedSet(): Long {
    val workout = insertWorkout("finished", startedAt = 1_000, finishedAt = 2_000)
    return insertSet(insertWorkoutExercise(workout, exercise("Finished")), 0, reps = 8)
  }

  private fun settingsWithRest(seconds: Int) =
      SettingsRepository(
          FakeDataStore(mutablePreferencesOf(intPreferencesKey("default_rest_seconds") to seconds)),
      )

  private fun authority(packet: WorkoutChangeSet.Packet) =
      CommandAuthority.local(packet, CommandAuthority.Anchors(null, null, null))

  private fun assertIsSaved(result: ModelProposalSaveResult): WorkoutProposal {
    assertTrue(result is ModelProposalSaveResult.Saved)
    return (result as ModelProposalSaveResult.Saved).proposal
  }

  private fun replacementWeight(proposal: WorkoutProposal): Double? =
      (proposal.packet.operations.single() as WorkoutChangeSet.Operation.ReplaceRemaining)
          .replacementWeightKg

  private suspend fun exercise(
      name: String = "Exercise",
      type: ExerciseType = ExerciseType.STRENGTH,
  ): Long =
      db.exerciseDao()
          .insert(ExerciseEntity(name = name, muscleGroup = MuscleGroup.CHEST, type = type))

  private class FakeSession : BackendSessionStore {
    private val state =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )
    override val session: StateFlow<BackendTokens?> = state

    override fun save(tokens: BackendTokens?) {
      state.value = tokens
    }
  }

  private class FakeHeartRateMonitor(reading: HeartRateReading?) : HeartRateMonitor {
    override val state: StateFlow<HeartRateConnectionState> =
        MutableStateFlow(HeartRateConnectionState.Idle)
    override val reading: StateFlow<HeartRateReading?> = MutableStateFlow(reading)

    override fun scan() = Unit

    override fun connect(device: HeartRateDevice) = Unit

    override fun stop() = Unit

    override fun reportError(message: String) = Unit
  }

  private class FakeDataStore(prefs: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(prefs)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = transform(state.value).also { state.value = it }
  }
}
