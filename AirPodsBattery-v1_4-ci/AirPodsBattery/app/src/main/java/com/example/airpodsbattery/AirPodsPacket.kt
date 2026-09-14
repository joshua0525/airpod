package com.example.airpodsbattery

/**
 * AirPods Proximity Pairing advertisement (Apple manufacturer ID 0x004C).
 *
 * Offsets are relative to the 0x07 message-type byte. A paired AirPods
 * advertisement is normally laid out as follows:
 *
 *  0     message type (0x07)
 *  1     message length (usually 0x19)
 *  2     pairing mode (0x01 = paired, 0x00 = pairing)
 *  3-4   model identifier (big-endian)
 *  5     status flags (bit 5 identifies the primary pod)
 *  6     pod battery byte (high/low nibbles)
 *  7     case battery (low nibble) + charging flags (high nibble)
 *  8+    lid/counter and encrypted bytes
 *
 * Android normally removes the 0x4C 0x00 company-ID bytes before returning
 * manufacturer data. normalize() also accepts records that retain those
 * bytes. An Apple manufacturer field may contain more than one Continuity
 * message, so parse() walks the [type][length][content] frames and considers
 * only a complete 0x07 AirPods frame. It never searches for an arbitrary 0x07
 * byte, which would turn encrypted/random data into battery values.
 */
data class AirPodsStatus(
    val model: String,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    /** Bluetooth name resolved from the connected/advertising device. */
    val deviceName: String? = null,
    /** Diagnostic fields; never used as a source of UI values. */
    val modelId: Int? = null,
    val rawStatus: Int? = null,
    val rawPodBattery: Int? = null,
    val rawCaseAndFlags: Int? = null,
    /** Diagnostic source identity; never shown as a UI value. */
    val sourceAddress: String? = null,
    val rssi: Int? = null
)

object AirPodsPacketParser {
    private const val APPLE_COMPANY_ID = 0x004C
    private const val PROXIMITY_PAIRING_TYPE = 0x07

    // Known 2-byte model identifiers. The USB-C and AirPods 4 IDs are included
    // so their names are preserved even when the phone reports a newer model.
    private val MODEL_NAMES = mapOf(
        0x0220 to "AirPods (1st gen)",
        0x0F20 to "AirPods (2nd gen)",
        0x1320 to "AirPods (3rd gen)",
        0x1920 to "AirPods (4th gen)",
        0x1B20 to "AirPods (4th gen, ANC)",
        0x0E20 to "AirPods Pro",
        // A2698 (right), A2699 (left), and A2700 (case), firmware 8B41.
        0x1420 to "AirPods Pro (2nd gen, Lightning)",
        0x2420 to "AirPods Pro (2nd gen, USB-C)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)"
    )

    private data class Layout(
        val modelIndex: Int,
        val statusIndex: Int,
        val podBatteryIndex: Int,
        val caseAndFlagsIndex: Int,
        val pairingModeIndex: Int?
    )

    private val STANDARD_LAYOUT = Layout(
        modelIndex = 3,
        statusIndex = 5,
        podBatteryIndex = 6,
        caseAndFlagsIndex = 7,
        pairingModeIndex = 2
    )

    // Compatibility for a short payload that omits the pairing-mode byte.
    private val NO_PAIRING_MODE_LAYOUT = Layout(
        modelIndex = 2,
        statusIndex = 4,
        podBatteryIndex = 5,
        caseAndFlagsIndex = 6,
        pairingModeIndex = null
    )

    // Compatibility for the one-byte-shifted layout used by the original
    // project. This is intentionally selected only when its model ID is known.
    private val LEGACY_LAYOUT = Layout(
        modelIndex = 1,
        statusIndex = 3,
        podBatteryIndex = 4,
        caseAndFlagsIndex = 5,
        pairingModeIndex = null
    )

    /** Parse raw Apple manufacturer data into battery values. */
    fun parse(rawData: ByteArray): AirPodsStatus? {
        val data = normalize(rawData) ?: return null
        val candidates = proximityMessages(data)
        if (candidates.isEmpty()) return null

        // A manufacturer field can carry multiple Apple Continuity messages.
        // Try each complete 0x07 frame; the first one with a known model and a
        // valid paired-message header is the only one allowed to produce UI
        // values.
        for (candidate in candidates) {
            parseProximityMessage(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Return complete AirPods (0x07) messages embedded in an Apple field.
     *
     * This is also used by the scanner for diagnostic logging. The returned
     * arrays include the 0x07 type byte and its length byte, matching the
     * offsets documented above. A direct 0x07 payload is accepted as-is;
     * otherwise the input is interpreted as a sequence of [type][length]
     * frames. A malformed frame terminates the walk rather than guessing an
     * offset in encrypted bytes.
     */
    fun proximityMessages(rawData: ByteArray): List<ByteArray> {
        val data = normalize(rawData) ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        if (u8(data[0]) == PROXIMITY_PAIRING_TYPE) return listOf(data)

        val messages = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 1 < data.size) {
            val messageLength = u8(data[offset + 1])
            if (messageLength == 0) break
            val endExclusive = offset + 2 + messageLength
            if (endExclusive > data.size) break
            if (u8(data[offset]) == PROXIMITY_PAIRING_TYPE) {
                messages += data.copyOfRange(offset, endExclusive)
            }
            offset = endExclusive
        }
        return messages
    }

    private fun parseProximityMessage(data: ByteArray): AirPodsStatus? {
        if (data.size < 7 || u8(data[0]) != PROXIMITY_PAIRING_TYPE) return null

        val standardModel = modelId(data, STANDARD_LAYOUT)
        val noPairingModel = modelId(data, NO_PAIRING_MODE_LAYOUT)
        val legacyModel = modelId(data, LEGACY_LAYOUT)
        // Do not infer a layout from arbitrary bytes. The old fallback treated
        // records such as `07 11 06 xx ...` as no-pairing packets and produced
        // random battery percentages. A model must be known and the standard
        // layout must have a valid paired-message header.
        val layout = when {
            standardModel != null && MODEL_NAMES.containsKey(standardModel) &&
                isValidStandard(data) -> STANDARD_LAYOUT
            noPairingModel != null && MODEL_NAMES.containsKey(noPairingModel) &&
                isValidShortMessage(data) -> NO_PAIRING_MODE_LAYOUT
            legacyModel != null && MODEL_NAMES.containsKey(legacyModel) &&
                isValidShortMessage(data) -> LEGACY_LAYOUT
            else -> return null
        }

        if (layout == STANDARD_LAYOUT && u8(data[STANDARD_LAYOUT.pairingModeIndex!!]) == 0) {
            // Pairing-mode packets use a different layout and do not carry
            // reliable battery values.
            return null
        }

        val model = modelId(data, layout) ?: return null
        if (layout.statusIndex >= data.size ||
            layout.podBatteryIndex >= data.size ||
            layout.caseAndFlagsIndex >= data.size
        ) return null

        val statusByte = u8(data[layout.statusIndex])
        val podBatteryByte = u8(data[layout.podBatteryIndex])
        val caseAndFlagsByte = u8(data[layout.caseAndFlagsIndex])

        // Bit 5 means the left pod is primary. When the right pod is primary,
        // the radio order (and the two charging bits) is reversed.
        val primaryLeft = (statusByte and 0x20) != 0
        val areValuesFlipped = !primaryLeft
        val highPod = (podBatteryByte ushr 4) and 0x0F
        val lowPod = podBatteryByte and 0x0F
        val leftRaw = if (areValuesFlipped) highPod else lowPod
        val rightRaw = if (areValuesFlipped) lowPod else highPod

        // Case battery is the LOW nibble and charging flags are the HIGH
        // nibble of byte 7.
        val rawCase = caseAndFlagsByte and 0x0F
        val flags = (caseAndFlagsByte ushr 4) and 0x0F
        val leftCharging = if (areValuesFlipped) {
            (flags and 0x02) != 0
        } else {
            (flags and 0x01) != 0
        }
        val rightCharging = if (areValuesFlipped) {
            (flags and 0x01) != 0
        } else {
            (flags and 0x02) != 0
        }
        val caseCharging = (flags and 0x04) != 0

        return AirPodsStatus(
            model = MODEL_NAMES[model] ?: "AirPods (unknown model 0x%04X)".format(model),
            leftBattery = toPercent(leftRaw),
            rightBattery = toPercent(rightRaw),
            caseBattery = toPercent(rawCase),
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            modelId = model,
            rawStatus = statusByte,
            rawPodBattery = podBatteryByte,
            rawCaseAndFlags = caseAndFlagsByte
        )
    }

    private fun isValidStandard(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val length = u8(data[1])
        val pairingMode = u8(data[2])
        // 0x00 is a pairing advertisement and does not contain reliable
        // battery values. Paired AirPods use 0x01 here.
        return pairingMode == 1 && length in 0x0C..0x1B
    }

    private fun isValidShortMessage(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val length = u8(data[1])
        return length in 0x0C..0x1B
    }

    private fun normalize(rawData: ByteArray): ByteArray? {
        if (rawData.isEmpty()) return null
        val companyIdAtStart = rawData.size >= 2 &&
            ((u8(rawData[0]) == 0x4C && u8(rawData[1]) == 0x00) ||
                (u8(rawData[0]) == 0x00 && u8(rawData[1]) == 0x4C))
        if (companyIdAtStart) {
            return rawData.copyOfRange(2, rawData.size).takeIf { it.isNotEmpty() }
        }
        // Do not reject a non-0x07 first byte here: Apple manufacturer data
        // may start with another Continuity message (for example 0x12) and
        // contain a valid 0x07 AirPods message afterwards. proximityMessages()
        // performs the strict frame walk and validation.
        return rawData
    }

    private fun modelId(data: ByteArray, layout: Layout): Int? {
        if (layout.modelIndex + 1 >= data.size) return null
        return (u8(data[layout.modelIndex]) shl 8) or u8(data[layout.modelIndex + 1])
    }

    private fun toPercent(raw: Int): Int? = when {
        raw in 0..9 -> raw * 10
        raw in 0xA..0xE -> 100
        else -> null // 0xF means not available/disconnected
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF

    const val APPLE_ID = APPLE_COMPANY_ID
}
