package com.example.airpodsbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(
                background = Color(0xFFF3F5F8)
            )) {
                BatteryScreen()
            }
        }
    }

    @Composable
    private fun BatteryScreen() {
        val lifecycleOwner = LocalLifecycleOwner.current
        val status by AirPodsState.status.collectAsStateWithLifecycle()
        val message by AirPodsState.message.collectAsStateWithLifecycle()
        var showCard by remember { mutableStateOf(false) }
        var showPreview by remember { mutableStateOf(false) }
        var overlayAllowed by remember { mutableStateOf(canDrawOverlays()) }
        var bluetoothAllowed by remember { mutableStateOf(hasBluetoothPermissions()) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            bluetoothAllowed = hasBluetoothPermissions()
            if (bluetoothAllowed) {
                startMonitor()
            } else {
                AirPodsState.message.value =
                    "주변 기기와 정확한 위치 권한을 허용해야 BLE 배터리 신호를 검색할 수 있어요."
            }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayAllowed = canDrawOverlays()
                    bluetoothAllowed = hasBluetoothPermissions()
                    if (bluetoothAllowed) startMonitor()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(Unit) {
            if (hasBluetoothPermissions()) {
                startMonitor()
            } else {
                permissionLauncher.launch(requiredPermissions())
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("AirPods Battery", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(22.dp))

                if (!overlayAllowed) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    }) {
                        Text("팝업 권한 허용")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "앱 밖에서도 배터리 창을 띄우려면 한 번만 허용해주세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (status != null) {
                    Text("${status!!.deviceName ?: status!!.model}  ·  BLE 신호 수신 중")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showPreview = false; showCard = true }) {
                        Text("배터리 카드 보기")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(onClick = {
                    if (hasBluetoothPermissions()) {
                        bluetoothAllowed = true
                        sendServiceAction(AirPodsMonitorService.ACTION_RESCAN)
                    } else {
                        permissionLauncher.launch(requiredPermissions())
                    }
                }) {
                    Text(if (bluetoothAllowed) "다시 검색" else "권한 허용 / 다시 검색")
                }
                TextButton(onClick = {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) {
                    Text("블루투스 설정")
                }
                TextButton(onClick = {
                    showPreview = true
                    showCard = true
                }) {
                    Text("디자인 미리보기")
                }
                TextButton(onClick = {
                    sendServiceAction(AirPodsMonitorService.ACTION_TEST)
                }) {
                    Text("팝업 테스트")
                }
            }
        }

        val displayed = if (showPreview) {
            AirPodsStatus("AirPods Pro", 100, 80, 92, true, false, false)
        } else {
            status
        }
        if (showCard && displayed != null) {
            BatteryPopup(displayed, showPreview) {
                showCard = false
                showPreview = false
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun hasBluetoothPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun startMonitor() {
        try {
            AirPodsMonitorService.start(this)
        } catch (_: SecurityException) {
            AirPodsState.message.value = "블루투스 권한을 허용한 뒤 다시 검색해주세요."
        } catch (_: IllegalStateException) {
            AirPodsState.message.value = "블루투스를 켠 뒤 다시 검색해주세요."
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            val intent = Intent(this, AirPodsMonitorService::class.java).setAction(action)
            ContextCompat.startForegroundService(this, intent)
        } catch (_: SecurityException) {
            AirPodsState.message.value = "먼저 주변 기기 권한을 허용해주세요."
        }
    }
}
