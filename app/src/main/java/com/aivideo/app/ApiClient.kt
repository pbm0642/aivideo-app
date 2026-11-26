package com.aivideo.app

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiException(message: String, val httpCode: Int = 0) : Exception(message)

object ApiClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun createOkHttpClient(ignoreSsl: Boolean, token: String = ""): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)

        if (token.isNotBlank()) {
            builder.addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                chain.proceed(req)
            }
        }

        if (ignoreSsl) {
            try {
                val trustAll = @SuppressLint("CustomX509TrustManager") object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAll)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {
            }
        }
        return builder.build()
    }

    @OptIn(UnstableApi::class)
    fun buildDataSourceFactory(ignoreSsl: Boolean, token: String): OkHttpDataSource.Factory {
        return OkHttpDataSource.Factory(createOkHttpClient(ignoreSsl, token))
    }

    fun resolveApiUrl(baseUrl: String, apiPath: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val path = if (apiPath.startsWith("/")) apiPath else "/$apiPath"
        return base + path
    }

    fun resolveMediaUrl(baseUrl: String, relativeOrFull: String, token: String = ""): String {
        val raw = relativeOrFull.trim()
        if (raw.isEmpty()) return ""
        val resolved = when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("/api/") -> resolveApiUrl(baseUrl, raw)
            raw.startsWith("/") -> {
                val origin = extractOrigin(baseUrl)
                origin + raw
            }
            else -> baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
        }
        if (token.isBlank()) return resolved
        val sep = if (resolved.contains("?")) "&" else "?"
        return resolved + sep + "token=" + URLEncoder.encode(token, "UTF-8")
    }

    private fun extractOrigin(baseUrl: String): String {
        val m = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(baseUrl.trim())
        return m?.groupValues?.get(1) ?: baseUrl.trim().trimEnd('/')
    }

    private suspend fun executeJson(
        baseUrl: String,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        token: String = "",
        ignoreSsl: Boolean = true
    ): Any? = withContext(Dispatchers.IO) {
        val client = createOkHttpClient(ignoreSsl, token)
        val url = resolveApiUrl(baseUrl, path)
        val reqBuilder = Request.Builder().url(url)
        when (method.uppercase()) {
            "POST" -> reqBuilder.post((body?.toString() ?: "{}").toRequestBody(JSON_MEDIA))
            "PUT" -> reqBuilder.put((body?.toString() ?: "{}").toRequestBody(JSON_MEDIA))
            "DELETE" -> reqBuilder.delete()
            else -> reqBuilder.get()
        }

        val response = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            throw ApiException("无法连接服务器: ${e.message ?: "网络错误"}")
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                if (!resp.isSuccessful) {
                    throw ApiException("服务器返回错误 (${resp.code})", resp.code)
                }
                throw ApiException("服务器返回格式异常")
            }

            val code = json.optInt("code", if (resp.isSuccessful) 0 else resp.code)
            if (resp.code == 401 || code == 401) {
                throw ApiException(json.optString("message", "登录已过期，请重新登录"), 401)
            }
            if (code != 0) {
                throw ApiException(json.optString("message", "请求失败 ($code)"), code)
            }
            json.opt("data")
        }
    }

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        ignoreSsl: Boolean
    ): LoginResult {
        val payload = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        val data = executeJson(baseUrl, "/api/v1/auth/login", "POST", payload, "", ignoreSsl)
            as? JSONObject ?: throw ApiException("登录响应异常")
        val token = data.optString("token", "")
        if (token.isBlank()) throw ApiException("未获取到登录 Token")
        val user = data.optJSONObject("user")
        return LoginResult(
            token = token,
            username = user?.optString("username", username) ?: username,
            nickname = user?.optString("nickname", username) ?: username,
            role = user?.optString("role", "user") ?: "user"
        )
    }

    suspend fun getDevices(baseUrl: String, token: String, ignoreSsl: Boolean): List<DeviceItem> {
        val arr = executeJson(baseUrl, "/api/v1/devices", "GET", null, token, ignoreSsl)
            as? JSONArray ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DeviceItem(
                id = o.optInt("id"),
                name = o.optString("name", "摄像机 #${o.optInt("id")}"),
                protocol = o.optString("protocol", ""),
                host = o.optString("host", ""),
                status = o.optString("status", "offline")
            )
        }
    }

    suspend fun startStream(
        baseUrl: String,
        token: String,
        deviceId: Int,
        mode: String = "hls",
        quality: String = "hd",
        ignoreSsl: Boolean
    ): StreamSession {
        val payload = JSONObject().apply {
            put("mode", mode)
            put("quality", quality)
        }
        val data = executeJson(
            baseUrl,
            "/api/v1/devices/$deviceId/streams",
            "POST",
            payload,
            token,
            ignoreSsl
        ) as? JSONObject ?: throw ApiException("取流响应异常")

        return StreamSession(
            key = data.optString("key", ""),
            deviceId = data.optInt("device_id", deviceId),
            mode = data.optString("mode", mode),
            status = data.optString("status", ""),
            hlsPath = data.optString("hls_path", ""),
            flvPath = data.optString("flv_path", ""),
            error = data.optString("error", "")
        )
    }

    suspend fun stopStream(baseUrl: String, token: String, key: String, ignoreSsl: Boolean) {
        if (key.isBlank()) return
        try {
            executeJson(baseUrl, "/api/v1/streams/$key", "DELETE", null, token, ignoreSsl)
        } catch (_: Exception) {
        }
    }

    suspend fun getRecordings(
        baseUrl: String,
        token: String,
        date: String,
        deviceId: Int? = null,
        ignoreSsl: Boolean
    ): List<RecordingItem> {
        val query = buildString {
            append("/api/v1/recordings?date=")
            append(URLEncoder.encode(date, "UTF-8"))
            if (deviceId != null) {
                append("&device_id=").append(deviceId)
            }
        }
        val arr = executeJson(baseUrl, query, "GET", null, token, ignoreSsl)
            as? JSONArray ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val startedAt = o.optString("started_at", "")
            val endedAt = o.optString("ended_at", "")
            val duration = o.optInt("duration", 0)
            val startSec = parseEpochSec(startedAt)
            val endSec = parseEpochSec(endedAt).let { parsed ->
                when {
                    parsed > startSec -> parsed
                    duration > 0 -> startSec + duration
                    else -> startSec + 300
                }
            }
            RecordingItem(
                id = o.optInt("id"),
                deviceId = o.optInt("device_id"),
                deviceName = o.optString("device_name", ""),
                name = o.optString("name", ""),
                type = o.optString("type", ""),
                duration = if (duration > 0) duration else (endSec - startSec).toInt().coerceAtLeast(1),
                sizeText = o.optString("size_text", ""),
                startedAt = startedAt,
                endedAt = endedAt,
                startEpochSec = startSec,
                endEpochSec = endSec,
                fileUrl = o.optString("file_url", ""),
                playUrl = o.optString("play_url", "")
            )
        }.sortedBy { it.startEpochSec }
    }

    suspend fun getRecordingPlayInfo(
        baseUrl: String,
        token: String,
        recordingId: Int,
        ignoreSsl: Boolean
    ): PlaybackInfo {
        val data = executeJson(
            baseUrl,
            "/api/v1/recordings/$recordingId/play",
            "GET",
            null,
            token,
            ignoreSsl
        ) as? JSONObject ?: throw ApiException("回放信息获取失败")
        return PlaybackInfo(
            mode = data.optString("mode", "file"),
            url = data.optString("url", ""),
            codec = data.optString("codec", "")
        )
    }

    suspend fun getEvents(
        baseUrl: String,
        token: String,
        date: String,
        deviceId: Int? = null,
        ignoreSsl: Boolean
    ): List<EventItem> {
        val query = buildString {
            append("/api/v1/events?page_size=100&date=")
            append(URLEncoder.encode(date, "UTF-8"))
            if (deviceId != null) {
                append("&device_id=").append(deviceId)
            }
        }
        val data = executeJson(baseUrl, query, "GET", null, token, ignoreSsl)
            as? JSONObject ?: return emptyList()
        val arr = data.optJSONArray("list") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val createdAt = o.optString("created_at", "")
            EventItem(
                id = o.optInt("id"),
                deviceId = o.optInt("device_id"),
                deviceName = o.optString("device_name", ""),
                type = o.optString("type", ""),
                typeName = o.optString("type_name", o.optString("type", "")),
                title = o.optString("title", ""),
                snapshotUrl = o.optString("snapshot_url", ""),
                createdAt = createdAt,
                epochSec = parseEpochSec(createdAt)
            )
        }.sortedBy { it.epochSec }
    }

    fun parseEpochSec(dateTime: String): Long {
        if (dateTime.isBlank()) return 0L
        return try {
            synchronized(dateTimeFormat) {
                (dateTimeFormat.parse(dateTime)?.time ?: 0L) / 1000L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
