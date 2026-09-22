package `in`.financeministry.app

import `in`.financeministry.app.widget.WidgetPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPresentationTest {
    @Test fun amounts_are_opt_in_and_hidden_on_the_lock_screen() {
        assertEquals("Quick add", WidgetPresentation.summary(false, false, 1_842_000))
        assertEquals("Unlock to view summary", WidgetPresentation.summary(true, true, 1_842_000))
        assertEquals("This month · ₹18,420.00", WidgetPresentation.summary(true, false, 1_842_000))
        assertEquals("Summary unavailable", WidgetPresentation.summary(true, false, null))
    }
}
