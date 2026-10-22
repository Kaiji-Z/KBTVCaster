package com.kbtv.caster.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kbtv.caster.R
import com.kbtv.caster.service.CastCoordinatorService
import timber.log.Timber

/**
 * 凯机投屏 - 极简待机界面
 * 电视形状设计，打开自动启动投屏服务
 * 完全支持电视遥控器控制
 */
class MainActivity : AppCompatActivity() {

    // UI 元素
    private lateinit var playerView: PlayerView
    private lateinit var mirrorSurface: android.view.SurfaceView
    private lateinit var standbyUi: View
    private lateinit var standbyStatus: TextView
    private lateinit var seekControlUi: View
    private lateinit var seekBar: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView

    // 服务绑定
    private var coordinatorService: CastCoordinatorService? = null
    private var isBound = false

    // 状态
    private var isPlaying = false
    private var hasMediaContent = false

    // UI自动隐藏
    private val uiHandler = Handler(Looper.getMainLooper())
    private var seekHideRunnable: Runnable? = null
    private val UI_HIDE_DELAY = 3000L
    private var playerListener: Player.Listener? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CastCoordinatorService.LocalBinder
            coordinatorService = binder.getService()
            isBound = true
            setupPlayerView()
            observeMirrorState()
            observeServiceStatus()
            // 绑定晚于 surfaceCreated 的情况：补交一次当前 Surface
            mirrorSurface.holder.surface?.let { coordinatorService?.mirrorRenderer?.setSurface(it) }
            Timber.d("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            coordinatorService = null
            isBound = false
            Timber.d("Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        startAndBindService()
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        standbyUi = findViewById(R.id.standbyUi)
        mirrorSurface = findViewById(R.id.mirrorSurface)

        // 镜像 Surface 生命周期交给 SurfaceHolder 回调：创建/销毁即接入/摘除服务侧解码器
        mirrorSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                coordinatorService?.mirrorRenderer?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                coordinatorService?.mirrorRenderer?.setSurface(null)
            }
        })

        // 进度条UI
        seekControlUi = findViewById(R.id.seekControlUi)
        seekBar = findViewById(R.id.seekBar)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)

        // 确保待机界面可见
        standbyStatus = findViewById(R.id.standbyStatus)
        Timber.d("Standby UI visibility: ${standbyUi.visibility}")
        Timber.d("PlayerView visibility: ${playerView.visibility}")
    }

    private fun startAndBindService() {
        // 启动服务
        val intent = Intent(this, CastCoordinatorService::class.java).apply {
            action = CastCoordinatorService.ACTION_START
        }
        startService(intent)

        // 绑定服务
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupPlayerView() {
        coordinatorService?.getExoPlayer()?.let { exoPlayer ->
            playerView.player = exoPlayer
            Timber.d("PlayerView bound")

            playerListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Timber.d("Playback state changed: $playbackState, isPlaying: $isPlaying, hasMedia: $hasMediaContent")

                    when (playbackState) {
                        Player.STATE_READY -> {
                            hasMediaContent = exoPlayer.mediaItemCount > 0
                            Timber.d("STATE_READY, hasMediaContent: $hasMediaContent")
                            if (hasMediaContent && isPlaying) {
                                showVideo()
                            }
                        }
                        Player.STATE_ENDED -> {
                            Timber.d("STATE_ENDED, stopping playback")
                            stopPlaybackAndReturnToStandby()
                        }
                        Player.STATE_IDLE -> {
                            hasMediaContent = false
                            Timber.d("STATE_IDLE, hiding video")
                            hideVideo()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    this@MainActivity.isPlaying = isPlaying
                    Timber.d("IsPlaying changed: $isPlaying, hasMediaContent: $hasMediaContent")
                    if (isPlaying && hasMediaContent) {
                        showVideo()
                    }
                }
            }
            playerListener?.let { exoPlayer.addListener(it) }
        }
    }

    private fun showVideo() {
        runOnUiThread {
            Timber.d("Showing video, hiding standby UI")
            if (mirrorSurface.visibility != View.VISIBLE) {
                playerView.visibility = View.VISIBLE
                standbyUi.visibility = View.GONE
            }
        }
    }

    private fun hideVideo() {
        runOnUiThread {
            Timber.d("Hiding video, showing standby UI")
            if (mirrorSurface.visibility != View.VISIBLE) {
                playerView.visibility = View.GONE
                standbyUi.visibility = View.VISIBLE
            }
        }
    }

    /** 待机页服务状态胶囊：跟随 CastCoordinatorService 的生命周期状态 */
    private fun observeServiceStatus() {
        coordinatorService?.serviceStatus?.observe(this) { status ->
            val (textRes, color) = when (status) {
                CastCoordinatorService.ServiceStatus.RUNNING ->
                    R.string.service_running to 0xFF7BD88F.toInt()
                CastCoordinatorService.ServiceStatus.ERROR ->
                    R.string.service_error to 0xFFFF7A74.toInt()
                else -> R.string.starting_service to 0xFFE8C46B.toInt()
            }
            standbyStatus.setText(textRes)
            standbyStatus.setTextColor(color)
            standbyStatus.compoundDrawables[0]?.mutate()?.setTint(color)
            Timber.d("Service status: $status")
        }
    }

    /** 镜像会话状态驱动界面切换：镜像时全屏镜像面，结束后回到播放/待机状态 */
    private fun observeMirrorState() {
        // sessionActive, not rendering: the surface must exist before the
        // first frame can render — gating visibility on the first frame
        // deadlocks the pipeline with a black screen.
        coordinatorService?.mirrorRenderer?.sessionActive?.observe(this) { active ->
            if (active) {
                Timber.d("Mirror session active, showing mirror surface")
                playerView.visibility = View.GONE
                standbyUi.visibility = View.GONE
                mirrorSurface.visibility = View.VISIBLE
            } else {
                Timber.d("Mirror session ended, hiding mirror surface")
                mirrorSurface.visibility = View.GONE
                // 交回播放状态判断：有内容显示播放器，否则待机
                val player = coordinatorService?.getExoPlayer()
                if (player != null && player.isPlaying) {
                    playerView.visibility = View.VISIBLE
                    standbyUi.visibility = View.GONE
                } else {
                    playerView.visibility = View.GONE
                    standbyUi.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * 完全停止播放，返回待机界面
     */
    private fun stopPlaybackAndReturnToStandby() {
        coordinatorService?.getExoPlayer()?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        hasMediaContent = false
        isPlaying = false
        hideVideo()
        Timber.d("Stopped playback, returned to standby")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Timber.d("Key event: ${event.keyCode}, action: ${event.action}")
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Timber.d("onKeyDown: keyCode=$keyCode")
        
        // 电视遥控器完全控制
        when (keyCode) {
            // 播放控制
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                coordinatorService?.getExoPlayer()?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                coordinatorService?.getExoPlayer()?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlaybackAndReturnToStandby()
                return true
            }

            // 前进/后退 (±10秒)
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-10000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(10000)
                return true
            }

            // 方向键控制
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekRelative(-10000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekRelative(10000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                adjustVolume(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                adjustVolume(-1)
                return true
            }

            // OK/选择键 = 暂停/播放
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                Timber.d("OK/Center key pressed, toggling play/pause")
                togglePlayPause()
                return true
            }

            // 返回键 = 停止播放 或 最小化到后台
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (isPlaying || hasMediaContent) {
                    stopPlaybackAndReturnToStandby()
                } else {
                    Timber.d("Minimizing app to background, service keeps running")
                    moveTaskToBack(true)
                }
                return true
            }

            // 静音键
            KeyEvent.KEYCODE_MUTE -> {
                adjustVolume(-100)
                return true
            }

            // 音量控制
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustVolume(1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustVolume(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun togglePlayPause() {
        coordinatorService?.getExoPlayer()?.let { player ->
            Timber.d("togglePlayPause: isPlaying=${player.isPlaying}")
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    private fun adjustVolume(delta: Int) {
        coordinatorService?.let { service ->
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val newVolume = if (delta == -100) {
                    0
                } else {
                    (currentVolume + delta * maxVolume / 20).coerceIn(0, maxVolume)
                }
                // FLAG_SHOW_UI 显示系统音量UI
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVolume, android.media.AudioManager.FLAG_SHOW_UI)
                Timber.d("Volume: $newVolume/$maxVolume")
            } catch (e: Exception) {
                Timber.e(e, "Failed to adjust volume")
            }
        }
    }

    private fun seekRelative(deltaMs: Long) {
        coordinatorService?.getExoPlayer()?.let { player ->
            if (player.duration > 0) {
                val newPosition = (player.currentPosition + deltaMs).coerceIn(0, player.duration)
                player.seekTo(newPosition)
                showSeekUI(player.currentPosition, player.duration)
                Timber.d("Seek to: $newPosition/${player.duration}")
            }
        }
    }

    /**
     * 显示进度条控制UI
     */
    private fun showSeekUI(position: Long, duration: Long) {
        seekHideRunnable?.let { uiHandler.removeCallbacks(it) }
        
        currentTime.text = formatTime(position)
        totalTime.text = formatTime(duration)
        
        val progress = (position * 100 / duration).toInt()
        seekBar.progress = progress
        
        seekControlUi.visibility = View.VISIBLE
        
        seekHideRunnable = Runnable {
            seekControlUi.visibility = View.GONE
        }
        uiHandler.postDelayed(seekHideRunnable!!, UI_HIDE_DELAY)
    }

    /**
     * 格式化时间为 mm:ss
     */
    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        seekHideRunnable?.let { uiHandler.removeCallbacks(it) }
        // Remove player listener to prevent Activity context leak
        coordinatorService?.getExoPlayer()?.let { player ->
            playerListener?.let { player.removeListener(it) }
        }
        playerListener = null
        playerView.player = null
        coordinatorService?.mirrorRenderer?.setSurface(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
