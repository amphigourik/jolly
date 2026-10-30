// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoRecorderTest {
    private fun jpeg(i: Int): ByteArray {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(20, 40 + i * 3, 90))
        c.drawCircle(50f + i * 12f, 240f, 40f, Paint().apply { color = Color.YELLOW })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test fun recordsPlayableMp4() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = List(45) { jpeg(it) }
        val done = CountDownLatch(1)
        var uri: Uri? = null
        var error: String? = null
        val rec = VideoRecorder.start(ctx, frames[0],
            onDone = { uri = it; done.countDown() },
            onError = { error = it; done.countDown() },
            relativePath = "Movies/JumpingTest")
        for (f in frames) { rec.offer(f); Thread.sleep(66) } // ~15 fps
        rec.stop()
        assertTrue("timeout", done.await(20, TimeUnit.SECONDS))
        assertNull("onError: $error", error)
        val u = uri!!
        try {
            val size = ctx.contentResolver.openFileDescriptor(u, "r")!!.use { it.statSize }
            assertTrue("taille $size", size > 10_000)
            val mmr = MediaMetadataRetriever()
            ctx.contentResolver.openFileDescriptor(u, "r")!!.use { mmr.setDataSource(it.fileDescriptor) }
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            mmr.release()
            assertEquals(640, w); assertEquals(480, h)
            assertTrue("duration $dur ms", dur > 2000)
        } finally {
            ctx.contentResolver.delete(u, null, null)
        }
    }
}
