package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/**
 * Foreground Service featuring MediaSessionCompat and MediaStyle Notification
 * with Play, Pause, Next, Previous, and Stop controls on both notification shade
 * and Android lock screen.
 */
class YouTubeBackgroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        const val CHANNEL_ID = "youtube_background_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START_PLAYBACK"
        const val ACTION_STOP = "com.example.action.STOP_PLAYBACK"
        const val ACTION_PLAY = "com.example.action.PLAY"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_PREVIOUS = "com.example.action.PREVIOUS"

        fun startService(context: Context) {
            val intent = Intent(context, YouTubeBackgroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, YouTubeBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()

        // Acquire a Partial WakeLock so CPU doesn't sleep with screen turned off
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YouTubeBackgroundService::WakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }

        // Listen for playback state changes triggered by the WebView or controller
        YouTubePlaybackController.onStateChanged = { isPlaying ->
            updatePlaybackState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            )
            updateNotification()
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "YouTubeBackgroundService").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    YouTubePlaybackController.play()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification()
                }

                override fun onPause() {
                    YouTubePlaybackController.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification()
                }

                override fun onSkipToNext() {
                    YouTubePlaybackController.skipNext()
                }

                override fun onSkipToPrevious() {
                    YouTubePlaybackController.skipPrevious()
                }

                override fun onStop() {
                    stopForegroundPlayback()
                    stopSelf()
                }
            })
        }
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                YouTubePlaybackController.play()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                updateNotification()
            }
            ACTION_PAUSE -> {
                YouTubePlaybackController.pause()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                updateNotification()
            }
            ACTION_NEXT -> {
                YouTubePlaybackController.skipNext()
            }
            ACTION_PREVIOUS -> {
                YouTubePlaybackController.skipPrevious()
            }
            else -> {
                startForegroundPlayback()
            }
        }
        return START_STICKY
    }

    private fun startForegroundPlayback() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = YouTubePlaybackController.isPlaying

        // Tap notification to reopen app
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Previous Action PendingIntent
        val prevPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, YouTubeBackgroundService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Play / Pause Toggle Action PendingIntent
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, YouTubeBackgroundService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Next Action PendingIntent
        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, YouTubeBackgroundService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop Action PendingIntent
        val stopPendingIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, YouTubeBackgroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YouTube Background Player")
            .setContentText(if (isPlaying) "Playing in background" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setOngoing(isPlaying)
            // Lock screen requirement: VISIBILITY_PUBLIC displays full controls on lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Notification Action 0: Previous
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            // Notification Action 1: Play/Pause Toggle
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            // Notification Action 2: Next
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            // Notification Action 3: Stop
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            // MediaStyle Notification with 3 compact view actions (Previous, Play/Pause, Next)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPendingIntent)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YouTube Background Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls for background YouTube audio"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundPlayback() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        YouTubePlaybackController.onStateChanged = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopForegroundPlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
