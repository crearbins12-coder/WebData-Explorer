package com.webdata.explorer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.charset.StandardCharsets

/** Direct HTTP diagnostic client, independent from WebView interception. */
object HttpProbe {
    const val GET_URL = "https://httpbin.org/get"
    const val POST_URL = "https://httpbin.org/post"
    const val POST_JSON = """{"test":"WebData Explorer","method":"POST","message":"Prueba de conectividad"}"""

    data class Result(
        val url: String,
        val method: String,
        val requestBody: String,
        val statusCode: Int?,
        val responseHeaders: String,
        val responseBody: String,
        val durationMs: Long,
        val errorType: String? = null,
        val errorMessage: String? = null
    ) {
        val isHttpSuccess: Boolean get() = statusCode != null && statusCode in 200..299 && errorType == null
        val displayStatus: String get() = when {
            errorType != null -> "FALLÓ: $errorType"
            statusCode == null -> "FALLÓ: sin respuesta HTTP"
            statusCode in 200..299 -> "ÉXITO · HTTP $statusCode"
            else -> "RESPUESTA HTTP NO EXITOSA · HTTP $statusCode"
        }

        fun formatForDisplay(): String = buildString {
            appendLine(displayStatus)
            appendLine("URL: $url")
            appendLine("Método: $method")
            if (requestBody.isNotBlank()) {
                appendLine("Cuerpo enviado:")
                appendLine(requestBody)
            }
            appendLine("Código HTTP: ${statusCode?.toString() ?: "No recibido"}")
            appendLine("Duración: ${durationMs} ms")
            if (responseHeaders.isNotBlank()) {
                appendLine("Encabezados de respuesta:")
                appendLine(responseHeaders)
            }
            appendLine("Cuerpo recibido:")
            appendLine(responseBody.ifBlank { "(cuerpo vacío)" })
            if (errorType != null) {
                appendLine("Tipo de error: $errorType")
                appendLine("Detalle: ${errorMessage.orEmpty()}")
            }
        }
    }

    @JvmStatic
    fun execute(method: String, url: String, requestBody: String = ""): Result {
        val normalizedMethod = method.trim().uppercase()
        require(normalizedMethod == "GET" || normalizedMethod == "POST") {
            "Método no admitido: $method"
        }
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "La URL debe comenzar con http:// o https://"
        }
        require(normalizedMethod != "GET" || requestBody.isEmpty()) {
            "GET no debe enviar cuerpo en esta prueba"
        }

        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = normalizedMethod
                connectTimeout = 8_000
                readTimeout = 10_000
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WebData-Explorer-HTTP-Probe/1.0")
                if (normalizedMethod == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(requestBody.toByteArray(StandardCharsets.UTF_8).size)
                }
            }

            if (normalizedMethod == "POST") {
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }

            val status = connection.responseCode
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .sortedBy { it.key.lowercase() }
                .joinToString("\n") { entry -> "${entry.key}: ${entry.value.joinToString(", ")}" }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = if (stream == null) "" else BufferedInputStream(stream).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = 256 * 1024 - total
                    if (remaining <= 0) break
                    val accepted = minOf(count, remaining)
                    output.write(buffer, 0, accepted)
                    total += accepted
                    if (accepted < count) break
                }
                output.toString("UTF-8")
            }

            return Result(url, normalizedMethod, requestBody, status, headers, body, elapsedMs(started))
        } catch (e: SocketTimeoutException) {
            return failure(url, normalizedMethod, requestBody, started, "TIEMPO DE ESPERA", e)
        } catch (e: UnknownHostException) {
            return failure(url, normalizedMethod, requestBody, started, "DNS/CONEXIÓN", e)
        } catch (e: ConnectException) {
            return failure(url, normalizedMethod, requestBody, started, "CONEXIÓN RECHAZADA", e)
        } catch (e: IOException) {
            return failure(url, normalizedMethod, requestBody, started, "ERROR DE RED", e)
        } catch (e: SecurityException) {
            return failure(url, normalizedMethod, requestBody, started, "PERMISO/SEGURIDAD", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun failure(url: String, method: String, body: String, started: Long, type: String, error: Exception) =
        Result(url, method, body, null, "", "", elapsedMs(started), type, error.message ?: error.javaClass.simpleName)

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
}
