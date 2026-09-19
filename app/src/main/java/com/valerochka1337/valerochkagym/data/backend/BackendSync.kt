package com.valerochka1337.valerochkagym.data.backend

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.calendar.PersistedCalendarMigrationGate
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.LegacyCoachArchiveRegistry
import com.valerochka1337.valerochkagym.data.profile.ProfileValidator
import com.valerochka1337.valerochkagym.data.trainingproposal.AcceptedProposalResult
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Singleton
class BackendSync
@Inject
constructor(
    private val database: GymDatabase,
    private val api: BackendTransport,
    private val tokens: BackendSessionStore,
    private val migrationGate: CalendarMigrationGate = PersistedCalendarMigrationGate(database),
) : CalendarCloudStatus {
  val mutex = Mutex()
  private val catalog = CatalogSync(database, api)
  private val coachJournal = CoachJournalSync(database, api, tokens)
  private val mutableCatalogConflict = MutableStateFlow(false)
  val catalogConflict = mutableCatalogConflict.asStateFlow()
  private val mutableTransfer =
      MutableStateFlow(GuestTransferState(GuestSyncPhase.GUEST, null, null, false))
  val transfer = mutableTransfer.asStateFlow()

  suspend fun personalCopy(kind: String, id: String): String =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          database.withTransaction {
            check(!active()) { "Завершите тренировку перед созданием копии" }
            val payload = PortableData(db).snapshot(includeStandard = true).getValue("$kind:$id")
            catalog.personalCopy(kind, payload)
          }
        }
      }

  private val mutableStatus = MutableStateFlow("Ожидает синхронизации")
  val status = mutableStatus.asStateFlow()
  private val mutableCalendarCloudState = MutableStateFlow(CalendarCloudState.Pending)
  override val calendarCloudState = mutableCalendarCloudState.asStateFlow()
  private val mutableConflict = MutableStateFlow(false)
  val conflict = mutableConflict.asStateFlow()
  private var lastAcceptedCapabilities: Set<String> = emptySet()
  private val db
    get() = database.openHelper.writableDatabase

  init {
    mutableTransfer.value = transferState()
  }

  private fun baseline(): Map<String, CloudRecord> =
      db.query("SELECT recordJson FROM backend_baseline").use { c ->
        buildMap {
          while (c.moveToNext()) {
            val r = api.json.decodeFromString<CloudRecord>(c.getString(0))
            put(r.key, r)
          }
        }
      }

  private fun supports(capability: String): Boolean =
      db.query("SELECT owner,capabilityOwner,acceptedCapabilities FROM backend_state WHERE id=1")
          .use { cursor ->
            cursor.moveToFirst() &&
                !cursor.isNull(0) &&
                !cursor.isNull(1) &&
                cursor.getString(0) == cursor.getString(1) &&
                capability in cursor.getString(2).split(',')
          }

  private fun supportsCalendarPlans(): Boolean = supports("calendar-plans")

  private fun supportsExerciseHints(): Boolean = supports("exercise-hint")

  private fun supportsProfile(): Boolean = supports("profile")

  private fun supportsStrengthPlannerPersonalization(): Boolean =
      supports("strength-planner-personalization")

  private fun supportsAgenticPlanner(): Boolean = supports("ai-planner-agentic-v1")

  /** Owner-bound negotiated feature capability; optional transports must not probe when absent. */
  fun supportsHealthLedger(): Boolean = supports("health-ledger-v1")

  private fun supportsAnnotatedWorkoutWrites(): Boolean = supports("annotated-workout-writes")

  /** Missing response header is a downgrade, so an old owner cache is never reused. */
  private fun cacheAcceptedCapabilities(owner: String) {
    db.execSQL(
        "UPDATE backend_state SET capabilityOwner=?,acceptedCapabilities=? WHERE id=1 AND owner=?",
        arrayOf(owner, lastAcceptedCapabilities.sorted().joinToString(","), owner),
    )
    mutableCalendarCloudState.value =
        if (supportsCalendarPlans()) CalendarCloudState.Available
        else CalendarCloudState.Unsupported
  }

  private fun capabilityFilteredSnapshot(
      records: Map<String, JsonObject>,
      acknowledged: Map<String, CloudRecord> = emptyMap(),
  ): Map<String, JsonObject> {
    var result =
        if (supportsCalendarPlans()) records
        else records.filterKeys { !it.substringBefore(':').startsWith("calendar_") }
    if (!supportsExerciseHints()) result = result.filterKeys { !it.startsWith("exercise_hint:") }
    if (!supportsProfile()) result = result.filterKeys { !it.startsWith("profile:") }
    if (!supportsStrengthPlannerPersonalization()) {
      result =
          result.filterKeys {
            !it.startsWith("strength_planner_profile:") && !it.startsWith("workout_effort:")
          }
    }
    if (!supportsAgenticPlanner())
      result = result.filterKeys { !it.startsWith("planner_exercise_preferences:") }
    if (!supportsAnnotatedWorkoutWrites()) {
      result =
          result
              .mapNotNull { (key, payload) ->
                if (!key.startsWith("workout:")) key to payload
                else {
                  val baseline = acknowledged[key]?.payload
                  if (hasSetNote(payload) || baseline?.let(::hasSetNote) == true) null
                  else key to stripEmptySetNotes(payload)
                }
              }
              .toMap()
    }
    return result
  }

  private fun capabilityFiltered(
      records: List<CloudRecord>,
      current: Map<String, JsonObject> = emptyMap(),
      acknowledged: Map<String, CloudRecord> = emptyMap(),
  ): List<CloudRecord> =
      records
          .filter { supportsCalendarPlans() || !it.kind.startsWith("calendar_") }
          .filter { supportsExerciseHints() || it.kind != "exercise_hint" }
          .filter { supportsProfile() || it.kind != "profile" }
          .filter {
            supportsStrengthPlannerPersonalization() ||
                it.kind !in setOf("strength_planner_profile", "workout_effort")
          }
          .filter { supportsAgenticPlanner() || it.kind != "planner_exercise_preferences" }
          .mapNotNull { record ->
            if (supportsAnnotatedWorkoutWrites() || record.kind != "workout") record
            else {
              val currentPayload = current[record.key]
              val acknowledgedPayload = acknowledged[record.key]?.payload
              if (
                  record.payload?.let(::hasSetNote) == true ||
                      currentPayload?.let(::hasSetNote) == true ||
                      acknowledgedPayload?.let(::hasSetNote) == true
              )
                  null
              else if (record.deleted) record
              else record.copy(payload = record.payload?.let(::stripEmptySetNotes))
            }
          }

  private fun capabilityFilteredBaseline(
      records: Map<String, CloudRecord>,
      current: Map<String, JsonObject> = emptyMap(),
  ): Map<String, CloudRecord> {
    var result =
        if (supportsCalendarPlans()) records
        else records.filterKeys { !it.substringBefore(':').startsWith("calendar_") }
    if (!supportsExerciseHints()) result = result.filterKeys { !it.startsWith("exercise_hint:") }
    if (!supportsProfile()) result = result.filterKeys { !it.startsWith("profile:") }
    if (!supportsStrengthPlannerPersonalization()) {
      result =
          result.filterKeys {
            !it.startsWith("strength_planner_profile:") && !it.startsWith("workout_effort:")
          }
    }
    if (!supportsAgenticPlanner())
      result = result.filterKeys { !it.startsWith("planner_exercise_preferences:") }
    if (!supportsAnnotatedWorkoutWrites()) {
      result =
          result
              .mapNotNull { (key, record) ->
                if (record.kind != "workout" || record.deleted) key to record
                else if (
                    record.payload?.let(::hasSetNote) == true ||
                        current[key]?.let(::hasSetNote) == true
                )
                    null
                else key to record.copy(payload = record.payload?.let(::stripEmptySetNotes))
              }
              .toMap()
    }
    return result
  }

  private fun hasSetNote(payload: JsonObject): Boolean =
      payload["exercises"]?.jsonArray.orEmpty().any { section ->
        section.jsonObject["sets"]?.jsonArray.orEmpty().any { set ->
          set.jsonObject["note"]?.jsonPrimitive?.content.orEmpty().isNotEmpty()
        }
      }

  private fun stripEmptySetNotes(payload: JsonObject): JsonObject =
      payload
          .toMutableMap()
          .apply {
            val exercises = payload["exercises"]?.jsonArray ?: return@apply
            put(
                "exercises",
                JsonArray(
                    exercises.map { section ->
                      val item = section.jsonObject
                      JsonObject(
                          item.toMutableMap().apply {
                            val sets = item["sets"]?.jsonArray ?: return@apply
                            put("sets", JsonArray(sets.map { JsonObject(it.jsonObject - "note") }))
                          }
                      )
                    }
                ),
            )
          }
          .let(::JsonObject)

  /**
   * Retained requests are immutable: hold them instead of rebuilding or partially acknowledging.
   */
  private fun isUnsupportedChange(change: CloudChange): Boolean =
      (change.kind.startsWith("calendar_") && !supportsCalendarPlans()) ||
          (change.kind == "exercise_hint" && !supportsExerciseHints()) ||
          (change.kind == "profile" && !supportsProfile()) ||
          (change.kind in setOf("strength_planner_profile", "workout_effort") &&
              !supportsStrengthPlannerPersonalization()) ||
          (change.kind == "planner_exercise_preferences" && !supportsAgenticPlanner()) ||
          (change.kind == "workout" &&
              !supportsAnnotatedWorkoutWrites() &&
              (change.payload?.let(::hasSetNote) == true ||
                  baseline()["workout:${change.id}"]?.payload?.let(::hasSetNote) == true))

  /**
   * A profile is cleared only by an empty snapshot; a tombstone must leave all local state intact.
   */
  private fun rejectProfileTombstone(snapshot: CloudSnapshot) {
    if (snapshot.records.any { it.kind == "profile" && it.deleted })
        throw BackendException(
            409,
            "profile_tombstone_invalid",
            "Сервер вернул недопустимое удаление профиля",
        )
  }

  /** Validate remote profile records before a retained request or baseline can be changed. */
  private fun rejectInvalidProfile(snapshot: CloudSnapshot, owner: String) {
    val expectedId =
        UUID.nameUUIDFromBytes("ValerochkaGym.profile.v1:$owner".toByteArray(UTF_8)).toString()
    snapshot.records
        .filter { it.kind == "profile" && !it.deleted }
        .forEach { record ->
          if (
              record.id != expectedId ||
                  record.payload?.let {
                    ProfileValidator.wireProfile(it, record.id, System.currentTimeMillis())
                  } == null
          )
              throw BackendException(
                  409,
                  "profile_payload_invalid",
                  "Сервер вернул некорректный профиль",
              )
        }
  }

  private fun saveBaseline(r: CloudRecord) {
    db.execSQL(
        "INSERT OR REPLACE INTO backend_baseline(`key`,recordJson) VALUES (?,?)",
        arrayOf(r.key, api.json.encodeToString(r)),
    )
  }

  private fun transferState(): GuestTransferState =
      db.query("SELECT owner,phase,mergeId,initialMergeAcknowledged FROM backend_state WHERE id=1")
          .use {
            if (!it.moveToFirst()) GuestTransferState(GuestSyncPhase.GUEST, null, null, false)
            else {
              val owner = if (it.isNull(0)) null else it.getString(0)
              val phase = GuestSyncPhase.valueOf(it.getString(1))
              GuestTransferState(
                  phase,
                  owner,
                  if (it.isNull(2)) null else it.getString(2),
                  it.getInt(3) != 0,
              )
            }
          }

  fun owner(): String? = transferState().owner

  suspend fun claim(user: String): GuestClaimResult =
      withContext(Dispatchers.IO) {
        if (!migrationGate.ensureReady())
            throw BackendException(
                409,
                "calendar_migration_pending",
                "Подготовка календаря ещё не завершена",
            )
        database.withTransaction {
          SyncSchema.install(db)
          val previous = transferState()
          if (previous.phase == GuestSyncPhase.CLAIMED && previous.owner != user)
              throw BackendException(
                  409,
                  "claim_owned_by_other",
                  "Перенос данных ожидает входа в прежний аккаунт",
              )
          if (previous.owner == user && previous.phase != GuestSyncPhase.GUEST) {
            mutableTransfer.value = previous
            return@withTransaction GuestClaimResult.Claimed(previous)
          }
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          if (previous.phase == GuestSyncPhase.OWNED && previous.owner != null) {
            val footprint = preservationFootprint(previous)
            if (footprint != null) return@withTransaction GuestClaimResult.Blocked(footprint)
            clearAccountData()
          }
          moveGuestProfileToOwner(user)
          moveGuestHealthToOwner(user)
          val claimed =
              GuestTransferState(GuestSyncPhase.CLAIMED, user, UUID.randomUUID().toString(), false)
          db.execSQL(
              "UPDATE backend_state SET owner=?,phase='CLAIMED',mergeId=?,initialMergeAcknowledged=0,capabilityOwner=NULL,acceptedCapabilities='' WHERE id=1",
              arrayOf(user, claimed.mergeId),
          )
          mutableTransfer.value = claimed
          mutableCalendarCloudState.value = CalendarCloudState.Pending
          GuestClaimResult.Claimed(claimed)
        }
      }

  // Called in the same Room transaction as the owner change. Cascades remove child rows;
  // the old outbox and baseline must never be reused with another account's credentials.
  private fun clearAccountData() {
    val profileScope = transferState().owner
    listOf(
            "scheduled_workouts",
            "workouts",
            "calendar_plans",
            "calendar_rules",
            "calendar_google_links",
            "routines",
            "gyms",
            "exercises",
            "body_measurements",
            "configuration_tombstones",
            "exercise_personal_hints",
            "muscle_load_upgrade_notice",
            "backend_outbox",
            "backend_baseline",
            "backend_conflict_copies",
            "backend_rejected_operations",
            "coach_sync_state",
        )
        .forEach {
          val personal =
              if (it in setOf("exercises", "gyms", "routines")) " WHERE origin='PERSONAL'" else ""
          db.execSQL("DELETE FROM $it$personal")
        }
    LegacyCoachArchiveRegistry.purge(db)
    profileScope?.let { db.execSQL("DELETE FROM profiles WHERE scope=?", arrayOf(it)) }
    profileScope?.let {
      db.execSQL("DELETE FROM strength_planner_profiles WHERE scope=?", arrayOf(it))
    }
    profileScope?.let { db.execSQL("DELETE FROM workout_efforts WHERE scope=?", arrayOf(it)) }
    profileScope?.let {
      db.execSQL("DELETE FROM planner_exercise_preferences WHERE scope=?", arrayOf(it))
    }
    profileScope?.let { owner ->
      db.execSQL("DELETE FROM workout_preparations WHERE owner=?", arrayOf(owner))
      db.execSQL("DELETE FROM training_proposal_projections WHERE owner=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_logical_records WHERE scope=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_metric_identities WHERE scope=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_sync_baseline WHERE scope=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_sync_state WHERE scope=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_sync_outbox WHERE scope=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_sync_staging WHERE scope=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_ai_consent_state WHERE owner=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_ai_consent_outbox WHERE owner=?", arrayOf(owner))
      db.execSQL("DELETE FROM health_ai_consent_intent WHERE owner=?", arrayOf(owner))
    }
    db.execSQL("UPDATE catalog_state SET pendingSnapshot=NULL,originalOutbox=NULL WHERE id=1")
    db.execSQL("UPDATE backend_state SET capabilityOwner=NULL,acceptedCapabilities='' WHERE id=1")
    mutableCalendarCloudState.value = CalendarCloudState.Pending
  }

  /** Called inside the same transaction as guest claim, before the active scope changes. */
  private fun moveGuestProfileToOwner(owner: String) {
    val guestExists =
        db.query("SELECT 1 FROM profiles WHERE scope='GUEST'").use { it.moveToFirst() }
    val ownerExists =
        db.query("SELECT 1 FROM profiles WHERE scope=?", arrayOf(owner)).use { it.moveToFirst() }
    if (guestExists && ownerExists) {
      // This account's cached/server-authoritative singleton wins over an unrelated guest draft.
      db.execSQL("DELETE FROM profiles WHERE scope='GUEST'")
    } else if (guestExists) {
      val syncId =
          UUID.nameUUIDFromBytes("ValerochkaGym.profile.v1:$owner".toByteArray(UTF_8)).toString()
      db.execSQL("UPDATE profiles SET scope=?,syncId=? WHERE scope='GUEST'", arrayOf(owner, syncId))
    }
    val guestStrength =
        db.query("SELECT 1 FROM strength_planner_profiles WHERE scope='GUEST'").use {
          it.moveToFirst()
        }
    val ownerStrength =
        db.query("SELECT 1 FROM strength_planner_profiles WHERE scope=?", arrayOf(owner)).use {
          it.moveToFirst()
        }
    if (guestStrength && ownerStrength)
        db.execSQL("DELETE FROM strength_planner_profiles WHERE scope='GUEST'")
    else if (guestStrength) {
      val strengthSyncId =
          UUID.nameUUIDFromBytes(
                  "ValerochkaGym.strength-planner-profile.v1:$owner".toByteArray(UTF_8),
              )
              .toString()
      db.execSQL(
          "UPDATE strength_planner_profiles SET scope=?,syncId=? WHERE scope='GUEST'",
          arrayOf(owner, strengthSyncId),
      )
    }
    db.query("SELECT workoutId FROM workout_efforts WHERE scope='GUEST'").use { cursor ->
      val workoutIdColumn = cursor.getColumnIndexOrThrow("workoutId")
      while (cursor.moveToNext()) {
        val workoutId = cursor.getString(workoutIdColumn)
        val effortSyncId =
            UUID.nameUUIDFromBytes(
                    "ValerochkaGym.workout-effort.v1:$owner:$workoutId".toByteArray(UTF_8),
                )
                .toString()
        db.execSQL(
            "UPDATE workout_efforts SET scope=?,syncId=? WHERE workoutId=? AND scope='GUEST'",
            arrayOf(owner, effortSyncId, workoutId),
        )
      }
    }
    // Preference aggregate has no wire id while a user is a guest; it becomes the owner's single
    // deterministic record on the next snapshot.
    db.execSQL(
        "UPDATE planner_exercise_preferences SET scope=? WHERE scope='GUEST'",
        arrayOf(owner),
    )
  }

  /** Binds the complete guest health aggregate before the claimed owner's token is installed. */
  private fun moveGuestHealthToOwner(owner: String) {
    val tables =
        listOf(
            "health_logical_records" to "scope",
            "health_metric_identities" to "scope",
            "health_sync_baseline" to "scope",
            "health_sync_state" to "scope",
            "health_sync_outbox" to "scope",
            "health_sync_staging" to "scope",
            "health_ai_consent_state" to "owner",
            "health_ai_consent_outbox" to "owner",
            "health_ai_consent_intent" to "owner",
        )
    tables.forEach { (table, column) ->
      val exists =
          db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
              .use { it.moveToFirst() }
      if (exists) db.execSQL("UPDATE $table SET $column=? WHERE $column='GUEST'", arrayOf(owner))
    }
  }

  suspend fun signIn(session: BackendTokens) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          // Persist the new token only after its account owns a clean cache. If interrupted,
          // assertOwner prevents the previous session from accessing the new cache.
          when (val claim = claim(session.userId)) {
            is GuestClaimResult.Blocked ->
                throw BackendException(
                    409,
                    "guest_data_preservation_required",
                    "Сначала сохраните данные прежнего аккаунта",
                )
            is GuestClaimResult.Claimed -> Unit
          }
          tokens.save(session)
          mutableConflict.value = false
          mutableCatalogConflict.value = false
          mutableCalendarCloudState.value = CalendarCloudState.Pending
          mutableStatus.value = "Загружаем ваши тренировки…"
        }
      }

  suspend fun signOut(all: Boolean = false) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          val user = tokens.session.value?.userId
          if (user != null) assertOwner(user)
          try {
            if (user != null) authorized(user, "POST", if (all) "/logout-all" else "/logout")
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Local logout must work offline and with an expired session. Logging out every
            // device needs a server acknowledgement, except when this session is already gone.
            if (all && !(e is BackendException && e.status == 401)) throw e
          }
          tokens.save(null)
          mutableConflict.value = false
          mutableCatalogConflict.value = false
          mutableCalendarCloudState.value = CalendarCloudState.Pending
          mutableStatus.value = "Войдите в аккаунт"
        }
      }

  suspend fun deleteAccount(code: String) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          val user = requireNotNull(tokens.session.value?.userId)
          authorized(user, "DELETE", "/me", buildJsonObject { put("code", code) })
          database.withTransaction {
            clearAccountData()
            db.execSQL("DELETE FROM workout_preparations WHERE owner=?", arrayOf(user))
            db.execSQL("DELETE FROM training_proposal_drafts WHERE owner=?", arrayOf(user))
            db.execSQL("DELETE FROM training_proposal_operations WHERE owner=?", arrayOf(user))
            db.execSQL("DELETE FROM training_proposal_projections WHERE owner=?", arrayOf(user))
            db.execSQL("DELETE FROM backend_rejected_operations")
            db.execSQL(
                "UPDATE backend_state SET owner=NULL,phase='GUEST',mergeId=NULL,initialMergeAcknowledged=0,capabilityOwner=NULL,acceptedCapabilities='' WHERE id=1"
            )
            mutableTransfer.value = GuestTransferState(GuestSyncPhase.GUEST, null, null, false)
          }
          tokens.save(null)
          mutableConflict.value = false
          mutableCatalogConflict.value = false
          mutableStatus.value = "Войдите в аккаунт"
        }
      }

  /** The entire request chain and its UI commit belong to one captured account. */
  suspend fun accountRequests(
      expectedOwner: String,
      requests: List<Pair<String, String>>,
      commit: (JsonElement) -> Unit,
  ) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          assertOwner(expectedOwner)
          var result: JsonElement = JsonNull
          for ((method, path) in requests) {
            result = authorized(expectedOwner, method, path)
          }
          kotlinx.coroutines.currentCoroutineContext().ensureActive()
          assertOwner(expectedOwner)
          commit(result)
        }
      }

  suspend fun accountRequest(method: String, path: String, body: JsonElement? = null): JsonElement =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          val user = requireNotNull(tokens.session.value?.userId)
          authorized(user, method, path, body)
        }
      }

  private fun assertOwner(user: String) {
    val state = transferState()
    if (
        state.owner != user ||
            state.phase !in setOf(GuestSyncPhase.CLAIMED, GuestSyncPhase.OWNED) ||
            tokens.session.value?.userId != user
    )
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  private suspend fun authorized(
      user: String,
      method: String,
      path: String,
      body: JsonElement? = null,
  ): JsonElement {
    assertOwner(user)
    val response = api.authorizedResponse(method, path, body)
    if (response.owner != null && response.owner != user) {
      throw BackendException(401, "owner_changed", "Аккаунт изменился")
    }
    lastAcceptedCapabilities = response.acceptedCapabilities
    assertOwner(user)
    return response.body
  }

  private fun active(): Boolean =
      db.query("SELECT 1 FROM workouts WHERE finishedAt IS NULL LIMIT 1").use { it.moveToFirst() }

  private fun preservationFootprint(state: GuestTransferState): GuestPreservationFootprint? {
    val local = PortableData(db).snapshot()
    val base = baseline()
    val divergent = (local.keys + base.keys).any { key -> local[key] != base[key]?.payload }
    val generation =
        db.query("SELECT generation FROM backend_state WHERE id=1").use {
          it.moveToFirst()
          it.getLong(0)
        }
    val outbox =
        db.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          if (it.moveToFirst()) it.getString(0) else null
        }
    val catalogJournal =
        db.query("SELECT pendingSnapshot,originalOutbox,applying FROM catalog_state WHERE id=1")
            .use {
              if (it.moveToFirst())
                  Triple(
                      if (it.isNull(0)) null else it.getString(0),
                      if (it.isNull(1)) null else it.getString(1),
                      it.getInt(2),
                  )
              else Triple(null, null, 0)
            }
    val tombstoneRows =
        db.query("SELECT kind,syncId,updatedAt FROM configuration_tombstones ORDER BY kind,syncId")
            .use {
              buildList {
                while (it.moveToNext()) add(
                    "${it.getString(0)}:${it.getString(1)}:${it.getLong(2)}"
                )
              }
            }
    val healthUnsafe = state.owner?.let(::healthPreservationRequired) == true
    if (
        !divergent &&
            outbox == null &&
            catalogJournal == Triple(null, null, 0) &&
            tombstoneRows.isEmpty() &&
            !healthUnsafe
    )
        return null
    val material = buildString {
      append(state.phase).append('|').append(state.owner).append('|').append(generation).append('|')
      local.toSortedMap().forEach { (key, value) ->
        append(key).append('=').append(value).append('|')
      }
      base.toSortedMap().forEach { (key, value) ->
        append(key).append('=').append(value).append('|')
      }
      append(outbox).append('|').append(catalogJournal.first).append('|')
      append(catalogJournal.second).append('|').append(catalogJournal.third).append('|')
      tombstoneRows.forEach { append(it).append('|') }
      state.owner?.let { append(healthPreservationMaterial(it)) }
    }
    val fingerprint =
        MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray()).joinToString("") {
          "%02x".format(it)
        }
    return GuestPreservationFootprint(
        local.size,
        base.size,
        outbox != null,
        catalogJournal != Triple(null, null, 0),
        tombstoneRows.size,
        fingerprint,
    )
  }

  private fun healthPreservationRequired(owner: String): Boolean {
    fun exists(sql: String): Boolean = db.query(sql, arrayOf(owner)).use { it.moveToFirst() }
    return exists(
        "SELECT 1 FROM health_record_versions v JOIN health_logical_records r ON r.logicalId=v.logicalId WHERE r.scope=? AND (v.serverSequence IS NULL OR v.healthRevision IS NULL) LIMIT 1"
    ) ||
        exists("SELECT 1 FROM health_sync_outbox WHERE scope=? LIMIT 1") ||
        exists("SELECT 1 FROM health_sync_staging WHERE scope=? LIMIT 1") ||
        exists(
            "SELECT 1 FROM health_sync_state WHERE scope=? AND (needsFullRefresh=1 OR pendingCursor IS NOT NULL) LIMIT 1"
        ) ||
        exists("SELECT 1 FROM health_ai_consent_outbox WHERE owner=? LIMIT 1") ||
        exists("SELECT 1 FROM health_ai_consent_intent WHERE owner=? LIMIT 1")
  }

  /**
   * Fingerprints every retained health value, including byte journals. Counts alone cannot prove
   * that an account switch preserved immutable evidence unchanged.
   */
  private fun healthPreservationMaterial(owner: String): String = buildString {
    fun rows(label: String, query: String) {
      db.query(query, arrayOf(owner)).use { cursor ->
        while (cursor.moveToNext()) {
          append(label).append('=')
          repeat(cursor.columnCount) { index ->
            if (index > 0) append(',')
            append(if (cursor.isNull(index)) "NULL" else cursor.getString(index))
          }
          append('|')
        }
      }
    }
    rows(
        "health_logical_records",
        "SELECT quote(logicalId),quote(scope),quote(kind),quote(createdAtEpochMs),quote(currentVersionId),quote(headRevision),quote(deleted),quote(healthRevision) FROM health_logical_records WHERE scope=? ORDER BY logicalId",
    )
    rows(
        "health_record_versions",
        "SELECT quote(v.versionId),quote(v.logicalId),quote(v.parentVersionId),quote(v.kind),quote(v.state),quote(v.enteredAtEpochMs),quote(v.payloadJson),quote(v.serverSequence),quote(v.healthRevision) FROM health_record_versions v JOIN health_logical_records r ON r.logicalId=v.logicalId WHERE r.scope=? ORDER BY v.logicalId,v.versionId",
    )
    rows(
        "health_head_history",
        "SELECT quote(h.logicalId),quote(h.headRevision),quote(h.currentVersionId),quote(h.kind),quote(h.deleted),quote(h.healthRevision) FROM health_head_history h JOIN health_logical_records r ON r.logicalId=h.logicalId WHERE r.scope=? ORDER BY h.logicalId,h.headRevision",
    )
    rows(
        "health_metric_identities",
        "SELECT quote(id),quote(scope),quote(nameOriginal),quote(createdAtEpochMs) FROM health_metric_identities WHERE scope=? ORDER BY id",
    )
    rows(
        "health_sync_baseline",
        "SELECT quote(scope),quote(versionId),quote(versionJson) FROM health_sync_baseline WHERE scope=? ORDER BY versionId",
    )
    rows(
        "health_sync_state",
        "SELECT quote(scope),quote(cursor),quote(needsFullRefresh),quote(pendingCursor),quote(pendingWatermark) FROM health_sync_state WHERE scope=? ORDER BY scope",
    )
    rows(
        "health_sync_outbox",
        "SELECT quote(operationId),quote(scope),quote(requestBytes),quote(requestSha256),quote(dispatched) FROM health_sync_outbox WHERE scope=? ORDER BY operationId",
    )
    rows(
        "health_sync_staging",
        "SELECT quote(scope),quote(healthRevision),quote(eventKind),quote(eventId),quote(eventJson) FROM health_sync_staging WHERE scope=? ORDER BY healthRevision,eventKind,eventId",
    )
    rows(
        "health_ai_consent_state",
        "SELECT quote(owner),quote(revision),quote(noticeVersion),quote(enabled),quote(recordedAtEpochMs),quote(receiptBytes) FROM health_ai_consent_state WHERE owner=? ORDER BY owner",
    )
    rows(
        "health_ai_consent_outbox",
        "SELECT quote(owner),quote(operationId),quote(requestBytes),quote(requestSha256),quote(dispatched) FROM health_ai_consent_outbox WHERE owner=? ORDER BY owner",
    )
    rows(
        "health_ai_consent_intent",
        "SELECT quote(owner),quote(enabled) FROM health_ai_consent_intent WHERE owner=? ORDER BY owner",
    )
  }

  private fun canonicalFingerprint(value: JsonElement): String {
    fun canonical(element: JsonElement): String =
        when (element) {
          is JsonObject ->
              element.entries
                  .sortedBy { it.key }
                  .joinToString(prefix = "{", postfix = "}") { "${it.key}:${canonical(it.value)}" }
          is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonical(it) }
          else -> element.toString()
        }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
  }

  suspend fun hasActiveWorkout() = withContext(Dispatchers.IO) { active() }

  private data class AiReadySnapshot(
      val owner: String,
      val sessionEpoch: Long,
      val cacheGeneration: Long,
  )

  /**
   * Reads local state only. The caller holds [mutex] when a network acknowledgement is in flight.
   */
  private fun aiReadySnapshot(owner: String): AiReadySnapshot? {
    val state = transferState()
    val session = tokens.snapshot() ?: return null
    if (
        state.owner != owner ||
            state.phase != GuestSyncPhase.OWNED ||
            !state.initialMergeAcknowledged ||
            session.tokens.userId != owner ||
            active() ||
            mutableConflict.value ||
            mutableCatalogConflict.value ||
            db.query("SELECT 1 FROM backend_outbox WHERE id=1").use { it.moveToFirst() } ||
            db.query(
                    "SELECT 1 FROM catalog_state WHERE id=1 AND (applying=1 OR pendingSnapshot IS NOT NULL OR originalOutbox IS NOT NULL)"
                )
                .use { it.moveToFirst() }
    )
        return null
    val generation =
        db.query("SELECT generation FROM backend_state WHERE id=1").use {
          if (it.moveToFirst()) it.getLong(0) else return null
        }
    return AiReadySnapshot(owner, session.epoch, generation)
  }

  /** Does not start sync or issue HTTP; it rejects any owner/session/cache change after [ready]. */
  suspend fun isAiReadyCurrent(ready: SyncReady.Ready): Boolean =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          aiReadySnapshot(ready.owner)?.let {
            it.sessionEpoch == ready.sessionEpoch && it.cacheGeneration == ready.cacheGeneration
          } ?: false
        }
      }

  /**
   * Performs sync, then captures a fresh final personal acknowledgement under the same mutex. The
   * receipt is deliberately separate from every per-record baseline revision.
   */
  suspend fun awaitAiReady(): SyncReady =
      withContext(Dispatchers.IO) {
        val initialOwner =
            tokens.snapshot()?.tokens?.userId
                ?: return@withContext SyncReady.Failure(
                    "Войдите в аккаунт",
                    BackendException(401, "unauthorized", "Войдите в аккаунт"),
                )
        try {
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          run()
          mutex.withLock {
            val beforeAcknowledgement =
                aiReadySnapshot(initialOwner) ?: return@withLock SyncReady.Blocked
            val acknowledged =
                api.json.decodeFromJsonElement<CloudSnapshot>(
                    authorized(initialOwner, "GET", "/sync"),
                )
            val catalogRevision = catalog.revision()
            val afterAcknowledgement =
                aiReadySnapshot(initialOwner) ?: return@withLock SyncReady.Blocked
            if (afterAcknowledgement != beforeAcknowledgement) SyncReady.Blocked
            else
                SyncReady.Ready(
                    owner = initialOwner,
                    revision = acknowledged.revision,
                    catalogRevision = catalogRevision,
                    sessionEpoch = beforeAcknowledgement.sessionEpoch,
                    cacheGeneration = beforeAcknowledgement.cacheGeneration,
                )
          }
        } catch (error: kotlinx.coroutines.CancellationException) {
          throw error
        } catch (error: Exception) {
          SyncReady.Failure("Не удалось подготовить данные для нейросети", error)
        }
      }

  /** Receive-only proposal projection: normal merge, baseline and marker share one transaction. */
  suspend fun applyApprovedProposal(
      expected: BackendSessionSnapshot,
      result: AcceptedProposalResult,
      markApplied: suspend (Long) -> Unit,
  ) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          fun guard() {
            assertOwner(expected.tokens.userId)
            if (tokens.snapshot()?.epoch != expected.epoch || active())
                throw BackendException(
                    409,
                    "proposal_context_changed",
                    "Аккаунт или активная тренировка изменились",
                )
          }
          guard()
          val response =
              api.authorizedRawResponse(
                  "GET",
                  "/sync",
                  ByteArray(0),
                  headers =
                      mapOf(
                          "X-Gym-Capabilities" to
                              "calendar-plans,exercise-hint,annotated-workout-writes,profile,health-ledger-v1,strength-planner-personalization"
                      ),
                  expectedOwner = expected.tokens.userId,
                  expectedSessionEpoch = expected.epoch,
                  retryOnUnauthorized = false,
              )
          guard()
          if (
              response.owner != expected.tokens.userId ||
                  response.sessionEpoch != expected.epoch ||
                  "calendar-plans" !in response.acceptedCapabilities
          )
              throw BackendException(
                  409,
                  "proposal_context_changed",
                  "Не удалось подтвердить данные календаря",
              )
          lastAcceptedCapabilities = response.acceptedCapabilities
          val remote = api.json.decodeFromJsonElement<CloudSnapshot>(response.body)
          rejectProfileTombstone(remote)
          rejectInvalidProfile(remote, expected.tokens.userId)
          cacheAcceptedCapabilities(expected.tokens.userId)
          require(
              remote.records.any {
                it.kind == "routine" && it.id == result.routineId && !it.deleted
              }
          )
          require(
              remote.records.any {
                it.kind == "calendar_plan" && it.id == result.calendarPlanId && !it.deleted
              }
          )
          applyRemoteSnapshot(expected.tokens.userId, remote) {
            guard()
            val linked =
                db.query(
                        "SELECT 1 FROM calendar_plans p JOIN routines r ON r.id=p.routineId WHERE p.id=? AND r.syncId=?",
                        arrayOf(result.calendarPlanId, result.routineId),
                    )
                    .use { it.moveToFirst() }
            check(linked)
            markApplied(remote.revision)
          }
          guard()
        }
      }

  /**
   * Receive-only projection for a server-created routine share import. The server owns the import
   * transaction and receipt; Android only reconciles the returned cloud record into Room.
   */
  suspend fun applyImportedRoutine(
      expected: BackendSessionSnapshot,
      routineId: String,
      importedRevision: Long,
  ) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          fun guard() {
            assertOwner(expected.tokens.userId)
            if (tokens.snapshot()?.epoch != expected.epoch || active())
                throw BackendException(
                    409,
                    "routine_share_context_changed",
                    "Аккаунт или активная тренировка изменились",
                )
          }
          guard()
          val response =
              api.authorizedRawResponse(
                  "GET",
                  "/sync",
                  ByteArray(0),
                  headers =
                      mapOf(
                          "X-Gym-Capabilities" to
                              "calendar-plans,exercise-hint,annotated-workout-writes,profile,health-ledger-v1,strength-planner-personalization"
                      ),
                  expectedOwner = expected.tokens.userId,
                  expectedSessionEpoch = expected.epoch,
                  retryOnUnauthorized = false,
              )
          guard()
          if (response.owner != expected.tokens.userId || response.sessionEpoch != expected.epoch)
              throw BackendException(
                  409,
                  "routine_share_context_changed",
                  "Не удалось подтвердить импорт программы",
              )
          val remote = api.json.decodeFromJsonElement<CloudSnapshot>(response.body)
          if (remote.revision < importedRevision)
              throw BackendException(
                  409,
                  "routine_share_pending",
                  "Импорт ещё не появился в синхронизации",
              )
          require(remote.records.any { it.kind == "routine" && it.id == routineId && !it.deleted })
          rejectProfileTombstone(remote)
          rejectInvalidProfile(remote, expected.tokens.userId)
          lastAcceptedCapabilities = response.acceptedCapabilities
          cacheAcceptedCapabilities(expected.tokens.userId)
          applyRemoteSnapshot(expected.tokens.userId, remote) {
            guard()
            val imported =
                db.query("SELECT 1 FROM routines WHERE syncId=?", arrayOf(routineId)).use {
                  it.moveToFirst()
                }
            check(imported)
          }
          guard()
        }
      }

  private suspend fun applyRemoteSnapshot(
      user: String,
      remoteSnapshot: CloudSnapshot,
      personalResolve: String? = null,
      rejectedByRevisionConflict: Boolean = false,
      afterApply: suspend () -> Unit = {},
  ) {
    database.withTransaction {
      assertOwner(user)
      if (active())
          throw BackendException(
              409,
              "workout_active",
              "Завершите тренировку перед синхронизацией",
          )
      val rawCurrent = PortableData(db).snapshot()
      val rawBase = baseline()
      val remote =
          remoteSnapshot.copy(
              records = capabilityFiltered(remoteSnapshot.records, rawCurrent, rawBase)
          )
      val local = capabilityFilteredSnapshot(rawCurrent, rawBase)
      val base = capabilityFilteredBaseline(rawBase, rawCurrent)
      val conflicts = CloudMerge.conflicts(local, base, remote.records)
      val automatic =
          remote.records.filter { record ->
            record.key in conflicts &&
                (record.kind in setOf("workout", "profile") ||
                    record.kind == "routine" && !record.deleted && local[record.key] != null)
          }
      val manual = conflicts - automatic.map { it.key }.toSet()
      if (manual.isNotEmpty() && personalResolve == null) {
        mutableConflict.value = true
        throw BackendException(
            409,
            "revision_conflict",
            "Есть изменения с другого устройства. Выберите версию в настройках аккаунта",
        )
      }
      automatic
          .filter { it.kind == "routine" }
          .forEach { remoteRoutine ->
            val state = transferState()
            val mergeId = state.mergeId ?: "owned:${requireNotNull(state.owner)}"
            val localPayload = requireNotNull(local[remoteRoutine.key])
            val fingerprint = canonicalFingerprint(localPayload)
            val existing =
                db.query(
                        "SELECT localCopySyncId FROM backend_conflict_copies WHERE mergeId=? AND kind=? AND originalSyncId=? AND remoteRevision=? AND localPayloadFingerprint=?",
                        arrayOf<Any>(
                            mergeId,
                            remoteRoutine.kind,
                            remoteRoutine.id,
                            remoteRoutine.revision,
                            fingerprint,
                        ),
                    )
                    .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existing == null) {
              val copyId = UUID.randomUUID().toString()
              PortableData(db)
                  .apply(
                      listOf(CloudRecord("routine", copyId, 0, false, localPayload)),
                      emptyList(),
                  )
              db.execSQL(
                  "INSERT INTO backend_conflict_copies(mergeId,kind,originalSyncId,remoteRevision,localPayloadFingerprint,localCopySyncId) VALUES(?,?,?,?,?,?)",
                  arrayOf<Any>(
                      mergeId,
                      "routine",
                      remoteRoutine.id,
                      remoteRoutine.revision,
                      fingerprint,
                      copyId,
                  ),
              )
            }
          }
      val incoming =
          remote.records.filter { r ->
            val changed = base[r.key]?.revision != r.revision
            val localChanged = local[r.key] != base[r.key]?.payload
            changed &&
                (!localChanged ||
                    local[r.key] == r.payload ||
                    r.key in automatic.map { it.key }.toSet() ||
                    personalResolve == "server" && r.key in conflicts ||
                    base[r.key] == null &&
                        r.kind == "exercise" &&
                        local[r.key]?.get("isCustom")?.toString() == "false")
          }
      PortableData(db).apply(incoming.filter { !it.deleted }, incoming.filter { it.deleted })
      remote.records.forEach(::saveBaseline)
      if (rejectedByRevisionConflict) {
        db.execSQL("DELETE FROM backend_outbox WHERE id=1")
        db.execSQL("DELETE FROM backend_rejected_operations WHERE owner=?", arrayOf(user))
      }
      afterApply()
    }
  }

  suspend fun run(resolve: String? = null) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          try {
            if (!migrationGate.ensureReady()) {
              mutableStatus.value = "Подготовка календаря ещё не завершена"
              return@withLock
            }
            if (active()) {
              mutableStatus.value = "Синхронизация продолжится после тренировки"
              return@withLock
            }
            val user =
                tokens.session.value?.userId
                    ?: run {
                      catalog.refresh(resolve)
                      return@withLock
                    }
            assertOwner(user)
            val wasCatalogConflict =
                mutableCatalogConflict.value ||
                    db.query("SELECT pendingSnapshot FROM catalog_state WHERE id=1").use {
                      it.moveToFirst() && !it.isNull(0)
                    }
            catalog.refresh(resolve) { assertOwner(user) }
            val personalResolve = if (wasCatalogConflict) null else resolve
            mutableCatalogConflict.value = false
            assertOwner(user)
            mutableStatus.value = "Синхронизация…"
            // No network response is allowed to mutate the set IDs currently used by the foreground
            // service.
            if (active()) {
              mutableStatus.value = "Синхронизация продолжится после тренировки"
              return@withLock
            }
            val pending =
                db.query("SELECT owner,requestJson FROM backend_outbox WHERE id=1").use { c ->
                  if (c.moveToFirst()) {
                    check(c.getString(0) == user)
                    val push = api.json.decodeFromString<CloudPush>(c.getString(1))
                    val rejected =
                        db.query(
                                "SELECT 1 FROM backend_rejected_operations WHERE operationId=? AND owner=?",
                                arrayOf(push.operationId, user),
                            )
                            .use { it.moveToFirst() }
                    push to rejected
                  } else null
                }
            // Refresh optional capabilities before any retained bytes are posted. A downgrade
            // keeps the journal byte-identical until that exact request can be sent safely.
            val capabilitySnapshot =
                api.json.decodeFromJsonElement<CloudSnapshot>(authorized(user, "GET", "/sync"))
            rejectProfileTombstone(capabilitySnapshot)
            rejectInvalidProfile(capabilitySnapshot, user)
            cacheAcceptedCapabilities(user)
            var rejectedByRevisionConflict = false
            if (pending != null) {
              val pendingHasUnsupportedCapability = pending.first.changes.any(::isUnsupportedChange)
              if (pendingHasUnsupportedCapability) {
                mutableStatus.value = "Часть данных ожидает поддержку сервера"
              } else if (!pending.second) {
                try {
                  send(user, pending.first)
                } catch (error: BackendException) {
                  if (error.status == 409 && error.code == "revision_conflict") {
                    database.withTransaction {
                      assertOwner(user)
                      db.execSQL(
                          "INSERT OR IGNORE INTO backend_rejected_operations(operationId,owner) VALUES(?,?)",
                          arrayOf(pending.first.operationId, user),
                      )
                    }
                    rejectedByRevisionConflict = true
                  } else {
                    throw error
                  }
                }
              }
              rejectedByRevisionConflict = rejectedByRevisionConflict || pending.second
            }
            val remoteSnapshot =
                api.json.decodeFromJsonElement<CloudSnapshot>(authorized(user, "GET", "/sync"))
            rejectProfileTombstone(remoteSnapshot)
            rejectInvalidProfile(remoteSnapshot, user)
            cacheAcceptedCapabilities(user)
            applyRemoteSnapshot(user, remoteSnapshot, personalResolve, rejectedByRevisionConflict)
            // A retained unsupported request is a byte-exact journal, not a staging area for the
            // next compatible mutation. Refreshes above may safely update compatible state, but
            // fresh capture waits until this durable request is acknowledged or rejected.
            if (
                db.query("SELECT 1 FROM backend_outbox WHERE id=1 AND owner=?", arrayOf(user)).use {
                  it.moveToFirst()
                }
            )
                return@withLock
            // Capture each exact payload durably before HTTP. A local edit during HTTP is compared
            // against
            // the acknowledged payload in the next iteration, so acknowledgement never clears a
            // newer edit.
            repeat(100) {
              val batch =
                  database.withTransaction {
                    assertOwner(user)
                    if (active()) return@withTransaction null
                    val rawBase = baseline()
                    val rawCurrent = PortableData(db).snapshot()
                    val local = capabilityFilteredSnapshot(rawCurrent, rawBase)
                    val base = capabilityFilteredBaseline(rawBase, rawCurrent)
                    val order =
                        listOf(
                            "profile",
                            "exercise",
                            "exercise_hint",
                            "gym",
                            "routine",
                            "workout",
                            "measurement",
                            "schedule",
                            "calendar_plan",
                            "calendar_rule",
                            "calendar_exception",
                        )
                    val changes =
                        (local.keys + base.keys)
                            .mapNotNull { key ->
                              val old = base[key]
                              val payload = local[key]
                              if (payload == old?.payload) null
                              else {
                                val kind = key.substringBefore(':')
                                val id = key.substringAfter(':')
                                CloudChange(kind, id, old?.revision ?: 0, payload == null, payload)
                              }
                            }
                            .sortedBy {
                              if (it.deleted) order.size - order.indexOf(it.kind)
                              else order.indexOf(it.kind)
                            }
                            .take(1000)
                    if (changes.isEmpty()) null
                    else
                        CloudPush(UUID.randomUUID().toString(), changes, catalog.revision()).also {
                          db.execSQL(
                              "INSERT OR REPLACE INTO backend_outbox(id,owner,requestJson) VALUES (1,?,?)",
                              arrayOf(user, api.json.encodeToString(it)),
                          )
                        }
                  }
              if (batch == null) {
                val acknowledged =
                    api.json.decodeFromJsonElement<CloudSnapshot>(authorized(user, "GET", "/sync"))
                database.withTransaction {
                  assertOwner(user)
                  val rawBase = baseline()
                  val rawCurrent = PortableData(db).snapshot()
                  val local = capabilityFilteredSnapshot(rawCurrent, rawBase)
                  val base = capabilityFilteredBaseline(rawBase, rawCurrent)
                  baseline()
                      .values
                      .filter { !it.deleted && it.payload == local[it.key] }
                      .forEach { r ->
                        val table =
                            when (r.kind) {
                              "workout" -> "workouts"
                              "measurement" -> "body_measurements"
                              else -> null
                            }
                        if (table != null)
                            db.execSQL(
                                "UPDATE $table SET uploadStatus='UPLOADED',uploadError=NULL WHERE id=? AND uploadStatus!='UPLOADED'",
                                arrayOf(r.id),
                            )
                      }
                  val state = transferState()
                  val matchesAcknowledgedSnapshot =
                      acknowledged.records.all { record ->
                        base[record.key] == record &&
                            (record.deleted || local[record.key] == record.payload)
                      } &&
                          local.keys.all { key ->
                            val record = base[key]
                            record != null && !record.deleted && record.payload == local[key]
                          }
                  if (
                      state.phase == GuestSyncPhase.CLAIMED &&
                          matchesAcknowledgedSnapshot &&
                          !db.query("SELECT 1 FROM backend_outbox WHERE id=1").use {
                            it.moveToFirst()
                          }
                  ) {
                    db.execSQL(
                        "UPDATE backend_state SET phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
                    )
                    mutableTransfer.value =
                        state.copy(phase = GuestSyncPhase.OWNED, initialMergeAcknowledged = true)
                  }
                }
                coachJournal.run(user)
                mutableStatus.value = "Данные синхронизированы"
                mutableConflict.value = false
                mutableCatalogConflict.value = false
                return@withLock
              }
              send(user, batch)
            }
            mutableStatus.value = "Синхронизация продолжится в фоне"
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is BackendException && e.code == "catalog_transition_required") {
              mutableCatalogConflict.value = true
              mutableConflict.value = true
            }
            if (e is BackendException && e.code == "revision_conflict") mutableConflict.value = true
            mutableStatus.value =
                if (e is BackendException) e.message
                else "Нет подключения. Повторим сохранение позже"
            throw e
          }
        }
      }

  private suspend fun send(user: String, push: CloudPush) {
    assertOwner(user)
    var sent = push
    var acknowledgedSession: BackendSessionSnapshot? = null
    suspend fun post(value: CloudPush): CloudAck {
      val raw =
          db.query("SELECT requestJson FROM backend_outbox WHERE id=1 AND owner=?", arrayOf(user))
              .use {
                if (!it.moveToFirst())
                    throw BackendException(409, "outbox_missing", "Запрос изменился")
                it.getString(0)
              }
      // The operation identity proves the bytes belong to this attempt without normalizing them.
      if (api.json.decodeFromString<CloudPush>(raw).operationId != value.operationId)
          throw BackendException(409, "outbox_changed", "Запрос изменился")
      val dispatch =
          tokens.snapshot() ?: throw BackendException(401, "owner_changed", "Аккаунт изменился")
      val response =
          api.authorizedRawResponse(
              method = "POST",
              path = "/sync",
              rawBody = raw.encodeToByteArray(),
              expectedOwner = user,
              expectedSessionEpoch = dispatch.epoch,
              retryOnUnauthorized = true,
          )
      if (
          response.owner != user ||
              response.sessionEpoch != dispatch.epoch ||
              tokens.snapshot() != dispatch
      )
          throw BackendException(401, "owner_changed", "Аккаунт изменился")
      assertOwner(user)
      acknowledgedSession = dispatch
      return api.json.decodeFromJsonElement(response.body)
    }
    val ack =
        try {
          try {
            post(sent)
          } catch (e: BackendException) {
            if (e.code != "catalog_stale") throw e
            catalog.refresh { assertOwner(user) }
            sent =
                push.copy(
                    operationId = UUID.randomUUID().toString(),
                    catalogRevision = catalog.revision(),
                )
            database.withTransaction {
              assertOwner(user)
              if (active())
                  throw BackendException(
                      409,
                      "workout_active",
                      "Завершите тренировку перед синхронизацией",
                  )
              db.execSQL(
                  "UPDATE backend_outbox SET requestJson=? WHERE id=1",
                  arrayOf(api.json.encodeToString(sent)),
              )
            }
            post(sent)
          }
        } catch (error: BackendException) {
          if (error.status == 409 && error.code == "revision_conflict")
              database.withTransaction {
                assertOwner(user)
                db.execSQL(
                    "INSERT OR IGNORE INTO backend_rejected_operations(operationId,owner) VALUES(?,?)",
                    arrayOf(sent.operationId, user),
                )
              }
          throw error
        }
    database.withTransaction {
      assertOwner(user)
      if (tokens.snapshot() != acknowledgedSession)
          throw BackendException(401, "owner_changed", "Аккаунт изменился")
      sent.changes.forEach {
        saveBaseline(CloudRecord(it.kind, it.id, ack.revision, it.deleted, it.payload))
      }
      db.execSQL("DELETE FROM backend_outbox WHERE id=1")
    }
  }
}
