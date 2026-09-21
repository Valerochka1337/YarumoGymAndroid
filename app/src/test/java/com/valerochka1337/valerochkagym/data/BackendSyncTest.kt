package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.db.LegacyCoachArchiveRegistry
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackendSyncTest : RoomDaoTest() {
  private val raw
    get() = db.openHelper.writableDatabase

  private fun exercise(id: String = "00000000-0000-0000-0000-000000000001") =
      ExerciseEntity(
          name = "Тест",
          muscleGroup = MuscleGroup.LEGS,
          type = ExerciseType.STRENGTH,
          isCustom = true,
          syncId = id,
          updatedAt = 1,
          equipmentRequirementState = EquipmentRequirementState.KNOWN,
      )

  @Test
  fun `v2 accent tombstone preserves marker baseline and retained request`() = runTest {
    val owner = "user-a"
    val exerciseId = "11111111-1111-1111-1111-111111111111"
    val recordId =
        UUID.nameUUIDFromBytes(
                "ValerochkaGym.planner-default-accents.v2:$owner".encodeToByteArray()
            )
            .toString()
    val key = "planner_exercise_accents:$recordId"
    SyncSchema.install(raw)
    val server = Server().apply { accepted = setOf("planner-default-accents-v2") }
    val sync = BackendSync(db, server, Store())
    sync.claim(owner)
    raw.execSQL("INSERT INTO planner_exercise_accent_markers(scope) VALUES(?)", arrayOf(owner))
    raw.execSQL(
        "INSERT INTO planner_exercise_accents_v2(scope,exerciseSyncId,preference) VALUES(?,?,?)",
        arrayOf(owner, exerciseId, "NORMAL"),
    )
    sync.run()
    raw.execSQL(
        "UPDATE planner_exercise_accents_v2 SET preference='LESS' WHERE scope=?",
        arrayOf(owner),
    )
    server.failBeforeCommit = true
    try {
      sync.run()
      fail("The local v2 update must remain retained")
    } catch (_: IOException) {}
    val outbox =
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          it.getString(0)
        }
    val baseline =
        raw.query("SELECT recordJson FROM backend_baseline WHERE `key`=?", arrayOf(key)).use {
          assertTrue(it.moveToFirst())
          it.getString(0)
        }
    server.revision++
    server.records[key] =
        CloudRecord("planner_exercise_accents", recordId, server.revision, deleted = true)

    val error = runCatching { sync.run() }.exceptionOrNull() as BackendException
    assertEquals("planner_accent_tombstone_invalid", error.code)
    assertTrue(db.plannerExerciseAccentV2Dao().hasMarker(owner))
    assertEquals("LESS", db.plannerExerciseAccentV2Dao().get(owner).single().preference.name)
    assertEquals(
        baseline,
        raw.query("SELECT recordJson FROM backend_baseline WHERE `key`=?", arrayOf(key)).use {
          assertTrue(it.moveToFirst())
          it.getString(0)
        },
    )
    assertEquals(
        outbox,
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          it.getString(0)
        },
    )
  }

  @Test
  fun `v2 accent request stays byte exact without capability and legacy import cannot change it`() =
      runTest {
        val owner = "user-a"
        val exerciseId = "11111111-1111-1111-1111-111111111111"
        val v2Id =
            UUID.nameUUIDFromBytes(
                    "ValerochkaGym.planner-default-accents.v2:$owner".encodeToByteArray()
                )
                .toString()
        val legacyId =
            UUID.nameUUIDFromBytes(
                    "ValerochkaGym.planner-exercise-preferences.v1:$owner".encodeToByteArray()
                )
                .toString()
        SyncSchema.install(raw)
        val server = Server().apply { accepted = setOf("ai-planner-agentic-v1") }
        server.records["planner_exercise_preferences:$legacyId"] =
            CloudRecord(
                "planner_exercise_preferences",
                legacyId,
                1,
                false,
                buildJsonObject {
                  put("schemaVersion", 1)
                  put(
                      "preferences",
                      JsonArray(
                          listOf(
                              buildJsonObject {
                                put("exerciseId", exerciseId)
                                put("preference", "NEVER")
                              }
                          )
                      ),
                  )
                },
            )
        server.revision = 1
        val sync = BackendSync(db, server, Store())
        sync.claim(owner)
        raw.execSQL("INSERT INTO planner_exercise_accent_markers(scope) VALUES(?)", arrayOf(owner))
        raw.execSQL(
            "INSERT INTO planner_exercise_accents_v2(scope,exerciseSyncId,preference) VALUES(?,?,?)",
            arrayOf(owner, exerciseId, "NORMAL"),
        )

        sync.run()
        val exact =
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              assertTrue(it.moveToFirst())
              it.getString(0)
            }
        sync.run()

        assertEquals(
            exact,
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              assertTrue(it.moveToFirst())
              it.getString(0)
            },
        )
        assertEquals("NORMAL", db.plannerExerciseAccentV2Dao().get(owner).single().preference.name)
        assertTrue(
            db.plannerExercisePreferenceDao().get(owner).any {
              it.preference == PlannerExercisePreference.NEVER
            }
        )
        assertFalse(server.records.containsKey("planner_exercise_accents:$v2Id"))
      }

  @Test
  fun `clearing synced planner preferences sends their aggregate tombstone`() = runTest {
    val owner = "user-a"
    val exerciseId = "00000000-0000-4000-8000-000000000001"
    val recordId =
        UUID.nameUUIDFromBytes(
                "ValerochkaGym.planner-exercise-preferences.v1:$owner".encodeToByteArray()
            )
            .toString()
    val key = "planner_exercise_preferences:$recordId"
    SyncSchema.install(raw)
    val server = Server().apply { accepted = setOf("ai-planner-agentic-v1") }
    val sync = BackendSync(db, server, Store())
    sync.claim(owner)
    raw.execSQL(
        "INSERT INTO planner_exercise_preferences(scope,exerciseSyncId,preference) VALUES(?,?,?)",
        arrayOf(owner, exerciseId, "MORE"),
    )

    sync.run()
    assertFalse(requireNotNull(server.records[key]).deleted)

    raw.execSQL("DELETE FROM planner_exercise_preferences WHERE scope=?", arrayOf(owner))
    sync.run()

    assertTrue(requireNotNull(server.records[key]).deleted)
  }

  @Test
  fun `account replacement purges retained legacy coach archives`() = runTest {
    SyncSchema.install(raw)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    sync.run()
    LegacyCoachArchiveRegistry.archiveTableNames().forEachIndexed { index, table ->
      raw.execSQL("CREATE TABLE `$table` (id INTEGER PRIMARY KEY)")
      raw.execSQL("INSERT INTO `$table` VALUES(?)", arrayOf(index))
    }

    sync.claim("user-b")

    LegacyCoachArchiveRegistry.archiveTableNames().forEach { table ->
      raw.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
        assertFalse(it.moveToFirst())
      }
    }
  }

  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user-a", "a@example.com", "access", "refresh")
        )
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }
  }

  private object PendingGate : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean = false
  }

  private fun Server.addSharedRoutine(): String {
    val id = "00000000-0000-0000-0000-000000000777"
    revision++
    val record =
        CloudRecord(
            "routine",
            id,
            revision,
            false,
            buildJsonObject {
              put("name", "Полученная программа")
              put("note", "")
              put("updatedAt", 1)
              put("gymIds", JsonArray(emptyList()))
              put("exercises", JsonArray(emptyList()))
            },
        )
    records[record.key] = record
    return id
  }

  @Test
  fun `share projection replays without duplication or uploading pending local changes`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val expected = requireNotNull(store.snapshot())
        val local = db.routineDao().upsertRoutine(RoutineEntity(name = "Несохранённая в облаке"))
        val importedId = server.addSharedRoutine()
        val posts = server.postAttempts

        sync.applyImportedRoutine(expected, importedId, server.revision)
        val first = requireNotNull(db.routineDao().getRoutineBySyncId(importedId))
        sync.applyImportedRoutine(expected, importedId, server.revision)

        assertEquals(first.id, db.routineDao().getRoutineBySyncId(importedId)?.id)
        assertEquals(2, tableCount("routines"))
        assertEquals(0, tableCount("routine_gyms"))
        assertEquals(posts, server.postAttempts)
        assertEquals("PERSONAL", first.origin)
        assertEquals("", first.note)
        raw.query("SELECT name FROM routines WHERE id=?", arrayOf(local)).use {
          assertTrue(it.moveToFirst())
          assertEquals("Несохранённая в облаке", it.getString(0))
        }
      }

  @Test
  fun `share projection rejects a session revoked while the snapshot loads`() = runTest {
    SyncSchema.install(raw)
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    sync.run()
    val expected = requireNotNull(store.snapshot())
    val importedId = server.addSharedRoutine()
    server.beforeGet = { store.save(null) }

    try {
      sync.applyImportedRoutine(expected, importedId, server.revision)
      fail("A revoked session must not apply the returned routine")
    } catch (_: BackendException) {}

    assertNull(db.routineDao().getRoutineBySyncId(importedId))
  }

  @Test
  fun `share projection waits for the acknowledged server revision`() = runTest {
    SyncSchema.install(raw)
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    sync.run()
    val importedId = server.addSharedRoutine()

    try {
      sync.applyImportedRoutine(requireNotNull(store.snapshot()), importedId, server.revision + 1)
      fail("An older snapshot must not confirm the import")
    } catch (error: BackendException) {
      assertEquals("routine_share_pending", error.code)
    }

    assertNull(db.routineDao().getRoutineBySyncId(importedId))
  }

  private class Server : BackendTransport {
    override val json = Json { encodeDefaults = true }
    var accepted: Set<String> = emptySet()
    var lastRawHeaders: Map<String, String> = emptyMap()
    override val acceptedCapabilities: Set<String>
      get() = accepted

    val records = linkedMapOf<String, CloudRecord>()
    val operations = mutableMapOf<String, Pair<CloudPush, CloudAck>>()
    val sentBodies = mutableListOf<JsonObject>()
    var revision = 0L
    var loseNextResponse = false
    var failBeforeCommit = false
    var catalog = StandardSnapshot(false, 0, emptyList(), emptyList())
    var catalogReads = 0
    var afterCommit: (suspend () -> Unit)? = null
    var beforePost: (suspend () -> Unit)? = null
    var beforeGet: (suspend () -> Unit)? = null
    var onGet: (suspend (Int) -> Unit)? = null
    var beforeCatalog: (suspend () -> Unit)? = null
    var getReads = 0
    var postAttempts = 0
    val rawPosts = mutableListOf<ByteArray>()

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        json.encodeToJsonElement(catalog).also {
          check(path == "/catalog")
          catalogReads++
          beforeCatalog?.also {
            beforeCatalog = null
            it()
          }
        }

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement {
      if (path.startsWith("/coach/journal"))
          return json.encodeToJsonElement(CoachJournalPage(emptyList(), watermark = 0))
      if (method == "GET") {
        getReads++
        onGet?.invoke(getReads)
        beforeGet?.also {
          beforeGet = null
          it()
        }
        return json.encodeToJsonElement(CloudSnapshot(revision, records.values.toList()))
      }
      if (failBeforeCommit) {
        failBeforeCommit = false
        throw IOException("Offline before commit")
      }
      sentBodies += body!!.jsonObject
      val push = json.decodeFromJsonElement<CloudPush>(body)
      postAttempts++
      beforePost?.also {
        beforePost = null
        it()
      }
      check(push.changes.none { c -> catalog.records.any { it.id == c.id } })
      val old = operations[push.operationId]
      if (old != null) {
        check(old.first == push)
        return json.encodeToJsonElement(old.second)
      }
      push.changes.forEach {
        if ((records["${it.kind}:${it.id}"]?.revision ?: 0) != it.baseRevision)
            throw BackendException(409, "revision_conflict", "Conflict")
      }
      revision++
      push.changes.forEach {
        val r = CloudRecord(it.kind, it.id, revision, it.deleted, it.payload)
        records[r.key] = r
      }
      val ack = CloudAck(revision)
      operations[push.operationId] = push to ack
      afterCommit?.also {
        afterCommit = null
        it()
      }
      if (loseNextResponse) {
        loseNextResponse = false
        throw IOException("Response lost after commit")
      }
      return json.encodeToJsonElement(ack)
    }

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      lastRawHeaders = headers
      if (method == "POST") rawPosts += rawBody
      return BackendResponse(
          body =
              authorized(
                  method,
                  path,
                  rawBody
                      .takeIf { it.isNotEmpty() }
                      ?.let { json.parseToJsonElement(it.decodeToString()) },
              ),
          rawBody = byteArrayOf(),
          acceptedCapabilities = accepted,
          owner = expectedOwner,
          sessionEpoch = expectedSessionEpoch ?: 0L,
      )
    }
  }

  private suspend fun finishedWorkoutSet(id: String, note: String): Long {
    val exerciseId =
        db.exerciseDao().insert(exercise("00000000-0000-0000-0000-00000000${id.takeLast(4)}"))
    insertWorkout(id, finishedAt = 2)
    val section = insertWorkoutExercise(id, exerciseId)
    val setId = insertSet(section, 0)
    db.workoutDao().updateSet(db.workoutDao().getSet(setId)!!.copy(note = note))
    return setId
  }

  private fun JsonObject.singleWorkoutSet(): JsonObject =
      getValue("exercises")
          .jsonArray
          .single()
          .jsonObject
          .getValue("sets")
          .jsonArray
          .single()
          .jsonObject

  @Test
  fun `server without RIR capability receives legacy workout while local RIR survives remote updates`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server().apply { accepted = setOf("annotated-workout-writes") }
        val sync = BackendSync(db, server, Store())
        val workoutId = "rir-legacy-server"
        val setId = finishedWorkoutSet(workoutId, "")
        db.workoutDao()
            .updateSet(
                requireNotNull(db.workoutDao().getSet(setId))
                    .copy(actualRir = 2, legacyTargetRir = 3, actualRirAtLeastFour = false)
            )
        sync.claim("user-a")

        sync.run()

        val uploaded = requireNotNull(server.records["workout:$workoutId"]?.payload)
        assertTrue(
            uploaded.singleWorkoutSet().keys.none {
              it in setOf("targetRir", "actualRir", "actualRirAtLeastFour")
            }
        )
        assertEquals(2, db.workoutDao().getSet(setId)?.actualRir)
        server.revision++
        server.records["workout:$workoutId"] =
            requireNotNull(server.records["workout:$workoutId"])
                .copy(
                    revision = server.revision,
                    payload =
                        JsonObject(uploaded + ("name" to JsonPrimitive("Обновлено на сервере"))),
                )

        sync.run()

        val restored = workoutFull(workoutId).exercises.single().sets.single()
        assertEquals(2, restored.actualRir)
        assertEquals(3, restored.legacyTargetRir)
        assertFalse(restored.actualRirAtLeastFour)
      }

  @Test
  fun `negotiated RIR capability sends four plus workout RIR`() = runTest {
    SyncSchema.install(raw)
    val server = Server().apply { accepted = setOf("annotated-workout-writes", "workout-rir-v1") }
    val sync = BackendSync(db, server, Store())
    val workoutId = "rir-current-server"
    val setId = finishedWorkoutSet(workoutId, "")
    db.workoutDao()
        .updateSet(
            requireNotNull(db.workoutDao().getSet(setId))
                .copy(actualRir = null, legacyTargetRir = 3, actualRirAtLeastFour = true)
        )
    sync.claim("user-a")

    sync.run()

    val uploaded = requireNotNull(server.records["workout:$workoutId"]?.payload).singleWorkoutSet()
    assertEquals(3, uploaded["targetRir"]?.jsonPrimitive?.int)
    assertEquals(JsonNull, uploaded["actualRir"])
    assertTrue(requireNotNull(uploaded["actualRirAtLeastFour"]).jsonPrimitive.boolean)
  }

  @Test
  fun `retained workout from 1_3_70 is reissued without RIR for a legacy server`() = runTest {
    SyncSchema.install(raw)
    val server = Server().apply { accepted = setOf("annotated-workout-writes") }
    val sync = BackendSync(db, server, Store())
    val workoutId = "rir-retained"
    val setId = finishedWorkoutSet(workoutId, "")
    db.workoutDao()
        .updateSet(
            requireNotNull(db.workoutDao().getSet(setId))
                .copy(actualRir = null, actualRirAtLeastFour = true)
        )
    sync.claim("user-a")
    val original =
        CloudPush(
            "rir-incompatible-operation",
            listOf(
                CloudChange(
                    "workout",
                    workoutId,
                    0,
                    payload = PortableData(raw).snapshot().getValue("workout:$workoutId"),
                )
            ),
        )
    val originalBytes = Json.encodeToString(original)
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(originalBytes),
    )

    server.failBeforeCommit = true
    try {
      sync.run()
      fail("A failed compatibility dispatch must retain the exact durable request")
    } catch (_: IOException) {}
    raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
      assertTrue(it.moveToFirst())
      assertEquals(originalBytes, it.getString(0))
    }
    assertTrue(
        server.rawPosts
            .single()
            .decodeToString()
            .let(Json::parseToJsonElement)
            .jsonObject
            .getValue("changes")
            .jsonArray
            .single()
            .jsonObject
            .getValue("payload")
            .jsonObject
            .singleWorkoutSet()
            .keys
            .none { it in setOf("targetRir", "actualRir", "actualRirAtLeastFour") }
    )

    sync.run()

    val posted =
        server.operations.values
            .map { it.first }
            .single { push -> push.changes.any { it.kind == "workout" && it.id == workoutId } }
    assertNotEquals(original.operationId, posted.operationId)
    assertTrue(
        requireNotNull(posted.changes.single().payload).singleWorkoutSet().keys.none {
          it in setOf("targetRir", "actualRir", "actualRirAtLeastFour")
        }
    )
    assertEquals(0, tableCount("backend_outbox"))
    assertTrue(workoutFull(workoutId).exercises.single().sets.single().actualRirAtLeastFour)
  }

  private suspend fun baselineWorkout(
      server: Server,
      sync: BackendSync,
      id: String,
      note: String,
  ): Long {
    SyncSchema.install(raw)
    val setId = finishedWorkoutSet(id, note)
    sync.claim("user-a")
    sync.run()
    return setId
  }

  private suspend fun baselineHint(
      server: Server,
      sync: BackendSync,
      syncId: String,
      text: String,
  ): Long {
    SyncSchema.install(raw)
    val exerciseId = db.exerciseDao().insert(exercise(syncId))
    db.exercisePersonalHintDao().upsert(ExercisePersonalHintEntity(syncId, text, 1))
    sync.claim("user-a")
    sync.run()
    return exerciseId
  }

  private fun profileId(owner: String = "user-a"): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.profile.v1:$owner".encodeToByteArray()).toString()

  private suspend fun baselineProfile(server: Server, sync: BackendSync): String {
    SyncSchema.install(raw)
    val id = profileId()
    val equipment = LocalEquipmentCatalog.entries.take(2).map { it.id }
    check(equipment.size == 2)
    sync.claim("user-a")
    db.profileDao()
        .upsert(
            ProfileEntity(
                scope = "user-a",
                syncId = id,
                trainingGoal = "STRENGTH",
                updatedAt = 1,
            ),
        )
    db.profileDao()
        .upsertEquipment(equipment.map { ProfileEquipmentPreferenceEntity("user-a", it) })
    sync.run()
    return id
  }

  private fun hasSetNote(payload: JsonObject): Boolean =
      payload["exercises"]?.jsonArray.orEmpty().any { section ->
        section.jsonObject["sets"]?.jsonArray.orEmpty().any { "note" in it.jsonObject }
      }

  @Test
  fun `clean profile survives a capability downgrade and accepts a later full server refresh`() =
      runTest {
        val server = Server().apply { accepted = setOf("profile") }
        val sync = BackendSync(db, server, Store())
        val id = baselineProfile(server, sync)
        val posts = server.postAttempts
        val baseline =
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("profile:$id"),
                )
                .use {
                  assertTrue(it.moveToFirst())
                  it.getString(0)
                }

        server.accepted = emptySet()
        sync.run()
        assertEquals(posts, server.postAttempts)
        assertEquals("STRENGTH", db.profileDao().get("user-a")?.trainingGoal)
        assertEquals(2, db.profileDao().equipmentIds("user-a").size)
        assertEquals(
            baseline,
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("profile:$id"),
                )
                .use {
                  assertTrue(it.moveToFirst())
                  it.getString(0)
                },
        )

        val remote = requireNotNull(server.records["profile:$id"])
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload =
                    JsonObject(
                        requireNotNull(remote.payload) +
                            ("trainingGoal" to JsonPrimitive("ENDURANCE"))
                    ),
            )
        server.accepted = setOf("profile")
        sync.run()

        assertEquals("ENDURANCE", db.profileDao().get("user-a")?.trainingGoal)
        assertEquals(2, db.profileDao().equipmentIds("user-a").size)
      }

  @Test
  fun `profile tombstone is rejected before absent capability can alter local rows or durable bytes`() =
      runTest {
        val server = Server().apply { accepted = setOf("profile") }
        val sync = BackendSync(db, server, Store())
        val id = baselineProfile(server, sync)
        db.profileDao()
            .upsert(
                requireNotNull(db.profileDao().get("user-a"))
                    .copy(trainingGoal = "OTHER", updatedAt = 2)
            )
        server.failBeforeCommit = true
        try {
          sync.run()
          fail("Offline dispatch must retain the profile request")
        } catch (_: IOException) {}
        val outbox =
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              assertTrue(it.moveToFirst())
              it.getString(0)
            }
        val baseline =
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("profile:$id"),
                )
                .use {
                  assertTrue(it.moveToFirst())
                  it.getString(0)
                }
        server.revision++
        server.records["profile:$id"] = CloudRecord("profile", id, server.revision, deleted = true)

        listOf(setOf("profile"), emptySet()).forEach { accepted ->
          server.accepted = accepted
          val error = runCatching { sync.run() }.exceptionOrNull() as BackendException

          assertEquals("profile_tombstone_invalid", error.code)
          assertEquals("OTHER", db.profileDao().get("user-a")?.trainingGoal)
          assertEquals(2, db.profileDao().equipmentIds("user-a").size)
          assertEquals(
              baseline,
              raw.query(
                      "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                      arrayOf("profile:$id"),
                  )
                  .use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                  },
          )
          assertEquals(
              outbox,
              raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
                assertTrue(it.moveToFirst())
                it.getString(0)
              },
          )
        }
      }

  @Test
  fun `remote profile with an unknown field is rejected before baseline or retained bytes change`() =
      runTest {
        val server = Server().apply { accepted = setOf("profile") }
        val sync = BackendSync(db, server, Store())
        val id = baselineProfile(server, sync)
        val payload = PortableData(raw).snapshot().getValue("profile:$id")
        val exact =
            Json.encodeToString(
                CloudPush(
                    "invalid-profile-shape-pending",
                    listOf(CloudChange("profile", id, 1, false, payload)),
                ),
            )
        raw.execSQL(
            "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
            arrayOf(exact),
        )
        val baseline =
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("profile:$id"),
                )
                .use {
                  assertTrue(it.moveToFirst())
                  it.getString(0)
                }
        val posts = server.postAttempts
        val remote = requireNotNull(server.records["profile:$id"])
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload =
                    JsonObject(requireNotNull(remote.payload) + ("unexpected" to JsonPrimitive(1))),
            )

        val error = runCatching { sync.run() }.exceptionOrNull() as BackendException

        assertEquals("profile_payload_invalid", error.code)
        assertEquals(posts, server.postAttempts)
        assertEquals("STRENGTH", db.profileDao().get("user-a")?.trainingGoal)
        assertEquals(2, db.profileDao().equipmentIds("user-a").size)
        assertEquals(
            baseline,
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("profile:$id"),
                )
                .use {
                  assertTrue(it.moveToFirst())
                  it.getString(0)
                },
        )
        assertEquals(
            exact,
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              assertTrue(it.moveToFirst())
              it.getString(0)
            },
        )
      }

  @Test
  fun `dirty profile stays local without creating an outbox while capability is unavailable`() =
      runTest {
        val server = Server().apply { accepted = setOf("profile") }
        val sync = BackendSync(db, server, Store())
        baselineProfile(server, sync)
        val posts = server.postAttempts
        db.profileDao()
            .upsert(
                requireNotNull(db.profileDao().get("user-a"))
                    .copy(trainingGoal = "OTHER", updatedAt = 2)
            )

        server.accepted = emptySet()
        db.bodyMeasurementDao().insert(BodyMeasurementEntity("compatible-profile-downgrade", 3))
        sync.run()

        assertEquals(posts + 1, server.postAttempts)
        assertEquals(0, tableCount("backend_outbox"))
        assertEquals("OTHER", db.profileDao().get("user-a")?.trainingGoal)
        assertEquals(2, db.profileDao().equipmentIds("user-a").size)
        val sent = server.operations.values.last().first.changes
        assertTrue(sent.any { it.kind == "measurement" })
        assertTrue(sent.none { it.kind == "profile" })
      }

  @Test
  fun `profile outbox bytes remain immutable while profile capability is unavailable`() = runTest {
    val server = Server().apply { accepted = setOf("profile") }
    val sync = BackendSync(db, server, Store())
    val id = baselineProfile(server, sync)
    server.rawPosts.clear()
    val payload = PortableData(raw).snapshot().getValue("profile:$id")
    val exact =
        "{ \"operationId\" : \"profile-whitespace\", \"changes\" : [ ${Json.encodeToString(CloudChange("profile", id, 1, false, payload))} ], \"catalogRevision\" : null }"
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(exact),
    )

    server.accepted = emptySet()
    db.bodyMeasurementDao().insert(BodyMeasurementEntity("queued-after-profile", 2))
    sync.run()
    assertTrue(server.rawPosts.isEmpty())
    assertEquals(
        exact,
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          it.getString(0)
        },
    )

    server.accepted = setOf("profile")
    sync.run()
    assertTrue(server.rawPosts.first().contentEquals(exact.encodeToByteArray()))
    assertTrue(server.operations.values.any { (_, ack) -> ack.revision >= 1 })
    assertNotNull(server.records["measurement:queued-after-profile"])
  }

  @Test
  fun `negotiated strength records round trip alongside their workout without changing profile wire`() =
      runTest {
        val server = Server().apply { accepted = setOf("strength-planner-personalization") }
        val sync = BackendSync(db, server, Store())
        SyncSchema.install(raw)
        sync.claim("user-a")
        val workoutId = "20000000-0000-4000-8000-000000000002"
        val strengthId =
            UUID.nameUUIDFromBytes(
                    "ValerochkaGym.strength-planner-profile.v1:user-a".encodeToByteArray()
                )
                .toString()
        val effortId =
            UUID.nameUUIDFromBytes(
                    "ValerochkaGym.workout-effort.v1:user-a:$workoutId".encodeToByteArray()
                )
                .toString()
        insertWorkout(workoutId, finishedAt = 2000)
        raw.execSQL(
            "INSERT INTO strength_planner_profiles(scope,syncId,updatedAt) VALUES('user-a',?,1)",
            arrayOf(strengthId),
        )
        raw.execSQL(
            "INSERT INTO workout_efforts(workoutId,scope,syncId,updatedAt,effort) VALUES(?,'user-a',?,1,'HARD')",
            arrayOf(workoutId, effortId),
        )

        sync.run()
        sync.run()

        assertNotNull(server.records["strength_planner_profile:$strengthId"])
        assertNotNull(server.records["workout_effort:$effortId"])
        assertEquals(
            "HARD",
            server.records
                .getValue("workout_effort:$effortId")
                .payload
                ?.get("effort")
                ?.jsonPrimitive
                ?.content,
        )
        assertNotNull(db.workoutEffortDao().get(workoutId, "user-a"))
        assertFalse(server.records.getValue("workout:$workoutId").payload!!.containsKey("effort"))
      }

  @Test
  fun `share import preserves strength capability local effort and exact queued bytes`() = runTest {
    val server = Server().apply { accepted = setOf("strength-planner-personalization") }
    val store = Store()
    val sync = BackendSync(db, server, store)
    SyncSchema.install(raw)
    sync.claim("user-a")
    val workoutId = "20000000-0000-4000-8000-000000000002"
    val effortId =
        UUID.nameUUIDFromBytes(
                "ValerochkaGym.workout-effort.v1:user-a:$workoutId".encodeToByteArray()
            )
            .toString()
    insertWorkout(workoutId, finishedAt = 2000)
    raw.execSQL(
        "INSERT INTO workout_efforts(workoutId,scope,syncId,updatedAt,effort) VALUES(?,'user-a',?,1,'HARD')",
        arrayOf(workoutId, effortId),
    )
    sync.run()
    raw.execSQL(
        "UPDATE workout_efforts SET effort='EASY',updatedAt=2 WHERE workoutId=?",
        arrayOf(workoutId),
    )
    val payload = PortableData(raw).snapshot().getValue("workout_effort:$effortId")
    val change =
        CloudChange(
            "workout_effort",
            effortId,
            server.records.getValue("workout_effort:$effortId").revision,
            false,
            payload,
        )
    val exact =
        "{ \"operationId\": \"retained-strength-effort\", \"changes\": [ ${Json.encodeToString(change)} ], \"catalogRevision\": null }"
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(exact),
    )
    val imported = server.addSharedRoutine()
    val posts = server.postAttempts
    sync.applyImportedRoutine(requireNotNull(store.snapshot()), imported, server.revision)
    assertTrue(
        server.lastRawHeaders
            .getValue("X-Gym-Capabilities")
            .split(',')
            .contains("strength-planner-personalization")
    )
    assertNotNull(db.routineDao().getRoutineBySyncId(imported))
    assertEquals(
        "EASY",
        PortableData(raw)
            .snapshot()
            .getValue("workout_effort:$effortId")["effort"]
            ?.jsonPrimitive
            ?.content,
    )
    assertEquals(posts, server.postAttempts)
    raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
      assertTrue(it.moveToFirst())
      assertEquals(exact, it.getString(0))
    }
  }

  @Test
  fun `strength planner outbox bytes remain immutable while capability is unavailable`() = runTest {
    val server = Server().apply { accepted = emptySet() }
    val sync = BackendSync(db, server, Store())
    SyncSchema.install(raw)
    raw.execSQL(
        "UPDATE backend_state SET owner='user-a',phase='OWNED',capabilityOwner='user-a',acceptedCapabilities='strength-planner-personalization' WHERE id=1",
    )
    val payload = buildJsonObject {
      put("schemaVersion", 1)
      put("syncId", "c3439134-6252-3a3d-b458-94b983f5e298")
      put("updatedAt", 1)
      put("keyExercises", JsonArray(emptyList()))
    }
    val exact =
        "{ \"operationId\" : \"strength-whitespace\", \"changes\" : [ ${Json.encodeToString(CloudChange("strength_planner_profile", "c3439134-6252-3a3d-b458-94b983f5e298", 1, false, payload))} ], \"catalogRevision\" : null }"
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(exact),
    )

    sync.run()

    assertTrue(server.rawPosts.isEmpty())
    assertEquals(
        exact,
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          it.getString(0)
        },
    )
  }

  @Test
  fun `absent annotation capability sends an empty current and baseline as legacy projection`() =
      runTest {
        val server = Server()
        val sync = BackendSync(db, server, Store())
        server.accepted = emptySet()

        baselineWorkout(server, sync, "empty-empty", "")

        val change = server.operations.values.single().first.changes.single { it.kind == "workout" }
        assertFalse(hasSetNote(requireNotNull(change.payload)))
      }

  @Test
  fun `absent annotation capability retains a nonempty current note over empty baseline`() =
      runTest {
        val server = Server()
        val sync = BackendSync(db, server, Store())
        server.accepted = setOf("annotated-workout-writes")
        val setId = baselineWorkout(server, sync, "current-note", "")
        val posts = server.postAttempts

        db.workoutDao().updateSet(db.workoutDao().getSet(setId)!!.copy(note = "local"))
        server.accepted = emptySet()
        sync.run()

        assertEquals(posts, server.postAttempts)
        assertTrue(
            PortableData(raw)
                .snapshot()
                .getValue("workout:current-note")
                .toString()
                .contains("local")
        )
      }

  @Test
  fun `absent annotation capability retains an empty current note over nonempty baseline`() =
      runTest {
        val server = Server()
        val sync = BackendSync(db, server, Store())
        server.accepted = setOf("annotated-workout-writes")
        val setId = baselineWorkout(server, sync, "baseline-note", "acknowledged")
        val posts = server.postAttempts

        db.workoutDao().updateSet(db.workoutDao().getSet(setId)!!.copy(note = ""))
        server.accepted = emptySet()
        sync.run()

        assertEquals(posts, server.postAttempts)
        raw.query("SELECT recordJson FROM backend_baseline WHERE `key`='workout:baseline-note'")
            .use {
              assertTrue(it.moveToFirst())
              assertTrue(it.getString(0).contains("acknowledged"))
            }
      }

  @Test
  fun `absent annotation capability retains a changed nonempty note over nonempty baseline`() =
      runTest {
        val server = Server()
        val sync = BackendSync(db, server, Store())
        server.accepted = setOf("annotated-workout-writes")
        val setId = baselineWorkout(server, sync, "both-notes", "acknowledged")
        val posts = server.postAttempts

        db.workoutDao().updateSet(db.workoutDao().getSet(setId)!!.copy(note = "new local"))
        server.accepted = emptySet()
        sync.run()

        assertEquals(posts, server.postAttempts)
      }

  @Test
  fun `clean acknowledged hint survives capability downgrade and refreshes when accepted again`() =
      runTest {
        val server = Server()
        val sync = BackendSync(db, server, Store())
        val id = "00000000-0000-0000-0000-000000000201"
        server.accepted = setOf("exercise-hint")
        baselineHint(server, sync, id, "acknowledged")

        server.accepted = emptySet()
        sync.run()
        assertEquals("acknowledged", db.exercisePersonalHintDao().get(id)?.text)
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                arrayOf("exercise_hint:$id"),
            )
            .use {
              assertTrue(it.moveToFirst())
              assertTrue(it.getString(0).contains("acknowledged"))
            }

        server.revision++
        server.records["exercise_hint:$id"] =
            CloudRecord(
                "exercise_hint",
                id,
                server.revision,
                payload =
                    buildJsonObject {
                      put("text", "server")
                      put("updatedAt", 2)
                    },
            )
        server.accepted = setOf("exercise-hint")
        sync.run()
        assertEquals("server", db.exercisePersonalHintDao().get(id)?.text)
      }

  @Test
  fun `dirty hidden hint stays local while compatible measurement still sends`() = runTest {
    val server = Server()
    val sync = BackendSync(db, server, Store())
    val id = "00000000-0000-0000-0000-000000000202"
    server.accepted = setOf("exercise-hint")
    baselineHint(server, sync, id, "acknowledged")
    db.exercisePersonalHintDao().upsert(ExercisePersonalHintEntity(id, "local", 2))
    db.bodyMeasurementDao().insert(BodyMeasurementEntity("compatible", 3))
    val posts = server.postAttempts

    server.accepted = emptySet()
    sync.run()

    assertEquals(posts + 1, server.postAttempts)
    assertEquals("local", db.exercisePersonalHintDao().get(id)?.text)
    val sent = server.operations.values.last().first
    assertTrue(sent.changes.any { it.kind == "measurement" })
    assertFalse(sent.changes.any { it.kind == "exercise_hint" })
  }

  @Test
  fun `server hint replacement and tombstone win without touching its exercise`() = runTest {
    val server = Server()
    val sync = BackendSync(db, server, Store())
    val id = "00000000-0000-0000-0000-000000000203"
    server.accepted = setOf("exercise-hint")
    val exerciseId = baselineHint(server, sync, id, "acknowledged")

    server.revision++
    server.records["exercise_hint:$id"] =
        CloudRecord(
            "exercise_hint",
            id,
            server.revision,
            payload =
                buildJsonObject {
                  put("text", "server")
                  put("updatedAt", 2)
                },
        )
    sync.run()
    assertEquals("server", db.exercisePersonalHintDao().get(id)?.text)
    assertEquals(id, db.exerciseDao().getById(exerciseId)?.syncId)

    server.revision++
    server.records["exercise_hint:$id"] =
        CloudRecord("exercise_hint", id, server.revision, deleted = true)
    sync.run()
    assertEquals(null, db.exercisePersonalHintDao().get(id))
    assertEquals(id, db.exerciseDao().getById(exerciseId)?.syncId)
  }

  @Test
  fun `owner replacement clears private hints with the prior account cache`() = runTest {
    val server = Server()
    val sync = BackendSync(db, server, Store())
    val id = "00000000-0000-0000-0000-000000000204"
    server.accepted = setOf("exercise-hint")
    baselineHint(server, sync, id, "acknowledged")

    sync.claim("user-b")

    assertEquals(null, db.exercisePersonalHintDao().get(id))
  }

  @Test
  fun `annotation tombstone with noted baseline stays held when capability is absent`() = runTest {
    val server = Server()
    val sync = BackendSync(db, server, Store())
    server.accepted = setOf("annotated-workout-writes")
    baselineWorkout(server, sync, "tombstone-note", "acknowledged")
    val posts = server.postAttempts

    db.workoutDao().deleteWorkout("tombstone-note")
    server.accepted = emptySet()
    sync.run()

    assertEquals(posts, server.postAttempts)
    raw.query("SELECT recordJson FROM backend_baseline WHERE `key`='workout:tombstone-note'").use {
      assertTrue(it.moveToFirst())
      assertTrue(it.getString(0).contains("acknowledged"))
    }
  }

  @Test
  fun `downgrade holds lost mixed hint and annotation bytes until capability reaccepts`() =
      runTest {
        val server = Server()
        val sync = BackendSync(db, server, Store())
        val hintId = "00000000-0000-0000-0000-000000000205"
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise(hintId))
        db.exercisePersonalHintDao().upsert(ExercisePersonalHintEntity(hintId, "cue", 1))
        insertWorkout("retry", finishedAt = 2)
        val section = insertWorkoutExercise("retry", exerciseId)
        val setId = insertSet(section, 0)
        db.workoutDao().updateSet(db.workoutDao().getSet(setId)!!.copy(note = "annotation"))
        sync.claim("user-a")
        server.accepted = setOf("exercise-hint", "annotated-workout-writes")
        server.loseNextResponse = true

        try {
          sync.run()
          fail("The lost response must retain the durable request for retry")
        } catch (_: IOException) {}

        val retained =
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              assertTrue(it.moveToFirst())
              it.getString(0)
            }
        val retainedBytes = retained.encodeToByteArray()
        assertTrue(server.rawPosts.single().contentEquals(retainedBytes))

        server.accepted = emptySet()
        db.bodyMeasurementDao().insert(BodyMeasurementEntity("compatible-after-downgrade", 2))
        sync.run()

        assertEquals(1, server.rawPosts.size)
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertTrue(it.getString(0).encodeToByteArray().contentEquals(retainedBytes))
        }
        assertTrue(server.operations.values.flatMap { it.first.changes }.none { it.deleted })

        server.accepted = setOf("exercise-hint", "annotated-workout-writes")
        sync.run()

        assertEquals(3, server.rawPosts.size)
        assertTrue(server.rawPosts[1].contentEquals(retainedBytes))
        assertTrue(server.operations.values.flatMap { it.first.changes }.none { it.deleted })
        assertNotNull(server.records["measurement:compatible-after-downgrade"])
        assertEquals(0, tableCount("backend_outbox"))
      }

  @Test
  fun `durable outbox replays noncanonical whitespace bytes before its first dispatch`() = runTest {
    val server = Server()
    val sync = BackendSync(db, server, Store())
    SyncSchema.install(raw)
    sync.claim("user-a")
    val exact =
        "{ \"operationId\" : \"whitespace-sentinel\", \"changes\" : [ ], \"catalogRevision\" : null }"
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(exact),
    )

    sync.run()

    assertEquals("whitespace-sentinel", server.operations.keys.single())
    assertTrue(server.rawPosts.single().contentEquals(exact.encodeToByteArray()))
    assertEquals(0, tableCount("backend_outbox"))
  }

  @Test
  fun `AI readiness returns only the final acknowledged personal revision`() = runTest {
    SyncSchema.install(raw)
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")

    val ready = sync.awaitAiReady()

    val receipt = ready as SyncReady.Ready
    assertEquals("user-a", receipt.owner)
    assertEquals(server.revision, receipt.revision)
    assertTrue(server.getReads >= 3)
  }

  @Test
  fun `AI readiness preserves network and backend failures and propagates cancellation`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        for (error in
            listOf(
                java.io.IOException("private"),
                BackendException(401, "unauthorized", "private"),
                BackendException(409, "revision_conflict", "private"),
            )) {
          server.onGet = { throw error }
          val failure = sync.awaitAiReady() as SyncReady.Failure
          assertEquals(error.javaClass, failure.cause!!.javaClass)
          if (error is BackendException)
              assertEquals(error.code, (failure.cause as BackendException).code)
          assertFalse(failure.message.contains("private"))
        }
        val cancellation = kotlinx.coroutines.CancellationException("private")
        server.onGet = { throw cancellation }
        assertTrue(
            runCatching { sync.awaitAiReady() }.exceptionOrNull()
                is kotlinx.coroutines.CancellationException
        )
      }

  @Test
  fun `AI readiness rejects a generation change during final acknowledgement`() = runTest {
    SyncSchema.install(raw)
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    server.onGet = { read ->
      if (read == 4) {
        raw.execSQL("UPDATE backend_state SET generation=generation+1 WHERE id=1")
      }
    }

    assertEquals(SyncReady.Blocked, sync.awaitAiReady())
  }

  @Test
  fun `AI readiness current check rejects an A to B to A session without HTTP`() = runTest {
    SyncSchema.install(raw)
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    val ready = sync.awaitAiReady() as SyncReady.Ready
    val readsAfterReady = server.getReads

    store.save(BackendTokens("user-b", "b@example.com", "b", "b"))
    store.save(BackendTokens("user-a", "a@example.com", "access", "refresh"))

    assertFalse(sync.isAiReadyCurrent(ready))
    assertEquals(readsAfterReady, server.getReads)
  }

  @Test
  fun `AI readiness blocks guests and an active workout before an acknowledgement receipt`() =
      runTest {
        SyncSchema.install(raw)
        val guest =
            object : BackendSessionStore {
              override val session = MutableStateFlow<BackendTokens?>(null)

              override fun save(tokens: BackendTokens?) {
                session.value = tokens
              }
            }
        assertEquals(
            "unauthorized",
            ((BackendSync(db, Server(), guest).awaitAiReady() as SyncReady.Failure).cause
                    as BackendException)
                .code,
        )

        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        insertWorkout("active")

        assertEquals(
            "workout_active",
            ((sync.awaitAiReady() as SyncReady.Failure).cause as BackendException).code,
        )
      }

  @Test
  fun `pending calendar migration blocks direct claim and sync before owner or API mutation`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server()
        val sync = BackendSync(db, server, Store(), PendingGate)

        val claim = runCatching { sync.claim("user-b") }.exceptionOrNull() as BackendException
        sync.run()

        assertEquals("calendar_migration_pending", claim.code)
        assertEquals(0, server.catalogReads)
        raw.query("SELECT owner FROM backend_state WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertTrue(it.isNull(0))
        }
      }

  @Test
  fun `guest claim preserves all three local calendar kinds`() = runTest {
    SyncSchema.install(raw)
    val routine =
        db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-calendar", name = "Ноги"))
    db.calendarPlanDao().upsertPlan(CalendarPlanEntity("plan", routine, 1_790_000_000_000, "UTC"))
    db.calendarPlanDao()
        .upsertRule(CalendarRuleEntity("rule", routine, 1, "08:30", "UTC", "2026-09-10"))
    db.calendarPlanDao()
        .upsertException(
            CalendarExceptionEntity(
                "exception",
                "rule",
                "2026-09-14T08:30[UTC]",
                CalendarExceptionKind.CANCELLED,
            )
        )

    assertTrue(BackendSync(db, Server(), Store()).claim("user-a") is GuestClaimResult.Claimed)
    assertEquals(1, tableCount("calendar_plans"))
    assertEquals(1, tableCount("calendar_rules"))
    assertEquals(1, tableCount("calendar_exceptions"))
  }

  @Test
  fun `sync emits calendar creates parent first and tombstones child first`() = runTest {
    SyncSchema.install(raw)
    val routine =
        db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-calendar", name = "Ноги"))
    db.calendarPlanDao().upsertPlan(CalendarPlanEntity("plan", routine, 1_790_000_000_000, "UTC"))
    db.calendarPlanDao()
        .upsertRule(CalendarRuleEntity("rule", routine, 1, "08:30", "UTC", "2026-09-10"))
    db.calendarPlanDao()
        .upsertException(
            CalendarExceptionEntity(
                "exception",
                "rule",
                "2026-09-14T08:30[UTC]",
                CalendarExceptionKind.CANCELLED,
            )
        )
    val server = Server().apply { accepted = setOf("calendar-plans") }
    val sync = BackendSync(db, server, Store())

    sync.claim("user-a")
    sync.run()

    assertEquals(
        listOf("calendar_plan", "calendar_rule", "calendar_exception"),
        server.operations.values
            .last()
            .first
            .changes
            .map { it.kind }
            .filter { it.startsWith("calendar_") },
    )

    db.calendarPlanDao().deleteAllExceptions()
    db.calendarPlanDao().deleteRule("rule")
    db.calendarPlanDao().deletePlan("plan")
    sync.run()

    assertEquals(
        listOf("calendar_exception", "calendar_rule", "calendar_plan"),
        server.operations.values
            .last()
            .first
            .changes
            .map { it.kind }
            .filter { it.startsWith("calendar_") },
    )
    assertTrue(
        server.operations.values
            .last()
            .first
            .changes
            .filter { it.kind.startsWith("calendar_") }
            .all { it.deleted }
    )
  }

  @Test
  fun `absent calendar capability retains exact outbox bytes and clears the owner cache`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        raw.execSQL(
            "UPDATE backend_state SET capabilityOwner='user-a',acceptedCapabilities='calendar-plans' WHERE id=1"
        )
        val bytes =
            Json.encodeToString(
                CloudPush(
                    "calendar-outbox",
                    listOf(CloudChange("calendar_plan", "plan", 0, false, buildJsonObject {})),
                )
            )
        raw.execSQL(
            "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
            arrayOf(bytes),
        )

        sync.run()

        assertEquals(0, server.postAttempts)
        assertEquals(CalendarCloudState.Unsupported, sync.calendarCloudState.first())
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertEquals(bytes, it.getString(0))
        }
        raw.query("SELECT capabilityOwner,acceptedCapabilities FROM backend_state WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertEquals("user-a", it.getString(0))
          assertEquals("", it.getString(1))
        }
      }

  @Test
  fun `owned account transition clears calendar rows Google metadata and owner capability cache`() =
      runTest {
        SyncSchema.install(raw)
        val routineId =
            db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine", name = "Ноги"))
        db.calendarPlanDao()
            .upsertPlan(CalendarPlanEntity("plan", routineId, 1_700_000_000_000, "UTC"))
        db.calendarPlanDao()
            .upsertRule(CalendarRuleEntity("rule", routineId, 1, "08:30", "UTC", "2026-01-01"))
        db.calendarPlanDao()
            .upsertException(
                CalendarExceptionEntity(
                    "exception",
                    "rule",
                    "2026-01-05T08:30[UTC]",
                    CalendarExceptionKind.CANCELLED,
                )
            )
        db.calendarPlanDao()
            .upsertGoogleLink(
                CalendarGoogleLinkEntity("plan", "plan", "google-a", "primary", "event", "LINKED")
            )
        raw.execSQL(
            "UPDATE backend_state SET owner='user-a',phase='OWNED',capabilityOwner='user-a',acceptedCapabilities='calendar-plans' WHERE id=1"
        )
        val portable = PortableData(raw).snapshot()
        portable.forEach { (key, payload) ->
          val record =
              CloudRecord(key.substringBefore(':'), key.substringAfter(':'), 1, false, payload)
          raw.execSQL(
              "INSERT INTO backend_baseline(`key`,recordJson) VALUES(?,?)",
              arrayOf(key, Json.encodeToString(record)),
          )
        }

        assertTrue(BackendSync(db, Server(), Store()).claim("user-b") is GuestClaimResult.Claimed)
        assertEquals(0, tableCount("calendar_plans"))
        assertEquals(0, tableCount("calendar_rules"))
        assertEquals(0, tableCount("calendar_exceptions"))
        assertEquals(0, tableCount("calendar_google_links"))
        raw.query("SELECT capabilityOwner,acceptedCapabilities FROM backend_state WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertTrue(it.isNull(0))
          assertEquals("", it.getString(1))
        }
      }

  @Test
  fun `clean restore removes unused bootstrap placeholders while keeping downloaded UUIDs`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
        val placeholder = exercise().copy(isCustom = false)
        db.exerciseDao().insert(placeholder)
        raw.execSQL(
            "UPDATE catalog_state SET bootstrapSnapshot=? WHERE id=1",
            arrayOf(JsonObject(PortableData(raw).snapshot()).toString()),
        )
        val originalPayload = PortableData(raw).snapshot().values.single()
        val server = Server()
        val restoredId = UUID.randomUUID().toString()
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", restoredId, 1, false, originalPayload)),
                emptyList(),
            )
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        assertEquals(listOf(restoredId), db.exerciseDao().getAllOnce().map { it.syncId })
        assertTrue(server.records.isEmpty())
      }

  @Test
  fun `catalog reclassifies stable IDs without changing history and never uploads standard rows`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
        val ex = exercise()
        val localId = db.exerciseDao().insert(ex)
        insertWorkout("history", finishedAt = 2000)
        val section = insertWorkoutExercise("history", localId)
        insertSet(section, 0, weightKg = 80.0, reps = 5, isCompleted = true)
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        val before = PortableData(raw).snapshot()["workout:history"]
        val standard = server.records.remove("exercise:${ex.syncId}")!!
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", ex.syncId, 1, false, standard.payload!!)),
                emptyList(),
            )
        sync.run()
        sync.run()
        assertEquals(localId, db.exerciseDao().getAllOnce().single().id)
        assertEquals("STANDARD", db.exerciseDao().getById(localId)!!.origin)
        assertEquals(before, PortableData(raw).snapshot()["workout:history"])
        assertEquals(section, workoutFull("history").exercises.single().workoutExercise.id)
        assertFalse(PortableData(raw).snapshot().containsKey(standard.key))
        assertTrue(server.records.values.none { it.kind == "exercise" })
      }

  @Test
  fun `transition retains exact outbox until restart choice and copies edits without relinking history`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
        val ex = exercise()
        val localId = db.exerciseDao().insert(ex)
        insertWorkout("history", finishedAt = 2000)
        insertWorkoutExercise("history", localId)
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val standard = server.records.getValue("exercise:${ex.syncId}")
        db.exerciseDao()
            .update(db.exerciseDao().getById(localId)!!.copy(name = "Моя правка", updatedAt = 2))
        server.failBeforeCommit = true
        try {
          sync.run()
          fail()
        } catch (_: IOException) {}
        val pending =
            raw.query("SELECT requestJson FROM backend_outbox").use {
              it.moveToFirst()
              it.getString(0)
            }
        server.records.remove(standard.key)
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", ex.syncId, 1, false, standard.payload!!)),
                emptyList(),
            )
        try {
          sync.run()
          fail()
        } catch (e: BackendException) {
          assertEquals("catalog_transition_required", e.code)
        }
        assertEquals(
            pending,
            raw.query("SELECT requestJson FROM backend_outbox").use {
              it.moveToFirst()
              it.getString(0)
            },
        )
        assertEquals("Моя правка", db.exerciseDao().getById(localId)!!.name)
        BackendSync(db, server, store).run("local")
        val rows = db.exerciseDao().getAllOnce()
        assertEquals(2, rows.size)
        assertEquals("STANDARD", rows.single { it.id == localId }.origin)
        val copy = rows.single { it.id != localId }
        assertEquals("PERSONAL", copy.origin)
        assertTrue(copy.name.startsWith("Моя правка"))
        assertNotEquals(ex.syncId, copy.syncId)
        assertEquals(localId, workoutFull("history").exercises.single().exercise.id)
        assertEquals(0, tableCount("backend_outbox"))
        assertEquals(
            pending,
            raw.query("SELECT originalOutbox FROM catalog_state").use {
              it.moveToFirst()
              it.getString(0)
            },
        )
      }

  @Test
  fun `public catalog updates equipment without account and active workout defers its application`() =
      runTest {
        val server = Server()
        val store = Store().also { it.save(null) }
        val payload = buildJsonObject {
          put("name", "Новая скамья")
          put("group", "Скамьи")
          put("synonyms", JsonArray(listOf(JsonPrimitive("лавка"))))
          put(
              "provides",
              JsonArray(listOf(JsonPrimitive("flat_bench"), JsonPrimitive("new_bench"))),
          )
        }
        server.catalog =
            StandardSnapshot(
                true,
                2,
                emptyList(),
                listOf(StandardRecord("equipment", "new_bench", 2, false, payload)),
            )
        val sync = BackendSync(db, server, store)
        insertWorkout("active")
        sync.run()
        assertEquals(0, server.catalogReads)
        raw.execSQL("UPDATE workouts SET finishedAt=2000 WHERE id='active'")
        sync.run()
        assertTrue(
            com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog.covers(
                setOf("new_bench"),
                "flat_bench",
            )
        )
        CatalogSchema.install(raw)
        CatalogSchema.publishEquipment(raw)
        assertEquals(
            "Новая скамья",
            com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog.require("new_bench")
                .name,
        )
      }

  @Test
  fun `lost response retries the same durable operation without a duplicate`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    db.exerciseDao().insert(exercise())
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    server.loseNextResponse = true
    try {
      sync.run()
      fail("Expected network failure")
    } catch (_: IOException) {}
    assertEquals(1, tableCount("backend_outbox"))
    val restarted = BackendSync(db, server, store)
    restarted.run()
    assertEquals(1, server.operations.size)
    assertEquals(2, server.postAttempts)
    assertEquals(0, tableCount("backend_outbox"))
    assertEquals(1, server.records.size)
  }

  @Test
  fun `legacy outbox preserves omitted fields and then sends current coach snapshot`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL(
        "UPDATE backend_state SET owner='user-a',phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
    )
    insertWorkout("history", finishedAt = 2000)
    val current = PortableData(raw).snapshot().getValue("workout:history")
    val legacy = JsonObject(current - "coachRevision")
    val push =
        CloudPush("old-operation", listOf(CloudChange("workout", "history", 0, payload = legacy)))
    val server = Server()
    val oldBody = JsonObject(server.json.encodeToJsonElement(push).jsonObject - "catalogRevision")
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(oldBody.toString()),
    )
    BackendSync(db, server, Store()).run()
    assertEquals(oldBody, server.sentBodies.first())
    assertEquals(2, server.operations.size)
    assertEquals(current, server.records.getValue("workout:history").payload)
  }

  @Test
  fun `legacy workout imports preserve set UUID and leave unknown coaching data empty`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise())
    insertWorkout("history", finishedAt = 2000)
    val section = insertWorkoutExercise("history", exerciseId)
    insertSet(section, 0, weightKg = 40.0, reps = 8, isCompleted = true)
    val portable = PortableData(raw)
    val before = portable.snapshot().getValue("workout:history")
    val oldSet =
        before
            .getValue("exercises")
            .jsonArray
            .single()
            .jsonObject
            .getValue("sets")
            .jsonArray
            .single()
            .jsonObject
    val legacyFields =
        setOf(
            "setIndex",
            "weightKg",
            "reps",
            "durationSec",
            "speedKmh",
            "inclinePct",
            "isCompleted",
            "completedAt",
        )
    val sections =
        before.getValue("exercises").jsonArray.map { sectionJson ->
          JsonObject(
              sectionJson.jsonObject +
                  ("sets" to
                      JsonArray(listOf(JsonObject(oldSet.filterKeys { it in legacyFields }))))
          )
        }
    val legacy = JsonObject((before - "coachRevision") + ("exercises" to JsonArray(sections)))
    repeat(2) {
      portable.apply(listOf(CloudRecord("workout", "history", 1, payload = legacy)), emptyList())
    }
    val restored = portable.snapshot().getValue("workout:history")
    val set =
        restored
            .getValue("exercises")
            .jsonArray
            .single()
            .jsonObject
            .getValue("sets")
            .jsonArray
            .single()
            .jsonObject
    assertEquals(oldSet["syncId"], set["syncId"])
    assertEquals(JsonNull, set["originalWeightKg"])
    assertEquals(JsonNull, set["actualReps"])
    assertEquals(JsonPrimitive("[]"), set["reportedFeelingsJson"])
    assertEquals(JsonPrimitive("UNKNOWN"), set["setType"])
    assertEquals(JsonPrimitive(40.0), set["weightKg"])
    assertEquals(JsonPrimitive(0), restored["coachRevision"])
  }

  @Test
  fun `same owner signing in during upload retains the old operation for retry`() = runTest {
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    SyncSchema.install(raw)
    sync.claim("user-a")
    db.exerciseDao().insert(exercise())
    server.afterCommit = { store.save(store.session.value) }
    try {
      sync.run()
      fail("Old session must not acknowledge")
    } catch (error: BackendException) {
      assertEquals("owner_changed", error.code)
    }
    assertEquals(1, tableCount("backend_outbox"))
    sync.run()
    assertEquals(0, tableCount("backend_outbox"))
    assertEquals(1, server.operations.size)
  }

  @Test
  fun `local edits committed while uploading are sent as a newer operation`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    server.afterCommit = {
      raw.execSQL("UPDATE exercises SET name='После запроса' WHERE id=?", arrayOf(id))
    }
    sync.run()
    assertEquals(2, server.operations.size)
    assertEquals(
        "После запроса",
        server.records.values.single().payload!!["name"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `switching accounts blocks a retained previous history and pending uploads`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val server = Server()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    db.exerciseDao().insert(exercise())
    sync.run()
    db.bodyMeasurementDao()
        .insert(
            BodyMeasurementEntity(
                id = UUID.randomUUID().toString(),
                measuredAt = 1,
                weightKg = 80.0,
            )
        )
    server.loseNextResponse = true
    try {
      sync.run()
    } catch (_: IOException) {}
    assertEquals(1, tableCount("backend_outbox"))
    val claim = sync.claim("user-b")
    assertTrue(claim is GuestClaimResult.Blocked)
    assertEquals(1, tableCount("exercises"))
    assertEquals(1, tableCount("body_measurements"))
    assertEquals(1, tableCount("backend_outbox"))
    assertEquals("user-a", sync.owner())
  }

  @Test
  fun `account switch and sign in retain downloaded standard records without reseeding`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        val ex = exercise()
        val localId = db.exerciseDao().insert(ex)
        raw.execSQL(
            "INSERT INTO exercise_equipment(exerciseId,equipmentId) VALUES (?, 'dumbbells')",
            arrayOf(localId),
        )
        val payload = PortableData(raw).snapshot().getValue("exercise:${ex.syncId}")
        raw.execSQL("UPDATE catalog_state SET applying=1 WHERE id=1")
        raw.execSQL("UPDATE exercises SET origin='STANDARD' WHERE id=?", arrayOf(localId))
        raw.execSQL("UPDATE catalog_state SET applying=0 WHERE id=1")
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", ex.syncId, 1, false, payload)),
                emptyList(),
            )
        sync.run()
        sync.signOut()
        assertNull(store.session.value)
        sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
        sync.run()
        val retained = db.exerciseDao().getAllOnce().single()
        assertEquals(localId, retained.id)
        assertEquals("STANDARD", retained.origin)
        assertEquals(ex.name, retained.name)
        assertTrue(server.records.isEmpty())
        assertEquals(1, tableCount("exercise_equipment"))
        assertEquals(
            payload,
            PortableData(raw).snapshot(includeStandard = true).getValue("exercise:${ex.syncId}"),
        )
        assertEquals(1, tableCount("catalog_records"))
        assertEquals("user-b", sync.owner())
      }

  @Test
  fun `first account retains legacy local history for the initial merge`() = runTest {
    SyncSchema.install(raw)
    db.exerciseDao().insert(exercise())
    insertWorkout("legacy", finishedAt = 2000)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    assertEquals(1, tableCount("workouts"))
    assertEquals(1, tableCount("exercises"))
  }

  @Test
  fun `initial merge retains the six kind guest union and existing server records`() = runTest {
    SyncSchema.install(raw)
    val exerciseId = db.exerciseDao().insert(exercise())
    val gymId = db.gymDao().insertGym(GymEntity(syncId = "gym", name = "Зал"))
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine", name = "План"))
    db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))
    db.routineDao()
        .replaceRoutineExercises(
            routineId,
            listOf(
                RoutineExerciseEntity(routineId = routineId, exerciseId = exerciseId, position = 0)
            ),
        )
    db.scheduledWorkoutDao()
        .insert(
            ScheduledWorkoutEntity(
                routineId = routineId,
                dateTimeMillis = 1,
                calendarEventId = "event",
            )
        )
    insertWorkout("workout", finishedAt = 2)
    raw.execSQL("UPDATE workouts SET routineId=? WHERE id='workout'", arrayOf(routineId))
    insertWorkoutExercise("workout", exerciseId)
    db.bodyMeasurementDao().insert(BodyMeasurementEntity("measurement", 1))

    val snapshot = PortableData(raw).snapshot()
    val server = Server()
    val remote =
        CloudRecord("measurement", "remote", 1, false, snapshot.getValue("measurement:measurement"))
    server.records[remote.key] = remote
    server.revision = 1
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    val merged = PortableData(raw).snapshot()
    snapshot.forEach { (key, payload) ->
      assertEquals(payload, merged[key])
      assertEquals(payload, server.records[key]?.payload)
    }
    assertEquals(remote.payload, merged[remote.key])
    assertEquals(GuestSyncPhase.OWNED, sync.transfer.value.phase)
    assertEquals(0, tableCount("backend_outbox"))

    assertEquals(
        setOf("exercise", "gym", "routine", "workout", "measurement", "schedule"),
        snapshot.keys.map { it.substringBefore(':') }.toSet(),
    )
    assertEquals(
        "routine",
        snapshot.getValue("workout:workout").getValue("routineId").jsonPrimitive.content,
    )
    assertEquals(
        "gym",
        snapshot
            .getValue("routine:routine")
            .getValue("gymIds")
            .jsonArray
            .single()
            .jsonPrimitive
            .content,
    )
  }

  @Test
  fun `returning to the same account retains offline changes`() = runTest {
    SyncSchema.install(raw)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    db.exerciseDao().insert(exercise())
    sync.claim("user-a")
    assertEquals(1, tableCount("exercises"))
  }

  @Test
  fun `claimed account rejects a different account before active data can change`() = runTest {
    SyncSchema.install(raw)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    insertWorkout("active")
    try {
      sync.claim("user-b")
      fail("Workout must be finished")
    } catch (e: BackendException) {
      assertEquals("claim_owned_by_other", e.code)
    }
    assertEquals("user-a", sync.owner())
    assertEquals(1, tableCount("workouts"))
  }

  @Test
  fun `local logout retains a claimed transfer for its original account`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val offline =
        object : BackendTransport {
          override val json = Json

          override suspend fun public(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()

          override suspend fun authorized(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()
        }
    val sync = BackendSync(db, offline, store)
    sync.claim("user-a")
    db.bodyMeasurementDao().insert(BodyMeasurementEntity(id = "a", measuredAt = 1, weightKg = 80.0))
    sync.signOut()
    assertNull(store.session.value)
    try {
      sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
      fail("Different account must not replace a claimed transfer")
    } catch (e: BackendException) {
      assertEquals("claim_owned_by_other", e.code)
    }
    assertNull(store.session.value)
    assertEquals("user-a", sync.owner())
    assertEquals(1, tableCount("body_measurements"))
  }

  @Test
  fun `logout on every device requires an acknowledgement while offline`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val offline =
        object : BackendTransport {
          override val json = Json

          override suspend fun public(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()

          override suspend fun authorized(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()
        }
    val sync = BackendSync(db, offline, store)
    sync.claim("user-a")
    try {
      sync.signOut(all = true)
      fail("Server acknowledgement required")
    } catch (_: IOException) {}
    assertEquals("user-a", store.session.value?.userId)
  }

  @Test
  fun `remote edits and offline deletion conflict without losing the local choice`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("DELETE FROM exercises WHERE id=?", arrayOf(id))
    val record = server.records.values.single()
    server.revision++
    server.records[record.key] =
        record.copy(
            revision = server.revision,
            payload =
                JsonObject(record.payload!! + mapOf("name" to JsonPrimitive("Другое устройство"))),
        )
    try {
      sync.run()
      fail("Expected conflict")
    } catch (e: BackendException) {
      assertEquals("revision_conflict", e.code)
    }
    assertEquals(0, tableCount("exercises"))
    assertTrue(sync.conflict.value)
    sync.run("local")
    assertTrue(server.records.values.single().deleted)
  }

  @Test
  fun `remote version restores an offline deleted object when explicitly selected`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("DELETE FROM exercises WHERE id=?", arrayOf(id))
    val record = server.records.values.single()
    server.revision++
    server.records[record.key] =
        record.copy(
            revision = server.revision,
            payload = JsonObject(record.payload!! + mapOf("name" to JsonPrimitive("Удалённо"))),
        )
    sync.run("server")
    assertEquals("Удалённо", db.exerciseDao().getAllOnce().single().name)
  }

  @Test
  fun `dirty marker and domain edit roll back together`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    try {
      db.withTransaction {
        db.exerciseDao().insert(exercise())
        throw IOException("rollback")
      }
    } catch (_: IOException) {}
    assertEquals(0, tableCount("exercises"))
    raw.query("SELECT generation FROM backend_state").use {
      it.moveToFirst()
      assertEquals(0, it.getLong(0))
    }
    db.exerciseDao().insert(exercise())
    raw.query("SELECT generation FROM backend_state").use {
      it.moveToFirst()
      assertEquals(1, it.getLong(0))
    }
  }

  @Test
  fun `portable snapshot restores duplicate sections null measurements and UUID references`() =
      runTest {
        val exercise = db.exerciseDao().insert(exercise())
        val workout = "00000000-0000-0000-0000-000000000002"
        insertWorkout(workout, finishedAt = 2000)
        val first = insertWorkoutExercise(workout, exercise, 0)
        val second = insertWorkoutExercise(workout, exercise, 1)
        insertSet(first, 0, weightKg = 25.0, reps = 8, isCompleted = true)
        insertSet(second, 0, weightKg = 30.0, reps = 6, isCompleted = true)
        raw.execSQL(
            "UPDATE workout_exercises SET sectionId=? WHERE id=?",
            arrayOf<Any>("00000000-0000-0000-0000-000000000003", first),
        )
        raw.execSQL(
            "UPDATE workout_exercises SET sectionId=? WHERE id=?",
            arrayOf<Any>("00000000-0000-0000-0000-000000000004", second),
        )
        db.bodyMeasurementDao()
            .insert(
                BodyMeasurementEntity(
                    id = "00000000-0000-0000-0000-000000000005",
                    measuredAt = 1000,
                    weightKg = 75.0,
                )
            )
        val before = PortableData(raw).snapshot()
        val records =
            before.map { (key, payload) ->
              CloudRecord(key.substringBefore(':'), key.substringAfter(':'), 1, false, payload)
            }
        val fixture = java.io.File("build/reports/backend/android-snapshot.json")
        fixture.parentFile.mkdirs()
        fixture.writeText(Json.encodeToString(CloudSnapshot(1, records)))
        db.clearAllTables()
        // Ensure restoration does not accidentally depend on the old numeric row IDs.
        db.exerciseDao().insert(exercise("00000000-0000-0000-0000-000000000099"))
        db.withTransaction { PortableData(raw).apply(records, emptyList()) }
        val after = PortableData(raw).snapshot().filterKeys { !it.endsWith("0099") }
        assertEquals(before, after)
        assertEquals(2, tableCount("workout_exercises"))
        assertEquals(2, tableCount("workout_sets"))
      }

  @Test
  fun `active workout blocks remote writes until the foreground session completes`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    insertWorkout(UUID.randomUUID().toString())
    sync.run()
    assertTrue(server.operations.isEmpty())
    assertTrue(sync.status.value.contains("после тренировки"))
  }

  @Test
  fun `claimed transfer rejects C before replacing B tokens`() = runTest {
    SyncSchema.install(raw)
    val store = Store().also { it.save(null) }
    val sync = BackendSync(db, Server(), store)
    sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))

    try {
      sync.signIn(BackendTokens("user-c", "c@example.com", "access-c", "refresh-c"))
      fail("C must not replace a durable B claim")
    } catch (error: BackendException) {
      assertEquals("claim_owned_by_other", error.code)
    }

    assertEquals("user-b", store.session.value?.userId)
    assertEquals("user-b", sync.transfer.value.owner)
    assertEquals(GuestSyncPhase.CLAIMED, sync.transfer.value.phase)
  }

  @Test
  fun `owned cache outbox blocks B before replacing A tokens`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val sync = BackendSync(db, Server(), store)
    sync.claim("user-a")
    sync.run()
    val bytes = "{\"operationId\":\"retain-exact\",\"changes\":[] }"
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(bytes),
    )

    try {
      sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
      fail("retained outbox must block replacement")
    } catch (error: BackendException) {
      assertEquals("guest_data_preservation_required", error.code)
    }

    assertEquals("user-a", store.session.value?.userId)
    raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
      assertTrue(it.moveToFirst())
      assertEquals(bytes, it.getString(0))
    }
  }

  private suspend fun assertIndependentFootprint(mutate: (CloudRecord) -> Unit) {
    SyncSchema.install(raw)
    db.exerciseDao().insert(exercise())
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    sync.run()
    val generation =
        raw.query("SELECT generation FROM backend_state WHERE id=1").use {
          it.moveToFirst()
          it.getLong(0)
        }
    mutate(server.records.getValue("exercise:${exercise().syncId}"))
    raw.execSQL("UPDATE backend_state SET generation=? WHERE id=1", arrayOf(generation))
    val before = PortableData(raw).snapshot()
    val baselineBytes =
        raw.query("SELECT recordJson FROM backend_baseline ORDER BY `key`").use {
          buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
    assertEquals(0, tableCount("backend_outbox"))
    assertTrue(sync.claim("user-b") is GuestClaimResult.Blocked)
    assertEquals("user-a", sync.owner())
    assertEquals("user-a", store.session.value?.userId)
    assertEquals(before, PortableData(raw).snapshot())
    assertEquals(
        baselineBytes,
        raw.query("SELECT recordJson FROM backend_baseline ORDER BY `key`").use {
          buildList { while (it.moveToNext()) add(it.getString(0)) }
        },
    )
  }

  @Test
  fun `baseline divergence blocks replacement even with unchanged generation and no journals`() =
      runTest {
        assertIndependentFootprint { record ->
          val different =
              record.copy(
                  payload =
                      JsonObject(record.payload!! + ("name" to JsonPrimitive("Baseline differs")))
              )
          raw.execSQL(
              "UPDATE backend_baseline SET recordJson=? WHERE `key`=?",
              arrayOf(Json.encodeToString(different), record.key),
          )
        }
      }

  @Test
  fun `baseline only deleted local record blocks replacement without generation evidence`() =
      runTest {
        assertIndependentFootprint { raw.execSQL("DELETE FROM exercises") }
      }

  @Test
  fun `catalog applying alone blocks replacement without pending snapshot or outbox`() = runTest {
    assertIndependentFootprint { raw.execSQL("UPDATE catalog_state SET applying=1 WHERE id=1") }
    raw.query("SELECT applying,pendingSnapshot,originalOutbox FROM catalog_state WHERE id=1").use {
      assertTrue(it.moveToFirst())
      assertEquals(1, it.getInt(0))
      assertTrue(it.isNull(1))
      assertTrue(it.isNull(2))
    }
  }

  @Test
  fun `late POST acknowledgement cannot update baseline or discard the retained operation under B`() =
      runTest {
        SyncSchema.install(raw)
        val id = db.exerciseDao().insert(exercise())
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val baseline =
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("exercise:${exercise().syncId}"),
                )
                .use {
                  it.moveToFirst()
                  it.getString(0)
                }
        raw.execSQL("UPDATE exercises SET name='Local A' WHERE id=?", arrayOf(id))
        var request = ""
        server.afterCommit = {
          request =
              raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
                it.moveToFirst()
                it.getString(0)
              }
          store.save(BackendTokens("user-b", "b@e", "b", "b"))
          raw.execSQL(
              "UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1"
          )
        }
        try {
          sync.run()
          fail("late A ACK must fail closed")
        } catch (error: BackendException) {
          assertEquals("owner_changed", error.code)
        }
        assertEquals("user-b", sync.owner())
        assertEquals(
            baseline,
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("exercise:${exercise().syncId}"),
                )
                .use {
                  it.moveToFirst()
                  it.getString(0)
                },
        )
        assertEquals(
            request,
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              it.moveToFirst()
              it.getString(0)
            },
        )
        assertEquals("Local A", db.exerciseDao().getById(id)!!.name)
      }

  @Test
  fun `preservation fingerprint changes with exact catalog journal bytes and tombstone contents`() =
      runTest {
        SyncSchema.install(raw)
        val sync = BackendSync(db, Server(), Store())
        sync.claim("user-a")
        sync.run()
        raw.execSQL("UPDATE catalog_state SET originalOutbox='first' WHERE id=1")
        val first = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL("UPDATE catalog_state SET originalOutbox='second' WHERE id=1")
        val second = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL(
            "INSERT INTO configuration_tombstones(kind,syncId,updatedAt) VALUES('routine','r',1)"
        )
        val third = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL(
            "UPDATE configuration_tombstones SET updatedAt=2 WHERE kind='routine' AND syncId='r'"
        )
        val fourth = sync.claim("user-b") as GuestClaimResult.Blocked

        assertNotEquals(first.footprint.fingerprint, second.footprint.fingerprint)
        assertNotEquals(second.footprint.fingerprint, third.footprint.fingerprint)
        assertNotEquals(third.footprint.fingerprint, fourth.footprint.fingerprint)
      }

  @Test
  fun `health preservation fingerprint includes literal journal bytes rather than row counts`() =
      runTest {
        SyncSchema.install(raw)
        val sync = BackendSync(db, Server(), Store())
        sync.claim("user-a")
        sync.run()
        raw.execSQL(
            "INSERT INTO health_sync_outbox(operationId,scope,requestBytes,requestSha256,dispatched) VALUES(?,?,?,?,0)",
            arrayOf(
                "33333333-3333-4333-8333-333333333333",
                "user-a",
                " { \"operationId\" : \"first\" } ".encodeToByteArray(),
                "same-count",
            ),
        )
        val first = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL(
            "UPDATE health_sync_outbox SET requestBytes=? WHERE scope='user-a'",
            arrayOf(" { \"operationId\" : \"second\" } ".encodeToByteArray()),
        )
        val second = sync.claim("user-b") as GuestClaimResult.Blocked

        assertNotEquals(first.footprint.fingerprint, second.footprint.fingerprint)
      }

  @Test
  fun `failed B token installation leaves claimed B and rejects old A sync after recreation`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL(
            "UPDATE backend_state SET owner='user-a',phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
        )
        var failTokenWrite = true
        val store =
            object : BackendSessionStore {
              override val session =
                  MutableStateFlow<BackendTokens?>(BackendTokens("user-a", "a@e", "a", "a"))

              override fun save(tokens: BackendTokens?) {
                if (tokens?.userId == "user-b" && failTokenWrite)
                    throw IOException("token write failed")
                session.value = tokens
              }
            }
        val server = Server()
        try {
          BackendSync(db, server, store).signIn(BackendTokens("user-b", "b@e", "b", "b"))
          fail("token installation must fail")
        } catch (_: IOException) {}

        assertEquals("user-a", store.session.value?.userId)
        assertEquals("user-b", BackendSync(db, server, store).transfer.value.owner)
        try {
          BackendSync(db, server, store).run()
          fail("old A credentials must never render or call through B's cache")
        } catch (error: BackendException) {
          assertEquals("owner_changed", error.code)
        }
        assertEquals(0, server.catalogReads)
        val restarted = BackendSync(db, server, store)
        val mergeId = restarted.transfer.value.mergeId
        try {
          restarted.signIn(BackendTokens("user-c", "c@e", "c", "c"))
          fail("a different account cannot resume B's transfer")
        } catch (error: BackendException) {
          assertEquals("claim_owned_by_other", error.code)
        }
        failTokenWrite = false
        restarted.signIn(BackendTokens("user-b", "b@e", "b", "b"))
        assertEquals(mergeId, restarted.transfer.value.mergeId)
        restarted.run()
        assertEquals("user-b", store.session.value?.userId)
        assertEquals(GuestSyncPhase.OWNED, restarted.transfer.value.phase)
      }

  @Test
  fun `confirmed account deletion resets claimed ownership while a failed request preserves it`() =
      runTest {
        SyncSchema.install(raw)
        val store = Store().also { it.save(BackendTokens("user-a", "a@e", "a", "a")) }
        val api =
            object : BackendTransport {
              override val json = Json
              var fail = true

              override suspend fun public(method: String, path: String, body: JsonElement?) =
                  buildJsonObject {}

              override suspend fun authorized(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ): JsonElement {
                if (fail) throw IOException("offline")
                assertEquals("DELETE", method)
                return buildJsonObject {}
              }
            }
        val sync = BackendSync(db, api, store)
        sync.claim("user-a")
        try {
          sync.deleteAccount("code")
          fail("failed deletion must retain recovery state")
        } catch (_: IOException) {}
        assertEquals(GuestSyncPhase.CLAIMED, sync.transfer.value.phase)
        api.fail = false
        sync.deleteAccount("code")
        assertEquals(GuestSyncPhase.GUEST, sync.transfer.value.phase)
        assertNull(sync.owner())
        assertNull(store.session.value)
        BackendSync(db, api, store).signIn(BackendTokens("user-b", "b@e", "b", "b"))
        assertEquals("user-b", BackendSync(db, api, store).owner())
      }

  @Test
  fun `catalog response cannot apply after ownership changes`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val server = Server()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    server.catalog =
        StandardSnapshot(
            true,
            1,
            listOf(
                StandardRecord(
                    "exercise",
                    "standard",
                    1,
                    false,
                    PortableData(raw).snapshot().values.firstOrNull()
                        ?: buildJsonObject { put("name", "x") },
                )
            ),
            emptyList(),
        )
    server.beforeCatalog = {
      store.save(BackendTokens("user-b", "b@e", "b", "b"))
      raw.execSQL("UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1")
    }

    try {
      sync.run()
      fail("late catalog response must be rejected")
    } catch (error: BackendException) {
      assertEquals("owner_changed", error.code)
    }
    assertEquals(0, tableCount("catalog_records"))
  }

  @Test
  fun `late account request result is rejected after ownership changes`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val server = Server()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    server.beforeGet = {
      store.save(BackendTokens("user-b", "b@e", "b", "b"))
      raw.execSQL("UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1")
    }
    try {
      sync.accountRequest("GET", "/sessions")
      fail("late A response must not reach account UI")
    } catch (error: BackendException) {
      assertEquals("owner_changed", error.code)
    }
  }

  @Test
  fun `new batch definite 409 records marker before process recreation`() = runTest {
    SyncSchema.install(raw)
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("UPDATE exercises SET name='Локально' WHERE id=?", arrayOf(id))
    server.beforePost = {
      val record = server.records.getValue("exercise:${exercise().syncId}")
      server.revision++
      server.records[record.key] = record.copy(revision = server.revision)
    }
    try {
      sync.run()
      fail("new batch must receive a definite conflict")
    } catch (error: BackendException) {
      assertEquals("revision_conflict", error.code)
    }
    assertEquals(1, tableCount("backend_rejected_operations"))
    val attempts = server.postAttempts
    try {
      BackendSync(db, server, Store()).run()
      fail("manual conflict must remain after recreation")
    } catch (error: BackendException) {
      assertEquals("revision_conflict", error.code)
    }
    assertEquals(attempts, server.postAttempts)
  }

  @Test
  fun `definite 409 keeps rejected bytes through GET and manual conflict without prechoice mutation`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()

        raw.execSQL("UPDATE exercises SET name='Локальная правка' WHERE id=?", arrayOf(exerciseId))
        val local = PortableData(raw).snapshot().getValue("exercise:${exercise().syncId}")
        val rejected =
            CloudPush(
                operationId = "definite-conflict",
                changes = listOf(CloudChange("exercise", exercise().syncId, 1, payload = local)),
            )
        val bytes = server.json.encodeToString(rejected)
        raw.execSQL(
            "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
            arrayOf(bytes),
        )
        val remote = server.records.getValue("exercise:${exercise().syncId}")
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload =
                    JsonObject(remote.payload!! + ("name" to JsonPrimitive("Серверная правка"))),
            )

        try {
          sync.run()
          fail("manual resolution must be requested")
        } catch (error: BackendException) {
          assertEquals("revision_conflict", error.code)
        }

        assertTrue(server.getReads > 0)
        val rejectedAttempts = server.postAttempts
        assertEquals("Локальная правка", db.exerciseDao().getById(exerciseId)!!.name)
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertEquals(bytes, it.getString(0))
        }
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`='exercise:${exercise().syncId}'"
            )
            .use {
              assertTrue(it.moveToFirst())
              assertEquals(1L, server.json.decodeFromString<CloudRecord>(it.getString(0)).revision)
            }

        val restarted = BackendSync(db, server, Store())
        try {
          restarted.run()
          fail("manual resolution must survive process recreation")
        } catch (error: BackendException) {
          assertEquals("revision_conflict", error.code)
        }
        assertEquals(rejectedAttempts, server.postAttempts)
        db.bodyMeasurementDao().insert(BodyMeasurementEntity(id = "unrelated", measuredAt = 1))
        restarted.run("server")
        assertEquals(rejectedAttempts + 1, server.postAttempts)
        assertTrue(server.operations.keys.none { it == "definite-conflict" })
        assertTrue(
            server.operations.values
                .single { (_, ack) -> ack.revision > remote.revision }
                .first
                .changes
                .any { it.id == "unrelated" }
        )
        assertEquals(0, tableCount("backend_rejected_operations"))
      }

  @Test
  fun `workout conflict applies one server history without a local copy`() = runTest {
    SyncSchema.install(raw)
    val exerciseId = db.exerciseDao().insert(exercise())
    insertWorkout("history", finishedAt = 2_000)
    insertWorkoutExercise("history", exerciseId)
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("UPDATE workouts SET name='Локальная история' WHERE id='history'")
    val remote = server.records.getValue("workout:history")
    server.revision++
    server.records[remote.key] =
        remote.copy(
            revision = server.revision,
            payload = JsonObject(remote.payload!! + ("name" to JsonPrimitive("Серверная история"))),
        )

    sync.run()

    assertEquals(1, tableCount("workouts"))
    assertEquals("Серверная история", workoutFull("history").workout.name)
    assertEquals(0, tableCount("backend_conflict_copies"))
  }

  @Test
  fun `routine deletion and gym measurement schedule conflicts retain every local choice before resolution`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val gymId = db.gymDao().insertGym(GymEntity(syncId = "gym", name = "Локальный зал"))
        val deletedRoutineId =
            db.routineDao()
                .upsertRoutine(RoutineEntity(syncId = "deleted-routine", name = "Удаляемая"))
        val scheduledRoutineId =
            db.routineDao()
                .upsertRoutine(RoutineEntity(syncId = "scheduled-routine", name = "По плану"))
        db.routineDao()
            .replaceRoutineExercises(
                scheduledRoutineId,
                listOf(
                    RoutineExerciseEntity(
                        routineId = scheduledRoutineId,
                        exerciseId = exerciseId,
                        position = 0,
                    )
                ),
            )
        val scheduledId =
            db.scheduledWorkoutDao()
                .insert(
                    ScheduledWorkoutEntity(
                        routineId = scheduledRoutineId,
                        dateTimeMillis = 10,
                        calendarEventId = "event",
                    )
                )
        db.bodyMeasurementDao()
            .insert(BodyMeasurementEntity(id = "measurement", measuredAt = 1, weightKg = 70.0))
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        val originalRevisions = server.records.mapValues { it.value.revision }

        db.routineDao().deleteRoutine(deletedRoutineId)
        db.gymDao().updateGym(db.gymDao().getGym(gymId)!!.copy(name = "Правка зала"))
        raw.execSQL("UPDATE body_measurements SET weightKg=71 WHERE id='measurement'")
        raw.execSQL(
            "UPDATE scheduled_workouts SET dateTimeMillis=20 WHERE id=?",
            arrayOf(scheduledId),
        )
        server.revision++
        listOf(
                "routine:deleted-routine" to "Серверная программа",
                "gym:gym" to "Серверный зал",
                "measurement:measurement" to "72",
                PortableData(raw).snapshot().keys.single { it.startsWith("schedule:") } to "30",
            )
            .forEach { (key, replacement) ->
              val record = server.records.getValue(key)
              val field =
                  when (record.kind) {
                    "routine",
                    "gym" -> "name"
                    "measurement" -> "weightKg"
                    else -> "dateTimeMillis"
                  }
              server.records[key] =
                  record.copy(
                      revision = server.revision,
                      payload =
                          JsonObject(record.payload!! + (field to JsonPrimitive(replacement))),
                  )
            }

        try {
          sync.run()
          fail("each unresolved conflict must remain manual")
        } catch (error: BackendException) {
          assertEquals("revision_conflict", error.code)
        }

        assertEquals("Правка зала", db.gymDao().getGym(gymId)!!.name)
        assertEquals(71.0, db.bodyMeasurementDao().getById("measurement")!!.weightKg)
        assertEquals(20L, db.scheduledWorkoutDao().getById(scheduledId)!!.dateTimeMillis)
        assertNull(db.routineDao().getRoutineBySyncId("deleted-routine"))
        assertEquals(0, tableCount("backend_outbox"))
        raw.query("SELECT `key`,recordJson FROM backend_baseline").use { cursor ->
          while (cursor.moveToNext()) {
            val record = server.json.decodeFromString<CloudRecord>(cursor.getString(1))
            assertEquals(originalRevisions.getValue(cursor.getString(0)), record.revision)
          }
        }
      }

  @Test
  fun `routine conflict retains server original and one full mapped dirty copy until its own ACK`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val gymId = db.gymDao().insertGym(GymEntity(syncId = "gym", name = "Зал", updatedAt = 1))
        val routineId =
            db.routineDao()
                .upsertRoutine(
                    RoutineEntity(
                        syncId = "routine",
                        name = "Локальная программа",
                        note = "Заметка",
                        updatedAt = 1,
                    )
                )
        db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))
        db.routineDao()
            .replaceRoutineExercises(
                routineId,
                listOf(
                    RoutineExerciseEntity(
                        routineId = routineId,
                        exerciseId = exerciseId,
                        position = 0,
                        restSeconds = 90,
                        plannedSets =
                            listOf(com.valerochka1337.valerochkagym.data.db.PlannedSet(80.0, 8)),
                    )
                ),
            )
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        raw.execSQL("UPDATE routines SET name='Локальная правка' WHERE id=?", arrayOf(routineId))
        val localPayload = PortableData(raw).snapshot().getValue("routine:routine")
        val remote = server.records.getValue("routine:routine")
        raw.execSQL("UPDATE backend_state SET phase='OWNED',mergeId=NULL WHERE id=1")
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload =
                    JsonObject(remote.payload!! + ("name" to JsonPrimitive("Серверная программа"))),
            )
        server.failBeforeCommit = true

        try {
          sync.run()
          fail("copy ACK is deliberately interrupted")
        } catch (_: IOException) {}

        val rows = db.routineDao().observeRoutinesFull().first()
        assertEquals(2, rows.size)
        val original = rows.single { it.routine.syncId == "routine" }
        val copy = rows.single { it.routine.syncId != "routine" }
        assertEquals("Серверная программа", original.routine.name)
        assertEquals("Локальная правка", copy.routine.name)
        assertEquals(
            localPayload["exercises"],
            PortableData(raw).snapshot().getValue("routine:${copy.routine.syncId}")["exercises"],
        )
        assertEquals(
            localPayload["gymIds"],
            PortableData(raw).snapshot().getValue("routine:${copy.routine.syncId}")["gymIds"],
        )
        assertEquals(1, tableCount("backend_conflict_copies"))
        assertEquals(1, tableCount("backend_outbox"))
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`='routine:${copy.routine.syncId}'"
            )
            .use { assertFalse(it.moveToFirst()) }

        BackendSync(db, server, Store()).run()
        assertEquals(2, db.routineDao().observeRoutinesFull().first().size)
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`='routine:${copy.routine.syncId}'"
            )
            .use { assertTrue(it.moveToFirst()) }
      }

  @Test
  fun `initial merge stays claimed until a fresh post ACK snapshot matches every batch`() =
      runTest {
        SyncSchema.install(raw)
        db.exerciseDao().insert(exercise())
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        server.afterCommit = {
          val record = server.records.getValue("exercise:${exercise().syncId}")
          server.revision++
          server.records[record.key] =
              record.copy(
                  revision = server.revision,
                  payload =
                      JsonObject(record.payload!! + ("name" to JsonPrimitive("Новее на сервере"))),
              )
        }

        sync.run()
        assertEquals(GuestSyncPhase.CLAIMED, sync.transfer.value.phase)
        assertEquals(0, tableCount("backend_outbox"))

        sync.run()
        assertEquals(GuestSyncPhase.OWNED, sync.transfer.value.phase)
        assertTrue(sync.transfer.value.initialMergeAcknowledged)
      }

  @Test
  fun `initial merge acknowledges only after all limited batches are durably ACKed`() = runTest {
    SyncSchema.install(raw)
    repeat(1_001) { index ->
      db.bodyMeasurementDao()
          .insert(BodyMeasurementEntity(id = "m-$index", measuredAt = index.toLong()))
    }
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")

    sync.run()

    assertEquals(2, server.operations.size)
    assertEquals(1_001, server.records.count { it.value.kind == "measurement" })
    assertEquals(GuestSyncPhase.OWNED, sync.transfer.value.phase)
    assertEquals(0, tableCount("backend_outbox"))
  }

  @Test
  fun `account replacement preserves catalog journals tombstones rows and A token when blocked`() =
      runTest {
        SyncSchema.install(raw)
        val store = Store()
        val sync = BackendSync(db, Server(), store)
        sync.claim("user-a")
        sync.run()
        val bytes = "{\"operationId\":\"catalog-original\",\"changes\":[]}"
        raw.execSQL(
            "UPDATE catalog_state SET pendingSnapshot='pending',originalOutbox=? WHERE id=1",
            arrayOf(bytes),
        )
        raw.execSQL(
            "INSERT INTO configuration_tombstones(kind,syncId,updatedAt) VALUES('routine','dead',1)"
        )

        try {
          sync.signIn(BackendTokens("user-b", "b@example.com", "b", "b"))
          fail("catalog journals and tombstones must block replacement")
        } catch (error: BackendException) {
          assertEquals("guest_data_preservation_required", error.code)
        }

        assertEquals("user-a", store.session.value?.userId)
        assertEquals("user-a", sync.owner())
        raw.query("SELECT pendingSnapshot,originalOutbox FROM catalog_state WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertEquals("pending", it.getString(0))
          assertEquals(bytes, it.getString(1))
        }
        assertEquals(1, tableCount("configuration_tombstones"))
      }

  @Test
  fun `active apply owner change and cancellation leave remote data and durable retry safe`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val remote = server.records.getValue("exercise:${exercise().syncId}")
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload = JsonObject(remote.payload!! + ("name" to JsonPrimitive("Не применять"))),
            )
        server.beforeGet = { insertWorkout("active-during-apply") }
        try {
          sync.run()
          fail("active transaction guard must reject apply")
        } catch (error: BackendException) {
          assertEquals("workout_active", error.code)
        }
        assertEquals("Тест", db.exerciseDao().getById(exerciseId)!!.name)
        raw.execSQL("UPDATE workouts SET finishedAt=2 WHERE id='active-during-apply'")
        server.beforeGet = {
          store.save(BackendTokens("user-b", "b@example.com", "b", "b"))
          raw.execSQL(
              "UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1"
          )
        }
        try {
          sync.run()
          fail("stale A callback must not apply under B")
        } catch (error: BackendException) {
          assertEquals("owner_changed", error.code)
        }
        assertEquals("Тест", db.exerciseDao().getById(exerciseId)!!.name)

        val cancelledStore = Store()
        raw.execSQL(
            "UPDATE backend_state SET owner='user-a',phase='CLAIMED',mergeId='a' WHERE id=1"
        )
        cancelledStore.save(BackendTokens("user-a", "a@example.com", "a", "a"))
        db.bodyMeasurementDao().insert(BodyMeasurementEntity(id = "cancel", measuredAt = 3))
        val cancelled = BackendSync(db, server, cancelledStore)
        server.beforePost = { throw CancellationException("stop") }
        try {
          cancelled.run()
          fail("cancellation must be rethrown")
        } catch (_: CancellationException) {}
        assertEquals(1, tableCount("backend_outbox"))
      }
}
