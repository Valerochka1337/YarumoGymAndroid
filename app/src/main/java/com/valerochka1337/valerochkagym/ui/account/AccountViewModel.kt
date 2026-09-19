package com.valerochka1337.valerochkagym.ui.account

import android.app.Activity
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.backend.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@HiltViewModel
class AccountViewModel
@Inject
constructor(
    private val api: BackendTransport,
    private val tokens: BackendSessionStore,
    private val sync: BackendSync,
    private val scheduler: BackendSyncScheduler,
) : ViewModel() {
  val mode = MutableStateFlow("login")
  val session =
      combine(tokens.session, sync.transfer) { current, state ->
            current?.takeIf {
              it.userId == state.owner &&
                  state.phase in setOf(GuestSyncPhase.CLAIMED, GuestSyncPhase.OWNED)
            }
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              tokens.session.value?.takeIf {
                it.userId == sync.transfer.value.owner &&
                    sync.transfer.value.phase in setOf(GuestSyncPhase.CLAIMED, GuestSyncPhase.OWNED)
              },
          )
  val status = sync.status
  val conflict = sync.conflict
  val catalogConflict = sync.catalogConflict
  val transfer = sync.transfer
  val busy = MutableStateFlow(false)
  val message = MutableStateFlow<String?>(null)
  private val sessionResults = MutableStateFlow<Pair<String, List<BackendSession>>?>(null)
  val sessions =
      combine(sessionResults, session) { result, current ->
            result?.takeIf { it.first == current?.userId }?.second ?: emptyList()
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private fun task(block: suspend () -> Unit) {
    if (busy.value) return
    viewModelScope.launch {
      busy.value = true
      message.value = null
      try {
        block()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (e is GetCredentialCancellationException) return@launch
        message.value =
            if (e is BackendException) e.message
            else "Не удалось выполнить действие. Проверьте подключение и повторите"
      } finally {
        busy.value = false
      }
    }
  }

  private suspend fun accept(value: JsonElement) {
    val session = api.json.decodeFromJsonElement<BackendTokens>(value)
    sync.signIn(session)
    sessionResults.value = null
    message.value = null
    scheduler.enqueue()
  }

  fun showMode(value: String) {
    if (busy.value) return
    mode.value = value
    message.value = null
  }

  fun submit(mode: String, email: String, password: String, code: String) = task {
    val body = buildJsonObject {
      put("email", email.trim())
      if (mode in setOf("login", "register", "reset")) put("password", password)
      if (mode in setOf("verify", "reset")) put("code", code)
      if (mode == "login") put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
    }
    val path =
        when (mode) {
          "reset" -> "/auth/password/reset"
          "request-reset" -> "/auth/password/request"
          "resend" -> "/auth/verify/request"
          else -> "/auth/$mode"
        }
    val result =
        try {
          api.public("POST", path, body)
        } catch (e: BackendException) {
          if (e.code == "email_unverified") this.mode.value = "verify"
          throw e
        }
    this.mode.value =
        when (mode) {
          "register" -> "verify"
          "request-reset" -> "reset"
          "verify",
          "reset" -> "login"
          else -> this.mode.value
        }
    if (mode == "login") accept(result)
    else
        message.value =
            when (mode) {
              "register",
              "resend",
              "request-reset" -> "Проверьте почту. Код действует 10 минут"
              "verify" -> "Email подтверждён. Теперь войдите с паролем"
              else -> "Пароль изменён. Войдите с новым паролем"
            }
  }

  fun google(activity: Activity) = task {
    val nonce =
        api.public("POST", "/auth/google/nonce").jsonObject.getValue("nonce").jsonPrimitive.content
    val option =
        GetSignInWithGoogleOption.Builder(activity.getString(R.string.google_web_client_id))
            .setNonce(nonce)
            .build()
    val response =
        CredentialManager.create(activity)
            .getCredential(
                activity,
                GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
    val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
    acceptGoogleCredential(credential.idToken, nonce)
  }

  /** Google ID token is exchanged only for a Yarumo session. */
  internal suspend fun acceptGoogleCredential(idToken: String, nonce: String) {
    accept(
        api.public(
            "POST",
            "/auth/google",
            buildJsonObject {
              put("idToken", idToken)
              put("nonce", nonce)
              put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
            },
        )
    )
  }

  fun synchronize(choice: String? = null) = task { sync.run(choice) }

  fun logout(all: Boolean = false) = task {
    sync.signOut(all)
    sessionResults.value = null
    mode.value = "login"
  }

  private fun accountTask(
      requests: List<Pair<String, String>>,
      commit: (String, JsonElement) -> Unit,
  ) {
    val expectedOwner = session.value?.userId ?: return
    task { sync.accountRequests(expectedOwner, requests) { commit(expectedOwner, it) } }
  }

  fun loadSessions() =
      accountTask(listOf("GET" to "/sessions")) { owner, result ->
        sessionResults.value = owner to api.json.decodeFromJsonElement(result)
      }

  fun revoke(id: String) =
      accountTask(listOf("DELETE" to "/sessions/$id", "GET" to "/sessions")) { owner, result ->
        sessionResults.value = owner to api.json.decodeFromJsonElement(result)
      }

  fun deletionCode() =
      accountTask(listOf("POST" to "/me/delete-code")) { _, _ ->
        message.value = "Введите код из письма, чтобы удалить аккаунт"
      }

  fun delete(code: String) = task {
    sync.deleteAccount(code)
    sessionResults.value = null
    mode.value = "login"
    message.value = "Аккаунт удалён"
  }
}
