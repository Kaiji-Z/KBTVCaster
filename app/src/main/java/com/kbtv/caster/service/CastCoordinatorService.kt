package com.kbtv.caster.service

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.kbtv.caster.ui.MainActivity
import com.kbtv.caster.service.protocol.CastProtocol
import com.kbtv.caster.service.protocol.DlnaProtocol
import com.kbtv.caster.service.protocol.isHlsStream
import timber.log.Timber

/**
 * Main coordinator service that manages all casting protocols.
 *
 * Holds a single shared ExoPlayer and a list of [CastProtocol]s. Each protocol
 * discovers itself on the network, receives commands from a sender, and
 * forwards them back here via [onCommand] (PlaybackSink). The service owns the
 * player and the rendering decisions (HLS detection, Baidu UA, foregrounding),
 * so protocols stay thin transport adapters.
 *
 * Currently registered: [DlnaProtocol]. A future mirror protocol (companion
 * App + MediaProjection) plugs in by adding to [protocols].
 */
class CastCoordinatorService : Service(), CastProtocol.PlaybackSink {

    private val binder = LocalBinder()

    /** Registered casting protocols. Add future protocols (mirror, …) here. */
    private val protocols: List<CastProtocol> = listOf(DlnaProtocol())

    // Service status
    private val _serviceStatus = MutableLiveData<ServiceStatus>(ServiceStatus.STOPPED)
    val serviceStatus: LiveData<ServiceStatus> = _serviceStatus

    private val _deviceName = MutableLiveData<String>("")
    val deviceName: LiveData<String> = _deviceName

    private val _rendererReady = MutableLiveData<Boolean>(false)
    val rendererReady: LiveData<Boolean> = _rendererReady

    // Server state
    @Volatile private var isRunning = false

    // ExoPlayer for direct playback
    private var exoPlayer: ExoPlayer? = null

    // Auto-restart handler
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var restartAttempts = 0
    private val maxRestartAttempts = 5
    private val restartDelayMillis = 5000L

    enum class ServiceStatus {
        STOPPED, STARTING, RUNNING, ERROR
    }

    inner class LocalBinder : Binder() {
        fun getService(): CastCoordinatorService = this@CastCoordinatorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.d("CastCoordinatorService created")
        // 设备名称固定为"凯机投屏"
        _deviceName.value = "凯机投屏"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            else -> start() // Auto-start on default
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    /**
     * Start all casting services with auto-recovery
     * Uses TVRemoteIME's DLNA implementation
     */
    private fun start() {
        if (isRunning) return

        Timber.d("Starting CastCoordinatorService...")
        // Call startForeground immediately to meet Android 8+ 5-second requirement
        showNotification("凯机投屏服务启动中...", "")
        _serviceStatus.value = ServiceStatus.STARTING

        try {
            // Initialize ExoPlayer
            initializePlayer()

            // Start every registered casting protocol (DLNA, future mirror, …)
            protocols.forEach { protocol ->
                try {
                    protocol.start(this, this)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start ${protocol.name} protocol")
                }
            }
            _rendererReady.value = true

            isRunning = true
            restartAttempts = 0
            _serviceStatus.value = ServiceStatus.RUNNING

            Timber.d("CastCoordinatorService started successfully")
            showNotification("凯机投屏服务运行中", "点击打开遥控器")

        } catch (e: Exception) {
            Timber.e(e, "Failed to start CastCoordinatorService")
            _serviceStatus.value = ServiceStatus.ERROR
            scheduleRestart()
        }
    }

    /**
     * Initialize ExoPlayer for media playback
     */
    private fun initializePlayer() {
        if (exoPlayer == null) {
            try {
                exoPlayer = ExoPlayer.Builder(this).build()
                exoPlayer?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Timber.d("ExoPlayer playback state: $playbackState")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Timber.d("ExoPlayer isPlaying: $isPlaying")
                    }
                })
                Timber.d("ExoPlayer initialized successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize ExoPlayer")
            }
        }
    }

    /**
     * Stop all casting services
     */
    private fun stop() {
        // Clear any pending restart callbacks
        restartHandler.removeCallbacksAndMessages(null)
        if (!isRunning) return

        Timber.d("Stopping CastCoordinatorService...")

        try {
            // Stop every registered protocol
            protocols.forEach { protocol ->
                try {
                    protocol.stop()
                } catch (e: Exception) {
                    Timber.w("Error stopping ${protocol.name}: ${e.message}")
                }
            }

            // Stop ExoPlayer — separate try/catch so release runs even if stop throws
            try { exoPlayer?.stop() } catch (e: Exception) { Timber.w("Error stopping ExoPlayer: ${e.message}") }
            try { exoPlayer?.release() } catch (e: Exception) { Timber.w("Error releasing ExoPlayer: ${e.message}") }
            exoPlayer = null
            Timber.d("ExoPlayer stopped")

        } catch (e: Exception) {
            Timber.e(e, "Error during stop")
        }

        isRunning = false
        _serviceStatus.value = ServiceStatus.STOPPED
        _rendererReady.value = false

        Timber.d("CastCoordinatorService stopped")
    }

    /**
     * Show foreground service notification
     */
    private fun showNotification(title: String, content: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Schedule restart after failure
     */
    private fun scheduleRestart() {
        if (restartAttempts < maxRestartAttempts) {
            restartAttempts++
            Timber.d("Scheduling restart attempt ${restartAttempts}/${maxRestartAttempts} in ${restartDelayMillis}ms")
            restartHandler.postDelayed({
                start()
            }, restartDelayMillis)
        } else {
            Timber.e("Max restart attempts reached, giving up")
        }
    }

    /**
     * Get ExoPlayer instance for video rendering
     */
    fun getExoPlayer(): ExoPlayer? = exoPlayer

    // ===== CastProtocol.PlaybackSink: commands from registered protocols =====

    override fun onCommand(command: CastProtocol.PlaybackCommand) {
        runOnUiThread {
            try {
                when (command) {
                    is CastProtocol.PlaybackCommand.Play -> handlePlay(command.uri)
                    CastProtocol.PlaybackCommand.Pause -> exoPlayer?.pause()
                    CastProtocol.PlaybackCommand.Resume -> exoPlayer?.play()
                    CastProtocol.PlaybackCommand.Stop -> exoPlayer?.stop()
                    is CastProtocol.PlaybackCommand.Seek -> exoPlayer?.seekTo(command.positionMs)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle playback command: $command")
            }
        }
    }

    /**
     * Play a media URI on the shared ExoPlayer, with HLS + Baidu-Netdisk UA
     * handling, then bring the UI to the foreground so the user sees playback.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun handlePlay(uri: Uri) {
        Timber.d("Play: $uri")
        val player = exoPlayer ?: return
        val uriString = uri.toString()

        if (isHlsStream(uriString)) {
            val isBaiduNetdisk = uriString.contains("pan.baidu.com")
            val dataSourceFactory = if (isBaiduNetdisk) {
                Timber.d("Detected Baidu Netdisk URL, setting custom User-Agent")
                DefaultHttpDataSource.Factory()
                    .setUserAgent("pan.baidu.com")
                    .setConnectTimeoutMs(30000)
                    .setReadTimeoutMs(30000)
            } else {
                DefaultDataSource.Factory(this)
            }

            val mediaItem = MediaItem.Builder()
                .setUri(uriString)
                .setMimeType("application/x-mpegURL")
                .build()

            val hlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            player.setMediaSource(hlsMediaSource)
            Timber.d("Using HlsMediaSource for playback")
        } else {
            player.setMediaItem(MediaItem.fromUri(uri))
        }
        player.prepare()
        player.play()
        Timber.d("ExoPlayer started playback: $uri")

        bringUiToFrontIfNeeded()
    }

    /**
     * Bring MainActivity to the foreground when a cast arrives while the app
     * is backgrounded, so the user sees playback start.
     */
    private fun bringUiToFrontIfNeeded() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isForeground = activityManager.runningAppProcesses
            .find { proc -> proc.pid == android.os.Process.myPid() }?.importance
            ?.equals(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            ?: false

        if (isForeground == false) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    /**
     * Run code on UI thread
     */
    private fun runOnUiThread(runnable: () -> Unit) {
        val handler = android.os.Handler(mainLooper)
        handler.post(runnable)
    }

    companion object {
        const val ACTION_START = "com.kbtv.caster.action.START"
        const val ACTION_STOP = "com.kbtv.caster.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "caster_service"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_NAME = "CasterTV Service"
    }
}
