package com.valerochka1337.valerochkagym.ui.coachrelation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.coachrelation.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Pages, grants in progress and invitation secrets never enter saved state or Room.
data class CoachRelationsState(
    val clients: Boolean = true,
    val relations: List<Relation> = emptyList(),
    val relation: Relation? = null,
    val calendar: List<CalendarProjectionItem> = emptyList(),
    val completed: List<CompletedWorkoutProjectionItem> = emptyList(),
    val calendarCursor: String? = null,
    val completedCursor: String? = null,
    val recipientRevision: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val invite: InvitationCreated? = null,
    val operations: List<CoachRelationOperationEntity> = emptyList(),
    val proposals: List<TrainingProposal> = emptyList(),
    val draft: ApprovalDraft? = null,
    val editing: TrainingProposal? = null,
    val exercises: List<Pair<String, String>> = emptyList(),
    val exerciseTypes: Map<String, ExerciseType> = emptyMap(),
)

@HiltViewModel
class CoachRelationsViewModel
@Inject
constructor(
    private val repo: CoachRelationsRepository,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val db: GymDatabase,
    exercises: ExerciseDao,
) : ViewModel() {
  private val state = MutableStateFlow(CoachRelationsState())
  val uiState =
      combine(state, exercises.getAll()) { s, es ->
            s.copy(
                exerciseTypes =
                    s.completed
                        .flatMap { it.exercises }
                        .associate { exercise ->
                          exercise.exerciseId to
                              when {
                                exercise.sets.any {
                                  it.speedKmh != null || it.inclinePct != null
                                } -> ExerciseType.CARDIO
                                exercise.sets.any { it.durationSec != null } -> ExerciseType.TIMED
                                else -> ExerciseType.STRENGTH
                              }
                        } +
                        es.filter { !it.archived && it.origin == "STANDARD" }
                            .associate { it.syncId to it.type },
                exercises =
                    (es.filter { !it.archived && it.origin == "STANDARD" }
                            .map { it.syncId to it.name } +
                            s.completed.flatMap { it.exercises }.map { it.exerciseId to it.name })
                        .distinctBy { it.first },
            )
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CoachRelationsState())
  private var epoch = 0L
  private var job: Job? = null
  private var bound: BackendSessionSnapshot? = null

  init {
    viewModelScope.launch {
      combine(sessions.session, sync.transfer) { _, _ -> Unit }
          .collect {
            if (bound?.let(repo::current) == false) {
              epoch++
              job?.cancel()
              bound = null
              state.value = CoachRelationsState(error = "Аккаунт изменился. Обновите список")
            }
          }
    }
  }

  fun refresh(
      clients: Boolean = state.value.clients,
      selected: String? = state.value.relation?.relationId,
  ) {
    launch(replace = true) { s ->
      state.update { CoachRelationsState(clients = clients, busy = true) }
      val items = directory(s, clients)
      checkCurrent(s)
      state.update {
        it.copy(relations = items, relation = items.find { r -> r.relationId == selected })
      }
      refreshOperations(s)
    }
  }

  private suspend fun directory(s: BackendSessionSnapshot, clients: Boolean): List<Relation> {
    repeat(2) { attempt ->
      try {
        val all = mutableListOf<Relation>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var revision: Long? = null
        do {
          val p = repo.directory(s, clients, cursor)
          checkCurrent(s)
          if (revision != null && revision != p.directoryRevision)
              throw BackendException(409, "relation_snapshot_changed", "Список изменился")
          revision = p.directoryRevision
          all += p.items
          cursor = p.nextCursor
          if (cursor != null) require(cursors.add(cursor))
        } while (cursor != null)
        require(all.distinctBy { it.relationId }.size == all.size)
        return all
      } catch (e: BackendException) {
        if (attempt == 1 || !CoachRelationsRepository.restartable(e)) throw e
      }
    }
    error("Список изменился")
  }

  fun open(id: String) {
    state.update {
      it.copy(
          relation = it.relations.find { r -> r.relationId == id },
          calendar = emptyList(),
          completed = emptyList(),
          recipientRevision = null,
          draft = null,
          editing = null,
          error = null,
      )
    }
    refresh(state.value.clients, id)
  }

  fun createInvite() {
    launch { s ->
      val r = repo.createInvite(s)
      checkCurrent(s)
      state.update { it.copy(invite = r) }
      refreshOperations(s)
    }
  }

  fun clearInvite() {
    state.update { it.copy(invite = null) }
  }

  fun accept(token: String, calendar: Boolean, completed: Boolean) {
    launch { s ->
      repo.acceptInvite(s, token, calendar, completed)
      checkCurrent(s)
      val rows = directory(s, false)
      state.update { it.copy(clients = false, relations = rows, invite = null) }
      refreshOperations(s)
    }
  }

  fun revoke() {
    val r = state.value.relation ?: return
    launch { s ->
      val revoked = repo.revoke(s, r.relationId)
      checkCurrent(s)
      state.update {
        it.copy(
            relation = revoked,
            calendar = emptyList(),
            completed = emptyList(),
            recipientRevision = null,
            draft = null,
            editing = null,
            proposals = emptyList(),
        )
      }
      refreshOperations(s)
    }
  }

  fun projection(calendar: Boolean, more: Boolean = false) {
    val r = state.value.relation ?: return
    if (
        r.state != "ACTIVE" ||
            !state.value.clients ||
            (calendar && !r.calendar) ||
            (!calendar && !r.completedWorkouts)
    )
        return
    launch { s ->
      var cursor =
          if (more) if (calendar) state.value.calendarCursor else state.value.completedCursor
          else null
      var append = more && cursor != null
      repeat(2) { attempt ->
        try {
          if (calendar) {
            val p = repo.calendar(s, r.relationId, cursor)
            checkCurrent(s)
            if (append && state.value.recipientRevision != p.recipientSyncRevision)
                throw BackendException(409, "relation_snapshot_changed", "Данные изменились")
            state.update {
              it.copy(
                  calendar =
                      (if (append) it.calendar + p.items else p.items).distinctBy { v ->
                        v.calendarPlanId
                      },
                  completed =
                      if (it.recipientRevision == p.recipientSyncRevision) it.completed
                      else emptyList(),
                  calendarCursor = p.nextCursor,
                  recipientRevision = p.recipientSyncRevision,
              )
            }
          } else {
            val p = repo.completed(s, r.relationId, cursor)
            checkCurrent(s)
            if (append && state.value.recipientRevision != p.recipientSyncRevision)
                throw BackendException(409, "relation_snapshot_changed", "Данные изменились")
            state.update {
              it.copy(
                  completed =
                      (if (append) it.completed + p.items else p.items).distinctBy { v ->
                        v.workoutId
                      },
                  calendar =
                      if (it.recipientRevision == p.recipientSyncRevision) it.calendar
                      else emptyList(),
                  completedCursor = p.nextCursor,
                  recipientRevision = p.recipientSyncRevision,
              )
            }
          }
          return@launch
        } catch (e: BackendException) {
          if (attempt == 1 || !CoachRelationsRepository.restartable(e)) throw e
          cursor = null
          append = false
          state.update {
            it.copy(
                calendar = emptyList(),
                completed = emptyList(),
                calendarCursor = null,
                completedCursor = null,
                recipientRevision = null,
            )
          }
        }
      }
    }
  }

  fun newDraft() {
    state.update {
      it.copy(
          editing = null,
          draft =
              ApprovalDraft(
                  "Тренировка",
                  emptyList(),
                  emptyList(),
                  System.currentTimeMillis() + 86400000,
                  java.time.ZoneId.systemDefault().id,
              ),
      )
    }
  }

  fun edit(p: TrainingProposal) {
    state.update { it.copy(editing = p, draft = p.snapshot.draft) }
  }

  fun updateDraft(d: ApprovalDraft) {
    if (!state.value.busy) state.update { it.copy(draft = d) }
  }

  fun closeDraft() {
    state.update { it.copy(draft = null, editing = null) }
  }

  fun submit() {
    val v = state.value
    val r = v.relation ?: return
    val d = v.draft ?: return
    if (!v.clients || r.state != "ACTIVE") return
    launch { s ->
      if (v.editing != null) repo.reviseProposal(s, r.relationId, v.editing, d)
      else {
        // The server projection provides the recipient's acknowledged revision; never use ours.
        val revision =
            when {
              r.calendar -> repo.calendar(s, r.relationId).recipientSyncRevision
              r.completedWorkouts -> repo.completed(s, r.relationId).recipientSyncRevision
              else ->
                  throw BackendException(
                      403,
                      "projection_required",
                      "Для подготовки нужен доступ к календарю или истории",
                  )
            }
        val catalog =
            withContext(Dispatchers.IO) {
              db.openHelper.readableDatabase
                  .query("SELECT revision FROM catalog_state WHERE id=1")
                  .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            }
        repo.createProposal(s, r.relationId, revision, catalog, d)
      }
      checkCurrent(s)
      state.update { it.copy(draft = null, editing = null) }
      refreshOperations(s)
    }
  }

  fun revokeProposal(p: TrainingProposal) {
    val r = state.value.relation ?: return
    launch { s ->
      repo.revokeProposal(s, r.relationId, p)
      refreshOperations(s)
    }
  }

  fun retry(id: String) {
    launch { s ->
      repo.retry(s, id)
      checkCurrent(s)
      refreshOperations(s)
      val r = state.value.relation
      val rows = directory(s, state.value.clients)
      state.update {
        it.copy(relations = rows, relation = rows.find { v -> v.relationId == r?.relationId })
      }
    }
  }

  private suspend fun refreshOperations(s: BackendSessionSnapshot) {
    val ops = repo.operations(s)
    checkCurrent(s)
    val relationId = state.value.relation?.relationId
    val proposals =
        ops.filter {
              it.resource.substringBefore(',') == relationId &&
                  it.action in setOf("CREATE_COACH_PROPOSAL", "REVISE_COACH_PROPOSAL") &&
                  it.resultJson != null
            }
            .mapNotNull {
              runCatching {
                    ProposalWire.decode<TrainingProposal>(it.resultJson!!.encodeToByteArray())
                  }
                  .getOrNull()
            }
            .groupBy { it.proposalId }
            .values
            .map { it.maxBy { p -> p.currentVersion } }
            .map { p ->
              if (
                  ops.any {
                    it.action == "REVOKE_COACH_PROPOSAL" &&
                        it.resource.endsWith(",${p.proposalId}") &&
                        it.state == "SUCCEEDED"
                  }
              )
                  p.copy(status = ProposalStatus.REVOKED)
              else p
            }
    state.update { it.copy(operations = ops, proposals = proposals) }
  }

  private fun checkCurrent(s: BackendSessionSnapshot) {
    if (!repo.current(s)) throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  private fun launch(replace: Boolean = false, block: suspend (BackendSessionSnapshot) -> Unit) {
    if (state.value.busy && !replace) return
    if (replace) {
      epoch++
      job?.cancel()
    }
    val token = epoch
    state.update { it.copy(busy = true, error = null) }
    job =
        viewModelScope.launch {
          try {
            val s = repo.session()
            bound = s
            block(s)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            if (token == epoch) {
              val denied = e is BackendException && e.code == "relation_not_found"
              state.update {
                if (denied)
                    it.copy(
                        relation = null,
                        calendar = emptyList(),
                        completed = emptyList(),
                        draft = null,
                        editing = null,
                        proposals = emptyList(),
                        recipientRevision = null,
                        error = "Доступ закрыт или связь отозвана",
                    )
                else
                    it.copy(
                        error =
                            when ((e as? BackendException)?.code) {
                              "owner_changed",
                              "unauthorized" -> "Войдите в аккаунт и обновите список"
                              "relation_pending" -> "Сначала повторите ожидающую операцию"
                              "projection_required" ->
                                  "Для подготовки нужен доступ к календарю или истории"
                              "invite_expired",
                              "invite_used" -> "Приглашение истекло или уже использовано"
                              else ->
                                  "Не удалось завершить действие. Обновите данные или повторите операцию"
                            }
                    )
              }
            }
          } finally {
            if (token == epoch) state.update { it.copy(busy = false) }
          }
        }
  }
}
