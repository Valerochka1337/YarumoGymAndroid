package com.valerochka1337.valerochkagym.data.routineshare

import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.backend.SyncReady
import com.valerochka1337.valerochkagym.data.backend.SyncReadySource
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class RoutineShareRepository
@Inject
constructor(
    private val api: BackendTransport,
    private val syncReady: SyncReadySource,
    private val sessions: BackendSessionStore,
    private val backendSync: BackendSync,
) : RoutineShareDataSource {
  override suspend fun create(
      routineId: String,
      operationId: String,
      expectedRevision: Long?,
      catalogRevision: Long?,
      onRequestPrepared: (expectedRevision: Long, catalogRevision: Long) -> Unit,
  ): CreatedRoutineShare {
    requireCanonicalUuid(routineId, "routineId")
    requireCanonicalUuid(operationId, "operationId")
    val ready = awaitReady()
    requireCurrent(ready)
    val requestRevision = expectedRevision ?: ready.revision
    val requestCatalogRevision = catalogRevision ?: ready.catalogRevision
    onRequestPrepared(requestRevision, requestCatalogRevision)
    val response =
        api.authorizedRawResponse(
            method = "POST",
            path = RoutineShareApi.shares,
            rawBody =
                buildJsonObject {
                      put("operationId", operationId)
                      put("routineId", routineId)
                      put("expectedRevision", requestRevision)
                      put("catalogRevision", requestCatalogRevision)
                    }
                    .toString()
                    .encodeToByteArray(),
            expectedOwner = ready.owner,
            expectedSessionEpoch = ready.sessionEpoch,
            retryOnUnauthorized = false,
        )
    requireResponseContext(response.owner, response.sessionEpoch, ready)
    requireSessionCurrent(ready.owner, ready.sessionEpoch)
    requireCurrent(ready)
    return response.body
        .objectStrict("share create", setOf("shareId", "url", "routineId", "createdAt"))
        .let { body ->
          CreatedRoutineShare(
              shareId = body.uuid("shareId"),
              url = body.shareUrl("url"),
              routineId = body.uuid("routineId"),
              createdAt = body.long("createdAt"),
          )
        }
  }

  override suspend fun list(routineId: String): List<RoutineShareLink> {
    requireCanonicalUuid(routineId, "routineId")
    val snapshot = requireSession()
    val response =
        api.authorizedRawResponse(
            "GET",
            RoutineShareApi.links(routineId),
            ByteArray(0),
            expectedOwner = snapshot.tokens.userId,
            expectedSessionEpoch = snapshot.epoch,
            retryOnUnauthorized = false,
        )
    requireSessionCurrent(snapshot)
    if (response.owner != snapshot.tokens.userId || response.sessionEpoch != snapshot.epoch)
        throw RoutineShareException("Аккаунт изменился")
    val items =
        response.body.objectStrict("share list", setOf("items"))["items"]?.jsonArray
            ?: throw RoutineShareException("Некорректный список ссылок")
    if (items.size > 50) throw RoutineShareException("Некорректный список ссылок")
    return items.map { element ->
      val item =
          element.objectStrict(
              "share list item",
              setOf("shareId", "routineId", "url", "createdAt", "active"),
          )
      RoutineShareLink(
              shareId = item.uuid("shareId"),
              routineId = item.uuid("routineId"),
              url = item.shareUrl("url"),
              createdAt = item.long("createdAt"),
              active = item.boolean("active"),
          )
          .also {
            if (it.routineId != routineId || !it.active)
                throw RoutineShareException("Некорректный список ссылок")
          }
    }
  }

  override suspend fun revoke(shareId: String, operationId: String) {
    requireCanonicalUuid(shareId, "shareId")
    requireCanonicalUuid(operationId, "operationId")
    val snapshot = requireSession()
    val response =
        api.authorizedRawResponse(
            "POST",
            RoutineShareApi.revoke(shareId),
            buildJsonObject { put("operationId", operationId) }.toString().encodeToByteArray(),
            expectedOwner = snapshot.tokens.userId,
            expectedSessionEpoch = snapshot.epoch,
            retryOnUnauthorized = false,
        )
    requireSessionCurrent(snapshot)
    if (response.owner != snapshot.tokens.userId || response.sessionEpoch != snapshot.epoch)
        throw RoutineShareException("Аккаунт изменился")
    val body = response.body.objectStrict("share revoke", setOf("shareId", "revokedAt"))
    if (body.uuid("shareId") != shareId) throw RoutineShareException("Некорректный ответ сервера")
    body.long("revokedAt")
  }

  override suspend fun preview(token: String): RoutineSharePreview {
    requireToken(token)
    return responsePreview(api.public("GET", RoutineShareApi.preview(token)))
  }

  override suspend fun import(token: String, operationId: String): ImportedRoutineShare {
    requireToken(token)
    requireCanonicalUuid(operationId, "operationId")
    val snapshot = requireSession()
    val response =
        api.authorizedRawResponse(
            "POST",
            RoutineShareApi.import(token),
            buildJsonObject { put("operationId", operationId) }.toString().encodeToByteArray(),
            expectedOwner = snapshot.tokens.userId,
            expectedSessionEpoch = snapshot.epoch,
            retryOnUnauthorized = false,
        )
    requireSessionCurrent(snapshot)
    if (response.owner != snapshot.tokens.userId || response.sessionEpoch != snapshot.epoch)
        throw RoutineShareException("Аккаунт изменился")
    val body =
        response.body.objectStrict(
            "share import",
            setOf("routineId", "revision", "importedAt", "alreadyImported"),
        )
    val result =
        ImportedRoutineShare(
            routineId = body.uuid("routineId"),
            revision = body.long("revision"),
            importedAt = body.long("importedAt"),
            alreadyImported = body.boolean("alreadyImported"),
        )
    backendSync.applyImportedRoutine(snapshot, result.routineId, result.revision)
    return result
  }

  private suspend fun awaitReady(): SyncReady.Ready =
      when (val ready = syncReady.await()) {
        is SyncReady.Ready -> ready
        SyncReady.Blocked ->
            throw RoutineShareException("Синхронизация ещё не готова. Повторите позже")
        is SyncReady.Failure -> throw RoutineShareException(ready.message, ready.cause)
      }

  private suspend fun requireCurrent(ready: SyncReady.Ready) {
    if (!syncReady.isCurrent(ready)) throw RoutineShareException("Аккаунт или данные изменились")
  }

  private fun requireSession(): BackendSessionSnapshot =
      sessions.snapshot() ?: throw RoutineShareException("Войдите в аккаунт")

  private fun requireSessionCurrent(snapshot: BackendSessionSnapshot) {
    if (sessions.snapshot() != snapshot) throw RoutineShareException("Аккаунт изменился")
  }

  private fun requireSessionCurrent(owner: String, epoch: Long) {
    val current = sessions.snapshot()
    if (current?.tokens?.userId != owner || current.epoch != epoch)
        throw RoutineShareException("Аккаунт изменился")
  }

  private fun requireResponseContext(owner: String?, epoch: Long, ready: SyncReady.Ready) {
    if (owner != ready.owner || epoch != ready.sessionEpoch)
        throw RoutineShareException("Аккаунт изменился")
  }
}

internal fun requireToken(token: String) {
  if (!TOKEN_PATTERN.matches(token)) throw RoutineShareException("Некорректная ссылка")
}

private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun requireCanonicalUuid(value: String, field: String) {
  if (!UUID_PATTERN.matches(value) || runCatching { UUID.fromString(value) }.isFailure)
      throw RoutineShareException("Некорректное поле $field")
}

private fun responsePreview(element: JsonElement): RoutineSharePreview {
  val body =
      element.objectStrict("share preview", setOf("title", "estimatedDurationSeconds", "exercises"))
  val exercises =
      body["exercises"]?.jsonArray ?: throw RoutineShareException("Некорректный предпросмотр")
  if (exercises.size > 200) throw RoutineShareException("Некорректный предпросмотр")
  return RoutineSharePreview(
      title = body.string("title", 1..200),
      estimatedDurationSeconds = body.nonNegativeLong("estimatedDurationSeconds"),
      exercises =
          exercises.map { raw ->
            val exercise =
                raw.objectStrict(
                    "share exercise",
                    setOf("exerciseKey", "name", "type", "sets", "restSeconds"),
                )
            val sets =
                exercise["sets"]?.jsonArray
                    ?: throw RoutineShareException("Некорректный предпросмотр")
            if (sets.size > 1_000) throw RoutineShareException("Некорректный предпросмотр")
            RoutineShareExercise(
                exerciseKey = exercise.uuid("exerciseKey"),
                name = exercise.string("name", 1..200),
                type = exercise.exerciseType(),
                restSeconds = exercise.int("restSeconds", 0..86_400),
                sets =
                    sets.map { setRaw ->
                      val set =
                          setRaw.objectStrict(
                              "share set",
                              setOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct"),
                          )
                      RoutineShareSet(
                          weightKg = set.optionalDouble("weightKg", -1_000_000.0..1_000_000.0),
                          reps = set.optionalInt("reps", -1_000_000..1_000_000),
                          durationSec = set.optionalInt("durationSec", -1_000_000..1_000_000),
                          speedKmh = set.optionalDouble("speedKmh", -1_000_000.0..1_000_000.0),
                          inclinePct = set.optionalDouble("inclinePct", -100.0..1_000_000.0),
                      )
                    },
            )
          },
  )
}

private fun JsonElement.objectStrict(label: String, fields: Set<String>): JsonObject {
  val value = this as? JsonObject ?: throw RoutineShareException("Некорректный $label")
  if (value.keys != fields) throw RoutineShareException("Некорректный $label")
  return value
}

private fun JsonObject.string(key: String, length: IntRange): String =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.length in length }
        ?: throw RoutineShareException("Некорректное поле $key")

private fun JsonObject.uuid(key: String): String =
    string(key, 36..36).also { requireCanonicalUuid(it, key) }

private fun JsonObject.shareUrl(key: String): String =
    string(key, 1..500).also { value ->
      val expected = Regex("https://api\\.valerochkagym\\.tech/r/[A-Za-z0-9_-]{43}")
      if (!expected.matches(value)) throw RoutineShareException("Некорректное поле $key")
    }

private fun JsonObject.exerciseType(): String =
    string("type", 1..40).takeIf { it in setOf("STRENGTH", "TIMED", "CARDIO") }
        ?: throw RoutineShareException("Некорректное поле type")

private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: throw RoutineShareException("Некорректное поле $key")

private fun JsonObject.nonNegativeLong(key: String): Long =
    long(key).takeIf { it >= 0 } ?: throw RoutineShareException("Некорректное поле $key")

private fun JsonObject.int(key: String, range: IntRange): Int =
    this[key]?.jsonPrimitive?.intOrNull?.takeIf { it in range }
        ?: throw RoutineShareException("Некорректное поле $key")

private fun JsonObject.boolean(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: throw RoutineShareException("Некорректное поле $key")

private fun JsonObject.optionalInt(key: String, range: IntRange): Int? {
  val element = this[key] ?: throw RoutineShareException("Некорректное поле $key")
  if (element is kotlinx.serialization.json.JsonNull) return null
  return element.jsonPrimitive.intOrNull?.takeIf { it in range }
      ?: throw RoutineShareException("Некорректное поле $key")
}

private fun JsonObject.optionalDouble(
    key: String,
    range: ClosedFloatingPointRange<Double>,
): Double? {
  val element = this[key] ?: throw RoutineShareException("Некорректное поле $key")
  if (element is kotlinx.serialization.json.JsonNull) return null
  return element.jsonPrimitive.doubleOrNull?.takeIf { it in range }
      ?: throw RoutineShareException("Некорректное поле $key")
}
