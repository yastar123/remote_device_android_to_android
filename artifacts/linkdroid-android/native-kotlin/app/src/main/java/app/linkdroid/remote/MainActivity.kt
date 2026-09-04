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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        var screen by rememberSaveable { mutableStateOf(if (email == null) AppScreen.LOGIN else AppScreen.HOME) }
        var activeSession by rememberSaveable { mutableStateOf<String?>(null) }
        var message by rememberSaveable { mutableStateOf<String?>(null) }
        var isScreenSharing by rememberSaveable { mutableStateOf(false) }
        var notificationsEnabled by rememberSaveable {
            mutableStateOf(getPreferences(MODE_PRIVATE).getBoolean("notifications", true))
        }
        var registrationStatus by rememberSaveable { mutableStateOf("Belum didaftarkan ke server") }
        val registrationScope = rememberCoroutineScope()
        val currentDeviceId = remember { getOrCreateDeviceId() }
        var devices by remember(currentDeviceId) { mutableStateOf(loadDevices(currentDeviceId)) }
        var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
        val sessionCoordinator = remember { RemoteSessionCoordinator() }
        val activeRemoteSession by sessionCoordinator.session.collectAsState()
        val registerCurrentDevice: () -> Unit = {
            val registeredEmail = email
            if (registeredEmail.isNullOrBlank()) {
                registrationStatus = "Masuk terlebih dahulu untuk mendaftarkan device."
            } else {
                registrationStatus = "Sedang mendaftarkan device ke server..."
                registrationScope.launch {
                    runCatching {
                        DeviceRegistrationClient.register(
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            email = registeredEmail,
                            deviceId = currentDeviceId,
                            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                            androidVersion = Build.VERSION.RELEASE.orEmpty(),
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    }.onSuccess {
                        registrationStatus = "Device sudah terdaftar di server."
                    }.onFailure { error ->
                        registrationStatus = "Pendaftaran gagal: ${error.message ?: "server tidak dapat dihubungi"}"
                    }
                }
            }
        }
        LaunchedEffect(email, currentDeviceId) {
            if (!email.isNullOrBlank()) registerCurrentDevice()
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
        val captureLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
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

                activeRemoteSession != null -> {
                    message = "Akhiri sesi aktif sebelum memulai sesi baru."
                }

                else -> {
                    sessionCoordinator.start(
                        RemoteSession(
                            sessionId = "local-${System.currentTimeMillis()}",
                            peerDeviceId = normalized,
                            role = RemoteSession.Role.CONTROLLER,
                        ),
                    )
                    activeSession = normalized
                    screen = AppScreen.SESSION
                    message = "Permintaan sesi dikirim. Menunggu persetujuan penerima."
                    if (notificationsEnabled) {
                        showSessionNotification("Menunggu persetujuan perangkat ${formatDeviceId(normalized)}.")
                    }
                }
            }
        }

        if (screen == AppScreen.LOGIN) {
            LoginScreen(
                onLogin = { enteredEmail ->
                    getPreferences(MODE_PRIVATE).edit().putString("email", enteredEmail).apply()
                    email = enteredEmail
                    screen = AppScreen.HOME
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
                    listOf(
                        AppScreen.HOME to "Beranda",
                        AppScreen.DEVICES to "Perangkat",
                        AppScreen.SETTINGS to "Pengaturan",
                    ).forEach { (destination, label) ->
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
                AppScreen.HOME -> HomeScreen(
                    email = email.orEmpty(),
                    deviceId = currentDeviceId,
                    activeSession = activeSession,
                    message = message,
                    onConnect = startRemoteSession,
                    onShareId = { shareDeviceId(currentDeviceId) },
                    onShareScreen = requestScreenShare,
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
                    message = message,
                    isScreenSharing = isScreenSharing,
                    onShareScreen = requestScreenShare,
                    onStopSharing = {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        isScreenSharing = false
                        message = "Berbagi layar dihentikan."
                    },
                    onStop = {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        sessionCoordinator.stop()
                        cancelSessionNotification()
                        isScreenSharing = false
                        activeSession = null
                        message = "Sesi remote diakhiri."
                        screen = AppScreen.HOME
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
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        isScreenSharing = false
                        message = "Berbagi layar dihentikan."
                    },
                    onLogout = {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        sessionCoordinator.stop()
                        cancelSessionNotification()
                        isScreenSharing = false
                        getPreferences(MODE_PRIVATE).edit().remove("email").apply()
                        email = null
                        activeSession = null
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

    companion object {
        private const val SESSION_NOTIFICATION_CHANNEL = "linkdroid-session-status"
        private const val SESSION_NOTIFICATION_ID = 1002
    }
}

private enum class AppScreen { LOGIN, HOME, DEVICES, SESSION, SETTINGS }

private data class Device(val id: String, val name: String, val platform: String, val online: Boolean)

private fun defaultDevices(currentDeviceId: String) = listOf(
    Device(currentDeviceId, "Perangkat ini", "Android • Perangkat ini", true),
    Device("221 095 648", "Xiaomi Pad 6", "Android 13 • 2 jam lalu", false),
    Device("731 440 219", "Galaxy S23", "Android 14 • Aktif sekarang", true),
)

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
private fun LoginScreen(onLogin: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

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
                    Text("Selamat datang kembali", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Masuk untuk mengakses perangkat Android Anda.", color = LinkDroidColors.muted)
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
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = {
                            if (email.contains("@") && password.length >= 6) onLogin(email.trim())
                            else error = "Masukkan email valid dan kata sandi minimal 6 karakter."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Masuk") }
                    OutlinedButton(
                        onClick = {
                            email = "demo@linkdroid.app"
                            password = "linkdroid"
                            onLogin(email)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Coba demo") }
                }
            }
        }
    }
}

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
    message: String?,
    isScreenSharing: Boolean,
    onShareScreen: () -> Unit,
    onStopSharing: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var audioEnabled by rememberSaveable { mutableStateOf(false) }
    Page(modifier) {
        Header("Sesi remote", "Koneksi aman dengan perangkat tujuan")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Menghubungkan ke", color = LinkDroidColors.muted)
                Text(formatDeviceId(deviceId), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Menunggu persetujuan perangkat penerima", color = LinkDroidColors.primary)
                HorizontalDivider()
                Text("Layar dan kontrol sentuhan hanya aktif setelah penerima menyetujui sesi.", color = LinkDroidColors.muted)
                if (message != null) Notice(message)
                SettingRow(
                    "Berbagi layar",
                    if (isScreenSharing) "MediaProjection aktif di perangkat ini" else "Belum dimulai",
                    isScreenSharing,
                ) { enabled -> if (enabled) onShareScreen() else onStopSharing() }
                SettingRow(
                    "Audio sesi",
                    "Belum tersedia sampai audio transport diaktifkan",
                    audioEnabled,
                    enabled = false,
                ) { audioEnabled = it }
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