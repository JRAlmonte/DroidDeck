package com.droiddeck.launcher.stores.romm

import java.net.ServerSocket

/** Answers like a RomM server and keeps what it was sent, bodies included. */
internal class RommFakeServer(private val answer: (Request) -> Pair<Int, String>) : AutoCloseable {
    class Request(val method: String, val path: String, val authorization: String, val body: ByteArray) {
        val text: String get() = String(body, Charsets.ISO_8859_1)
    }

    val seen: MutableList<Request> = java.util.Collections.synchronizedList(ArrayList())
    private val socket = ServerSocket(0)
    val address: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { it >= 0 }?.toChar() ?: break)
                    val first = head.lineSequence().first().split(' ')
                    val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                    val request = Request(first[0], first.getOrElse(1) { "" }, Regex("(?i)authorization: *(.*)").find(head)?.groupValues?.get(1)?.trim().orEmpty(), body)
                    seen.add(request)
                    val (code, text) = runCatching { answer(request) }.getOrElse { 500 to "{}" }
                    val bytes = text.toByteArray(Charsets.ISO_8859_1)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun paths(): List<String> = seen.toList().map { "${it.method} ${it.path}" }

    override fun close() = socket.close()
}
