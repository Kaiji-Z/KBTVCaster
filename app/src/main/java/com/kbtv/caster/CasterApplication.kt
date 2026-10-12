package com.kbtv.caster

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber

class CasterApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_SERVICE = "caster_service"
        const val NOTIFICATION_CHANNEL_PLAYBACK = "caster_playback"

        /**
         * Configure XML parser for compatibility with custom Android ROMs
         * that may not have default SAX parser configured
         */
        init {
            try {
                // Set the SAX driver property for XML parsing compatibility
                System.setProperty("org.xml.sax.driver", "org.xmlpull.v1.sax2.Driver")
            } catch (e: Exception) {
                // Ignore if property can't be set
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber — verbose logging only in debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Create notification channels
        createNotificationChannels()

        Timber.d("CasterApplication initialized")
        // Surface vendored Cling's java.util.logging (FINE) in debug builds —
        // the router's protocol matching is otherwise a black box on Android.
        if (BuildConfig.DEBUG) {
            try {
                java.util.logging.LogManager.getLogManager().getLogger("").apply {
                    level = java.util.logging.Level.FINE
                    handlers?.forEach { it.level = java.util.logging.Level.FINE }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                "投屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "投屏服务通知"
                setShowBadge(false)
            }

            val playbackChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PLAYBACK,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "播放控制通知"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(
                listOf(serviceChannel, playbackChannel)
            )
        }
    }
}
