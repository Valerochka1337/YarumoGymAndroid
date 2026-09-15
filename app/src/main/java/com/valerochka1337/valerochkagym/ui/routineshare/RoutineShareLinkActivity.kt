package com.valerochka1337.valerochkagym.ui.routineshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.valerochka1337.valerochkagym.MainActivity

/** The only exported sharing entry point. It forwards a token, never a caller-controlled route. */
class RoutineShareLinkActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val token = intent?.data?.routineShareTokenOrNull()
    if (token != null) {
      startActivity(
          Intent(this, MainActivity::class.java)
              .putExtra(MainActivity.EXTRA_ROUTINE_SHARE_TOKEN, token)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      )
    }
    finish()
  }
}

internal fun android.net.Uri.routineShareTokenOrNull(): String? {
  if (
      scheme != "https" ||
          host != "api.valerochkagym.tech" ||
          port != -1 ||
          userInfo != null
  ) return null
  if (query != null || fragment != null || pathSegments.size != 2 || pathSegments[0] != "r") return null
  val token = pathSegments[1]
  return token.takeIf {
    Regex("[A-Za-z0-9_-]{43}").matches(it) && encodedPath == "/r/$it"
  }
}
