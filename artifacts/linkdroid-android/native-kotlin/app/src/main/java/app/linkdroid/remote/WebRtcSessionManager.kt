package app.linkdroid.remote

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.VideoTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcSessionManager(
    private val context: Context,
    private val sessionId: String,
    private val role: RemoteSession.Role,
    private val projectionPermission: Intent?,
    backendIceServers: List<BackendIceServer>,
    private val sendSignal: (String, JSONObject) -> Unit,
    private val onStateChanged: (String) -> Unit,
    private val onRemoteVideoTrack: (VideoTrack?) -> Unit,
    private val onCommandResult: (String, Boolean, String?) -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val eglBase = EglBase.create()
    private val iceServers = buildIceServers(backendIceServers)
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var commandChannel: DataChannel? = null
    private var capture: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var captureSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var remoteTrack: VideoTrack? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    val eglContext: EglBase.Context
        get() = eglBase.eglBaseContext

    fun start() {
        if (!started.compareAndSet(false, true)) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val connection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            },
            observer,
        )
        if (connection == null) {
            onStateChanged("PeerConnection gagal dibuat")
            stop()
            return
        }
        peerConnection = connection
        if (role == RemoteSession.Role.CONTROLLER) {
            commandChannel = connection.createDataChannel("linkdroid-commands", DataChannel.Init())
            registerDataChannel(commandChannel)
        } else {
            startScreenCaptureIfPermitted()
        }
        onStateChanged("PeerConnection siap")
    }

    fun beginNegotiation() {
        if (!started.get() || role != RemoteSession.Role.CONTROLLER) return
        val connection = peerConnection ?: return
        connection.createOffer(
            sdpObserver(
                onSuccess = { description ->
                    connection.setLocalDescription(
                        sdpObserver(
                            onSuccess = {
                                sendSignal(
                                    "offer",
                                    JSONObject().put("sdp", description.description),
                                )
                            },
                            onFailure = { onStateChanged("Local SDP gagal: $it") },
                        ),
                        description,
                    )
                },
                onFailure = { onStateChanged("Offer gagal: $it") },
            ),
            MediaConstraints(),
        )
    }

    fun handleSignal(signalType: String, payload: JSONObject) {
        val connection = peerConnection ?: return
        when (signalType) {
            "offer" -> {
                if (role != RemoteSession.Role.RECEIVER) return
                val description = SessionDescription(
                    SessionDescription.Type.OFFER,
                    payload.optString("sdp"),
                )
                if (description.description.isBlank()) {
                    onStateChanged("Remote offer kosong")
                    return
                }
                connection.setRemoteDescription(
                    sdpObserver(
                        onSuccess = {
                            flushPendingIceCandidates(connection)
                            connection.createAnswer(
                                sdpObserver(
                                    onSuccess = { answer ->
                                        connection.setLocalDescription(
                                            sdpObserver(
                                                onSuccess = {
                                                    sendSignal(
                                                        "answer",
                                                        JSONObject().put("sdp", answer.description),
                                                    )
                                                },
                                                onFailure = { onStateChanged("Local answer gagal: $it") },
                                            ),
                                            answer,
                                        )
                                    },
                                    onFailure = { onStateChanged("Answer gagal: $it") },
                                ),
                                MediaConstraints(),
                            )
                        },
                        onFailure = { onStateChanged("Remote offer gagal: $it") },
                    ),
                    description,
                )
            }
            "answer" -> {
                if (role != RemoteSession.Role.CONTROLLER) return
                val description = SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp"))
                if (description.description.isBlank()) {
                    onStateChanged("Remote answer kosong")
                    return
                }
                connection.setRemoteDescription(
                    sdpObserver(
                        onSuccess = {
                            flushPendingIceCandidates(connection)
                            onStateChanged("Remote answer diterapkan")
                        },
                        onFailure = { onStateChanged("Remote answer gagal: $it") },
                    ),
                    description,
                )
            }
            "ice-candidate" -> {
                val candidate = IceCandidate(
                    payload.optString("sdpMid").takeIf { it.isNotBlank() },
                    payload.optInt("sdpMLineIndex", -1),
                    payload.optString("candidate"),
                )
                if (candidate.sdpMLineIndex >= 0 && candidate.sdp.isNotBlank()) {
                    if (connection.remoteDescription != null) {
                        connection.addIceCandidate(candidate)
                    } else {
                        pendingIceCandidates += candidate
                    }
                }
            }
        }
    }

    fun sendCommand(commandId: String, command: RemoteCommand): Boolean {
        val channel = commandChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(
            DataChannel.Buffer(
                ByteBuffer.wrap(
                    JSONObject()
                        .put("type", "command")
                        .put("commandId", commandId)
                        .put("command", command.toJson())
                        .toString()
                        .toByteArray(Charsets.UTF_8),
                ),
                false,
            ),
        )
    }

    fun sendCommandResult(commandId: String, ok: Boolean, error: String?): Boolean {
        val channel = commandChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val result = JSONObject()
            .put("type", "command.result")
            .put("commandId", commandId)
            .put("ok", ok)
        if (!error.isNullOrBlank()) result.put("error", error)
        return channel.send(
            DataChannel.Buffer(
                ByteBuffer.wrap(result.toString().toByteArray(Charsets.UTF_8)),
                false,
            ),
        )
    }

    fun attachRenderer(newRenderer: SurfaceViewRenderer) {
        if (renderer === newRenderer) {
            remoteTrack?.addSink(newRenderer)
            return
        }
        renderer?.let { previous ->
            remoteTrack?.removeSink(previous)
            previous.release()
        }
        renderer = newRenderer
        newRenderer.init(eglContext, null)
        newRenderer.setEnableHardwareScaler(true)
        newRenderer.setMirror(false)
        remoteTrack?.addSink(newRenderer)
    }

    fun stopScreenCapture() {
        capture?.let { capturer -> runCatching { capturer.stopCapture() } }
        capture?.dispose()
        capture = null
        captureHelper?.dispose()
        captureHelper = null
        captureSource?.dispose()
        captureSource = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        onStateChanged("Berbagi layar dihentikan")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        remoteTrack?.let { track -> renderer?.let(track::removeSink) }
        remoteTrack = null
        pendingIceCandidates.clear()
        onRemoteVideoTrack(null)
        renderer?.release()
        renderer = null
        commandChannel?.dispose()
        commandChannel = null
        stopScreenCapture()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        factory?.dispose()
        factory = null
        eglBase.release()
        onStateChanged("PeerConnection dihentikan")
    }

    private fun startScreenCaptureIfPermitted() {
        val permission = projectionPermission
        val activeFactory = factory
        if (permission == null || activeFactory == null) {
            onStateChanged("MediaProjection belum diberikan")
            return
        }
        val metrics = context.resources.displayMetrics
        captureHelper = SurfaceTextureHelper.create("LinkDroidCapture", eglBase.eglBaseContext)
        captureSource = activeFactory.createVideoSource(false)
        val screenCapturer = ScreenCapturerAndroid(permission, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                onStateChanged("MediaProjection dihentikan")
            }
        })
        capture = screenCapturer
        screenCapturer.initialize(
            captureHelper,
            context,
            captureSource?.capturerObserver,
        )
        runCatching {
            screenCapturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15)
        }.onFailure {
            onStateChanged("Capture layar gagal: ${it.message ?: "unknown"}")
            return
        }
        captureSource?.let { source ->
            localVideoTrack = activeFactory.createVideoTrack("linkdroid-screen", source)
        }
        localVideoTrack?.let { peerConnection?.addTrack(it) }
    }

    private fun registerDataChannel(channel: DataChannel?) {
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                onStateChanged("Data channel: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleDataMessage(JSONObject(String(bytes, Charsets.UTF_8)))
            }
        })
    }

    private fun flushPendingIceCandidates(connection: PeerConnection) {
        pendingIceCandidates.forEach(connection::addIceCandidate)
        pendingIceCandidates.clear()
    }

    private fun handleDataMessage(message: JSONObject) {
        when (message.optString("type")) {
            "command" -> {
                val command = message.optJSONObject("command")?.let(RemoteCommand::fromJson)
                val commandId = message.optString("commandId")
                if (command == null || commandId.isBlank()) {
                    sendCommandResult(commandId, false, "INVALID_COMMAND")
                } else {
                    RemoteAccessibilityService.execute(command) { ok, error ->
                        sendCommandResult(commandId, ok, error)
                    }
                }
            }
            "command.result" -> onCommandResult(
                message.optString("commandId"),
                message.optBoolean("ok", false),
                message.optString("error").takeIf { it.isNotBlank() },
            )
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            onStateChanged("ICE: ${newState.name}")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            onStateChanged("ICE gathering: ${newState.name}")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            sendSignal(
                "ice-candidate",
                JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let(::setRemoteVideoTrack)
        }

        override fun onRemoveStream(stream: MediaStream) {
            if (remoteTrack in stream.videoTracks) setRemoteVideoTrack(null)
        }

        override fun onDataChannel(dataChannel: DataChannel) {
            commandChannel = dataChannel
            registerDataChannel(dataChannel)
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            (receiver.track() as? VideoTrack)?.let(::setRemoteVideoTrack)
        }
    }

    private fun setRemoteVideoTrack(track: VideoTrack?) {
        remoteTrack?.let { current -> renderer?.let(current::removeSink) }
        remoteTrack = track
        track?.let { current -> renderer?.let(current::addSink) }
        onRemoteVideoTrack(track)
    }

    private fun sdpObserver(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess()
        override fun onSetSuccess() = onSuccess()
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetFailure(error: String) = onFailure(error)
    }

    private fun buildIceServers(backend: List<BackendIceServer>): List<PeerConnection.IceServer> =
        (backend + BackendIceServer(listOf("stun:stun.l.google.com:19302")))
            .flatMap { server ->
                server.urls.map { url ->
                    PeerConnection.IceServer.builder(url)
                        .apply {
                            if (!server.username.isNullOrBlank()) setUsername(server.username)
                            if (!server.credential.isNullOrBlank()) setPassword(server.credential)
                        }
                        .createIceServer()
                }
            }
}