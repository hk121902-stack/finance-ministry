package `in`.financeministry.app

import `in`.financeministry.app.data.BackupCipher
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    @Test fun roundtrip_is_authenticated_and_randomized() {
        val payload = "synthetic-ledger".toByteArray()
        val password = "test-only-long-password".toCharArray()
        val encrypted = BackupCipher.encrypt(payload, password)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("synthetic-ledger"))
        assertArrayEquals(payload, BackupCipher.decrypt(encrypted, password))
        assertFalse(encrypted.contentEquals(BackupCipher.encrypt(payload, password)))
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(encrypted, "wrong-password".toCharArray()) }
        val changed = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(changed, password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(encrypted.copyOf(20), password) }
    }
    @Test fun untrusted_header_cannot_request_unbounded_key_derivation() {
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(ByteArray(200), "test-only-long-password".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.encrypt(byteArrayOf(1), "short".toCharArray()) }
    }
}
