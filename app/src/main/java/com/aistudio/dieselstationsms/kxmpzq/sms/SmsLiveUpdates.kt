package com.aistudio.dieselstationsms.kxmpzq.sms

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

object LiveUpdateHub {
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    fun subscribe(listener: (String) -> Unit) { listeners += listener }
    fun unsubscribe(listener: (String) -> Unit) { listeners -= listener }
    fun publish(event: JSONObject) { val payload = event.toString(); listeners.forEach { it(payload) } }
}

class SmsLiveUpdatesClient {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    private var endpoint: String? = null
    private var reconnectAttempt = 0

    fun connect(url: String) {
        if (url.isBlank() || !url.startsWith("wss://")) return
        if (endpoint == url && socket != null) return
        disconnect(); endpoint = url
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { reconnectAttempt = 0; LiveUpdateHub.publish(JSONObject().put("type", "connection").put("status", "connected")) }
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { LiveUpdateHub.publish(JSONObject(text)) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { socket = null; LiveUpdateHub.publish(JSONObject().put("type", "connection").put("status", "closed").put("reason", reason)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { socket = null; LiveUpdateHub.publish(JSONObject().put("type", "connection").put("status", "failed")); scheduleReconnect() }
        })
    }

    fun disconnect() { socket?.close(1000, "screen closed"); socket = null }

    private fun scheduleReconnect() {
        val url = endpoint ?: return
        val delay = (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempt++
        Handler(Looper.getMainLooper()).postDelayed({ if (endpoint == url && socket == null) connect(url) }, delay)
    }
}
