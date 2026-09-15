package com.valerochka1337.valerochkagym.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlannerDurationTest {
  @Test
  fun `shared fixtures count work rest transitions and listed warmup once`() {
    val root =
        Json.parseToJsonElement(javaClass.getResource("/planner-duration-v1.json")!!.readText())
            .jsonObject
    root.getValue("cases").jsonArray.forEach { row ->
      val value = row.jsonObject
      val exercises =
          value.getValue("exercises").jsonArray.map { exercise ->
            val e = exercise.jsonObject
            PlannerDuration.Exercise(
                e.getValue("durations").jsonArray.map { it.jsonPrimitive.intOrNull },
                e.getValue("restSeconds").jsonPrimitive.intOrNull,
            )
          }
      val seconds = PlannerDuration.seconds(exercises)
      val minimum =
          PlannerDuration.minimumSeconds(value.getValue("desiredMinutes").jsonPrimitive.int)
      assertEquals(
          value.getValue("name").jsonPrimitive.content,
          value.getValue("seconds").jsonPrimitive.long,
          seconds,
      )
      assertEquals(value.getValue("minimumSeconds").jsonPrimitive.long, minimum)
      assertEquals(value.getValue("shortfall").jsonPrimitive.boolean, seconds < minimum)
    }
  }
}
