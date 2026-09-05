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
        var role by rememberSaveable {
            mutableStateOf(
                getPreferences(MODE_PRIVATE).getString("role", UserRole.ADMIN.name)
                    ?.let { runCatching { UserRole.valueOf(it) }.getOrDefault(UserRole.ADMIN) }
                    ?: UserRole.ADMIN,
            )
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
        var submittedCustomer by remember { mutableStateOf<CustomerData?>(null) }
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
                onLogin = { enteredEmail, selectedRole ->
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("email", enteredEmail)
                        .putString("role", selectedRole.name)
                        .apply()
                    email = enteredEmail
                    role = selectedRole
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
                        onConnect = startRemoteSession,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    WorkerHomeScreen(
                        email = email.orEmpty(),
                        deviceId = currentDeviceId,
                        submittedCustomer = submittedCustomer,
                        onStartForm = { screen = AppScreen.CUSTOMER_FORM },
                        onContinue = { screen = AppScreen.PLN_MOBILE },
                        onShareId = { shareDeviceId(currentDeviceId) },
                        modifier = Modifier.padding(padding),
                    )
                }

                AppScreen.CUSTOMER_FORM -> CustomerFormScreen(
                    initialData = submittedCustomer,
                    onBack = { screen = AppScreen.HOME },
                    onSubmit = {
                        submittedCustomer = it
                        getPreferences(MODE_PRIVATE).edit()
                            .putBoolean("customer_form_saved", true)
                            .apply()
                        screen = AppScreen.PLN_MOBILE
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.PLN_MOBILE -> PlnMobileScreen(
                    customer = submittedCustomer,
                    isScreenSharing = isScreenSharing,
                    message = message,
                    onBack = { screen = AppScreen.HOME },
                    onShareScreen = requestScreenShare,
                    onStopSharing = {
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
                        getPreferences(MODE_PRIVATE).edit()
                            .remove("email")
                            .remove("role")
                            .apply()
                        email = null
                        role = UserRole.ADMIN
                        activeSession = null
                        submittedCustomer = null
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

private enum class AppScreen { LOGIN, HOME, CUSTOMER_FORM, PLN_MOBILE, DEVICES, SESSION, SETTINGS }

private enum class UserRole { ADMIN, WORKER }

private data class Device(val id: String, val name: String, val platform: String, val online: Boolean)

private data class CustomerData(
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
private fun LoginScreen(onLogin: (String, UserRole) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRole by rememberSaveable { mutableStateOf(UserRole.ADMIN) }

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
                     Text("Masuk sebagai", fontWeight = FontWeight.Medium)
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
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = {
                             if (email.contains("@") && password.length >= 6) onLogin(email.trim(), selectedRole)
                            else error = "Masukkan email valid dan kata sandi minimal 6 karakter."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Masuk") }
                    OutlinedButton(
                        onClick = {
                            email = "demo@linkdroid.app"
                            password = "linkdroid"
                             onLogin(email, selectedRole)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Coba demo") }
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
    onConnect: (String) -> Unit,
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
    onStartForm: () -> Unit,
    onContinue: () -> Unit,
    onShareId: () -> Unit,
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
                    if (submittedCustomer == null) "Belum dimulai" else "Data tersimpan, lanjut ke PLN Mobile",
                    color = if (submittedCustomer == null) LinkDroidColors.muted else LinkDroidColors.success,
                )
            }
        }
        Button(
            onClick = if (submittedCustomer == null) onStartForm else onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (submittedCustomer == null) "Mulai input data" else "Lanjutkan ke PLN Mobile")
        }
        Notice("Admin dapat memantau layar perangkat ini setelah Anda memberikan ID device dan menyetujui permintaan sesi.")
    }
}

@Composable
private fun CustomerFormScreen(
    initialData: CustomerData?,
    onBack: () -> Unit,
    onSubmit: (CustomerData) -> Unit,
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

    Page(modifier) {
        Header("Data pelanggan", "Isi sesuai KTP atau rekening listrik")
        Text("Data ini akan diproses di perangkat petugas dan dipantau admin.", color = LinkDroidColors.muted)
        FormField("Nama Lengkap Pelanggan", fullName, { fullName = it; error = null }, "Sesuai KTP/Rekening Listrik")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = meterId,
                onValueChange = { meterId = it.filter(Char::isDigit).take(12); error = null },
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
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Demo ID")
            }
        }
        FormField("Alamat Lengkap", address, { address = it; error = null }, "Nama jalan, nomor rumah, RT/RW", minLines = 2)
        FormField("Desa / Kelurahan", village, { village = it; error = null }, "Contoh: Sukamaju")
        FormField("Kecamatan", district, { district = it; error = null }, "Contoh: Setiabudi")
        FormField("Kabupaten / Kota", city, { city = it; error = null }, "Contoh: Jakarta Selatan")
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
                modifier = Modifier.weight(1f),
            ) {
                Text("Simpan & Lanjut")
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