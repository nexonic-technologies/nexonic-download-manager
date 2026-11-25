package com.nexonic.downloadmanager.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nexonic.downloadmanager.core.FileNames
import com.nexonic.downloadmanager.core.Links
import com.nexonic.downloadmanager.core.Media
import com.nexonic.downloadmanager.data.Downloads
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.URLEncoder

/** A file or video found on the page: offered in the "Found on this page" panel. */
data class Found(val url: String, val name: String, val kind: String, val label: String? = null)

/** What the browser hands to the app when a download starts or a link is long-pressed. */
data class Offer(
    val url: String,
    val fileName: String? = null,
    val mime: String? = null,
    val size: Long = -1,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val quality: String? = null,
)

/** Pure navigation and detection rules for [Browser], separated so they can be unit-tested on the JVM. */
internal object BrowserRules {
    enum class LinkAction { ALLOW_WEB, MAGNET, NEXONIC, IGNORE }

    fun classifyNavigation(url: String): LinkAction {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':').lowercase()
        return when {
            (scheme == "http" || scheme == "https") && Links.isWebLink(trimmed) -> LinkAction.ALLOW_WEB
            trimmed.startsWith("magnet:?", ignoreCase = true) -> LinkAction.MAGNET
            scheme == "nexonic" -> LinkAction.NEXONIC
            else -> LinkAction.IGNORE
        }
    }

    fun isTorrentDownload(url: String, mime: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        if (m == "application/x-bittorrent") return true
        return Links.isWebLink(url) && FileNames.extensionOf(url) == "torrent"
    }

    fun pageKey(url: String): String = url.trim().substringBefore('#')

    fun shouldRescanOnVisitedUpdate(lastPageKey: String, newUrl: String, isReload: Boolean): Boolean {
        if (isReload) return false
        val trimmed = newUrl.trim()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) return false
        if (!Links.isWebLink(trimmed)) return false
        return pageKey(trimmed) != lastPageKey
    }

    fun shouldCollectRequest(url: String, isForMainFrame: Boolean): Boolean =
        !Media.isStream(url) && (Media.isMedia(url, null, -1) || (Media.looksDownloadable(url) && !isForMainFrame))

    fun acceptScanItem(url: String, kind: String): Boolean =
        if (kind == "Link") Media.looksDownloadable(url) && !Media.isStream(url)
        else Links.isWebLink(url) && !Media.isStream(url)
}

/**
 * The built-in browser. It does what the desktop browser extension does: downloads go to Nexonic, and files and
 * videos on the page are listed so they can be saved. Streams and copy-protected video are not offered.
 */
class Browser(
    context: Context,
    private val onOffer: (Offer) -> Unit,
    private val onLongPress: (Offer, isImage: Boolean) -> Unit,
    private val onTorrent: (String) -> Unit = {},
    private val onNexonic: (List<String>) -> Unit = {},
) {
    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(0)
    val canBack = MutableStateFlow(false)
    val canForward = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    @Volatile private var navGen = 0L
    private var lastPageKey = ""
    @Volatile private var destroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, !Downloads.settings.blockThirdPartyCookies)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val raw = request.url.toString()
                return when (BrowserRules.classifyNavigation(raw)) {
                    BrowserRules.LinkAction.ALLOW_WEB -> false
                    BrowserRules.LinkAction.MAGNET -> { onTorrent(raw); true }
                    BrowserRules.LinkAction.NEXONIC -> {
                        val links = Links.fromNexonicLink(raw)
                        if (links.isNotEmpty()) onNexonic(links)
                        true
                    }
                    BrowserRules.LinkAction.IGNORE -> true
                }
            }

            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                navGen++
                val blank = u.equals("about:blank", ignoreCase = true)
                lastPageKey = if (blank) "" else BrowserRules.pageKey(u)
                this@Browser.url.value = if (blank) "" else u
                this@Browser.title.value = ""
                found.value = emptyList()
                updateNav()
            }

            override fun onPageFinished(view: WebView, u: String) {
                val blank = u.equals("about:blank", ignoreCase = true)
                lastPageKey = if (blank) "" else BrowserRules.pageKey(u)
                this@Browser.url.value = if (blank) "" else u
                this@Browser.title.value = if (blank) "" else view.title.orEmpty()
                updateNav()
                if (!blank) scan(navGen)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                val blank = u.equals("about:blank", ignoreCase = true)
                this@Browser.url.value = if (blank) "" else u
                updateNav()
                if (BrowserRules.shouldRescanOnVisitedUpdate(lastPageKey, u, isReload)) {
                    navGen++
                    lastPageKey = BrowserRules.pageKey(u)
                    found.value = emptyList()
                    val g = navGen
                    main.postDelayed({ if (!destroyed && g == navGen) scan(g) }, 600)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Only looks at addresses; nothing is changed or blocked.
                val u = request.url.toString()
                val g = navGen
                if (BrowserRules.shouldCollectRequest(u, request.isForMainFrame)) {
                    main.post { if (!destroyed && g == navGen) addFound(Found(u, FileNames.fromUrl(u), kindOf(u))) }
                }
                return null
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@Browser.progress.value = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { this@Browser.title.value = if (this@Browser.url.value.isEmpty()) "" else t.orEmpty() }
        }
        setDownloadListener { u, userAgent, contentDisposition, mimetype, contentLength ->
            if (!Links.isWebLink(u)) return@setDownloadListener // blob: and data: stay in the page
            if (BrowserRules.isTorrentDownload(u, mimetype)) {
                onTorrent(u)
                return@setDownloadListener
            }
            onOffer(
                Offer(
                    url = u,
                    fileName = URLUtil.guessFileName(u, contentDisposition, mimetype),
                    mime = mimetype,
                    size = contentLength,
                    referer = this.url,
                    userAgent = userAgent,
                    cookies = CookieManager.getInstance().getCookie(u),
                )
            )
        }
        setOnLongClickListener {
            val hit = hitTestResult
            val target = hit.extra
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE -> {
                    if (target != null && Links.isWebLink(target)) {
                        onLongPress(offerFor(target), hit.type == WebView.HitTestResult.IMAGE_TYPE)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    fun offerFor(link: String, quality: String? = null): Offer {
        val u = Links.unwrap(link)
        return Offer(
            url = u, fileName = FileNames.fromUrl(u), referer = view.url,
            userAgent = view.settings.userAgentString, cookies = CookieManager.getInstance().getCookie(u), quality = quality,
        )
    }

    private fun kindOf(u: String): String {
        val ext = FileNames.extensionOf(u)
        return when {
            ext in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac") -> "Audio"
            ext in Media.MEDIA_TYPES -> "Video"
            else -> "File"
        }
    }

    private fun addFound(f: Found) {
        if (Media.isStream(f.url)) return
        val list = found.value
        if (list.any { it.url == f.url } || list.size >= 200) return
        found.value = list + f
    }

    /** Finds video, audio and file links in the page (like the extension's content script). */
    private fun scan(expectedGen: Long = navGen) {
        if (destroyed || expectedGen != navGen) return
        val js = """
            (function(){
              var out=[];
              function add(u,kind,label){ try{ u=new URL(u,location.href).href; }catch(e){ return; } out.push([u,kind,label||'']); }
              document.querySelectorAll('video,audio').forEach(function(m){
                var k=m.tagName==='VIDEO'?'Video':'Audio';
                if(m.currentSrc&&!m.currentSrc.startsWith('blob:')) add(m.currentSrc,k,'');
                m.querySelectorAll('source[src]').forEach(function(s){ add(s.src,k,s.getAttribute('label')||s.getAttribute('size')||s.getAttribute('res')||s.getAttribute('title')||''); });
              });
              document.querySelectorAll('a[href]').forEach(function(a){ add(a.href,'Link',(a.textContent||'').trim().slice(0,60)); });
              return JSON.stringify(out.slice(0,800));
            })();
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            if (destroyed || expectedGen != navGen) return@evaluateJavascript
            runCatching {
                val arr = JSONArray(JSONArray("[$result]").getString(0))
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    val u = e.getString(0)
                    val kind = e.getString(1)
                    val label = e.getString(2).ifEmpty { null }
                    if (BrowserRules.acceptScanItem(u, kind)) {
                        addFound(Found(u, FileNames.fromUrl(u), if (kind == "Link") kindOf(u) else kind, if (kind == "Link") null else label))
                    }
                }
            }
        }
    }

    private fun updateNav() {
        canBack.value = view.canGoBack()
        canForward.value = view.canGoForward()
    }

    /** Loads an address, or searches for the words typed. */
    fun go(input: String) {
        if (destroyed) return
        val text = input.trim()
        if (text.isEmpty()) return
        when (BrowserRules.classifyNavigation(text)) {
            BrowserRules.LinkAction.MAGNET -> { onTorrent(text); return }
            BrowserRules.LinkAction.NEXONIC -> {
                val links = Links.fromNexonicLink(text)
                if (links.isNotEmpty()) onNexonic(links)
                return
            }
            else -> {}
        }
        val target = when {
            Links.isWebLink(text) -> text
            !text.contains(' ') && text.contains('.') && !text.contains("://") -> "https://$text"
            else -> com.nexonic.downloadmanager.core.SearchEngines.url(Downloads.settings.searchEngine, text)
        }
        started = true
        view.loadUrl(target)
    }

    fun startIfNeeded() {
        if (!started && !destroyed) { started = true; view.loadUrl(Downloads.settings.homePage) }
    }

    fun back() { if (!destroyed && view.canGoBack()) view.goBack() }
    fun forward() { if (!destroyed && view.canGoForward()) view.goForward() }
    fun reload() { if (!destroyed) view.reload() }
    fun stop() { if (!destroyed) view.stopLoading() }
    fun pause() { if (!destroyed) view.onPause() }
    fun resume() { if (!destroyed) view.onResume() }

    fun saveState(outState: Bundle) { if (!destroyed) view.saveState(outState) }

    fun restoreState(savedState: Bundle): Boolean {
        if (destroyed) return false
        val restored = view.restoreState(savedState) != null
        if (restored) {
            started = true
            url.value = view.url.orEmpty()
            title.value = view.title.orEmpty()
            updateNav()
        }
        return restored
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        view.stopLoading()
        view.destroy()
    }

    fun applyPrivacy() {
        if (!destroyed) CookieManager.getInstance().setAcceptThirdPartyCookies(view, !Downloads.settings.blockThirdPartyCookies)
    }

    /** Clears cookies, site data, cache and history, and tears down the currently loaded page. */
    fun clearData() {
        navGen++
        lastPageKey = ""
        started = false
        found.value = emptyList()
        title.value = ""
        url.value = ""
        main.removeCallbacksAndMessages(null)
        if (!destroyed) {
            view.stopLoading()
            view.loadUrl("about:blank")
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (!destroyed) {
            view.clearCache(true)
            view.clearHistory()
            view.clearFormData()
        }
        canBack.value = false
        canForward.value = false
    }

    fun host(): String = runCatching { Uri.parse(url.value).host.orEmpty() }.getOrDefault("")
}
