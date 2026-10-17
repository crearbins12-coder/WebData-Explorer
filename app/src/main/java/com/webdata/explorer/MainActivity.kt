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
    private val requestDetails = java.util.Collections.synchronizedMap(mutableMapOf<String, org.json.JSONObject>())
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
                            // Cada navegación principal inicia un registro nuevo: no mezclar
                            // las solicitudes de la página anterior con la página actual.
                            if (request.isForMainFrame) clearRequestLogForNewPage()
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
                    view?.evaluateJavascript(
                        """(function(){
 try {
  if (window.__wdxInstalled) return JSON.stringify({resources:performance.getEntriesByType('resource').map(function(e){return e.name;}),media:[],network:[]});
  window.__wdxInstalled=true; window.__wdxNet=[]; window.__wdxNetCursor=0;
  var LIMIT=12000;
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
    item.requestHeaders=redact(item.requestHeaders||'');
    item.requestBody=redact(item.requestBody||'');
    item.responseHeaders=redact(item.responseHeaders||'');
    item.responseBody=redact(item.responseBody||'');
    item.contentType=String(item.contentType||'');
    item.captureState=item.captureState||((item.responseBody||item.responseHeaders)?'PARCIAL':'LIMITADA');
    item.note=item.note||'';
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
      record({kind:'FETCH',method:String(method).toUpperCase(),url:url,status:response.status,requestHeaders:reqHeaders,requestBody:values[0],responseHeaders:headersText(response.headers),responseBody:rb.body,contentType:ct,captureState:rb.state,note:rb.note});
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
   var xhr=this, requestBody='';
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
    record({kind:'XHR',method:String(xhr.__wdxMethod||'GET').toUpperCase(),url:xhr.__wdxUrl||'',status:xhr.status||0,requestHeaders:redact((xhr.__wdxReqHeaders||[]).join('\n')),requestBody:requestBody,responseHeaders:redact(headers),responseBody:responseBody,contentType:ct,captureState:state,note:note||'Cuerpo capturado desde XMLHttpRequest cuando fue accesible.'});
   });
   return originalSend.apply(this,arguments);
  };
  var resources=performance.getEntriesByType('resource').map(function(e){return e.name;});
  var media=Array.prototype.slice.call(document.querySelectorAll('video,audio,source')).map(function(e){return e.currentSrc||e.src||e.getAttribute('src')||'';}).filter(Boolean);
  var net=window.__wdxNet||[]; window.__wdxNetCursor=net.length;
  return JSON.stringify({resources:resources,media:media,network:net});
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
        val safeLine = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).replace(line) { sanitizeUrlForStorage(it.value) }
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
        val filtered = snapshot.filter { matchesFilter(it) }.asReversed().take(300)
        if (snapshot.isEmpty()) {
            requestText.text = "Todavía no se observaron solicitudes. Abrí una página o recargala."
            return
        }
        val out = SpannableStringBuilder("Filtro: $requestFilter · ${filtered.size}/${snapshot.size} solicitudes (máximo 300)\n\n")
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

    private fun addDetailedRequest(
        kind: String, method: String, url: String, status: Int,
        requestHeaders: String, requestBody: String,
        responseHeaders: String, responseBody: String,
        contentType: String, captureState: String, note: String
    ) {
        if (!scanEnabled) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val safeUrl = sanitizeUrlForStorage(url)
        val statusLabel = if (status > 0) "HTTP $status" else "HTTP ?"
        val line = "$stamp · ${method.uppercase()} · $statusLabel · [$kind/${classifyRequest(safeUrl)}] $safeUrl"
        val detail = org.json.JSONObject()
            .put("timestamp", stamp).put("url", safeUrl).put("method", method.uppercase())
            .put("status", status).put("kind", kind).put("contentType", contentType)
            .put("bodyType", classifyContentType(contentType))
            .put("requestHeaders", cleanSensitiveText(requestHeaders).take(12000))
            .put("requestBody", cleanSensitiveText(requestBody).take(16000))
            .put("responseHeaders", cleanSensitiveText(responseHeaders).take(12000))
            .put("responseBody", cleanSensitiveText(responseBody).take(16000))
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

    private fun showRequestDetails(line: String) {
        val detail = requestDetails[line] ?: run {
            toast("Esta solicitud solo tiene URL; WebView no expuso más datos")
            return
        }
        val labels = arrayOf("Resumen", "Encabezados solicitud", "Cuerpo enviado", "Encabezados respuesta", "Cuerpo recibido")
        val keys = arrayOf("", "requestHeaders", "requestBody", "responseHeaders", "responseBody")
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
                append("Captura: ").append(detail.optString("captureState", "parcial")).append("\n")
                append(detail.optString("note", ""))
            }
            val raw = detail.optString(keys[index], "")
            if (raw.isNotBlank()) return raw
            return when (index) {
                1 -> "Encabezados no disponibles: la solicitud no fue observada por instrumentación JavaScript o WebView no los expuso."
                2 -> if (detail.optString("method") in listOf("GET", "HEAD") && detail.optString("kind") == "WEBVIEW") "No se observó cuerpo enviado. GET/HEAD normalmente no envían cuerpo; shouldInterceptRequest no expone el cuerpo original." else "Cuerpo enviado no disponible. WebView no ofrece el cuerpo de solicitudes de recursos en shouldInterceptRequest; para JavaScript solo se capturan cuerpos de texto accesibles."
                3 -> "Encabezados de respuesta no disponibles para esta solicitud."
                else -> "Cuerpo recibido no disponible. WebView no expone directamente el cuerpo de respuestas desde shouldInterceptRequest; el navegador solo permite leer algunas respuestas fetch/XHR accesibles."
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
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
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
        layout.addView(actions)
        updateSection()
        android.app.AlertDialog.Builder(this).setTitle("Detalle de solicitud").setView(layout).setPositiveButton("Cerrar", null).show()
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
