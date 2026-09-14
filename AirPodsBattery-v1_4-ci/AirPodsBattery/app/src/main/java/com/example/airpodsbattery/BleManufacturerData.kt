package com.example.airpodsbattery

/** Walk length-prefixed BLE AD structures, including one-byte Flags payloads. */
internal fun extractManufacturerData(record: ByteArray, manufacturerId: Int): ByteArray? {
    var offset = 0
    fun u8(index: Int) = record[index].toInt() and 0xFF
    while (offset < record.size) {
        val length = u8(offset)
        if (length == 0) break
        val end = offset + 1 + length
        if (end > record.size) break
        if (u8(offset + 1) == 0xFF && length >= 3) {
            val id = u8(offset + 2) or (u8(offset + 3) shl 8)
            if (id == manufacturerId) return record.copyOfRange(offset + 4, end)
        }
        offset = end
    }
    return null
}
