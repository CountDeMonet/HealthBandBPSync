package dev.erban.humebridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class J2208ProtocolTest {
    @Test
    fun framePadsTo16BytesAndAddsChecksum() {
        val frame = J2208Protocol.frame(0x56, 0, 0, 0)

        assertEquals(16, frame.size)
        assertEquals(0x56, frame[0].toUByte().toInt())
        assertEquals(0x56, frame[15].toUByte().toInt())
    }

    @Test
    fun startFrameEncodesSinceTimestampAsBcdAtOffset4() {
        val frame = J2208Protocol.hrvHistoryStartFrame(
            LocalDateTime.of(2026, 7, 27, 12, 24, 47)
        )

        assertArrayEquals(
            byteArrayOf(0x26, 0x07, 0x27, 0x12, 0x24, 0x47),
            frame.copyOfRange(4, 10)
        )
    }

    @Test
    fun parsesHrvRecordWithBpFields() {
        val raw = byteArrayOf(
            0x56, 0x00, 0x00,
            0x26, 0x07, 0x27, 0x12, 0x24, 0x47,
            55, 42, 73, 18, 121.toByte(), 79
        )

        val parsed = J2208Protocol.parseHrvRecord(raw)!!

        assertEquals(LocalDateTime.of(2026, 7, 27, 12, 24, 47), parsed.deviceTime)
        assertEquals(55, parsed.hrv)
        assertEquals(42, parsed.vascularAging)
        assertEquals(73, parsed.heartRate)
        assertEquals(18, parsed.stress)
        assertEquals(121, parsed.bpSystolic)
        assertEquals(79, parsed.bpDiastolic)
        assertEquals("560000260727122447372a4912794f", parsed.rawHex)
    }

    @Test
    fun detectsAndStripsTerminatorPair() {
        val packet = byteArrayOf(0x56, 1, 2, 0x56, 0xff.toByte())

        assertTrue(J2208Protocol.isHrvHistoryTerminator(packet))
        assertArrayEquals(byteArrayOf(0x56, 1, 2), J2208Protocol.stripHrvHistoryTerminator(packet))
    }

    @Test
    fun rejectsMalformedTimestamp() {
        val raw = byteArrayOf(
            0x56, 0x00, 0x00,
            0x26, 0x19, 0x45, 0x99.toByte(), 0x99.toByte(), 0x99.toByte(),
            55, 42, 73, 18, 121.toByte(), 79
        )

        assertEquals(null, J2208Protocol.parseHrvRecord(raw))
        assertFalse(J2208Protocol.isHrvHistoryTerminator(raw))
    }
}
