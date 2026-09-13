package com.valerochka1337.valerochkagym.domain

enum class HealthRecordKind {
  REPORT,
  OBSERVATION,
  RESTRICTION,
}

enum class HealthVersionState {
  CONFIRMED,
  TOMBSTONE,
}

enum class HealthObservedPrecision {
  DATE,
  DATETIME,
}

enum class HealthValueKind {
  NUMBER,
  COMPARATOR,
  RANGE,
  CATEGORY,
  TEXT,
}

enum class HealthOperator {
  LT,
  LE,
  GT,
  GE,
  EQ,
}

sealed interface HealthEditorDraft {
  data class Report(
      val title: String,
      val sourceText: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
  ) : HealthEditorDraft

  data class Observation(
      val reportLogicalId: String,
      val metricIdentityId: String,
      val metricNameOriginal: String,
      val valueKind: HealthValueKind,
      val valueOriginal: String,
      val numberValue: String?,
      val rangeLow: String?,
      val rangeHigh: String?,
      val operator: HealthOperator?,
      val unitOriginal: String?,
      val methodOriginal: String?,
      val specimenOriginal: String?,
      val sourceOriginal: String?,
      val referenceOriginal: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
  ) : HealthEditorDraft

  data class Restriction(val textOriginal: String) : HealthEditorDraft
}

sealed interface HealthPayload {
  data class Report(
      val title: String,
      val sourceText: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
  ) : HealthPayload

  data class Observation(
      val reportLogicalId: String,
      val metricIdentityId: String,
      val metricNameOriginal: String,
      val valueKind: HealthValueKind,
      val valueOriginal: String,
      val numberValue: String?,
      val rangeLow: String?,
      val rangeHigh: String?,
      val operator: HealthOperator?,
      val unitOriginal: String?,
      val methodOriginal: String?,
      val specimenOriginal: String?,
      val sourceOriginal: String?,
      val referenceOriginal: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
      val enteredAtEpochMs: Long,
  ) : HealthPayload

  data class Restriction(val textOriginal: String, val confirmedAtEpochMs: Long) : HealthPayload
}
