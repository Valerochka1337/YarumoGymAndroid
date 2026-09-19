package com.valerochka1337.valerochkagym.data.google

/**
 * Единые формулировки временных (transient) ошибок доступа к Google — общие для выгрузки в Sheets
 * ([SheetsRepository]). Постоянные ошибки формулируются по месту: они специфичны для конкретной
 * операции (нет таблицы, нет прав и т.п.).
 */
internal object GoogleErrorMessages {

  /** Токен получить не удалось (сеть или сервис аутентификации) — имеет смысл повторить. */
  const val NO_CONNECTION = "Нет соединения с Google — попробуйте ещё раз"

  /** Обрыв сети во время запроса к API — имеет смысл повторить. */
  const val NO_NETWORK = "Нет сети"
}
