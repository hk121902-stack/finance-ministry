package `in`.financeministry.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounds untrusted file input before allocating a JSON tree or invoking the KDF. */
object BackupInput {
    fun readBounded(input: InputStream, limit: Int = BackupCipher.MAX_BYTES + 52): ByteArray {
        require(limit in 1..(BackupCipher.MAX_BYTES + 52))
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                require(++total <= limit) { "Backup is too large." }
                output.write(one)
            } else {
                require(count <= limit - total) { "Backup is too large." }
                output.write(buffer, 0, count); total += count
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= BackupCipher.MAX_BYTES) { "Backup is too large." }
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw IllegalArgumentException("Backup text is invalid.") }
        var depth = 0
        var values = 0
        var inString = false
        var escaped = false
        var stringLength = 0
        for (c in text) {
            if (inString) {
                require(++stringLength <= 20_000) { "Backup text field is too large." }
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> { inString = true; stringLength = 0 }
                '{', '[' -> { require(++depth <= 16) { "Backup nesting is too deep." }; values++ }
                '}', ']' -> { require(--depth >= 0) { "Backup structure is invalid." } }
                ',', ':' -> values++
            }
            require(values <= 2_000_000) { "Backup contains too many values." }
        }
        require(!inString && depth == 0) { "Backup structure is incomplete." }
        return text
    }
}
