package com.example.airpodsbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

/** Keeps BLE scanning alive after the activity is put in the background. */
class AirPodsMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var scanner: AirPodsScanner
    private var overlay: AirPodsOverlayView? = null
    private var lastSeenAt = 0L
    private var lastDismissedAt = 0L
    private var hideRunnable: Runnable? = null
    private var windowManager: WindowManager? = null
    private var connectedDeviceName: String? = null
    private var currentStatus: AirPodsStatus? = null
    private var receiverRegistered = false
    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()
    private var retryScanRunnable: Runnable? = null
    private var lastManualRestartAt = -30_000L
    private data class SourceCandidate(
        val address: String,
        var status: AirPodsStatus,
        var samples: Int,
        var lastSeenAt: Long,
        var rssi: Int
    )
    private val sourceCandidates = mutableMapOf<String, SourceCandidate>()
    private var selectedSourceAddress: String? = null
    private var selectedSourceLastSeenAt = 0L

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileProxies[profile] = proxy
            updateNameFromProfile(proxy)
        }

        override fun onServiceDisconnected(profile: Int) {
            profileProxies.remove(profile)
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("AirPodsPacket", "connectionEvent action=${intent.action} state=" +
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_ON) {
                    scheduleScanRestart(2_000L)
                } else if (state == BluetoothAdapter.STATE_OFF ||
                    state == BluetoothAdapter.STATE_TURNING_OFF
                ) {
                    if (::scanner.isInitialized) scanner.stop()
                    clearSourceSelection()
                    AirPodsState.message.value = "블루투스가 꺼져 있어요. 다시 켜주세요."
                }
                return
            }
            val device = parcelableDevice(intent) ?: return
            val name = safeDeviceName(device) ?: return
            val action = intent.action
            val connected = when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED
                ) == BluetoothProfile.STATE_CONNECTED
            }
            if (connected && isAudioDevice(device, name)) {
                connectedDeviceName = name
                refreshCurrentStatusName()
            } else if (!connected && name == connectedDeviceName) {
                connectedDeviceName = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForegroundService()
        startConnectedNameTracking()
        scanner = AirPodsScanner(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ordinary lifecycle calls only ensure registration. An explicit
        // rescan stops and replaces the session below, with a cooldown.
        if (intent?.action != ACTION_STOP && intent?.action != ACTION_RESCAN &&
            ::scanner.isInitialized) startScanner()
        when (intent?.action) {
            ACTION_RESCAN -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastManualRestartAt >= 30_000L) {
                    lastManualRestartAt = now
                    scanner.stop()
                    clearSourceSelection()
                    startScanner()
                } else {
                    android.util.Log.d("AirPodsPacket", "scanRestart deferred: wait 30 seconds between attempts")
                }
            }
            ACTION_HIDE -> hideOverlay(userDismissed = true)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> handleStatus(SAMPLE_STATUS.copy(deviceName = connectedDeviceName))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay(userDismissed = false)
        if (::scanner.isInitialized) scanner.stop()
        clearSourceSelection()
        stopConnectedNameTracking()
        retryScanRunnable?.let(mainHandler::removeCallbacks)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStatus(status: AirPodsStatus) {
        val now = android.os.SystemClock.elapsedRealtime()
        currentStatus = status
        AirPodsState.status.value = status
        AirPodsState.message.value = "${status.deviceName ?: status.model} 감지됨"
        lastSeenAt = now
        scheduleStaleCheck()

        if (!canDrawOverlays()) return
        if (overlay == null && now - lastDismissedAt >= DISMISS_COOLDOWN_MS) {
            showOverlay(status)
        } else {
            overlay?.update(status)
        }
    }

    private fun scheduleStaleCheck() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSeenAt >= SIGNAL_TIMEOUT_MS) {
                AirPodsState.status.value = null
                AirPodsState.message.value = "신호가 잠시 보이지 않아요. 케이스를 다시 열어주세요."
                hideOverlay(userDismissed = false)
            } else {
                scheduleStaleCheck()
            }
        }
        hideRunnable = runnable
        mainHandler.postDelayed(runnable, SIGNAL_TIMEOUT_MS)
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(status: AirPodsStatus) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = AirPodsOverlayView(this) { sendAction(ACTION_HIDE) }
        view.update(status)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (16 * resources.displayMetrics.density).toInt()
        }
        try {
            wm.addView(view, params)
            windowManager = wm
            overlay = view
        } catch (_: SecurityException) {
            AirPodsState.message.value = "팝업 권한을 허용하면 배터리 카드가 표시됩니다."
        } catch (_: WindowManager.BadTokenException) {
            AirPodsState.message.value = "팝업을 표시할 수 없습니다. 팝업 권한을 확인해주세요."
        }
    }

    private fun hideOverlay(userDismissed: Boolean) {
        if (userDismissed) lastDismissedAt = android.os.SystemClock.elapsedRealtime()
        val view = overlay ?: return
        overlay = null
        try { windowManager?.removeView(view) }
        catch (_: IllegalArgumentException) { /* Already removed by the system. */ }
        catch (_: SecurityException) { /* Permission was revoked while visible. */ }
    }

    private fun sendAction(action: String) {
        startService(Intent(this, AirPodsMonitorService::class.java).setAction(action))
    }

    private fun startScanner() {
        if (!::scanner.isInitialized) return
        scanner.start(
            onError = { error ->
                AirPodsState.message.value = error
                scheduleScanRestart()
            },
            onUpdate = { status ->
                mainHandler.post {
                    val selectedStatus = selectSource(status) ?: return@post
                    // Prefer the name from the connected A2DP/HFP profile; the
                    // BLE result name is the fallback when the profile proxy
                    // has not reported yet.
                    if (connectedDeviceName == null) {
                        connectedDeviceName = selectedStatus.deviceName
                    }
                    handleStatus(selectedStatus.copy(
                        deviceName = connectedDeviceName ?: selectedStatus.deviceName
                    ))
                }
            }
        )
    }

    /**
     * Keep one nearby AirPods advertisement source selected for this scan
     * session. Without this, advertisements from different devices can
     * overwrite one another and make the card appear to jump.
     */
    private fun selectSource(status: AirPodsStatus): AirPodsStatus? {
        val address = status.sourceAddress?.takeIf { it.isNotBlank() } ?: return status
        val now = android.os.SystemClock.elapsedRealtime()
        val existing = sourceCandidates[address]
        val candidate = if (existing == null) {
            SourceCandidate(
                address = address,
                status = status,
                samples = 1,
                lastSeenAt = now,
                rssi = status.rssi ?: -127
            )
        } else {
            existing.status = status
            existing.samples = (existing.samples + 1).coerceAtMost(100)
            existing.lastSeenAt = now
            existing.rssi = status.rssi ?: existing.rssi
            existing
        }
        sourceCandidates[address] = candidate

        val staleAddresses = sourceCandidates
            .filterValues { now - it.lastSeenAt > SOURCE_CANDIDATE_TIMEOUT_MS }
            .keys
        staleAddresses.forEach(sourceCandidates::remove)

        val selected = selectedSourceAddress
        if (selected == null ||
            now - selectedSourceLastSeenAt > SOURCE_HOLD_MS ||
            !sourceCandidates.containsKey(selected)
        ) {
            // Require two observations before locking onto a source. This
            // avoids showing a one-off advertisement from a nearby device.
            val best = sourceCandidates.values
                .filter { it.samples >= MIN_SOURCE_SAMPLES }
                .maxWithOrNull(compareBy<SourceCandidate> { it.samples }.thenBy { it.rssi })
                ?: return null
            selectedSourceAddress = best.address
            selectedSourceLastSeenAt = best.lastSeenAt
        }
        if (address != selectedSourceAddress) return null
        selectedSourceLastSeenAt = now
        return candidate.status
    }

    private fun clearSourceSelection() {
        sourceCandidates.clear()
        selectedSourceAddress = null
        selectedSourceLastSeenAt = 0L
    }

    private fun scheduleScanRestart(delayMs: Long = 30_000L) {
        if (!::scanner.isInitialized) return
        retryScanRunnable?.let(mainHandler::removeCallbacks)
        val retry = Runnable {
            retryScanRunnable = null
            startScanner()
        }
        retryScanRunnable = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun startConnectedNameTracking() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                // Bluetooth profile state broadcasts originate in the system
                // Bluetooth process, so this receiver must accept system
                // broadcasts on Android 13+.
                this, connectionReceiver, filter, ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (_: SecurityException) {
            // The BLE scanner still works; only the friendly connected name is unavailable.
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        // A BLE advertisement often has no local name. A bonded AirPods name
        // is a useful fallback until the A2DP/HFP profile proxy responds.
        try {
            connectedDeviceName = adapter.bondedDevices
                .asSequence()
                .mapNotNull { safeDeviceName(it) }
                .firstOrNull(::looksLikeAirPods)
        } catch (_: SecurityException) {
            // The profile proxy/scan path below may still provide a name.
        }
        try {
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT can be revoked while the service is starting.
        }
    }

    private fun stopConnectedNameTracking() {
        if (receiverRegistered) {
            try { unregisterReceiver(connectionReceiver) }
            catch (_: IllegalArgumentException) { /* Already unregistered. */ }
            receiverRegistered = false
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        for ((profile, proxy) in profileProxies) {
            try { adapter?.closeProfileProxy(profile, proxy) }
            catch (_: SecurityException) { /* Permission can be revoked at shutdown. */ }
        }
        profileProxies.clear()
    }

    private fun updateNameFromProfile(proxy: BluetoothProfile) {
        val devices = try { proxy.connectedDevices } catch (_: SecurityException) { return }
        val names = devices.mapNotNull { device ->
            val name = safeDeviceName(device) ?: return@mapNotNull null
            if (isAudioDevice(device, name)) name else null
        }
        val name = names.firstOrNull { looksLikeAirPods(it) } ?: names.firstOrNull() ?: return
        connectedDeviceName = name
        refreshCurrentStatusName()
    }

    private fun refreshCurrentStatusName() {
        val status = currentStatus ?: return
        val name = connectedDeviceName ?: return
        if (status.deviceName == name) return
        handleStatus(status.copy(deviceName = name))
    }

    private fun safeDeviceName(device: BluetoothDevice): String? = try {
        device.name?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: SecurityException) {
        null
    }

    private fun isAudioDevice(device: BluetoothDevice, name: String): Boolean {
        if (looksLikeAirPods(name)) return true
        return try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (_: SecurityException) {
            false
        }
    }

    private fun looksLikeAirPods(name: String): Boolean {
        val value = name.lowercase()
        return "airpod" in value || "air pod" in value || "beats" in value || "powerbeats" in value
    }

    @Suppress("DEPRECATION")
    private fun parcelableDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun startAsForegroundService() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AirPods Battery")
            .setContentText("에어팟 배터리를 확인하는 중")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "AirPods 배터리 감지",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "에어팟 BLE 배터리 정보를 감지하는 서비스"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_HIDE = "com.example.airpodsbattery.HIDE_POPUP"
        const val ACTION_RESCAN = "com.example.airpodsbattery.RESCAN"
        const val ACTION_TEST = "com.example.airpodsbattery.TEST_POPUP"
        const val ACTION_STOP = "com.example.airpodsbattery.STOP_MONITOR"
        private const val CHANNEL_ID = "airpods_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val SIGNAL_TIMEOUT_MS = 30_000L
        private const val SOURCE_CANDIDATE_TIMEOUT_MS = 45_000L
        private const val SOURCE_HOLD_MS = 15_000L
        private const val MIN_SOURCE_SAMPLES = 2
        private const val DISMISS_COOLDOWN_MS = 30_000L
        private val SAMPLE_STATUS = AirPodsStatus("AirPods Pro", 100, 80, 92, true, false, false)

        fun start(context: android.content.Context) {
            val intent = Intent(context, AirPodsMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
