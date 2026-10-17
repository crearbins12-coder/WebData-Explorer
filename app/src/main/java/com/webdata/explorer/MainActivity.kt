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

        val title = TextView(this).apply {
            text = "WebData Explorer"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Analizador de HTML · APIs · JavaScript · multimedia"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, dp(16))
        }
        root.addView(subtitle)

        urlInput = EditText(this).apply {
            hint = "https://ejemplo.com"
            singleLine = true
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(35, 40, 54))
        }
        root.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val analyzeButton = Button(this).apply {
            text = "ANALIZAR PÁGINA"
            setOnClickListener { analyze() }
        }
        root.addView(analyzeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val copyButton = Button(this).apply {
            text = "COPIAR INFORME"
            setOnClickListener {
                if (lastReport.isBlank()) {
                    toast("Primero analizá una página")
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WebData Explorer", lastReport))
                    toast("Informe copiado")
                }
            }
        }
        root.addView(copyButton)

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
        statusText.text = "Analizando…"
        resultText.text = "Descargando HTML y buscando enlaces, APIs y multimedia…"

        executor.execute {
            val report = runCatching { inspect(raw) }
                .getOrElse { "ERROR DE ANÁLISIS\n${it.javaClass.simpleName}: ${it.message ?: "sin detalles"}" }
            runOnUiThread {
                lastReport = report
                resultText.text = report
                statusText.text = if (report.startsWith("ERROR")) "No se pudo completar el análisis" else "Análisis terminado"
            }
        }
    }

    private fun inspect(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WebDataExplorer/1.0 (Android)")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        }

        val html: String
        val finalUrl: String
        val code: Int
        val contentType: String
        try {
            code = connection.responseCode
            finalUrl = connection.url.toString()
            contentType = connection.contentType ?: "desconocido"
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            html = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(2_000_000) }
                ?: ""
        } finally {
            connection.disconnect()
        }

        val base = URI(finalUrl)
        val links = linkedSetOf<String>()
        val apiPaths = linkedSetOf<String>()
        val media = linkedSetOf<String>()
        val scripts = linkedSetOf<String>()

        val attrRegex = Regex("""(?i)(?:href|src|action|data-src)\s*=\s*["']([^"'<> ]+)["']""")
        for (match in attrRegex.findAll(html)) {
            val value = match.groupValues[1].trim()
            val resolved = runCatching { base.resolve(value).toString() }.getOrNull() ?: continue
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                links.add(resolved)
                if (value.endsWith(".js", true) || resolved.substringBefore("?").endsWith(".js", true)) scripts.add(resolved)
                if (isMedia(resolved)) media.add(resolved)
            }
        }

        val absoluteRegex = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        for (match in absoluteRegex.findAll(html)) {
            val value = match.value.trimEnd(')', ']', ',', ';', '.')
            links.add(value)
            if (isMedia(value)) media.add(value)
        }

        val apiRegex = Regex("""(?i)(?:https?://[^\s"'<>]+)?/api/[A-Za-z0-9_./?=&%{}-]*""")
        for (match in apiRegex.findAll(html)) {
            apiPaths.add(match.value.trimEnd(')', ']', ',', ';', '.'))
        }
        val gqlRegex = Regex("""(?i)["']([^"']*graphql[^"']*)["']""")
        for (match in gqlRegex.findAll(html)) apiPaths.add(match.groupValues[1])

        val domains = links.mapNotNull { runCatching { URL(it).host }.getOrNull() }
            .toSortedSet()

        return buildString {
            appendLine("WEBData Explorer — INFORME")
            appendLine("==========================")
            appendLine("URL solicitada: $address")
            appendLine("URL final: $finalUrl")
            appendLine("HTTP: $code")
            appendLine("Content-Type: $contentType")
            appendLine("HTML leído: ${html.length} caracteres")
            appendLine()
            appendLine("DOMINIOS (${domains.size})")
            appendLine(domains.take(150).joinToString("\n").ifBlank { "No se detectaron dominios." })
            appendLine()
            appendLine("POSIBLES ENDPOINTS API (${apiPaths.size})")
            appendLine(apiPaths.take(200).joinToString("\n").ifBlank { "No se encontraron rutas API evidentes en el HTML." })
            appendLine()
            appendLine("ENLACES (${links.size})")
            appendLine(links.take(250).joinToString("\n").ifBlank { "No se encontraron enlaces." })
            appendLine()
            appendLine("SCRIPTS JAVASCRIPT (${scripts.size})")
            appendLine(scripts.take(100).joinToString("\n").ifBlank { "No se detectaron scripts por URL." })
            appendLine()
            appendLine("POSIBLES MEDIOS (${media.size})")
            appendLine(media.take(150).joinToString("\n").ifBlank { "No se encontraron URLs multimedia evidentes." })
            appendLine()
            appendLine("NOTA: los resultados son coincidencias del HTML recibido. No garantizan que una API o URL multimedia sea accesible o reproducible.")
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
