package com.valerochka1337.valerochkagym.data.google

sealed interface ScheduleResult {
  data object Success : ScheduleResult

  data object NeedsConsent : ScheduleResult

  data class Failure(val message: String) : ScheduleResult
}
