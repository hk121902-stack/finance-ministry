package `in`.financeministry.app

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackupUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun pickerNodes(): List<AccessibilityNodeInfo> {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }
        return automation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString()?.contains("documentsui") == true }.flatMap(::descendants)
    }
    private fun clickPickerText(text: String) {
        var target: AccessibilityNodeInfo? = null
        try { rule.waitUntil(15000) {
            target = pickerNodes().firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            target != null
        } } catch (failure: Throwable) {
            throw AssertionError("Missing picker text $text. Visible: ${pickerNodes().map { "${it.text}|${it.contentDescription}|${it.viewIdResourceName}" }}", failure)
        }
        var node = requireNotNull(target)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Picker action $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun savePickerFile(name: String) {
        var field: AccessibilityNodeInfo? = null
        rule.waitUntil(15000) {
            field = pickerNodes().firstOrNull { it.className?.toString() == "android.widget.EditText" }
            field != null
        }
        assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        clickPickerText("Save")
    }
    private fun waitForMessage(text: String) = rule.waitUntil(15000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun real_file_provider_backup_restore_and_csv_preserve_expected_records() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val unique = java.util.UUID.randomUUID().toString()
        val backupName = "fm-test-$unique.fmbackup"
        val safetyName = "fm-safety-$unique.fmbackup"
        val csvName = "fm-report-$unique.csv"
        val password = "test-only-password-long"
        val original = runBlocking { r.save(ManualInput("42", Direction.Debit, System.currentTimeMillis(), TransactionType.Other, label = "Backup provider test")) }
        var later: String? = null
        fun createBackup(name: String) {
            rule.onNodeWithText("Create encrypted backup").performScrollTo().performClick()
            rule.onNodeWithText("Backup password").performTextInput(password)
            rule.onNodeWithText("Confirm backup password").performTextInput(password)
            rule.onNodeWithText("Choose save location").performClick()
            savePickerFile(name)
            waitForMessage("Encrypted backup saved. Keep its password separately.")
        }
        fun readFile(name: String): ByteArray = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("cat /sdcard/Download/$name")).use { it.readBytes() }
        try {
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Backup & export").performScrollTo().performClick()
            createBackup(backupName)
            val bytes = readFile(backupName)
            assertEquals("FMBAK001", bytes.take(8).toByteArray().toString(Charsets.US_ASCII))
            val plain = BackupCipher.decrypt(bytes, password.toCharArray())
            try { assertTrue(plain.toString(Charsets.UTF_8).contains(original)) } finally { plain.fill(0) }
            assertTrue(r.preferences.getLong("last_backup_at", 0) > 0)
            later = runBlocking { r.save(ManualInput("43", Direction.Debit, System.currentTimeMillis(), TransactionType.Other)) }
            createBackup(safetyName)
            rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
            clickPickerText(backupName)
            rule.onNodeWithText("Backup password").performTextInput(password)
            rule.onNodeWithText("Validate backup").performClick()
            waitForMessage("Backup validated. Nothing has been replaced.")
            assertNotNull(runBlocking { r.get(requireNotNull(later)) })
            rule.onNodeWithText("Replace current ledger").performScrollTo().performClick()
            rule.onNodeWithText("Confirm replacement").performClick()
            waitForMessage("Ledger restored. SMS capture and reminders are off; review Settings before enabling them.")
            assertNotNull(runBlocking { r.get(original) })
            assertNull(runBlocking { r.get(requireNotNull(later)) })
            rule.onNodeWithText("Export CSV report").performScrollTo().performClick()
            rule.onNodeWithText("Choose CSV location").performClick()
            savePickerFile(csvName)
            waitForMessage("CSV saved. It is a readable report, not a restore backup.")
            val csv = readFile(csvName).toString(Charsets.UTF_8)
            assertTrue(csv.contains("Backup provider test"))
            assertTrue(csv.contains("42"))
        } finally {
            runBlocking { r.get(original)?.let { r.delete(original) }; later?.let { if (r.get(it) != null) r.delete(it) } }
            listOf(backupName, safetyName, csvName).forEach { name ->
                automation.executeShellCommand("rm -f /sdcard/Download/$name").close()
            }
        }
    }

    @Test fun cancelling_real_android_file_pickers_preserves_ledger_and_backup_status() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val id = runBlocking { r.save(ManualInput("42", Direction.Debit, System.currentTimeMillis(), TransactionType.Other)) }
        val lastBackup = r.preferences.getLong("last_backup_at", 0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun waitForPicker() {
            var lastWindows = ""
            try { rule.waitUntil(45000) {
                val windows = automation.windows
                val state = "${windows.map { "${it.type}:${it.root?.packageName}" }}; active=${automation.rootInActiveWindow?.packageName}"
                if (state != lastWindows) { android.util.Log.i("BackupPickerTest", state); lastWindows = state }
                windows.any { it.root?.packageName?.toString()?.contains("documentsui") == true }
            } }
            catch (e: Throwable) { throw AssertionError("Picker windows: $lastWindows", e) }
        }
        try {
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Backup & export").performScrollTo().performClick()
            rule.onNodeWithText("Create encrypted backup").performClick()
            rule.onNodeWithText("Backup password").performTextInput("test-only-password-long")
            rule.onNodeWithText("Confirm backup password").performTextInput("test-only-password-long")
            rule.onNodeWithText("Choose save location").performClick()
            waitForPicker()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(15000) { rule.onAllNodesWithText("Backup cancelled. No completed backup was recorded.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(lastBackup, r.preferences.getLong("last_backup_at", 0))
            rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
            waitForPicker()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(15000) { rule.onAllNodesWithText("Restore cancelled. Your ledger is unchanged.").fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(runBlocking { r.get(id) })
        } finally { runBlocking { r.delete(id) } }
    }
}
