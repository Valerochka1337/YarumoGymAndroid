package com.valerochka1337.valerochkagym.data.routineshare

/**
 * Exact routine-sharing routes. Paths are intentionally assembled only from validated IDs/tokens.
 */
internal object RoutineShareApi {
  const val shares = "/routine-shares"

  fun links(routineId: String) = "$shares?routineId=$routineId&limit=50"

  fun revoke(shareId: String) = "$shares/$shareId/revoke"

  fun preview(token: String) = "$shares/preview/$token"

  fun import(token: String) = "${preview(token)}/import"
}
