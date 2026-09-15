package com.valerochka1337.valerochkagym.data.db

import androidx.room.TypeConverter
import com.valerochka1337.valerochkagym.data.backend.GuestSyncPhase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffort
import kotlinx.serialization.json.Json

class Converters {

  private val json = Json { ignoreUnknownKeys = true }

  @TypeConverter fun fromMuscleGroup(value: MuscleGroup): String = value.name

  @TypeConverter fun toMuscleGroup(value: String): MuscleGroup = MuscleGroup.valueOf(value)

  @TypeConverter fun fromMuscle(value: Muscle): String = value.name

  @TypeConverter fun toMuscle(value: String): Muscle = Muscle.valueOf(value)

  @TypeConverter fun fromExerciseType(value: ExerciseType): String = value.name

  @TypeConverter fun toExerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)

  @TypeConverter fun fromUploadStatus(value: UploadStatus): String = value.name

  @TypeConverter fun toUploadStatus(value: String): UploadStatus = UploadStatus.valueOf(value)

  @TypeConverter fun fromKeyExercisePriority(value: KeyExercisePriority): String = value.name

  @TypeConverter
  fun toKeyExercisePriority(value: String): KeyExercisePriority = KeyExercisePriority.valueOf(value)

  @TypeConverter fun fromWorkoutEffort(value: WorkoutEffort?): String? = value?.name

  @TypeConverter
  fun toWorkoutEffort(value: String?): WorkoutEffort? = value?.let(WorkoutEffort::valueOf)

  @TypeConverter
  fun fromCalendarEventAccountLinkState(value: CalendarEventAccountLinkState): String = value.name

  @TypeConverter
  fun toCalendarEventAccountLinkState(value: String): CalendarEventAccountLinkState =
      CalendarEventAccountLinkState.valueOf(value)

  @TypeConverter fun fromCalendarExceptionKind(value: CalendarExceptionKind): String = value.name

  @TypeConverter
  fun toCalendarExceptionKind(value: String): CalendarExceptionKind =
      CalendarExceptionKind.valueOf(value)

  @TypeConverter fun fromCalendarMigrationPhase(value: CalendarMigrationPhase): String = value.name

  @TypeConverter
  fun toCalendarMigrationPhase(value: String): CalendarMigrationPhase =
      CalendarMigrationPhase.valueOf(value)

  @TypeConverter fun fromGuestSyncPhase(value: GuestSyncPhase): String = value.name

  @TypeConverter fun toGuestSyncPhase(value: String): GuestSyncPhase = GuestSyncPhase.valueOf(value)

  @TypeConverter
  fun fromPlannedSetList(value: List<PlannedSet>): String = json.encodeToString(value)

  @TypeConverter
  fun toPlannedSetList(value: String): List<PlannedSet> =
      if (value.isBlank()) emptyList() else json.decodeFromString(value)
}
