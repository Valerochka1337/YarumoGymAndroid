package com.valerochka1337.valerochkagym.data.routineshare

/** Public, allowlisted routine snapshot. It deliberately has no author or source-routine fields. */
data class RoutineSharePreview(
    val title: String,
    val estimatedDurationSeconds: Long,
    val exercises: List<RoutineShareExercise>,
)

data class RoutineShareExercise(
    val exerciseKey: String,
    val name: String,
    val type: String,
    val sets: List<RoutineShareSet>,
    val restSeconds: Int,
)

data class RoutineShareSet(
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double?,
    val inclinePct: Double?,
)

data class RoutineShareLink(
    val shareId: String,
    val routineId: String,
    val url: String,
    val createdAt: Long,
    val active: Boolean,
)

data class CreatedRoutineShare(
    val shareId: String,
    val url: String,
    val routineId: String,
    val createdAt: Long,
)

data class ImportedRoutineShare(
    val routineId: String,
    val revision: Long,
    val importedAt: Long,
    val alreadyImported: Boolean,
)

/** Kept separate from server error DTOs so UI never needs to understand wire failures. */
class RoutineShareException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Narrow UI boundary; the production source remains the singleton authenticated repository. */
interface RoutineShareDataSource {
  suspend fun create(
      routineId: String,
      operationId: String,
      expectedRevision: Long? = null,
      catalogRevision: Long? = null,
      onRequestPrepared: (expectedRevision: Long, catalogRevision: Long) -> Unit = { _, _ -> },
  ): CreatedRoutineShare

  suspend fun list(routineId: String): List<RoutineShareLink>

  suspend fun revoke(shareId: String, operationId: String)

  suspend fun preview(token: String): RoutineSharePreview

  suspend fun import(token: String, operationId: String): ImportedRoutineShare
}
