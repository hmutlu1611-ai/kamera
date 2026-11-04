package com.makrokamera.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** rot derece döndürülmüş w×h görüntünün sol-üst köşesi (0,0) olacak şekilde dönüşüm matrisi. */
fun rotMatrix(rot: Int, w: Float, h: Float): Matrix {
    val m = Matrix()
    m.postRotate(rot.toFloat())
    val r = RectF(0f, 0f, w, h)
    m.mapRect(r)
    m.postTranslate(-r.left, -r.top)
    return m
}

/**
 * Büyütülen bölgeyi orijinal fotoğraftan TAM çözünürlükte okur
 * (tüm resmi belleğe açmadan, sadece görünen parçayı).
 */
class RegionLoader private constructor(
    private val decoder: BitmapRegionDecoder,
    private val rawW: Int,
    private val rawH: Int,
    private val exifRot: Int
) {
    val orientedW = if (exifRot == 90 || exifRot == 270) rawH else rawW
    val orientedH = if (exifRot == 90 || exifRot == 270) rawW else rawH

    class Region(val bitmap: Bitmap, val orientedRect: RectF)

    /** want: EXIF'e göre döndürülmüş tam çözünürlük koordinatlarında istenen bölge. */
    fun decode(want: RectF, targetPx: Int): Region? {
        val rm = rotMatrix(exifRot, rawW.toFloat(), rawH.toFloat())
        val inv = Matrix()
        if (!rm.invert(inv)) return null
        val raw = RectF(want)
        inv.mapRect(raw)

        val l = floor(raw.left).toInt().coerceIn(0, rawW - 1)
        val t = floor(raw.top).toInt().coerceIn(0, rawH - 1)
        val r = ceil(raw.right).toInt().coerceIn(l + 1, rawW)
        val b = ceil(raw.bottom).toInt().coerceIn(t + 1, rawH)
        val rect = Rect(l, t, r, b)

        // Ekranda görünecek kadar ayrıntı yeter; gereksiz büyük açma
        val longest = max(rect.width(), rect.height())
        var sample = 1
        while (sample < 64 && (longest / (sample * 2) >= targetPx || longest / sample > 4096)) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = decoder.decodeRegion(rect, opts) ?: return null
        if (exifRot != 0) {
            val m = Matrix().apply { postRotate(exifRot.toFloat()) }
            val rb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rb !== bmp) bmp.recycle()
            bmp = rb
        }
        val actual = RectF(rect)
        rm.mapRect(actual)
        return Region(bmp, actual)
    }

    fun close() {
        runCatching { decoder.recycle() }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun open(cr: ContentResolver, uri: Uri): RegionLoader? = try {
            var rot = 0
            cr.openInputStream(uri)?.use { ins ->
                val o = ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                rot = when (o) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
            val dec = cr.openInputStream(uri)?.use { BitmapRegionDecoder.newInstance(it, false) }
            if (dec == null) null else RegionLoader(dec, dec.width, dec.height, rot)
        } catch (e: Throwable) {
            null
        }
    }
}
