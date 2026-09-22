package `in`.financeministry.app

import `in`.financeministry.app.data.BackupInput
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupInputTest {
    @Test fun bounded_reads_accept_exact_limit_and_reject_overflow() {
        assertArrayEquals(byteArrayOf(1, 2, 3), BackupInput.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        try { BackupInput.readBounded(ByteArrayInputStream(ByteArray(4)), 3); fail("Oversized file") } catch (_: IllegalArgumentException) { }
    }
    @Test fun json_preflight_rejects_deep_nesting_and_malformed_utf8() {
        BackupInput.decode("{\"text\":\"braces [ { ] } are text\"}".toByteArray())
        try { BackupInput.decode(("[".repeat(80) + "0" + "]".repeat(80)).toByteArray()); fail("Deep JSON") } catch (_: IllegalArgumentException) { }
        try { BackupInput.decode(byteArrayOf(0xc3.toByte(), 0x28)); fail("Bad UTF8") } catch (_: IllegalArgumentException) { }
    }
}
