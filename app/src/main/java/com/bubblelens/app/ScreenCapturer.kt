package com.bubblelens.app

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Mantiene siempre el último fotograma de la pantalla (a resolución reducida) y solo
 * lo convierte a Bitmap cuando se pide. Así es muy liviano en memoria y batería.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private var capW: Int,
    private var capH: Int,
    private val dpi: Int,
) {
    private val thread = HandlerThread("bubblelens-cap").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null

    @Volatile
    var frameCount = 0L
        private set

    var onStopped: (() -> Unit)? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            onStopped?.invoke()
        }
    }

    fun start() {
        // Android 14 exige registrar el callback antes de crear el display virtual.
        projection.registerCallback(callback, handler)
        val r = newReader(capW, capH)
        reader = r
        display = projection.createVirtualDisplay(
            "BubbleLens", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        return r
    }

    private fun onFrame(rd: ImageReader) {
        val img: Image? = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) return
        synchronized(lock) {
            if (rd !== reader) {
                img.close()
            } else {
                latest?.close()
                latest = img
                frameCount++
            }
        }
    }

    /** Cambia el tamaño (rotación de pantalla) sin crear otro display virtual. */
    fun resize(w: Int, h: Int) {
        if (w == capW && h == capH) return
        val swapped: Pair<ImageReader?, ImageReader> = synchronized(lock) {
            latest?.close()
            latest = null
            val oldReader = reader
            val newOne = newReader(w, h)
            reader = newOne
            capW = w
            capH = h
            Pair(oldReader, newOne)
        }
        val old = swapped.first
        val nr = swapped.second
        try {
            display?.resize(w, h, dpi)
            display?.setSurface(nr.surface)
        } catch (e: Exception) {
            // si falla, el siguiente toque pedirá reactivar la captura
        }
        try {
            old?.close()
        } catch (e: Exception) {
        }
    }

    /** Espera a que llegue un fotograma nuevo (o se cumpla el tiempo). */
    suspend fun awaitNewFrame(after: Long, timeoutMs: Long) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (frameCount <= after && SystemClock.uptimeMillis() < end) delay(16)
    }

    /** Copia el recorte [region] (en píxeles de captura) del último fotograma; null = todo. */
    fun grab(region: Rect?): Bitmap? = synchronized(lock) {
        val img = latest
        if (img == null) {
            null
        } else {
            try {
                crop(img, region)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun crop(img: Image, region: Rect?): Bitmap? {
        val r = Rect(0, 0, img.width, img.height)
        if (region != null && !r.intersect(region)) return null
        val w = r.width()
        val h = r.height()
        if (w < 8 || h < 8) return null

        val plane = img.planes[0]
        val bb: ByteBuffer = plane.buffer
        val buf: Buffer = bb
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val rowBytes = w * ps
        val row = ByteArray(rowBytes)
        val out = ByteBuffer.allocate(w * h * ps)
        for (y in 0 until h) {
            buf.position((r.top + y) * rs + r.left * ps)
            bb.get(row, 0, rowBytes)
            out.put(row, 0, rowBytes)
        }
        (out as Buffer).rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(out)
        return bmp
    }

    fun release() {
        try {
            projection.unregisterCallback(callback)
        } catch (e: Exception) {
        }
        try {
            display?.release()
        } catch (e: Exception) {
        }
        display = null
        synchronized(lock) {
            latest?.close()
            latest = null
            try {
                reader?.close()
            } catch (e: Exception) {
            }
            reader = null
        }
        try {
            projection.stop()
        } catch (e: Exception) {
        }
        thread.quitSafely()
    }
}
