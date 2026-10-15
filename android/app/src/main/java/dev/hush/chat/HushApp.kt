package dev.hush.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HushApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Identity generation does real key derivation, so do it off the main
        // thread. Without this, first launch janks visibly while X25519 keys
        // are generated.
        Thread({ dev.hush.chat.data.SessionManager(this).loadOrCreateIdentity() }, "hush-identity").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        // An ongoing call notification, so the call survives the user leaving
        // the app. Without a foreground service, Android kills the microphone
        // the moment the activity is backgrounded.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                getString(R.string.channel_call),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.channel_call_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.channel_messages_description)
            }
        )
    }

    companion object {
        const val CHANNEL_CALL = "hush_call"
        const val CHANNEL_MESSAGES = "hush_messages"
    }
}
