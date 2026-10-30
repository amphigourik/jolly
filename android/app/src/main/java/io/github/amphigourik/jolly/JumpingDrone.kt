// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client for the Jumping drone (Sumo/Race/Night). Blocking network calls happen on the thread calling
 * [connect], so call it from a background thread.
 *
 * [bindTcp] and [bindUdp] bind the sockets to the drone's Wi-Fi network (Network.bindSocket).
 */
private const val TAG = "Jolly"

class JumpingDrone(
    private val host: String = "192.168.2.1",
    private val bindTcp: (Socket) -> Unit = {},
    private val bindUdp: (DatagramSocket) -> Unit = {},
) {
    @Volatile var speed = 0
    @Volatile var turn = 0
    @Volatile var state = DroneState(); private set

    var onState: ((DroneState) -> Unit)? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    private val rxPackets = java.util.concurrent.atomic.AtomicLong()
    private val txPackets = java.util.concurrent.atomic.AtomicLong()
    private val frames = java.util.concurrent.atomic.AtomicLong()
    private val videoFrags = java.util.concurrent.atomic.AtomicLong()
    private val seq = IntArray(256)
    private val lock = Any()
    private val video = VideoReassembler()
    private val logger = CommandLogger()
    private val watchdog = LinkWatchdog(3000)
    @Volatile private var running = false
    private var rx: DatagramSocket? = null
    private var tx: DatagramSocket? = null
    private var c2dPort = 0
    private var rxThread: Thread? = null
    private var pcmdThread: Thread? = null

    fun connect() {
        // Order matters: bind the socket to its local port first, THEN to the Network.
        // Network.bindSocket on an unbound DatagramSocket(null) has no valid file descriptor yet,
        // which can throw immediately.
        Log.i(TAG, "connect: UDP bind 0.0.0.0:${Protocol.D2C_PORT}")
        val rxSock = DatagramSocket(null)
        rx = rxSock
        try {
            rxSock.reuseAddress = true
            rxSock.bind(InetSocketAddress("0.0.0.0", Protocol.D2C_PORT))
            rxSock.soTimeout = 500
            bindUdp(rxSock)
            Log.i(TAG, "connect: UDP rx bound to the network, local port ${rxSock.localPort}")
            Log.i(TAG, "connect: TCP to $host:${Protocol.DISCOVERY_PORT}")
            val reply = Socket().use { s ->
                bindTcp(s)
                Log.i(TAG, "connect: TCP socket bound to the network, connecting…")
                s.connect(InetSocketAddress(host, Protocol.DISCOVERY_PORT), 5000)
                s.soTimeout = 5000
                val hello = JSONObject()
                    .put("controller_type", "computer")
                    .put("controller_name", "jolly")
                    .put("d2c_port", Protocol.D2C_PORT)
                Log.i(TAG, "connect: TCP connected, sending handshake $hello")
                s.getOutputStream().write(hello.toString().toByteArray())
                s.getOutputStream().flush()
                val buf = ByteArray(4096)
                val n = s.getInputStream().read(buf)
                if (n <= 0) throw IllegalStateException("no reply from the drone")
                String(buf, 0, n).trimEnd('\u0000').also { Log.i(TAG, "connect: handshake received $it") }
            }
            val info = JSONObject(reply)
            if (info.optInt("status", 0) != 0) throw IllegalStateException("connection refused: $info")
            c2dPort = info.getInt("c2d_port")
            Log.i(TAG, "connect: c2d_port=$c2dPort")
        } catch (e: Exception) {
            Log.e(TAG, "connect: failed (UDP bind / TCP / handshake)", e)
            rxSock.close()
            throw e
        }
        tx = DatagramSocket().also { bindUdp(it) }
        Log.i(TAG, "connect: UDP tx ready, starting threads")

        watchdog.reset(System.currentTimeMillis())
        running = true
        rxThread = Thread(::recvLoop, "drone-rx").apply { isDaemon = true; start() }
        pcmdThread = Thread(::pcmdLoop, "drone-pcmd").apply { isDaemon = true; start() }
        Thread(::statsLoop, "drone-stats").apply { isDaemon = true; start() }

        val now = Date()
        send(Protocol.dateCmd(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)))
        send(Protocol.timeCmd(SimpleDateFormat("'T'HHmmssZ", Locale.US).format(now)))
        send(Protocol.allStates())
        send(Protocol.allSettings())
    }

    /** Sets speed to 0, disables video and stops the threads. Background thread only. */
    fun close() {
        if (!running) return
        Log.i(TAG, "close: speed 0, video off, stopping")
        speed = 0; turn = 0
        try { video(false) } catch (_: Exception) {}
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        running = false
        rx?.close(); tx?.close()
        rxThread?.join(1000); pcmdThread?.join(1000)
    }

    val isRunning get() = running

    private fun write(type: Int, buf: Int, payload: ByteArray) {
        synchronized(lock) {
            val s = seq[buf]
            seq[buf] = (s + 1) and 0xFF
            val data = Protocol.encodeFrame(type, buf, s, payload)
            tx?.send(DatagramPacket(data, data.size, InetAddress.getByName(host), c2dPort))
            txPackets.incrementAndGet()
        }
    }

    /** Acknowledged command (jumps, animations, settings). */
    fun send(payload: ByteArray) = write(Protocol.FRAME_DATA_WITH_ACK, Protocol.BUF_CMD_ACK, payload)

    fun jump(high: Boolean) = send(Protocol.jump(high))
    fun animation(id: Int) = send(Protocol.animation(id))
    fun posture(index: Int) = send(Protocol.posture(index))
    fun video(enable: Boolean) { Log.i(TAG, "video enable=$enable"); send(Protocol.video(enable)) }

    private fun statsLoop() {
        try {
            var tick = 0
            var lastFrags = 0L
            while (running) {
                Thread.sleep(5000)
                if (!running) break
                tick++
                val frags = videoFrags.get()
                val delta = frags - lastFrags
                lastFrags = frags
                Log.i(TAG, "stats: frames=${frames.get()} videoFrags=$delta/5s (total $frags) rx=${rxPackets.get()} tx=${txPackets.get()} battery=${state.battery}")
                // Timestamped battery reading every 30 s, to compare video on / off.
                if (tick % 6 == 0) {
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    Log.i(TAG, "battery: ${state.battery}% at $ts (videoFrags=$delta/5s)")
                }
            }
        } catch (_: InterruptedException) {}
    }

    private fun pcmdLoop() {
        try {
            while (running) {
                write(Protocol.FRAME_DATA, Protocol.BUF_CMD_NOACK, Protocol.pcmd(speed, turn))
                if (watchdog.check(System.currentTimeMillis())) {
                    Log.w(TAG, "watchdog: no packet from the drone for 3 s")
                    onError?.invoke(java.io.IOException("no packet from the drone for 3 s"))
                }
                Thread.sleep(25)
            }
        } catch (e: Exception) {
            if (running) { Log.e(TAG, "pcmdLoop: error", e); onError?.invoke(e) }
        }
    }

    private fun recvLoop() {
        val buf = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)
        val sock = rx ?: return
        try {
            while (running) {
                try {
                    packet.length = buf.size
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                rxPackets.incrementAndGet()
                watchdog.onPacket(System.currentTimeMillis())
                for (f in Protocol.decodeFrames(buf, packet.length)) handle(f)
            }
        } catch (e: Exception) {
            if (running) { Log.e(TAG, "recvLoop: error", e); onError?.invoke(e) }
        }
    }

    private fun handle(f: Protocol.Frame) {
        if (f.type == Protocol.FRAME_DATA_WITH_ACK) {
            write(Protocol.FRAME_ACK, (f.buf + 128) and 0xFF, byteArrayOf(f.seq.toByte()))
        }
        when (f.buf) {
            Protocol.BUF_PING -> write(Protocol.FRAME_DATA, Protocol.BUF_PONG, f.payload)
            Protocol.BUF_VIDEO -> {
                videoFrags.incrementAndGet()
                val r = video.handle(f.payload) ?: return
                write(Protocol.FRAME_DATA, Protocol.BUF_VIDEO_ACK, r.ack)
                r.frame?.let { frames.incrementAndGet(); onFrame?.invoke(it) }
            }
            else -> {
                if (f.type == Protocol.FRAME_DATA || f.type == Protocol.FRAME_DATA_WITH_ACK) {
                    logger.check(f.payload)?.let { Log.i("JumpingProto", it) }
                    val next = DroneStateDecoder.apply(state, f.payload)
                    if (next != state) { state = next; onState?.invoke(next) }
                }
            }
        }
    }
}
