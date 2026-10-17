package com.webdata.explorer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.ViewGroup
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
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
    private lateinit var browser: WebView
    private lateinit var requestText: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var contentFrame: FrameLayout
    private val requestLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var lastReport = ""
    private var browserMode = false
    private var requestFilter = "TODAS"
    private var pendingExport = ""
    private val exportRequestCode = 4501

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestPollRunnable = object : Runnable {
        override fun run() {
            if (!browserMode || !::browser.isInitialized) return
            browser.evaluateJavascript(
                """(function(){
                    try {
                        var resources = performance.getEntriesByType('resource').map(function(e){return e.name;});
                        var media = Array.prototype.slice.call(document.querySelectorAll('video, audio, source')).map(function(e){
                            return e.currentSrc || e.src || e.getAttribute('src') || '';
                        }).filter(Boolean);
                        return JSON.stringify({resources:resources, media:media, network:window.__wdxNet || []});
                    } catch(e) { return JSON.stringify({error:String(e)}); }
                })()""".trimIndent()
            ) { value ->
                runCatching {
                    val decoded = org.json.JSONTokener(value).nextValue() as? String ?: "{}"
                    val payload = org.json.JSONObject(decoded)
                    val resources = payload.optJSONArray("resources") ?: org.json.JSONArray()
                    for (i in 0 until resources.length()) {
                        val item = resources.optString(i)
                        if (item.startsWith("http://") || item.startsWith("https://")) addRequest("RECURSO DOM [${classifyRequest(item)}] $item")
                    }
                    val media = payload.optJSONArray("media") ?: org.json.JSONArray()
                    for (i in 0 until media.length()) {
                        val item = media.optString(i)
                        if (item.startsWith("http://") || item.startsWith("https://")) addRequest("MEDIO DOM [${classifyRequest(item)}] $item")
                    }
                    val network = payload.optJSONArray("network") ?: org.json.JSONArray()
                    for (i in 0 until network.length()) {
                        val item = network.optJSONObject(i) ?: continue
                        val kind = item.optString("kind", "FETCH")
                        val url = item.optString("url", "")
                        val code = item.optInt("status", 0)
                        if (url.startsWith("http://") || url.startsWith("https://")) addRequest("$kind HTTP $code [${classifyRequest(url)}] $url")
                    }
                }
                if (browserMode) mainHandler.postDelayed(requestPollRunnable, 1800)
            }
        }
    }

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

        val navButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navButtons.addView(Button(this).apply {
            text = "NAVEGADOR"
            setOnClickListener { showBrowser() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navButtons.addView(Button(this).apply {
            text = "ANALIZADOR"
            setOnClickListener { showAnalyzer() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(navButtons)

        val actionButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionButtons.addView(Button(this).apply {
            text = "COPIAR"
            setOnClickListener {
                val content = if (browserMode) synchronized(requestLog) { requestLog.joinToString("\\n") } else lastReport
                if (content.isBlank()) toast("Todavía no hay datos para copiar")
                else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WebData Explorer", content))
                    toast("Copiado al portapapeles")
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionButtons.addView(Button(this).apply {
            text = "EXPORTAR JSON"
            setOnClickListener { exportRequests() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionButtons)

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
        resultScroll = ScrollView(this).apply { addView(resultText) }
        browser = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request != null) {
                        val url = request.url.toString()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val kind = classifyRequest(url)
                            addRequest("${request.method} [$kind] ${if (request.isForMainFrame) "[PÁGINA]" else "[RECURSO]"} $url")
                        }
                    }
                    return null
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request != null) addRequest("HTTP ${errorResponse?.statusCode ?: "?"} ${request.method} ${request.url}")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusText.text = "Navegación terminada · registrando recursos…"
                    // Instala observadores pasivos para las siguientes interacciones del usuario.
                    // No resuelve ni evita desafíos de acceso.
                    view?.evaluateJavascript(
                        """(function(){
                            try {
                                if (!window.__wdxInstalled) {
                                    window.__wdxInstalled = true;
                                    window.__wdxNet = [];
                                    var record = function(kind, url, status) {
                                        try {
                                            var absolute = new URL(String(url), location.href).href;
                                            window.__wdxNet.push({kind:kind,url:absolute,status:status || 0});
                                            if (window.__wdxNet.length > 200) window.__wdxNet.shift();
                                        } catch(e) {}
                                    };
                                    if (window.fetch) {
                                        var originalFetch = window.fetch;
                                        window.fetch = function() {
                                            var input = arguments[0];
                                            var url = input && input.url ? input.url : input;
                                            return originalFetch.apply(this, arguments).then(function(response) {
                                                record('FETCH', url, response.status);
                                                return response;
                                            }, function(error) {
                                                record('FETCH_ERROR', url, 0);
                                                throw error;
                                            });
                                        };
                                    }
                                    var originalOpen = XMLHttpRequest.prototype.open;
                                    var originalSend = XMLHttpRequest.prototype.send;
                                    XMLHttpRequest.prototype.open = function(method, url) {
                                        this.__wdxMethod = method;
                                        this.__wdxUrl = url;
                                        return originalOpen.apply(this, arguments);
                                    };
                                    XMLHttpRequest.prototype.send = function() {
                                        var xhr = this;
                                        xhr.addEventListener('loadend', function() {
                                            record('XHR ' + (xhr.__wdxMethod || 'GET'), xhr.__wdxUrl || '', xhr.status);
                                        });
                                        return originalSend.apply(this, arguments);
                                    };
                                }
                                var resources = performance.getEntriesByType('resource').map(function(e){return e.name;});
                                var media = Array.prototype.slice.call(document.querySelectorAll('video, audio, source')).map(function(e){
                                    return e.currentSrc || e.src || e.getAttribute('src') || '';
                                }).filter(Boolean);
                                return JSON.stringify({resources:resources, media:media, network:window.__wdxNet || []});
                            } catch(e) { return JSON.stringify({error:String(e)}); }
                        })()""".trimIndent()
                    ) { value ->
                        runCatching {
                            val decoded = org.json.JSONTokener(value).nextValue() as? String ?: "{}"
                            val payload = org.json.JSONObject(decoded)
                            val resources = payload.optJSONArray("resources") ?: org.json.JSONArray()
                            for (i in 0 until resources.length()) {
                                val item = resources.optString(i)
                                if (item.startsWith("http://") || item.startsWith("https://")) addRequest("RECURSO DOM [${classifyRequest(item)}] $item")
                            }
                            val media = payload.optJSONArray("media") ?: org.json.JSONArray()
                            for (i in 0 until media.length()) {
                                val item = media.optString(i)
                                if (item.startsWith("http://") || item.startsWith("https://")) addRequest("MEDIO DOM [${classifyRequest(item)}] $item")
                            }
                            val network = payload.optJSONArray("network") ?: org.json.JSONArray()
                            for (i in 0 until network.length()) {
                                val item = network.optJSONObject(i) ?: continue
                                val kind = item.optString("kind", "FETCH")
                                val url = item.optString("url", "")
                                val code = item.optInt("status", 0)
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    addRequest("$kind HTTP $code [${classifyRequest(url)}] $url")
                                }
                            }
                        }
                        runOnUiThread {
                            refreshRequestText()
                            statusText.text = "Navegador listo · observando solicitudes continuamente. Interactuá con la página para capturar más datos."
                            mainHandler.removeCallbacks(requestPollRunnable)
                            mainHandler.postDelayed(requestPollRunnable, 1800)
                        }
                    }
                }
            }
        }
        requestText = TextView(this).apply {
            text = "Las solicitudes observadas aparecerán acá."
            textSize = 11f
            setTextColor(Color.rgb(230, 233, 240))
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val browserPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "SOLICITUDES DE RED · FILTROS"
                textSize = 12f
                setTextColor(Color.rgb(120, 210, 170))
                setPadding(dp(4), dp(5), dp(4), dp(3))
            })
            val filters = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("TODAS", "API", "VIDEO", "JS", "ERRORES", "ANTI-BOT").forEach { filter ->
                filters.addView(Button(this@MainActivity).apply {
                    text = filter
                    textSize = 9f
                    minWidth = dp(64)
                    setPadding(dp(5), 0, dp(5), 0)
                    setOnClickListener { requestFilter = filter; refreshRequestText() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            }
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(filters)
            })
            addView(ScrollView(this@MainActivity).apply { addView(requestText) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(125)))
        }
        contentFrame = FrameLayout(this)
        contentFrame.addView(resultScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        contentFrame.addView(browserPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })
        setContentView(root)
        browser.tag = browserPanel
    }

    private fun showBrowser() {
        browserMode = true
        resultScroll.visibility = View.GONE
        (browser.tag as? View)?.visibility = View.VISIBLE
        statusText.text = "Navegador integrado · solicitudes HTTP observadas"
        var raw = urlInput.text.toString().trim()
        if (raw.isBlank()) { toast("Pegá una URL primero"); return }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "https://$raw"
        val parsed = runCatching { URL(raw) }.getOrNull()
        if (parsed == null || (parsed.protocol != "http" && parsed.protocol != "https")) {
            toast("La URL no es válida"); return
        }
        urlInput.setText(raw)
        requestLog.clear()
        refreshRequestText()
        browser.loadUrl(raw)
    }

    private fun showAnalyzer() {
        browserMode = false
        mainHandler.removeCallbacks(requestPollRunnable)
        (browser.tag as? View)?.visibility = View.GONE
        resultScroll.visibility = View.VISIBLE
        statusText.text = "Modo analizador HTML"
    }

    private fun classifyRequest(raw: String): String {
        val lower = raw.lowercase()
        return when {
            isAntiBotUrl(lower) -> "ANTI-BOT"
            lower.contains("/api/") || lower.contains("graphql") || lower.contains("/ajax/") -> "API?"
            listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".ts").any { lower.substringBefore("?").endsWith(it) } -> "MEDIO?"
            lower.substringBefore("?").endsWith(".js") -> "JS"
            lower.substringBefore("?").endsWith(".css") -> "CSS"
            lower.substringBefore("?").matches(Regex(".*\\.(png|jpg|jpeg|webp|gif|svg|avif)$")) -> "IMAGEN"
            else -> "WEB"
        }
    }

    private fun addRequest(line: String) {
        synchronized(requestLog) {
            if (!requestLog.contains(line)) {
                requestLog.add(line)
                if (requestLog.size > 300) requestLog.removeAt(0)
            }
        }
        runOnUiThread { refreshRequestText() }
    }

    private fun matchesFilter(line: String): Boolean {
        val lower = line.lowercase()
        return when (requestFilter) {
            "API" -> lower.contains("[api?]") || lower.contains("/api/") || lower.contains("graphql") || lower.contains("/ajax/")
            "VIDEO" -> lower.contains("[medio?]") || listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".ts").any { lower.substringBefore("?").contains(it) }
            "JS" -> lower.contains("[js]") || lower.substringBefore("?").endsWith(".js")
            "ERRORES" -> lower.startsWith("http ") || lower.contains(" error ") || lower.contains("failed") || Regex("""http\s+[45]\d\d""").containsMatchIn(lower)
            "ANTI-BOT" -> isAntiBotUrl(lower)
            else -> true
        }
    }

    private fun refreshRequestText() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val filtered = snapshot.filter { matchesFilter(it) }.asReversed()
        requestText.text = if (snapshot.isEmpty()) {
            "Todavía no se observaron solicitudes. Abrí una página o recargala."
        } else {
            "Filtro: $requestFilter · ${filtered.size}/${snapshot.size} solicitudes (máximo 300)\n\n" +
                filtered.joinToString("\n\n") { displayRequestLine(it) }
        }
    }

    private fun isAntiBotUrl(value: String): Boolean {
        val lower = value.lowercase()
        return listOf("cloudflare", "/cdn-cgi/", "turnstile", "challenge-platform", "chl_page", "/pat/", "/fo/", "/ci/", "brunhild.challenges").any { lower.contains(it) }
    }

    // Sólo acorta la vista; el registro original y la exportación JSON conservan la URL completa.
    private fun displayRequestLine(line: String): String {
        val urlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        return urlRegex.replace(line) { match ->
            val url = match.value
            if (url.length > 220 && isAntiBotUrl(url)) url.take(180) + "… [URL anti-bot extensa, " + url.length + " caracteres]"
            else url
        }
    }

    private fun exportRequests() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        if (snapshot.isEmpty()) {
            toast("Primero abrí una página para registrar solicitudes")
            return
        }
        val requests = org.json.JSONArray()
        snapshot.forEachIndexed { index, line ->
            requests.put(org.json.JSONObject().put("index", index + 1).put("entry", line))
        }
        pendingExport = org.json.JSONObject()
            .put("app", "WebData Explorer")
            .put("url", urlInput.text.toString())
            .put("count", snapshot.size)
            .put("requests", requests)
            .toString(2)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "webdata-solicitudes.json")
        }
        startActivityForResult(intent, exportRequestCode)
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(pendingExport.toByteArray(Charsets.UTF_8))
            } ?: error("No se pudo abrir el archivo")
        }.onSuccess {
            toast("JSON exportado correctamente")
        }.onFailure {
            toast("No se pudo exportar: ${it.message ?: "error"}")
        }
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

        val absoluteRegex = Regex("""https?://[^,\\s"'<>\\\\]+""", RegexOption.IGNORE_CASE)
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
        val playerPages = links.filter { isPlayerPage(it) }.toSortedSet()

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
            appendLine("PÁGINAS DE REPRODUCTOR / EMBEDS (${playerPages.size})")
            appendLine(playerPages.take(100).joinToString("\n").ifBlank { "No se detectaron páginas de reproductor reconocibles." })
            appendLine()
            appendLine("POSIBLES MEDIOS DIRECTOS (${media.size})")
            appendLine(media.take(150).joinToString("\n").ifBlank { "No se encontraron URLs multimedia directas evidentes. Los enlaces a páginas de reproductor no son archivos de video directos." })
            appendLine()
            appendLine("NOTA: se inspeccionó el HTML recibido y hasta 12 scripts externos. Las rutas detectadas son coincidencias, no garantía de que un endpoint sea válido o accesible.")
        }
    }

    private fun isPlayerPage(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = (uri.host ?: "").lowercase()
        val path = (uri.path ?: "").lowercase()
        val knownHosts = listOf("doodstream.com", "dhcplay.com", "movearnpre.com", "voe.sx", "mixdrop", "mp4upload.com", "bysesukior.com")
        return knownHosts.any { host == it || host.endsWith(".$it") } ||
            path.startsWith("/embed/") || path.startsWith("/d/") || path.startsWith("/f/")
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
        mainHandler.removeCallbacks(requestPollRunnable)
        executor.shutdownNow()
        browser.destroy()
        super.onDestroy()
    }
}
