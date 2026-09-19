package com.valerochka1337.valerochkagym.data.profile

import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object ProfileValidator {
  private const val MAX_CONSTRAINT_CODE_POINTS = 2_000

  fun normalize(profile: BasicProfile, nowMillis: Long): BasicProfile? {
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val date = profile.birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (
        profile.birthDate != null &&
            (date == null ||
                date.toString() != profile.birthDate ||
                date < LocalDate.of(1900, 1, 1) ||
                date > today)
    )
        return null
    if (profile.plannedSessionsPerWeek != null && profile.plannedSessionsPerWeek !in 1..7)
        return null
    if (
        profile.preferredSessionDurationMinutes != null &&
            profile.preferredSessionDurationMinutes !in 10..240
    )
        return null
    if ((profile.preferredRepMin == null) != (profile.preferredRepMax == null)) return null
    if (
        profile.preferredRepMin != null &&
            (profile.preferredRepMin !in 1..50 ||
                profile.preferredRepMax !in profile.preferredRepMin..50)
    )
        return null
    val constraints = profile.manualConstraints?.trim()?.takeIf(String::isNotEmpty)
    if (
        constraints != null &&
            constraints.codePointCount(0, constraints.length) > MAX_CONSTRAINT_CODE_POINTS
    )
        return null
    if (profile.equipmentIds.any { !LocalEquipmentCatalog.isKnown(it) }) return null
    return profile.copy(
        equipmentIds = profile.equipmentIds.toSortedSet(),
        manualConstraints = constraints,
    )
  }

  fun wireProfile(payload: JsonObject, recordId: String, nowMillis: Long): BasicProfile? {
    val required =
        setOf(
            "schemaVersion",
            "syncId",
            "updatedAt",
            "trainingGoal",
            "sex",
            "birthDate",
            "experienceLevel",
            "plannedSessionsPerWeek",
            "preferredSessionDurationMinutes",
            "manualConstraints",
            "equipmentIds",
        )
    if (payload.keys != required) return null
    fun nullableStringIsValid(name: String): Boolean =
        payload[name] == JsonNull || (payload[name] as? JsonPrimitive)?.isString == true
    fun nullableIntegerIsValid(name: String): Boolean =
        payload[name] == JsonNull ||
            ((payload[name] as? JsonPrimitive)?.let { !it.isString && it.intOrNull != null } ==
                true)
    if (
        listOf("syncId", "trainingGoal", "sex", "birthDate", "experienceLevel", "manualConstraints")
            .any { !nullableStringIsValid(it) }
    )
        return null
    if (
        listOf("plannedSessionsPerWeek", "preferredSessionDurationMinutes").any {
          !nullableIntegerIsValid(it)
        }
    )
        return null
    fun string(name: String): String? =
        (payload[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun integer(name: String): Int? =
        (payload[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    if ((payload["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1)
        return null
    if (string("syncId") != recordId) return null
    if (
        (payload["updatedAt"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull
            ?.takeIf { it >= 0L } == null
    )
        return null
    val equipment = payload["equipmentIds"] as? JsonArray ?: return null
    val ids =
        equipment.map {
          (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content ?: return null
        }
    if (ids != ids.distinct().sorted() || ids.any { !LocalEquipmentCatalog.isKnown(it) })
        return null
    val constraints = string("manualConstraints")
    if (constraints != constraints?.trim()?.takeIf(String::isNotEmpty)) return null
    return normalize(
        BasicProfile(
            trainingGoal =
                string("trainingGoal")?.let { enumValue<TrainingGoal>(it) ?: return null },
            sex = string("sex")?.let { enumValue<ProfileSex>(it) ?: return null },
            birthDate = string("birthDate"),
            experienceLevel =
                string("experienceLevel")?.let { enumValue<ExperienceLevel>(it) ?: return null },
            plannedSessionsPerWeek = integer("plannedSessionsPerWeek"),
            preferredSessionDurationMinutes = integer("preferredSessionDurationMinutes"),
            equipmentIds = ids.toSet(),
            manualConstraints = constraints,
        ),
        nowMillis,
    )
  }

  private inline fun <reified T : Enum<T>> enumValue(value: String): T? =
      runCatching { enumValueOf<T>(value) }.getOrNull()
}
