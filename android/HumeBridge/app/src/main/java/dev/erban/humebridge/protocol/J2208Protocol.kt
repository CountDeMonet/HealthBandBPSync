package dev.erban.humebridge.protocol

import java.security.MessageDigest
import java.time.LocalDateTime

object J2208Protocol {
    const val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000fff6-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000fff7-0000-1000-8000-00805f9b34fb"

    const val OP_HRV_HISTORY = 0x56
    const val MODE_START = 0
    const val MODE_CONTINUE = 2
    const val HRV_RECORD_STRIDE = 15

    fun frame(opcode: Int, vararg args: Int): ByteArray {
        require(args.size <= 14) { "J2208 commands allow at most 14 argument bytes" }
        val packet = ByteArray(16)
        packet[0] = opcode.toByte()
        args.forEachIndexed { index, value -> packet[index + 1] = value.toByte() }
        packet[15] = packet.take(15).sumOf { it.toUByte().toInt() }.and(0xff).toByte()
        return packet
    }

    fun hrvHistoryStartFrame(since: LocalDateTime? = null): ByteArray {
        val dateArgs = since?.let {
            intArrayOf(
                bcd(it.year % 100),
                bcd(it.monthValue),
                bcd(it.dayOfMonth),
                bcd(it.hour),
                bcd(it.minute),
                bcd(it.second),
            )
        } ?: intArrayOf()
        return frame(OP_HRV_HISTORY, MODE_START, 0, 0, *dateArgs)
    }

    fun hrvHistoryContinueFrame(): ByteArray = frame(OP_HRV_HISTORY, MODE_CONTINUE)

    fun isHrvHistoryTerminator(packet: ByteArray): Boolean =
        packet.size >= 2 &&
            packet[packet.lastIndex - 1].toUByte().toInt() == OP_HRV_HISTORY &&
            packet[packet.lastIndex].toUByte().toInt() == 0xff

    fun stripHrvHistoryTerminator(packet: ByteArray): ByteArray =
        if (isHrvHistoryTerminator(packet)) packet.copyOf(packet.size - 2) else packet

    fun parseHrvRecords(payload: ByteArray): List<HrvHistoryRecord> {
        val usable = stripHrvHistoryTerminator(payload)
        if (usable.isEmpty()) return emptyList()
        return (0 until usable.size / HRV_RECORD_STRIDE).mapNotNull { index ->
            val start = index * HRV_RECORD_STRIDE
            parseHrvRecord(usable.copyOfRange(start, start + HRV_RECORD_STRIDE))
        }
    }

    fun parseHrvRecord(record: ByteArray): HrvHistoryRecord? {
        if (record.size < HRV_RECORD_STRIDE) return null
        val timestamp = decodeRecordDateTime(record) ?: return null
        return HrvHistoryRecord(
            deviceTime = timestamp,
            raw = record.copyOf(HRV_RECORD_STRIDE),
            rawHex = record.copyOf(HRV_RECORD_STRIDE).toHex(),
            rawSha256 = sha256(record.copyOf(HRV_RECORD_STRIDE)),
            hrv = record[9].toUByte().toInt(),
            vascularAging = record[10].toUByte().toInt(),
            heartRate = record[11].toUByte().toInt(),
            stress = record[12].toUByte().toInt(),
            bpSystolic = record[13].toUByte().toInt(),
            bpDiastolic = record[14].toUByte().toInt(),
        )
    }

    fun decodeRecordDateTime(record: ByteArray): LocalDateTime? {
        if (record.size < 9) return null
        val yy = unbcd(record[3].toUByte().toInt())
        val month = unbcd(record[4].toUByte().toInt())
        val day = unbcd(record[5].toUByte().toInt())
        val hour = unbcd(record[6].toUByte().toInt())
        val minute = unbcd(record[7].toUByte().toInt())
        val second = unbcd(record[8].toUByte().toInt())
        return try {
            LocalDateTime.of(2000 + yy, month, day, hour, minute, second)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun bcd(value: Int): Int {
        require(value in 0..99) { "BCD value out of range: $value" }
        return value.toString().toInt(16)
    }

    fun unbcd(value: Int): Int = ((value shr 4) and 0x0f) * 10 + (value and 0x0f)

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toUByte().toInt()) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

data class HrvHistoryRecord(
    val deviceTime: LocalDateTime,
    val raw: ByteArray,
    val rawHex: String,
    val rawSha256: String,
    val hrv: Int,
    val vascularAging: Int,
    val heartRate: Int,
    val stress: Int,
    val bpSystolic: Int,
    val bpDiastolic: Int,
)
