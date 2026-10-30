// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "Jolly"

private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

object PhotoSaver {
    /** Saves the JPEG as-is into Pictures/Jumping. */
    fun save(ctx: Context, jpeg: ByteArray): Uri {
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "jumping_${stamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Jumping")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore insert failed")
        try {
            (r.openOutputStream(uri) ?: error("output stream unavailable")).use { it.write(jpeg) }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "photo: save failed", e)
            r.delete(uri, null, null)
            throw e
        }
        Log.i(TAG, "photo: saved $uri (${jpeg.size} bytes)")
        return uri
    }
}

/**
 * Records the incoming JPEG frames to H.264 MP4 (Surface input) in Movies/Jumping.
 * The thread calling [offer] is never blocked: frames are dropped if the
 * encoding thread falls behind.
 */
class VideoRecorder private constructor(
    private val ctx: Context,
    private val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    private val muxer: MediaMuxer,
    private val codec: MediaCodec,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onDone: (Uri) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(3)
    @Volatile private var stopRequested = false
    private var track = -1
    private var muxerStarted = false
    private var basePts = -1L
    private var lastPts = -1L
    private val dst = Rect(0, 0, width, height)

    /** Callable from any thread, never blocks. */
    fun offer(jpeg: ByteArray) { if (!stopRequested) queue.offer(jpeg) }

    /** Requests a stop; finalization happens on the encoding thread. */
    fun stop() { stopRequested = true }

    private fun run() {
        var failure: String? = null
        var failureCause: Exception? = null
        Log.i(TAG, "recorder: starting ${width}x$height")
        try {
            while (true) {
                val jpeg = queue.poll(100, TimeUnit.MILLISECONDS)
                if (jpeg == null) { if (stopRequested) break else continue }
                draw(jpeg)
                drain(false)
            }
            codec.signalEndOfInputStream()
            drain(true)
            if (!muxerStarted) failure = "empty video"
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            failureCause = e
        }
        try { if (muxerStarted) muxer.stop() } catch (e: Exception) { if (failure == null) failure = e.message ?: "muxer error" }
        try { muxer.release() } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
        val r = ctx.contentResolver
        if (failure != null) Log.e(TAG, "recorder: failed: $failure", failureCause)
        if (failure == null) {
            try {
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                Log.i(TAG, "recorder: finished $uri")
                onDone(uri)
            } catch (e: Exception) { failure = e.message ?: "MediaStore error"; Log.e(TAG, "recorder: MediaStore", e) }
        }
        if (failure != null) {
            try { r.delete(uri, null, null) } catch (_: Exception) {}
            onError(failure)
        }
    }

    private fun draw(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        try {
            val c = surface.lockCanvas(null)
            try { c.drawBitmap(bmp, null, dst, null) } finally { surface.unlockCanvasAndPost(c) }
        } finally { bmp.recycle() }
    }

    private fun drain(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (System.nanoTime() > deadline) return
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted && buf != null) {
                        if (basePts < 0) basePts = info.presentationTimeUs
                        var pts = info.presentationTimeUs - basePts
                        if (pts <= lastPts) pts = lastPts + 1
                        lastPts = pts
                        info.presentationTimeUs = pts
                        buf.position(info.offset).limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        /** Prepares the encoder from the size of [firstJpeg] and starts the thread. Throws on failure. */
        fun start(
            ctx: Context, firstJpeg: ByteArray, onDone: (Uri) -> Unit, onError: (String) -> Unit,
            relativePath: String = "Movies/Jumping",
        ): VideoRecorder {
            Log.i(TAG, "recorder.start: jpeg=${firstJpeg.size} bytes, folder=$relativePath")
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(firstJpeg, 0, firstJpeg.size, o)
            val w = maxOf(2, o.outWidth and 1.inv())
            val h = maxOf(2, o.outHeight and 1.inv())
            Log.i(TAG, "recorder.start: image ${o.outWidth}x${o.outHeight} -> ${w}x$h")
            if (o.outWidth <= 0 || o.outHeight <= 0) error("invalid image")

            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "jumping_${stamp()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore insert failed")
            Log.i(TAG, "recorder.start: uri=$uri")
            var pfd: ParcelFileDescriptor? = null
            var muxer: MediaMuxer? = null
            var codec: MediaCodec? = null
            var surface: Surface? = null
            try {
                pfd = r.openFileDescriptor(uri, "w") ?: error("file unavailable")
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                val rec = VideoRecorder(ctx.applicationContext, uri, pfd, muxer, codec, surface, w, h, onDone, onError)
                Thread(rec::run, "video-rec").start()
                return rec
            } catch (e: Exception) {
                Log.e(TAG, "recorder: setup failed", e)
                try { codec?.release() } catch (_: Exception) {}
                try { surface?.release() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
                try { r.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
        }
    }
}
