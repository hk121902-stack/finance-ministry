package `in`.financeministry.app

import `in`.financeministry.app.data.BudgetProgress
import `in`.financeministry.app.data.RecurringSchedule
import `in`.financeministry.app.data.BudgetAlertDecision
import `in`.financeministry.app.data.BudgetEntity
import `in`.financeministry.app.data.BudgetStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OptionalToolsTest {
    @Test fun budget_progress_uses_personal_share_and_reports_remaining_target() {
        val progress = BudgetProgress(spentMinor = 520_000, limitMinor = 600_000)
        assertEquals(80_000, progress.remainingMinor)
        assertEquals(87, progress.percentUsed)
        assertTrue(progress.reached(80))
        assertFalse(progress.reached(90))
    }

    @Test fun monthly_reminder_clamps_to_the_last_real_day_without_drifting() {
        assertEquals(LocalDate.of(2026, 2, 28), RecurringSchedule.dueDate(2026, 2, 31))
        assertEquals(LocalDate.of(2026, 3, 31), RecurringSchedule.nextDue(31, LocalDate.of(2026, 3, 1)))
        assertEquals(LocalDate.of(2026, 4, 30), RecurringSchedule.nextDue(31, LocalDate.of(2026, 4, 1)))
    }

    @Test fun budget_alert_fires_once_per_budget_version_and_month_after_threshold() {
        val budget = BudgetEntity("b", "Food", 600_000, 80, 1, 2)
        val below = BudgetStatus(budget, 470_000, BudgetProgress(470_000, 600_000))
        val reached = BudgetStatus(budget, 480_000, BudgetProgress(480_000, 600_000))
        val month = LocalDate.of(2026, 9, 1)
        assertNull(BudgetAlertDecision.notificationKey(below, month))
        val key = BudgetAlertDecision.notificationKey(reached, month)
        assertEquals("b:2026-09:2:80", key)
        assertFalse(BudgetAlertDecision.shouldNotify(reached, month, key))
        assertTrue(BudgetAlertDecision.shouldNotify(reached, month, null))
    }
}
