package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Durable, owner-scoped state for the optional pre-AI profile prompt. */
data class AiProfilePromptState(
    val disabled: Boolean = false,
    val lastShownAtMillis: Long? = null,
    val reservationToken: String? = null,
    val kind: String? = null,
    val config: String? = null,
    val processMarker: String? = null,
)

data class GymSettings(
    val googleEmail: String? = null,
    val spreadsheetId: String? = null,
    val defaultRestSeconds: Int = DEFAULT_REST_SECONDS,
    val liveCoachEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /**
     * Тактильный отклик интерфейса (гейтит
     * [com.valerochka1337.valerochkagym.ui.haptics.GymHaptics]).
     */
    val hapticsEnabled: Boolean = true,
    /**
     * Автостарт таймера отдыха после отметки подхода; выключен — отдых только не запускается сам.
     */
    val restAutostart: Boolean = true,
    /** Завершать автостартованный отдых по свежему пульсу после заданной длительности. */
    val heartRateRestEnabled: Boolean = false,
    /** Порог завершения отдыха по пульсу. */
    val heartRateRestThresholdBpm: Int = DEFAULT_HEART_RATE_REST_THRESHOLD_BPM,
    /** Сколько секунд пульс должен непрерывно держаться не выше порога. */
    val heartRateRestHoldSeconds: Int = DEFAULT_HEART_RATE_REST_HOLD_SECONDS,
    /** Immediate local privacy block for the existing InBody AI action. */
    val healthAiDisclosureEnabled: Boolean = false,
    /** Versioned acknowledgement for storing newly confirmed manual health entries locally. */
    val localHealthStorageAcknowledgedVersion: Int = 0,
    /** Separate transfer choice; it never grants the existing AI disclosure. */
    val healthBackendSyncEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteMode: PaletteMode = PaletteMode.SYSTEM,
    val accent: AccentColor = AccentColor.DEFAULT,
    /** GitHub Release, для которого пользователь выбрал «Не напоминать». */
    val ignoredUpdateTag: String? = null,
) {
  companion object {
    const val DEFAULT_REST_SECONDS: Int = 120
    const val DEFAULT_HEART_RATE_REST_THRESHOLD_BPM: Int = 110
    const val DEFAULT_HEART_RATE_REST_HOLD_SECONDS: Int = 10
  }
}

@Singleton
class SettingsRepository
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
) {

  private object Keys {
    val GOOGLE_EMAIL = stringPreferencesKey("google_email")
    val SPREADSHEET_ID = stringPreferencesKey("spreadsheet_id")
    val DEFAULT_REST_SECONDS = intPreferencesKey("default_rest_seconds")
    val LIVE_COACH_ENABLED = booleanPreferencesKey("live_coach_enabled")
    val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    val REST_AUTOSTART = booleanPreferencesKey("rest_autostart")
    val HEART_RATE_REST_ENABLED = booleanPreferencesKey("heart_rate_rest_enabled")
    val HEART_RATE_REST_THRESHOLD_BPM = intPreferencesKey("heart_rate_rest_threshold_bpm")
    val HEART_RATE_REST_HOLD_SECONDS = intPreferencesKey("heart_rate_rest_hold_seconds")
    val HEALTH_AI_DISCLOSURE_ENABLED = booleanPreferencesKey("health_ai_disclosure_enabled")
    val LOCAL_HEALTH_STORAGE_ACKNOWLEDGED_VERSION =
        intPreferencesKey("local_health_storage_acknowledged_version")
    val HEALTH_BACKEND_SYNC_ENABLED = booleanPreferencesKey("health_backend_sync_enabled")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val PALETTE_MODE = stringPreferencesKey("palette_mode")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val IGNORED_UPDATE_TAG = stringPreferencesKey("ignored_update_tag")
    const val COACH_MODEL_PREFIX = "coach_model."
  }

  val settings: Flow<GymSettings> =
      dataStore.data
          .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
          .map { prefs ->
            GymSettings(
                googleEmail = prefs[Keys.GOOGLE_EMAIL],
                spreadsheetId = prefs[Keys.SPREADSHEET_ID],
                defaultRestSeconds =
                    prefs[Keys.DEFAULT_REST_SECONDS] ?: GymSettings.DEFAULT_REST_SECONDS,
                liveCoachEnabled = prefs[Keys.LIVE_COACH_ENABLED] ?: true,
                soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
                vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
                hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
                restAutostart = prefs[Keys.REST_AUTOSTART] ?: true,
                heartRateRestEnabled = prefs[Keys.HEART_RATE_REST_ENABLED] ?: false,
                heartRateRestThresholdBpm =
                    (prefs[Keys.HEART_RATE_REST_THRESHOLD_BPM]
                            ?: GymSettings.DEFAULT_HEART_RATE_REST_THRESHOLD_BPM)
                        .coerceIn(
                            MIN_HEART_RATE_REST_THRESHOLD_BPM,
                            MAX_HEART_RATE_REST_THRESHOLD_BPM,
                        ),
                heartRateRestHoldSeconds =
                    (prefs[Keys.HEART_RATE_REST_HOLD_SECONDS]
                            ?: GymSettings.DEFAULT_HEART_RATE_REST_HOLD_SECONDS)
                        .coerceIn(
                            MIN_HEART_RATE_REST_HOLD_SECONDS,
                            MAX_HEART_RATE_REST_HOLD_SECONDS,
                        ),
                healthAiDisclosureEnabled = prefs[Keys.HEALTH_AI_DISCLOSURE_ENABLED] ?: false,
                localHealthStorageAcknowledgedVersion =
                    prefs[Keys.LOCAL_HEALTH_STORAGE_ACKNOWLEDGED_VERSION] ?: 0,
                healthBackendSyncEnabled = prefs[Keys.HEALTH_BACKEND_SYNC_ENABLED] ?: false,
                themeMode = ThemeMode.fromId(prefs[Keys.THEME_MODE]),
                paletteMode = PaletteMode.fromId(prefs[Keys.PALETTE_MODE]),
                accent = AccentColor.fromId(prefs[Keys.ACCENT_COLOR]),
                ignoredUpdateTag = prefs[Keys.IGNORED_UPDATE_TAG],
            )
          }

  suspend fun setGoogleEmail(value: String?) =
      dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.GOOGLE_EMAIL) else prefs[Keys.GOOGLE_EMAIL] = value
      }

  suspend fun setSpreadsheetId(value: String?) =
      dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.SPREADSHEET_ID) else prefs[Keys.SPREADSHEET_ID] = value
      }

  suspend fun setDefaultRestSeconds(value: Int) =
      dataStore.edit { prefs -> prefs[Keys.DEFAULT_REST_SECONDS] = value }

  suspend fun setLiveCoachEnabled(value: Boolean) =
      dataStore.edit { it[Keys.LIVE_COACH_ENABLED] = value }

  suspend fun setSoundEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.SOUND_ENABLED] = value }

  suspend fun setVibrationEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.VIBRATION_ENABLED] = value }

  suspend fun setHapticsEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.HAPTICS_ENABLED] = value }

  suspend fun setRestAutostart(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.REST_AUTOSTART] = value }

  suspend fun setHeartRateRestEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.HEART_RATE_REST_ENABLED] = value }

  suspend fun setHeartRateRestThresholdBpm(value: Int) =
      dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_THRESHOLD_BPM] =
            value.coerceIn(
                MIN_HEART_RATE_REST_THRESHOLD_BPM,
                MAX_HEART_RATE_REST_THRESHOLD_BPM,
            )
      }

  suspend fun setHeartRateRestHoldSeconds(value: Int) =
      dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_HOLD_SECONDS] =
            value.coerceIn(
                MIN_HEART_RATE_REST_HOLD_SECONDS,
                MAX_HEART_RATE_REST_HOLD_SECONDS,
            )
      }

  suspend fun setHealthAiDisclosureEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.HEALTH_AI_DISCLOSURE_ENABLED] = value }

  suspend fun setLocalHealthStorageAcknowledgedVersion(value: Int) =
      dataStore.edit { prefs ->
        prefs[Keys.LOCAL_HEALTH_STORAGE_ACKNOWLEDGED_VERSION] =
            maxOf(
                prefs[Keys.LOCAL_HEALTH_STORAGE_ACKNOWLEDGED_VERSION] ?: 0,
                value.coerceAtLeast(0),
            )
      }

  suspend fun setHealthBackendSyncEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.HEALTH_BACKEND_SYNC_ENABLED] = value }

  suspend fun setAccent(value: AccentColor) =
      dataStore.edit { prefs -> prefs[Keys.ACCENT_COLOR] = value.id }

  suspend fun setThemeMode(value: ThemeMode) =
      dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = value.id }

  suspend fun setPaletteMode(value: PaletteMode) =
      dataStore.edit { prefs ->
        prefs[Keys.PALETTE_MODE] = value.id
        value.accent?.let { prefs[Keys.ACCENT_COLOR] = it.id }
      }

  suspend fun setIgnoredUpdateTag(value: String?) =
      dataStore.edit { prefs ->
        if (value == null) {
          prefs.remove(Keys.IGNORED_UPDATE_TAG)
        } else {
          prefs[Keys.IGNORED_UPDATE_TAG] = value
        }
      }

  /** Model selection is scoped to the authenticated backend owner and contains no credential. */
  fun coachModel(owner: String): Flow<String?> {
    require(owner.isNotBlank())
    val key = stringPreferencesKey(Keys.COACH_MODEL_PREFIX + ownerKey(owner))
    return dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[key] }
  }

  suspend fun setCoachModel(owner: String, model: String?) {
    require(owner.isNotBlank())
    require(model == null || (model.isNotBlank() && model.length <= 256))
    val key = stringPreferencesKey(Keys.COACH_MODEL_PREFIX + ownerKey(owner))
    dataStore.edit { prefs -> if (model == null) prefs.remove(key) else prefs[key] = model }
  }

  suspend fun aiProfilePromptState(scope: String): AiProfilePromptState =
      dataStore.data
          .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
          .map { it.toAiProfilePromptState(promptKeys(scope)) }
          .first()

  /**
   * Atomically clears an orphan from another process and reserves a prompt if it is still due. A
   * reservation deliberately does not update [AiProfilePromptState.lastShownAtMillis].
   */
  suspend fun reserveAiProfilePrompt(
      scope: String,
      token: String,
      kind: String,
      config: String,
      processMarker: String,
      nowMillis: Long,
      cooldownMillis: Long,
  ): Boolean {
    val keys = promptKeys(scope)
    var reserved = false
    dataStore.edit { prefs ->
      val pendingToken = prefs[keys.reservationToken]
      if (pendingToken != null && prefs[keys.processMarker] != processMarker) {
        prefs.clearPromptReservation(keys)
      }
      val lastShown = prefs[keys.lastShownAtMillis]
      val due = lastShown == null || nowMillis - lastShown >= cooldownMillis
      if (prefs[keys.disabled] != true && prefs[keys.reservationToken] == null && due) {
        prefs[keys.reservationToken] = token
        prefs[keys.kind] = kind
        prefs[keys.config] = config
        prefs[keys.processMarker] = processMarker
        reserved = true
      }
    }
    return reserved
  }

  /** Records the timestamp only once the prompt is actually visible to the user. */
  suspend fun acknowledgeAiProfilePrompt(
      scope: String,
      token: String,
      processMarker: String,
      shownAtMillis: Long,
  ): Boolean {
    val keys = promptKeys(scope)
    var acknowledged = false
    dataStore.edit { prefs ->
      if (
          prefs[keys.reservationToken] == token &&
              prefs[keys.processMarker] == processMarker &&
              prefs[keys.config] == "1:reserved"
      ) {
        prefs[keys.lastShownAtMillis] = shownAtMillis
        prefs[keys.config] = "1:shown"
        acknowledged = true
      }
    }
    return acknowledged
  }

  /** Consumes the current reservation before its live continuation may run. */
  suspend fun consumeAiProfilePrompt(
      scope: String,
      token: String,
      processMarker: String,
      disable: Boolean,
  ): Boolean =
      mutateAiProfilePrompt(scope, token, processMarker) { prefs, keys ->
        if (disable) prefs[keys.disabled] = true
        prefs.clearPromptReservation(keys)
      }

  /** Cancelling an unshown or shown dialog never alters its already-acknowledged timestamp. */
  suspend fun cancelAiProfilePrompt(scope: String, token: String, processMarker: String): Boolean =
      mutateAiProfilePrompt(scope, token, processMarker) { prefs, keys ->
        prefs.clearPromptReservation(keys)
      }

  suspend fun setAiProfilePromptDisabled(scope: String, disabled: Boolean) {
    dataStore.edit { prefs -> prefs[promptKeys(scope).disabled] = disabled }
  }

  /** A new process has no live continuation, so an older pending decision must be discarded. */
  suspend fun clearAiProfilePromptOrphan(scope: String, processMarker: String) {
    val keys = promptKeys(scope)
    dataStore.edit { prefs ->
      if (prefs[keys.reservationToken] != null && prefs[keys.processMarker] != processMarker) {
        prefs.clearPromptReservation(keys)
      }
    }
  }

  private suspend fun mutateAiProfilePrompt(
      scope: String,
      token: String,
      processMarker: String,
      mutation: (androidx.datastore.preferences.core.MutablePreferences, PromptKeys) -> Unit,
  ): Boolean {
    val keys = promptKeys(scope)
    var mutated = false
    dataStore.edit { prefs ->
      if (prefs[keys.reservationToken] == token && prefs[keys.processMarker] == processMarker) {
        mutation(prefs, keys)
        mutated = true
      }
    }
    return mutated
  }

  private data class PromptKeys(
      val disabled: Preferences.Key<Boolean>,
      val lastShownAtMillis: Preferences.Key<Long>,
      val reservationToken: Preferences.Key<String>,
      val kind: Preferences.Key<String>,
      val config: Preferences.Key<String>,
      val processMarker: Preferences.Key<String>,
  )

  private fun promptKeys(scope: String): PromptKeys {
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(scope.toByteArray())
    val prefix = "ai_profile_prompt.$encoded"
    return PromptKeys(
        disabled = booleanPreferencesKey("$prefix.disabled"),
        lastShownAtMillis = longPreferencesKey("$prefix.last_shown_at"),
        reservationToken = stringPreferencesKey("$prefix.reservation_token"),
        kind = stringPreferencesKey("$prefix.kind"),
        config = stringPreferencesKey("$prefix.config"),
        processMarker = stringPreferencesKey("$prefix.process_marker"),
    )
  }

  private fun ownerKey(owner: String): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(owner.toByteArray())

  private fun Preferences.toAiProfilePromptState(keys: PromptKeys): AiProfilePromptState =
      AiProfilePromptState(
          disabled = this[keys.disabled] ?: false,
          lastShownAtMillis = this[keys.lastShownAtMillis],
          reservationToken = this[keys.reservationToken],
          kind = this[keys.kind],
          config = this[keys.config],
          processMarker = this[keys.processMarker],
      )

  private fun androidx.datastore.preferences.core.MutablePreferences.clearPromptReservation(
      keys: PromptKeys
  ) {
    remove(keys.reservationToken)
    remove(keys.kind)
    remove(keys.config)
    remove(keys.processMarker)
  }

  private companion object {
    const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
    const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
    const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
    const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60
  }
}
