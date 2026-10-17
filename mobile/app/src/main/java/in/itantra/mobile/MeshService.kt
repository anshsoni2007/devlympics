package `in`.itantra.mobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.json.JSONObject

class MeshService : Service(), LocalTransport.Listener {

    companion object {
        private const val TAG = "SWAR_MeshService"
        const val CHANNEL_ID = "swar_mesh_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: MeshService? = null

        @Volatile
        private var sharedTransport: LocalTransport? = null

        @Volatile
        var uiListener: LocalTransport.Listener? = null

        @Synchronized
        fun getTransport(context: Context): LocalTransport {
            if (sharedTransport == null) {
                val appCtx = context.applicationContext
                val service = instance
                val listener: LocalTransport.Listener = service ?: object : LocalTransport.Listener {
                    override fun event(type: String, data: JSONObject) {
                        uiListener?.event(type, data)
                    }
                    override fun received(message: ItpPacket.Decoded) {
                        uiListener?.received(message)
                    }
                }
                sharedTransport = LocalTransport(appCtx, listener)
            }
            return sharedTransport!!
        }

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, MeshService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start MeshService: ${t.message}")
            }
        }

        fun onAppForegrounded() {
            instance?.handleAppForegrounded()
        }

        fun onAppBackgrounded() {
            instance?.handleAppBackgrounded()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val INACTIVITY_TIMEOUT_MS = 15L * 60L * 1000L // 15 minutes

    @Volatile
    private var isAppForeground: Boolean = false

    @Volatile
    private var isScreenOn: Boolean = true

    private val backgroundDisconnectRunnable = Runnable {
        Log.i(TAG, "15-minute background/screen-off timeout reached: disconnecting active mesh connection")
        sharedTransport?.let { t ->
            if (t.isConnected) {
                t.disconnect()
                updateNotification("SWAR Standby", "Disconnected after 15 min inactive in background")
            }
        }
    }

    private fun handleAppForegrounded() {
        isAppForeground = true
        Log.i(TAG, "App foregrounded: evaluating timeout")
        evaluateBackgroundTimer()
    }

    private fun handleAppBackgrounded() {
        isAppForeground = false
        Log.i(TAG, "App backgrounded: evaluating timeout")
        evaluateBackgroundTimer()
    }

    private fun evaluateBackgroundTimer() {
        timeoutHandler.removeCallbacks(backgroundDisconnectRunnable)
        // If app is in background OR screen is turned off, start 15 min countdown
        if (!isAppForeground || !isScreenOn) {
            sharedTransport?.let { t ->
                if (t.isConnected) {
                    Log.i(TAG, "Scheduling 15-minute background disconnect timeout (appForeground=$isAppForeground, screenOn=$isScreenOn)")
                    timeoutHandler.postDelayed(backgroundDisconnectRunnable, INACTIVITY_TIMEOUT_MS)
                }
            }
        } else {
            Log.i(TAG, "App active in foreground with screen ON: background disconnect timer cancelled")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_OFF) {
                isScreenOn = false
                Log.i(TAG, "Screen turned OFF: maintaining persistent WakeLock & WifiLock; evaluating 15m timeout")
                ensureLocksHeld()
                evaluateBackgroundTimer()
            } else if (action == Intent.ACTION_SCREEN_ON) {
                isScreenOn = true
                Log.i(TAG, "Screen turned ON: evaluating 15m timeout")
                ensureLocksHeld()
                evaluateBackgroundTimer()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        Log.i(TAG, "SWAR Mesh Foreground Service created")

        createNotificationChannel()
        val initialNotification = buildNotification("SWAR Mesh Active", "Offline peer-to-peer network ready")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground error: ${t.message}")
        }

        // Acquire persistent wake lock and high-performance wifi lock
        acquireLocks()

        // Register screen on/off receiver to maintain active connection when phone is locked
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screenReceiver: ${e.message}")
        }

        // Initialize and bind LocalTransport singleton
        val t = getTransport(applicationContext)
        t.setListener(this)
        t.registerP2pReceiver()
        t.discover()
        t.discoverP2pPeers()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SWAR:MeshServiceWakeLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SWAR:MeshServiceWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired persistent WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    private fun ensureLocksHeld() {
        try {
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Error re-acquiring locks: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureLocksHeld()
        sharedTransport?.let { t ->
            if (!t.isConnected) {
                t.discover()
                t.discoverP2pPeers()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task closed/swiped from recents: keeping MeshService alive in background")
        // Schedule immediate service restart via AlarmManager as watchdog backup
        try {
            val restartIntent = Intent(applicationContext, MeshService::class.java).apply {
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                202,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w(TAG, "AlarmManager restart schedule error: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "LinC Mesh Foreground Service destroying")
        timeoutHandler.removeCallbacks(backgroundDisconnectRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        instance = null
        isRunning = false

        // Automatically re-launch service to maintain persistent connection
        start(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═══════════════ LocalTransport.Listener Implementation ═══════════════

    override fun event(type: String, data: JSONObject) {
        if (type == "connection") {
            val st = data.optString("state", "")
            val peerName = data.optString("peer", "")
            val transportType = data.optString("transport", "wifi")
            if (st == "CONNECTED") {
                val suffix = if (transportType == "bluetooth") " (Bluetooth RFCOMM)" else ""
                updateNotification("SWAR Connected$suffix", if (peerName.isNotEmpty()) "Connected to $peerName$suffix" else "Mesh connection active$suffix")
                evaluateBackgroundTimer()
            } else if (st == "RECONNECTING") {
                updateNotification("SWAR Reconnecting", "Reconnecting to offline peer...")
            } else if (st == "STANDBY" || st == "DISCONNECTED") {
                updateNotification("SWAR Mesh Active", "Waiting for nearby peers...")
                timeoutHandler.removeCallbacks(backgroundDisconnectRunnable)
            }
        }
        uiListener?.event(type, data)
    }

    override fun received(message: ItpPacket.Decoded) {
        Log.i(TAG, "Packet received by MeshService: '${message.text}' (${message.language}) emergency=${message.emergency}")

        // If UI is active, forward to MainActivity
        val ui = uiListener
        if (ui != null) {
            ui.received(message)
        } else {
            // App is closed / backgrounded: alert the user directly!
            if (message.emergency) {
                triggerEmergencyAlert(message.text)
            } else {
                updateNotification("SWAR: New message", "${message.language.uppercase()}: ${message.text}")
            }
        }
    }

    private fun triggerEmergencyAlert(text: String) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
            }
        } catch (_: Exception) {}

        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (_: Exception) {}

        updateNotification("🚨 EMERGENCY ALERT", text)
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notification = buildNotification(title, text)
            nm?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SWAR Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains persistent offline Wi-Fi Direct connection even when app is closed or screen is off"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
