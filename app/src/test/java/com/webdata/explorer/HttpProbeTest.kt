package com.webdata.explorer

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class HttpProbeTest {
    @Test
    fun getReadsRealJsonResponseFromControlledServer() {
        val probe = serveOnce(200, """{"ok":true,"value":42}""")
        val result = HttpProbe.execute("GET", probe.url)

        assertEquals(200, result.statusCode)
        assertTrue(result.responseHeaders.contains("Content-Type", ignoreCase = true))
        assertTrue(result.responseBody.contains("\"value\":42"))
        assertTrue(result.isHttpSuccess)
        assertTrue(result.durationMs >= 0)
        assertEquals("GET", probe.method.get())
    }

    @Test
    fun postSendsJsonAndReadsServerResponse() {
        val probe = serveOnce(200, """{"received":true,"method":"POST"}""")
        val request = """{"test":"WebData Explorer","method":"POST","message":"Prueba"}"""
        val result = HttpProbe.execute("POST", probe.url, request)

        assertEquals(200, result.statusCode)
        assertTrue(probe.requestHeaders.get().contains("content-type: application/json", ignoreCase = true))
        assertEquals(request, probe.requestBody.get())
        assertTrue(result.responseBody.contains("\"received\":true"))
        assertEquals(request, result.requestBody)
    }

    @Test
    fun httpErrorsKeepStatusAndErrorBodyInsteadOfBecomingNetworkErrors() {
        val probe = serveOnce(503, """{"error":"maintenance"}""")
        val result = HttpProbe.execute("GET", probe.url)

        assertEquals(503, result.statusCode)
        assertNull(result.errorType)
        assertTrue(result.responseBody.contains("maintenance"))
        assertFalse(result.isHttpSuccess)
        assertTrue(result.formatForDisplay().contains("HTTP 503"))
    }

    @Test
    fun refusedConnectionIsReportedAsNetworkFailureWithoutHttpStatus() {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()
        val result = HttpProbe.execute("GET", "http://127.0.0.1:$port/fail")

        assertNull(result.statusCode)
        assertNotNull(result.errorType)
        assertTrue(result.errorType!!.contains("CONEXIÓN") || result.errorType!!.contains("RED"))
        assertTrue(result.formatForDisplay().contains("FALLÓ"))
    }

    @Test
    fun formattedResultContainsActualResponseAndRequestMetadata() {
        val probe = serveOnce(200, """{"hello":"world"}""")
        val result = HttpProbe.execute("GET", probe.url)
        val display = result.formatForDisplay()

        assertTrue(display.contains(probe.url))
        assertTrue(display.contains("Método: GET"))
        assertTrue(display.contains("Código HTTP: 200"))
        assertTrue(display.contains("hello"))
        assertTrue(display.contains("Duración:"))
    }

    private data class ServerProbe(
        val url: String,
        val method: AtomicReference<String>,
        val requestHeaders: AtomicReference<String>,
        val requestBody: AtomicReference<String>
    )

    private fun serveOnce(status: Int, body: String): ServerProbe {
        val server = ServerSocket(0)
        val method = AtomicReference("")
        val headers = AtomicReference("")
        val requestBody = AtomicReference("")
        val url = "http://127.0.0.1:${server.localPort}/probe"
        val thread = Thread {
            server.use { listener ->
                listener.soTimeout = 5000
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = BufferedInputStream(socket.getInputStream())
                    val headerBytes = StringBuilder()
                    var state = 0
                    while (state < 4) {
                        val ch = input.read()
                        if (ch < 0) break
                        headerBytes.append(ch.toChar())
                        state = when {
                            state == 0 && ch == 13 -> 1
                            state == 1 && ch == 10 -> 2
                            state == 2 && ch == 13 -> 3
                            state == 3 && ch == 10 -> 4
                            ch == 13 -> 1
                            else -> 0
                        }
                    }
                    val rawHeaders = headerBytes.toString()
                    headers.set(rawHeaders)
                    method.set(rawHeaders.lineSequence().firstOrNull()?.substringBefore(' ') ?: "")
                    val length = Regex("(?im)^Content-Length:\\s*(\\d+)").find(rawHeaders)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(bodyBytes, read, length - read)
                        if (count < 0) break
                        read += count
                    }
                    requestBody.set(String(bodyBytes, 0, read, StandardCharsets.UTF_8))
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val response = "HTTP/1.1 $status ${if (status in 200..299) "OK" else "Service Unavailable"}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(StandardCharsets.UTF_8))
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        thread.start()
        return ServerProbe(url, method, headers, requestBody).also {
            // The test client connects after this helper returns; server thread is already listening.
        }
    }
}
