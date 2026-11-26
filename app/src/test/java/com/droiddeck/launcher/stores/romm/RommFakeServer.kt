package com.droiddeck.launcher.stores.romm

import java.net.ServerSocket
import java.net.Socket

/**
 * Answers like a RomM server and keeps what it was sent, bodies included. It takes one request at a time, in the order
 * they arrive, unless [atOnce] lets that many be answered together.
 */
internal class RommFakeServer(atOnce: Int, private val answer: (Request) -> Pair<Int, String>) : AutoCloseable {
    constructor(answer: (Request) -> Pair<Int, String>) : this(1, answer)

    class Request(val method: String, val path: String, val authorization: String, val body: ByteArray, val range: String = "", private val client: Socket? = null) {
        val text: String get() = String(body, Charsets.ISO_8859_1)

        /** Set while answering, the answer stops after this many bytes, as a dropped connection does. */
        @Volatile var cut = 0

        /** Whether the client has closed the connection it is still owed an answer on. */
        fun left(): Boolean {
            val socket = client ?: return false
            return runCatching { socket.soTimeout = 20; socket.getInputStream().read() < 0 }.getOrElse { it !is java.net.SocketTimeoutException }
        }
    }

    val seen: MutableList<Request> = java.util.Collections.synchronizedList(ArrayList())
    private val socket = ServerSocket(0)
    private val workers = if (atOnce > 1) java.util.concurrent.Executors.newFixedThreadPool(atOnce) { Thread(it).apply { isDaemon = true } } else null
    val address: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread {
            while (!socket.isClosed) runCatching {
                val client = socket.accept()
                if (workers == null) serve(client) else workers.execute { serve(client) }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        runCatching {
            client.use {
                val input = client.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { it >= 0 }?.toChar() ?: break)
                val first = head.lineSequence().first().split(' ')
                fun header(name: String) = Regex("(?im)^$name: *(.*)$").find(head)?.groupValues?.get(1)?.trim().orEmpty()
                val length = header("content-length").toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                val request = Request(first[0], first.getOrElse(1) { "" }, header("authorization"), body, header("range"), client)
                seen.add(request)
                val (code, text) = runCatching { answer(request) }.getOrElse { 500 to "{}" }
                val bytes = text.toByteArray(Charsets.ISO_8859_1)
                client.getOutputStream().apply {
                    write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes, 0, request.cut.takeIf { it in 1 until bytes.size } ?: bytes.size)
                    flush()
                }
            }
        }
    }

    fun paths(): List<String> = seen.toList().map { "${it.method} ${it.path}" }

    override fun close() {
        socket.close()
        workers?.shutdownNow()
    }
}
