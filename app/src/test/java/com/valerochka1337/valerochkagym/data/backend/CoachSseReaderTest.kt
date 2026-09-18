package com.valerochka1337.valerochkagym.data.backend

import java.io.EOFException
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class CoachSseReaderTest {
  @Test
  fun `durable event IDs survive keepalive comments`() {
    var eventId: String? = null
    CoachSseReader(Buffer().writeUtf8("id: 42\n: keepalive\nevent: completed\ndata: {}\n\n"))
        .readWithId { _, _, id ->
          eventId = id
          false
        }
    assertEquals("42", eventId)
  }

  @Test
  fun `framing survives every byte boundary and all line endings`() {
    for (newline in listOf("\n", "\r\n", "\r")) {
      val wire =
          listOf(
                  "\uFEFF:heartbeat",
                  "",
                  "event:text_delta",
                  "data: Привет 💪",
                  "data: ещё",
                  "",
                  "event:completed",
                  "data:{}",
                  "",
                  "",
              )
              .joinToString(newline)
      for (packet in 1..wire.encodeToByteArray().size) {
        val input = Buffer().writeUtf8(wire)
        val source =
            object : Source {
                  override fun read(sink: Buffer, byteCount: Long) =
                      input.read(sink, minOf(byteCount, packet.toLong()))

                  override fun timeout() = Timeout.NONE

                  override fun close() = Unit
                }
                .buffer()
        val events = mutableListOf<Pair<String, String>>()
        CoachSseReader(source).read { event, data ->
          events += event to data
          event != "completed"
        }
        assertEquals(listOf("text_delta" to "Привет 💪\nещё", "completed" to "{}"), events)
      }
    }
  }

  @Test
  fun `eof never promotes an unterminated event`() {
    for (wire in
        listOf(
            "",
            ":heartbeat\n\n",
            "event:completed\ndata:{}\n",
            "event:text_delta\ndata:x\n\n",
        )) {
      try {
        CoachSseReader(Buffer().writeUtf8(wire)).read { name, _ -> name != "completed" }
        fail()
      } catch (_: EOFException) {}
    }
  }

  @Test
  fun `limits include comments unknown fields and unterminated lines`() {
    for ((wire, total, event) in
        listOf(Triple(":" + "x".repeat(30), 100, 20), Triple(":123456\n\n".repeat(10), 30, 20))) {
      try {
        CoachSseReader(Buffer().writeUtf8(wire), total, event).read { _, _ -> true }
        fail()
      } catch (e: BackendException) {
        assertEquals(413, e.status)
      }
    }
  }

  @Test
  fun `invalid utf8 is rejected`() {
    val source = Buffer().writeUtf8("data:").writeByte(0xc3).writeUtf8("\n\n")
    assertTrue(runCatching { CoachSseReader(source).read { _, _ -> false } }.isFailure)
  }
}
