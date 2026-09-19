package com.valerochka1337.valerochkagym.ui.profile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class ProfilePickersTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `date picker confirms an existing date without timezone shift and can clear it`() {
    var date by mutableStateOf("1990-06-15")
    compose.setContent { GymTheme { BirthDateField(date) { date = it } } }
    compose.onNodeWithText("Дата рождения: 15.06.1990").performClick()
    compose.onNodeWithText("Выбрать").performClick()
    assertEquals("1990-06-15", date)
    compose.onNodeWithText("Дата рождения: 15.06.1990").performClick()
    compose.onNodeWithText("Очистить").performClick()
    assertEquals("", date)
    compose.onNodeWithText("Дата рождения: Не задана").assertIsDisplayed()
  }

  @Test
  fun `equipment dropdown searches synonyms and preserves multiple selections`() {
    val previous = LocalEquipmentCatalog.state.value
    LocalEquipmentCatalog.publish(
        EquipmentCatalog.entries.take(2).map { LocalEquipmentCatalog.Entry(it, false) }
    )
    try {
      var selected by mutableStateOf(setOf("dumbbells"))
      compose.setContent {
        GymTheme {
          EquipmentDropdown(selected) { id ->
            selected = if (id in selected) selected - id else selected + id
          }
        }
      }
      compose.onNodeWithText("Оборудование").performClick()
      compose.onNodeWithText("Поиск оборудования").performTextInput("гриф")
      compose.onNodeWithText("Гантели").assertDoesNotExist()
      compose.onNodeWithText("Штанга").performClick()
      assertEquals(setOf("barbell", "dumbbells"), selected)
      compose.onNodeWithText("Штанга").performClick()
      assertEquals(setOf("dumbbells"), selected)
    } finally {
      LocalEquipmentCatalog.publish(previous)
    }
  }
}
