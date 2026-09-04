package app.linkdroid.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeviceRegistrationClient {
    suspend fun register(
        baseUrl: String,
        email: String,
        deviceId: String,
        deviceName: String,
        androidVersion: String,
        appVersion: String,
    ) = withContext(Dispatchers.IO) {
        val endpoint = "${baseUrl.trimEnd('/')}/api/v1/devices/register"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            val payload = """
                {
                  "email": "${email.jsonEscape()}",
                  "deviceId": "${deviceId.jsonEscape()}",
                  "deviceName": "${deviceName.jsonEscape()}",
                  "androidVersion": "${androidVersion.jsonEscape()}",
                  "appVersion": "${appVersion.jsonEscape()}"
                }
            """.trimIndent()

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("Server merespons HTTP $statusCode")
            }
        } finally {
            connection.disconnect()
        }
    }
}

private fun String.jsonEscape(): String = buildString {
    for (character in this@jsonEscape) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}