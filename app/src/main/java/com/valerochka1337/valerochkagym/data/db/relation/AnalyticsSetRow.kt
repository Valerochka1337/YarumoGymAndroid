package com.valerochka1337.valerochkagym.data.db.relation

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType

/**
 * Плоская проекция одного выполненного подхода завершённой тренировки — единственный вход
 * аналитики. Один запрос вместо загрузки деревьев [WorkoutFull] по каждой тренировке: подходов
 * много (тысячи за год), а нужны от них только числа и время отметки.
 *
 * [completedAt] уже приведён к непустому значению (`COALESCE` со временем старта тренировки),
 * поэтому «легаси»-подходы без момента отметки тоже попадают в правильную неделю.
 */
data class AnalyticsSetRow(
    val workoutId: String,
    val exerciseId: Long,
    val exerciseName: String,
    val exerciseType: ExerciseType,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double?,
    val inclinePct: Double?,
    val completedAt: Long,
    val setType: String = "UNKNOWN",
)
