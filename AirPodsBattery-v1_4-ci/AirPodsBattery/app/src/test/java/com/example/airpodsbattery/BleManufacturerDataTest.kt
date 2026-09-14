package com.example.airpodsbattery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleManufacturerDataTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readsAppleAfterShortFlagsAndTxPower() {
        val record = hex("02 01 1A 02 0A 11 0A FF 4C 00 10 05 04 18 96 34 CF 00")
        assertArrayEquals(hex("10 05 04 18 96 34 CF"), extractManufacturerData(record, 0x004C))
    }

    @Test fun keepsLatestAppleMessageIntact() {
        val payload = hex("12 19 2A D6 DF B6 1F 55 C2 B5 3F 5A 96 D0 7B F1 EA 4A 37 4F D7 18 D1 26 EA 03 98")
        assertArrayEquals(payload, extractManufacturerData(hex("1E FF 4C 00") + payload + byteArrayOf(0), 0x004C))
    }

    @Test fun rejectsServiceDataAndTruncatedManufacturer() {
        assertNull(extractManufacturerData(hex("0D 16 F3 FE 11 02 82 86 54 BD A2 F4 4E DA 00"), 0x004C))
        assertNull(extractManufacturerData(hex("1E FF 4C 00 07"), 0x004C))
        assertNull(extractManufacturerData(hex("01 FF 00"), 0x004C))
    }
}
