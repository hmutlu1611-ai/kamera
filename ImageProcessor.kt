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
import kotlin.math.max

data class Adjust(
    val brightness: Float = 0f,   // -100..100
    val contrast: Float = 1f,     // 0..2
    val saturation: Float = 1f,   // 0..2 (0 = siyah-beyaz)
    val warmth: Float = 0f,       // -40..40
    val sharpness: Float = 0f,    // 0..2
    val rotation: Int = 0
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

    fun colorMatrix(a: Adjust): ColorMatrix {
        val cm = ColorMatrix().apply { setSaturation(a.saturation) }
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
        return cm
    }

    fun process(src: Bitmap, a: Adjust, recycleSource: Boolean): Bitmap {
        var out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix(a))
        })
        if (recycleSource) src.recycle()

        if (a.sharpness > 0.01f) {
            val s = sharpen(out, a.sharpness)
            out.recycle()
            out = s
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
