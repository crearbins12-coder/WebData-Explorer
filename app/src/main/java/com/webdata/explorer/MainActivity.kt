package com.webdata.explorer

import android.app.Activity
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 18, 28)
        window.navigationBarColor = Color.rgb(15, 18, 28)
        window.decorView.setBackgroundColor(Color.rgb(15, 18, 28))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "WebData Explorer"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Analizador de HTML · APIs · JavaScript · multimedia"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, dp(16))
        })

        urlInput = EditText(this).apply {
            hint = "https://ejemplo.com"
            isSingleLine = true
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(35, 40, 54))
        }
        root.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "ANALIZAR PÁGINA"
            setOnClickListener { analyze() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        root.addView(Button(this).apply {
            text = "COPIAR INFORME"
            setOnClickListener {
                if (lastReport.isBlank()) toast("Primero analizá una página")
                else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WebData Explorer", lastReport))
                    toast("Informe copiado")
                }
            }
        })

        statusText = TextView(this).apply {
            text = "Listo para analizar una URL pública."
            textSize = 13f
            setTextColor(Color.rgb(120, 210, 170))
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(statusText)

        resultText = TextView(this).apply {
            text = "Los resultados aparecerán acá."
            textSize = 13f
            setTextColor(Color.rgb(230, 233, 240))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(18))
            setBackgroundColor(Color.rgb(25, 29, 41))
        }
        val scroll = ScrollView(this)
        scroll.addView(resultText)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })
        setContentView(root)
    }

    private fun analyze() {
        var raw = urlInput.text.toString().trim()
        if (raw.isBlank()) {
            toast("Pegá una dirección web")
            return
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "https://$raw"
        val parsed = runCatching { URL(raw) }.getOrNull()
        if (parsed == null || (parsed.protocol != "http" && parsed.protocol != "https")) {
            toast("La URL no es válida")
            return
        }
        urlInput.setText(raw)
        statusText.text = "Analizando HTML y scripts…"
        resultText.text = "Descargando contenido y buscando enlaces, APIs y multimedia…"
        executor.execute {
            val report = runCatching { inspect(raw) }
                .getOrElse { "ERROR DE ANÁLISIS\n${it.javaClass.simpleName}: ${it.message ?: "sin detalles"}" }
            runOnUiThread {
                lastReport = report
                resultText.text = report
                statusText.text = if (report.startsWith("ERROR")) "No se pudo completar el análisis"
                    else if (report.contains("ANÁLISIS INCOMPLETO")) "Análisis limitado: bloqueo o desafío detectado"
                    else "Análisis terminado"
            }
        }
    }

    private data class FetchResult(val body: String, val finalUrl: String, val code: Int, val contentType: String)

    private fun fetch(address: String, maxChars: Int): FetchResult {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/javascript,text/javascript,*/*;q=0.8")
        }
        try {
            val code = connection.responseCode
            val finalUrl = connection.url.toString()
            val contentType = connection.contentType ?: "desconocido"
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(maxChars) } ?: ""
            return FetchResult(body, finalUrl, code, contentType)
        } finally {
            connection.disconnect()
        }
    }

    private fun inspect(address: String): String {
        val page = fetch(address, 1_500_000)
        val html = page.body
        val base = URI(page.finalUrl)
        val links = linkedSetOf<String>()
        val apiPaths = linkedSetOf<String>()
        val media = linkedSetOf<String>()
        val scripts = linkedSetOf<String>()
        val inlineScripts = mutableListOf<String>()
        val scriptErrors = mutableListOf<String>()

        val attrRegex = Regex("""(?is)\b(?:href|src|action|data-src|data-url|data-href)\s*=\s*["']([^"'<> ]+)["']""")
        for (match in attrRegex.findAll(html)) {
            val value = match.groupValues[1].trim()
            val resolved = runCatching { base.resolve(value).toString() }.getOrNull() ?: continue
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                links.add(resolved)
                val clean = resolved.substringBefore("?").substringBefore("#")
                if (clean.endsWith(".js", true) || clean.contains(".js/")) scripts.add(resolved)
                if (isMedia(resolved)) media.add(resolved)
            }
        }

        val scriptTagRegex = Regex("""(?is)<script\b([^>]*)>(.*?)</script\s*>""")
        for (match in scriptTagRegex.findAll(html)) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val srcMatch = Regex("""(?is)\bsrc\s*=\s*["']([^"']+)["']""").find(attrs)
            if (srcMatch == null) {
                if (body.isNotBlank()) inlineScripts.add(body.take(300_000))
            } else {
                val src = runCatching { base.resolve(srcMatch.groupValues[1]).toString() }.getOrNull()
                if (src != null && (src.startsWith("https://") || src.startsWith("http://"))) scripts.add(src)
            }
        }

        val absoluteRegex = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        for (match in absoluteRegex.findAll(html)) {
            val value = match.value.trimEnd(')', ']', ',', ';', '.')
            links.add(value)
            if (isMedia(value)) media.add(value)
        }

        val sourcesToScan = mutableListOf(html)
        sourcesToScan.addAll(inlineScripts)
        var scriptsFetched = 0
        for (scriptUrl in scripts.take(12)) {
            try {
                val result = fetch(scriptUrl, 500_000)
                scriptsFetched++
                if (result.code in 200..299 &&
                    (result.contentType.contains("javascript", true) ||
                     scriptUrl.substringBefore("?").endsWith(".js", true) ||
                     result.body.contains("fetch(", true) ||
                     result.body.contains("XMLHttpRequest", true))) {
                    sourcesToScan.add(result.body)
                    val scriptBase = URI(result.finalUrl)
                    for (match in absoluteRegex.findAll(result.body)) {
                        val found = match.value.trimEnd(')', ']', ',', ';', '.')
                        links.add(found)
                        if (isMedia(found)) media.add(found)
                    }
                    for (match in attrRegex.findAll(result.body)) {
                        val value = match.groupValues[1].trim()
                        if (value.startsWith("http://") || value.startsWith("https://")) links.add(value)
                        else if (value.startsWith("/") && !value.startsWith("//")) {
                            links.add(runCatching { scriptBase.resolve(value).toString() }.getOrDefault(value))
                        }
                    }
                } else {
                    scriptErrors.add("$scriptUrl → HTTP ${result.code}")
                }
            } catch (e: Exception) {
                scriptErrors.add("$scriptUrl → ${e.javaClass.simpleName}")
            }
        }

        val apiRegex = Regex("""(?i)(?:https?://[^\s"'<>]+)?/(?:api|apis|graphql|gql|v[0-9]+/api)/[A-Za-z0-9_./?=&%{}:$-]*""")
        val quotedRouteRegex = Regex("""(?i)["'](\/(?:api|graphql|gql|ajax|embed|player|videos?|episodes?)[A-Za-z0-9_./?=&%{}:$-]*)["']""")
        val fetchRouteRegex = Regex("""(?is)(?:fetch|axios\.(?:get|post|put|delete)|open)\s*\(\s*["'](https?://[^"']+|/[^"']+)["']""")
        for (source in sourcesToScan) {
            for (match in apiRegex.findAll(source)) apiPaths.add(match.value.trimEnd(')', ']', ',', ';', '.'))
            for (match in quotedRouteRegex.findAll(source)) apiPaths.add(match.groupValues[1])
            for (match in fetchRouteRegex.findAll(source)) {
                val route = match.groupValues[1]
                if (route.contains("/api", true) || route.contains("graphql", true) ||
                    route.contains("/embed", true) || route.contains("/player", true)) apiPaths.add(route)
            }
            for (match in absoluteRegex.findAll(source)) {
                val value = match.value.trimEnd(')', ']', ',', ';', '.')
                if (isMedia(value)) media.add(value)
            }
        }

        val cloudflareChallenge = page.code == 403 &&
            (html.contains("cloudflare", true) || html.contains("challenge-platform", true) ||
             html.contains("cf-chl-", true) || html.contains("Just a moment", true))
        val accessBlocked = page.code == 401 || page.code == 403 || page.code == 429
        val domains = (links + scripts + media).mapNotNull { runCatching { URL(it).host }.getOrNull() }.toSortedSet()

        return buildString {
            appendLine("WEBData Explorer — INFORME")
            appendLine("==========================")
            appendLine("URL solicitada: $address")
            appendLine("URL final: ${page.finalUrl}")
            appendLine("HTTP: ${page.code}")
            appendLine("Content-Type: ${page.contentType}")
            appendLine("HTML leído: ${html.length} caracteres")
            appendLine("Scripts externos detectados: ${scripts.size}; descargados: $scriptsFetched")
            appendLine()
            if (cloudflareChallenge) {
                appendLine("ANÁLISIS INCOMPLETO")
                appendLine("Motivo probable: respuesta 403 con señales de desafío de Cloudflare.")
                appendLine("El HTML recibido parece ser una verificación y no el contenido normal.")
                appendLine("No se intentó eludir la protección. Probá abrir el sitio en el navegador normal y analizar una página cuyo contenido sea accesible.")
                appendLine()
            } else if (accessBlocked) {
                appendLine("AVISO DE ACCESO")
                appendLine("El servidor respondió HTTP ${page.code}. Los resultados pueden corresponder a una página de error y no al contenido solicitado.")
                appendLine()
            }
            appendLine("DOMINIOS (${domains.size})")
            appendLine(domains.take(150).joinToString("\n").ifBlank { "No se detectaron dominios." })
            appendLine()
            appendLine("POSIBLES ENDPOINTS API (${apiPaths.size})")
            appendLine(apiPaths.take(250).joinToString("\n").ifBlank { "No se encontraron rutas API evidentes en el contenido descargado." })
            appendLine()
            appendLine("ENLACES (${links.size})")
            appendLine(links.take(250).joinToString("\n").ifBlank { "No se encontraron enlaces." })
            appendLine()
            appendLine("SCRIPTS JAVASCRIPT (${scripts.size})")
            appendLine(scripts.take(100).joinToString("\n").ifBlank { "No se detectaron scripts externos por URL." })
            if (scriptErrors.isNotEmpty()) {
                appendLine("Scripts no analizados:")
                appendLine(scriptErrors.take(20).joinToString("\n"))
            }
            appendLine()
            appendLine("POSIBLES MEDIOS (${media.size})")
            appendLine(media.take(150).joinToString("\n").ifBlank { "No se encontraron URLs multimedia evidentes." })
            appendLine()
            appendLine("NOTA: se inspeccionó el HTML recibido y hasta 12 scripts externos. Las rutas detectadas son coincidencias, no garantía de que un endpoint sea válido o accesible.")
        }
    }

    private fun isMedia(value: String): Boolean {
        val path = value.substringBefore("?").substringBefore("#").lowercase()
        return listOf(".m3u8", ".mp4", ".mpd", ".webm", ".mov", ".m4v", ".ts").any { path.endsWith(it) }
            || value.contains("manifest", true)
            || value.contains("videoplayback", true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
