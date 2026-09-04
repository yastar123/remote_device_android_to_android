package app.linkdroid.remote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var deviceId by rememberSaveable { mutableStateOf("") }
            var message by rememberSaveable { mutableStateOf("Siap menerima sesi remote.") }
            val captureLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                    }
                    startForegroundService(serviceIntent)
                    message = "Berbagi layar aktif."
                } else {
                    message = "Berbagi layar belum diizinkan."
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("LinkDroid", style = MaterialTheme.typography.headlineMedium)
                        Text("Remote support untuk perangkat Android.")
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { deviceId = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("ID perangkat tujuan") },
                        )
                        Button(
                            onClick = {
                                message = if (deviceId.replace(" ", "").length >= 9) {
                                    "Permintaan sesi dikirim. Menunggu persetujuan penerima."
                                } else {
                                    "Masukkan 9 digit ID perangkat."
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Hubungkan")
                        }
                        Button(
                            onClick = {
                                val manager = getSystemService(MediaProjectionManager::class.java)
                                captureLauncher.launch(manager.createScreenCaptureIntent())
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Aktifkan berbagi layar")
                        }
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}