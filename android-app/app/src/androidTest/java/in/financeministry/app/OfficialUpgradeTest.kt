package `in`.financeministry.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: run on the disposable official-signer upgrade emulator. */
class OfficialUpgradeTest {
    @Test fun official_apk_upgrade_preserves_edited_record_and_keys() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("upgradeStage")
        assumeTrue("Requires disposable signed upgrade device", stage in listOf("seed", "verify"))
        val app = ApplicationProvider.getApplicationContext<FinanceMinistryApp>()
        val repository = app.container.repository
        val id = "synthetic-official-upgrade"
        if (stage == "seed") {
            assertNull(repository.get(id))
            val input = ManualInput("42.00", Direction.Debit, 1788600000000L, TransactionType.Other)
            repository.save(input, null, id)
            repository.save(input.copy(amount = "43.00"), id)
        }
        val row = repository.get(id)!!
        assertEquals(4300L, row.amountMinor)
        assertTrue(row.isUserCorrected)
        repository.close()
        if (stage == "verify") {
            val db = FinanceDatabase.open(app, DeviceSecrets(app).databasePassphrase(true))
            try {
                assertTrue(db.transactions().corrections(id).any { it.fieldName == "amountMinor" && it.previousValue == "4200" && it.newValue == "4300" })
                assertNull(db.transactions().get(id)!!.referenceHash)
                assertEquals(3, db.openHelper.readableDatabase.version)
            } finally { db.close() }
            assertEquals(4300L, repository.get(id)!!.amountMinor)
            repository.close()
        }
    }
}
