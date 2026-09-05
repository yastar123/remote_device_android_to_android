package app.linkdroid.remote

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class BackendAuthResult(
    val email: String,
    val role: UserRole,
    val accessToken: String,
    val refreshToken: String,
)

data class BackendAuthTokens(
    val accessToken: String,
    val refreshToken: String,
)

data class AuthenticatedOperationResult<T>(
    val value: T,
    val accessToken: String,
    val refreshToken: String,
)

class BackendHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

data class BackendSessionResult(
    val id: String,
    val status: String,
)

data class BackendTaskSummary(
    val id: String,
    val fullName: String,
    val meterId: String,
    val city: String,
    val province: String,
    val status: String,
)

data class BackendDeviceSummary(
    val deviceId: String,
    val name: String,
    val androidVersion: String?,
    val appVersion: String?,
    val lastSeenAt: String?,
)

data class BackendAuditLogSummary(
    val action: String,
    val entityType: String,
    val entityId: String,
    val createdAt: String,
    val actorEmail: String?,
)

data class BackendIceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?,
)

object BackendApiClient {
    suspend fun login(baseUrl: String, email: String, password: String): BackendAuthResult =
        authenticate(baseUrl, "/api/v1/auth/login", email, password, null)

    suspend fun register(
        baseUrl: String,
        email: String,
        password: String,
        role: UserRole,
        adminInviteCode: String?,
    ): BackendAuthResult = authenticate(baseUrl, "/api/v1/auth/register", email, password, role, adminInviteCode)

    suspend fun registerDevice(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
        deviceName: String,
        androidVersion: String = Build.VERSION.RELEASE.orEmpty(),
        appVersion: String,
    ) {
        request(
            baseUrl = baseUrl,
            path = "/api/v1/devices/register",
            method = "POST",
            accessToken = accessToken,
            body = JSONObject()
                .put("deviceId", deviceId.filter(Char::isDigit))
                .put("deviceName", deviceName)
                .put("androidVersion", androidVersion)
                .put("appVersion", appVersion),
        )
    }

    suspend fun heartbeat(baseUrl: String, accessToken: String, deviceId: String) {
        request(
            baseUrl,
            "/api/v1/devices/${deviceId.filter(Char::isDigit)}/heartbeat",
            "POST",
            accessToken,
        )
    }

    suspend fun listDevices(baseUrl: String, accessToken: String): List<BackendDeviceSummary> {
        val response = request(baseUrl, "/api/v1/devices", "GET", accessToken)
        val devices = response.optJSONArray("devices") ?: JSONArray()
        return buildList {
            for (index in 0 until devices.length()) {
                val device = devices.getJSONObject(index)
                add(
                    BackendDeviceSummary(
                        deviceId = device.getString("deviceId"),
                        name = device.getString("name"),
                        androidVersion = device.optString("androidVersion").takeIf { it.isNotBlank() },
                        appVersion = device.optString("appVersion").takeIf { it.isNotBlank() },
                        lastSeenAt = device.optString("lastSeenAt").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    suspend fun createSession(
        baseUrl: String,
        accessToken: String,
        controllerDeviceId: String,
        receiverDeviceId: String,
    ): BackendSessionResult {
        val response = request(
            baseUrl,
            "/api/v1/sessions",
            "POST",
            accessToken,
            JSONObject()
                .put("controllerDeviceId", controllerDeviceId.filter(Char::isDigit))
                .put("receiverDeviceId", receiverDeviceId.filter(Char::isDigit)),
        )
        val session = response.getJSONObject("session")
        return BackendSessionResult(session.getString("id"), session.getString("status"))
    }

    suspend fun createCustomerTask(
        baseUrl: String,
        accessToken: String,
        workerDeviceId: String,
        customer: CustomerData,
    ): String {
        val response = request(
            baseUrl,
            "/api/v1/tasks",
            "POST",
            accessToken,
            JSONObject()
                .put("workerDeviceId", workerDeviceId.filter(Char::isDigit))
                .put("fullName", customer.fullName)
                .put("meterId", customer.meterId)
                .put("address", customer.address)
                .put("village", customer.village)
                .put("district", customer.district)
                .put("city", customer.city)
                .put("province", customer.province),
        )
        return response.getJSONObject("task").getString("id")
    }

    suspend fun updateTaskStatus(
        baseUrl: String,
        accessToken: String,
        taskId: String,
        status: String,
    ) {
        request(
            baseUrl,
            "/api/v1/tasks/$taskId/status",
            "PATCH",
            accessToken,
            JSONObject().put("status", status),
        )
    }

    suspend fun listTasks(baseUrl: String, accessToken: String): List<BackendTaskSummary> {
        val response = request(baseUrl, "/api/v1/tasks", "GET", accessToken)
        val tasks = response.optJSONArray("tasks") ?: JSONArray()
        return buildList {
            for (index in 0 until tasks.length()) {
                val task = tasks.getJSONObject(index)
                add(
                    BackendTaskSummary(
                        id = task.getString("id"),
                        fullName = task.getString("fullName"),
                        meterId = task.getString("meterId"),
                        city = task.getString("city"),
                        province = task.getString("province"),
                        status = task.getString("status"),
                    ),
                )
            }
        }
    }

    suspend fun listAuditLogs(baseUrl: String, accessToken: String): List<BackendAuditLogSummary> {
        val response = request(baseUrl, "/api/v1/audit-logs?limit=20", "GET", accessToken)
        val logs = response.optJSONArray("logs") ?: JSONArray()
        return buildList {
            for (index in 0 until logs.length()) {
                val log = logs.getJSONObject(index)
                add(
                    BackendAuditLogSummary(
                        action = log.getString("action"),
                        entityType = log.getString("entityType"),
                        entityId = log.getString("entityId"),
                        createdAt = log.getString("createdAt"),
                        actorEmail = log.optJSONObject("actor")?.optString("email")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    suspend fun listIceServers(baseUrl: String, accessToken: String): List<BackendIceServer> {
        val response = request(baseUrl, "/api/v1/turn/credentials", "GET", accessToken)
        val servers = response.optJSONArray("iceServers") ?: return emptyList()
        return buildList {
            for (index in 0 until servers.length()) {
                val server = servers.getJSONObject(index)
                val urls = when (val rawUrls = server.opt("urls")) {
                    is JSONArray -> buildList {
                        for (urlIndex in 0 until rawUrls.length()) add(rawUrls.getString(urlIndex))
                    }
                    is String -> listOf(rawUrls)
                    else -> emptyList()
                }
                if (urls.isNotEmpty()) {
                    add(
                        BackendIceServer(
                            urls = urls,
                            username = server.optString("username").takeIf { it.isNotBlank() },
                            credential = server.optString("credential").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
    }

    suspend fun approveSession(baseUrl: String, accessToken: String, sessionId: String) {
        request(baseUrl, "/api/v1/sessions/$sessionId/approve", "POST", accessToken)
    }

    suspend fun rejectSession(baseUrl: String, accessToken: String, sessionId: String) {
        request(baseUrl, "/api/v1/sessions/$sessionId/reject", "POST", accessToken)
    }

    suspend fun endSession(baseUrl: String, accessToken: String, sessionId: String) {
        request(baseUrl, "/api/v1/sessions/$sessionId/end", "POST", accessToken)
    }

    suspend fun logout(baseUrl: String, accessToken: String) {
        request(baseUrl, "/api/v1/auth/logout", "POST", accessToken)
    }

    suspend fun refresh(baseUrl: String, refreshToken: String): BackendAuthTokens {
        val response = request(
            baseUrl,
            "/api/v1/auth/refresh",
            "POST",
            null,
            JSONObject().put("refreshToken", refreshToken),
        )
        return BackendAuthTokens(
            accessToken = response.getString("accessToken"),
            refreshToken = response.getString("refreshToken"),
        )
    }

    suspend fun <T> withAutoRefresh(
        baseUrl: String,
        accessToken: String,
        refreshToken: String,
        operation: suspend (String) -> T,
    ): AuthenticatedOperationResult<T> {
        return try {
            AuthenticatedOperationResult(
                value = operation(accessToken),
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
        } catch (error: BackendHttpException) {
            if (error.statusCode != 401) throw error
            val refreshed = refresh(baseUrl, refreshToken)
            AuthenticatedOperationResult(
                value = operation(refreshed.accessToken),
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
            )
        }
    }

    private suspend fun authenticate(
        baseUrl: String,
        path: String,
        email: String,
        password: String,
        role: UserRole?,
        adminInviteCode: String? = null,
    ): BackendAuthResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
        if (role != null) body.put("role", role.name)
        if (!adminInviteCode.isNullOrBlank()) body.put("adminInviteCode", adminInviteCode)
        val response = request(baseUrl, path, "POST", null, body)
        val user = response.getJSONObject("user")
        return BackendAuthResult(
            email = user.getString("email"),
            role = UserRole.valueOf(user.getString("role")),
            accessToken = response.getString("accessToken"),
            refreshToken = response.getString("refreshToken"),
        )
    }

    private suspend fun request(
        baseUrl: String,
        path: String,
        method: String,
        accessToken: String?,
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (!accessToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            doOutput = body != null
        }
        try {
            body?.let { payload ->
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseBody).optString("message") }.getOrNull()
                throw BackendHttpException(
                    statusCode = status,
                    message = message?.takeIf { it.isNotBlank() } ?: "Server merespons HTTP $status",
                )
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}