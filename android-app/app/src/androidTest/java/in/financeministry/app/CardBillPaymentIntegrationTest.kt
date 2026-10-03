package `in`.financeministry.app

import android.app.Application
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.sms.TransactionNotifications
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CardBillPaymentIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun row(id: String, direction: String, type: String = "Unknown", channel: String = "Card") =
        TransactionEntity(id, sourceType = "SMS", sourceTimestamp = System.currentTimeMillis(),
            effectiveTimestamp = System.currentTimeMillis(), amountMinor = 100000, direction = direction,
            status = "Successful", channel = channel, transactionType = type, reviewState = "AutoRecorded",
            counterpartyLabel = "Test bill", createdAt = 1, updatedAt = 1)

    @Test fun card_confirmation_notification_does_not_describe_income() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val item = row("notification-${UUID.randomUUID()}", "Credit", "CardRepayment")
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            TransactionNotifications.post(context, item, true)
            // NotificationManager publishes asynchronously; a successful notify()
            // binder call does not guarantee the next snapshot already contains it.
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            var published = manager.activeNotifications.firstOrNull { it.tag == item.id }
            while (published == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(25)
                published = manager.activeNotifications.firstOrNull { it.tag == item.id }
            }
            assertNotNull("Card bill notification was not published within five seconds", published)
            val notification = published!!.notification
            assertEquals("Card bill payment recorded", notification.extras.getString(Notification.EXTRA_TITLE))
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertTrue(text.contains("Card bill payment")); assertFalse(text.contains("Money in"))
            assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("1000"))
        } finally { manager.cancel(item.id, 1) }
    }

    @Test fun settlement_sides_do_not_double_count_and_correction_is_audited() = runBlocking {
        val repo = TransactionRepository(context, "bills_${UUID.randomUUID()}")
        try {
            repo.withLedger { db -> listOf(row("purchase", "Debit", "MerchantPayment"), row("bank", "Debit", channel = "BankTransfer"),
                row("receipt", "Credit"),
                row("pending", "Credit").copy(status = "Pending", reviewState = "NeedsReview", amountMinor = null))
                .forEach { db.transactions().insert(it) } }
            assertEquals(setOf("receipt", "pending"), repo.snapshot(filter = "CardCreditsToCheck").rows.map { it.id }.toSet())
            val before = repo.get("pending")!!
            val corrected = repo.markCardBillPayment("pending")
            assertEquals(before.copy(transactionType = "CardRepayment", updatedAt = corrected.updatedAt, isUserCorrected = true), corrected)
            repo.markCardBillPayment("bank"); repo.markCardBillPayment("receipt")
            val snapshot = repo.snapshot()
            assertEquals("100000", snapshot.debit.toString())
            assertEquals("100000", snapshot.personalSpend.toString())
            assertEquals("0", snapshot.credit.toString())
            repo.withLedger { it.transactions().insert(row("refund", "Credit", "Refund")) }
            assertEquals("100000", repo.snapshot().credit.toString()) // refund remains a genuine credit
            val bills = repo.snapshot(filter = "CardBills")
            assertEquals(3L, bills.resultCount)
            assertEquals("0", bills.resultDebit.toString()); assertEquals("0", bills.resultCredit.toString())
            assertEquals(listOf("bank"), repo.snapshot(filter = "CardBills+Debit+Edited+Category:Other", search = "Test bill").rows.map { it.id })
            assertTrue(repo.snapshot(filter = "CardCreditsToCheck").rows.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.markCardBillPayment("refund") } }
            repo.withLedger { db -> assertEquals("transactionType", db.transactions().corrections("bank").single().fieldName) }
        } finally { repo.eraseAll(); repo.close() }
    }

    @Test fun paginated_bill_filter_does_not_truncate_counts_or_change_old_records() = runBlocking {
        val repo = TransactionRepository(context, "bills_page_${UUID.randomUUID()}")
        try {
            repo.withLedger { db -> repeat(105) { db.transactions().insert(row("bill-$it", "Credit", "CardRepayment")) }
                db.transactions().insert(row("old-credit", "Credit")) }
            val first = repo.snapshot(filter = "CardBills")
            assertEquals(100, first.rows.size); assertTrue(first.hasOlder); assertEquals(105L, first.resultCount)
            val next = repo.snapshot(offset = 100, filter = "CardBills")
            assertEquals(5, next.rows.size); assertFalse(next.hasOlder)
            assertTrue(first.rows.map { it.id }.intersect(next.rows.map { it.id }.toSet()).isEmpty())
            assertEquals("Unknown", repo.get("old-credit")!!.transactionType)
        } finally { repo.eraseAll(); repo.close() }
    }

    @Test fun credit_allocated_to_a_friends_repayment_cannot_be_marked_as_a_card_bill() = runBlocking {
        val repo = TransactionRepository(context, "bills_link_${UUID.randomUUID()}")
        try {
            val expense = repo.save(ManualInput("1000", `in`.financeministry.app.core.model.Direction.Debit,
                System.currentTimeMillis(), `in`.financeministry.app.core.model.TransactionType.Other,
                ownership = `in`.financeministry.app.core.model.SpendingOwnership.ForOther))
            val credit = row("linked-credit", "Credit")
            repo.withLedger { it.transactions().insert(credit) }
            repo.recordRepayment(expense, "500", credit.effectiveTimestamp, "Friend", credit.id)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.markCardBillPayment(credit.id) } }
            assertEquals("Unknown", repo.get(credit.id)!!.transactionType)
            assertEquals(1, repo.repaymentsFor(expense).size)
        } finally { repo.eraseAll(); repo.close() }
    }
}
