package `in`.financeministry.app

import `in`.financeministry.app.data.*
import org.junit.Assert.*
import org.junit.Test

class RepaymentAccountingTest {
    private fun expense(id: String = "dinner", time: Long = 2000) = TransactionEntity(
        id = id, sourceType = "Manual", sourceTimestamp = time, effectiveTimestamp = time,
        amountMinor = 300000, direction = "Debit", status = "Successful", channel = "UPI",
        transactionType = "Other", reviewState = "Confirmed", createdAt = time, updatedAt = time,
        ownership = "Group", groupLabel = "Dinner", personalShareMinor = 100000, repaidMinor = 50000)

    @Test fun monthly_receipts_follow_repayment_date_even_for_older_expenses() {
        val current = expense()
        val older = expense("old", 500).copy(repaidMinor = 100000)
        val payments = listOf(
            RepaymentEntity("legacy", "dinner", null, 50000, null, "Aarav", "Legacy", 100),
            RepaymentEntity("recent", "old", "credit", 100000, 2100, "Aarav", "Linked", 2100))
        val s = RepaymentAccounting.summary(listOf(current, older), payments, 1000, 3000)
        assertEquals("200000", s.newlyOwed.toString())
        assertEquals("100000", s.received.toString())
        assertEquals("150000", s.monthOutstanding.toString())
        assertEquals("250000", s.outstanding.toString())
    }

    @Test fun allocation_is_bounded_by_both_remaining_credit_and_debt() {
        val debit = expense()
        val credit = expense("credit").copy(direction = "Credit", ownership = "Personal", amountMinor = 100000)
        RepaymentAccounting.validateAllocation(debit, credit, 50000, 50000)
        assertThrows(IllegalArgumentException::class.java) { RepaymentAccounting.validateAllocation(debit, credit, 50001, 50000) }
        assertThrows(IllegalArgumentException::class.java) { RepaymentAccounting.validateAllocation(debit, null, 150001, 0) }
        assertThrows(IllegalArgumentException::class.java) { RepaymentAccounting.validateAllocation(debit, null, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RepaymentAccounting.validateAllocation(debit.copy(repaymentExpected = false), null, 100, 0) }
        assertThrows(IllegalArgumentException::class.java) { RepaymentAccounting.validateAllocation(debit, credit.copy(reviewState = "NeedsReview"), 100, 0) }
    }

    @Test fun gifts_transfers_failed_and_uncertain_records_cannot_create_debt() {
        assertEquals(0L, RepaymentAccounting.owed(expense().copy(repaymentExpected = false)))
        assertEquals(300000L, RepaymentAccounting.personal(expense().copy(repaymentExpected = false)))
        listOf(expense().copy(status = "Failed"), expense().copy(reviewState = "NeedsReview"),
            expense().copy(transactionType = "SelfTransfer"), expense().copy(direction = "Credit"))
            .forEach { assertEquals(0L, RepaymentAccounting.owed(it)) }
    }
}
