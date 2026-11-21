package com.droiddeck.launcher.ports

import java.io.InputStream
import java.io.OutputStream

object N64Rom {
    enum class Order(val group: Int) { BIG(1), PAIRS(2), QUADS(4) }

    fun order(head: ByteArray): Order? {
        if (head.size < 4) return null
        val word = head.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return when (word) {
            "80371240" -> Order.BIG
            "37804012" -> Order.PAIRS
            "40123780" -> Order.QUADS
            else -> null
        }
    }

    /** Feeds the ROM to [sink] in big-endian order; false when the file is not an N64 ROM. */
    fun bigEndian(input: InputStream, sink: (ByteArray, Int) -> Unit): Boolean {
        val buffer = ByteArray(1 shl 20)
        var order: Order? = null
        while (true) {
            val n = fill(input, buffer)
            if (n <= 0) break
            if (order == null) order = order(buffer.copyOf(minOf(n, 4))) ?: return false
            val group = order.group
            var i = 0
            while (group > 1 && i + group <= n) {
                var a = i
                var b = i + group - 1
                while (a < b) {
                    val t = buffer[a]; buffer[a] = buffer[b]; buffer[b] = t
                    a++; b--
                }
                i += group
            }
            sink(buffer, n)
        }
        return order != null
    }

    fun copyBigEndian(input: InputStream, out: OutputStream): Boolean = bigEndian(input) { b, n -> out.write(b, 0, n) }

    private fun fill(input: InputStream, buffer: ByteArray): Int {
        var have = 0
        while (have < buffer.size) {
            val n = input.read(buffer, have, buffer.size - have)
            if (n < 0) break
            have += n
        }
        return have
    }
}
