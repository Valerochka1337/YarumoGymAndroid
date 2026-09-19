package com.valerochka1337.valerochkagym.data.google

import android.app.Activity
import android.app.PendingIntent

/**
 * Результат запроса OAuth-доступа к Google Sheets/Calendar.
 *
 * [Granted] — доступ уже выдан, токен доступен. [NeedsConsent] — требуется явное согласие
 * пользователя; [NeedsConsent.pendingIntent] нужно запустить через `StartIntentSenderForResult`,
 * после чего повторить [GoogleAuth.authorize]. [Failed] — запрос не удался (нет сети, пользователь
 * не залогинен и т. п.).
 */
sealed interface AuthorizeOutcome {
  data object Granted : AuthorizeOutcome

  data class NeedsConsent(val pendingIntent: PendingIntent) : AuthorizeOutcome

  data class Failed(val error: Throwable?) : AuthorizeOutcome
}

/**
 * Результат запроса access-токена без участия Activity ([GoogleAuth.getAccessToken]).
 *
 * [Success] — токен получен. [NeedsConsent] — доступ ещё не выдан, нужен интерактивный
 * [GoogleAuth.authorize] с Activity. [Failed] — запрос не удался (нет сети и т. п.).
 */
sealed interface TokenResult {
  data class Success(val token: String) : TokenResult

  data object NeedsConsent : TokenResult

  data class Failed(val error: Throwable?) : TokenResult
}

/**
 * Вход через Google и выдача OAuth-доступа к нужным API. Интерфейс отделяет UI и будущие загрузчики
 * (Стадии 19/21) от конкретной реализации на Credential Manager / AuthorizationClient — чтобы их
 * можно было мокать в тестах.
 */
interface GoogleAuth : AccountBoundGoogleAuth {

  /** Explicit account picker. It returns a candidate and does not establish Calendar access. */
  suspend fun selectAccount(activity: Activity): Result<String>

  /** Requests calendar.events for one exact normalized account. */
  suspend fun authorizeForAccount(activity: Activity, expectedEmail: String): AuthorizeOutcome

  /** Revokes calendar.events for one exact account without clearing backend credentials. */
  suspend fun revokeCalendarAccess(expectedEmail: String): Result<Unit>

  /**
   * Вход через Credential Manager. Требует Activity-контекст (системный UI выбора аккаунта). В
   * случае успеха email возвращается как кандидат без изменения Calendar identity. Отмена
   * пользователем — «тихий» [Result.failure] без побочных эффектов.
   */
  suspend fun signIn(activity: Activity): Result<String>

  /**
   * Запрашивает OAuth-доступ (scopes Sheets + Calendar). Может потребовать согласия пользователя —
   * тогда возвращает [AuthorizeOutcome.NeedsConsent] с [PendingIntent].
   */
  suspend fun authorize(activity: Activity): AuthorizeOutcome

  /**
   * Access-токен для Bearer-авторизации без участия Activity. [TokenResult.NeedsConsent], если
   * доступ ещё не выдан (требуется [authorize]) или пользователь не залогинен.
   */
  suspend fun getAccessToken(): TokenResult

  override suspend fun getAccessTokenForAccount(expectedEmail: String): TokenResult

  /** Legacy full sign-out retained for old callers; Calendar disconnect does not use it. */
  suspend fun signOut()
}

/** OAuth seam that guarantees the returned token belongs to expectedEmail. */
interface AccountBoundGoogleAuth {
  suspend fun getAccessTokenForAccount(expectedEmail: String): TokenResult
}
