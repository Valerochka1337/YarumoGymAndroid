package com.valerochka1337.valerochkagym.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParsingTest {

  @Test
  fun `semantic versions compare numeric parts instead of text`() {
    val versionTen = parseSemanticVersion("v1.10.0")!!
    val versionNine = parseSemanticVersion("1.9.9")!!

    assertTrue(versionTen > versionNine)
    assertEquals(0, parseSemanticVersion("1.2")!!.compareTo(parseSemanticVersion("1.2.0")!!))
  }

  @Test
  fun `semantic parser rejects prerelease and arbitrary tags`() {
    assertNull(parseSemanticVersion("v1.2.0-beta"))
    assertNull(parseSemanticVersion("latest"))
    assertNull(parseSemanticVersion("1"))
  }

  @Test
  fun `newer release maps the expected apk and github digest`() {
    val release = release(tag = "v1.3.0").toAppRelease(installedVersionName = "1.2.0")!!

    assertEquals("1.3.0", release.versionName)
    assertEquals("ValerochkaGym-v1.3.0.apk", release.apk.name)
    assertEquals(SHA256, release.apk.sha256)
    assertEquals(5_000_000L, release.apk.sizeBytes)
  }

  @Test
  fun `same or older release is not an update`() {
    assertNull(release(tag = "v1.2.0").toAppRelease(installedVersionName = "1.2.0"))
    assertNull(release(tag = "v1.1.9").toAppRelease(installedVersionName = "1.2.0"))
  }

  @Test
  fun `newer release requires the deterministic apk name`() {
    val dto =
        release(tag = "v1.3.0")
            .copy(
                assets =
                    listOf(
                        asset(name = "app-release.apk", tag = "v1.3.0"),
                    ),
            )

    val error =
        assertThrows(AppUpdateException::class.java) {
          dto.toAppRelease(installedVersionName = "1.2.0")
        }

    assertEquals("Файл обновления пока недоступен. Попробуйте позже.", error.userMessage)
  }

  @Test
  fun `newer release rejects missing sha256 digest`() {
    val dto =
        release(tag = "v1.3.0")
            .copy(
                assets = listOf(asset(tag = "v1.3.0").copy(digest = null)),
            )

    val error =
        assertThrows(AppUpdateException::class.java) {
          dto.toAppRelease(installedVersionName = "1.2.0")
        }

    assertEquals("Не удалось проверить целостность файла обновления", error.userMessage)
  }

  @Test
  fun `latest release requests the renamed repository`() {
    val method = GitHubReleaseApi::class.java.methods.single { it.name == "latestRelease" }

    assertEquals(
        "repos/Valerochka1337/YarumoGymAndroid/releases/latest",
        method.getAnnotation(retrofit2.http.GET::class.java)?.value,
    )
  }

  @Test
  fun `official renamed repository url keeps the existing apk name`() {
    val release = release(tag = "v1.3.60").toAppRelease(installedVersionName = "1.3.59")!!

    assertEquals(
        "https://github.com/Valerochka1337/YarumoGymAndroid/releases/download/" +
            "v1.3.60/ValerochkaGym-v1.3.60.apk",
        release.apk.downloadUrl,
    )
    assertEquals("ValerochkaGym-v1.3.60.apk", release.apk.name)
  }

  @Test
  fun `newer release rejects untrusted or malformed asset urls`() {
    val validUrl = asset(tag = "v1.3.60").browserDownloadUrl
    val invalidUrls =
        listOf(
            validUrl.replace("https://", "http://"),
            validUrl.replace("github.com", "example.com"),
            validUrl.replace("github.com", "github.com.evil.example"),
            validUrl.replace("github.com", "evil.github.com"),
            validUrl.replace("Valerochka1337", "OtherOwner"),
            validUrl.replace("Valerochka1337", "Valerochka1337-extra"),
            validUrl.replace("YarumoGymAndroid", "OtherRepo"),
            validUrl.replace("YarumoGymAndroid", "ValerochkaGym"),
            validUrl.replace("YarumoGymAndroid", "YarumoGymAndroid-extra"),
            validUrl.replace("releases/download/", "releases/download-extra/"),
            validUrl.replace("/v1.3.60/", "/v1.3.59/"),
            validUrl.replace(".apk", ".apk.extra"),
            validUrl.replace("github.com", "github.com@evil.example"),
            validUrl.replace("github.com", "user@github.com"),
            validUrl.replace("github.com", "github.com:8443"),
            validUrl.replace("/v1.3.60/", "/../v1.3.60/"),
            validUrl.replace("/v1.3.60/", "/%2e%2e/v1.3.60/"),
            validUrl.replace("YarumoGymAndroid/", "YarumoGymAndroid%2f"),
            "$validUrl?redirect=elsewhere",
            "$validUrl#fragment",
            "https://github.com/Valerochka1337/YarumoGymAndroid/releases/download/",
            "https://github.com/%ZZ",
            "https://[github.com/invalid",
            "https://github.com/a b",
            "https:github.com/asset.apk",
            "/Valerochka1337/YarumoGymAndroid/releases/download/v1.3.60/asset.apk",
            "",
        )

    invalidUrls.forEach { url ->
      val dto =
          release(tag = "v1.3.60")
              .copy(assets = listOf(asset(tag = "v1.3.60").copy(browserDownloadUrl = url)))

      val error =
          assertThrows(url, AppUpdateException::class.java) {
            dto.toAppRelease(installedVersionName = "1.3.59")
          }

      assertEquals(url, "Получена небезопасная ссылка на обновление", error.userMessage)
    }
  }

  private fun release(tag: String): GitHubReleaseDto =
      GitHubReleaseDto(
          tagName = tag,
          htmlUrl = "https://github.com/Valerochka1337/YarumoGymAndroid/releases/tag/$tag",
          name = "ValerochkaGym ${tag.removePrefix("v")}",
          assets = listOf(asset(tag = tag)),
      )

  private fun asset(
      tag: String,
      name: String = "ValerochkaGym-${tag}.apk",
  ): GitHubReleaseAssetDto =
      GitHubReleaseAssetDto(
          name = name,
          browserDownloadUrl =
              "https://github.com/Valerochka1337/YarumoGymAndroid/releases/download/$tag/$name",
          size = 5_000_000L,
          digest = "sha256:$SHA256",
      )

  private companion object {
    const val SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
