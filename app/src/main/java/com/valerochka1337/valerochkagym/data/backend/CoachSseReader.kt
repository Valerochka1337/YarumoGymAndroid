package com.valerochka1337.valerochkagym.data.backend

import java.io.EOFException
import okio.Buffer
import okio.BufferedSource

data class BackendStreamEvent(
    val event: String,
    val data: String,
    val owner: String,
    val sessionEpoch: Long,
    val id: String? = null,
)

/** Bounded byte-oriented framing; UTF-8 is decoded only after a whole line arrives. */
internal class CoachSseReader(
    private val source: BufferedSource,
    private val maxStreamBytes: Int = 4 * 1024 * 1024,
    private val maxEventBytes: Int = 256 * 1024,
) {
  fun read(consume: (String, String) -> Boolean) {
    readWithId { event, data, _ -> consume(event, data) }
  }

  fun readWithId(consume: (String, String, String?) -> Boolean) {
    var id: String? = null
    var total = 0
    var size = 0
    var skipLf = false
    var firstLine = true
    var name = "message"
    val line = Buffer()
    val data = StringBuilder()
    var hasData = false
    while (!source.exhausted()) {
      val byte = source.readByte().toInt() and 255
      if (++total > maxStreamBytes) tooLarge()
      if (++size > maxEventBytes) tooLarge()
      if (skipLf && byte == 10) {
        skipLf = false
        continue
      }
      skipLf = false
      if (byte != 10 && byte != 13) {
        line.writeByte(byte)
        continue
      }
      skipLf = byte == 13
      val raw = line.readByteArray().decodeToString(throwOnInvalidSequence = true)
      val value = if (firstLine) raw.removePrefix("\uFEFF") else raw
      firstLine = false
      if (value.isEmpty()) {
        if (hasData && !consume(name, data.toString().dropLast(1), id)) return
        name = "message"
        data.setLength(0)
        hasData = false
        size = 0
      } else if (!value.startsWith(':')) {
        val field = value.substringBefore(':')
        val content = value.substringAfter(':', "").removePrefix(" ")
        when (field) {
          "id" -> if (!content.contains('\u0000')) id = content
          "event" -> name = content
          "data" -> {
            hasData = true
            data.append(content).append('\n')
          }
        }
      }
    }
    throw EOFException("Coach stream ended without a terminal event")
  }

  private fun tooLarge(): Nothing =
      throw BackendException(413, "response_too_large", "Ответ сервера слишком большой")
}
