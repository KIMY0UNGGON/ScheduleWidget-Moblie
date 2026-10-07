package com.schedulewidget.mobile.music

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Hosts the YouTube IFrame player (port of desktop Player/player.html) in a process-wide WebView created with the
 * application context, so playback survives activity recreation. [YouTubeHost] only attaches it to the window.
 */
internal object YouTubeEngine {
    private const val PLAYER_URL = "https://schedulewidget.example/player.html"

    interface Listener {
        fun onYtState(request: Int, state: Int)
        fun onYtEnded(request: Int)
        fun onYtError(request: Int, code: String)
        fun onYtTime(request: Int, currentSec: Double, durationSec: Double)
        fun onYtTitle(request: Int, title: String)
        fun onYtBlocked(request: Int)
    }

    var listener: Listener? = null
    private var webView: WebView? = null
    private var ready = false
    private val queued = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private var lastRequest = -1
    /** Bumped when the WebView is re-created (renderer crash) so [YouTubeHost] attaches the new one. */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)
    // Do not contact YouTube or create a WebView until playback is explicitly requested.
    val requested = kotlinx.coroutines.flow.MutableStateFlow(false)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun view(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webChromeClient = WebChromeClient()
        // The bridge is visible to every frame; keep its nonce in the top-frame source, never the URL.
        val token = java.util.UUID.randomUUID().toString()
        wv.webViewClient = object : WebViewClient() {
            // Keep the player page; links inside the embed (logo, "watch on YouTube") are ignored.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            // Serve the player page from a real https origin (like the desktop's virtual host) so the embed
            // iframe gets a proper Referer; YouTube rejects referrer-less embeds with errors 152/153.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = when {
                request.isForMainFrame && isPlayerPage(request.url) -> WebResourceResponse(
                    "text/html", "utf-8", ByteArrayInputStream(playerHtml(token).toByteArray(Charsets.UTF_8)),
                )
                isPlayerHost(request.url) -> WebResourceResponse(
                    "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                )
                else -> null
            }

            // Without this, a killed/crashed WebView renderer (common under memory pressure) kills the whole app.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    webView = null
                    ready = false
                    queued.clear()
                    generation.value++
                    listener?.onYtError(lastRequest, "renderer")
                }
                runCatching { view.destroy() }
                return true
            }
        }
        wv.addJavascriptInterface(Bridge(token), "Android")
        wv.loadUrl(PLAYER_URL)
        webView = wv
        return wv
    }

    private fun isPlayerHost(uri: Uri) = uri.host?.equals("schedulewidget.example", ignoreCase = true) == true

    private fun isPlayerPage(uri: Uri) = uri.scheme == "https" && isPlayerHost(uri) &&
        uri.port == -1 && uri.userInfo == null && uri.path == "/player.html" && uri.query == null

    private fun playerHtml(token: String) = PLAYER_HTML.replace("__ANDROID_BRIDGE_TOKEN__", JSONObject.quote(token))

    fun detach() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    fun load(context: Context, request: Int, videoId: String?, listId: String?, volume: Int) {
        requested.value = true
        lastRequest = request
        val m = JSONObject().put("action", "load").put("request", request).put("volume", volume)
        if (listId != null) m.put("list", listId) else m.put("video", videoId)
        send(context, m)
    }

    fun command(context: Context, action: String, extra: Map<String, Any> = emptyMap()) {
        if (webView == null && action != "load") return
        val m = JSONObject().put("action", action)
        extra.forEach { (k, v) -> m.put(k, v) }
        send(context, m)
    }

    private fun send(context: Context, message: JSONObject) {
        val script = "handle(${message});"
        val wv = view(context)
        if (ready) wv.evaluateJavascript(script, null) else {
            if (message.optString("action") == "load") queued.removeAll { it.contains("\"load\"") }
            queued += script
        }
    }

    private class Bridge(private val token: String) {
        @JavascriptInterface
        fun post(candidate: String, json: String) {
            if (candidate != token || json.length > 8192) return
            main.post { dispatch(json) }
        }
    }

    private fun dispatch(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        val request = m.optInt("request", -1)
        val l = listener
        when (m.optString("type")) {
            "ready" -> {
                ready = true
                queued.forEach { webView?.evaluateJavascript(it, null) }
                queued.clear()
            }
            "state" -> l?.onYtState(request, m.optInt("state"))
            "ended" -> l?.onYtEnded(request)
            "error" -> l?.onYtError(request, m.opt("code")?.toString() ?: "")
            "time" -> {
                val current = m.optDouble("current", Double.NaN)
                val duration = m.optDouble("duration", Double.NaN)
                if (current.isFinite() && duration.isFinite() && current >= 0 && duration >= 0)
                    l?.onYtTime(request, current, duration)
            }
            "title" -> l?.onYtTitle(request, m.optString("title"))
            "blocked" -> l?.onYtBlocked(request)
        }
    }

    private val PLAYER_HTML = """
<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body,#player{margin:0;width:100%;height:100%;background:#111827;overflow:hidden}</style></head>
<body><div id="player"></div><script>
'use strict';
let player, pending, request = 0, playlist = false, ended = false, acceptsEnd = false, apiReady = false, wantPaused = false;
const bridgeToken = __ANDROID_BRIDGE_TOKEN__;
const send = (type, extra = {}) => Android.post(bridgeToken, JSON.stringify(Object.assign({type, request}, extra)));
function load(m) {
  request = m.request; playlist = !!m.list; ended = false; acceptsEnd = false;
  player.setVolume(m.volume);
  wantPaused = !!m.paused;
  if (playlist) player.loadPlaylist({list: m.list, listType: 'playlist', index: 0});
  else player.loadVideoById(m.video);
}
window.onYouTubeIframeAPIReady = function() {
  player = new YT.Player('player', {width:'100%',height:'100%',playerVars:{playsinline:1,origin:location.origin},events:{
    onReady() { apiReady = true; if (pending) { load(pending); pending = null; } },
    onStateChange(e) {
      send('state', {state: e.data});
      if (e.data === 1) {
        acceptsEnd = true; ended = false;
        const d = player.getVideoData && player.getVideoData();
        if (d && d.title) send('title', {title: d.title});
        if (wantPaused) { wantPaused = false; player.pauseVideo(); return; }
      }
      if (e.data !== 0 || ended || !acceptsEnd) return;
      if (playlist) { const items = player.getPlaylist() || []; if (player.getPlaylistIndex() < items.length - 1) return; }
      ended = true; send('ended');
    },
    onError(e) { send('error', {code: String(e.data)}); },
    onAutoplayBlocked() { send('blocked'); }
  }});
};
function handle(m) {
  if (m.action === 'load') { if (apiReady) load(m); else { pending = m; request = m.request; if (apiFailed) loadApi(); } return; }
  if (m.action === 'pause' && pending) pending.paused = true;
  if (m.action === 'resume') { wantPaused = false; if (pending) pending.paused = false; }
  if (m.action === 'stop') { pending = null; acceptsEnd = false; }
  if (!apiReady) return;
  if (m.action === 'pause') player.pauseVideo();
  if (m.action === 'resume') player.playVideo();
  if (m.action === 'stop') player.stopVideo();
  if (m.action === 'volume') player.setVolume(m.volume);
  if (m.action === 'seek' && typeof m.seconds === 'number') player.seekTo(Math.max(0, m.seconds), true);
}
let lastTime = -1, lastDuration = -1;
setInterval(() => {
  if (!apiReady || !player.getDuration) return;
  const d = player.getDuration(), c = player.getCurrentTime();
  if (d > 0 && (c !== lastTime || d !== lastDuration)) { lastTime = c; lastDuration = d; send('time', {current: c, duration: d}); }
}, 500);
// Retried on the next load when it failed (e.g. no network at first start), instead of never working again.
let apiFailed = false;
function loadApi() {
  apiFailed = false;
  const s = document.createElement('script'); s.src = 'https://www.youtube.com/iframe_api';
  s.onerror = () => { apiFailed = true; s.remove(); send('error', {code: 'network'}); };
  document.head.appendChild(s);
}
send('ready');
loadApi();
</script></body></html>
"""
}
