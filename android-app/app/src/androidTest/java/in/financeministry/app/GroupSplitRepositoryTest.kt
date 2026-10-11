package `in`.financeministry.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupSplitRepositoryTest {
    private fun repo() = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "groups_${UUID.randomUUID()}")
    private suspend fun expense(r: TransactionRepository) = r.save(ManualInput("900", Direction.Debit,
        System.currentTimeMillis(), TransactionType.Other, label = "Dinner", category = "Food",
        ownership = SpendingOwnership.Group, groupLabel = "Friends", personalShare = "300"))
    private suspend fun split(r: TransactionRepository, id: String): Pair<LocalMemberEntity, LocalMemberEntity> {
        val a = r.addLocalMember("Alice"); val b = r.addLocalMember("Bob")
        r.saveGroupSplit(id, 30000, listOf(MemberShareInput(a.id, 30000), MemberShareInput(b.id, 30000)), r.get(id)!!.updatedAt)
        return a to b
    }
    @Test fun member_settlements_preserve_own_spending_and_never_duplicate_linked_money_in() = runBlocking {
        val r = repo()
        try {
            val id = expense(r); val (a, b) = split(r, id)
            val cash = r.recordRepayment(id, "100", System.currentTimeMillis(), "Wrong label", memberId = a.id)
            assertEquals("Alice", cash.payer)
            val credit = r.save(ManualInput("300", Direction.Credit, System.currentTimeMillis(), TransactionType.Other, label = "Bob receipt"))
            r.recordRepayment(id, "300", r.get(credit)!!.effectiveTimestamp, "Bob", credit, b.id)
            val shares = r.groupSharesFor(id); val receipts = r.repaymentsFor(id)
            assertEquals(20000L, GroupSplitAccounting.owed(shares.single { it.memberId == a.id }, receipts))
            assertEquals(0L, GroupSplitAccounting.owed(shares.single { it.memberId == b.id }, receipts))
            val snapshot = r.snapshot()
            assertEquals(90000L, snapshot.debit.toLong()); assertEquals(40000L, snapshot.credit.toLong())
            assertEquals(30000L, snapshot.personalSpend.toLong()); assertEquals(20000L, snapshot.outstandingRepayments.toLong())
            r.renameLocalMember(a.id, "Alice home")
            assertEquals(a.id, r.repaymentsFor(id).first { it.id == cash.id }.memberId)
            r.removeRepayment(cash.id)
            assertEquals(30000L, GroupSplitAccounting.owed(r.groupSharesFor(id).single { it.memberId == a.id }, r.repaymentsFor(id)))
            assertNotNull(r.get(cash.incomingId!!))
        } finally { r.eraseAll(); r.close() }
    }
    @Test fun rejects_overpayment_unknown_person_stale_split_and_silent_financial_changes() = runBlocking {
        val r = repo()
        try {
            val id = expense(r); val (a, _) = split(r, id)
            suspend fun rejected(block: suspend () -> Unit) {
                try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            }
            rejected { r.recordRepayment(id, "301", System.currentTimeMillis(), "Alice", memberId = a.id) }
            rejected { r.recordRepayment(id, "1", System.currentTimeMillis(), "Alice") }
            rejected { r.saveGroupSplit(id, 30000, listOf(MemberShareInput(a.id, 60000)), 0) }
            rejected { r.classify(id, "Food", SpendingOwnership.Personal) }
            rejected { r.classify(id, "Food", SpendingOwnership.Group, "Friends", "300", "100") }
            assertEquals(0, r.repaymentsFor(id).size)
            val receipt = r.recordRepayment(id, "100", System.currentTimeMillis(), "Alice", memberId = a.id)
            rejected { r.saveGroupSplit(id, 30000, emptyList(), r.get(id)!!.updatedAt) }
            rejected { r.editRepayment(receipt.id, "301", receipt.receivedAt, "Alice") }
            assertEquals(10000L, r.repaymentsFor(id).single().amountMinor)
            assertEquals(2, r.groupSharesFor(id).size)
        } finally { r.eraseAll(); r.close() }
    }
    @Test fun encrypted_backup_roundtrip_preserves_people_shares_and_settlements() = runBlocking {
        val r = repo(); val restored = repo()
        try {
            val id = expense(r); val (a, _) = split(r, id)
            r.recordRepayment(id, "100", System.currentTimeMillis(), "Alice", memberId = a.id)
            val bytes = r.createEncryptedBackup("long-test-only-password".toCharArray())
            val plain = BackupCipher.decrypt(bytes, "long-test-only-password".toCharArray())
            val root = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
            assertEquals(8, root.getInt("schema"))
            assertEquals(2, root.getJSONObject("data").getJSONObject("tables").getJSONArray("local_members").length())
            val preview = restored.previewBackup(bytes, "long-test-only-password".toCharArray())
            assertEquals(2, preview.memberCount); assertEquals(2, preview.groupShareCount)
            restored.restoreBackup(preview, false)
            assertEquals(2, restored.localMembers().size); assertEquals(2, restored.groupSharesFor(id).size)
            assertEquals(a.id, restored.repaymentsFor(id).single().memberId)
            assertEquals(50000L, restored.snapshot().outstandingRepayments.toLong())
            val csv = restored.csvReport(1, Long.MAX_VALUE)
            assertTrue(csv.contains("Alice")); assertTrue(csv.contains("Bob"))
        } finally { r.eraseAll(); r.close(); restored.eraseAll(); restored.close() }
    }
    @Test fun legacy_receipts_remain_unassigned_and_block_guessing_people() = runBlocking {
        val r = repo()
        try {
            val id = expense(r)
            r.recordRepayment(id, "100", System.currentTimeMillis(), "Alice")
            val a = r.addLocalMember("Alice")
            try { r.saveGroupSplit(id, 30000, listOf(MemberShareInput(a.id, 60000)), r.get(id)!!.updatedAt); fail("Must not guess old payer") }
            catch (_: IllegalArgumentException) { }
            assertTrue(r.groupSharesFor(id).isEmpty()); assertNull(r.repaymentsFor(id).single().memberId)
            assertEquals(50000L, r.snapshot().outstandingRepayments.toLong())
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun classification_cannot_assign_people_to_an_ineligible_expense() = runBlocking {
        val r = repo()
        try {
            val id = expense(r); val a = r.addLocalMember("Alice")
            val row = r.get(id)!!
            r.withLedger { db -> db.transactions().update(row.copy(status = "Pending")) }
            try {
                r.classify(id, "Food", SpendingOwnership.Group, "Friends", "300", "",
                    groupShares = listOf(MemberShareInput(a.id, 60000)))
                fail("Pending expense must not get named shares")
            } catch (_: IllegalArgumentException) { }
            assertTrue(r.groupSharesFor(id).isEmpty())
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun schema_seven_backup_is_upgraded_without_inventing_members() = runBlocking {
        val r = repo(); val restored = repo()
        try {
            val id = expense(r)
            r.recordRepayment(id, "100", System.currentTimeMillis(), "Alice")
            val bytes = r.createEncryptedBackup("long-test-only-password".toCharArray())
            val plain = BackupCipher.decrypt(bytes, "long-test-only-password".toCharArray())
            val root = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
            root.put("schema", 7)
            val tables = root.getJSONObject("data").getJSONObject("tables")
            tables.remove("local_members"); tables.remove("group_shares")
            val repayments = tables.getJSONArray("repayments")
            for (i in 0 until repayments.length()) repayments.getJSONObject(i).remove("memberId")
            val legacy = BackupCipher.encrypt(root.toString().toByteArray(), "long-test-only-password".toCharArray())
            restored.restoreBackup(restored.previewBackup(legacy, "long-test-only-password".toCharArray()), false)
            assertTrue(restored.localMembers().isEmpty()); assertTrue(restored.groupSharesFor(id).isEmpty())
            assertNull(restored.repaymentsFor(id).single().memberId)
            assertEquals(50000L, restored.snapshot().outstandingRepayments.toLong())
        } finally { r.eraseAll(); r.close(); restored.eraseAll(); restored.close() }
    }

    @Test fun invalid_share_totals_or_member_overallocations_cannot_replace_a_ledger() = runBlocking {
        val r = repo()
        try {
            val id = expense(r); val (a, _) = split(r, id)
            r.recordRepayment(id, "100", System.currentTimeMillis(), "Alice", memberId = a.id)
            val bytes = r.createEncryptedBackup("long-test-only-password".toCharArray())
            val plain = BackupCipher.decrypt(bytes, "long-test-only-password".toCharArray())
            val root = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
            val shares = root.getJSONObject("data").getJSONObject("tables").getJSONArray("group_shares")
            shares.getJSONObject(0).put("amountMinor", 30001)
            val invalid = BackupCipher.encrypt(root.toString().toByteArray(), "long-test-only-password".toCharArray())
            try { r.previewBackup(invalid, "long-test-only-password".toCharArray()); fail("Invalid split should be rejected") }
            catch (_: IllegalArgumentException) { }
            shares.getJSONObject(0).put("amountMinor", 30000)
            val alice = (0 until shares.length()).map { shares.getJSONObject(it) }.single { it.getString("memberId") == a.id }
            val bob = (0 until shares.length()).map { shares.getJSONObject(it) }.single { it.getString("memberId") != a.id }
            alice.put("amountMinor", 5000); bob.put("amountMinor", 55000)
            val overallocated = BackupCipher.encrypt(root.toString().toByteArray(), "long-test-only-password".toCharArray())
            try { r.previewBackup(overallocated, "long-test-only-password".toCharArray()); fail("Individual over-allocation should be rejected") }
            catch (_: IllegalArgumentException) { }
            assertEquals(90000L, r.get(id)!!.amountMinor); assertEquals(2, r.groupSharesFor(id).size)
            assertEquals(50000L, r.snapshot().outstandingRepayments.toLong())
        } finally { r.eraseAll(); r.close() }
    }
}
