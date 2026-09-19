package com.valerochka1337.valerochkagym.ui.account

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.backend.GuestSyncPhase
import com.valerochka1337.valerochkagym.ui.components.GymCard

@Composable
fun AccountGate(
    vm: AccountViewModel = hiltViewModel(),
    allowGuest: Boolean = false,
    content: @Composable () -> Unit,
) {
  val session by vm.session.collectAsStateWithLifecycle()
  if (session != null || allowGuest)
      key(if (allowGuest) "guest" else session!!.userId) { content() }
  else
      Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
          Column(
              Modifier.widthIn(max = 480.dp).fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text("Yarumo coach", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Ваши тренировки. Ваш прогресс.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          AccountForm(vm)
        }
      }
}

@Composable
internal fun AccountForm(vm: AccountViewModel, onGoogleSignIn: (() -> Unit)? = null) {
  val activity = LocalActivity.current
  val googleSignIn = onGoogleSignIn ?: activity?.let { { vm.google(it) } }
  val busy by vm.busy.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  val mode by vm.mode.collectAsStateWithLifecycle()
  val transfer by vm.transfer.collectAsStateWithLifecycle()
  var email by rememberSaveable { mutableStateOf("") }
  // Credentials stay in memory and are never written to saved instance state.
  var password by remember { mutableStateOf("") }
  var code by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var attempted by remember { mutableStateOf(false) }
  val focus = LocalFocusManager.current
  val needsPassword = mode in setOf("login", "register", "reset")
  val needsCode = mode in setOf("verify", "reset")
  val emailValid =
      android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() &&
          email.trim().length <= 254
  val passwordValid =
      !needsPassword || if (mode == "login") password.isNotEmpty() else password.length in 12..128
  val codeValid = !needsCode || code.length == 8
  LaunchedEffect(mode) {
    attempted = false
    password = ""
    code = ""
    showPassword = false
  }
  BackHandler(mode != "login" && !busy) { vm.showMode("login") }
  val submit = {
    attempted = true
    if (!busy && emailValid && passwordValid && codeValid) {
      focus.clearFocus()
      vm.submit(mode, email, password, code)
    }
  }
  val title =
      when (mode) {
        "register" -> "Создать аккаунт"
        "verify" -> "Проверьте почту"
        "reset" -> "Новый пароль"
        "request-reset" -> "Забыли пароль?"
        else -> "С возвращением!"
      }
  val action =
      when (mode) {
        "register" -> "Создать аккаунт"
        "verify" -> "Подтвердить email"
        "reset" -> "Сохранить пароль"
        "request-reset" -> "Получить код"
        else -> "Войти"
      }
  GymCard(Modifier.widthIn(max = 480.dp).fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(title, style = MaterialTheme.typography.headlineSmall)
      Text(
          when (mode) {
            "register" -> "Сохраняйте тренировки и возвращайтесь к ним на любом устройстве."
            "verify" ->
                "Введите 8 цифр из письма. Код действует 10 минут. Если письма нет, проверьте папку «Спам»."
            "request-reset" -> "Укажите email аккаунта — отправим код для смены пароля."
            "reset" -> "Введите код из письма и придумайте новый пароль."
            else -> "Войдите, чтобы продолжить тренировки."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (transfer.phase == GuestSyncPhase.CLAIMED)
          Text(
              "Перенос данных ожидает входа в тот же аккаунт, с которым начали перенос. Войдите в него, чтобы продолжить.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
          )
      if (mode == "login" || mode == "register") {
        OutlinedButton(
            onClick = {
              focus.clearFocus()
              googleSignIn?.invoke()
            },
            enabled = !busy && googleSignIn != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
          Text("Продолжить с Google")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          HorizontalDivider(Modifier.weight(1f))
          Text(
              "или по email",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          HorizontalDivider(Modifier.weight(1f))
        }
      }
      OutlinedTextField(
          value = email,
          onValueChange = { email = it },
          modifier = Modifier.fillMaxWidth(),
          enabled = !busy,
          label = { Text("Email") },
          singleLine = true,
          leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
          isError = attempted && !emailValid,
          supportingText =
              if (attempted && !emailValid) ({ Text("Введите email, например name@mail.ru") })
              else null,
          keyboardOptions =
              KeyboardOptions(
                  keyboardType = KeyboardType.Email,
                  imeAction = if (mode == "request-reset") ImeAction.Done else ImeAction.Next,
              ),
          keyboardActions = KeyboardActions(onDone = { submit() }),
      )
      if (needsCode)
          OutlinedTextField(
              value = code,
              onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(8) },
              modifier = Modifier.fillMaxWidth(),
              enabled = !busy,
              label = { Text("Код из письма") },
              singleLine = true,
              isError = attempted && !codeValid,
              supportingText =
                  if (attempted && !codeValid) ({ Text("Введите все 8 цифр") }) else null,
              keyboardOptions =
                  KeyboardOptions(
                      keyboardType = KeyboardType.NumberPassword,
                      imeAction = if (needsPassword) ImeAction.Next else ImeAction.Done,
                  ),
              keyboardActions = KeyboardActions(onDone = { submit() }),
          )
      if (needsPassword)
          OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              modifier = Modifier.fillMaxWidth(),
              enabled = !busy,
              label = { Text(if (mode == "reset") "Новый пароль" else "Пароль") },
              singleLine = true,
              isError = attempted && !passwordValid,
              supportingText = {
                if (mode != "login") Text("От 12 до 128 символов")
                else if (attempted && !passwordValid) Text("Введите пароль")
              },
              visualTransformation =
                  if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
              trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                  Icon(
                      if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                      contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль",
                  )
                }
              },
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { submit() }),
          )
      message?.let { AccountMessage(it) }
      Button(
          onClick = submit,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) {
        if (busy) {
          CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
          Text("Подождите…")
        } else Text(action)
      }
      if (mode == "login") {
        TextButton(
            onClick = { vm.showMode("request-reset") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Забыли пароль?")
        }
        HorizontalDivider()
        OutlinedButton(
            onClick = { vm.showMode("register") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Создать аккаунт")
        }
      } else {
        if (mode == "verify" || mode == "reset")
            TextButton(
                onClick = {
                  attempted = true
                  if (emailValid)
                      vm.submit(if (mode == "verify") "resend" else "request-reset", email, "", "")
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Отправить код ещё раз")
            }
        if (mode == "register")
            TextButton(onClick = { vm.showMode("verify") }, enabled = !busy) {
              Text("Уже есть код подтверждения")
            }
        if (mode == "request-reset")
            TextButton(onClick = { vm.showMode("reset") }, enabled = !busy) {
              Text("Уже есть код восстановления")
            }
        TextButton(
            onClick = { vm.showMode("login") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Вернуться ко входу")
        }
      }
    }
  }
}

@Composable
private fun AccountMessage(message: String) {
  Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      shape = MaterialTheme.shapes.medium,
  ) {
    Text(
        message,
        Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
fun AccountCard(vm: AccountViewModel = hiltViewModel()) {
  val session by vm.session.collectAsStateWithLifecycle()
  val status by vm.status.collectAsStateWithLifecycle()
  val conflict by vm.conflict.collectAsStateWithLifecycle()
  val catalogConflict by vm.catalogConflict.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  var confirm by remember { mutableStateOf<String?>(null) }
  var deleteCode by remember { mutableStateOf("") }
  var deleting by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    GymCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            Icons.Rounded.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("Ваш аккаунт", style = MaterialTheme.typography.titleLarge)
        Text(session?.email.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let { AccountMessage(it) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (conflict) {
          Text(
              if (catalogConflict)
                  "Упражнения и залы стали стандартными. Ваши локальные правки сохранены до выбора."
              else "Тренировки изменились на другом устройстве. Какой вариант оставить?"
          )
          OutlinedButton(
              onClick = { confirm = "local" },
              enabled = !busy,
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (catalogConflict) "Сохранить правки личными копиями" else "С этого устройства")
          }
          OutlinedButton(
              onClick = { confirm = "server" },
              enabled = !busy,
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (catalogConflict) "Принять стандартные версии" else "С другого устройства")
          }
        } else
            TextButton(onClick = { vm.synchronize() }, enabled = !busy) {
              Text("Обновить тренировки")
            }
        OutlinedButton(
            onClick = { confirm = "logout" },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Выйти из аккаунта")
        }
        Text(
            "После выхода можно войти с другим email.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    GymCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Безопасность", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { deleting = !deleting }, enabled = !busy) {
          Text(
              if (deleting) "Отменить удаление" else "Удалить аккаунт",
              color = MaterialTheme.colorScheme.error,
          )
        }
        if (deleting) {
          Text(
              "Все тренировки и замеры этого аккаунта будут удалены навсегда. Подтвердите удаление кодом из письма."
          )
          TextButton(onClick = vm::deletionCode, enabled = !busy) { Text("Получить код на почту") }
          OutlinedTextField(
              deleteCode,
              { deleteCode = it.filter { c -> c in '0'..'9' }.take(8) },
              modifier = Modifier.fillMaxWidth(),
              enabled = !busy,
              label = { Text("Код удаления") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          )
          TextButton(onClick = { confirm = "delete" }, enabled = !busy && deleteCode.length == 8) {
            Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }
  confirm?.let { action ->
    val title =
        when (action) {
          "delete" -> "Удалить аккаунт навсегда?"
          "logout" -> "Выйти из аккаунта?"
          else -> "Заменить изменения?"
        }
    AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text(title) },
        text = {
          Text(
              when (action) {
                "local" ->
                    if (catalogConflict)
                        "Будут созданы личные копии ваших правок. История останется связана со стандартными объектами."
                    else "Для совпадающих тренировок будут сохранены изменения с этого устройства."
                "server" ->
                    if (catalogConflict)
                        "Локальные правки перенесённых объектов будут заменены стандартными версиями. Личные тренировки сохранятся."
                    else
                        "Для совпадающих тренировок будут сохранены изменения с другого устройства."
                "delete" -> "Восстановить тренировки и замеры после удаления будет невозможно."
                else ->
                    "Сохранённые в аккаунте тренировки останутся. Изменения без подключения могут потеряться при входе в другой аккаунт."
              }
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                confirm = null
                when (action) {
                  "local",
                  "server" -> vm.synchronize(action)
                  "delete" -> vm.delete(deleteCode)
                  else -> vm.logout(false)
                }
              }
          ) {
            Text(
                when (action) {
                  "delete" -> "Удалить"
                  "local",
                  "server" -> "Сохранить выбранное"
                  else -> "Выйти"
                }
            )
          }
        },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
    )
  }
}
