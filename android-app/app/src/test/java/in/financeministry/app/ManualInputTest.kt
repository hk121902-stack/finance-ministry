package `in`.financeministry.app

import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.ManualInput
import org.junit.Assert.*
import org.junit.Test

class ManualInputTest {
    private fun input(amount: String) = ManualInput(amount, Direction.Debit, 1700000000000, TransactionType.Other)
    @Test fun family_bears_the_full_cost_without_repayment_tracking() {
        val family = input("2000").copy(ownership = SpendingOwnership.valueOf("Family"),
            personalShare = "100", repaid = "500")
        family.validate()
        assertEquals(200000L, family.personalShareMinor())
        assertEquals(0L, family.repaidMinor())
    }
    @Test fun exact_minor_units() { assertEquals(25050L, input("250.50").amountMinor()); input("1").validate() }
    @Test fun rejects_invalid_amounts() {
        listOf("", "-1", "0", "1.234", "NaN", "92233720368547759", "1e4", "1,000").forEach {
            assertThrows(IllegalArgumentException::class.java) { input(it).validate() }
        }
    }
    @Test fun rejects_missing_fields_and_sensitive_identifiers() {
        assertThrows(IllegalArgumentException::class.java) { input("10").copy(direction = Direction.Unknown).validate() }
        assertThrows(IllegalArgumentException::class.java) { input("10").copy(type = TransactionType.Unknown).validate() }
        assertThrows(IllegalArgumentException::class.java) { input("10").copy(status = TransactionStatus.Unknown).validate() }
        assertThrows(IllegalArgumentException::class.java) { input("10").copy(timestamp = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { input("10").copy(notes = "test@upi").validate() }
        assertThrows(IllegalArgumentException::class.java) { input("10").copy(accountHint = "12345678").validate() }
        input("10").copy(label = "Groceries", notes = "Weekly shop", accountHint = "4321").validate()
    }
    @Test fun group_and_for_other_payments_keep_the_users_share_separate() {
        val group = input("3000").copy(ownership = SpendingOwnership.Group, groupLabel = "Dinner", personalShare = "1000", repaid = "500")
        group.validate()
        assertEquals(100000L, group.personalShareMinor())
        assertEquals(50000L, group.repaidMinor())
        val forOther = input("3000").copy(ownership = SpendingOwnership.ForOther, repaid = "3000")
        forOther.validate()
        assertEquals(0L, forOther.personalShareMinor())
        assertThrows(IllegalArgumentException::class.java) { group.copy(repaid = "2001").validate() }
        assertThrows(IllegalArgumentException::class.java) { group.copy(groupLabel = "").validate() }
    }
}
