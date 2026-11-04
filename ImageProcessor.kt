package com.makrokamera.app

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Otomatik seviye için kanal başına alt/üst sınırlar. */
class Levels(val lo: IntArray, val hi: IntArray)

data class Adjust(
    val brightness: Float = 0f,   // -100..100
    val contrast: Float = 1f,     // 0..2
    val saturation: Float = 1f,   // 0..2 (0 = siyah-beyaz)
    val warmth: Float = 0f,       // -40..40
    val sharpness: Float = 0f,    // 0..2
    val rotation: Int = 0,
    val channel: Int = 0,         // 0 renkli, 1 kırmızı, 2 yeşil, 3 mavi, 4 gri
    val invert: Boolean = false,  // negatif
    val autoLevels: Boolean = false,
    val gamma: Float = 1f,        // 0.25..4
    val localContrast: Float = 0f // 0..2
)

object ImageProcessor {

    /** EXIF yönünü otomatik düzelterek, en uzun kenar maxSide'ı geçmeyecek şekilde açar. */
    fun decode(cr: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(cr, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > maxSide) {
                val f = maxSide.toFloat() / longest
                decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
            }
        }
    }

    /** Kameradan gelen JPEG baytlarını döndürüp en uzun kenarı maxSide olacak şekilde açar. */
    fun jpegToBitmap(bytes: ByteArray, rotation: Int, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("Kare çözülemedi")
        val longest = max(b.width, b.height)
        if (longest > maxSide) {
            val f = maxSide.toFloat() / longest
            val s = Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
            if (s !== b) b.recycle()
            b = s
        }
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            b = r
        }
        return b
    }

    fun saveJpeg(cr: ContentResolver, bmp: Bitmap, prefix: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${timestamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MakroKamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }

    private fun channelMatrix(ch: Int): FloatArray = when (ch) {
        1 -> floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        2 -> floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        3 -> floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        4 -> floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        else -> floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    fun colorMatrix(a: Adjust): ColorMatrix {
        val cm = ColorMatrix(channelMatrix(a.channel))
        cm.postConcat(ColorMatrix().apply { setSaturation(a.saturation) })
        val c = a.contrast
        val t = (1f - c) * 128f + a.brightness
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t + a.warmth,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t - a.warmth,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        if (a.invert) {
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        return cm
    }

    /**
     * Otomatik seviye sınırları, küçük bir önizlemeden hesaplanır.
     * Aynı girdiden aynı sonuç çıktığı için ekrandaki ayrıntı katmanı ve kayıt tutarlı kalır.
     */
    fun levelsFor(src: Bitmap, a: Adjust): Levels? {
        if (!a.autoLevels) return null
        val scale = min(1f, 512f / max(src.width, src.height))
        val tw = max(1, (src.width * scale).toInt())
        val th = max(1, (src.height * scale).toInt())
        val thumb = Bitmap.createScaledBitmap(src, tw, th, true)
        val cm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(cm).drawBitmap(thumb, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix(a))
        })
        if (thumb !== src) thumb.recycle()
        val px = IntArray(tw * th)
        cm.getPixels(px, 0, tw, 0, 0, tw, th)
        cm.recycle()

        val hist = Array(3) { IntArray(256) }
        for (p in px) {
            hist[0][(p shr 16) and 255]++
            hist[1][(p shr 8) and 255]++
            hist[2][p and 255]++
        }
        val cut = (px.size * 0.005f).toInt()
        val lo = IntArray(3)
        val hi = IntArray(3)
        for (c in 0..2) {
            var acc = 0
            var i = 0
            while (i < 255 && acc + hist[c][i] <= cut) { acc += hist[c][i]; i++ }
            lo[c] = i
            acc = 0
            i = 255
            while (i > 0 && acc + hist[c][i] <= cut) { acc += hist[c][i]; i-- }
            hi[c] = i
            if (hi[c] - lo[c] < 16) { lo[c] = 0; hi[c] = 255 }
        }
        return Levels(lo, hi)
    }

    /** Seviye germe + gama (tek geçişte, satır satır). */
    fun applyLut(src: Bitmap, levels: Levels?, gamma: Float): Bitmap {
        val lut = Array(3) { IntArray(256) }
        val inv = 1f / gamma.coerceAtLeast(0.1f)
        for (c in 0..2) {
            val lo = levels?.let { it.lo[c] } ?: 0
            val hi = levels?.let { it.hi[c] } ?: 255
            val range = max(1, hi - lo).toFloat()
            for (v in 0..255) {
                val x = ((v - lo) / range).coerceIn(0f, 1f)
                lut[c][v] = (x.pow(inv) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        val w = src.width
        val h = src.height
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                row[x] = (p and -0x1000000) or
                        (lut[0][(p shr 16) and 255] shl 16) or
                        (lut[1][(p shr 8) and 255] shl 8) or
                        lut[2][p and 255]
            }
            dst.setPixels(row, 0, w, 0, y, w, 1)
        }
        return dst
    }

    /** Yerel kontrast: büyük yarıçaplı bulanıklığı çıkarıp farkı güçlendirir (silik izleri öne çıkarır). */
    fun localContrast(src: Bitmap, amount: Float, block: Int): Bitmap {
        val w = src.width
        val h = src.height
        val small = Bitmap.createScaledBitmap(src, max(1, w / block), max(1, h / block), true)
        val blur = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== blur) small.recycle()
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val a = IntArray(w)
        val b = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(a, 0, w, 0, y, w, 1)
            blur.getPixels(b, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = a[x]
                val q = b[x]
                val pr = (p shr 16) and 255
                val pg = (p shr 8) and 255
                val pb = p and 255
                val r = clamp(pr + amount * (pr - ((q shr 16) and 255)))
                val g = clamp(pg + amount * (pg - ((q shr 8) and 255)))
                val bl = clamp(pb + amount * (pb - (q and 255)))
                a[x] = (p and -0x1000000) or (r shl 16) or (g shl 8) or bl
            }
            dst.setPixels(a, 0, w, 0, y, w, 1)
        }
        blur.recycle()
        return dst
    }

    fun process(
        src: Bitmap, a: Adjust, recycleSource: Boolean,
        levels: Levels? = null, localBlock: Int = 0
    ): Bitmap {
        var out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix(a))
        })
        if (recycleSource) src.recycle()

        if (levels != null || abs(a.gamma - 1f) > 0.01f) {
            val n = applyLut(out, levels, a.gamma)
            out.recycle()
            out = n
        }
        if (a.localContrast > 0.01f) {
            val block = if (localBlock > 0) localBlock else max(4, min(out.width, out.height) / 80)
            val n = localContrast(out, a.localContrast, block)
            out.recycle()
            out = n
        }
        if (a.sharpness > 0.01f) {
            val s2 = sharpen(out, a.sharpness)
            out.recycle()
            out = s2
        }
        if (a.rotation % 360 != 0) {
            val m = Matrix().apply { postRotate(a.rotation.toFloat()) }
            val r = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
            if (r !== out) out.recycle()
            out = r
        }
        return out
    }

    /**
     * Satır satır çalışan keskinleştirme (Laplace çekirdeği).
     * Tüm resmi tek dizide tutmadığı için 50 MP fotoğraflarda bile bellek dostudur.
     */
    fun sharpen(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        val row = IntArray(w)
        val center = 1f + 4f * amount

        src.getPixels(cur, 0, w, 0, 0, w, 1)
        System.arraycopy(cur, 0, prev, 0, w)

        for (y in 0 until h) {
            if (y + 1 < h) src.getPixels(next, 0, w, 0, y + 1, w, 1)
            else System.arraycopy(cur, 0, next, 0, w)

            for (x in 0 until w) {
                val c = cur[x]
                val l = cur[if (x > 0) x - 1 else x]
                val r = cur[if (x < w - 1) x + 1 else x]
                val u = prev[x]
                val d = next[x]

                val rr = center * ((c shr 16) and 255) - amount *
                        (((l shr 16) and 255) + ((r shr 16) and 255) + ((u shr 16) and 255) + ((d shr 16) and 255))
                val gg = center * ((c shr 8) and 255) - amount *
                        (((l shr 8) and 255) + ((r shr 8) and 255) + ((u shr 8) and 255) + ((d shr 8) and 255))
                val bb = center * (c and 255) - amount *
                        ((l and 255) + (r and 255) + (u and 255) + (d and 255))

                row[x] = (c and -0x1000000) or (clamp(rr) shl 16) or (clamp(gg) shl 8) or clamp(bb)
            }
            dst.setPixels(row, 0, w, 0, y, w, 1)
            val t = prev; prev = cur; cur = next; next = t
        }
        return dst
    }

    private fun clamp(v: Float): Int = when {
        v < 0f -> 0
        v > 255f -> 255
        else -> v.toInt()
    }
}
