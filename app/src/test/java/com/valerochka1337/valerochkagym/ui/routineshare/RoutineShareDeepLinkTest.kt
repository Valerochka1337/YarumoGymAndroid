package com.valerochka1337.valerochkagym.ui.routineshare

import android.net.Uri
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoutineShareDeepLinkTest {
  private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE"

  @Test
  fun `validated routine share link returns its token`() {
    val uri = Uri.parse("https://api.valerochkagym.tech/r/$token")

    assertEquals(token, uri.routineShareTokenOrNull())
  }

  @Test
  fun `query fragment port and path variants are rejected`() {
    listOf(
            "http://api.valerochkagym.tech/r/$token",
            "https://api.valerochkagym.tech:443/r/$token",
            "https://api.valerochkagym.tech/r/$token?next=workouts",
            "https://api.valerochkagym.tech/r/$token#section",
            "https://api.valerochkagym.tech/r/$token/extra",
            "https://api.valerochkagym.tech/r/${token.dropLast(1)}%45",
            "https://caller@api.valerochkagym.tech/r/$token",
        )
        .forEach { candidate -> assertNull(Uri.parse(candidate).routineShareTokenOrNull()) }
  }
}
