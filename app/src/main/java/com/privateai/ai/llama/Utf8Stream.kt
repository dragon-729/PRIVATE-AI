package com.privateai.ai.llama

/** Token pieces can split a multi-byte character; only emit complete UTF-8 sequences. */
class Utf8Stream {
    private var buf = ByteArray(0)

    fun push(bytes: ByteArray): String {
        buf += bytes
        var end = buf.size
        var i = end - 1
        var back = 0
        while (i >= 0 && back < 4 && (buf[i].toInt() and 0xC0) == 0x80) { i--; back++ }
        if (i >= 0) {
            val lead = buf[i].toInt() and 0xFF
            val need = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            if (need > back + 1) end = i
        }
        val text = String(buf, 0, end, Charsets.UTF_8)
        buf = buf.copyOfRange(end, buf.size)
        return text
    }
}
