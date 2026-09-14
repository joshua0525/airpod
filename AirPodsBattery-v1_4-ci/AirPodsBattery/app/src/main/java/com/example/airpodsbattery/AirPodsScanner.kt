package com.example.airpodsbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log

class AirPodsScanner(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var activeScanner: BluetoothLeScanner? = null
    private var onUpdate: ((AirPodsStatus) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var lastLogAt = 0L
    private var lastPayloadLogAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var received = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (activeScanner == null) return
            Log.d("AirPodsPacket", "scanAlive appleResults=$received")
            handler.postDelayed(this, 15_000L)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onError: (String) -> Unit, onUpdate: (AirPodsStatus) -> Unit) {
        this.onUpdate = onUpdate
        this.onError = onError
        if (activeScanner != null) return
        try {
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                onError("블루투스를 켠 후 다시 검색해주세요.")
                return
            }
            activeScanner = scanner
            received = 0
            val filters = listOf(ScanFilter.Builder()
                .setManufacturerData(AirPodsPacketParser.APPLE_ID, byteArrayOf()).build())
            Log.d("AirPodsPacket", "scanStart v=${BuildConfig.VERSION_NAME} filter=004C")
            scanner.startScan(filters, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.removeCallbacks(heartbeat)
            handler.postDelayed(heartbeat, 15_000L)
        } catch (_: SecurityException) {
            activeScanner = null
            onError("블루투스 권한을 허용해주세요.")
        } catch (_: IllegalStateException) {
            activeScanner = null
            onError("블루투스 상태를 확인한 후 다시 검색해주세요.")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(heartbeat)
        Log.d("AirPodsPacket", "scanStop")
        val scanner = activeScanner
        activeScanner = null
        onUpdate = null
        onError = null
        try { scanner?.stopScan(callback) }
        catch (_: SecurityException) { /* Permission can be revoked while scanning. */ }
        catch (_: IllegalStateException) { /* Bluetooth can be disabled while scanning. */ }
    }

    private val callback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            Log.d("AirPodsPacket", "scanFailed code=$errorCode")
            handler.removeCallbacks(heartbeat)
            if (activeScanner == null) return
            activeScanner = null
            onError?.invoke("검색을 시작하지 못했어요 ($errorCode). 잠시 후 다시 검색해주세요.")
        }
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (activeScanner == null) return
            received++
            val scanRecord = result.scanRecord ?: return
            val mappedData = scanRecord.getManufacturerSpecificData(AirPodsPacketParser.APPLE_ID)
            // A few Samsung Bluetooth stacks have returned the complete AD
            // record from getManufacturerSpecificData(). Parse the raw
            // ScanRecord ourselves so an AD length/type header (for example
            // `12 02 ...`) can never be mistaken for AirPods payload bytes.
            val rawAppleData = extractManufacturerData(scanRecord.bytes, AirPodsPacketParser.APPLE_ID)
            val data = rawAppleData ?: mappedData?.takeIf(::startsWithAirPodsPayload)
            val sourceAddress = try {
                result.device.address
            } catch (_: SecurityException) {
                null
            }
            val now = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG && now - lastLogAt >= 2_000) {
                lastLogAt = now
                // Include the raw record/map lengths so OEM parsing differences
                // are visible. Encrypted bytes remain truncated in the log.
                Log.d("AirPodsPacket", "addr=${sourceAddress ?: "-"} rssi=${result.rssi}" +
                    " recordLen=${scanRecord.bytes.size} mapLen=${mappedData?.size ?: 0}" +
                    " appleLen=${rawAppleData?.size ?: 0} data=" +
                    scanRecord.bytes.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            }
            if (data == null || data.isEmpty()) return
            val proximityMessages = AirPodsPacketParser.proximityMessages(data)
            if (BuildConfig.DEBUG && rawAppleData == null && mappedData != null &&
                now - lastPayloadLogAt >= 2_000
            ) {
                lastPayloadLogAt = now
                Log.d("AirPodsPacket", "ignored OEM manufacturer mapping addr=${sourceAddress ?: "-"}" +
                    " map=" + (mappedData?.take(32)?.joinToString(" ") {
                        "%02X".format(it.toInt() and 0xFF)
                    } ?: "-"))
            }
            if (BuildConfig.DEBUG && rawAppleData != null && now - lastPayloadLogAt >= 2_000) {
                lastPayloadLogAt = now
                // Include address/RSSI so nearby Apple advertisements can be
                // distinguished while diagnosing packet changes. The encrypted
                // payload is not interpreted here. The nested 0x07 headers are
                // included so a packet that starts with another ACM (for
                // example 0x12) is easy to distinguish from a valid AirPods
                // message.
                Log.d("AirPodsPacket", "applePayload addr=${sourceAddress ?: "-"} len=${data.size} data=" +
                    data.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
                    " acm07=" + describeProximityMessages(proximityMessages))
            }
            val status = AirPodsPacketParser.parse(data) ?: return
            val deviceName = try {
                result.device.name?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: SecurityException) {
                null
            } ?: result.scanRecord?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
            if (BuildConfig.DEBUG) {
                Log.d("AirPodsPacket", "parsed modelId=" +
                    status.modelId?.let { "0x%04X".format(it) } +
                    " status=0x" + (status.rawStatus ?: 0).toString(16) +
                    " pods=0x" + (status.rawPodBattery ?: 0).toString(16) +
                    " caseFlags=0x" + (status.rawCaseAndFlags ?: 0).toString(16) +
                    " L=${status.leftBattery} R=${status.rightBattery} C=${status.caseBattery}" +
                    " name=${deviceName ?: "-"}" +
                    " addr=${sourceAddress ?: "-"} rssi=${result.rssi}")
            }
            onUpdate?.invoke(status.copy(
                deviceName = deviceName,
                sourceAddress = sourceAddress,
                rssi = result.rssi
            ))
        }
    }

    private fun startsWithAirPodsPayload(data: ByteArray): Boolean {
        // The OEM map may return either a direct 0x07 message, a company-ID
        // prefixed message, or several ACM frames beginning with another type.
        // Let the same strict frame walker decide whether an AirPods frame is
        // present instead of checking only the first byte.
        return AirPodsPacketParser.proximityMessages(data).isNotEmpty()
    }

    private fun describeProximityMessages(messages: List<ByteArray>): String {
        if (messages.isEmpty()) return "none"
        return messages.joinToString(",") { message ->
            val length = if (message.size > 1) u8(message[1]) else 0
            val pairing = if (message.size > 2) u8(message[2]) else -1
            val model = if (message.size > 4) {
                (u8(message[3]) shl 8) or u8(message[4])
            } else {
                -1
            }
            "len=0x${length.toString(16).uppercase()}" +
                " pair=" + (if (pairing >= 0) "0x${pairing.toString(16).uppercase()}" else "-") +
                " model=" + (if (model >= 0) "0x${model.toString(16).padStart(4, '0').uppercase()}" else "-")
        }
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}
