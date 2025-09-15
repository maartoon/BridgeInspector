package com.dji.bridgeinspector.Legacy

import android.util.Log
import okhttp3.*
import org.json.JSONObject

class WebSocketManager {

    private val TAG = "WebSocketManager"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    var listener: WebSocketListener? = null

    // Define an interface for callbacks
    interface WebSocketListener {
        fun onMessage(point: Pair<Float, Float>, radius: Float)
        fun onConnectionError()
    }

    fun connect(ipAddress: String) {
        if (webSocket != null) return // Already connected

        val request = Request.Builder().url("ws://$ipAddress:8000/ws").build()
        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Connection Opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "Receiving: $text")
                try {
                    val json = JSONObject(text)
                    val u = json.getDouble("u").toFloat()
                    val v = json.getDouble("v").toFloat()
                    val r = json.getDouble("radius").toFloat()
                    listener?.onMessage(Pair(u, v), r)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JSON", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Connection Failure: ${t.message}")
                listener?.onConnectionError()
                this@WebSocketManager.webSocket = null
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.i(TAG, "Closing: $code / $reason")
                this@WebSocketManager.webSocket = null
            }
        })
    }

    fun sendMessage(json: String) {
        if (webSocket != null) {
            webSocket?.send(json)
        } else {
            Log.w(TAG, "WebSocket is not connected. Message not sent. Reconnecting...")
            connect("10.194.245.135")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Activity Destroyed")
        webSocket = null
    }
}