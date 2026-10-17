package com.webdata.explorer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Local, read-only first-pass APK inspector. It never installs or executes the selected APK.
 * Full Java decompilation is intentionally kept separate from this lightweight scanner.
 */
object ApkAnalyzer {
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private const val MAX_DEX_BYTES = 40L * 1024L * 1024L
    private const val MAX_REPORT_ITEMS = 250

    fun analyze(context: Context, uri: Uri): String {
        val originalName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "APK seleccionado"
        val temp = File.createTempFile("wdx-apk-", ".apk", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return "ERROR\nNo se pudo abrir el archivo seleccionado."
            input.use { source ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_APK_BYTES) {
                            "El APK supera el límite de análisis de 250 MB."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(temp.length() > 0) { "El archivo está vacío." }
            return inspectFile(context, temp, originalName)
        } finally {
            temp.delete()
        }
    }

    private fun inspectFile(context: Context, apk: File, originalName: String): String {
        val report = StringBuilder()
        report.appendLine("WEB DATA EXPLORER · ANALIZADOR APK")
        report.appendLine("Archivo original: $originalName")
        report.appendLine("Archivo temporal de análisis: ${apk.name}")
        report.appendLine("Tamaño: ${apk.length() / 1024} KiB")
        report.appendLine("Modo: análisis local de solo lectura")
        report.appendLine()

        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }.getOrNull()

        report.appendLine("=== INFORMACIÓN DEL PAQUETE ===")
        if (info == null) {
            report.appendLine("No se pudo interpretar el manifiesto con PackageManager.")
            report.appendLine("El archivo podría estar dañado o no ser un APK compatible.")
        } else {
            report.appendLine("Paquete: ${info.packageName}")
            report.appendLine("Versión: ${info.versionName ?: "desconocida"}")
            if (Build.VERSION.SDK_INT >= 28) report.appendLine("Version code: ${info.longVersionCode}")
            else {
                @Suppress("DEPRECATION")
                report.appendLine("Version code: ${info.versionCode}")
            }
            report.appendLine("SDK mínimo: ${info.applicationInfo?.minSdkVersion ?: "no disponible"}")
            report.appendLine("SDK objetivo: ${info.applicationInfo?.targetSdkVersion ?: "no disponible"}")
            report.appendLine("Activities: ${info.activities?.size ?: 0}")
            report.appendLine("Services: ${info.services?.size ?: 0}")
            report.appendLine("Receivers: ${info.receivers?.size ?: 0}")
            report.appendLine("Providers: ${info.providers?.size ?: 0}")
            val permissions = info.requestedPermissions.orEmpty()
            report.appendLine("Permisos solicitados (${permissions.size}):")
            permissions.take(100).forEach { report.appendLine("  • $it") }
            if (permissions.size > 100) report.appendLine("  … lista limitada a 100")
        }

        val allUrls = linkedSetOf<String>()
        val endpointPaths = linkedSetOf<String>()
        val interesting = linkedSetOf<String>()
        val dexEntries = mutableListOf<String>()
        val fileEntries = mutableListOf<String>()
        val nativeEntries = linkedSetOf<String>()
        val libraries = linkedSetOf<String>()
        val dexResults = mutableListOf<DexCodeInspector.Result>()
        val networkCallSites = linkedSetOf<String>()
        var totalDexBytes = 0L

        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (fileEntries.size < 600) fileEntries.add("${entry.name} (${entry.size} bytes)")
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    nativeEntries.add(entry.name)
                }
                if (entry.name.endsWith(".dex")) {
                    dexEntries.add(entry.name)
                    if (totalDexBytes < MAX_DEX_BYTES) {
                        val allowed = (MAX_DEX_BYTES - totalDexBytes).coerceAtLeast(0L)
                        val bytes = zip.getInputStream(entry).use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            var readTotal = 0L
                            while (readTotal < allowed) {
                                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), allowed - readTotal).toInt())
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                readTotal += n
                            }
                            totalDexBytes += readTotal
                            out.toByteArray()
                        }
                        val dexResult = DexCodeInspector.inspect(bytes, context.cacheDir)
                        dexResults.add(dexResult)
                        allUrls.addAll(dexResult.endpoints.filter { it.startsWith("http://") || it.startsWith("https://") })
                        endpointPaths.addAll(dexResult.endpoints.filterNot { it.startsWith("http://") || it.startsWith("https://") })
                        libraries.addAll(dexResult.libraries)
                        networkCallSites.addAll(dexResult.networkCalls)
                        interesting.addAll(dexResult.strings.filter { it.length in 4..350 })
                        val strings = printableStrings(bytes)
                        strings.forEach { rawValue ->
                            // Scan the original text and a small set of safely decoded variants.
                            // This helps reveal endpoints obfuscated as hex/Base64/URL encoding.
                            val candidates = linkedSetOf(rawValue)
                            candidates.addAll(decodeStringCandidates(rawValue))
                            for (value in candidates) {
                                URL_REGEX.findAll(value).forEach { match ->
                                    allUrls.add(match.value.trimEnd('.', ',', ';', ')', ']', '}'))
                                }
                                PATH_REGEX.findAll(value).forEach { match -> endpointPaths.add(match.value) }
                                if (INTERESTING_REGEX.containsMatchIn(value) && value.length in 4..350) {
                                    interesting.add(value)
                                }
                                LIBRARY_REGEX.findAll(value).forEach { match ->
                                    libraries.add(match.value)
                                }
                            }
                        }
                    }
                }
            }
        }

        report.appendLine()
        report.appendLine("=== CLASES, MÉTODOS E INSTRUCCIONES DEX ===")
        report.appendLine("Clases estructuradas: ${dexResults.sumOf { it.classCount }}")
        report.appendLine("Métodos examinados: ${dexResults.sumOf { it.methodCount }}")
        report.appendLine("Llamadas invoke detectadas: ${dexResults.sumOf { it.invokeCount }}")
        dexResults.mapNotNull { it.warning }.forEach { report.appendLine("Aviso: $it") }
        report.appendLine("Muestra de instrucciones tipo Smali (límite de salida):")
        dexResults.joinToString("\n\n") { it.listing }.take(30000).takeIf { it.isNotBlank() }?.let { report.appendLine(it) }
        if (dexResults.isEmpty()) report.appendLine("No se pudo analizar ningún DEX dentro del límite configurado.")

        report.appendLine()
        report.appendLine("=== ARCHIVOS INTERNOS ===")
        report.appendLine("Entradas DEX: ${dexEntries.size} (${dexEntries.joinToString()})")
        report.appendLine("Bytes DEX escaneados: $totalDexBytes (límite ${MAX_DEX_BYTES / 1024 / 1024} MiB)")
        report.appendLine("Bibliotecas nativas (.so): ${nativeEntries.size}")
        nativeEntries.take(100).forEach { report.appendLine("  • $it") }
        report.appendLine("Listado de archivos (máximo 600):")
        fileEntries.forEach { report.appendLine("  • $it") }

        report.appendLine()
        report.appendLine("=== POSIBLES PUNTOS DE LLAMADA DE RED ===")
        report.appendLine("Referencias detectadas: ${networkCallSites.size}")
        networkCallSites.take(MAX_REPORT_ITEMS).forEach { report.appendLine(it) }
        if (networkCallSites.size > MAX_REPORT_ITEMS) report.appendLine("… resultados limitados")
        if (networkCallSites.isEmpty()) report.appendLine("No se detectaron referencias de red reconocibles en las instrucciones examinadas.")

        report.appendLine()
        report.appendLine("=== URLS ENCONTRADAS EN DEX ===")
        report.appendLine("Coincidencias únicas: ${allUrls.size}")
        allUrls.take(MAX_REPORT_ITEMS).forEach { report.appendLine(it) }
        if (allUrls.size > MAX_REPORT_ITEMS) report.appendLine("… resultados limitados")

        report.appendLine()
        report.appendLine("=== POSIBLES RUTAS API ===")
        report.appendLine("Coincidencias únicas: ${endpointPaths.size}")
        endpointPaths.take(MAX_REPORT_ITEMS).forEach { report.appendLine(it) }
        if (endpointPaths.size > MAX_REPORT_ITEMS) report.appendLine("… resultados limitados")

        report.appendLine()
        report.appendLine("=== BIBLIOTECAS / CLIENTES HTTP DETECTADOS ===")
        val knownLibraries = libraries + interesting.filter {
            LIBRARY_REGEX.containsMatchIn(it)
        }
        if (knownLibraries.isEmpty()) report.appendLine("No se detectaron nombres reconocibles en las cadenas examinadas.")
        knownLibraries.take(100).forEach { report.appendLine(it) }

        report.appendLine()
        report.appendLine("=== CADENAS RELEVANTES ===")
        val selected = interesting.filter {
            INTERESTING_REGEX.containsMatchIn(it) && !URL_REGEX.containsMatchIn(it)
        }
        report.appendLine("Coincidencias: ${selected.size}")
        selected.take(MAX_REPORT_ITEMS).forEach { report.appendLine(it) }

        report.appendLine()
        report.appendLine("=== LÍMITES DEL ANÁLISIS ===")
        report.appendLine("El analizador ya interpreta la estructura DEX y muestra clases, métodos e instrucciones con referencias; la salida Smali es una vista limitada, no una reconstrucción Java completa.")
        report.appendLine("Las URLs/rutas son pistas, no endpoints confirmados. Puede perder cadenas codificadas, comprimidas o construidas en tiempo de ejecución.")
        report.appendLine("No se ejecuta ni instala el APK. La descompilación Java completa y el renderizado Smali fiel requieren una etapa adicional de integración y validación.")
        return report.toString()
    }

    /**
     * Returns bounded candidate decodings only; it does not execute code or contact endpoints.
     * Hex is accepted only when the full string is even-length hex; Base64 is length-limited.
     */
    private fun decodeStringCandidates(value: String): Set<String> {
        val result = linkedSetOf<String>()
        val trimmed = value.trim()
        if (trimmed.length in 8..4096 && trimmed.length % 2 == 0 &&
            trimmed.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            runCatching {
                val bytes = ByteArray(trimmed.length / 2) { index ->
                    trimmed.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
                val decoded = bytes.toString(Charsets.UTF_8)
                if (decoded.count { it == '\uFFFD' } < 2 && decoded.any { it.isLetter() }) result.add(decoded)
            }
        }
        if (trimmed.length in 12..4096 && trimmed.matches(Regex("[A-Za-z0-9_+/=-]+"))) {
            runCatching {
                val decodedBytes = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
                if (decodedBytes.size in 4..4096) {
                    val decoded = decodedBytes.toString(Charsets.UTF_8)
                    if (decoded.count { it == '\uFFFD' } < 2 && decoded.any { it.isLetter() }) result.add(decoded)
                }
            }
        }
        if (trimmed.contains('%') && trimmed.length <= 4096) {
            runCatching {
                val decoded = java.net.URLDecoder.decode(trimmed, "UTF-8")
                if (decoded != trimmed && decoded.length <= 4096) result.add(decoded)
            }
        }
        return result
    }

    private fun printableStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value in 0x20..0x7e || value == 0x09) {
                current.append(value.toChar())
            } else {
                if (current.length >= 4) result.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length >= 4) result.add(current.toString())
        return result
    }

    private val URL_REGEX = Regex("""https?://[^\s\"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val PATH_REGEX = Regex("""/(?:api|v[0-9]+|graphql|rest|auth|oauth|portalCore|wp-json)/[^\s\"'<>\\]{1,180}""", RegexOption.IGNORE_CASE)
    private val INTERESTING_REGEX = Regex(
        """api|endpoint|base.?url|retrofit|okhttp|volley|graphql|authorization|bearer|token|client.?id|secret|domain|server|host|login|oauth|portalcore|websocket|wss?://|https?://""",
        RegexOption.IGNORE_CASE
    )
    private val LIBRARY_REGEX = Regex(
        """(?:retrofit2?|okhttp3?|com\.android\.volley|ktor|apollo|graphql|cronet|websocket)""",
        RegexOption.IGNORE_CASE
    )
}
