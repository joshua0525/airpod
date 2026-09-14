package com.example.airpodsbattery

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-local state shared by the monitor service and the activity. */
object AirPodsState {
    val status = MutableStateFlow<AirPodsStatus?>(null)
    val message = MutableStateFlow("에어팟을 가까이 두고 케이스를 열어주세요.")
}
