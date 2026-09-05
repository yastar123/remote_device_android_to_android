package app.linkdroid.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.SharedPreferences

data class RemoteSession(
    val sessionId: String,
    val peerDeviceId: String,
    val role: Role,
) {
    enum class Role { CONTROLLER, RECEIVER }
}

class RemoteSessionCoordinator(
    private val preferences: SharedPreferences,
) {
    private val _session = MutableStateFlow(load())
    val session: StateFlow<RemoteSession?> = _session.asStateFlow()

    fun start(session: RemoteSession) {
        if (_session.value == session) return
        check(_session.value == null) { "A remote session is already active." }
        _session.value = session
        preferences.edit()
            .putString(KEY_SESSION_ID, session.sessionId)
            .putString(KEY_PEER_DEVICE_ID, session.peerDeviceId)
            .putString(KEY_ROLE, session.role.name)
            .apply()
    }

    fun stop() {
        _session.value = null
        preferences.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_PEER_DEVICE_ID)
            .remove(KEY_ROLE)
            .apply()
    }

    private fun load(): RemoteSession? {
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return null
        val peerDeviceId = preferences.getString(KEY_PEER_DEVICE_ID, null) ?: return null
        val role = preferences.getString(KEY_ROLE, null)
            ?.let { runCatching { RemoteSession.Role.valueOf(it) }.getOrNull() }
            ?: return null
        return RemoteSession(sessionId, peerDeviceId, role)
    }

    private companion object {
        const val KEY_SESSION_ID = "remote_session_id"
        const val KEY_PEER_DEVICE_ID = "remote_session_peer_device_id"
        const val KEY_ROLE = "remote_session_role"
    }
}