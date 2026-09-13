package com.valerochka1337.valerochkagym.ui.trainingproposal

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedExercise
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedSet

internal fun ProposalPlannedExercise.replacingExercise(
    id: String,
    type: ExerciseType,
): ProposalPlannedExercise =
    if (exerciseId == id) this
    else copy(exerciseId = id, plannedSets = plannedSets.map { it.forType(type) })

internal fun ProposalPlannedSet.forType(type: ExerciseType): ProposalPlannedSet =
    when (type) {
      ExerciseType.STRENGTH -> copy(durationSec = null, speedKmh = null, inclinePct = null)
      ExerciseType.TIMED -> copy(weightKg = null, reps = null, speedKmh = null, inclinePct = null)
      ExerciseType.CARDIO -> copy(weightKg = null, reps = null)
    }

internal fun ProposalPlannedExercise.inferredType(): ExerciseType =
    when {
      plannedSets.any { it.speedKmh != null || it.inclinePct != null } -> ExerciseType.CARDIO
      plannedSets.any { it.durationSec != null } -> ExerciseType.TIMED
      else -> ExerciseType.STRENGTH
    }
