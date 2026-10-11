package `in`.financeministry.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.data.FinanceDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), FinanceDatabase::class.java)

    @Test fun schema_seven_upgrade_keeps_legacy_balances_unassigned() {
        helper.createDatabase("migration-7-8", 7).apply {
            execSQL("INSERT INTO transactions (id, sourceType, sourceTimestamp, effectiveTimestamp, amountMinor, currency, direction, status, channel, transactionType, confidence, reviewState, parserVersion, isUserCorrected, createdAt, updatedAt, category, ownership, groupLabel, personalShareMinor, repaidMinor, repaymentExpected, categoryNeedsReview) VALUES ('dinner', 'Manual', 10, 10, 90000, 'INR', 'Debit', 'Successful', 'CashManual', 'Other', 0, 'Confirmed', 0, 1, 10, 10, 'Food', 'Group', 'Friends', 30000, 10000, 1, 0)")
            execSQL("INSERT INTO repayments VALUES ('old', 'dinner', NULL, 10000, NULL, 'Alice', 'Legacy', 10)")
            close()
        }
        helper.runMigrationsAndValidate("migration-7-8", 8, true, FinanceDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT amountMinor, personalShareMinor, repaidMinor FROM transactions WHERE id='dinner'").use {
                assertTrue(it.moveToFirst()); assertEquals(90000L, it.getLong(0)); assertEquals(30000L, it.getLong(1)); assertEquals(10000L, it.getLong(2))
            }
            db.query("SELECT memberId FROM repayments WHERE id='old'").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
            db.query("SELECT COUNT(*) FROM group_shares").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM local_members").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun published_schema_four_migrates_to_seven_without_losing_ledger_or_creating_debt() {
        helper.createDatabase("migration-4-7", 4).apply {
            execSQL("INSERT INTO transactions (id, sourceType, sourceTimestamp, effectiveTimestamp, amountMinor, currency, direction, status, channel, transactionType, confidence, reviewState, parserVersion, isUserCorrected, createdAt, updatedAt, category, ownership, repaidMinor) VALUES ('legacy', 'Manual', 10, 10, 4200, 'INR', 'Debit', 'Successful', 'CashManual', 'Other', 0, 'Confirmed', 0, 1, 10, 10, 'Food', 'Personal', 0)")
            close()
        }
        helper.runMigrationsAndValidate("migration-4-7", 7, true,
            FinanceDatabase.MIGRATION_4_5, FinanceDatabase.MIGRATION_5_6, FinanceDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT amountMinor, category, repaymentExpected, categoryNeedsReview, duplicateOfId FROM transactions WHERE id='legacy'").use {
                assertTrue(it.moveToFirst()); assertEquals(4200L, it.getLong(0)); assertEquals("Food", it.getString(1))
                assertEquals(1, it.getInt(2)); assertEquals(0, it.getInt(3)); assertTrue(it.isNull(4))
            }
            db.query("SELECT COUNT(*) FROM repayments").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM budgets").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM recurring_reminders").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun intermediate_schemas_five_and_six_reach_seven() {
        listOf(5 to "migration-5-7", 6 to "migration-6-7").forEach { (version, name) ->
            helper.createDatabase(name, version).close()
            val migrations = if (version == 5) arrayOf(FinanceDatabase.MIGRATION_5_6, FinanceDatabase.MIGRATION_6_7)
                else arrayOf(FinanceDatabase.MIGRATION_6_7)
            helper.runMigrationsAndValidate(name, 7, true, *migrations).close()
        }
    }
}
