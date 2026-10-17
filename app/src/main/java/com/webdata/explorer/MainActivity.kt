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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.ViewGroup
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.HttpURLConnection
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
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
    private val requestDetails = java.util.Collections.synchronizedMap(mutableMapOf<String, org.json.JSONObject>())
    private var lastReport = ""
    private var browserMode = false
    private var requestFilter = "TODAS"
    private var requestSearchQuery = ""
    private var pendingExport = ""
    private val exportRequestCode = 4501
    private val apkAnalyzerRequestCode = 4502
    private val browserTabs = mutableListOf<String>()
    private var activeTabIndex = -1
    private var switchingTab = false
    @Volatile private var scanEnabled = true
    @Volatile private var excludeNoisyResources = false
    @Volatile private var captureEventsReceived = 0
    @Volatile private var captureBridgeErrors = 0
    private val sessionLock = Any()
    private var currentSessionId = UUID.randomUUID().toString()
    private var currentSessionUrl = ""
    private var currentSessionStartedAt = System.currentTimeMillis()

    private val networkInstrumentationScript = """(function(){
 try {
  // Se instala en los frames donde WebView permita ejecutar el script.
  if (window.__wdxInstalled) return JSON.stringify({resources:performance.getEntriesByType('resource').map(function(e){return e.name;}),media:[],network:[]});
  window.__wdxInstalled=true; window.__wdxNet=[]; window.__wdxNetCursor=0;
  var LIMIT=12000;
  try { if(window.WebDataCapture && window.WebDataCapture.getBodyLimit) LIMIT=Number(window.WebDataCapture.getBodyLimit())||LIMIT; } catch(e) {}
  function redact(s) {
   s=String(s||'').slice(0,LIMIT);
   s=s.replace(/(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|session|jwt|credential|signature)(\s*[:=]\s*)([^\r\n,;&]+)/ig,'$1$2[REDACTADO]');
   s=s.replace(/("(?:password|passwd|token|access_token|refresh_token|api_key|secret|authorization|cookie|session|jwt|credential|signature)"\s*:\s*")[^"]*(")/ig,'$1[REDACTADO]$2');
   return s;
  }
  function headersText(headers) {
   var out=[];
   try { headers.forEach(function(v,k){ out.push(k+': '+(/^(authorization|proxy-authorization|cookie|set-cookie|x-api-key|.*token.*|.*secret.*|.*session.*|.*credential.*|.*signature.*|.*jwt.*)$/i.test(k)?'[REDACTADO]':v)); }); } catch(e) {}
   return out.join('\n').slice(0,LIMIT);
  }
  function readLimited(stream) {
   if (!stream || !stream.getReader) return Promise.resolve('');
   return new Promise(function(resolve) {
    var reader=stream.getReader(), chunks=[], total=0, decoder=new TextDecoder();
    function next() {
     reader.read().then(function(part) {
      if (part.done) { resolve(redact(chunks.join(''))); return; }
      var bytes=part.value, remain=LIMIT-total;
      if (remain>0) { var piece=bytes.length>remain?bytes.slice(0,remain):bytes; chunks.push(decoder.decode(piece,{stream:true})); total+=piece.length; }
      if (bytes.length>remain || total>=LIMIT) {
       try { reader.cancel(); } catch(e) {}
       chunks.push('\n[TRUNCADO: límite de 12 KB]');
       resolve(redact(chunks.join('')));
      } else next();
     }).catch(function(){ resolve(chunks.length?redact(chunks.join('')):'[No se pudo leer el cuerpo]'); });
    }
    next();
   });
  }
  function safeBody(body, contentType) {
   if (body==null) return Promise.resolve('');
   if (typeof body==='string') return Promise.resolve(redact(body));
   if (typeof URLSearchParams!=='undefined' && body instanceof URLSearchParams) return Promise.resolve(redact(body.toString()));
   if (typeof FormData!=='undefined' && body instanceof FormData) return Promise.resolve('[FormData omitido por seguridad; no se registran campos/archivos automáticamente]');
   return Promise.resolve('[Cuerpo no textual o no accesible]');
  }
  function record(item) {
   try {
    item.url=new URL(String(item.url||''),location.href).href;
    if (!/^https?:/i.test(item.url)) return;
    item.timestamp=new Date().toISOString();
    item.initiatorOrigin=item.initiatorOrigin||location.origin;
    item.durationMs=Number.isFinite(item.durationMs)?item.durationMs:-1;
    item.requestSize=Number.isFinite(item.requestSize)?item.requestSize:String(item.requestBody||'').length;
    item.responseSize=Number.isFinite(item.responseSize)?item.responseSize:String(item.responseBody||'').length;
    item.requestHeaders=redact(item.requestHeaders||'');
    item.requestBody=redact(item.requestBody||'');
    item.responseHeaders=redact(item.responseHeaders||'');
    item.responseBody=redact(item.responseBody||'');
    item.contentType=String(item.contentType||'');
    item.captureState=item.captureState||((item.responseBody||item.responseHeaders)?'PARCIAL':'LIMITADA');
    item.note=item.note||'';
    // El puente entrega los eventos de cada frame, incluidos los iframes accesibles.
    try {
     if (window.WebDataCapture && typeof window.WebDataCapture.postCapture==='function') {
      window.WebDataCapture.postCapture(JSON.stringify(item));
      return;
     }
    } catch(e) {}
    window.__wdxNet.push(item);
    if (window.__wdxNet.length>200) window.__wdxNet.shift();
   } catch(e) {}
  }
  function textResponse(clone, ct) {
   if (!/(json|text\/|xml|javascript|html)/i.test(ct||'')) return Promise.resolve({body:'',state:'PARCIAL',note:'Cuerpo omitido: respuesta binaria o tipo no textual.'});
   if (/^(video|audio|image)\//i.test(ct||'')) return Promise.resolve({body:'',state:'PARCIAL',note:'Cuerpo multimedia/binario omitido para evitar consumo de memoria.'});
   return readLimited(clone.body).then(function(body){
    var state=body.indexOf('[TRUNCADO:')>=0?'PARCIAL':'COMPLETA';
    if (!body) return {body:'',state:'PARCIAL',note:'El cuerpo estaba vacío o el navegador no permitió leerlo.'};
    return {body:body,state:state,note:state==='PARCIAL'?'Cuerpo limitado a 12 KB.':'Cuerpo textual leído desde una copia de la respuesta.'};
   });
  }
  if (window.fetch) {
   var originalFetch=window.fetch;
   window.fetch=function(input,init) {
    var url=input&&input.url?input.url:input;
    var method=(init&&init.method)||(input&&input.method)||'GET';
    var headers=(init&&init.headers)||(input&&input.headers), reqHeaders='';
    try { reqHeaders=headersText(new Headers(headers||{})); } catch(e) { reqHeaders='[Encabezados no accesibles]'; }
    var reqBodyPromise=Promise.resolve('');
    var body=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:null;
    if (body!==null && body!==undefined) { var reqCt=''; try { reqCt=new Headers(headers||{}).get('content-type')||''; } catch(e) {} reqBodyPromise=safeBody(body,reqCt); }
    else if (input&&input.clone&&input.body) { try { reqBodyPromise=readLimited(input.clone().body); } catch(e) { reqBodyPromise=Promise.resolve('[Cuerpo de Request no accesible]'); } }
    var started=Date.now();
    return originalFetch.apply(this,arguments).then(function(response) {
     var ct=response.headers.get('content-type')||'', clone;
     try { clone=response.clone(); } catch(e) { clone=null; }
     var responseBodyPromise=clone?textResponse(clone,ct):Promise.resolve({body:'',state:'PARCIAL',note:'No se pudo clonar la respuesta sin alterar la solicitud original.'});
     Promise.all([reqBodyPromise,responseBodyPromise]).then(function(values) {
      var rb=values[1];
      record({kind:'FETCH',method:String(method).toUpperCase(),url:url,responseUrl:response.url,redirected:response.redirected,status:response.status,requestHeaders:reqHeaders,requestBody:values[0],responseHeaders:headersText(response.headers),responseBody:rb.body,contentType:ct,captureState:rb.state,note:rb.note,durationMs:Date.now()-started,requestSize:String(values[0]||'').length,responseSize:String(rb.body||'').length});
     }).catch(function(){record({kind:'FETCH',method:String(method).toUpperCase(),url:url,status:response.status,requestHeaders:reqHeaders,requestBody:'',responseHeaders:headersText(response.headers),responseBody:'',contentType:ct,captureState:'PARCIAL',note:'No se pudo leer el cuerpo de la respuesta.'});});
     return response;
    },function(error) {
     reqBodyPromise.then(function(rb){record({kind:'FETCH_ERROR',method:String(method).toUpperCase(),url:url,status:0,requestHeaders:reqHeaders,requestBody:rb,responseHeaders:'',responseBody:'',contentType:'',captureState:'NO DISPONIBLE',note:'fetch falló: '+String(error)});});
     throw error;
    });
   };
  }
  var originalOpen=XMLHttpRequest.prototype.open, originalSend=XMLHttpRequest.prototype.send, originalSetHeader=XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open=function(method,url) {
   this.__wdxMethod=method; this.__wdxUrl=url; this.__wdxReqHeaders=[];
   return originalOpen.apply(this,arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader=function(name,value) {
   try { this.__wdxReqHeaders.push(name+': '+(/^(authorization|cookie|x-api-key|.*token.*|.*secret.*|.*session.*|.*credential.*|.*signature.*|.*jwt.*)$/i.test(name)?'[REDACTADO]':value)); } catch(e) {}
   return originalSetHeader.apply(this,arguments);
  };
  XMLHttpRequest.prototype.send=function(body) {
   var xhr=this, requestBody='', started=Date.now();
   try {
    if (typeof body==='string' || (typeof URLSearchParams!=='undefined'&&body instanceof URLSearchParams)) requestBody=redact(body.toString());
    else if (body!=null) requestBody='[Cuerpo no textual/FormData omitido por seguridad]';
   } catch(e) { requestBody='[Cuerpo enviado no accesible]'; }
   xhr.addEventListener('loadend',function() {
    var headers='',ct='',responseBody='',state='PARCIAL',note='';
    try { headers=xhr.getAllResponseHeaders()||''; } catch(e) {}
    try { ct=xhr.getResponseHeader('content-type')||''; } catch(e) {}
    try {
     if (xhr.responseType==='json') responseBody=JSON.stringify(xhr.response);
     else if (!xhr.responseType || xhr.responseType==='text') responseBody=xhr.responseText||'';
     else { note='Cuerpo omitido: responseType '+xhr.responseType+' (binario o no textual).'; }
     if (responseBody) {
      if (/(json|text\/|xml|javascript|html)/i.test(ct)) { responseBody=redact(responseBody); state=responseBody.length>=LIMIT?'PARCIAL':'COMPLETA'; if(state==='PARCIAL')responseBody=responseBody.slice(0,LIMIT)+'\n[TRUNCADO: límite de 12 KB]'; }
      else { responseBody=''; note='Cuerpo omitido: tipo de respuesta no textual.'; }
     }
    } catch(e) { responseBody=''; note='El navegador no permitió leer el cuerpo: '+String(e); }
    record({kind:'XHR',method:String(xhr.__wdxMethod||'GET').toUpperCase(),url:xhr.__wdxUrl||'',responseUrl:xhr.responseURL||xhr.__wdxUrl||'',redirected:!!(xhr.responseURL&&xhr.responseURL!==new URL(xhr.__wdxUrl,location.href).href),status:xhr.status||0,requestHeaders:redact((xhr.__wdxReqHeaders||[]).join('\n')),requestBody:requestBody,responseHeaders:redact(headers),responseBody:responseBody,contentType:ct,captureState:state,note:note||'Cuerpo capturado desde XMLHttpRequest cuando fue accesible.',durationMs:Date.now()-started,requestSize:String(requestBody||'').length,responseSize:String(responseBody||'').length});
   });
   return originalSend.apply(this,arguments);
  };
  var resources=performance.getEntriesByType('resource').map(function(e){return e.name;});
  var media=Array.prototype.slice.call(document.querySelectorAll('video,audio,source')).map(function(e){return e.currentSrc||e.src||e.getAttribute('src')||'';}).filter(Boolean);
  var net=window.__wdxNet||[]; window.__wdxNetCursor=net.length;
  return JSON.stringify({resources:resources,media:media,network:net});
 } catch(e) { return JSON.stringify({error:String(e)}); }
})()""".trimIndent()

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
                        val url = item.optString("url", "")
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            addDetailedRequest(
                                item.optString("kind", "FETCH"), item.optString("method", "GET"), url,
                                item.optInt("status", 0), item.optString("requestHeaders", ""),
                                item.optString("requestBody", ""), item.optString("responseHeaders", ""),
                                item.optString("responseBody", ""), item.optString("contentType", ""),
                                item.optString("captureState", "PARCIAL"), item.optString("note", "")
                            )
                        }
                    }
                }
                if (browserMode) mainHandler.postDelayed(this, 2800)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        excludeNoisyResources = getSharedPreferences("webdata_settings", Context.MODE_PRIVATE).getBoolean("exclude_noisy_resources", false)
        window.statusBarColor = Color.rgb(18, 24, 40)
        window.navigationBarColor = Color.rgb(18, 24, 40)
        window.decorView.setBackgroundColor(Color.rgb(12, 17, 29))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            setBackgroundColor(Color.rgb(12, 17, 29))
        }
        root.addView(TextView(this).apply {
            text = "🌐 WebData Explorer"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(5), dp(4), 0)
        })
        root.addView(TextView(this).apply {
            text = "Navegá, inspeccioná solicitudes y encontrá APIs y recursos multimedia."
            textSize = 13f
            setTextColor(Color.rgb(177, 190, 211))
            setPadding(dp(4), dp(5), dp(4), dp(14))
        })

        urlInput = EditText(this).apply {
            hint = "https://ejemplo.com"
            isSingleLine = true
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(27, 36, 54))
        }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                enterPressed) {
                if (browserMode) showBrowser() else analyze()
                true
            } else false
        }
        urlInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO

        root.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "🔎  ANALIZAR PÁGINA"
            textSize = 14f
            setOnClickListener { analyze() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val navButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navButtons.addView(Button(this).apply {
            text = "🌐  NAVEGADOR"
            setOnClickListener { showBrowser() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navButtons.addView(Button(this).apply {
            text = "📊  ANALIZADOR"
            setOnClickListener { showAnalyzer() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(navButtons)

        root.addView(Button(this).apply {
            text = "🧰  HERRAMIENTAS Y ANÁLISIS  ▾"
            textSize = 13f
            setOnClickListener {
                val tools = arrayOf(
                    "🔌  APIs detectadas",
                    "🎬  Recursos multimedia",
                    "🗂️  Sesiones guardadas",
                    "🕘  Historial de páginas",
                    "⭐  Favoritos",
                    "🩺  Diagnóstico del navegador",
                    "🌐  Pruebas HTTP directas GET/POST",
                    "🧪  Probar captura del navegador (WebView)",
                    "📄  Exportar solicitudes CSV",
                    "⚙️  Ajustes de captura",
                    "🔬  Analizador de APK (DEX, URLs y APIs)"
                )
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Herramientas")
                    .setItems(tools) { _, which ->
                        when (which) {
                            0 -> showApiEndpoints()
                            1 -> showMediaRequests()
                            2 -> showSavedSessions()
                            3 -> showSavedPages(false)
                            4 -> showSavedPages(true)
                            5 -> showDiagnostics()
                            6 -> showHttpTests()
                            7 -> runCaptureTest()
                            8 -> exportRequestsCsv()
                            9 -> showSettings()
                            10 -> chooseApkForAnalysis()
                        }
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) })

        val actionButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionButtons.addView(Button(this).apply {
            text = "📋 COPIAR"
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
            text = "⬇ EXPORTAR JSON"
            setOnClickListener { exportRequests() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionButtons)

        root.addView(TextView(this).apply {
            text = "ESTADO"
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(130, 160, 204))
            setPadding(dp(3), dp(8), 0, 0)
        })

        statusText = TextView(this).apply {
            text = "Listo para analizar una URL pública."
            textSize = 13f
            setTextColor(Color.rgb(145, 224, 190))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(20, 39, 43))
                setStroke(dp(1), Color.rgb(39, 82, 76))
            }
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
            // Recibe capturas de fetch/XHR desde el documento y los frames donde corre el script.
            addJavascriptInterface(WebDataCaptureBridge(), "WebDataCapture")
            // Instrumenta fetch/XHR al inicio del documento para no perder las API llamadas durante la carga.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching { WebViewCompat.addDocumentStartJavaScript(this, networkInstrumentationScript, setOf("*")) }
                    .onFailure { addRequest("INSTRUMENTACIÓN TEMPRANA NO DISPONIBLE · ${it.javaClass.simpleName}") }
            }
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                        startCaptureSession(url)
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request != null) {
                        val url = request.url.toString()
                        if (url.startsWith("https://webdata.test/__wdx_test_api/")) {
                            // Endpoint local controlado para comprobar GET/POST y JSON sin depender de Internet.
                            val payload = org.json.JSONObject()
                                .put("test", true)
                                .put("method", request.method.uppercase())
                                .put("path", request.url.path)
                                .put("message", "Respuesta JSON local de prueba")
                                .put("knownValue", 42)
                                .toString()
                            val headers = mapOf(
                                "Content-Type" to "application/json; charset=utf-8",
                                "Cache-Control" to "no-store",
                                "Access-Control-Allow-Origin" to "*"
                            )
                            return WebResourceResponse(
                                "application/json", "UTF-8", 200, "OK",
                                headers, ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
                            )
                        }
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            // Cada navegación principal inicia un registro nuevo: no mezclar
                            // las solicitudes de la página anterior con la página actual.
                            val kind = classifyRequest(url)
                            val reqHeaders = request.requestHeaders.entries.joinToString("\n") { it.key + ": " + it.value }
                            addDetailedRequest(
                                "WEBVIEW", request.method, url, 0, reqHeaders, "",
                                "", "", "", "PARCIAL",
                                "URL y encabezados de solicitud disponibles en shouldInterceptRequest. WebView no expone aquí el cuerpo enviado ni las respuestas exitosas."
                            )
                        }
                    }
                    return null
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request != null) {
                        val code = errorResponse?.statusCode ?: 0
                        val address = request.url.toString()
                        val reqHeaders = request.requestHeaders.entries.joinToString("\n") { it.key + ": " + it.value }
                        val respHeaders = errorResponse?.responseHeaders?.entries?.joinToString("\n") { it.key + ": " + it.value }.orEmpty()
                        addDetailedRequest(
                            "WEBVIEW_HTTP_ERROR", request.method, address, code, reqHeaders, "",
                            respHeaders, "", errorResponse?.mimeType.orEmpty(), "PARCIAL",
                            "WebView notificó un error HTTP y expuso estado/encabezados. El cuerpo de la respuesta no está disponible en onReceivedHttpError."
                        )
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
                    view?.evaluateJavascript(networkInstrumentationScript) { value ->
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
                                val url = item.optString("url", "")
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    addDetailedRequest(
                                        item.optString("kind", "FETCH"), item.optString("method", "GET"), url,
                                        item.optInt("status", 0), item.optString("requestHeaders", ""),
                                        item.optString("requestBody", ""), item.optString("responseHeaders", ""),
                                        item.optString("responseBody", ""), item.optString("contentType", ""),
                                        item.optString("captureState", "PARCIAL"), item.optString("note", "")
                                    )
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
            setTextIsSelectable(false)
            movementMethod = LinkMovementMethod.getInstance()
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
                control("↗ Compartir") {
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
                control("DIAGNÓSTICO") { showDiagnostics() }
                control("PRUEBA CAPTURA") { runCaptureTest() }
                control("EXPORTAR CSV") { exportRequestsCsv() }
                control("API") { showApiEndpoints() }
                control("MULTIMEDIA") { showMediaRequests() }
                control("SESIONES") { showSavedSessions() }
                control("AJUSTES") { showSettings() }
            }
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(browserControls)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            var requestsExpanded = false
            var requestListScroll: ScrollView? = null
            var requestFiltersScroll: HorizontalScrollView? = null
            val requestsToggle = Button(this@MainActivity).apply {
                text = "▴  MOSTRAR SOLICITUDES DE RED"
                textSize = 11f
                setOnClickListener {
                    requestsExpanded = !requestsExpanded
                    text = if (requestsExpanded) "▾  OCULTAR SOLICITUDES DE RED" else "▴  MOSTRAR SOLICITUDES DE RED"
                    requestListScroll?.visibility = if (requestsExpanded) View.VISIBLE else View.GONE
                    requestFiltersScroll?.visibility = if (requestsExpanded) View.VISIBLE else View.GONE
                    requestListScroll?.layoutParams = (requestListScroll?.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        height = if (requestsExpanded) dp(190) else 0
                    }
                }
            }
            addView(requestsToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            val filters = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            filters.addView(Button(this@MainActivity).apply {
                text = "🔎 BUSCAR TEXTO"
                setOnClickListener { showRequestSearchDialog() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            filters.addView(Button(this@MainActivity).apply {
                text = "LIMPIAR"
                setOnClickListener {
                    requestSearchQuery = ""
                    requestFilter = "TODAS"
                    refreshRequestText()
                    toast("Filtros limpiados")
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            listOf("TODAS", "API", "VIDEO", "JS", "ERRORES", "ANTI-BOT").forEach { filter ->
                filters.addView(Button(this@MainActivity).apply {
                    text = filter
                    textSize = 9f
                    minWidth = dp(64)
                    setPadding(dp(5), 0, dp(5), 0)
                    setOnClickListener { requestFilter = filter; refreshRequestText() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            }
            requestFiltersScroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE
                addView(filters)
            }
            addView(requestFiltersScroll)
            requestListScroll = ScrollView(this@MainActivity).apply {
                visibility = View.GONE
                addView(requestText)
            }
            addView(requestListScroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            ))
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
        styleInterface(root)
        browser.tag = browserPanel
        restoreRequestLog()
        restoreBrowserTabs()
        if (activeTabIndex in browserTabs.indices) {
            urlInput.setText(browserTabs[activeTabIndex])
            urlInput.hint = "URL de la pestaña " + (activeTabIndex + 1)
        }
        refreshRequestText()
    }

    private fun styleInterface(view: View) {
        if (view is Button) {
            val label = view.text?.toString().orEmpty()
            val primary = label.contains("ANALIZAR PÁGINA") || label.contains("PRUEBA CAPTURA")
            val bg = if (primary) Color.rgb(48, 111, 220) else Color.rgb(34, 47, 68)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(bg)
                setStroke(dp(1), if (primary) Color.rgb(80, 145, 245) else Color.rgb(57, 73, 98))
            }
            view.setTextColor(Color.WHITE)
            view.textSize = if (view.textSize > 12f) 13f else 11f
            view.isAllCaps = false
            view.minHeight = dp(42)
            view.setPadding(dp(10), dp(5), dp(10), dp(5))
            view.stateListAnimator = null
        } else if (view is EditText) {
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(27, 36, 54))
                setStroke(dp(1), Color.rgb(66, 88, 122))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) styleInterface(view.getChildAt(i))
        }
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
        val safeLine = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).replace(line) { sanitizeUrlForStorage(it.value) }
        if (excludeNoisyResources && shouldExcludeNoisyResource(safeLine)) return
        var changed = false
        synchronized(requestLog) {
            // HashSet evita recorrer todo el historial por cada solicitud capturada.
            if (requestKeys.add(safeLine)) {
                requestLog.add(safeLine)
                if (requestLog.size > 500) {
                    val removed = requestLog.removeAt(0)
                    requestKeys.remove(removed)
                    requestDetails.remove(removed)
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

    private fun startCaptureSession(url: String) {
        saveCurrentSession()
        clearRequestLogForNewPage()
        currentSessionId = UUID.randomUUID().toString()
        currentSessionUrl = sanitizeUrlForStorage(url)
        currentSessionStartedAt = System.currentTimeMillis()
    }

    private fun sessionDirectory(): File = File(filesDir, "webdata_sessions").apply { mkdirs() }

    private fun saveCurrentSession() {
        val snapshot = synchronized(requestLog) { requestLog.toList().takeLast(300) }
        if (snapshot.isEmpty()) return
        val detailsArray = org.json.JSONArray()
        synchronized(requestDetails) {
            snapshot.forEach { line ->
                val item = org.json.JSONObject().put("line", line)
                requestDetails[line]?.let { item.put("detail", org.json.JSONObject(it.toString())) }
                detailsArray.put(item)
            }
        }
        val session = org.json.JSONObject()
            .put("id", currentSessionId)
            .put("name", runCatching { URI(currentSessionUrl).host }.getOrNull().orEmpty().ifBlank { "Análisis web" })
            .put("url", currentSessionUrl)
            .put("startedAt", currentSessionStartedAt)
            .put("savedAt", System.currentTimeMillis())
            .put("requestCount", snapshot.size)
            .put("requests", detailsArray)
        synchronized(sessionLock) {
            runCatching {
                val target = File(sessionDirectory(), "$currentSessionId.json")
                val temp = File(sessionDirectory(), "$currentSessionId.tmp")
                temp.writeText(session.toString(), Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    target.writeText(session.toString(), Charsets.UTF_8)
                    temp.delete()
                }
                sessionDirectory().listFiles { f -> f.isFile && f.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(20)?.forEach { it.delete() }
            }
        }
    }

    private fun showSavedSessions() {
        val files = synchronized(sessionLock) {
            sessionDirectory().listFiles { f -> f.isFile && f.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.toList().orEmpty()
        }
        if (files.isEmpty()) {
            toast("Todavía no hay sesiones guardadas. Navegá y luego cambiá de página o cerrá la app.")
            return
        }
        val sessions = files.mapNotNull { file ->
            runCatching { file to org.json.JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Sesiones guardadas (${sessions.size})")
            .setItems(sessions.map { (_, item) ->
                val date = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.optLong("startedAt")))
                "$date · ${item.optString("name")} · ${item.optInt("requestCount")} solicitudes"
            }.toTypedArray()) { _, which ->
                val selected = sessions.getOrNull(which) ?: return@setItems
                showSavedSessionRequests(selected.first, selected.second)
            }
            .setNeutralButton("BORRAR HISTORIAL") { _, _ ->
                synchronized(sessionLock) { files.forEach { it.delete() } }
                toast("Sesiones guardadas eliminadas")
            }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun showSavedSessionRequests(file: File, session: org.json.JSONObject) {
        val requests = session.optJSONArray("requests") ?: org.json.JSONArray()
        val entries = (0 until requests.length()).mapNotNull { index ->
            requests.optJSONObject(index)?.let { it.optString("line") to it.optJSONObject("detail") }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(session.optString("name", "Sesión") + " · ${entries.size} solicitudes")
            .setItems(entries.map { it.first.take(220) }.toTypedArray()) { _, which ->
                val entry = entries.getOrNull(which) ?: return@setItems
                val detail = entry.second
                if (detail != null) showRequestDetails(entry.first, detail)
                else toast("En esta solicitud solo se guardó la URL observada")
            }
            .setNeutralButton("EXPORTAR JSON") { _, _ ->
                pendingExport = session.toString(2)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "webdata-sesion-${file.nameWithoutExtension.take(12)}.json")
                }
                startActivityForResult(intent, exportRequestCode)
            }
            .setNegativeButton("VOLVER", null)
            .show()
    }

    private fun clearRequestLogForNewPage() {
        mainHandler.removeCallbacks(saveRequestLogRunnable)
        mainHandler.removeCallbacks(refreshRequestTextRunnable)
        synchronized(requestLog) {
            requestLog.clear()
            requestKeys.clear()
        }
        // También borrar el registro persistido para que al reabrir la app
        // no reaparezcan solicitudes pertenecientes a páginas anteriores.
        requestDetails.clear()
        requestStore().edit().putString("requests", "[]").putString("details", "[]").apply()
        if (::requestText.isInitialized) {
            mainHandler.post { refreshRequestText() }
        }
    }

    private fun requestStore() = getSharedPreferences("webdata_scan", Context.MODE_PRIVATE)

    private fun saveRequestLog() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val array = org.json.JSONArray()
        snapshot.forEach { array.put(it) }
        val detailsArray = org.json.JSONArray()
        synchronized(requestDetails) {
            snapshot.forEach { line -> requestDetails[line]?.let { detailsArray.put(org.json.JSONObject().put("line", line).put("detail", it)) } }
        }
        requestStore().edit().putString("requests", array.toString()).putString("details", detailsArray.toString()).apply()
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
            requestDetails.clear()
            val detailArray = org.json.JSONArray(requestStore().getString("details", "[]") ?: "[]")
            for (i in 0 until detailArray.length()) {
                val item = detailArray.optJSONObject(i) ?: continue
                val line = item.optString("line")
                val detail = item.optJSONObject("detail")
                if (line.isNotBlank() && detail != null) requestDetails[line] = detail
            }
        }
    }

    private fun matchesFilter(line: String): Boolean {
        val lower = line.lowercase()
        if (requestSearchQuery.isNotBlank() && !lower.contains(requestSearchQuery.lowercase())) return false
        return when (requestFilter) {
            "API" -> lower.contains("[api?]") || lower.contains("/api/") || lower.contains("graphql") || lower.contains("/ajax/")
            "VIDEO" -> lower.contains("[medio?]") || listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".ts").any { lower.substringBefore("?").contains(it) }
            "JS" -> lower.contains("[js]") || lower.substringBefore("?").endsWith(".js")
            "ERRORES" -> lower.startsWith("http ") || lower.contains(" error ") || lower.contains("failed") || Regex("""http\s+[45]\d\d""").containsMatchIn(lower)
            "ANTI-BOT" -> isAntiBotUrl(lower)
            else -> true
        }
    }

    private fun showRequestSearchDialog() {
        val input = EditText(this).apply {
            hint = "URL, dominio, método, código HTTP…"
            setSingleLine(true)
            setText(requestSearchQuery)
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Buscar en solicitudes")
            .setMessage("Busca coincidencias en las líneas registradas. No vuelve a consultar el sitio.")
            .setView(input)
            .setPositiveButton("Buscar") { _, _ ->
                requestSearchQuery = input.text.toString().trim()
                refreshRequestText()
                toast(if (requestSearchQuery.isBlank()) "Búsqueda vacía" else "Filtro aplicado")
            }
            .setNeutralButton("Limpiar") { _, _ ->
                requestSearchQuery = ""
                refreshRequestText()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refreshRequestText() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val filtered = snapshot.filter { matchesFilter(it) }.asReversed().take(300)
        if (snapshot.isEmpty()) {
            requestText.text = "Todavía no se observaron solicitudes. Abrí una página o recargala."
            return
        }
        val searchLabel = if (requestSearchQuery.isBlank()) "" else " · búsqueda: \"$requestSearchQuery\""
        val out = SpannableStringBuilder("Filtro: $requestFilter$searchLabel · ${filtered.size}/${snapshot.size} solicitudes (máximo 300)\n\n")
        filtered.forEachIndexed { index, line ->
            val start = out.length
            out.append(displayRequestLine(line))
            val end = out.length
            if (requestDetails.containsKey(line)) {
                out.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = showRequestDetails(line)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Color.rgb(120, 190, 255)
                        ds.isUnderlineText = true
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (index != filtered.lastIndex) out.append("\n\n")
        }
        requestText.text = out
    }

    private fun sanitizeUrlForStorage(raw: String): String {
        return raw.replace(
            Regex("(?i)([?&](?:access_token|refresh_token|token|auth|authorization|api_key|apikey|signature|sig|jwt|session|password|credential)=)[^&#\\s]+")
        ) { it.groupValues[1] + "[REDACTADO]" }
    }

    private fun classifyResource(url: String, contentType: String): String {
        val path = url.substringBefore("?").substringBefore("#").lowercase()
        val type = contentType.substringBefore(";").lowercase()
        return when {
            path.endsWith(".m3u8") || type.contains("mpegurl") -> "MANIFIESTO HLS"
            path.endsWith(".mpd") || type.contains("dash+xml") -> "MANIFIESTO MPEG-DASH"
            listOf(".ts", ".m4s", ".cmfv", ".cmfa", ".aac", ".m4a").any { path.endsWith(it) } || path.contains("/segment") || path.contains("/chunk") -> "SEGMENTO MULTIMEDIA"
            type.startsWith("video/") || type.startsWith("audio/") || listOf(".mp4", ".webm", ".mov", ".m4v", ".mp3", ".ogg", ".wav").any { path.endsWith(it) } -> "AUDIO/VIDEO"
            type.startsWith("image/") || listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif").any { path.endsWith(it) } -> "IMAGEN"
            type.contains("font") || listOf(".woff", ".woff2", ".ttf", ".otf").any { path.endsWith(it) } -> "FUENTE"
            type.contains("javascript") || path.endsWith(".js") -> "JAVASCRIPT"
            type.contains("css") || path.endsWith(".css") -> "CSS"
            type.contains("html") || path.endsWith(".html") -> "HTML"
            type.contains("json") || path.contains("/api/") || path.contains("graphql") -> "API/JSON"
            type.startsWith("text/") || type.contains("xml") -> "TEXTO/XML"
            else -> "OTRO"
        }
    }

    private fun classifyContentType(raw: String): String {
        val type = raw.substringBefore(";").trim().lowercase()
        return when {
            type.contains("json") -> "JSON"
            type.contains("xml") -> "XML"
            type.contains("html") -> "HTML"
            type.startsWith("text/") || type.contains("javascript") -> "TEXTO"
            type.startsWith("image/") -> "IMAGEN"
            type.startsWith("video/") || type.startsWith("audio/") || type.contains("mpegurl") || type.contains("dash+xml") -> "MULTIMEDIA"
            type.isBlank() -> "DESCONOCIDO"
            else -> "BINARIO/OTRO"
        }
    }

    private fun cleanSensitiveText(raw: String): String {
        var value = raw
        value = value.replace(Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|session|jwt|credential|signature)(\\s*[:=]\\s*)([^\\r\\n,;&]+)")) { m ->
            m.groupValues[1] + m.groupValues[2] + "[REDACTADO]"
        }
        value = value.replace(Regex("(?i)(\\\"(?:password|passwd|token|access_token|refresh_token|api_key|secret|authorization|cookie|session|jwt|credential|signature)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"), "$1[REDACTADO]$2")
        return value
    }

    private inner class WebDataCaptureBridge {
        @JavascriptInterface
        fun getBodyLimit(): Int = getSharedPreferences("webdata_settings", Context.MODE_PRIVATE).getInt("body_limit", 12000).coerceIn(4096, 24576)

        @JavascriptInterface
        fun getTestStatus(runId: String?): String {
            val runMarker = "run=" + (runId ?: "")
            val details = synchronized(requestDetails) { requestDetails.values.map { org.json.JSONObject(it.toString()) } }
            val getOk = details.any {
                it.optString("url").contains("/__wdx_test_api/get") &&
                    it.optString("url").contains(runMarker) && it.optInt("status") == 200 &&
                    it.optString("responseBody").contains("knownValue")
            }
            val postOk = details.any {
                it.optString("url").contains("/__wdx_test_api/post") &&
                    it.optString("url").contains(runMarker) && it.optString("method") == "POST" &&
                    it.optInt("status") == 200
            }
            val postBody = details.any {
                it.optString("url").contains("/__wdx_test_api/post") &&
                    it.optString("url").contains(runMarker) &&
                    it.optString("requestBody").contains("POST_BODY_CAPTURED")
            }
            return org.json.JSONObject().put("getCaptured", getOk).put("postCaptured", postOk)
                .put("postBodyCaptured", postBody).put("captureEvents", captureEventsReceived)
                .put("bridgeErrors", captureBridgeErrors).toString()
        }

        @JavascriptInterface
        fun postCapture(payload: String?) {
            if (!scanEnabled || payload.isNullOrBlank()) return
            if (payload.length > 48_000) {
                captureBridgeErrors++
                return
            }
            try {
                val item = org.json.JSONObject(payload)
                val url = item.optString("url", "")
                if (!url.startsWith("https://") && !url.startsWith("http://")) return
                captureEventsReceived++
                addDetailedRequest(
                    item.optString("kind", "FETCH/XHR"),
                    item.optString("method", "GET"),
                    url,
                    item.optInt("status", 0),
                    item.optString("requestHeaders", ""),
                    item.optString("requestBody", ""),
                    item.optString("responseHeaders", ""),
                    item.optString("responseBody", ""),
                    item.optString("contentType", ""),
                    item.optString("captureState", "PARCIAL"),
                    item.optString("note", "Captura recibida desde instrumentación JavaScript."),
                    item.optLong("durationMs", -1L),
                    item.optInt("requestSize", -1),
                    item.optInt("responseSize", -1),
                    item.optString("initiatorOrigin", ""),
                    item.optString("responseUrl", ""),
                    item.optBoolean("redirected", false)
                )
            } catch (_: Exception) {
                captureBridgeErrors++
            }
        }
    }

    private fun addDetailedRequest(
        kind: String, method: String, url: String, status: Int,
        requestHeaders: String, requestBody: String,
        responseHeaders: String, responseBody: String,
        contentType: String, captureState: String, note: String,
        durationMs: Long = -1L, requestSize: Int = -1, responseSize: Int = -1,
        initiatorOrigin: String = "", responseUrl: String = "", redirected: Boolean = false
    ) {
        if (!scanEnabled) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (excludeNoisyResources && shouldExcludeNoisyResource(url)) return
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val safeUrl = sanitizeUrlForStorage(url)
        val statusLabel = if (status > 0) "HTTP $status" else "HTTP ?"
        val line = "$stamp · ${method.uppercase()} · $statusLabel · [$kind/${classifyRequest(safeUrl)}] $safeUrl"
        val detail = org.json.JSONObject()
            .put("timestamp", stamp).put("url", safeUrl).put("method", method.uppercase())
            .put("status", status).put("kind", kind).put("contentType", contentType)
            .put("bodyType", classifyContentType(contentType))
            .put("durationMs", durationMs)
            .put("requestSizeCaptured", requestSize)
            .put("responseSizeCaptured", responseSize)
            .put("initiatorOrigin", sanitizeUrlForStorage(initiatorOrigin))
            .put("responseUrl", sanitizeUrlForStorage(responseUrl))
            .put("redirected", redirected)
            .put("resourceType", classifyResource(url, contentType))
            .put("requestHeaders", cleanSensitiveText(requestHeaders).take(12000))
             .put("requestBody", cleanSensitiveText(requestBody).take(32000))
            .put("responseHeaders", cleanSensitiveText(responseHeaders).take(12000))
            .put("responseBody", cleanSensitiveText(responseBody).take(32000))
            .put("captureState", captureState).put("note", note)
        synchronized(requestDetails) {
            requestDetails[line] = detail
            while (requestDetails.size > 40) {
                val oldest = requestDetails.keys.firstOrNull() ?: break
                requestDetails.remove(oldest)
            }
        }
        addRequest(line)
    }

    private fun showRequestDetails(line: String, detailOverride: org.json.JSONObject? = null) {
        val detail = detailOverride ?: requestDetails[line] ?: run {
            toast("Esta solicitud solo tiene URL; WebView no expuso más datos")
            return
        }
        val labels = arrayOf("Resumen", "Solicitud", "Encabezados enviados", "Cuerpo enviado", "Encabezados recibidos", "Cuerpo recibido", "Errores/diagnóstico")
        val keys = arrayOf("", "", "requestHeaders", "requestBody", "responseHeaders", "responseBody", "note")
        var selected = 0
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val tabs = HorizontalScrollView(this)
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val content = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val scroll = ScrollView(this).apply { addView(content) }
        fun sectionText(index: Int): String {
            if (index == 0) return buildString {
                append("URL: ").append(detail.optString("url")).append("\n")
                append("Método: ").append(detail.optString("method")).append("\n")
                append("Fecha: ").append(detail.optString("timestamp")).append("\n")
                append("Estado HTTP: ").append(if (detail.optInt("status") > 0) detail.optInt("status") else "No disponible").append("\n")
                append("Tipo: ").append(detail.optString("contentType", "desconocido")).append("\n")
                append("Formato: ").append(detail.optString("bodyType", "DESCONOCIDO")).append("\n")
                append("Duración: ").append(if (detail.optLong("durationMs", -1L) >= 0) "${detail.optLong("durationMs")} ms" else "No disponible").append("\n")
                append("Tamaño enviado capturado: ").append(if (detail.optInt("requestSizeCaptured", -1) >= 0) "${detail.optInt("requestSizeCaptured")} caracteres" else "No disponible").append("\n")
                append("Tamaño recibido capturado: ").append(if (detail.optInt("responseSizeCaptured", -1) >= 0) "${detail.optInt("responseSizeCaptured")} caracteres" else "No disponible").append("\n")
                append("Origen iniciador: ").append(detail.optString("initiatorOrigin").ifBlank { "No disponible" }).append("\n")
                append("Categoría: ").append(detail.optString("resourceType", "No clasificada")).append("\n")
                if (detail.optBoolean("redirected")) append("Redirección observada a: ").append(detail.optString("responseUrl")).append("\n")
                append("Captura: ").append(detail.optString("captureState", "parcial")).append("\n")
                append(detail.optString("note", ""))
            }
            if (index == 1) return buildString {
                append(detail.optString("method")).append(" ").append(detail.optString("url")).append("\n")
                append("Hora: ").append(detail.optString("timestamp")).append("\n")
                append("Estado HTTP: ").append(if (detail.optInt("status") > 0) detail.optInt("status") else "No disponible").append("\n")
                append("Origen iniciador: ").append(detail.optString("initiatorOrigin").ifBlank { "No disponible" }).append("\n")
                append("Duración: ").append(if (detail.optLong("durationMs", -1L) >= 0) "${detail.optLong("durationMs")} ms" else "No disponible")
            }
            val raw = detail.optString(keys[index], "")
            if (raw.isNotBlank()) {
                if (index == 5 && (detail.optString("bodyType") == "JSON" || raw.trimStart().startsWith("{") || raw.trimStart().startsWith("["))) {
                    return runCatching {
                        if (raw.trimStart().startsWith("[")) org.json.JSONArray(raw).toString(2)
                        else org.json.JSONObject(raw).toString(2)
                    }.getOrDefault(raw)
                }
                return raw
            }
            return when (index) {
                2 -> "Encabezados de solicitud no disponibles: WebView no los expuso para este tipo de recurso."
                3 -> if (detail.optString("method") in listOf("GET", "HEAD") && detail.optString("kind") == "WEBVIEW") "No se observó cuerpo enviado. GET/HEAD normalmente no envían cuerpo; shouldInterceptRequest no expone el cuerpo original." else "Cuerpo enviado no disponible. WebView no ofrece el cuerpo de solicitudes de recursos en shouldInterceptRequest; para JavaScript solo se capturan cuerpos de texto accesibles."
                4 -> "Encabezados de respuesta no disponibles para esta solicitud."
                5 -> "Cuerpo recibido no disponible. WebView no expone directamente el cuerpo de respuestas desde shouldInterceptRequest; solo se pueden leer algunas respuestas fetch/XHR accesibles."
                6 -> "No se registraron errores específicos. Consultá el estado de captura y la nota de la sección Resumen."
                else -> "No hay datos disponibles para esta sección."
            }
        }
        fun updateSection() { content.text = sectionText(selected) }
        labels.forEachIndexed { index, label ->
            tabRow.addView(Button(this).apply {
                text = label
                textSize = 10f
                setOnClickListener { selected = index; updateSection() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        tabs.addView(tabRow)
        layout.addView(tabs)
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val searchInput = EditText(this).apply {
            hint = "Buscar en esta sección"
            textSize = 13f
            setSingleLine(true)
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchRow.addView(Button(this).apply {
            text = "BUSCAR"
            setOnClickListener {
                val query = searchInput.text.toString()
                val raw = sectionText(selected)
                val found = if (query.isBlank()) -1 else raw.indexOf(query, ignoreCase = true)
                if (found < 0) {
                    toast(if (query.isBlank()) "Escribí un texto para buscar" else "No se encontró ese texto en esta sección")
                } else {
                    val highlighted = SpannableStringBuilder(raw)
                    highlighted.setSpan(BackgroundColorSpan(Color.YELLOW), found, found + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    content.text = highlighted
                    scroll.post {
                        val line = content.layout?.getLineForOffset(found) ?: 0
                        scroll.smoothScrollTo(0, content.layout?.getLineTop(line) ?: 0)
                    }
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        layout.addView(searchRow)
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "COPIAR SECCIÓN"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(labels[selected], sectionText(selected)))
                toast("Sección copiada")
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(Button(this).apply {
            text = "GUARDAR JSON"
            setOnClickListener {
                pendingExport = detail.toString(2)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "webdata-detalle-solicitud.json")
                }
                startActivityForResult(intent, exportRequestCode)
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(Button(this).apply {
            text = "COMPARAR"
            setOnClickListener { showResponseComparison(line, detail) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        layout.addView(actions)
        updateSection()
        android.app.AlertDialog.Builder(this).setTitle("Detalle de solicitud").setView(layout).setPositiveButton("Cerrar", null).show()
    }
    private fun showApiEndpoints() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val endpoints = mutableMapOf<String, MutableList<Pair<String, org.json.JSONObject?>>>()
        var ignoredNoise = 0

        snapshot.forEach { line ->
            val detail = requestDetails[line]
            val url = detail?.optString("url").orEmpty().ifBlank {
                Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).find(line)?.value.orEmpty()
            }
            if (url.isBlank()) return@forEach

            val uri = runCatching { URI(url) }.getOrNull()
            val host = uri?.host.orEmpty()
            val path = uri?.path.orEmpty().ifBlank { "/" }
            val lowerPath = path.lowercase()
            val query = uri?.rawQuery.orEmpty().lowercase()
            val method = detail?.optString("method").orEmpty()
                .ifBlank { line.substringAfter("· ").substringBefore(" ·") }
                .uppercase()
            val kind = detail?.optString("kind").orEmpty().uppercase()
            val contentType = detail?.optString("contentType").orEmpty().lowercase()
            val body = detail?.optString("responseBody").orEmpty().trimStart()
            val requestBody = detail?.optString("requestBody").orEmpty().trimStart()

            // No clasificar archivos estáticos como API aunque hayan sido pedidos con fetch/XHR.
            val extension = lowerPath.substringAfterLast('.', "")
            val staticExtensions = setOf(
                "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico",
                "woff", "woff2", "ttf", "otf", "eot", "mp4", "m4v", "webm", "mp3", "aac",
                "m3u8", "mpd", "ts", "m4s", "vtt", "pdf"
            )
            val isStaticFile = extension in staticExtensions
            val hasApiPath = Regex("""/(api|ajax|graphql|gql|rest|rpc)(/|$)|/v[0-9]+(/|$)""")
                .containsMatchIn(lowerPath) || lowerPath.contains("graphql")
            val hasApiQuery = Regex("""(^|&)(action|endpoint|operation|query|mutation|graphql|format=json|_ajax)(=|&|$)""")
                .containsMatchIn(query)
            val jsonContent = contentType.contains("json") || contentType.contains("graphql")
            val jsonResponse = body.startsWith("{") || body.startsWith("[")
            val jsonRequest = requestBody.startsWith("{") || requestBody.startsWith("[")
            val fetchOrXhr = kind.contains("FETCH") || kind.contains("XHR")
            val score = (if (hasApiPath) 4 else 0) +
                (if (hasApiQuery) 2 else 0) +
                (if (jsonContent) 3 else 0) +
                (if (jsonResponse || jsonRequest) 2 else 0) +
                (if (fetchOrXhr) 1 else 0)

            if (isStaticFile || score < 3) {
                ignoredNoise++
                return@forEach
            }

            // Agrupar por método, host y ruta; normalizar IDs largos y tokens en la ruta.
            val normalizedPath = path.replace(Regex("""/[0-9]{3,}(?=/|$)"""), "/{id}")
                .replace(Regex("""/[0-9a-fA-F]{16,}(?=/|$)"""), "/{token}")
                .trimEnd('/').ifBlank { "/" }
            val key = "$method $host$normalizedPath"
            endpoints.getOrPut(key) { mutableListOf() }.add(line to detail)
        }

        if (endpoints.isEmpty()) {
            toast("No se detectaron endpoints con señales suficientes. Probá navegar/interactuar con la página y revisar la captura de solicitudes.")
            return
        }

        val rows = endpoints.entries.sortedByDescending { it.value.size }.map { (key, items) ->
            val latest = items.last()
            val d = latest.second
            val code = d?.optInt("status", 0)?.takeIf { it > 0 }?.toString() ?: "HTTP ?"
            val body = d?.optString("responseBody").orEmpty()
            val jsonLike = body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            val keys = if (jsonLike) {
                extractJsonKeys(body).take(12).joinToString(", ").ifBlank { "JSON sin claves detectables" }
            } else "sin cuerpo JSON capturado"
            val responseType = d?.optString("contentType").orEmpty().ifBlank { "tipo desconocido" }
            val confidence = when {
                responseType.contains("json", true) || responseType.contains("graphql", true) || jsonLike -> "JSON"
                key.contains("/api/", true) || key.contains("graphql", true) -> "ruta API"
                else -> "probable"
            }
            ("$key · HTTP $code · ${items.size} registro(s) · $confidence\n  $responseType · $keys") to latest.first
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Endpoints probables (${rows.size})")
            .setMessage("Agrupados por método y ruta. Se excluyen recursos estáticos comunes; la clasificación es orientativa y depende de lo que WebView haya podido capturar. Ruido omitido: $ignoredNoise.")
            .setItems(rows.map { it.first.take(240) }.toTypedArray()) { _, which ->
                val line = rows.getOrNull(which)?.second ?: return@setItems
                if (requestDetails.containsKey(line)) showRequestDetails(line)
                else toast("Solo se observó la URL; no hay detalles de respuesta capturados")
            }
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun extractJsonKeys(raw: String): List<String> {
        val found = linkedSetOf<String>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 4 || found.size >= 80) return
            when (value) {
                is org.json.JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext() && found.size < 80) {
                        val key = iterator.next()
                        found.add(key)
                        visit(value.opt(key), depth + 1)
                    }
                }
                is org.json.JSONArray -> if (value.length() > 0) visit(value.opt(0), depth + 1)
            }
        }
        runCatching {
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("{")) visit(org.json.JSONObject(raw), 0)
            else if (trimmed.startsWith("[")) visit(org.json.JSONArray(raw), 0)
        }
        return found.toList()
    }

    private fun showMediaRequests() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val media = snapshot.filter { line ->
            val lower = line.lowercase()
            classifyRequest(lower) == "MEDIO?" || lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".ts") ||
                lower.contains("manifest") || lower.contains("videoplayback") ||
                lower.contains("kind=media")
        }.asReversed()
        if (media.isEmpty()) {
            toast("No se detectaron recursos multimedia en la sesión actual")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Recursos multimedia (${media.size})")
            .setItems(media.take(200).map { displayRequestLine(it).take(240) }.toTypedArray()) { _, which ->
                val line = media.getOrNull(which) ?: return@setItems
                if (requestDetails.containsKey(line)) showRequestDetails(line)
                else toast("URL multimedia detectada; no se capturó el cuerpo del recurso")
            }
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun showHttpTests() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val explanation = TextView(this).apply {
            text = "Estas pruebas usan HttpURLConnection directamente. No pasan por WebView ni demuestran que el navegador capture su propio tráfico. Endpoint público: httpbin.org."
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(explanation)

        fun addProbeSection(title: String, method: String, url: String, body: String) {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setPadding(0, dp(8), 0, dp(4))
            })
            val output = TextView(this).apply {
                text = "PENDIENTE · La prueba todavía no se ejecutó."
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(25, 32, 47))
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val runButton = Button(this).apply { text = "EJECUTAR $method" }
            val copyButton = Button(this).apply { text = "COPIAR RESULTADO"; isEnabled = false }
            runButton.setOnClickListener {
                runButton.isEnabled = false
                output.text = "EJECUTANDO $method…\nURL: $url\nMétodo: $method\nEsperando una respuesta HTTP real."
                copyButton.isEnabled = false
                executor.execute {
                    val result = try {
                        HttpProbe.execute(method, url, body)
                    } catch (e: Exception) {
                        HttpProbe.Result(
                            url = url,
                            method = method,
                            requestBody = body,
                            statusCode = null,
                            responseHeaders = "",
                            responseBody = "",
                            durationMs = 0L,
                            errorType = "ERROR DE CONFIGURACIÓN",
                            errorMessage = e.message ?: e.javaClass.simpleName
                        )
                    }
                    runOnUiThread {
                        output.text = result.formatForDisplay()
                        runButton.isEnabled = true
                        copyButton.isEnabled = true
                    }
                }
            }
            copyButton.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Prueba HTTP $method", output.text))
                toast("Resultado $method copiado")
            }
            actions.addView(runButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(copyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(actions)
            panel.addView(output)
        }

        addProbeSection("1. GET real", "GET", HttpProbe.GET_URL, "")
        addProbeSection("2. POST real con JSON", "POST", HttpProbe.POST_URL, HttpProbe.POST_JSON)

        val scroll = ScrollView(this).apply { addView(panel) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pruebas HTTP directas")
            .setView(scroll)
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun runCaptureTest() {
        if (!::browser.isInitialized) {
            toast("El navegador integrado todavía no está inicializado")
            return
        }
        showBrowser()
        statusText.text = "Prueba controlada: GET y POST locales…"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Prueba de captura WebData</title></head>
            <body style="font-family:sans-serif;padding:18px;background:#171b28;color:#f2f4f8">
            <h2>Prueba de captura HTTP</h2>
            <p>Esta página genera un GET y un POST contra una respuesta JSON local conocida.</p>
            <pre id="out">Preparando prueba…</pre>
            <script>
            setTimeout(async function(){
              var out=document.getElementById('out');
              var runId=Date.now().toString()+'-'+Math.random().toString(36).slice(2);
              var suffix='?run='+encodeURIComponent(runId);
              try {
                var getResponse=await fetch('/__wdx_test_api/get'+suffix);
                var getJson=await getResponse.json();
                var postPayload={probe:'POST_BODY_CAPTURED',value:42};
                var postResponse=await fetch('/__wdx_test_api/post'+suffix,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(postPayload)});
                var postJson=await postResponse.json();
                var nativeStatus={getCaptured:false,postCaptured:false,postBodyCaptured:false};
                for(var attempt=0;attempt<8;attempt++){
                  await new Promise(function(resolve){setTimeout(resolve,250);});
                  try { nativeStatus=JSON.parse(window.WebDataCapture.getTestStatus(runId)); } catch(e) {}
                  if(nativeStatus.getCaptured && nativeStatus.postCaptured && nativeStatus.postBodyCaptured) break;
                }
                var pass=getResponse.status===200 && postResponse.status===200 &&
                  getJson.knownValue===42 && postJson.knownValue===42 &&
                  nativeStatus.getCaptured && nativeStatus.postCaptured && nativeStatus.postBodyCaptured;
                out.textContent=JSON.stringify({
                  get:{status:getResponse.status,body:getJson},
                  post:{status:postResponse.status,sent:postPayload,body:postJson},
                  nativeCapture:nativeStatus,
                  pageStillWorking:true,
                  acceptance:pass?'PASS':'PARTIAL/FAIL'
                },null,2);
                document.body.setAttribute('data-test-result',pass?'PASS':'FAIL');
              } catch(e) {
                out.textContent='FALLO DE PRUEBA: '+String(e);
                document.body.setAttribute('data-test-result','FAIL');
              }
            },1200);
            </script></body></html>
        """.trimIndent()
        browser.loadDataWithBaseURL("https://webdata.test/", html, "text/html", "UTF-8", "https://webdata.test/")
    }

    private fun shouldExcludeNoisyResource(raw: String): Boolean {
        val url = raw.substringAfter("] ", raw).substringBefore(" ·").substringBefore("?").substringBefore("#").lowercase()
        val path = url.substringBefore(" ")
        val image = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif", ".ico").any { path.endsWith(it) }
        val segment = listOf(".ts", ".m4s", ".cmfv", ".cmfa", ".aac", ".m4a").any { path.endsWith(it) } ||
            path.contains("/segment") || path.contains("/chunk") || path.contains("/seg-")
        return image || segment
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("webdata_settings", Context.MODE_PRIVATE)
        val choices = arrayOf("4 KB — mínimo", "12 KB — recomendado", "24 KB — respuestas más largas")
        val values = intArrayOf(4096, 12000, 24576)
        val current = prefs.getInt("body_limit", 12000)
        val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 1
        android.app.AlertDialog.Builder(this)
            .setTitle("Ajustes de captura")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                prefs.edit().putInt("body_limit", values[which]).apply()
                dialog.dismiss()
                toast("Límite aplicado a la próxima navegación")
            }
            .setNeutralButton(if (excludeNoisyResources) "INCLUIR IMÁGENES/SEGMENTOS" else "EXCLUIR IMÁGENES/SEGMENTOS") { _, _ ->
                excludeNoisyResources = !excludeNoisyResources
                prefs.edit().putBoolean("exclude_noisy_resources", excludeNoisyResources).apply()
                toast(if (excludeNoisyResources) "Se omiten imágenes y segmentos en nuevas capturas" else "Se registran imágenes y segmentos")
            }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun showDiagnostics() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        val details = synchronized(requestDetails) { requestDetails.values.map { org.json.JSONObject(it.toString()) } }
        val statuses = details.count { it.optInt("status", 0) > 0 }
        val bodies = details.count { it.optString("responseBody").isNotBlank() }
        val partial = details.count { it.optString("captureState").equals("PARCIAL", true) || it.optString("captureState").contains("LIMITADA", true) }
        val failed = details.count { it.optInt("status", 0) >= 400 || it.optString("kind").contains("ERROR", true) }
        val errors = snapshot.filter { line ->
            val l = line.lowercase()
            l.contains("error") || l.contains("falló") || l.contains("no disponible")
        }.takeLast(12)
        val report = buildString {
            appendLine("DIAGNÓSTICO — WebData Explorer")
            appendLine("WebView inicializado: ${::browser.isInitialized}")
            appendLine("Instrumentación document-start: ${if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) "disponible en este WebView" else "no disponible; se usa instalación posterior cuando es posible"}")
            appendLine("Escaneo: ${if (scanEnabled) "activo" else "pausado"}")
            appendLine("Excluir imágenes/segmentos: ${if (excludeNoisyResources) "activado" else "desactivado"}")
            appendLine("Solicitudes visibles: ${snapshot.size}")
            appendLine("Registros con estado HTTP: $statuses")
            appendLine("Cuerpos de respuesta disponibles: $bodies")
            appendLine("Capturas parciales/limitadas: $partial")
            appendLine("Respuestas HTTP 4xx/5xx o errores registrados: $failed")
            appendLine("Eventos recibidos por puente JS: $captureEventsReceived")
            appendLine("Errores del puente JS: $captureBridgeErrors")
            appendLine()
            appendLine("LIMITACIONES")
            appendLine("• shouldInterceptRequest detecta URL/método/encabezados de solicitud, pero no entrega el cuerpo original de la respuesta.")
            appendLine("• fetch/XHR solo se inspeccionan cuando la instrumentación se instala a tiempo y el cuerpo es textual y clonable.")
            appendLine("• Encabezados restringidos por CORS pueden no estar disponibles; iframe sin instrumentación también puede quedar incompleto.")
            appendLine("• Imágenes, audio, video y respuestas binarias no se almacenan como cuerpos.")
            appendLine()
            appendLine("ERRORES RECIENTES")
            appendLine(errors.joinToString("\n").ifBlank { "No se detectaron errores recientes en el registro visible." })
        }
        val view = TextView(this).apply {
            text = report
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Diagnóstico técnico")
            .setView(ScrollView(this).apply { addView(view) })
            .setNeutralButton("COPIAR") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico WebData", report))
                toast("Diagnóstico copiado")
            }
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun showResponseComparison(currentLine: String, current: org.json.JSONObject) {
        val currentUrl = runCatching { URI(current.optString("url")) }.getOrNull()
        val currentMethod = current.optString("method")
        val candidates = synchronized(requestLog) { requestLog.toList() }.filter { it != currentLine }.mapNotNull { line ->
            val detail = requestDetails[line] ?: return@mapNotNull null
            val uri = runCatching { URI(detail.optString("url")) }.getOrNull()
            if (detail.optString("method") == currentMethod && uri?.host == currentUrl?.host &&
                uri?.path == currentUrl?.path && detail.optString("responseBody").isNotBlank()) line to detail else null
        }
        if (candidates.isEmpty()) {
            toast("No hay otra respuesta capturada para comparar en este endpoint")
            return
        }
        val recent = candidates.asReversed().take(100)
        android.app.AlertDialog.Builder(this)
            .setTitle("Comparar con otra respuesta")
            .setItems(recent.map { (_, detail) ->
                "${detail.optString("timestamp")} · HTTP ${detail.optInt("status")} · ${detail.optString("responseBody").length} caracteres"
            }.toTypedArray()) { _, which ->
                val selected = recent.getOrNull(which)?.second ?: return@setItems
                val left = current.optString("responseBody")
                val right = selected.optString("responseBody")
                val comparison = compareResponseBodies(left, right)
                val view = TextView(this).apply {
                    text = "ACTUAL: ${current.optString("timestamp")} · HTTP ${current.optInt("status")}\n" +
                        "COMPARADA: ${selected.optString("timestamp")} · HTTP ${selected.optInt("status")}\n\n$comparison"
                    textSize = 12f
                    setTextIsSelectable(true)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Diferencias de respuesta")
                    .setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton("CERRAR", null)
                    .show()
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun compareResponseBodies(first: String, second: String): String {
        val a = runCatching { org.json.JSONObject(first) }.getOrNull()
        val b = runCatching { org.json.JSONObject(second) }.getOrNull()
        if (a == null || b == null) {
            if (first == second) return "Los cuerpos de texto son idénticos."
            val limit = 4000
            return "Los cuerpos difieren o no son objetos JSON.\n\n--- RESPUESTA A ---\n${first.take(limit)}\n\n--- RESPUESTA B ---\n${second.take(limit)}"
        }
        val keys = (a.keys().asSequence().toList() + b.keys().asSequence().toList()).distinct().sorted()
        val changes = keys.filter { a.opt(it)?.toString() != b.opt(it)?.toString() }
        if (changes.isEmpty()) return "Los objetos JSON tienen las mismas claves y valores."
        return buildString {
            appendLine("Claves diferentes: ${changes.size}")
            changes.forEach { key ->
                appendLine("• $key")
                appendLine("  A: ${a.opt(key)?.toString()?.take(500) ?: "[ausente]"}")
                appendLine("  B: ${b.opt(key)?.toString()?.take(500) ?: "[ausente]"}")
            }
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

    private fun exportRequestsCsv() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        if (snapshot.isEmpty()) {
            toast("Primero abrí una página para registrar solicitudes")
            return
        }
        fun csv(value: String): String = "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", "\\n") + "\""
        val columns = listOf("fecha", "metodo", "estado_http", "url", "tipo", "content_type", "estado_captura", "duracion_ms", "origen", "encabezados_solicitud", "cuerpo_enviado", "encabezados_respuesta", "cuerpo_recibido", "nota")
        val rows = mutableListOf(columns.joinToString(","))
        snapshot.forEach { line ->
            val d = requestDetails[line]
            val values = listOf(
                d?.optString("timestamp").orEmpty(),
                d?.optString("method").orEmpty(),
                d?.optInt("status", 0)?.takeIf { it > 0 }?.toString().orEmpty(),
                d?.optString("url").orEmpty().ifBlank { Regex("""https?://[^\s]+""").find(line)?.value.orEmpty() },
                d?.optString("kind").orEmpty(),
                d?.optString("contentType").orEmpty(),
                d?.optString("captureState").orEmpty(),
                d?.optLong("durationMs", -1L)?.takeIf { it >= 0 }?.toString().orEmpty(),
                d?.optString("initiatorOrigin").orEmpty(),
                d?.optString("requestHeaders").orEmpty(),
                d?.optString("requestBody").orEmpty(),
                d?.optString("responseHeaders").orEmpty(),
                d?.optString("responseBody").orEmpty(),
                d?.optString("note").orEmpty().ifBlank { "No se capturaron todos los campos; consultar estado y limitaciones de WebView." }
            )
            rows.add(values.joinToString(",") { csv(it) })
        }
        pendingExport = rows.joinToString("\n")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "webdata-solicitudes.csv")
        }
        startActivityForResult(intent, exportRequestCode)
    }

    private fun exportRequests() {
        val snapshot = synchronized(requestLog) { requestLog.toList() }
        if (snapshot.isEmpty()) {
            toast("Primero abrí una página para registrar solicitudes")
            return
        }
        val requests = org.json.JSONArray()
        snapshot.forEachIndexed { index, line ->
            val item = org.json.JSONObject().put("index", index + 1).put("entry", line)
            requestDetails[line]?.let { item.put("details", it) }
            requests.put(item)
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
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == apkAnalyzerRequestCode) {
            analyzeSelectedApk(uri)
            return
        }
        if (requestCode != exportRequestCode) return
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(pendingExport.toByteArray(Charsets.UTF_8))
            } ?: error("No se pudo abrir el archivo")
        }.onSuccess {
            toast("Archivo exportado correctamente")
        }.onFailure {
            toast("No se pudo exportar: ${it.message ?: "error"}")
        }
    }

    private fun chooseApkForAnalysis() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        runCatching {
            startActivityForResult(intent, apkAnalyzerRequestCode)
        }.onFailure {
            toast("No se pudo abrir el selector de archivos: ${it.message ?: "error"}")
        }
    }

    private fun analyzeSelectedApk(uri: android.net.Uri) {
        toast("Analizando APK localmente…")
        executor.execute {
            val report = runCatching { ApkAnalyzer.analyze(this, uri) }
                .getOrElse { "ERROR AL ANALIZAR APK\\n${it.javaClass.simpleName}: ${it.message ?: "sin detalles"}" }
            runOnUiThread { showApkAnalysisReport(report) }
        }
    }

    private fun showApkAnalysisReport(report: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "Análisis local de solo lectura. Las URLs encontradas son indicios, no APIs verificadas."
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun addReportCopyButton(label: String, startMarker: String, endMarker: String) {
            val button = Button(this).apply {
                text = label
                textSize = 10f
                setOnClickListener {
                    val start = report.indexOf(startMarker)
                    val end = if (start >= 0) report.indexOf(endMarker, start + startMarker.length) else -1
                    val section = if (start >= 0) {
                        report.substring(start, if (end >= 0) end else report.length).trim()
                    } else ""
                    if (section.isBlank()) {
                        toast("Esa sección no contiene resultados")
                    } else {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, section))
                        toast("$label copiado")
                    }
                }
            }
            quickActions.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addReportCopyButton(
            "COPIAR LLAMADAS DE RED",
            "=== ENDPOINTS Y CONTEXTO DE CÓDIGO ===",
            "=== POSIBLES PUNTOS DE LLAMADA DE RED ===",
            "=== URLS ENCONTRADAS EN DEX ==="
        )
        addReportCopyButton(
            "COPIAR URLS",
            "=== URLS ENCONTRADAS EN DEX ===",
            "=== POSIBLES RUTAS API ==="
        )
        quickActions.addView(Button(this).apply {
            text = "BUSCAR EN SMALI"
            textSize = 10f
            setOnClickListener { showSmaliSearchDialog(report) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickActions.addView(Button(this).apply {
            text = "EXPLORAR CLASES"
            textSize = 10f
            setOnClickListener { showSmaliClassExplorer(report) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickActions.addView(Button(this).apply {
            text = "EXPORTAR JSON"
            textSize = 10f
            setOnClickListener {
                pendingExport = buildApkReportJson(report)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "webdata-analisis-apk.json")
                }
                startActivityForResult(intent, exportRequestCode)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(quickActions)
        val output = TextView(this).apply {
            text = report
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(25, 32, 47))
        }
        val scroll = ScrollView(this).apply {
            addView(output)
            isFillViewport = true
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(440)
        ))
        android.app.AlertDialog.Builder(this)
            .setTitle("🔬 Resultado del análisis APK")
            .setView(panel)
            .setNeutralButton("COPIAR TODO") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Informe APK", report))
                toast("Informe del APK copiado")
            }
            .setNegativeButton("EXPORTAR TXT") { _, _ ->
                pendingExport = report
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "webdata-analisis-apk.txt")
                }
                startActivityForResult(intent, exportRequestCode)
            }
            .setPositiveButton("CERRAR", null)
            .show()
    }

    /** Converts the report's named sections into a portable JSON document without claiming findings are verified. */
    private fun buildApkReportJson(report: String): String {
        val sectionNames = listOf(
            "=== INFORMACIÓN DEL PAQUETE ===",
            "=== CLASES, MÉTODOS E INSTRUCCIONES DEX ===",
            "=== ARCHIVOS INTERNOS ===",
            "=== POSIBLES PUNTOS DE LLAMADA DE RED ===",
            "=== URLS ENCONTRADAS EN DEX ===",
            "=== POSIBLES RUTAS API ===",
            "=== BIBLIOTECAS / CLIENTES HTTP DETECTADOS ===",
            "=== CADENAS RELEVANTES ===",
            "=== LÍMITES DEL ANÁLISIS ==="
        )
        val sections = org.json.JSONObject()
        for (i in sectionNames.indices) {
            val marker = sectionNames[i]
            val start = report.indexOf(marker)
            if (start < 0) continue
            val contentStart = start + marker.length
            val nextStart = sectionNames.drop(i + 1)
                .map { report.indexOf(it, contentStart) }
                .filter { it >= 0 }
                .minOrNull() ?: report.length
            sections.put(marker.removePrefix("=== ").removeSuffix(" ==="), report.substring(contentStart, nextStart).trim())
        }
        val urls = org.json.JSONArray()
        val paths = org.json.JSONArray()
        val networkCalls = org.json.JSONArray()
        fun fillArray(section: String, target: org.json.JSONArray) {
            val value = sections.optString(section, "")
            value.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("Coincidencias") && !it.startsWith("Referencias detectadas") && !it.startsWith("…") }
                .forEach { target.put(it.removePrefix("• ").trim()) }
        }
        fillArray("URLS ENCONTRADAS EN DEX", urls)
        fillArray("POSIBLES RUTAS API", paths)
        fillArray("POSIBLES PUNTOS DE LLAMADA DE RED", networkCalls)
        return org.json.JSONObject()
            .put("format", "webdata-explorer-apk-report")
            .put("version", 1)
            .put("generatedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
            .put("analysisMode", "static-read-only")
            .put("findingsAreVerified", false)
            .put("reportHeader", report.lineSequence().takeWhile { !it.startsWith("=== ") }.joinToString("\n").trim())
            .put("sections", sections)
            .put("urls", urls)
            .put("possibleApiPaths", paths)
            .put("possibleNetworkCallSites", networkCalls)
            .put("limitations", "El informe contiene indicios estáticos. Las URLs y rutas no están confirmadas como endpoints funcionales; la muestra Smali está limitada.")
            .toString(2)
    }

    /**
     * Searches the bounded Smali listing in the APK report and shows matching instructions
     * with their enclosing class/method. This is static text analysis; it never executes code.
     */
    private fun showSmaliSearchDialog(report: String) {
        val input = EditText(this).apply {
            hint = "Ej.: baseUrl, okhttp, getInputStream, /api/"
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Buscar clases, métodos o instrucciones")
            .setMessage("Busca texto dentro de la muestra Smali incluida en el informe. No es una búsqueda de todo el APK.")
            .setView(input)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("BUSCAR", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.length < 2) {
                    toast("Escribí al menos 2 caracteres")
                    return@setOnClickListener
                }
                val results = searchSmaliReport(report, query)
                if (results.isBlank()) {
                    toast("No hubo coincidencias en la muestra Smali")
                } else {
                    dialog.dismiss()
                    showSmaliSearchResults(query, results)
                }
            }
        }
        dialog.show()
    }

    private fun searchSmaliReport(report: String, query: String): String {
        val lines = report.lines()
        val matches = mutableListOf<String>()
        var currentClass = ""
        var currentMethod = ""
        val needle = query.lowercase()
        for ((index, line) in lines.withIndex()) {
            if (line.trimStart().startsWith(".class ")) {
                currentClass = line.trim()
                currentMethod = ""
            } else if (line.trimStart().startsWith(".method ")) {
                currentMethod = line.trim()
            } else if (line.trimStart().startsWith(".end method")) {
                currentMethod = ""
            }
            if (line.contains(needle, ignoreCase = true)) {
                val context = buildString {
                    if (currentClass.isNotBlank()) appendLine(currentClass)
                    if (currentMethod.isNotBlank()) appendLine(currentMethod)
                    append("Línea ${index + 1}: ${line.trim()}")
                }
                matches.add(context)
                if (matches.size >= 200) break
            }
        }
        if (matches.isEmpty()) return ""
        return buildString {
            appendLine("BÚSQUEDA SMALI: $query")
            appendLine("Coincidencias mostradas: ${matches.size}${if (matches.size == 200) " (límite de 200)" else ""}")
            appendLine("Nota: la búsqueda cubre solo el Smali que el informe conservó.")
            appendLine()
            matches.forEachIndexed { i, item ->
                appendLine("---- ${i + 1} ----")
                appendLine(item)
                appendLine()
            }
        }.take(50_000)
    }

    private fun showSmaliSearchResults(query: String, results: String) {
        val output = TextView(this).apply {
            text = results
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(25, 32, 47))
        }
        val scroll = ScrollView(this).apply {
            addView(output)
            isFillViewport = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Coincidencias: $query")
            .setView(scroll)
            .setNeutralButton("COPIAR RESULTADOS") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Búsqueda Smali: $query", results))
                toast("Resultados Smali copiados")
            }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private data class SmaliMethodEntry(val signature: String, val body: String)
    private data class SmaliClassEntry(val descriptor: String, val methods: List<SmaliMethodEntry>)

    /** Explores only the Smali retained in the report; the APK scan is intentionally bounded. */
    private fun showSmaliClassExplorer(report: String) {
        val listingStart = report.indexOf("Muestra de instrucciones tipo Smali")
        val listingEnd = report.indexOf("=== ARCHIVOS INTERNOS ===", listingStart.coerceAtLeast(0))
        if (listingStart < 0 || listingEnd <= listingStart) {
            toast("El informe no contiene una muestra Smali navegable")
            return
        }
        val classes = parseSmaliClasses(report.substring(listingStart, listingEnd))
        if (classes.isEmpty()) {
            toast("No se encontraron clases en la muestra Smali")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Clases Smali (${classes.size} en la muestra)")
            .setMessage("Vista estática y limitada: puede no incluir todas las clases del APK.")
            .setItems(classes.map { entry ->
                "${entry.descriptor.substringAfterLast('/').removeSuffix(";")} · ${entry.methods.size} métodos"
            }.toTypedArray()) { _, which ->
                val selected = classes.getOrNull(which) ?: return@setItems
                showSmaliMethods(selected)
            }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun parseSmaliClasses(listing: String): List<SmaliClassEntry> {
        val result = mutableListOf<SmaliClassEntry>()
        var currentClass: String? = null
        var methods = mutableListOf<SmaliMethodEntry>()
        var currentMethod: String? = null
        var methodBody = StringBuilder()

        fun finishMethod() {
            val signature = currentMethod ?: return
            methods.add(SmaliMethodEntry(signature, methodBody.toString().trimEnd()))
            currentMethod = null
            methodBody = StringBuilder()
        }
        fun finishClass() {
            finishMethod()
            val descriptor = currentClass ?: return
            result.add(SmaliClassEntry(descriptor, methods.toList()))
            currentClass = null
            methods = mutableListOf()
        }

        for (line in listing.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(".class ")) {
                finishClass()
                currentClass = trimmed.substringAfterLast(' ').trim()
            } else if (currentClass != null && trimmed.startsWith(".method ")) {
                finishMethod()
                currentMethod = trimmed
                methodBody = StringBuilder().apply { appendLine(line) }
            } else if (currentMethod != null) {
                methodBody.appendLine(line)
                if (trimmed == ".end method") finishMethod()
            }
        }
        finishClass()
        return result
    }

    private fun showSmaliMethods(entry: SmaliClassEntry) {
        if (entry.methods.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle(entry.descriptor)
                .setMessage("La clase aparece en la muestra, pero no hay métodos disponibles en el fragmento conservado.")
                .setPositiveButton("VOLVER", null)
                .show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Métodos · ${entry.descriptor.substringAfterLast('/')}")
            .setItems(entry.methods.map { it.signature.removePrefix(".method ") }.toTypedArray()) { _, which ->
                val method = entry.methods.getOrNull(which) ?: return@setItems
                showSmaliMethod(entry.descriptor, method)
            }
            .setNegativeButton("VOLVER", null)
            .show()
    }

    private fun showSmaliMethod(className: String, method: SmaliMethodEntry) {
        val code = buildString {
            appendLine(className)
            appendLine(method.signature)
            append(method.body)
        }
        val output = TextView(this).apply {
            text = code
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(25, 32, 47))
        }
        val scroll = ScrollView(this).apply {
            addView(output)
            isFillViewport = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Smali · método")
            .setView(scroll)
            .setNeutralButton("COPIAR MÉTODO") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Smali $className", code))
                toast("Método Smali copiado")
            }
            .setNegativeButton("VOLVER", null)
            .show()
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
        saveCurrentSession()
        if (::browser.isInitialized) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        saveCurrentSession()
        mainHandler.removeCallbacks(requestPollRunnable)
        if (::browser.isInitialized) CookieManager.getInstance().flush()
        executor.shutdownNow()
        browser.destroy()
        super.onDestroy()
    }
}
