// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun encodeFrameHeader() {
        val f = Protocol.encodeFrame(Protocol.FRAME_DATA_WITH_ACK, 11, 5, byteArrayOf(1, 2, 3))
        assertEquals("040b050a000000010203", hex(f))
    }

    @Test fun decodeMultipleFramesAndTruncated() {
        val a = Protocol.encodeFrame(2, 0, 7, byteArrayOf(9))
        val b = Protocol.encodeFrame(4, 127, 255, byteArrayOf(1, 2))
        val data = a + b + byteArrayOf(2, 3) // incomplete remainder ignored
        val frames = Protocol.decodeFrames(data)
        assertEquals(2, frames.size)
        assertEquals(7, frames[0].seq)
        assertArrayEquals(byteArrayOf(9), frames[0].payload)
        assertEquals(127, frames[1].buf)
        assertEquals(255, frames[1].seq)
        assertArrayEquals(byteArrayOf(1, 2), frames[1].payload)
    }

    @Test fun decodeRejectsBadSize() {
        val bad = Protocol.encodeFrame(2, 0, 0, byteArrayOf(1, 2, 3))
        bad[3] = 100
        assertTrue(Protocol.decodeFrames(bad).isEmpty())
    }

    @Test fun commands() {
        assertEquals("03000000000000", hex(Protocol.pcmd(0, 0)))
        assertEquals("03000000010ac4", hex(Protocol.pcmd(10, -60)))
        assertEquals("03020300" + "01000000", hex(Protocol.jump(true)))
        assertEquals("03020400" + "09000000", hex(Protocol.animation(9)))
        assertEquals("03000100" + "02000000", hex(Protocol.posture(2)))
        assertEquals("03120000" + "01", hex(Protocol.video(true)))
        assertEquals("00040100" + "323032362d31302d313700", hex(Protocol.dateCmd("2026-10-17")))
    }

    @Test fun battery() {
        assertEquals(87, Protocol.parseBattery(byteArrayOf(0, 5, 1, 0, 87)))
        assertNull(Protocol.parseBattery(byteArrayOf(0, 5, 2, 0, 87)))
        assertNull(Protocol.parseBattery(byteArrayOf(0, 5, 1)))
    }

    private fun frag(frame: Int, n: Int, count: Int, data: ByteArray) =
        byteArrayOf((frame and 0xFF).toByte(), (frame shr 8).toByte(), 0, n.toByte(), count.toByte()) + data

    @Test fun videoOutOfOrder() {
        val v = VideoReassembler()
        val r2 = v.handle(frag(0x0102, 2, 3, byteArrayOf(5, 6)))!!
        assertNull(r2.frame)
        // ack: frame number LE, high word (8 bytes), low word (8 bytes); fragment 2 => low = 4
        assertEquals("0201" + "0000000000000000" + "0400000000000000", hex(r2.ack))
        assertNull(v.handle(frag(0x0102, 0, 3, byteArrayOf(1, 2)))!!.frame)
        val r = v.handle(frag(0x0102, 1, 3, byteArrayOf(3, 4)))!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), r.frame)
        assertEquals("0201" + "0000000000000000" + "0700000000000000", hex(r.ack))
    }

    @Test fun videoHighFragmentsAckedInHighWord() {
        val v = VideoReassembler()
        val r = v.handle(frag(1, 65, 70, byteArrayOf(0)))!!
        assertEquals("0100" + "0200000000000000" + "0000000000000000", hex(r.ack))
    }

    @Test fun videoDropsOlderIncompleteFrames() {
        val v = VideoReassembler()
        v.handle(frag(10, 0, 2, byteArrayOf(1))) // frame 10 incomplete
        v.handle(frag(11, 0, 1, byteArrayOf(7)))!!.also { assertArrayEquals(byteArrayOf(7), it.frame) }
        // Frame 10 is older than 11: forgotten, so completing 10 is no longer enough.
        val late = v.handle(frag(10, 1, 2, byteArrayOf(2)))!!
        assertNull(late.frame)
    }

    @Test fun mapping() {
        assertEquals(0, ControlMapping.map(0f, 100, false))
        assertEquals(100, ControlMapping.map(1f, 100, false))
        assertEquals(-60, ControlMapping.map(-1f, 60, false))
        assertEquals(50, ControlMapping.map(0.5f, 100, false))
        assertEquals(25, ControlMapping.map(0.5f, 100, true))
        assertEquals(-25, ControlMapping.map(-0.5f, 100, true))
        assertEquals(30, ControlMapping.map(1f, 30, true))
        assertEquals(100, ControlMapping.map(5f, 100, false))
    }

    private fun ev(p: Int, k: Int, c: Int, vararg b: Int) =
        byteArrayOf(p.toByte(), k.toByte(), (c and 0xFF).toByte(), (c shr 8).toByte()) + ByteArray(b.size) { b[it].toByte() }

    private fun z(s: String) = s.toByteArray() + 0

    @Test fun stateDecoder() {
        var s = DroneState()
        s = DroneStateDecoder.apply(s, ev(0, 3, 2) + z("JumpingRace_123"))
        assertEquals("JumpingRace_123", s.name)
        s = DroneStateDecoder.apply(s, ev(0, 3, 3) + z("1.2.3") + z("hw42"))
        assertEquals("1.2.3", s.softwareVersion); assertEquals("hw42", s.hardwareVersion)
        s = DroneStateDecoder.apply(s, ev(0, 3, 4) + z("AB12"))
        s = DroneStateDecoder.apply(s, ev(0, 3, 5) + z("CD34"))
        assertEquals("AB12CD34", s.serial)
        s = DroneStateDecoder.apply(s, ev(0, 5, 1, 77))
        assertEquals(77, s.battery)
        s = DroneStateDecoder.apply(s, ev(0, 5, 7, 0xC4, 0xFF)) // -60 dBm
        assertEquals(-60, s.wifiRssi)
        s = DroneStateDecoder.apply(s, ev(3, 1, 0, 2, 0, 0, 0))
        assertEquals(2, s.posture)
        s = DroneStateDecoder.apply(s, ev(3, 1, 1, 1, 0, 0, 0))
        assertEquals(1, s.alert)
        s = DroneStateDecoder.apply(s, ev(3, 1, 2, 0xFB, 0x2C, 0x01)) // speed -5, real 300
        assertEquals(-5, s.speed); assertEquals(300, s.realSpeed)
        assertEquals("posture_kicker", DroneStateDecoder.POSTURE_KEYS[s.posture!!])
    }

    @Test fun stateDecoderIgnoresUnknownAndTruncated() {
        val s = DroneState(battery = 5)
        assertSame(s, DroneStateDecoder.apply(s, ev(9, 9, 9, 1, 2)))
        assertSame(s, DroneStateDecoder.apply(s, byteArrayOf(1, 2)))
        assertSame(s, DroneStateDecoder.apply(s, ev(3, 1, 0, 2))) // truncated posture
        assertSame(s, DroneStateDecoder.apply(s, ev(0, 5, 7, 1))) // truncated rssi
        assertEquals("", DroneStateDecoder.apply(s, ev(0, 3, 2)).name) // string without terminator
    }

    @Test fun commandLoggerFirstSeenOnly() {
        val l = CommandLogger()
        val line = l.check(ev(0, 5, 1, 77))
        assertEquals("0/5/1 len=5 hex=000501004d", line)
        assertNull(l.check(ev(0, 5, 1, 78)))
        assertNotNull(l.check(ev(0, 5, 7, 1, 2)))
        assertNull(l.check(byteArrayOf(1)))
        assertTrue(l.check(ev(3, 1, 2) + ByteArray(60))!!.length < 120) // 32 bytes max
    }

    @Test fun allSettings() {
        assertEquals("00020000", hex(Protocol.allSettings()))
    }

    @Test fun errorClassification() {
        val eperm = java.net.SocketException("Binding socket to network 123 failed: EPERM")
        assertEquals(ErrorText.Kind.VPN, ErrorText.kind(eperm))
        assertTrue(ErrorText.describe(eperm, TestTexts()).startsWith("A VPN is blocking access to the drone"))
        assertFalse(ErrorText.isRetryable(eperm))
        assertTrue(ErrorText.isRetryable(java.net.ConnectException("failed to connect: ECONNREFUSED")))
        assertTrue(ErrorText.isRetryable(java.net.SocketTimeoutException("timed out")))
        assertFalse(ErrorText.isRetryable(IllegalStateException("connection refused: {}")))
        val unreach = java.net.SocketException("sendto failed: ENETUNREACH (Network is unreachable)")
        assertEquals(ErrorText.Kind.NETWORK_LOST, ErrorText.kind(unreach))
        assertEquals("Wi-Fi connection lost", ErrorText.describe(unreach, TestTexts()))
        assertEquals("boom", ErrorText.describe(RuntimeException("boom"), TestTexts()))
    }

    @Test fun displayText() {
        assertEquals("—", DisplayText.realSpeed(-32768))
        assertEquals("—", DisplayText.realSpeed(null))
        assertEquals("0", DisplayText.realSpeed(0))
        assertEquals(0, DisplayText.batterySeverity(DroneState(battery = 50)))
        assertEquals(0, DisplayText.batterySeverity(DroneState()))
        assertEquals(1, DisplayText.batterySeverity(DroneState(battery = 10)))
        assertEquals(1, DisplayText.batterySeverity(DroneState(battery = 80, alert = 2)))
        assertEquals(2, DisplayText.batterySeverity(DroneState(battery = 0)))
        assertEquals(2, DisplayText.batterySeverity(DroneState(battery = 50, alert = 1)))
    }

    @Test fun realDroneEvents() {
        var s = DroneState()
        s = DroneStateDecoder.apply(s, ev(3, 1, 2, 0x0e, 0x00, 0x80))
        assertEquals(14, s.speed); assertEquals(-32768, s.realSpeed)
        s = DroneStateDecoder.apply(s, ev(0, 5, 1, 0))
        assertEquals(0, s.battery)
        assertFalse(s.disconnected)
        s = DroneStateDecoder.apply(s, ev(0, 1, 0))
        assertTrue(s.disconnected)
    }
}
