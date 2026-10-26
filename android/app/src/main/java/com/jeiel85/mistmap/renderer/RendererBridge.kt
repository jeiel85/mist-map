package com.jeiel85.mistmap.renderer

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jeiel85.mistmap.MistApp
import com.jeiel85.mistmap.reveal.CellGrid
import com.jeiel85.mistmap.tracking.Fix
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 앱 ↔ 웹 렌더러 메시지 (설계 문서 5장). 형식은 src/main.js의 "앱 브리지" 주석과 짝을 이룬다.
 * 페이지가 다 뜬 뒤 [connect]로 MessagePort 하나를 넘기고, 이후 모든 메시지는 그 포트로 오간다.
 */
class RendererBridge private constructor(private val port: WebMessagePortCompat) {
  private val arrayBuffers = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

  fun sendCells(kind: Int, cellIds: Collection<Long>) {
    val bytes = encodeCells(kind, cellIds)
    if (arrayBuffers) {
      port.postMessage(WebMessageCompat(bytes))
    } else {
      val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
      post(JSONObject().put("type", "cells").put("b64", b64))
    }
  }

  fun sendPlayer(fix: Fix) = post(JSONObject().put("type", "player").put("lng", fix.lng).put("lat", fix.lat))

  /** 가져온 기록이 한눈에 들어오게 카메라를 옮긴다. */
  fun sendFit(south: Double, west: Double, north: Double, east: Double) = post(
    JSONObject().put("type", "fit")
      .put("south", south).put("west", west).put("north", north).put("east", east),
  )

  /** 앱 HUD가 가리는 위·아래 높이 (dp = WebView CSS px). */
  fun sendInsets(topDp: Int, bottomDp: Int) =
    post(JSONObject().put("type", "insets").put("top", topDp).put("bottom", bottomDp))

  private fun post(json: JSONObject) = port.postMessage(WebMessageCompat(json.toString()))

  fun close() = port.close()

  companion object {
    const val CELLS_SET = 1
    const val CELLS_ADD = 2

    fun isSupported() = listOf(
      WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
      WebViewFeature.POST_WEB_MESSAGE,
      WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
      WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
    ).all(WebViewFeature::isFeatureSupported)

    /** [u8 종류, 3바이트 비움, (u32 x, u32 y) 리틀 엔디언 반복] */
    fun encodeCells(kind: Int, cellIds: Collection<Long>): ByteArray {
      val buf = ByteBuffer.allocate(4 + cellIds.size * 8).order(ByteOrder.LITTLE_ENDIAN)
      buf.put(kind.toByte()).put(0).put(0).put(0)
      for (id in cellIds) {
        buf.putInt(CellGrid.x(id).toInt())
        buf.putInt(CellGrid.y(id).toInt())
      }
      return buf.array()
    }

    /** 웹 → 앱 메시지는 `{type, ...}` JSON 문자열이다. */
    fun connect(webView: WebView, origin: Uri, onMessage: (JSONObject) -> Unit): RendererBridge {
      val (mine, theirs) = WebViewCompat.createWebMessageChannel(webView)
      mine.setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
        override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
          val text = message?.takeIf { it.type == WebMessageCompat.TYPE_STRING }?.data ?: return
          try {
            onMessage(JSONObject(text))
          } catch (e: org.json.JSONException) {
            Log.w(MistApp.TAG, "렌더러 메시지를 읽지 못함: $text", e)
          }
        }
      })
      WebViewCompat.postWebMessage(webView, WebMessageCompat("mistmap:port", arrayOf(theirs)), origin)
      return RendererBridge(mine)
    }
  }
}
