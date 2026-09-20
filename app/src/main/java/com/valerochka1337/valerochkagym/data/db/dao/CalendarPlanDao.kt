package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarGoogleLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationMetadataEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity
import com.valerochka1337.valerochkagym.data.db.relation.CalendarPlanWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.CalendarRuleWithRoutine
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarPlanDao {
  @Query(
      "SELECT p.*, r.name AS routineName FROM calendar_plans p JOIN routines r ON r.id=p.routineId ORDER BY p.startsAtMillis"
  )
  suspend fun plansWithRoutines(): List<CalendarPlanWithRoutine>

  @Query(
      "SELECT r.*, routine.name AS routineName FROM calendar_rules r JOIN routines routine ON routine.id=r.routineId ORDER BY r.isoDay,r.localTime"
  )
  suspend fun rulesWithRoutines(): List<CalendarRuleWithRoutine>

  @Query("SELECT * FROM calendar_exceptions")
  suspend fun allExceptions(): List<CalendarExceptionEntity>

  @Query("SELECT * FROM calendar_migration_state WHERE id=1")
  fun observeMigrationState(): Flow<CalendarMigrationStateEntity?>

  @Query("SELECT * FROM calendar_plans ORDER BY startsAtMillis")
  fun observePlans(): Flow<List<CalendarPlanEntity>>

  @Query(
      "SELECT p.*, r.name AS routineName FROM calendar_plans p JOIN routines r ON r.id=p.routineId ORDER BY p.startsAtMillis"
  )
  fun observePlansWithRoutines(): Flow<List<CalendarPlanWithRoutine>>

  @Query("SELECT * FROM calendar_rules ORDER BY isoDay, localTime")
  fun observeRules(): Flow<List<CalendarRuleEntity>>

  @Query("SELECT * FROM calendar_rules ORDER BY isoDay, localTime")
  suspend fun rules(): List<CalendarRuleEntity>

  @Query(
      "SELECT r.*, routine.name AS routineName FROM calendar_rules r JOIN routines routine ON routine.id=r.routineId ORDER BY r.isoDay, r.localTime"
  )
  fun observeRulesWithRoutines(): Flow<List<CalendarRuleWithRoutine>>

  @Query("SELECT * FROM calendar_exceptions")
  fun observeExceptions(): Flow<List<CalendarExceptionEntity>>

  @Query("SELECT * FROM calendar_plans WHERE id=:id")
  suspend fun plan(id: String): CalendarPlanEntity?

  @Query("SELECT * FROM calendar_plans WHERE routineId=:routineId")
  suspend fun plansForRoutine(routineId: Long): List<CalendarPlanEntity>

  @Query("SELECT COUNT(*) FROM calendar_plans") suspend fun planCount(): Int

  @Query("SELECT * FROM calendar_rules WHERE id=:id")
  suspend fun rule(id: String): CalendarRuleEntity?

  @Query("SELECT * FROM calendar_rules WHERE routineId=:routineId")
  suspend fun rulesForRoutine(routineId: Long): List<CalendarRuleEntity>

  @Query("SELECT COUNT(*) FROM calendar_rules") suspend fun ruleCount(): Int

  @Query("SELECT * FROM calendar_exceptions WHERE ruleId=:ruleId")
  suspend fun exceptions(ruleId: String): List<CalendarExceptionEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertPlan(plan: CalendarPlanEntity): Long

  @Upsert suspend fun upsertPlan(plan: CalendarPlanEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertRule(rule: CalendarRuleEntity): Long

  @Upsert suspend fun upsertRule(rule: CalendarRuleEntity)

  @Upsert suspend fun upsertException(exception: CalendarExceptionEntity)

  @Query("DELETE FROM calendar_plans WHERE id=:id") suspend fun deletePlan(id: String)

  @Query("DELETE FROM calendar_rules WHERE id=:id") suspend fun deleteRule(id: String)

  @Query("DELETE FROM calendar_rules") suspend fun deleteAllRules()

  @Query("DELETE FROM calendar_exceptions WHERE ruleId=:ruleId")
  suspend fun deleteExceptions(ruleId: String)

  @Query("DELETE FROM calendar_exceptions") suspend fun deleteAllExceptions()

  @Upsert suspend fun upsertGoogleLink(link: CalendarGoogleLinkEntity)

  @Query("DELETE FROM calendar_google_links") suspend fun clearGoogleLinks()

  @Query("SELECT * FROM calendar_migration_state WHERE id=1")
  suspend fun migrationState(): CalendarMigrationStateEntity?

  @Query("UPDATE calendar_migration_state SET phase=:phase WHERE id=1")
  suspend fun setMigrationPhase(phase: CalendarMigrationPhase)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertMigrationState(state: CalendarMigrationStateEntity)

  @Query("SELECT * FROM calendar_migration_metadata WHERE id=1")
  suspend fun migrationMetadata(): CalendarMigrationMetadataEntity?

  @Upsert suspend fun upsertMigrationMetadata(metadata: CalendarMigrationMetadataEntity)

  @Transaction
  suspend fun replaceRule(oldId: String, replacement: CalendarRuleEntity) {
    deleteExceptions(oldId)
    deleteRule(oldId)
    upsertRule(replacement)
  }
}
