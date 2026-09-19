package com.valerochka1337.valerochkagym.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CoachDiagnosticsTest {
  @Test
  fun `HTTP failure identifies known contract reasons without exposing response text`() {
    val lines = mutableListOf<String>()
    val writer = CoachDiagnosticWriter(true) { lines += it }
    writer.failure(
        "network.failed",
        com.valerochka1337.valerochkagym.data.backend.BackendException(
            400,
            "invalid_request",
            "Незавершённый диалог инструментов",
        ),
    )
    assertTrue(lines.single().contains("http_status_0=400"))
    assertTrue(lines.single().contains("http_reason_0=unfinished_tool_dialog"))
    assertFalse(lines.single().contains("Незавершённый"))
    writer.failure(
        "network.failed",
        com.valerochka1337.valerochkagym.data.backend.BackendException(
            400,
            "SECRET_CODE",
            "SECRET_PROMPT",
        ),
    )
    assertTrue(lines.last().contains("http_reason_0=unclassified"))
    assertFalse(lines.last().contains("SECRET"))
  }

  @Test
  fun `failure retains source locations and nested types without exception contents`() {
    val lines = mutableListOf<String>()
    val writer = CoachDiagnosticWriter(true) { lines += it }
    val cause = IllegalArgumentException("SECRET_TOOL_ARGUMENTS")
    val error = IllegalStateException("SECRET_RESPONSE", cause)
    error.stackTrace = arrayOf(StackTraceElement("CoachAgent", "reply", "CoachAgent.kt", 42))

    writer.failure("request.failed", error, "trace" to 7)

    val line = lines.single()
    assertTrue(line.contains("trace=7"))
    assertTrue(line.contains("IllegalStateException"))
    assertTrue(line.contains("IllegalArgumentException"))
    assertTrue(line.contains("CoachAgent.reply(CoachAgent.kt:42)"))
    assertFalse(line.contains("SECRET"))
  }

  @Test
  fun `disabled diagnostics never invoke the sink`() {
    val lines = mutableListOf<String>()
    val writer = CoachDiagnosticWriter(false) { lines += it }
    writer.event("request.started")
    writer.failure("request.failed", IllegalStateException())
    assertTrue(lines.isEmpty())
  }

  @Test
  fun `broken logging sink cannot interrupt business execution`() {
    val writer = CoachDiagnosticWriter(true) { throw IllegalStateException("sink unavailable") }
    writer.event("request.started")
    writer.failure("request.failed", IllegalArgumentException("invalid"))
  }

  @Test
  fun `entries stay bounded on one line and do not serialize arbitrary objects`() {
    val lines = mutableListOf<String>()
    val writer = CoachDiagnosticWriter(true) { lines += it }
    val payload =
        object {
          override fun toString() = "SECRET_PAYLOAD"
        }
    writer.event("response\nforged", "payload" to payload, "value" to "x\r\ny")
    writer.event("bounded", *Array(100) { "field$it" to "x".repeat(1000) })
    assertFalse(lines.joinToString().contains("SECRET_PAYLOAD"))
    assertTrue(lines.all { it.length <= 3500 && '\n' !in it && '\r' !in it })
  }
}
