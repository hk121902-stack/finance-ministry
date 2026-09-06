package `in`.financeministry.app

import `in`.financeministry.app.data.ImportWindow
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ImportWindowTest {
    @Test fun three_calendar_months_are_not_ninety_days_and_end_at_scan_start() {
        val now = Instant.parse("2026-05-31T12:00:00Z").toEpochMilli()
        val range = ImportWindow.lastThreeMonths(now, ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-02-28T00:00:00Z").toEpochMilli(), range.start)
        assertEquals(now, range.end)
        assertTrue(range.contains(range.start))
        assertTrue(range.contains(now))
        assertFalse(range.contains(range.start - 1))
        assertFalse(range.contains(now + 1))
    }
}
