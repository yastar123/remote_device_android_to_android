package app.linkdroid.remote

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class IncomingSession(
    val sessionId: String,
    val controllerDeviceId: String,
    val requesterEmail: String,
)

class SignalingClient(
    baseUrl: String,
    accessToken: String,
    deviceId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onSessionRequested(session: IncomingSession)
        fun onSessionEvent(type: String, sessionId: String)
        fun onError(message: String)
    }

    private val client = OkHttpClient()
    private val websocketUrl = Uri.parse(baseUrl)
        .buildUpon()
        .scheme(if (baseUrl.startsWith("https")) "wss" else "ws")
        .path("/ws")
        .appendQueryParameter("access_token", accessToken)
        .appendQueryParameter("device_id", deviceId.filter(Char::isDigit))
        .build()
        .toString()
    private var socket: WebSocket? = null

    fun connect() {
        socket = client.newWebSocket(
            Request.Builder().url(websocketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                    listener.onError(throwable.message ?: "WebSocket signaling terputus")
                }
            },
        )
    }

    fun sendSignal(sessionId: String, signalType: String, payload: JSONObject) {
        socket?.send(
            JSONObject()
                .put("type", "session.signal")
                .put("sessionId", sessionId)
                .put("signalType", signalType)
                .put("payload", payload)
                .toString(),
        )
    }

    fun close() {
        socket?.close(1000, "Activity closed")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private fun handleMessage(text: String) {
        runCatching {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "connected" -> Unit
                "session.requested" -> {
                    val requester = message.optJSONObject("requester")
                    listener.onSessionRequested(
                        IncomingSession(
                            sessionId = message.getString("sessionId"),
                            controllerDeviceId = message.getString("controllerDeviceId"),
                            requesterEmail = requester?.optString("email").orEmpty(),
                        ),
                    )
                }
                "session.approved", "session.rejected", "session.ended" -> {
                    listener.onSessionEvent(message.optString("type"), message.getJSONObject("session").getString("id"))
                }
                "error" -> listener.onError(message.optString("error", "Signaling error"))
            }
        }.onFailure { listener.onError(it.message ?: "Pesan signaling tidak valid") }
    }
}