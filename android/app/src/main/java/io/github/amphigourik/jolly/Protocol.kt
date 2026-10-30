// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ARNetworkAL / ARCommands / ARStream v1 protocol (pure logic, no Android dependency). */
object Protocol {
    const val FRAME_ACK = 1
    const val FRAME_DATA = 2
    const val FRAME_LOW_LATENCY = 3
    const val FRAME_DATA_WITH_ACK = 4
    const val HEADER_SIZE = 7

    const val BUF_CMD_NOACK = 10
    const val BUF_CMD_ACK = 11
    const val BUF_VIDEO_ACK = 13
    const val BUF_PONG = 1
    const val BUF_PING = 0
    const val BUF_VIDEO = 125

    const val DISCOVERY_PORT = 44444
    const val D2C_PORT = 54321

    const val PROJECT_COMMON = 0
    const val PROJECT_JS = 3

    val POSTURES = listOf("standard", "jumper", "kicker")
    val ANIMATIONS = listOf(
        1 to "spin", 2 to "tap", 3 to "slowshake", 4 to "metronome", 5 to "ondulation",
        6 to "spinjump", 7 to "spintoposture", 8 to "spiral", 9 to "slalom",
    )

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** Frame: type (u8), buffer (u8), seq (u8), total size (u32 LE), data. */
    fun encodeFrame(type: Int, buf: Int, seq: Int, payload: ByteArray): ByteArray {
        val b = le(HEADER_SIZE + payload.size)
        b.put(type.toByte()).put(buf.toByte()).put(seq.toByte())
        b.putInt(HEADER_SIZE + payload.size)
        b.put(payload)
        return b.array()
    }

    class Frame(val type: Int, val buf: Int, val seq: Int, val payload: ByteArray)

    /** A datagram may hold several frames; stops at the first invalid frame. */
    fun decodeFrames(data: ByteArray, length: Int = data.size): List<Frame> {
        val out = ArrayList<Frame>()
        var off = 0
        while (length - off >= HEADER_SIZE) {
            val b = ByteBuffer.wrap(data, off, length - off).order(ByteOrder.LITTLE_ENDIAN)
            val type = b.get().toInt() and 0xFF
            val buf = b.get().toInt() and 0xFF
            val seq = b.get().toInt() and 0xFF
            val size = b.getInt()
            if (size < HEADER_SIZE || size > length - off) break
            out.add(Frame(type, buf, seq, data.copyOfRange(off + HEADER_SIZE, off + size)))
            off += size
        }
        return out
    }

    /** Command: project (u8), class (u8), command (u16 LE), then arguments. */
    fun cmd(project: Int, klass: Int, command: Int, args: ByteArray = ByteArray(0)): ByteArray {
        val b = le(4 + args.size)
        b.put(project.toByte()).put(klass.toByte()).putShort(command.toShort()).put(args)
        return b.array()
    }

    private fun i32(v: Int) = le(4).putInt(v).array()

    fun pcmd(speed: Int, turn: Int): ByteArray {
        val moving = if (speed != 0 || turn != 0) 1 else 0
        return cmd(PROJECT_JS, 0, 0, byteArrayOf(moving.toByte(), speed.toByte(), turn.toByte()))
    }

    fun jump(high: Boolean) = cmd(PROJECT_JS, 2, 3, i32(if (high) 1 else 0))
    fun animation(id: Int) = cmd(PROJECT_JS, 2, 4, i32(id))
    fun posture(index: Int) = cmd(PROJECT_JS, 0, 1, i32(index))
    fun video(enable: Boolean) = cmd(PROJECT_JS, 18, 0, byteArrayOf(if (enable) 1 else 0))

    /** "YYYY-MM-DD\0" */
    fun dateCmd(date: String) = cmd(PROJECT_COMMON, 4, 1, date.toByteArray(Charsets.US_ASCII) + 0)

    /** "THHMMSS+ZZZZ\0" */
    fun timeCmd(time: String) = cmd(PROJECT_COMMON, 4, 2, time.toByteArray(Charsets.US_ASCII) + 0)
    fun allStates() = cmd(PROJECT_COMMON, 4, 0)
    fun allSettings() = cmd(PROJECT_COMMON, 2, 0)

    /** common/Settings/ProductName (0/2/2). Verified on a Race Max: the Wi-Fi SSID takes the new name after a reboot. */
    fun productName(name: String) = cmd(PROJECT_COMMON, 2, 2, name.toByteArray(Charsets.UTF_8) + 0)

    /** jumpingsumo AudioSettings/MasterVolume (3/12/0), u8 0..100 — verified on a Race Max: audible, and saved by the drone (`volume` in /data/dragon.conf). */
    fun audioVolume(v: Int) = cmd(PROJECT_JS, 12, 0, byteArrayOf(v.coerceIn(0, 100).toByte()))

    /** jumpingsumo AudioSettings/Theme (3/12/1), i32. Do NOT send to EVO models: on a Race Max any value silences all sounds until reboot. Kept for reference, unused. */
    fun audioTheme(theme: Int) = cmd(PROJECT_JS, 12, 1, i32(theme))

    /** Battery level if the payload is a BatteryStateChanged (common/5/1), otherwise null. */
    fun parseBattery(payload: ByteArray): Int? {
        if (payload.size < 5) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val project = b.get().toInt() and 0xFF
        val klass = b.get().toInt() and 0xFF
        val command = b.getShort().toInt() and 0xFFFF
        return if (project == PROJECT_COMMON && klass == 5 && command == 1) payload[4].toInt() and 0xFF else null
    }
}

/**
 * ARStream v1 video reassembly. Payload: frame number (u16), flags (u8),
 * fragment number (u8), fragment count (u8), then a chunk of JPEG.
 */
class VideoReassembler {
    class Result(val ack: ByteArray, val frame: ByteArray?)

    private var fragments = HashMap<Int, HashMap<Int, ByteArray>>()

    fun handle(payload: ByteArray): Result? {
        if (payload.size < 5) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val frameNo = b.getShort().toInt() and 0xFFFF
        b.get() // flags
        val fragNo = b.get().toInt() and 0xFF
        val fragCount = b.get().toInt() and 0xFF
        val chunk = payload.copyOfRange(5, payload.size)
        val frags = fragments.getOrPut(frameNo) { HashMap() }
        frags[fragNo] = chunk

        var low = 0L
        var high = 0L
        for (n in frags.keys) {
            if (n < 64) low = low or (1L shl n) else high = high or (1L shl (n - 64))
        }
        // "<HQQ": frame number, then high word, then low word.
        val ack = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(frameNo.toShort()).putLong(high).putLong(low).array()

        var frame: ByteArray? = null
        if (frags.size == fragCount) {
            val out = java.io.ByteArrayOutputStream()
            var complete = true
            for (i in 0 until fragCount) {
                val f = frags[i]
                if (f == null) { complete = false; break }
                out.write(f)
            }
            if (complete) {
                frame = out.toByteArray()
                // Forget older, incomplete frames.
                val kept = HashMap<Int, HashMap<Int, ByteArray>>()
                for ((k, v) in fragments) {
                    if (((k - frameNo) and 0xFFFF) < 0x8000 && k != frameNo) kept[k] = v
                }
                fragments = kept
            }
        }
        return Result(ack, frame)
    }
}

/** Joystick (-1..1) -> integer command, with a cap (in %) and an optional progressive curve. */
object ControlMapping {
    fun map(x: Float, maxPercent: Int, expo: Boolean): Int {
        val v = x.coerceIn(-1f, 1f)
        val shaped = if (expo) Math.signum(v) * v * v else v
        return Math.round(shaped * maxPercent.coerceIn(0, 100)).coerceIn(-100, 100)
    }
}

/** Drone state rebuilt from the events received. */
data class DroneState(
    val name: String? = null,
    val softwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val serialHigh: String? = null,
    val serialLow: String? = null,
    val battery: Int? = null,
    val wifiRssi: Int? = null,
    val speed: Int? = null,
    val realSpeed: Int? = null,
    val posture: Int? = null,
    val alert: Int? = null,
    /** Sound volume (3/13/0, inferred), theme (3/13/1, inferred). */
    val audioVolume: Int? = null,
    val audioTheme: Int? = null,
    /** Wi-Fi band (0 = 2.4 GHz, 1 = 5 GHz) and channel (3/9/0, inferred). */
    val wifiBand: Int? = null,
    val wifiChannel: Int? = null,
    /** Link quality (3/11/4, inferred; observed 5). */
    val linkQuality: Int? = null,
    /** Video streaming enabled (3/19/0, inferred). */
    val videoEnabled: Int? = null,
    /** The drone sent common/Network/Disconnection (0/1/0). */
    val disconnected: Boolean = false,
) {
    val serial: String? get() = if (serialHigh == null && serialLow == null) null else (serialHigh ?: "") + (serialLow ?: "")
}

object DroneStateDecoder {
    val POSTURE_KEYS = listOf("posture_standard", "posture_jumper", "posture_kicker", "posture_stuck", "posture_unknown")
    val ALERT_KEYS = listOf("alert_none", "alert_critical", "alert_low")

    /** Applies an event; returns the same state if the event is unknown or truncated. */
    fun apply(state: DroneState, payload: ByteArray): DroneState {
        if (payload.size < 4) return state
        return try {
            val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val project = b.get().toInt() and 0xFF
            val klass = b.get().toInt() and 0xFF
            val command = b.getShort().toInt() and 0xFFFF
            when {
                project == 0 && klass == 1 && command == 0 -> state.copy(disconnected = true)
                project == 0 && klass == 3 && command == 2 -> state.copy(name = str(b))
                project == 0 && klass == 3 && command == 3 -> {
                    val sw = str(b); val hw = str(b)
                    state.copy(softwareVersion = sw, hardwareVersion = hw)
                }
                project == 0 && klass == 3 && command == 4 -> state.copy(serialHigh = str(b))
                project == 0 && klass == 3 && command == 5 -> state.copy(serialLow = str(b))
                project == 0 && klass == 5 && command == 1 -> state.copy(battery = b.get().toInt() and 0xFF)
                project == 0 && klass == 5 && command == 7 -> state.copy(wifiRssi = b.getShort().toInt())
                project == 3 && klass == 13 && command == 0 -> state.copy(audioVolume = b.get().toInt() and 0xFF)
                project == 3 && klass == 13 && command == 1 -> state.copy(audioTheme = b.getInt())
                project == 3 && klass == 9 && command == 0 -> {
                    b.getInt() // selection type
                    val band = b.getInt()
                    state.copy(wifiBand = band, wifiChannel = b.get().toInt() and 0xFF)
                }
                project == 3 && klass == 11 && command == 4 -> state.copy(linkQuality = b.get().toInt() and 0xFF)
                project == 3 && klass == 19 && command == 0 -> state.copy(videoEnabled = b.getInt())
                project == 3 && klass == 1 && command == 0 -> state.copy(posture = b.getInt())
                project == 3 && klass == 1 && command == 1 -> state.copy(alert = b.getInt())
                project == 3 && klass == 1 && command == 2 -> {
                    val sp = b.get().toInt()
                    state.copy(speed = sp, realSpeed = b.getShort().toInt())
                }
                else -> state
            }
        } catch (_: RuntimeException) {
            state
        }
    }

    private fun str(b: ByteBuffer): String {
        val start = b.position()
        var end = start
        while (end < b.limit() && b.get(end).toInt() != 0) end++
        val s = String(b.array(), b.arrayOffset() + start, end - start, Charsets.UTF_8)
        b.position(minOf(end + 1, b.limit()))
        return s
    }
}

/** Produces a log line the first time each (project, class, command) triple is seen. */
class CommandLogger {
    private val seen = HashSet<Int>()

    fun check(payload: ByteArray): String? {
        if (payload.size < 4) return null
        val project = payload[0].toInt() and 0xFF
        val klass = payload[1].toInt() and 0xFF
        val command = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
        if (!seen.add((project shl 24) or (klass shl 16) or command)) return null
        val hex = payload.take(32).joinToString("") { "%02x".format(it) }
        return "$project/$klass/$command len=${payload.size} hex=$hex"
    }
}

/** Classification and wording of connection errors. */
object ErrorText {
    enum class Kind { VPN, NETWORK_LOST, OTHER }

    private fun msg(e: Throwable) = e.message ?: e.javaClass.simpleName

    fun isVpnBlock(e: Throwable) = msg(e).contains("EPERM")
    fun isNetworkLost(e: Throwable): Boolean {
        val m = msg(e)
        return m.contains("ENETUNREACH") || m.contains("ENETDOWN") || m.contains("Network is unreachable") ||
            m.contains("EPIPE") || m.contains("Socket closed")
    }

    fun kind(e: Throwable) = when {
        isVpnBlock(e) -> Kind.VPN
        isNetworkLost(e) -> Kind.NETWORK_LOST
        else -> Kind.OTHER
    }

    fun describe(e: Throwable, tx: Texts): String = when (kind(e)) {
        Kind.VPN -> tx.t("err_vpn")
        Kind.NETWORK_LOST -> tx.t("err_network_lost")
        Kind.OTHER -> msg(e)
    }

    /** Drone still booting: connection refused or timed out. Never for a VPN block. */
    fun isRetryable(e: Throwable): Boolean {
        if (isVpnBlock(e)) return false
        val m = msg(e)
        return e is java.net.ConnectException || e is java.net.SocketTimeoutException ||
            m.contains("ECONNREFUSED") || m.contains("Connection refused") || m.contains("timed out")
    }
}

object DisplayText {
    fun postureName(i: Int?, tx: Texts): String? =
        i?.let { DroneStateDecoder.POSTURE_KEYS.getOrNull(it)?.let(tx::t) ?: tx.t("unknown_value", it) }

    fun alertName(i: Int?, tx: Texts): String? =
        i?.let { DroneStateDecoder.ALERT_KEYS.getOrNull(it)?.let(tx::t) ?: tx.t("unknown_value", it) }

    /** realSpeed is -32768 (0x8000) when the actual speed is unknown. */
    fun realSpeed(v: Int?): String = if (v == null || v == -32768) "—" else v.toString()

    /** 0 = normal, 1 = orange (low battery), 2 = red (critical). */
    fun batterySeverity(s: DroneState): Int {
        val b = s.battery
        return when {
            s.alert == 1 || (b != null && b <= 5) -> 2
            s.alert == 2 || (b != null && b <= 10) -> 1
            else -> 0
        }
    }
}
