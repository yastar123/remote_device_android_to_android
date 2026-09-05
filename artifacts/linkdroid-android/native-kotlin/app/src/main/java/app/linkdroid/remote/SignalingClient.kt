package app.linkdroid.remote

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class IncomingSession(
    val sessionId: String,
    val controllerDeviceId: String,
    val requesterEmail: String,
)

class SignalingClient(
    baseUrl: String,
    accessToken: String,
    deviceId: String,
    private val sessionId: String? = null,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onSessionRequested(session: IncomingSession)
        fun onSessionEvent(type: String, sessionId: String)
        fun onSessionSignal(sessionId: String, fromDeviceId: String, signalType: String, payload: JSONObject) {}
        fun onRemoteCommand(sessionId: String, commandId: String, command: RemoteCommand) {}
        fun onRemoteCommandResult(sessionId: String, commandId: String, ok: Boolean, error: String?) {}
        fun onError(message: String)
    }

    private val client = OkHttpClient()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val websocketUrl = Uri.parse(baseUrl)
        .buildUpon()
        .scheme(if (baseUrl.startsWith("https")) "wss" else "ws")
        .path("/ws")
        .appendQueryParameter("access_token", accessToken)
        .appendQueryParameter("device_id", deviceId.filter(Char::isDigit))
        .build()
        .toString()
    private var socket: WebSocket? = null
    private var reconnectTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var reconnectAttempt = 0
    @Volatile private var closed = false

    @Synchronized
    fun connect() {
        closed = false
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (closed) return
        socket?.cancel()
        socket = client.newWebSocket(
            Request.Builder().url(websocketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempt = 0
                    reconnectTask?.cancel(false)
                    startPing()
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                    listener.onError(throwable.message ?: "WebSocket signaling terputus")
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    stopPing()
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (closed || reconnectTask?.isDone == false) return
        val delaySeconds = minOf(30L, 1L shl minOf(reconnectAttempt, 5))
        reconnectAttempt += 1
        reconnectTask = reconnectExecutor.schedule({ openSocket() }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun startPing() {
        stopPing()
        val activeSessionId = sessionId ?: return
        pingTask = reconnectExecutor.scheduleAtFixedRate(
            {
                socket?.send(
                    JSONObject()
                        .put("type", "session.ping")
                        .put("sessionId", activeSessionId)
                        .toString(),
                )
            },
            20,
            20,
            TimeUnit.SECONDS,
        )
    }

    private fun stopPing() {
        pingTask?.cancel(false)
        pingTask = null
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

    fun sendCommand(sessionId: String, commandId: String, command: RemoteCommand): Boolean =
        socket?.send(
            JSONObject()
                .put("type", "session.command")
                .put("sessionId", sessionId)
                .put("commandId", commandId)
                .put("command", command.toJson())
                .toString(),
        ) == true

    fun sendCommandResult(
        sessionId: String,
        commandId: String,
        ok: Boolean,
        error: String? = null,
    ): Boolean =
        socket?.send(
            JSONObject()
                .put("type", "session.command.result")
                .put("sessionId", sessionId)
                .put("commandId", commandId)
                .put("ok", ok)
                .apply { if (!error.isNullOrBlank()) put("error", error) }
                .toString(),
        ) == true

    fun close() {
        closed = true
        reconnectTask?.cancel(true)
        reconnectTask = null
        stopPing()
        socket?.close(1000, "Activity closed")
        socket = null
        reconnectExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    private fun handleMessage(text: String) {
        runCatching {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "connected", "session.pong" -> Unit
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
                "session.approved", "session.rejected", "session.ended", "session.expired", "session.active" -> {
                    val session = message.optJSONObject("session")
                    val eventSessionId = session?.optString("id").takeUnless { it.isNullOrBlank() }
                        ?: message.optString("sessionId").takeUnless { it.isNullOrBlank() }
                    if (eventSessionId == null) {
                        listener.onError("Event sesi tidak memiliki sessionId")
                    } else {
                        listener.onSessionEvent(message.optString("type"), eventSessionId)
                    }
                }
                "session.command" -> {
                    val command = message.optJSONObject("command")
                        ?.let(RemoteCommand::fromJson)
                    if (command == null) {
                        listener.onError("Perintah remote tidak valid")
                    } else {
                        listener.onRemoteCommand(
                            sessionId = message.getString("sessionId"),
                            commandId = message.getString("commandId"),
                            command = command,
                        )
                    }
                }
                "session.signal" -> {
                    listener.onSessionSignal(
                        sessionId = message.getString("sessionId"),
                        fromDeviceId = message.optString("fromDeviceId"),
                        signalType = message.getString("signalType"),
                        payload = message.optJSONObject("payload") ?: JSONObject(),
                    )
                }
                "session.command.result" -> {
                    listener.onRemoteCommandResult(
                        sessionId = message.getString("sessionId"),
                        commandId = message.getString("commandId"),
                        ok = message.optBoolean("ok", false),
                        error = message.optString("error").takeIf { it.isNotBlank() },
                    )
                }
                "error" -> listener.onError(message.optString("error", "Signaling error"))
            }
        }.onFailure { listener.onError(it.message ?: "Pesan signaling tidak valid") }
    }
}