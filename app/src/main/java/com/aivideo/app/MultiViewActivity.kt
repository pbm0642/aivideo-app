package com.aivideo.app

import android.app.DatePickerDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MultiViewActivity : AppCompatActivity() {

    private data class CameraSlot(
        val device: DeviceItem,
        val rootView: View,
        val tvName: TextView,
        val playerView: PlayerView,
        val tvStatus: TextView,
        val tvBadge: TextView,
        val player: ExoPlayer,
        var liveStreamKey: String = "",
        var currentRecording: RecordingItem? = null
    )

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var cameraGrid: GridLayout
    private lateinit var timelinePanel: View
    private lateinit var timelineRuler: TimelineRulerView
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnLive: TextView
    private lateinit var tvDrawerUser: TextView
    private lateinit var tvDrawerHost: TextView
    private lateinit var llSavedServers: LinearLayout

    private lateinit var currentAccount: SavedAccount
    private var currentToken: String = ""
    private var ignoreSsl: Boolean = true

    private var allDevices: List<DeviceItem> = emptyList()
    private var visibleDevices: List<DeviceItem> = emptyList()
    private var currentPage: Int = 0
    private var layoutMode: GridLayoutMode = GridLayoutMode.GRID_1X2
    private var showInfoBar: Boolean = true
    private var keepAspectRatio: Boolean = true
    private var singleFullscreenDeviceId: Int? = null

    private val slots = mutableListOf<CameraSlot>()
    private var focusedDeviceId: Int? = null

    private var selectedDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    private var allRecordings: List<RecordingItem> = emptyList()
    private var allEvents: List<EventItem> = emptyList()

    private var isLiveMode: Boolean = true
    private var isPaused: Boolean = false
    private var playbackSpeed: Float = 1.0f
    private var currentEpochSec: Long = System.currentTimeMillis() / 1000L
    private var lastBackPressMs: Long = 0L
    private var tickerJob: Job? = null

    private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val account = SessionPrefs.getCurrentAccount(this)
        val token = SessionPrefs.getToken(this)
        if (account == null || token.isBlank()) {
            redirectToLogin(true)
            return
        }
        currentAccount = account
        currentToken = token
        ignoreSsl = SessionPrefs.isIgnoreSsl(this)
        layoutMode = SessionPrefs.getLayoutMode(this)
        showInfoBar = SessionPrefs.isShowInfoBar(this)
        keepAspectRatio = SessionPrefs.isKeepAspectRatio(this)

        if (SessionPrefs.isKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setContentView(R.layout.activity_multi_view)
        bindViews()
        setupTopBar()
        setupTimelineControls()
        setupDrawer()
        setupBackNavigation()

        loadInitialData()
        startTicker()
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        cameraGrid = findViewById(R.id.cameraGrid)
        timelinePanel = findViewById(R.id.timelinePanel)
        timelineRuler = findViewById(R.id.timelineRuler)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnLive = findViewById(R.id.btnLive)
        tvDrawerUser = findViewById(R.id.tvDrawerUser)
        tvDrawerHost = findViewById(R.id.tvDrawerHost)
        llSavedServers = findViewById(R.id.llSavedServers)
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.btnDrawer).setOnClickListener {
            renderDrawerServers()
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.btnPageSwitch).setOnClickListener {
            val totalPages = totalPageCount()
            if (totalPages > 1) {
                currentPage = (currentPage + 1) % totalPages
                singleFullscreenDeviceId = null
                rebuildGridAndPlay()
            } else {
                Toast.makeText(this, "当前只有 1 页", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnToggleTimeline).setOnClickListener {
            timelinePanel.visibility =
                if (timelinePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<View>(R.id.btnMoreMenu).setOnClickListener {
            showLayoutBottomSheet()
        }
    }

    private fun setupTimelineControls() {
        timelineRuler.onScrubbing = { epochSec ->
            currentEpochSec = epochSec
            updateTimeBubble(epochSec)
        }
        timelineRuler.onScrubFinished = { epochSec ->
            currentEpochSec = epochSec
            updateTimeBubble(epochSec)
            val nowSec = System.currentTimeMillis() / 1000L
            if (nowSec - epochSec <= 5) {
                switchToLive()
            } else {
                seekAllToEpoch(epochSec)
            }
        }

        btnPlayPause.setOnClickListener {
            isPaused = !isPaused
            btnPlayPause.text = if (isPaused) "▶" else "⏸"
            slots.forEach { slot ->
                if (isPaused) slot.player.pause() else slot.player.play()
            }
        }

        btnSpeed.setOnClickListener {
            playbackSpeed = when (playbackSpeed) {
                1.0f -> 2.0f
                2.0f -> 0.5f
                else -> 1.0f
            }
            btnSpeed.text = "${playbackSpeed}x"
            if (!isLiveMode) {
                slots.forEach { it.player.playbackParameters = PlaybackParameters(playbackSpeed) }
            }
        }

        tvCurrentTime.setOnClickListener { showDatePicker() }
        findViewById<View>(R.id.btnPickDate).setOnClickListener { showDatePicker() }
        btnLive.setOnClickListener { switchToLive() }

        findViewById<View>(R.id.btnPrevEvent).setOnClickListener { jumpEvent(previous = true) }
        findViewById<View>(R.id.btnNextEvent).setOnClickListener { jumpEvent(previous = false) }
        findViewById<View>(R.id.btnRewind10).setOnClickListener {
            val target = (currentEpochSec - 10).coerceAtLeast(0)
            currentEpochSec = target
            timelineRuler.setCurrentEpochSec(target)
            updateTimeBubble(target)
            seekAllToEpoch(target)
        }
        findViewById<View>(R.id.btnForward10).setOnClickListener {
            val nowSec = System.currentTimeMillis() / 1000L
            val target = (currentEpochSec + 10).coerceAtMost(nowSec)
            currentEpochSec = target
            timelineRuler.setCurrentEpochSec(target)
            updateTimeBubble(target)
            if (nowSec - target <= 3) switchToLive() else seekAllToEpoch(target)
        }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener {
            if (!timelineRuler.zoomIn()) {
                Toast.makeText(this, "已是最大刻度", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener {
            if (!timelineRuler.zoomOut()) {
                Toast.makeText(this, "已是最小刻度", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupDrawer() {
        tvDrawerUser.text = currentAccount.username
        tvDrawerHost.text = currentAccount.displayHost()

        findViewById<View>(R.id.btnDrawerLogout).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            SessionPrefs.clearToken(this)
            redirectToLogin(true)
        }

        findViewById<View>(R.id.navMultiView).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.navCameras).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showCamerasDialog()
        }

        findViewById<View>(R.id.navRecordings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showRecordingsDialog()
        }

        findViewById<View>(R.id.navEvents).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showEventsDialog()
        }

        findViewById<View>(R.id.navWebAdmin).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = Intent(this, WebViewerActivity::class.java).apply {
                putExtra(WebViewerActivity.EXTRA_URL, currentAccount.normalizedBaseUrl())
            }
            startActivity(intent)
        }

        renderDrawerServers()
    }

    private fun renderDrawerServers() {
        llSavedServers.removeAllViews()
        val history = SessionPrefs.getHistory(this)
        for (acc in history) {
            val isCurrent = acc.displayHost().equals(currentAccount.displayHost(), ignoreCase = true) &&
                acc.username == currentAccount.username

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(44, 24, 36, 24)
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvHost = TextView(this).apply {
                text = acc.displayHost()
                setTextColor(Color.parseColor(if (isCurrent) "#FFFFFF" else "#CBD5E1"))
                textSize = 15f
                maxLines = 1
            }

            val tvUser = TextView(this).apply {
                text = acc.username
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                maxLines = 1
            }

            col.addView(tvHost)
            col.addView(tvUser)
            row.addView(col)

            if (isCurrent) {
                val tvCheck = TextView(this).apply {
                    text = "✓"
                    setTextColor(Color.parseColor("#1E88E5"))
                    textSize = 18f
                }
                row.addView(tvCheck)
            }

            row.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                if (!isCurrent) {
                    switchToSavedAccount(acc)
                }
            }

            llSavedServers.addView(row)
        }
    }

    private fun switchToSavedAccount(target: SavedAccount) {
        if (target.password.isBlank()) {
            SessionPrefs.saveSession(this, target, "", false)
            redirectToLogin(true)
            return
        }
        Toast.makeText(this, "正在切换到 ${target.displayHost()}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = ApiClient.login(
                    baseUrl = target.normalizedBaseUrl(),
                    username = target.username,
                    password = target.password,
                    ignoreSsl = ignoreSsl
                )
                SessionPrefs.saveSession(
                    context = this@MultiViewActivity,
                    account = target,
                    token = result.token,
                    rememberLogin = true
                )
                currentAccount = target
                currentToken = result.token
                tvDrawerUser.text = target.username
                tvDrawerHost.text = target.displayHost()
                currentPage = 0
                singleFullscreenDeviceId = null
                loadInitialData()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MultiViewActivity,
                    "切换失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            try {
                allDevices = ApiClient.getDevices(
                    baseUrl = currentAccount.normalizedBaseUrl(),
                    token = currentToken,
                    ignoreSsl = ignoreSsl
                )
                applySelectedDevices()
                rebuildGridAndPlay()
                refreshTimelineDataForDate(selectedDate)
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    Toast.makeText(this@MultiViewActivity, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show()
                    SessionPrefs.clearToken(this@MultiViewActivity)
                    redirectToLogin(true)
                } else {
                    Toast.makeText(this@MultiViewActivity, e.message ?: "加载设备失败", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiViewActivity, e.message ?: "网络异常", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applySelectedDevices() {
        val savedIds = SessionPrefs.getSelectedDeviceIds(this)
        visibleDevices = if (savedIds.isEmpty()) {
            allDevices
        } else {
            val map = allDevices.associateBy { it.id }
            val filtered = savedIds.mapNotNull { map[it] }
            if (filtered.isEmpty()) allDevices else filtered
        }
    }

    private fun totalPageCount(): Int {
        if (visibleDevices.isEmpty()) return 1
        return ((visibleDevices.size - 1) / layoutMode.pageSize) + 1
    }

    @OptIn(UnstableApi::class)
    private fun rebuildGridAndPlay() {
        releaseAllSlots()
        cameraGrid.removeAllViews()

        val displayList = if (singleFullscreenDeviceId != null) {
            allDevices.filter { it.id == singleFullscreenDeviceId }
        } else {
            val totalPages = totalPageCount()
            if (currentPage >= totalPages) currentPage = 0
            val start = currentPage * layoutMode.pageSize
            visibleDevices.drop(start).take(layoutMode.pageSize)
        }

        val cols = if (singleFullscreenDeviceId != null) 1 else layoutMode.cols
        val rows = if (singleFullscreenDeviceId != null) 1 else layoutMode.rows
        cameraGrid.columnCount = cols
        cameraGrid.rowCount = rows

        tvPageIndicator.text = if (singleFullscreenDeviceId != null) {
            "单路全屏（双击返回）"
        } else {
            "${currentPage + 1} / ${totalPageCount()} 页"
        }

        if (displayList.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "暂无摄像机，请在左侧菜单进入网页后台添加"
                setTextColor(Color.parseColor("#9AA4B0"))
                gravity = Gravity.CENTER
                textSize = 15f
            }
            cameraGrid.addView(emptyTv, GridLayout.LayoutParams().apply {
                width = GridLayout.LayoutParams.MATCH_PARENT
                height = GridLayout.LayoutParams.MATCH_PARENT
            })
            return
        }

        val dataSourceFactory = ApiClient.buildDataSourceFactory(ignoreSsl, currentToken)
        val inflater = LayoutInflater.from(this)

        for (index in 0 until (cols * rows)) {
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                rowSpec = GridLayout.spec(index / cols, 1f)
                columnSpec = GridLayout.spec(index % cols, 1f)
            }

            if (index >= displayList.size) {
                val blank = View(this).apply { setBackgroundColor(Color.BLACK) }
                cameraGrid.addView(blank, lp)
                continue
            }

            val device = displayList[index]
            val cellView = inflater.inflate(R.layout.item_camera_cell, cameraGrid, false)
            val tvName = cellView.findViewById<TextView>(R.id.tvCameraName)
            val playerView = cellView.findViewById<PlayerView>(R.id.playerView)
            val tvStatus = cellView.findViewById<TextView>(R.id.tvCellStatus)
            val tvBadge = cellView.findViewById<TextView>(R.id.tvCellBadge)

            tvName.text = device.name
            tvName.visibility = if (showInfoBar) View.VISIBLE else View.GONE
            playerView.resizeMode = if (keepAspectRatio) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }

            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
            player.volume = 0f // Multi-view defaults muted to avoid audio echo
            playerView.player = player

            val slot = CameraSlot(
                device = device,
                rootView = cellView,
                tvName = tvName,
                playerView = playerView,
                tvStatus = tvStatus,
                tvBadge = tvBadge,
                player = player
            )

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            tvStatus.visibility = View.GONE
                            tvBadge.visibility = View.VISIBLE
                        }
                        Player.STATE_BUFFERING -> {
                            tvStatus.text = "缓冲中..."
                            tvStatus.visibility = View.VISIBLE
                        }
                        Player.STATE_ENDED -> {
                            if (!isLiveMode) {
                                playNextRecordingSegment(slot)
                            }
                        }
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    tvStatus.text = "播放失败，点击重试"
                    tvStatus.visibility = View.VISIBLE
                }
            })

            var lastTapMs = 0L
            cellView.setOnClickListener {
                val now = System.currentTimeMillis()
                focusedDeviceId = device.id
                updateSlotHighlights()
                updateRulerFilter()
                if (now - lastTapMs < 320) {
                    singleFullscreenDeviceId = if (singleFullscreenDeviceId == device.id) null else device.id
                    rebuildGridAndPlay()
                } else if (tvStatus.visibility == View.VISIBLE && tvStatus.text.contains("失败")) {
                    if (isLiveMode) startLiveForSlot(slot) else seekSlotToEpoch(slot, currentEpochSec)
                }
                lastTapMs = now
            }

            slots.add(slot)
            cameraGrid.addView(cellView, lp)
        }

        if (focusedDeviceId == null || slots.none { it.device.id == focusedDeviceId }) {
            focusedDeviceId = slots.firstOrNull()?.device?.id
        }
        updateSlotHighlights()

        if (isLiveMode) {
            slots.forEach { startLiveForSlot(it) }
        } else {
            seekAllToEpoch(currentEpochSec)
        }
    }

    private fun updateSlotHighlights() {
        slots.forEach { slot ->
            val selected = slot.device.id == focusedDeviceId && slots.size > 1
            slot.rootView.setBackgroundColor(
                if (selected) Color.parseColor("#1E88E5") else Color.parseColor("#111317")
            )
        }
    }

    private fun startLiveForSlot(slot: CameraSlot) {
        slot.currentRecording = null
        slot.tvBadge.text = "LIVE"
        slot.tvBadge.setTextColor(Color.parseColor("#60A5FA"))
        slot.tvStatus.text = "正在连接 ${slot.device.name}..."
        slot.tvStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val session = ApiClient.startStream(
                    baseUrl = currentAccount.normalizedBaseUrl(),
                    token = currentToken,
                    deviceId = slot.device.id,
                    mode = "hls",
                    quality = "hd",
                    ignoreSsl = ignoreSsl
                )
                if (!slots.contains(slot)) return@launch
                slot.liveStreamKey = session.key
                val path = session.hlsPath.ifBlank { session.flvPath }
                val streamUrl = ApiClient.resolveMediaUrl(currentAccount.normalizedBaseUrl(), path, currentToken)
                if (streamUrl.isBlank()) {
                    slot.tvStatus.text = "未获取到流地址"
                    return@launch
                }
                slot.player.playbackParameters = PlaybackParameters(1.0f)
                slot.player.setMediaItem(MediaItem.fromUri(streamUrl))
                slot.player.prepare()
                slot.player.playWhenReady = !isPaused
            } catch (e: Exception) {
                if (slots.contains(slot)) {
                    slot.tvStatus.text = "取流失败\n${e.message ?: ""}"
                    slot.tvStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun switchToLive() {
        isLiveMode = true
        isPaused = false
        btnPlayPause.text = "⏸"
        btnLive.setBackgroundColor(Color.parseColor("#1E88E5"))
        currentEpochSec = System.currentTimeMillis() / 1000L
        timelineRuler.setCurrentEpochSec(currentEpochSec)
        updateTimeBubble(currentEpochSec)
        slots.forEach { startLiveForSlot(it) }
    }

    private fun seekAllToEpoch(epochSec: Long) {
        isLiveMode = false
        isPaused = false
        btnPlayPause.text = "⏸"
        btnLive.setBackgroundColor(Color.parseColor("#4B5563"))

        val targetDate = synchronized(dateOnlyFormat) { dateOnlyFormat.format(Date(epochSec * 1000L)) }
        if (targetDate != selectedDate) {
            selectedDate = targetDate
            lifecycleScope.launch {
                refreshTimelineDataForDate(selectedDate)
                slots.forEach { seekSlotToEpoch(it, epochSec) }
            }
        } else {
            slots.forEach { seekSlotToEpoch(it, epochSec) }
        }
    }

    private fun seekSlotToEpoch(slot: CameraSlot, epochSec: Long) {
        val rec = allRecordings.firstOrNull {
            it.deviceId == slot.device.id && epochSec in it.startEpochSec..it.endEpochSec
        } ?: allRecordings
            .filter { it.deviceId == slot.device.id && it.startEpochSec >= epochSec && it.startEpochSec - epochSec <= 30 }
            .minByOrNull { it.startEpochSec }

        if (rec == null) {
            slot.currentRecording = null
            slot.player.stop()
            slot.tvBadge.text = "回放"
            slot.tvBadge.setTextColor(Color.parseColor("#F59E0B"))
            slot.tvStatus.text = "该时段无录像"
            slot.tvStatus.visibility = View.VISIBLE
            return
        }

        val offsetMs = ((epochSec - rec.startEpochSec).coerceAtLeast(0L)) * 1000L
        slot.tvBadge.text = "回放"
        slot.tvBadge.setTextColor(Color.parseColor("#F59E0B"))

        if (slot.currentRecording?.id == rec.id && slot.player.mediaItemCount > 0) {
            slot.player.seekTo(offsetMs)
            slot.player.playbackParameters = PlaybackParameters(playbackSpeed)
            slot.player.playWhenReady = !isPaused
            slot.tvStatus.visibility = View.GONE
            return
        }

        slot.currentRecording = rec
        slot.tvStatus.text = "正在加载录像..."
        slot.tvStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val playInfo = ApiClient.getRecordingPlayInfo(
                    baseUrl = currentAccount.normalizedBaseUrl(),
                    token = currentToken,
                    recordingId = rec.id,
                    ignoreSsl = ignoreSsl
                )
                if (!slots.contains(slot)) return@launch
                val mediaUrl = ApiClient.resolveMediaUrl(
                    currentAccount.normalizedBaseUrl(),
                    playInfo.url,
                    currentToken
                )
                slot.player.setMediaItem(MediaItem.fromUri(mediaUrl))
                slot.player.prepare()
                slot.player.seekTo(offsetMs)
                slot.player.playbackParameters = PlaybackParameters(playbackSpeed)
                slot.player.playWhenReady = !isPaused
            } catch (e: Exception) {
                if (slots.contains(slot)) {
                    slot.tvStatus.text = "录像加载失败\n${e.message ?: ""}"
                    slot.tvStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun playNextRecordingSegment(slot: CameraSlot) {
        val cur = slot.currentRecording ?: return
        val next = allRecordings
            .filter { it.deviceId == slot.device.id && it.startEpochSec >= cur.endEpochSec - 2 && it.id != cur.id }
            .minByOrNull { it.startEpochSec }
        if (next != null) {
            seekSlotToEpoch(slot, next.startEpochSec)
        }
    }

    private suspend fun refreshTimelineDataForDate(date: String) {
        try {
            allRecordings = ApiClient.getRecordings(
                baseUrl = currentAccount.normalizedBaseUrl(),
                token = currentToken,
                date = date,
                deviceId = null,
                ignoreSsl = ignoreSsl
            )
            allEvents = ApiClient.getEvents(
                baseUrl = currentAccount.normalizedBaseUrl(),
                token = currentToken,
                date = date,
                deviceId = null,
                ignoreSsl = ignoreSsl
            )
            updateRulerFilter()
        } catch (_: Exception) {
        }
    }

    private fun updateRulerFilter() {
        val visibleIds = slots.map { it.device.id }.toSet()
        val targetId = focusedDeviceId
        val recs = if (targetId != null && allRecordings.any { it.deviceId == targetId }) {
            allRecordings.filter { it.deviceId == targetId }
        } else {
            allRecordings.filter { it.deviceId in visibleIds }.ifEmpty { allRecordings }
        }
        val evs = if (targetId != null && allEvents.any { it.deviceId == targetId }) {
            allEvents.filter { it.deviceId == targetId }
        } else {
            allEvents.filter { it.deviceId in visibleIds }.ifEmpty { allEvents }
        }
        timelineRuler.setData(recs, evs)
    }

    private fun jumpEvent(previous: Boolean) {
        val visibleIds = slots.map { it.device.id }.toSet()
        val candidates = allEvents.filter { it.deviceId in visibleIds }.ifEmpty { allEvents }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "$selectedDate 暂无告警事件", Toast.LENGTH_SHORT).show()
            return
        }
        val target = if (previous) {
            candidates.lastOrNull { it.epochSec < currentEpochSec - 2 }
        } else {
            candidates.firstOrNull { it.epochSec > currentEpochSec + 2 }
        }
        if (target == null) {
            Toast.makeText(
                this,
                if (previous) "没有更早的事件了" else "没有更晚的事件了",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        currentEpochSec = target.epochSec
        timelineRuler.setCurrentEpochSec(target.epochSec)
        updateTimeBubble(target.epochSec)
        Toast.makeText(
            this,
            "${target.deviceName} · ${target.typeName} (${target.createdAt.takeLast(8)})",
            Toast.LENGTH_SHORT
        ).show()
        seekAllToEpoch(target.epochSec)
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = currentEpochSec * 1000L
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, day)
                selectedDate = picked
                lifecycleScope.launch {
                    refreshTimelineDataForDate(picked)
                    val firstRec = allRecordings.firstOrNull()
                    if (firstRec != null) {
                        currentEpochSec = firstRec.startEpochSec
                        timelineRuler.setCurrentEpochSec(currentEpochSec)
                        updateTimeBubble(currentEpochSec)
                        seekAllToEpoch(currentEpochSec)
                    } else {
                        Toast.makeText(this@MultiViewActivity, "$picked 暂无录像", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showLayoutBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_menu, null)
        dialog.setContentView(view)

        val opt1x2 = view.findViewById<TextView>(R.id.optLayout1x2)
        val opt1x3 = view.findViewById<TextView>(R.id.optLayout1x3)
        val opt2x2 = view.findViewById<TextView>(R.id.optLayout2x2)
        val opt2x3 = view.findViewById<TextView>(R.id.optLayout2x3)
        val tvInfoCheck = view.findViewById<TextView>(R.id.tvInfoBarCheck)
        val tvAspectCheck = view.findViewById<TextView>(R.id.tvAspectCheck)

        fun styleLayoutBtn(tv: TextView, mode: GridLayoutMode) {
            val active = layoutMode == mode
            tv.setBackgroundColor(if (active) Color.parseColor("#E3F2FD") else Color.TRANSPARENT)
            tv.setTextColor(if (active) Color.parseColor("#1565C0") else Color.parseColor("#1E88E5"))
            tv.setOnClickListener {
                layoutMode = mode
                singleFullscreenDeviceId = null
                currentPage = 0
                SessionPrefs.setLayoutMode(this, mode)
                dialog.dismiss()
                rebuildGridAndPlay()
            }
        }

        styleLayoutBtn(opt1x2, GridLayoutMode.GRID_1X2)
        styleLayoutBtn(opt1x3, GridLayoutMode.GRID_1X3)
        styleLayoutBtn(opt2x2, GridLayoutMode.GRID_2X2)
        styleLayoutBtn(opt2x3, GridLayoutMode.GRID_2X3)

        tvInfoCheck.visibility = if (showInfoBar) View.VISIBLE else View.INVISIBLE
        tvAspectCheck.visibility = if (keepAspectRatio) View.VISIBLE else View.INVISIBLE

        view.findViewById<View>(R.id.optEditLayout).setOnClickListener {
            dialog.dismiss()
            showEditLayoutDialog()
        }

        view.findViewById<View>(R.id.optDownload).setOnClickListener {
            dialog.dismiss()
            downloadActiveRecording()
        }

        view.findViewById<View>(R.id.optInfoBar).setOnClickListener {
            showInfoBar = !showInfoBar
            SessionPrefs.setShowInfoBar(this, showInfoBar)
            tvInfoCheck.visibility = if (showInfoBar) View.VISIBLE else View.INVISIBLE
            slots.forEach { it.tvName.visibility = if (showInfoBar) View.VISIBLE else View.GONE }
        }

        view.findViewById<View>(R.id.optAspectRatio).setOnClickListener {
            keepAspectRatio = !keepAspectRatio
            SessionPrefs.setKeepAspectRatio(this, keepAspectRatio)
            tvAspectCheck.visibility = if (keepAspectRatio) View.VISIBLE else View.INVISIBLE
            val mode = if (keepAspectRatio) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            slots.forEach { it.playerView.resizeMode = mode }
        }

        dialog.show()
    }

    private fun showEditLayoutDialog() {
        if (allDevices.isEmpty()) {
            Toast.makeText(this, "暂无可选摄像机", Toast.LENGTH_SHORT).show()
            return
        }
        val names = allDevices.map { it.name }.toTypedArray()
        val visibleIds = visibleDevices.map { it.id }.toSet()
        val checked = BooleanArray(allDevices.size) { i -> allDevices[i].id in visibleIds }

        AlertDialog.Builder(this)
            .setTitle("编辑版面（勾选要显示的摄像机）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                val chosen = allDevices.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "请至少保留一台摄像机", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                SessionPrefs.setSelectedDeviceIds(this, chosen)
                applySelectedDevices()
                singleFullscreenDeviceId = null
                currentPage = 0
                rebuildGridAndPlay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadActiveRecording() {
        val activeSlot = slots.firstOrNull { it.device.id == focusedDeviceId } ?: slots.firstOrNull()
        val rec = activeSlot?.currentRecording
        if (rec != null) {
            enqueueRecordingDownload(rec)
        } else {
            showRecordingsDialog(downloadMode = true)
        }
    }

    private fun enqueueRecordingDownload(rec: RecordingItem) {
        try {
            val url = ApiClient.resolveMediaUrl(
                currentAccount.normalizedBaseUrl(),
                rec.fileUrl,
                currentToken
            )
            val fileName = "${rec.deviceName}_${rec.startedAt.replace(":", "-").replace(" ", "_")}.mp4"
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setMimeType("video/mp4")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(this, "开始下载录像: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCamerasDialog() {
        if (allDevices.isEmpty()) {
            Toast.makeText(this, "暂无摄像机", Toast.LENGTH_SHORT).show()
            return
        }
        val items = allDevices.map { d ->
            val st = if (d.isOnline) "● 在线" else "○ 离线"
            "${d.name}  [$st]  (${d.host})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("摄像机列表 (${allDevices.size})")
            .setItems(items) { _, which ->
                singleFullscreenDeviceId = allDevices[which].id
                focusedDeviceId = allDevices[which].id
                rebuildGridAndPlay()
            }
            .setPositiveButton("恢复多路") { _, _ ->
                singleFullscreenDeviceId = null
                rebuildGridAndPlay()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRecordingsDialog(downloadMode: Boolean = false) {
        if (allRecordings.isEmpty()) {
            Toast.makeText(this, "$selectedDate 暂无录像", Toast.LENGTH_SHORT).show()
            return
        }
        val items = allRecordings.map { r ->
            "${r.deviceName}  ${r.startedAt.takeLast(8)} (${r.duration}秒 · ${r.sizeText})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (downloadMode) "选择要下载的录像 ($selectedDate)" else "录像列表 ($selectedDate)")
            .setItems(items) { _, which ->
                val rec = allRecordings[which]
                if (downloadMode) {
                    enqueueRecordingDownload(rec)
                } else {
                    focusedDeviceId = rec.deviceId
                    currentEpochSec = rec.startEpochSec
                    timelineRuler.setCurrentEpochSec(currentEpochSec)
                    updateTimeBubble(currentEpochSec)
                    seekAllToEpoch(currentEpochSec)
                }
            }
            .setNeutralButton("切换日期") { _, _ -> showDatePicker() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showEventsDialog() {
        if (allEvents.isEmpty()) {
            Toast.makeText(this, "$selectedDate 暂无告警事件", Toast.LENGTH_SHORT).show()
            return
        }
        val items = allEvents.map { ev ->
            "${ev.createdAt.takeLast(8)}  [${ev.deviceName}] ${ev.typeName}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("告警事件 ($selectedDate)")
            .setItems(items) { _, which ->
                val ev = allEvents[which]
                focusedDeviceId = ev.deviceId
                currentEpochSec = ev.epochSec
                timelineRuler.setCurrentEpochSec(currentEpochSec)
                updateTimeBubble(currentEpochSec)
                seekAllToEpoch(currentEpochSec)
            }
            .setNeutralButton("切换日期") { _, _ -> showDatePicker() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                if (isLiveMode) {
                    currentEpochSec = System.currentTimeMillis() / 1000L
                    timelineRuler.setCurrentEpochSec(currentEpochSec)
                    updateTimeBubble(currentEpochSec)
                } else if (!isPaused) {
                    val activeSlot = slots.firstOrNull { it.device.id == focusedDeviceId && it.currentRecording != null }
                        ?: slots.firstOrNull { it.currentRecording != null }
                    val rec = activeSlot?.currentRecording
                    if (activeSlot != null && rec != null && activeSlot.player.isPlaying) {
                        val posSec = rec.startEpochSec + (activeSlot.player.currentPosition / 1000L)
                        currentEpochSec = posSec
                        timelineRuler.setCurrentEpochSec(posSec)
                        updateTimeBubble(posSec)
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun updateTimeBubble(epochSec: Long) {
        tvCurrentTime.text = synchronized(timeOnlyFormat) {
            timeOnlyFormat.format(Date(epochSec * 1000L))
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                if (singleFullscreenDeviceId != null) {
                    singleFullscreenDeviceId = null
                    rebuildGridAndPlay()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2000) {
                    finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(this@MultiViewActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun releaseAllSlots() {
        val keysToStop = slots.mapNotNull { it.liveStreamKey.takeIf { k -> k.isNotBlank() } }
        slots.forEach { it.player.release() }
        slots.clear()
        if (keysToStop.isNotEmpty()) {
            lifecycleScope.launch {
                keysToStop.forEach { key ->
                    ApiClient.stopStream(currentAccount.normalizedBaseUrl(), currentToken, key, ignoreSsl)
                }
            }
        }
    }

    private fun redirectToLogin(force: Boolean) {
        val intent = Intent(this, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_FORCE_LOGIN, force)
        }
        startActivity(intent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        slots.forEach { it.player.pause() }
    }

    override fun onResume() {
        super.onResume()
        if (!isPaused) {
            slots.forEach { it.player.play() }
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        releaseAllSlots()
        super.onDestroy()
    }
}
