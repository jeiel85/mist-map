package com.jeiel85.mistmap.renderer

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.jeiel85.mistmap.MistApp
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

private const val HOST = "appassets.androidplatform.net"
private val ORIGIN: Uri = Uri.parse("https://$HOST")
private val PAGE = "https://$HOST/assets/web/index.html?app"

/**
 * 웹 프로토타입의 안개 렌더러를 띄우는 WebView (DECISIONS D1).
 * 페이지가 뜨면 [onConnected]로 브리지를 넘기고, 페이지가 사라지면 null을 넘긴다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RendererView(
  onConnected: (RendererBridge?) -> Unit,
  onMessage: (JSONObject) -> Unit,
  modifier: Modifier = Modifier,
) {
  val connected by rememberUpdatedState(onConnected)
  val messages by rememberUpdatedState(onMessage)
  // 렌더러 프로세스가 죽으면 WebView를 새로 만든다
  var generation by remember { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle

  key(generation) {
    val webViewRef = remember { AtomicReference<WebView?>() }
    AndroidView(
      modifier = modifier,
      factory = { context ->
        val loader = WebViewAssetLoader.Builder()
          .setDomain(HOST)
          .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
          .build()
        WebView(context).apply {
          webViewRef.set(this)
          // 기본값(wrap_content)이면 WebView가 문서 높이를 0으로 잡아 100% 높이 지도가 사라진다
          layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
          settings.javaScriptEnabled = true
          settings.allowFileAccess = false
          settings.allowContentAccess = false
          setBackgroundColor(0xFFF4EBD9.toInt())
          if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
          }
          webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
              val text = "웹: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
              when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.e(MistApp.TAG, text)
                ConsoleMessage.MessageLevel.WARNING -> Log.w(MistApp.TAG, text)
                else -> Log.d(MistApp.TAG, text)
              }
              return true
            }
          }
          webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
              loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
              if (!RendererBridge.isSupported()) {
                messages(JSONObject().put("type", "error").put("message", "이 기기의 WebView가 너무 오래되었어요"))
                return
              }
              connected(RendererBridge.connect(view, ORIGIN) { messages(it) })
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
              Log.w(MistApp.TAG, "렌더러 프로세스 종료 (crash=${detail.didCrash()}), 다시 만듦")
              connected(null)
              generation++
              return true
            }
          }
          loadUrl(PAGE)
        }
      },
    )
    DisposableEffect(lifecycle) {
      val observer = LifecycleEventObserver { _, event ->
        when (event) {
          // 화면이 꺼져 있으면 그리지 않는다. 기록은 서비스와 DB만으로 돈다.
          Lifecycle.Event.ON_PAUSE -> webViewRef.get()?.onPause()
          Lifecycle.Event.ON_RESUME -> webViewRef.get()?.onResume()
          else -> Unit
        }
      }
      lifecycle.addObserver(observer)
      onDispose {
        lifecycle.removeObserver(observer)
        connected(null)
        webViewRef.getAndSet(null)?.destroy()
      }
    }
  }
}
