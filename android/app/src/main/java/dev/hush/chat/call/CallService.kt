package dev.hush.chat.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.hush.chat.HushApp
import dev.hush.chat.MainActivity

/**
 * Foreground service that holds a call open.
 *
 * Android revokes the microphone as soon as an app that holds it is
 * backgrounded, unless it is running a foreground service with the
 * `microphone` type. Without this, putting a HUSH call in the background and
 * returning to it produces a call that is silently one-way.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerId = intent?.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val isVideo = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false

        startForegroundCompat(buildNotification(peerId, isVideo))

        // START_NOT_STICKY: a call cannot be resumed after process death —
        // the peer connection and every ICE credential are gone. Restarting
        // the service would just show a notification for a call that does not
        // exist.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    } else {
                        0
                    }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(peerId: String, video: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).apply {
                action = ACTION_HANGUP
                putExtra(EXTRA_PEER_ID, peerId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, HushApp.CHANNEL_CALL)
            .setContentTitle(if (video) "Video call" else "Call")
            .setContentText("Connected — peer to peer")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .addAction(0, "End", hangUp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onDestroy() {
        CallManager.get(this).hangUp()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_VIDEO = "video"
        const val ACTION_HANGUP = "dev.hush.chat.HANG_UP"
        private const val NOTIFICATION_ID = 0x4855 // 'HU'
    }
}
