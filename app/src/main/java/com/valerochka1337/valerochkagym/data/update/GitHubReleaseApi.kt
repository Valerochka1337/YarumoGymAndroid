package com.valerochka1337.valerochkagym.data.update

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming
import retrofit2.http.Url

private const val REPOSITORY_OWNER = "Valerochka1337"
private const val REPOSITORY_NAME = "YarumoGymAndroid"
internal const val MAX_UPDATE_APK_BYTES = 100L * 1024L * 1024L

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GitHubReleaseAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long,
    val digest: String? = null,
)

interface GitHubReleaseApi {
  @Headers(
      "Accept: application/vnd.github+json",
      "X-GitHub-Api-Version: 2022-11-28",
  )
  @GET("repos/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/latest")
  suspend fun latestRelease(): Response<GitHubReleaseDto>

  @Streaming @GET suspend fun downloadAsset(@Url url: String): Response<ResponseBody>
}

/** Числовой SemVer без prerelease: GitHub latest должен указывать только стабильный релиз. */
internal data class SemanticVersion(private val parts: List<Int>) : Comparable<SemanticVersion> {
  override fun compareTo(other: SemanticVersion): Int {
    val count = maxOf(parts.size, other.parts.size)
    repeat(count) { index ->
      val comparison = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
      if (comparison != 0) return comparison
    }
    return 0
  }
}

internal fun parseSemanticVersion(raw: String): SemanticVersion? {
  val value = raw.removePrefix("v").removePrefix("V")
  if (!value.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) return null
  return value.split('.').map { part -> part.toIntOrNull() ?: return null }.let(::SemanticVersion)
}

internal fun GitHubReleaseDto.toAppRelease(installedVersionName: String): AppRelease? {
  if (draft || prerelease) return null
  val installedVersion =
      parseSemanticVersion(installedVersionName)
          ?: throw AppUpdateException("Текущая версия приложения имеет неизвестный формат")
  val remoteVersion =
      parseSemanticVersion(tagName)
          ?: throw AppUpdateException("Номер доступной версии имеет неподдерживаемый формат")
  if (remoteVersion <= installedVersion) return null

  val versionName = tagName.removePrefix("v").removePrefix("V")
  val expectedApkName = "ValerochkaGym-v$versionName.apk"
  val asset =
      assets.singleOrNull { it.name == expectedApkName }
          ?: throw AppUpdateException("Файл обновления пока недоступен. Попробуйте позже.")
  if (asset.size !in 1..MAX_UPDATE_APK_BYTES) {
    throw AppUpdateException("Файл обновления имеет недопустимый размер")
  }
  val sha256 =
      asset.digest?.let(SHA256_DIGEST_PATTERN::matchEntire)?.groupValues?.get(1)?.lowercase()
          ?: throw AppUpdateException("Не удалось проверить целостность файла обновления")
  if (!asset.browserDownloadUrl.isTrustedReleaseAssetUrl(tagName, expectedApkName)) {
    throw AppUpdateException("Получена небезопасная ссылка на обновление")
  }

  return AppRelease(
      tagName = tagName,
      versionName = versionName,
      title = name?.trim()?.takeIf(String::isNotEmpty) ?: "Версия $versionName",
      notes = body?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_RELEASE_NOTES_LENGTH),
      pageUrl = htmlUrl,
      apk =
          AppReleaseAsset(
              name = asset.name,
              downloadUrl = asset.browserDownloadUrl,
              sizeBytes = asset.size,
              sha256 = sha256,
          ),
  )
}

private fun String.isTrustedReleaseAssetUrl(tagName: String, apkName: String): Boolean =
    try {
      val uri = URI(this)
      uri.scheme == "https" &&
          uri.host.equals("github.com", ignoreCase = true) &&
          uri.userInfo == null &&
          uri.port == -1 &&
          uri.rawQuery == null &&
          uri.rawFragment == null &&
          uri.rawPath == "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/$tagName/$apkName"
    } catch (_: Exception) {
      false
    }

private val SHA256_DIGEST_PATTERN = Regex("sha256:([0-9a-fA-F]{64})")
private const val MAX_RELEASE_NOTES_LENGTH = 4_000
