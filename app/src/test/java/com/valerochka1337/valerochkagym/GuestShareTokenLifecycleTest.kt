package com.valerochka1337.valerochkagym

import com.valerochka1337.valerochkagym.ui.navigation.guestShareHostKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestShareTokenLifecycleTest {
  private val first = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE"
  private val second = "ZbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE"

  @Test
  fun `dismissed link does not reopen after recreation and a new valid link still opens`() {
    assertNull(restoredGuestShareToken(first, first))
    assertFalse(acceptsGuestShareToken(first, first))
    assertTrue(acceptsGuestShareToken(second, first))
    assertEquals(second, restoredGuestShareToken(second, first))
    assertFalse(guestShareHostKey(first) == guestShareHostKey(second))
  }

  @Test
  fun `opening the same link again restores a dismissed preview`() {
    assertTrue(acceptsGuestShareToken(first, first, newDelivery = true))
    assertFalse(acceptsGuestShareToken("invalid", first, newDelivery = true))
  }
}
