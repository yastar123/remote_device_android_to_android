package app.linkdroid.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteSession(
    val sessionId: String,
    val peerDeviceId: String,
    val role: Role,
) {
    enum class Role { CONTROLLER, RECEIVER }
}

class RemoteSessionCoordinator {
    private val _session = MutableStateFlow<RemoteSession?>(null)
    val session: StateFlow<RemoteSession?> = _session.asStateFlow()

    fun start(session: RemoteSession) {
        check(_session.value == null) { "A remote session is already active." }
        _session.value = session
    }

    fun stop() {
        _session.value = null
    }
}