package com.example.airpodsbattery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryPopup(status: AirPodsStatus, preview: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = Color.White,
        contentColor = Color(0xFF17191C),
        dragHandle = null
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status.deviceName ?: status.model, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(
                        if (preview) "디자인 미리보기 · 예시 값"
                        else if (!status.deviceName.isNullOrBlank() && status.deviceName != status.model)
                            "${status.model} · 주변에서 감지됨"
                        else "주변에서 감지됨",
                        style = MaterialTheme.typography.labelMedium, color = Color(0xFF68717D))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)
                    .semantics { contentDescription = "배터리 카드 닫기" }) {
                    Text("×", style = MaterialTheme.typography.headlineSmall)
                }
            }
            Spacer(Modifier.height(20.dp))
            // User-supplied reference photo; illustrative, not model-specific artwork.
            Image(painterResource(R.drawable.airpods_pro),
                contentDescription = "AirPods Pro 제품 예시 이미지",
                modifier = Modifier.fillMaxWidth().height(210.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color.Black))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BatteryTile("왼쪽", status.leftBattery, status.leftCharging, Modifier.weight(1f))
                BatteryTile("오른쪽", status.rightBattery, status.rightCharging, Modifier.weight(1f))
                BatteryTile("케이스", status.caseBattery, status.caseCharging, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text("배터리 정보가 없는 항목은 — 로 표시됩니다.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF68717D))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BatteryTile(label: String, percent: Int?, charging: Boolean, modifier: Modifier) {
    val batteryColor = if (percent != null && percent <= 20) Color(0xFFD94242) else Color(0xFF218C51)
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFF3F5F7))
        .padding(horizontal = 8.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color(0xFF68717D))
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = (percent ?: 0) / 100f,
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
            color = batteryColor, trackColor = Color(0xFFDBE0E5))
        Spacer(Modifier.height(10.dp))
        Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text(if (percent == null) "정보 없음" else if (charging) "충전 중 ⚡" else "",
            style = MaterialTheme.typography.labelSmall, color = batteryColor)
    }
}
