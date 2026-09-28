package com.valerochka1337.valerochkagym.data.plannermapping

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannerExerciseMappingRepository
@Inject
constructor(
    private val api: PlannerExerciseMappingApi,
    private val sessions: BackendSessionStore,
) {
  suspend fun list(): List<PlannerExerciseMapping> = api.list(session())

  suspend fun save(mapping: PlannerExerciseMapping): PlannerExerciseMapping =
      api.put(session(), mapping)

  suspend fun delete(exerciseId: String) = api.delete(session(), exerciseId)

  private fun session() = sessions.snapshot() ?: throw BackendException(401, "unauthorized", "")
}
