package `in`.financeministry.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real encrypted storage, isolated namespaces; never erases the application ledger. */
class LedgerConcurrencyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun namespace() = "test_${UUID.randomUUID().toString().replace("-", "")}"
    private fun enable(repository: TransactionRepository) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        check(repository.preferences.edit().putBoolean("sms_disclosure", true).commit())
    }
    private fun input(amount: String) = ManualInput(amount, Direction.Debit, 1788600000000L, TransactionType.Other)

    // Catches duplicate insertion/notification and automation overwriting a concurrent correction.
    @Test fun duplicate_capture_race_preserves_manual_correction() = runBlocking {
        val repository = TransactionRepository(context, namespace())
        try {
            enable(repository)
            val sms = IncomingSms("SYNTHETIC", 1788600000000L, "INR 42 debited via UPI")
            val notifications = AtomicInteger()
            val start = CompletableDeferred<Unit>()
            val attempts = List(24) { async(Dispatchers.Default) {
                start.await(); repository.ingest(sms) { notifications.incrementAndGet() }
            } }
            start.complete(Unit)
            assertEquals(1, attempts.awaitAll().count { it })
            val id = repository.snapshot().rows.single().id
            coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val duplicates = List(24) { async(Dispatchers.Default) {
                    gate.await(); repository.ingest(sms) { notifications.incrementAndGet() }
                } }
                val edit = async(Dispatchers.Default) { gate.await(); repository.save(input("43.00"), id) }
                gate.complete(Unit)
                assertTrue(duplicates.awaitAll().none { it })
                edit.await()
            }
            repository.close()
            assertEquals(1, repository.snapshot().rows.size)
            assertEquals(4300L, repository.get(id)!!.amountMinor)
            assertTrue(repository.get(id)!!.isUserCorrected)
            assertEquals(1, notifications.get())
        } finally { repository.eraseAll(); repository.close() }
    }

    // Catches read-before-lock edits losing their predecessor in the persisted audit trail.
    @Test fun concurrent_edits_keep_a_complete_audit_chain() = runBlocking {
        val name = namespace()
        val repository = TransactionRepository(context, name)
        try {
            val id = repository.save(input("10.00"))
            val start = CompletableDeferred<Unit>()
            val edits = (11..22).map { amount -> async(Dispatchers.Default) {
                start.await(); repository.save(input("$amount.00"), id)
            } }
            start.complete(Unit); edits.awaitAll()
            val finalAmount = repository.get(id)!!.amountMinor.toString()
            repository.close()
            val db = FinanceDatabase.open(context, DeviceSecrets(context, name).databasePassphrase(true), "$name.db")
            try {
                val changes = db.transactions().corrections(id).filter { it.fieldName == "amountMinor" }
                assertEquals(12, changes.size)
                val remaining = changes.toMutableList()
                var amount = "1000"
                repeat(12) {
                    val next = remaining.single { it.previousValue == amount }
                    amount = next.newValue!!; remaining.remove(next)
                }
                assertEquals(finalAmount, amount)
                assertTrue(remaining.isEmpty())
            } finally { db.close() }
        } finally { repository.eraseAll(); repository.close() }
    }

    // Catches erasure racing an in-flight callback or queued capture recreating erased data/keys.
    @Test fun erasure_serializes_with_inflight_and_queued_capture() = runBlocking {
        val name = namespace()
        val repository = TransactionRepository(context, name)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbacks = AtomicInteger()
        try {
            enable(repository)
            coroutineScope {
                val first = async(Dispatchers.IO) {
                    repository.ingest(IncomingSms("SYNTHETIC", 1788600000000L, "INR 42 debited via UPI")) {
                        callbackEntered.countDown()
                        if (!releaseCallback.await(15, TimeUnit.SECONDS)) error("Host test did not release callback")
                        callbacks.incrementAndGet()
                    }
                }
                try {
                    assertTrue(callbackEntered.await(15, TimeUnit.SECONDS))
                    val start = CompletableDeferred<Unit>()
                    val queued = List(16) { index -> async(Dispatchers.Default) {
                        start.await()
                        repository.ingest(IncomingSms("SYNTHETIC", 1788600000001L + index, "INR 43 debited via UPI")) {
                            callbacks.incrementAndGet()
                        }
                    } }
                    val erase = async(Dispatchers.Default) { start.await(); repository.eraseAll() }
                    start.complete(Unit)
                    releaseCallback.countDown()
                    assertTrue(first.await())
                    queued.awaitAll(); erase.await()
                } finally { releaseCallback.countDown() }
            }
            val completedCallbacks = callbacks.get()
            assertTrue(completedCallbacks >= 1)
            assertFalse(repository.captureAllowed())
            assertTrue(repository.snapshot().rows.isEmpty())
            assertFalse(context.getDatabasePath("$name.db").exists())
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keys.containsAlias("${name}_db_wrap_v1"))
            assertFalse(keys.containsAlias("${name}_source_hmac_v1"))
            assertFalse(repository.ingest(IncomingSms("SYNTHETIC", 1788600000100L, "INR 44 credited via UPI")) {
                callbacks.incrementAndGet()
            })
            assertEquals(completedCallbacks, callbacks.get())
            assertFalse(context.getDatabasePath("$name.db").exists())
            // A fresh, explicit manual entry remains usable after erasure.
            val newId = repository.save(input("5.00"))
            repository.close()
            assertEquals(500L, repository.get(newId)!!.amountMinor)
        } finally { releaseCallback.countDown(); repository.eraseAll(); repository.close() }
    }
}
