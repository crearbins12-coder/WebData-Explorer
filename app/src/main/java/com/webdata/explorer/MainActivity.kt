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
import android.webkit.CookieManager
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
    private val requestKeys = HashSet<String>()
    private var lastReport = ""
    private var browserMode = false
    private var requestFilter = "TODAS"
    private var pendingExport = ""
    private val exportRequestCode = 4501
    private val browserTabs = mutableListOf<String>()
    private var activeTabIndex = -1
    private var switchingTab = false
    private var scanEnabled = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestPollRunnable = object : Runnable {
        override fun run() {
            if (!browserMode || !scanEnabled || !::browser.isInitialized) return
            browser.evaluateJavascript(
                """(function(){
                    try {
                        var entries = performance.getEntriesByType('resource');
                        var cursor = window.__wdxResCursor || 0;
                        var resources = entries.slice(cursor).map(function(e){return e.name;});
                        window.__wdxResCursor = entries.length;
                        var seen = window.__wdxMediaSeen || (window.__wdxMediaSeen = {});
                        var media = Array.prototype.slice.call(document.querySelectorAll('video, audio, source')).map(function(e){
                            return e.currentSrc || e.src || e.getAttribute('src') || '';
                        }).filter(function(url){ if (!url || seen[url]) return false; seen[url] = true; return true; });
                        var net = window.__wdxNet || [];
                        var netCursor = window.__wdxNetCursor || 0;
                        var network = net.slice(netCursor);
                        window.__wdxNetCursor = net.length;
                        return JSON.stringify({resources:resources, media:media, network:network});
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
                if (browserMode) mainHandler.postDelayed(this, 2800)
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
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // Mantiene cookies de sesión y permite cookies de terceros cuando un reproductor las necesita.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        addRequest("JS ERROR ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                    }
                    return true
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Mensaje de la página").setMessage(message.orEmpty())
                        .setPositiveButton("Aceptar") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }.show()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Confirmar").setMessage(message.orEmpty())
                        .setPositiveButton("Aceptar") { _, _ -> result?.confirm() }
                        .setNegativeButton("Cancelar") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }.show()
                    return true
                }

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                    val input = EditText(this@MainActivity).apply { setText(defaultValue.orEmpty()) }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(message.orEmpty()).setView(input)
                        .setPositiveButton("Aceptar") { _, _ -> result?.confirm(input.text.toString()) }
                        .setNegativeButton("Cancelar") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }.show()
                    return true
                }
            }
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
                    if (request != null) {
                        val code = errorResponse?.statusCode ?: 0
                        val address = request.url.toString()
                        addRequest("HTTP $code ${request.method} ${if (isAntiBotUrl(address.lowercase())) "[ANTI-BOT]" else ""} $address")
                        if (request.isForMainFrame && code in listOf(403, 429, 503)) {
                            runOnUiThread {
                                statusText.text = when (code) {
                                    403 -> "Acceso rechazado (403) · puede ser Cloudflare o permisos del sitio"
                                    429 -> "Límite de solicitudes (429) · esperá antes de reintentar"
                                    else -> "Respuesta 503 · el sitio puede estar bajo protección o temporalmente caído"
                                }
                            }
                        }
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        addRequest("ERROR NAVEGACIÓN ${error?.errorCode}: ${error?.description} · ${request.url}")
                        runOnUiThread { statusText.text = "Error de navegación · ${error?.description ?: "sin detalles"}" }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                        urlInput.setText(url)
                        if (activeTabIndex in browserTabs.indices && !switchingTab) {
                            browserTabs[activeTabIndex] = url
                            saveBrowserTabs()
                        }
                        saveHistory(url)
                    }
                    switchingTab = false
                    statusText.text = "Navegación terminada · registrando recursos…"
                    // Diagnóstico visible de páginas de desafío; no intenta resolver ni eludir el control.
                    view?.evaluateJavascript(
                        """(function(){
                            var title = (document.title || '').toLowerCase();
                            var body = ((document.body && document.body.innerText) || '').slice(0,12000).toLowerCase();
                            var url = location.href.toLowerCase();
                            var signals = ['just a moment', 'checking your browser', 'verify you are human',
                                'performing security verification', 'cf-chl-', 'challenge-platform',
                                'attention required! | cloudflare', 'enable javascript and cookies to continue'];
                            var found = signals.some(function(s){ return title.indexOf(s) >= 0 || body.indexOf(s) >= 0 || url.indexOf(s) >= 0; });
                            return JSON.stringify({challenge:found,title:document.title || '',url:location.href});
                        })()"""
                    ) { value ->
                        runCatching {
                            val decoded = org.json.JSONTokener(value).nextValue() as? String ?: "{}"
                            val diagnostic = org.json.JSONObject(decoded)
                            if (diagnostic.optBoolean("challenge")) {
                                addRequest("CLOUDFLARE/ANTI-BOT DETECTADO · ${diagnostic.optString("title")} · ${diagnostic.optString("url")}")
                                runOnUiThread {
                                    statusText.text = "Posible desafío anti-bot detectado · revisá cookies/JavaScript o abrí el sitio en su navegador compatible"
                                    refreshRequestText()
                                }
                            }
                        }
                    }
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
            val browserControls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                fun control(label: String, action: () -> Unit) {
                    addView(Button(this@MainActivity).apply {
                        text = label
                        textSize = 10f
                        minWidth = dp(48)
                        setPadding(dp(2), 0, dp(2), 0)
                        setOnClickListener { action() }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                }
                control("◀") {
                    if (browser.canGoBack()) browser.goBack() else toast("No hay página anterior")
                }
                control("▶") {
                    if (browser.canGoForward()) browser.goForward() else toast("No hay página siguiente")
                }
                control("↻") {
                    if (browser.url != null) browser.reload() else toast("Abrí una página primero")
                }
                control("■") { browser.stopLoading(); statusText.text = "Carga detenida" }
                control("COMPARTIR") {
                    val current = browser.url ?: urlInput.text.toString()
                    if (current.isNotBlank()) {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, current)
                        }, "Compartir página"))
                    } else toast("No hay URL para compartir")
                }
                control("★") { toggleFavorite() }
                control("HIST.") { showSavedPages(false) }
                control("FAV.") { showSavedPages(true) }
                control("+ PESTAÑA") { newBrowserTab() }
                control("PESTAÑAS") { showBrowserTabs() }
                control("PAUSAR SCAN") { toggleScan() }
            }
            addView(browserControls)
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
        restoreRequestLog()
        restoreBrowserTabs()
        if (activeTabIndex in browserTabs.indices) {
            urlInput.setText(browserTabs[activeTabIndex])
            urlInput.hint = "URL de la pestaña " + (activeTabIndex + 1)
        }
        refreshRequestText()
    }

    private fun browserTabStore() = getSharedPreferences("webdata_tabs", Context.MODE_PRIVATE)

    private fun saveBrowserTabs() {
        val array = org.json.JSONArray()
        browserTabs.forEach { array.put(it) }
        browserTabStore().edit()
            .putString("tabs", array.toString())
            .putInt("active", activeTabIndex)
            .apply()
    }

    private fun restoreBrowserTabs() {
        runCatching {
            val raw = browserTabStore().getString("tabs", "[]") ?: "[]"
            val array = org.json.JSONArray(raw)
            browserTabs.clear()
            for (i in 0 until minOf(array.length(), 10)) browserTabs.add(array.optString(i))
            activeTabIndex = browserTabStore().getInt("active", -1)
            if (activeTabIndex !in browserTabs.indices) activeTabIndex = if (browserTabs.isEmpty()) -1 else 0
        }
    }

    private fun showBrowser() {
        browserMode = true
        resultScroll.visibility = View.GONE
        (browser.tag as? View)?.visibility = View.VISIBLE
        // El WebView estaba quedando en GONE aunque el panel padre se mostrara.
        browser.visibility = View.VISIBLE
        browser.isClickable = true
        browser.isFocusable = true
        browser.isFocusableInTouchMode = true
        browser.requestFocus()
        statusText.text = "Navegador integrado · solicitudes HTTP observadas"
        var raw = urlInput.text.toString().trim()

        // El botón NAVEGADOR debe abrir el navegador incluso si todavía no hay URL.
        if (raw.isBlank()) {
            if (activeTabIndex !in browserTabs.indices) {
                browserTabs.add("")
                activeTabIndex = browserTabs.lastIndex
                saveBrowserTabs()
            }
            urlInput.hint = "Buscar o escribir una dirección web"
            if (browser.url == null || browser.url == "about:blank") {
                switchingTab = true
                browser.loadUrl("about:blank")
                statusText.text = "Navegador listo · escribí una dirección arriba"
            }
            refreshRequestText()
            return
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            // Si no parece un dominio, convertirlo en una búsqueda web.
            val looksLikeHost = raw.contains(".") && !raw.contains(" ")
            raw = if (looksLikeHost) "https://$raw"
                else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(raw, "UTF-8")
        }
        val parsed = runCatching { URL(raw) }.getOrNull()
        if (parsed == null || (parsed.protocol != "http" && parsed.protocol != "https")) {
            toast("La URL no es válida"); return
        }
        urlInput.setText(raw)
        if (activeTabIndex !in browserTabs.indices) { browserTabs.add(raw); activeTabIndex = browserTabs.lastIndex }
        else browserTabs[activeTabIndex] = raw
        saveBrowserTabs()
        refreshRequestText()
        browser.loadUrl(raw)
    }

    private fun newBrowserTab() {
        if (browserTabs.size >= 10) { toast("Máximo 10 pestañas"); return }
        browserTabs.add("")
        activeTabIndex = browserTabs.lastIndex
        saveBrowserTabs()
        refreshRequestText()
        urlInput.setText("")
        urlInput.hint = "URL de la pestaña " + (activeTabIndex + 1)
        switchingTab = true
        browser.loadUrl("about:blank")
        statusText.text = "Pestaña " + (activeTabIndex + 1) + " creada"
    }

    private fun showBrowserTabs() {
        if (browserTabs.isEmpty()) { toast("No hay pestañas abiertas"); return }
        val labels = browserTabs.mapIndexed { index, url ->
            val title = if (url.isBlank()) "Pestaña vacía" else runCatching { URL(url).host + URL(url).path }.getOrDefault(url)
            (if (index == activeTabIndex) "● " else "") + (index + 1) + ". " + title.take(75)
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Pestañas abiertas (" + browserTabs.size + "/10)")
            .setItems(labels) { _, which -> switchBrowserTab(which) }
            .setPositiveButton("Nueva") { _, _ -> newBrowserTab() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun switchBrowserTab(index: Int) {
        if (index !in browserTabs.indices) return
        activeTabIndex = index
        val url = browserTabs[index]
        saveBrowserTabs()
        refreshRequestText()
        urlInput.setText(url)
        switchingTab = true
        browser.loadUrl(if (url.isBlank()) "about:blank" else url)
        statusText.text = "Pestaña " + (index + 1) + "/" + browserTabs.size
    }

    private fun showAnalyzer() {
        browserMode = false
        mainHandler.removeCallbacks(requestPollRunnable)
        (browser.tag as? View)?.visibility = View.GONE
        resultScroll.visibility = View.VISIBLE
        statusText.text = "Modo analizador HTML"
    }

    private fun toggleScan() {
        scanEnabled = !scanEnabled
        val controls = (browser.tag as? ViewGroup)?.getChildAt(0) as? ViewGroup
        if (controls != null) {
            for (i in 0 until controls.childCount) {
                val item = controls.getChildAt(i)
                if (item is Button && (item.text.toString() == "PAUSAR SCAN" || item.text.toString() == "ACTIVAR SCAN")) {
                    item.text = if (scanEnabled) "PAUSAR SCAN" else "ACTIVAR SCAN"
                }
            }
        }
        if (scanEnabled && browserMode) {
            statusText.text = "Escaneo activado · capturando solicitudes"
            mainHandler.removeCallbacks(requestPollRunnable)
            mainHandler.postDelayed(requestPollRunnable, 500)
        } else {
            mainHandler.removeCallbacks(requestPollRunnable)
            statusText.text = "Escaneo pausado · la navegación sigue activa"
        }
        toast(if (scanEnabled) "Escaneo activado" else "Escaneo pausado")
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

    private val saveRequestLogRunnable = Runnable { saveRequestLog() }
    private val refreshRequestTextRunnable = Runnable { if (::requestText.isInitialized) refreshRequestText() }

    private fun addRequest(line: String) {
        if (!scanEnabled) return
        var changed = false
        synchronized(requestLog) {
            // HashSet evita recorrer todo el historial por cada solicitud capturada.
            if (requestKeys.add(line)) {
                requestLog.add(line)
                if (requestLog.size > 500) {
                    val removed = requestLog.removeAt(0)
                    requestKeys.remove(removed)
                }
                changed = true
            }
        }
        if (changed) {
            // Agrupar persistencia y refresco de pantalla durante ráfagas de solicitudes.
            mainHandler.removeCallbacks(saveRequestLogRunnable)
            mainHandler.postDelayed(saveRequestLogRunnable, 1000)
            mainHandler.removeCallbacks(refreshRequestTextRunnable)
            mainHandler.postDelayed(refreshRequestTextRunnable, 400)
        }
    }

    private fun requestStore() = getSharedPreferences("webdata_scan", Context.MODE_PRIVATE)

    private fun saveRequestLog() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val array = org.json.JSONArray()
        snapshot.forEach { array.put(it) }
        requestStore().edit().putString("requests", array.toString()).apply()
    }

    private fun restoreRequestLog() {
        runCatching {
            val raw = requestStore().getString("requests", "[]") ?: "[]"
            val array = org.json.JSONArray(raw)
            synchronized(requestLog) {
                requestLog.clear()
                requestKeys.clear()
                for (i in 0 until array.length()) {
                    val item = array.optString(i)
                    if (requestKeys.add(item)) requestLog.add(item)
                }
                while (requestLog.size > 500) requestKeys.remove(requestLog.removeAt(0))
            }
        }
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

        val absoluteRegex = Regex("""https?://[^,\s"'<>\\]+""", RegexOption.IGNORE_CASE)
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

    private fun pageStore() = getSharedPreferences("webdata_pages", Context.MODE_PRIVATE)

    private fun readPages(key: String): MutableList<String> {
        val result = mutableListOf<String>()
        runCatching {
            val array = org.json.JSONArray(pageStore().getString(key, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val item = array.optString(i)
                if (item.startsWith("https://") || item.startsWith("http://")) result.add(item)
            }
        }
        return result
    }

    private fun writePages(key: String, pages: List<String>) {
        val array = org.json.JSONArray()
        pages.distinct().take(100).forEach { array.put(it) }
        pageStore().edit().putString(key, array.toString()).apply()
    }

    private fun saveHistory(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val pages = readPages("history").toMutableList()
        pages.remove(url)
        pages.add(0, url)
        writePages("history", pages)
    }

    private fun toggleFavorite() {
        val url = browser.url ?: urlInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Abrí una página primero")
            return
        }
        val pages = readPages("favorites")
        if (pages.contains(url)) {
            pages.remove(url)
            writePages("favorites", pages)
            toast("Favorito eliminado")
        } else {
            writePages("favorites", listOf(url) + pages)
            toast("Página guardada en favoritos")
        }
    }

    private fun showSavedPages(favorites: Boolean) {
        val pages = readPages(if (favorites) "favorites" else "history")
        val title = if (favorites) "Favoritos" else "Historial"
        if (pages.isEmpty()) {
            toast(if (favorites) "Todavía no hay favoritos" else "El historial está vacío")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(pages.map { it.take(90) }.toTypedArray()) { _, which ->
                val selected = pages.getOrNull(which) ?: return@setItems
                urlInput.setText(selected)
                showBrowser()
            }
            .setNeutralButton("Borrar lista") { _, _ ->
                writePages(if (favorites) "favorites" else "history", emptyList())
                toast("$title borrado")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        if (::browser.isInitialized) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(requestPollRunnable)
        if (::browser.isInitialized) CookieManager.getInstance().flush()
        executor.shutdownNow()
        browser.destroy()
        super.onDestroy()
    }
}
