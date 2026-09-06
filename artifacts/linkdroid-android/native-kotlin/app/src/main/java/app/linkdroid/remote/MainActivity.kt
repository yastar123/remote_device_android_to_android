package app.linkdroid.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class PendingSessionSignal(
    val fromDeviceId: String,
    val signalType: String,
    val payload: org.json.JSONObject,
)

class MainActivity : ComponentActivity() {
    private val secureTokenStore by lazy { SecureTokenStore(applicationContext) }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureTokenStore.migrateLegacy(getPreferences(MODE_PRIVATE))
        if (Build.VERSION.SDK_INT >= 33 &&
            getPreferences(MODE_PRIVATE).getBoolean("notifications", true)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            LinkDroidTheme { LinkDroidApp() }
        }
    }

    @Composable
    private fun LinkDroidApp() {
        var email by rememberSaveable {
            mutableStateOf(getPreferences(MODE_PRIVATE).getString("email", null))
        }
        var accessToken by remember {
            mutableStateOf(secureTokenStore.accessToken())
        }
        var refreshToken by remember {
            mutableStateOf(secureTokenStore.refreshToken())
        }
        var role by rememberSaveable {
            mutableStateOf(
                getPreferences(MODE_PRIVATE).getString("role", UserRole.ADMIN.name)
                    ?.let { runCatching { UserRole.valueOf(it) }.getOrDefault(UserRole.ADMIN) }
                    ?: UserRole.ADMIN,
            )
        }
        var screen by rememberSaveable {
            mutableStateOf(if (email == null || accessToken == null) AppScreen.LOGIN else AppScreen.HOME)
        }
        var activeSession by rememberSaveable { mutableStateOf<String?>(null) }
        var message by rememberSaveable { mutableStateOf<String?>(null) }
        var isScreenSharing by rememberSaveable { mutableStateOf(false) }
        var notificationsEnabled by rememberSaveable {
            mutableStateOf(getPreferences(MODE_PRIVATE).getBoolean("notifications", true))
        }
        var registrationStatus by rememberSaveable { mutableStateOf("Belum didaftarkan ke server") }
        var authBusy by rememberSaveable { mutableStateOf(false) }
        var authError by rememberSaveable { mutableStateOf<String?>(null) }
        var submittingTask by rememberSaveable { mutableStateOf(false) }
        var submittedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingIncomingSession by remember { mutableStateOf<IncomingSession?>(null) }
        val pendingSessionSignals = remember { mutableMapOf<String, MutableList<PendingSessionSignal>>() }
        var adminTasks by remember { mutableStateOf<List<BackendTaskSummary>>(emptyList()) }
        val registrationScope = rememberCoroutineScope()
        val currentDeviceId = remember { getOrCreateDeviceId() }
        var devices by remember(currentDeviceId) { mutableStateOf(loadDevices(currentDeviceId)) }
        suspend fun <T> withAuthenticatedApi(operation: suspend (String) -> T): T {
            val currentAccessToken = accessToken
                ?: throw IllegalStateException("Login server diperlukan.")
            val currentRefreshToken = refreshToken
                ?: throw IllegalStateException("Sesi login sudah tidak dapat diperbarui. Silakan login kembali.")
            val result = BackendApiClient.withAutoRefresh(
                baseUrl = BuildConfig.BACKEND_BASE_URL,
                accessToken = currentAccessToken,
                refreshToken = currentRefreshToken,
                operation = operation,
            )
            if (result.accessToken != currentAccessToken || result.refreshToken != currentRefreshToken) {
                secureTokenStore.saveTokens(result.accessToken, result.refreshToken)
                accessToken = result.accessToken
                refreshToken = result.refreshToken
            }
            return result.value
        }
        val refreshRegisteredDevices: () -> Unit = {
            val token = accessToken
            if (!token.isNullOrBlank()) {
                registrationScope.launch {
                    runCatching {
                        withAuthenticatedApi { currentToken ->
                            BackendApiClient.listDevices(
                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                accessToken = currentToken,
                            )
                        }
                    }.onSuccess { serverDevices ->
                        val synchronized = serverDevices.map { device ->
                            Device(
                                id = formatDeviceId(device.deviceId),
                                name = device.name,
                                platform = listOfNotNull(device.androidVersion, device.appVersion?.let { "app $it" })
                                    .joinToString(" • ")
                                    .ifBlank { "Android" },
                                online = isRecentlySeen(device.lastSeenAt),
                            )
                        }.toMutableList()
                        if (synchronized.none { it.id == currentDeviceId }) {
                            synchronized += Device(currentDeviceId, "Perangkat ini", "Android • Perangkat ini", true)
                        }
                        devices = synchronized
                            .distinctBy { it.id }
                            .map { if (it.id == currentDeviceId) it.copy(online = true) else it }
                        saveDevices(devices)
                    }
                }
            }
        }
        var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
        var submittedCustomer by remember { mutableStateOf<CustomerData?>(null) }
        var customerDraft by remember { mutableStateOf(loadCustomerDraft()) }
        var adminAuditLogs by remember { mutableStateOf<List<BackendAuditLogSummary>>(emptyList()) }
        val sessionCoordinator = remember {
            RemoteSessionCoordinator(getPreferences(MODE_PRIVATE))
        }
        val activeRemoteSession by sessionCoordinator.session.collectAsState()
        var sendRemoteCommand by remember { mutableStateOf<((RemoteCommand) -> Boolean)?>(null) }
        var projectionPermission by remember { mutableStateOf<Intent?>(null) }
        var backendIceServers by remember { mutableStateOf<List<BackendIceServer>>(emptyList()) }
        var webRtcSessionManager by remember { mutableStateOf<WebRtcSessionManager?>(null) }
        LaunchedEffect(activeRemoteSession?.sessionId) {
            val restoredSession = activeRemoteSession
            if (restoredSession != null && screen == AppScreen.HOME) {
                activeSession = restoredSession.peerDeviceId
                screen = AppScreen.SESSION
                message = "Sesi sebelumnya dipulihkan. Verifikasi status sesi dengan server."
            }
        }
        LaunchedEffect(accessToken, refreshToken) {
            if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                runCatching {
                    withAuthenticatedApi { token ->
                        BackendApiClient.listIceServers(
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            accessToken = token,
                        )
                    }
                }.onSuccess { backendIceServers = it }
                    .onFailure { backendIceServers = emptyList() }
            } else {
                backendIceServers = emptyList()
            }
        }
        val registerCurrentDevice: () -> Unit = {
            val registeredEmail = email
            if (registeredEmail.isNullOrBlank() || accessToken.isNullOrBlank()) {
                registrationStatus = "Login server diperlukan untuk mendaftarkan device."
            } else {
                registrationStatus = "Sedang mendaftarkan device ke server..."
                registrationScope.launch {
                    runCatching {
                        withAuthenticatedApi { token ->
                            BackendApiClient.registerDevice(
                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                accessToken = token,
                                deviceId = currentDeviceId,
                                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                                appVersion = BuildConfig.VERSION_NAME,
                            )
                        }
                    }.onSuccess {
                        registrationStatus = "Device sudah terdaftar di server."
                        refreshRegisteredDevices()
                    }.onFailure { error ->
                        registrationStatus = "Pendaftaran gagal: ${error.message ?: "server tidak dapat dihubungi"}"
                    }
                }
            }
        }
        val loadAdminTasks: () -> Unit = {
            if (role == UserRole.ADMIN && !accessToken.isNullOrBlank()) {
                registrationScope.launch {
                    runCatching {
                        withAuthenticatedApi { token ->
                            BackendApiClient.listTasks(
                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                accessToken = token,
                            )
                        }
                    }.onSuccess { adminTasks = it }
                        .onFailure { message = "Data tugas gagal dimuat: ${it.message ?: "server tidak dapat dihubungi"}" }
                    runCatching {
                        withAuthenticatedApi { token ->
                            BackendApiClient.listAuditLogs(
                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                accessToken = token,
                            )
                        }
                    }.onSuccess { adminAuditLogs = it }
                }
            }
        }
        LaunchedEffect(email, currentDeviceId, accessToken) {
            if (!email.isNullOrBlank() && !accessToken.isNullOrBlank()) registerCurrentDevice()
        }
        LaunchedEffect(currentDeviceId, accessToken, refreshToken) {
            val token = accessToken
            if (!token.isNullOrBlank()) {
                while (isActive) {
                    runCatching {
                        withAuthenticatedApi { currentToken ->
                            BackendApiClient.heartbeat(
                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                accessToken = currentToken,
                                deviceId = currentDeviceId,
                            )
                        }
                    }
                    delay(30_000)
                }
            }
        }
        LaunchedEffect(role, accessToken, screen) {
            if (role == UserRole.ADMIN && screen == AppScreen.HOME) loadAdminTasks()
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accessibilityEnabled = isAccessibilityServiceEnabled()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        DisposableEffect(accessToken, currentDeviceId, activeRemoteSession?.sessionId, notificationsEnabled) {
            if (accessToken.isNullOrBlank() || activeRemoteSession != null) {
                onDispose { }
            } else {
                val incomingSignaling = SignalingClient(
                    baseUrl = BuildConfig.BACKEND_BASE_URL,
                    accessToken = accessToken.orEmpty(),
                    deviceId = currentDeviceId,
                    listener = object : SignalingClient.Listener {
                        override fun onConnected() {
                            runOnUiThread { message = "Menunggu permintaan monitoring." }
                        }

                        override fun onSessionRequested(session: IncomingSession) {
                            runOnUiThread {
                                pendingIncomingSession = session
                                message = "Permintaan monitoring baru dari ${session.requesterEmail}."
                                if (notificationsEnabled) {
                                    showSessionNotification("Permintaan monitoring dari ${session.requesterEmail}.")
                                }
                            }
                        }

                        override fun onSessionEvent(type: String, sessionId: String) {
                            if (type == "session.ended" || type == "session.expired") {
                                runOnUiThread {
                                    pendingIncomingSession = null
                                    synchronized(pendingSessionSignals) {
                                        pendingSessionSignals.remove(sessionId)
                                    }
                                }
                            }
                        }

                        override fun onSessionSignal(
                            sessionId: String,
                            fromDeviceId: String,
                            signalType: String,
                            payload: org.json.JSONObject,
                        ) {
                            synchronized(pendingSessionSignals) {
                                val signals = pendingSessionSignals[sessionId] ?: mutableListOf()
                                if (signals.size < 64) {
                                    signals += PendingSessionSignal(fromDeviceId, signalType, payload)
                                    pendingSessionSignals[sessionId] = signals
                                }
                            }
                        }

                        override fun onError(messageText: String) {
                            runOnUiThread { message = "Signaling: $messageText" }
                        }
                    },
                )
                incomingSignaling.connect()
                onDispose { incomingSignaling.close() }
            }
        }
        DisposableEffect(
            accessToken,
            currentDeviceId,
            activeRemoteSession?.sessionId,
            projectionPermission,
            backendIceServers,
            notificationsEnabled,
        ) {
            val token = accessToken
            val currentSession = activeRemoteSession
            if (
                token.isNullOrBlank() ||
                currentSession == null ||
                (currentSession.role == RemoteSession.Role.RECEIVER && projectionPermission == null)
            ) {
                onDispose { }
            } else {
                lateinit var webRtc: WebRtcSessionManager
                lateinit var signaling: SignalingClient
                var signalingConnected = false
                var sessionApproved = false
                fun beginNegotiationWhenReady() {
                    if (signalingConnected && sessionApproved) webRtc.beginNegotiation()
                }
                webRtc = WebRtcSessionManager(
                    context = this@MainActivity,
                    sessionId = currentSession.sessionId,
                    role = currentSession.role,
                    projectionPermission = projectionPermission.takeIf {
                        currentSession.role == RemoteSession.Role.RECEIVER
                    },
                    backendIceServers = backendIceServers,
                    sendSignal = { signalType, payload ->
                        signaling.sendSignal(currentSession.sessionId, signalType, payload)
                    },
                    onStateChanged = { state ->
                        runOnUiThread { message = "Media: $state" }
                    },
                    onRemoteVideoTrack = {
                        runOnUiThread { message = "Media: video track tersedia." }
                    },
                    onCommandResult = { _, ok, error ->
                        runOnUiThread {
                            message = if (ok) {
                                "Perintah WebRTC berhasil dijalankan."
                            } else {
                                "Perintah WebRTC gagal: ${error ?: "receiver menolak perintah"}"
                            }
                        }
                    },
                )
                signaling = SignalingClient(
                    baseUrl = BuildConfig.BACKEND_BASE_URL,
                    accessToken = token,
                    deviceId = currentDeviceId,
                    sessionId = currentSession.sessionId,
                    listener = object : SignalingClient.Listener {
                        override fun onConnected() {
                            signalingConnected = true
                            runOnUiThread { message = "Signaling server tersambung." }
                            beginNegotiationWhenReady()
                        }

                        override fun onSessionRequested(session: IncomingSession) {
                            runOnUiThread {
                                pendingIncomingSession = session
                                message = "Permintaan monitoring baru dari ${session.requesterEmail}."
                                if (notificationsEnabled) {
                                    showSessionNotification("Permintaan monitoring dari ${session.requesterEmail}.")
                                }
                            }
                        }

                        override fun onSessionEvent(type: String, sessionId: String) {
                            runOnUiThread {
                                if (type == "session.approved" || type == "session.active") {
                                    sessionApproved = true
                                    message = "Permintaan monitoring disetujui petugas."
                                    if (notificationsEnabled) {
                                        showSessionNotification("Sesi monitoring aktif.")
                                    }
                                    beginNegotiationWhenReady()
                                } else if (type == "session.rejected") {
                                    message = "Permintaan monitoring ditolak petugas."
                                    cancelSessionNotification()
                                    activeSession = null
                                } else if (type == "session.ended" || type == "session.expired") {
                                    message = "Sesi monitoring telah dihentikan."
                                    cancelSessionNotification()
                                    activeSession = null
                                    sessionCoordinator.stop()
                                }
                            }
                        }

                        override fun onSessionSignal(
                            sessionId: String,
                            fromDeviceId: String,
                            signalType: String,
                            payload: org.json.JSONObject,
                        ) {
                            if (sessionId == currentSession.sessionId) {
                                webRtc.handleSignal(signalType, payload)
                            }
                        }

                        override fun onRemoteCommand(sessionId: String, commandId: String, command: RemoteCommand) {
                            RemoteAccessibilityService.execute(command) { ok, error ->
                                signaling.sendCommandResult(
                                    sessionId = sessionId,
                                    commandId = commandId,
                                    ok = ok,
                                    error = error,
                                )
                            }
                        }

                        override fun onRemoteCommandResult(
                            sessionId: String,
                            commandId: String,
                            ok: Boolean,
                            error: String?,
                        ) {
                            runOnUiThread {
                                message = if (ok) {
                                    "Perintah remote berhasil dijalankan."
                                } else {
                                    "Perintah remote gagal: ${error ?: "perangkat menolak perintah"}"
                                }
                            }
                        }

                        override fun onError(messageText: String) {
                            runOnUiThread { message = "Signaling: $messageText" }
                        }
                    },
                )
                sendRemoteCommand = { command ->
                    if (currentSession.role != RemoteSession.Role.CONTROLLER) {
                        false
                    } else {
                        val commandId = java.util.UUID.randomUUID().toString()
                        webRtc.sendCommand(commandId, command) ||
                            signaling.sendCommand(
                                sessionId = currentSession.sessionId,
                                commandId = commandId,
                                command = command,
                            )
                    }
                }
                webRtcSessionManager = webRtc
                runCatching { webRtc.start() }
                    .onFailure { error ->
                        message = "WebRTC gagal dimulai: ${error.message ?: "periksa perangkat dan izin layar"}"
                        webRtcSessionManager = null
                    }
                signaling.connect()
                val bufferedSignals = synchronized(pendingSessionSignals) {
                    pendingSessionSignals.remove(currentSession.sessionId).orEmpty()
                }
                bufferedSignals.forEach { signal ->
                    webRtc.handleSignal(signal.signalType, signal.payload)
                }
                onDispose {
                    sendRemoteCommand = null
                    webRtc.stop()
                    webRtcSessionManager = null
                    signaling.close()
                }
            }
        }
        val captureLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                    putExtra(
                        ScreenCaptureService.EXTRA_WEBRTC_CAPTURE,
                        activeRemoteSession?.role == RemoteSession.Role.RECEIVER,
                    )
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                projectionPermission = result.data
                isScreenSharing = true
                message = "Berbagi layar aktif dan menunggu koneksi."
            } else {
                message = "Izin berbagi layar belum diberikan."
            }
        }
        val requestScreenShare = {
            if (isScreenSharing) {
                message = "Berbagi layar sudah aktif."
            } else {
                val manager = getSystemService(MediaProjectionManager::class.java)
                captureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
        val startRemoteSession: (String) -> Unit = { id ->
            val normalized = id.filter(Char::isDigit)
            when {
                normalized.length != 9 -> {
                    message = "Masukkan ID perangkat 9 digit."
                }

                accessToken.isNullOrBlank() -> {
                    message = "Login server diperlukan sebelum memulai monitoring."
                }

                activeRemoteSession != null -> {
                    message = "Akhiri sesi aktif sebelum memulai sesi baru."
                }

                else -> {
                    message = "Mengirim permintaan monitoring ke device petugas..."
                    registrationScope.launch {
                        runCatching {
                                withAuthenticatedApi { token ->
                                    BackendApiClient.listSessions(
                                        baseUrl = BuildConfig.BACKEND_BASE_URL,
                                        accessToken = token,
                                    ).filter { it.status in setOf("REQUESTED", "APPROVED", "ACTIVE") }
                                        .forEach { session ->
                                            BackendApiClient.endSession(
                                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                                accessToken = token,
                                                sessionId = session.id,
                                            )
                                        }
                                    sessionCoordinator.stop()
                                    BackendApiClient.createSession(
                                        baseUrl = BuildConfig.BACKEND_BASE_URL,
                                        accessToken = token,
                                        controllerDeviceId = currentDeviceId,
                                        receiverDeviceId = normalized,
                                    )
                                }
                        }.onSuccess { remoteSession ->
                            sessionCoordinator.start(
                                RemoteSession(
                                    sessionId = remoteSession.id,
                                    peerDeviceId = normalized,
                                    role = RemoteSession.Role.CONTROLLER,
                                ),
                            )
                            activeSession = normalized
                            screen = AppScreen.SESSION
                            message = "Permintaan sesi dikirim. Menunggu persetujuan petugas."
                            if (notificationsEnabled) {
                                showSessionNotification("Menunggu persetujuan perangkat ${formatDeviceId(normalized)}.")
                            }
                        }.onFailure { error ->
                            message = "Monitoring gagal: ${error.message ?: "server tidak dapat dihubungi"}"
                        }
                    }
                }
            }
        }

        if (screen == AppScreen.LOGIN) {
            LoginScreen(
                isLoading = authBusy,
                serverError = authError,
                onLogin = { enteredEmail, password, selectedRole, isRegistering, adminInviteCode ->
                    authBusy = true
                    authError = null
                    registrationScope.launch {
                        runCatching {
                            if (isRegistering) {
                                BackendApiClient.register(
                                    baseUrl = BuildConfig.BACKEND_BASE_URL,
                                    email = enteredEmail,
                                    password = password,
                                    role = selectedRole,
                                    adminInviteCode = adminInviteCode,
                                )
                            } else {
                                try {
                                    BackendApiClient.login(
                                        baseUrl = BuildConfig.BACKEND_BASE_URL,
                                        email = enteredEmail,
                                        password = password,
                                    )
                                } catch (error: BackendHttpException) {
                                    if (
                                        error.statusCode == 401 &&
                                        enteredEmail.equals("demo@linkdroid.app", ignoreCase = true) &&
                                        password == "linkdroid"
                                    ) {
                                        BackendApiClient.register(
                                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                                            email = "demo@linkdroid.app",
                                            password = "linkdroid",
                                            role = UserRole.WORKER,
                                            adminInviteCode = null,
                                        )
                                    } else {
                                        throw error
                                    }
                                }
                            }
                        }.onSuccess { result ->
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("email", result.email)
                                .putString("role", result.role.name)
                                .apply()
                            secureTokenStore.saveTokens(result.accessToken, result.refreshToken)
                            email = result.email
                            role = result.role
                            accessToken = result.accessToken
                            refreshToken = result.refreshToken
                            authBusy = false
                            screen = AppScreen.HOME
                        }.onFailure { error ->
                            authBusy = false
                            authError = error.message ?: "Autentikasi ke server gagal."
                        }
                    }
                },
            )
            return
        }

        Scaffold(
            containerColor = LinkDroidColors.background,
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = Color.White,
                ) {
                    val destinations = if (role == UserRole.ADMIN) {
                        listOf(
                            AppScreen.HOME to "Monitoring",
                            AppScreen.DEVICES to "Perangkat",
                            AppScreen.SETTINGS to "Pengaturan",
                        )
                    } else {
                        listOf(
                            AppScreen.HOME to "Tugas",
                            AppScreen.SETTINGS to "Pengaturan",
                        )
                    }
                    destinations.forEach { (destination, label) ->
                        NavigationBarItem(
                            selected = screen == destination,
                            onClick = { screen = destination },
                            icon = { Text(if (destination == AppScreen.HOME) "⌂" else if (destination == AppScreen.DEVICES) "▣" else "⚙") },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (screen) {
                AppScreen.HOME -> if (role == UserRole.ADMIN) {
                    AdminDashboardScreen(
                        email = email.orEmpty(),
                        activeSession = activeSession,
                        message = message,
                        tasks = adminTasks,
                        auditLogs = adminAuditLogs,
                        onConnect = startRemoteSession,
                        onRefreshTasks = loadAdminTasks,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    WorkerHomeScreen(
                        email = email.orEmpty(),
                        deviceId = currentDeviceId,
                        submittedCustomer = submittedCustomer,
                        hasCustomerDraft = customerDraft != null,
                        activeSession = activeSession,
                        pendingSession = pendingIncomingSession,
                        onStartForm = { screen = AppScreen.CUSTOMER_FORM },
                        onContinue = {
                            screen = if (submittedCustomer != null) AppScreen.PLN_MOBILE else AppScreen.CUSTOMER_FORM
                        },
                        onShareId = { shareDeviceId(currentDeviceId) },
                        onApproveSession = { incoming ->
                            val token = accessToken
                            if (!token.isNullOrBlank()) {
                                registrationScope.launch {
                                    runCatching {
                                        withAuthenticatedApi { currentToken ->
                                            BackendApiClient.approveSession(
                                                BuildConfig.BACKEND_BASE_URL,
                                                currentToken,
                                                incoming.sessionId,
                                            )
                                        }
                                    }.onSuccess {
                                        pendingIncomingSession = null
                                        webRtcSessionManager?.stop()
                                        webRtcSessionManager = null
                                        sessionCoordinator.stop()
                                        sessionCoordinator.start(
                                            RemoteSession(
                                                sessionId = incoming.sessionId,
                                                peerDeviceId = incoming.controllerDeviceId,
                                                role = RemoteSession.Role.RECEIVER,
                                            ),
                                        )
                                        activeSession = incoming.controllerDeviceId
                                        message = "Monitoring disetujui. Menunggu koneksi media."
                                        requestScreenShare()
                                    }.onFailure { error ->
                                        message = "Persetujuan gagal: ${error.message ?: "server tidak dapat dihubungi"}"
                                    }
                                }
                            }
                        },
                        onRejectSession = { incoming ->
                            val token = accessToken
                            if (!token.isNullOrBlank()) {
                                registrationScope.launch {
                                    runCatching {
                                        withAuthenticatedApi { currentToken ->
                                            BackendApiClient.rejectSession(
                                                BuildConfig.BACKEND_BASE_URL,
                                                currentToken,
                                                incoming.sessionId,
                                            )
                                        }
                                    }.onSuccess {
                                        pendingIncomingSession = null
                                        message = "Permintaan monitoring ditolak."
                                    }.onFailure { error ->
                                        message = "Penolakan gagal: ${error.message ?: "server tidak dapat dihubungi"}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(padding),
                    )
                }

                AppScreen.CUSTOMER_FORM -> CustomerFormScreen(
                    initialData = customerDraft ?: submittedCustomer,
                    onBack = { screen = AppScreen.HOME },
                    onDraftChanged = {
                        customerDraft = it
                        saveCustomerDraft(it)
                    },
                    onSubmit = {
                        if (accessToken.isNullOrBlank()) {
                            message = "Login server diperlukan sebelum menyimpan data pelanggan."
                        } else {
                            submittingTask = true
                            message = "Menyimpan data pelanggan ke server..."
                            registrationScope.launch {
                                runCatching {
                                    withAuthenticatedApi { token ->
                                        BackendApiClient.createCustomerTask(
                                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                                            accessToken = token,
                                            workerDeviceId = currentDeviceId,
                                            customer = it,
                                        )
                                    }
                                }.onSuccess { taskId ->
                                    submittedCustomer = it
                                    customerDraft = null
                                    clearCustomerDraft()
                                    submittedTaskId = taskId
                                    runCatching {
                                        withAuthenticatedApi { token ->
                                            BackendApiClient.updateTaskStatus(
                                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                                accessToken = token,
                                                taskId = taskId,
                                                status = "PLN_MOBILE",
                                            )
                                        }
                                    }
                                    submittingTask = false
                                    message = "Data pelanggan tersimpan dan dikirim ke server."
                                    screen = AppScreen.PLN_MOBILE
                                }.onFailure { error ->
                                    submittingTask = false
                                    message = "Data belum tersimpan: ${error.message ?: "server tidak dapat dihubungi"}"
                                }
                            }
                        }
                    },
                    isSubmitting = submittingTask,
                    modifier = Modifier.padding(padding),
                )

                AppScreen.PLN_MOBILE -> PlnMobileScreen(
                    customer = submittedCustomer,
                    isScreenSharing = isScreenSharing,
                    message = message,
                    onBack = { screen = AppScreen.HOME },
                    onShareScreen = requestScreenShare,
                    onStopSharing = {
                        webRtcSessionManager?.stopScreenCapture()
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        isScreenSharing = false
                        message = "Pemantauan layar dihentikan."
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.DEVICES -> DevicesScreen(
                    devices = devices,
                    currentDeviceId = currentDeviceId,
                    onConnect = startRemoteSession,
                    onAdd = { device ->
                        if (devices.any { it.id == device.id }) {
                            message = "ID ${device.id} sudah ada di daftar perangkat."
                        } else {
                            devices = devices + device
                            saveDevices(devices)
                            message = "${device.name} ditambahkan."
                        }
                    },
                    onRemove = { device ->
                        devices = devices.filterNot { it.id == device.id }
                        saveDevices(devices)
                        message = "${device.name} dihapus dari daftar."
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.SESSION -> SessionScreen(
                    deviceId = activeSession.orEmpty(),
                    isController = activeRemoteSession?.role == RemoteSession.Role.CONTROLLER,
                    webRtcSessionManager = webRtcSessionManager,
                    message = message,
                    isScreenSharing = isScreenSharing,
                    onShareScreen = requestScreenShare,
                    onStopSharing = {
                        webRtcSessionManager?.stopScreenCapture()
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        isScreenSharing = false
                        message = "Berbagi layar dihentikan."
                    },
                    onStop = {
                            val serverSessionId = activeRemoteSession?.sessionId
                            if (!serverSessionId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
                                registrationScope.launch {
                                    runCatching {
                                        withAuthenticatedApi { token ->
                                            BackendApiClient.endSession(
                                                BuildConfig.BACKEND_BASE_URL,
                                                token,
                                                serverSessionId,
                                            )
                                        }
                                    }
                                }
                            }
                        webRtcSessionManager?.stopScreenCapture()
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        sessionCoordinator.stop()
                        cancelSessionNotification()
                        isScreenSharing = false
                        activeSession = null
                        message = "Sesi remote diakhiri."
                        screen = AppScreen.HOME
                    },
                    onCommand = { command ->
                        if (sendRemoteCommand?.invoke(command) != true) {
                            message = "Perintah belum dapat dikirim; pastikan sesi aktif."
                        }
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    email = email.orEmpty(),
                    backendBaseUrl = BuildConfig.BACKEND_BASE_URL,
                    registrationStatus = registrationStatus,
                    onRegisterDevice = registerCurrentDevice,
                    notificationsEnabled = notificationsEnabled,
                    accessibilityEnabled = accessibilityEnabled,
                    isScreenSharing = isScreenSharing,
                    onNotificationsChanged = {
                        notificationsEnabled = it
                        getPreferences(MODE_PRIVATE).edit().putBoolean("notifications", it).apply()
                        if (it) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            cancelSessionNotification()
                        }
                    },
                    onAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onScreenShare = requestScreenShare,
                    onStopSharing = {
                        webRtcSessionManager?.stopScreenCapture()
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        isScreenSharing = false
                        message = "Berbagi layar dihentikan."
                    },
                    onLogout = {
                        val logoutToken = accessToken
                        if (!logoutToken.isNullOrBlank()) {
                            registrationScope.launch {
                                runCatching {
                                    BackendApiClient.logout(BuildConfig.BACKEND_BASE_URL, logoutToken)
                                }
                            }
                        }
                        webRtcSessionManager?.stopScreenCapture()
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        sessionCoordinator.stop()
                        cancelSessionNotification()
                        isScreenSharing = false
                        getPreferences(MODE_PRIVATE).edit()
                            .remove("email")
                            .remove("role")
                            .apply()
                        secureTokenStore.clearTokens()
                        clearCustomerDraft()
                        email = null
                        accessToken = null
                        refreshToken = null
                        role = UserRole.ADMIN
                        activeSession = null
                        submittedCustomer = null
                        customerDraft = null
                        submittedTaskId = null
                        submittingTask = false
                        pendingIncomingSession = null
                        adminTasks = emptyList()
                        message = null
                        registrationStatus = "Belum didaftarkan ke server"
                        screen = AppScreen.LOGIN
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.LOGIN -> Unit
            }
        }
    }

    private fun shareDeviceId(deviceId: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Hubungkan ke perangkat LinkDroid saya dengan ID $deviceId.")
                },
                "Bagikan ID perangkat",
            ),
        )
    }

    private fun showSessionNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_NOTIFICATION_CHANNEL,
                "Status sesi LinkDroid",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.notify(
            SESSION_NOTIFICATION_ID,
            Notification.Builder(this, SESSION_NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Sesi LinkDroid")
                .setContentText(message)
                .setOngoing(true)
                .build(),
        )
    }

    private fun cancelSessionNotification() {
        getSystemService(NotificationManager::class.java).cancel(SESSION_NOTIFICATION_ID)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.packageName == packageName &&
                    serviceInfo.name == RemoteAccessibilityService::class.java.name
            }
    }

    private fun getOrCreateDeviceId(): String {
        val preferences = getPreferences(MODE_PRIVATE)
        preferences.getString("device_id", null)?.let { return formatDeviceId(it) }
        val generated = java.security.SecureRandom().nextInt(900_000_000) + 100_000_000
        val deviceId = formatDeviceId(generated.toString())
        preferences.edit().putString("device_id", deviceId).apply()
        return deviceId
    }

    private fun loadDevices(currentDeviceId: String): List<Device> {
        val saved = getPreferences(MODE_PRIVATE).getStringSet("devices", null).orEmpty()
        if (saved.isEmpty()) return defaultDevices(currentDeviceId)
        return saved.mapNotNull { encoded ->
            val values = encoded.split("~")
            if (values.size != 4) null else Device(values[0], values[1], values[2], values[3] == "1")
        }.map { device ->
            if (device.platform.contains("Perangkat ini")) device.copy(id = currentDeviceId) else device
        }.ifEmpty { defaultDevices(currentDeviceId) }
    }

    private fun saveDevices(devices: List<Device>) {
        getPreferences(MODE_PRIVATE).edit().putStringSet(
            "devices",
            devices.map {
                val safeName = it.name.replace("~", " ")
                val safePlatform = it.platform.replace("~", " ")
                "${it.id}~$safeName~$safePlatform~${if (it.online) "1" else "0"}"
            }.toSet(),
        ).apply()
    }

    private fun loadCustomerDraft(): CustomerData? {
        val preferences = getPreferences(MODE_PRIVATE)
        if (!preferences.contains("draft_full_name") &&
            !preferences.contains("draft_meter_id") &&
            !preferences.contains("draft_address")
        ) {
            return null
        }
        return CustomerData(
            fullName = preferences.getString("draft_full_name", "").orEmpty(),
            meterId = preferences.getString("draft_meter_id", "").orEmpty(),
            address = preferences.getString("draft_address", "").orEmpty(),
            village = preferences.getString("draft_village", "").orEmpty(),
            district = preferences.getString("draft_district", "").orEmpty(),
            city = preferences.getString("draft_city", "").orEmpty(),
            province = preferences.getString("draft_province", "Pilih provinsi").orEmpty(),
        )
    }

    private fun saveCustomerDraft(draft: CustomerData) {
        getPreferences(MODE_PRIVATE).edit()
            .putString("draft_full_name", draft.fullName)
            .putString("draft_meter_id", draft.meterId)
            .putString("draft_address", draft.address)
            .putString("draft_village", draft.village)
            .putString("draft_district", draft.district)
            .putString("draft_city", draft.city)
            .putString("draft_province", draft.province)
            .apply()
    }

    private fun clearCustomerDraft() {
        getPreferences(MODE_PRIVATE).edit()
            .remove("draft_full_name")
            .remove("draft_meter_id")
            .remove("draft_address")
            .remove("draft_village")
            .remove("draft_district")
            .remove("draft_city")
            .remove("draft_province")
            .apply()
    }

    companion object {
        private const val SESSION_NOTIFICATION_CHANNEL = "linkdroid-session-status"
        private const val SESSION_NOTIFICATION_ID = 1002
    }
}

private enum class AppScreen { LOGIN, HOME, CUSTOMER_FORM, PLN_MOBILE, DEVICES, SESSION, SETTINGS }

enum class UserRole { ADMIN, WORKER }

private const val DEMO_ADMIN_EMAIL = "demo.admin@linkdroid.app"
private const val DEMO_ADMIN_PASSWORD = "LinkDroidAdmin2026!"
private const val DEMO_WORKER_EMAIL = "demo.worker@linkdroid.app"
private const val DEMO_WORKER_PASSWORD = "LinkDroidWorker2026!"

private data class Device(val id: String, val name: String, val platform: String, val online: Boolean)

data class CustomerData(
    val fullName: String,
    val meterId: String,
    val address: String,
    val village: String,
    val district: String,
    val city: String,
    val province: String,
)

private fun defaultDevices(currentDeviceId: String) = listOf(
    Device(currentDeviceId, "Perangkat ini", "Android • Perangkat ini", true),
)

private fun isRecentlySeen(lastSeenAt: String?): Boolean =
    lastSeenAt?.let {
        runCatching {
            java.time.Instant.parse(it).isAfter(java.time.Instant.now().minusSeconds(120))
        }.getOrDefault(false)
    } ?: false

private object LinkDroidColors {
    val background = Color(0xFFF7F9FC)
    val primary = Color(0xFF1769E0)
    val navy = Color(0xFF152238)
    val muted = Color(0xFF637083)
    val border = Color(0xFFE1E7F0)
    val success = Color(0xFF2B9B65)
}

@Composable
private fun LinkDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = LinkDroidColors.primary,
            background = LinkDroidColors.background,
            surface = Color.White,
            onSurface = LinkDroidColors.navy,
        ),
        content = content,
    )
}

@Composable
private fun LoginScreen(
    isLoading: Boolean,
    serverError: String?,
    onLogin: (String, String, UserRole, Boolean, String?) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRole by rememberSaveable { mutableStateOf(UserRole.ADMIN) }
    var isRegistering by rememberSaveable { mutableStateOf(false) }
    var adminInviteCode by rememberSaveable { mutableStateOf("") }

    Surface(color = LinkDroidColors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Logo()
            Spacer(Modifier.height(16.dp))
            Text("LinkDroid", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Remote support, made simple.", color = LinkDroidColors.muted)
            Spacer(Modifier.height(32.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                     Text(
                         if (isRegistering) "Buat akun LinkDroid" else "Masuk ke LinkDroid",
                         style = MaterialTheme.typography.titleLarge,
                         fontWeight = FontWeight.Bold,
                     )
                     Text(
                         if (isRegistering) "Buat akun server untuk mulai menggunakan aplikasi."
                         else "Masuk untuk mengakses perangkat Android Anda.",
                         color = LinkDroidColors.muted,
                     )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Kata sandi") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                     if (isRegistering) {
                         Text("Daftar sebagai", fontWeight = FontWeight.Medium)
                         Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                             OutlinedButton(
                                 onClick = { selectedRole = UserRole.ADMIN },
                                 modifier = Modifier.weight(1f),
                             ) {
                                 Text(if (selectedRole == UserRole.ADMIN) "✓ Admin" else "Admin")
                             }
                             OutlinedButton(
                                 onClick = { selectedRole = UserRole.WORKER },
                                 modifier = Modifier.weight(1f),
                             ) {
                                 Text(if (selectedRole == UserRole.WORKER) "✓ Petugas" else "Petugas")
                             }
                         }
                     } else {
                         Text(
                             "Role mengikuti akun yang terdaftar di server.",
                             color = LinkDroidColors.muted,
                             style = MaterialTheme.typography.bodySmall,
                         )
                     }
                     if (isRegistering && selectedRole == UserRole.ADMIN) {
                         OutlinedTextField(
                             value = adminInviteCode,
                             onValueChange = { adminInviteCode = it; error = null },
                             label = { Text("Kode undangan Admin") },
                             singleLine = true,
                             modifier = Modifier.fillMaxWidth(),
                         )
                     }
                     if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                     if (serverError != null) Text(serverError, color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = {
                             if (email.contains("@") && password.length >= 8) {
                                 onLogin(
                                     email.trim(),
                                     password,
                                     selectedRole,
                                     isRegistering,
                                     adminInviteCode.takeIf { isRegistering && selectedRole == UserRole.ADMIN },
                                 )
                             } else {
                                 error = "Masukkan email valid dan kata sandi minimal 8 karakter."
                             }
                        },
                         enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                     ) { Text(if (isLoading) "Menghubungkan..." else if (isRegistering) "Daftar" else "Masuk") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                email = DEMO_ADMIN_EMAIL
                                password = DEMO_ADMIN_PASSWORD
                                isRegistering = false
                                error = null
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) { Text("Demo Admin") }
                        OutlinedButton(
                            onClick = {
                                email = DEMO_WORKER_EMAIL
                                password = DEMO_WORKER_PASSWORD
                                isRegistering = false
                                error = null
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) { Text("Demo Petugas") }
                    }
                     TextButton(
                         onClick = {
                             isRegistering = !isRegistering
                             error = null
                         },
                         enabled = !isLoading,
                         modifier = Modifier.fillMaxWidth(),
                     ) {
                         Text(if (isRegistering) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar")
                     }
                }
            }
        }
    }
}

@Composable
private fun AdminDashboardScreen(
    email: String,
    activeSession: String?,
    message: String?,
    tasks: List<BackendTaskSummary>,
    auditLogs: List<BackendAuditLogSummary>,
    onConnect: (String) -> Unit,
    onRefreshTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var workerDeviceId by rememberSaveable { mutableStateOf("") }
    Page(modifier) {
        Header("Monitoring petugas", "Kontrol dan pantau proses input data lapangan")
        Text("Halo, ${email.substringBefore("@")}", color = LinkDroidColors.muted)
        if (message != null) Notice(message)
        Card(
            colors = CardDefaults.cardColors(containerColor = LinkDroidColors.primary),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mulai pemantauan", color = Color.White.copy(alpha = .8f))
                Text(
                    "Masukkan ID device petugas untuk melihat layar dan mengontrol proses input.",
                    color = Color.White,
                )
            }
        }
        SectionTitle("ID device petugas")
        OutlinedTextField(
            value = workerDeviceId,
            onValueChange = { workerDeviceId = it.filter(Char::isDigit).take(9) },
            label = { Text("ID device") },
            placeholder = { Text("Contoh: 221095648") },
            supportingText = { Text("ID diberikan oleh petugas sebelum mulai bekerja.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onConnect(workerDeviceId) },
            enabled = workerDeviceId.length == 9,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Hubungkan & Pantau")
        }
        if (activeSession != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF8F1))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pemantauan aktif", fontWeight = FontWeight.Bold, color = LinkDroidColors.success)
                    Text("Device petugas: ${formatDeviceId(activeSession)}")
                    Text(
                        "Layar petugas akan tampil setelah sesi disetujui dan MediaProjection aktif.",
                        color = LinkDroidColors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Data pelanggan masuk")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefreshTasks) { Text("Muat ulang") }
        }
        if (tasks.isEmpty()) {
            Text("Belum ada data pelanggan yang dikirim petugas.", color = LinkDroidColors.muted)
        } else {
            tasks.forEach { task ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.fullName, fontWeight = FontWeight.Bold)
                        Text("IDPEL ${task.meterId}", color = LinkDroidColors.muted)
                        Text("${task.city}, ${task.province}", color = LinkDroidColors.muted)
                        Text(
                            task.status.replace("_", " "),
                            color = if (task.status == "COMPLETED") LinkDroidColors.success else LinkDroidColors.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        SectionTitle("Audit aktivitas terbaru")
        if (auditLogs.isEmpty()) {
            Text("Belum ada audit log yang dapat ditampilkan.", color = LinkDroidColors.muted)
        } else {
            auditLogs.take(10).forEach { log ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(log.action, fontWeight = FontWeight.Medium)
                        Text("${log.entityType} • ${log.entityId.take(8)}", color = LinkDroidColors.muted)
                        Text(
                            "${log.actorEmail ?: "system"} • ${log.createdAt.replace("T", " ").take(19)}",
                            color = LinkDroidColors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        SectionTitle("Alur kerja")
        WorkflowStep("1", "Hubungkan device", "Masukkan ID device petugas.")
        WorkflowStep("2", "Petugas mengisi data", "Data pelanggan diisi langsung di perangkat petugas.")
        WorkflowStep("3", "Pantau PLN Mobile", "Admin memantau sampai proses di PLN Mobile selesai.")
        Notice("Monitoring nyata antarperangkat membutuhkan backend signaling/WebRTC. Saat ini alur UI dan izin Android sudah disiapkan.")
    }
}

@Composable
private fun WorkflowStep(number: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(LinkDroidColors.primary, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkerHomeScreen(
    email: String,
    deviceId: String,
    submittedCustomer: CustomerData?,
    hasCustomerDraft: Boolean,
    activeSession: String?,
    pendingSession: IncomingSession?,
    onStartForm: () -> Unit,
    onContinue: () -> Unit,
    onShareId: () -> Unit,
    onApproveSession: (IncomingSession) -> Unit,
    onRejectSession: (IncomingSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Page(modifier) {
        Header("Tugas lapangan", "Lengkapi data pelanggan dengan akurat")
        Text("Halo, ${email.substringBefore("@")}", color = LinkDroidColors.muted)
        Card(colors = CardDefaults.cardColors(containerColor = LinkDroidColors.primary)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ID device Anda", color = Color.White.copy(alpha = .8f))
                Text(
                    deviceId,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("Berikan ID ini kepada admin untuk dipantau.", color = Color.White.copy(alpha = .85f))
                OutlinedButton(onClick = onShareId, modifier = Modifier.fillMaxWidth()) {
                    Text("Bagikan ID", color = Color.White)
                }
            }
        }
        if (pendingSession != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4D6))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permintaan monitoring", fontWeight = FontWeight.Bold)
                    Text("Admin ${pendingSession.requesterEmail} ingin memantau device ini.")
                    Text("Device pengendali: ${formatDeviceId(pendingSession.controllerDeviceId)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onRejectSession(pendingSession) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Tolak") }
                        Button(
                            onClick = { onApproveSession(pendingSession) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Setujui") }
                    }
                }
            }
        }
        if (activeSession != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF8F1))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Monitoring aktif", fontWeight = FontWeight.Bold, color = LinkDroidColors.success)
                    Text("Device Admin: ${formatDeviceId(activeSession)}")
                    Text("Koneksi video/control menunggu WebRTC media.", color = LinkDroidColors.muted)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pemeriksaan data pelanggan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Pastikan data sesuai KTP atau rekening listrik sebelum melanjutkan ke PLN Mobile.",
                    color = LinkDroidColors.muted,
                )
                HorizontalDivider()
                Text("Status tugas", fontWeight = FontWeight.Medium)
                Text(
                    when {
                        submittedCustomer != null -> "Data tersimpan, lanjut ke PLN Mobile"
                        hasCustomerDraft -> "Draft tersimpan di perangkat ini"
                        else -> "Belum dimulai"
                    },
                    color = if (submittedCustomer != null || hasCustomerDraft) LinkDroidColors.success else LinkDroidColors.muted,
                )
            }
        }
        Button(
            onClick = if (submittedCustomer == null || hasCustomerDraft) onStartForm else onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    submittedCustomer != null -> "Lanjutkan ke PLN Mobile"
                    hasCustomerDraft -> "Lanjutkan draft"
                    else -> "Mulai input data"
                },
            )
        }
        Notice("Admin dapat memantau layar perangkat ini setelah Anda memberikan ID device dan menyetujui permintaan sesi.")
    }
}

@Composable
private fun CustomerFormScreen(
    initialData: CustomerData?,
    onBack: () -> Unit,
    onDraftChanged: (CustomerData) -> Unit,
    onSubmit: (CustomerData) -> Unit,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    var fullName by rememberSaveable(initialData) { mutableStateOf(initialData?.fullName.orEmpty()) }
    var meterId by rememberSaveable(initialData) { mutableStateOf(initialData?.meterId.orEmpty()) }
    var address by rememberSaveable(initialData) { mutableStateOf(initialData?.address.orEmpty()) }
    var village by rememberSaveable(initialData) { mutableStateOf(initialData?.village.orEmpty()) }
    var district by rememberSaveable(initialData) { mutableStateOf(initialData?.district.orEmpty()) }
    var city by rememberSaveable(initialData) { mutableStateOf(initialData?.city.orEmpty()) }
    var province by rememberSaveable(initialData) { mutableStateOf(initialData?.province ?: "Pilih provinsi") }
    var provinceMenuOpen by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun emitDraft() {
        onDraftChanged(
            CustomerData(
                fullName = fullName,
                meterId = meterId,
                address = address,
                village = village,
                district = district,
                city = city,
                province = province,
            ),
        )
    }

    Page(modifier) {
        Header("Data pelanggan", "Isi sesuai KTP atau rekening listrik")
        Text("Data ini akan diproses di perangkat petugas dan dipantau admin.", color = LinkDroidColors.muted)
        FormField("Nama Lengkap Pelanggan", fullName, { fullName = it; error = null; emitDraft() }, "Sesuai KTP/Rekening Listrik")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = meterId,
                onValueChange = { meterId = it.filter(Char::isDigit).take(12); error = null; emitDraft() },
                label = { Text("Nomor Meter / ID Pelanggan (IDPEL)") },
                placeholder = { Text("532819004521") },
                supportingText = { Text("11–12 digit") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    meterId = "532819004521"
                    error = null
                    emitDraft()
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Demo ID")
            }
        }
        FormField("Alamat Lengkap", address, { address = it; error = null; emitDraft() }, "Nama jalan, nomor rumah, RT/RW", minLines = 2)
        FormField("Desa / Kelurahan", village, { village = it; error = null; emitDraft() }, "Contoh: Sukamaju")
        FormField("Kecamatan", district, { district = it; error = null; emitDraft() }, "Contoh: Setiabudi")
        FormField("Kabupaten / Kota", city, { city = it; error = null; emitDraft() }, "Contoh: Jakarta Selatan")
        Box {
            OutlinedButton(
                onClick = { provinceMenuOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("Provinsi", style = MaterialTheme.typography.labelSmall, color = LinkDroidColors.muted)
                        Text(province, color = if (province == "Pilih provinsi") LinkDroidColors.muted else LinkDroidColors.navy)
                    }
                    Text("▾")
                }
            }
            DropdownMenu(
                expanded = provinceMenuOpen,
                onDismissRequest = { provinceMenuOpen = false },
            ) {
                provinceOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            province = option
                            provinceMenuOpen = false
                            error = null
                            emitDraft()
                        },
                    )
                }
            }
        }
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Kembali") }
            Button(
                onClick = {
                    val customer = CustomerData(
                        fullName = fullName.trim(),
                        meterId = meterId,
                        address = address.trim(),
                        village = village.trim(),
                        district = district.trim(),
                        city = city.trim(),
                        province = province,
                    )
                    if (customer.fullName.isBlank() || customer.meterId.length !in 11..12 ||
                        customer.address.isBlank() || customer.village.isBlank() ||
                        customer.district.isBlank() || customer.city.isBlank() ||
                        customer.province == "Pilih provinsi"
                    ) {
                        error = "Lengkapi semua data dan pastikan IDPEL berisi 11–12 digit."
                    } else {
                        onSubmit(customer)
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isSubmitting) "Menyimpan..." else "Simpan & Lanjut")
            }
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = minLines,
        singleLine = minLines == 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlnMobileScreen(
    customer: CustomerData?,
    isScreenSharing: Boolean,
    message: String?,
    onBack: () -> Unit,
    onShareScreen: () -> Unit,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Page(modifier) {
        Header("Lanjut ke PLN Mobile", "Selesaikan proses pada aplikasi PLN Mobile")
        if (message != null) Notice(message)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Data siap diproses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(customer?.fullName.orEmpty(), fontWeight = FontWeight.Medium)
                Text("IDPEL ${customer?.meterId.orEmpty()}", color = LinkDroidColors.muted)
                Text(customer?.city.orEmpty(), color = LinkDroidColors.muted)
            }
        }
        WorkflowStep("1", "Buka PLN Mobile", "Lanjutkan input atau verifikasi data di aplikasi PLN Mobile.")
        WorkflowStep("2", "Aktifkan pemantauan", "Admin akan melihat layar perangkat setelah izin berbagi layar disetujui.")
        WorkflowStep("3", "Selesaikan dan konfirmasi", "Jangan menutup proses sebelum admin menyatakan selesai.")
        SettingAction(
            "Berbagi layar ke admin",
            if (isScreenSharing) "MediaProjection aktif pada perangkat ini" else "Belum aktif — minta izin Android terlebih dahulu",
            if (isScreenSharing) "Hentikan" else "Aktifkan",
            if (isScreenSharing) onStopSharing else onShareScreen,
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali ke tugas") }
    }
}

private val provinceOptions = listOf(
    "Aceh", "Sumatera Utara", "Sumatera Barat", "Riau", "Jambi",
    "Sumatera Selatan", "Bengkulu", "Lampung", "Kepulauan Bangka Belitung",
    "Kepulauan Riau", "DKI Jakarta", "Jawa Barat", "Jawa Tengah",
    "DI Yogyakarta", "Jawa Timur", "Banten", "Bali", "Nusa Tenggara Barat",
    "Nusa Tenggara Timur", "Kalimantan Barat", "Kalimantan Tengah",
    "Kalimantan Selatan", "Kalimantan Timur", "Kalimantan Utara",
    "Sulawesi Utara", "Sulawesi Tengah", "Sulawesi Selatan", "Sulawesi Tenggara",
    "Gorontalo", "Sulawesi Barat", "Maluku", "Maluku Utara", "Papua Barat",
    "Papua",
)

@Composable
private fun HomeScreen(
    email: String,
    deviceId: String,
    activeSession: String?,
    message: String?,
    onConnect: (String) -> Unit,
    onShareId: () -> Unit,
    onShareScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
        var targetDeviceId by rememberSaveable { mutableStateOf("") }
    Page(modifier) {
        Header("Beranda", "Halo, ${email.substringBefore("@")}")
        if (message != null) Notice(message)
        Card(colors = CardDefaults.cardColors(containerColor = LinkDroidColors.primary)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ID perangkat Anda", color = Color.White.copy(alpha = .8f))
                Text(deviceId, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Online • Siap menerima koneksi", color = Color.White.copy(alpha = .85f))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onShareId, modifier = Modifier.fillMaxWidth()) { Text("Bagikan ID", color = Color.White) }
            }
        }
        SectionTitle("Hubungkan ke perangkat")
        OutlinedTextField(
            value = targetDeviceId,
            onValueChange = { targetDeviceId = it },
            label = { Text("ID perangkat tujuan") },
            placeholder = { Text("Contoh: 221 095 648") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onConnect(targetDeviceId) }, modifier = Modifier.fillMaxWidth()) { Text("Hubungkan") }
        OutlinedButton(onClick = onShareScreen, modifier = Modifier.fillMaxWidth()) {
            Text("Aktifkan berbagi layar")
        }
        if (activeSession != null) Text("Sesi aktif: $activeSession", color = LinkDroidColors.success)
    }
}

@Composable
private fun DevicesScreen(
    devices: List<Device>,
    currentDeviceId: String,
    onConnect: (String) -> Unit,
    onAdd: (Device) -> Unit,
    onRemove: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var newId by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Page(modifier) {
        Header("Perangkat", "Kelola perangkat yang tersimpan")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tambah perangkat", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; error = null },
                    label = { Text("Nama perangkat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newId,
                    onValueChange = { newId = it; error = null },
                    label = { Text("ID perangkat 9 digit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = {
                        val normalized = formatDeviceId(newId)
                        if (newName.trim().isBlank() || normalized.filter(Char::isDigit).length != 9) {
                            error = "Isi nama dan ID perangkat 9 digit."
                        } else {
                            onAdd(Device(normalized, newName.trim(), "Android • Baru ditambahkan", true))
                            newName = ""
                            newId = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Simpan perangkat") }
            }
        }
        devices.forEach { device ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.Bold)
                            Text(device.id, color = LinkDroidColors.muted)
                        }
                        Text(if (device.online) "Online" else "Offline", color = if (device.online) LinkDroidColors.success else LinkDroidColors.muted)
                    }
                    Text(device.platform, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
                    if (device.online) {
                        Row {
                            TextButton(onClick = { onConnect(device.id) }) { Text("Mulai sesi") }
                            if (device.id != currentDeviceId) {
                                TextButton(onClick = { onRemove(device) }) { Text("Hapus") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(
    deviceId: String,
    isController: Boolean,
    webRtcSessionManager: WebRtcSessionManager?,
    message: String?,
    isScreenSharing: Boolean,
    onShareScreen: () -> Unit,
    onStopSharing: () -> Unit,
    onStop: () -> Unit,
    onCommand: (RemoteCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var audioEnabled by rememberSaveable { mutableStateOf(false) }
    var tapX by rememberSaveable { mutableStateOf("0.5") }
    var tapY by rememberSaveable { mutableStateOf("0.5") }
    Page(modifier) {
        Header("Sesi remote", "Koneksi aman dengan perangkat tujuan")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Menghubungkan ke", color = LinkDroidColors.muted)
                Text(formatDeviceId(deviceId), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (isController) "Kontrol tersedia setelah perangkat penerima menyetujui sesi"
                    else "Layar dan kontrol hanya aktif setelah sesi disetujui",
                    color = LinkDroidColors.primary,
                )
                HorizontalDivider()
                Text("Layar dan kontrol sentuhan hanya aktif setelah penerima menyetujui sesi.", color = LinkDroidColors.muted)
                if (message != null) Notice(message)
                if (isController && webRtcSessionManager != null) {
                    Text("Video perangkat penerima", fontWeight = FontWeight.Bold)
                    AndroidView(
                        factory = { context ->
                            org.webrtc.SurfaceViewRenderer(context).also {
                                webRtcSessionManager.attachRenderer(it)
                            }
                        },
                        update = { webRtcSessionManager.attachRenderer(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                }
                SettingRow(
                    "Berbagi layar",
                    if (isScreenSharing) "MediaProjection aktif di perangkat ini" else "Belum dimulai",
                    isScreenSharing,
                    onCheckedChange = { enabled -> if (enabled) onShareScreen() else onStopSharing() },
                )
                SettingRow(
                    "Audio sesi",
                    "Belum tersedia sampai audio transport diaktifkan",
                    audioEnabled,
                    onCheckedChange = { audioEnabled = it },
                    enabled = false,
                )
                if (isController) {
                    HorizontalDivider()
                    Text("Kontrol aksesibilitas", fontWeight = FontWeight.Bold)
                    Text(
                        "Koordinat menggunakan rasio 0.0–1.0 dari ukuran layar perangkat penerima.",
                        color = LinkDroidColors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tapX,
                            onValueChange = { tapX = it },
                            label = { Text("X") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tapY,
                            onValueChange = { tapY = it },
                            label = { Text("Y") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val x = tapX.toFloatOrNull()
                            val y = tapY.toFloatOrNull()
                            if (x != null && y != null && x in 0f..1f && y in 0f..1f) {
                                onCommand(RemoteCommand.Tap(x, y))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Kirim tap") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onCommand(RemoteCommand.Back) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Back") }
                        OutlinedButton(
                            onClick = { onCommand(RemoteCommand.Home) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Home") }
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Akhiri sesi") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    email: String,
    backendBaseUrl: String,
    registrationStatus: String,
    onRegisterDevice: () -> Unit,
    notificationsEnabled: Boolean,
    accessibilityEnabled: Boolean,
    isScreenSharing: Boolean,
    onNotificationsChanged: (Boolean) -> Unit,
    onAccessibility: () -> Unit,
    onScreenShare: () -> Unit,
    onStopSharing: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Page(modifier) {
        Header("Pengaturan", "Izin dan akun LinkDroid")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(email, fontWeight = FontWeight.Bold)
                Text("Akun lokal demo", color = LinkDroidColors.muted)
                Text("Server produksi", fontWeight = FontWeight.Medium)
                Text(backendBaseUrl, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
                Text(registrationStatus, color = if (registrationStatus.startsWith("Device sudah")) LinkDroidColors.success else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRegisterDevice) { Text("Daftarkan ulang device") }
                HorizontalDivider()
                SettingRow("Notifikasi sesi", "Terima status koneksi remote", notificationsEnabled, onNotificationsChanged)
                SettingAction(
                    "Berbagi layar",
                    if (isScreenSharing) "MediaProjection sedang aktif" else "Minta izin MediaProjection Android",
                    if (isScreenSharing) "Hentikan" else "Buka",
                    if (isScreenSharing) onStopSharing else onScreenShare,
                )
                SettingAction(
                    "Accessibility Service",
                    if (accessibilityEnabled) "Aktif di pengaturan Android" else "Diperlukan untuk kontrol sentuhan remote",
                    if (accessibilityEnabled) "Kelola" else "Buka",
                    onAccessibility,
                )
            }
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
    }
}

@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun Header(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(subtitle, color = LinkDroidColors.muted)
}

@Composable
private fun Logo() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(LinkDroidColors.primary, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("↔", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Notice(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF8F1))) {
        Text(message, color = Color(0xFF23794F), modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingAction(title: String, description: String, actionLabel: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) { Text(actionLabel) }
    }
}

private fun formatDeviceId(value: String): String =
    value.filter(Char::isDigit).chunked(3).joinToString(" ").ifBlank { "—" }