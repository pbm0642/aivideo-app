package com.aivideo.app

data class SavedAccount(
    val address: String,
    val username: String,
    val password: String,
    val useHttps: Boolean
) {
    fun normalizedBaseUrl(): String {
        val raw = address.trim().trimEnd('/')
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            return raw
        }
        val scheme = if (useHttps) "https://" else "http://"
        return scheme + raw
    }

    fun displayHost(): String {
        return address.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
    }
}

data class LoginResult(
    val token: String,
    val username: String,
    val nickname: String,
    val role: String
)

data class DeviceItem(
    val id: Int,
    val name: String,
    val protocol: String,
    val host: String,
    val status: String
) {
    val isOnline: Boolean get() = status.equals("online", ignoreCase = true)
}

data class StreamSession(
    val key: String,
    val deviceId: Int,
    val mode: String,
    val status: String,
    val hlsPath: String,
    val flvPath: String,
    val error: String
)

data class RecordingItem(
    val id: Int,
    val deviceId: Int,
    val deviceName: String,
    val name: String,
    val type: String,
    val duration: Int,
    val sizeText: String,
    val startedAt: String,
    val endedAt: String,
    val startEpochSec: Long,
    val endEpochSec: Long,
    val fileUrl: String,
    val playUrl: String
)

data class PlaybackInfo(
    val mode: String,
    val url: String,
    val codec: String
)

data class EventItem(
    val id: Int,
    val deviceId: Int,
    val deviceName: String,
    val type: String,
    val typeName: String,
    val title: String,
    val snapshotUrl: String,
    val createdAt: String,
    val epochSec: Long
)

enum class GridLayoutMode(val label: String, val cols: Int, val rows: Int) {
    GRID_1X2("1 x 2", 1, 2),
    GRID_1X3("1 x 3", 1, 3),
    GRID_2X2("2 x 2", 2, 2),
    GRID_2X3("2 x 3", 2, 3);

    val pageSize: Int get() = cols * rows

    companion object {
        fun fromName(name: String?): GridLayoutMode {
            return entries.firstOrNull { it.name == name } ?: GRID_1X2
        }
    }
}
