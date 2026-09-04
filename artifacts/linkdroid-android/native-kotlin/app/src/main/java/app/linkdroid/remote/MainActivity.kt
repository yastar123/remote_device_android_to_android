package app.linkdroid.remote

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
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
        val captureLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                message = "Berbagi layar aktif dan menunggu koneksi."
            } else {
                message = "Izin berbagi layar belum diberikan."
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
                    activeSession = activeSession,
                    message = message,
                    onConnect = { id ->
                        val normalized = id.filter(Char::isDigit)
                        if (normalized.length == 9) {
                            activeSession = normalized
                            screen = AppScreen.SESSION
                            message = "Permintaan sesi dikirim. Menunggu persetujuan penerima."
                        } else {
                            message = "Masukkan ID perangkat 9 digit."
                        }
                    },
                    onShareScreen = {
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        captureLauncher.launch(manager.createScreenCaptureIntent())
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.DEVICES -> DevicesScreen(
                    onConnect = { id ->
                        activeSession = id
                        screen = AppScreen.SESSION
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.SESSION -> SessionScreen(
                    deviceId = activeSession.orEmpty(),
                    message = message,
                    onStop = {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        activeSession = null
                        message = "Sesi remote diakhiri."
                        screen = AppScreen.HOME
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    email = email.orEmpty(),
                    onAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onScreenShare = {
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        captureLauncher.launch(manager.createScreenCaptureIntent())
                    },
                    onLogout = {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        getPreferences(MODE_PRIVATE).edit().remove("email").apply()
                        email = null
                        screen = AppScreen.LOGIN
                    },
                    modifier = Modifier.padding(padding),
                )

                AppScreen.LOGIN -> Unit
            }
        }
    }
}

private enum class AppScreen { LOGIN, HOME, DEVICES, SESSION, SETTINGS }

private data class Device(val id: String, val name: String, val platform: String, val online: Boolean)

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
    activeSession: String?,
    message: String?,
    onConnect: (String) -> Unit,
    onShareScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deviceId by rememberSaveable { mutableStateOf("") }
    Page(modifier) {
        Header("Beranda", "Halo, ${email.substringBefore("@")}")
        if (message != null) Notice(message)
        Card(colors = CardDefaults.cardColors(containerColor = LinkDroidColors.primary)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ID perangkat Anda", color = Color.White.copy(alpha = .8f))
                Text("884 512 307", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Online • Siap menerima koneksi", color = Color.White.copy(alpha = .85f))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text("Bagikan ID", color = Color.White) }
            }
        }
        SectionTitle("Hubungkan ke perangkat")
        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            label = { Text("ID perangkat tujuan") },
            placeholder = { Text("Contoh: 221 095 648") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onConnect(deviceId) }, modifier = Modifier.fillMaxWidth()) { Text("Hubungkan") }
        OutlinedButton(onClick = onShareScreen, modifier = Modifier.fillMaxWidth()) {
            Text("Aktifkan berbagi layar")
        }
        if (activeSession != null) Text("Sesi aktif: $activeSession", color = LinkDroidColors.success)
    }
}

@Composable
private fun DevicesScreen(onConnect: (String) -> Unit, modifier: Modifier = Modifier) {
    val devices = remember {
        listOf(
            Device("884 512 307", "Samsung A54", "Android 14 • Perangkat ini", true),
            Device("221 095 648", "Xiaomi Pad 6", "Android 13 • 2 jam lalu", false),
            Device("731 440 219", "Galaxy S23", "Android 14 • Aktif sekarang", true),
        )
    }
    Page(modifier) {
        Header("Perangkat", "Kelola perangkat yang tersimpan")
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
                        TextButton(onClick = { onConnect(device.id) }) { Text("Mulai sesi") }
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
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Akhiri sesi") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    email: String,
    onAccessibility: () -> Unit,
    onScreenShare: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var notifications by rememberSaveable { mutableStateOf(true) }
    Page(modifier) {
        Header("Pengaturan", "Izin dan akun LinkDroid")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(email, fontWeight = FontWeight.Bold)
                Text("Akun lokal demo", color = LinkDroidColors.muted)
                HorizontalDivider()
                SettingRow("Notifikasi sesi", "Terima status koneksi remote", notifications) { notifications = it }
                SettingAction("Berbagi layar", "Minta izin MediaProjection Android", onScreenShare)
                SettingAction("Accessibility Service", "Izinkan kontrol sentuhan setelah persetujuan", onAccessibility)
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
private fun SettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingAction(title: String, description: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = LinkDroidColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) { Text("Buka") }
    }
}

private fun formatDeviceId(value: String): String =
    value.filter(Char::isDigit).chunked(3).joinToString(" ").ifBlank { "—" }