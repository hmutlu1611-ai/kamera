package com.makrokamera.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Parlaklık (gri) görüntüsü. */
class Luma(val w: Int, val h: Int, val v: IntArray)

/** Referans karenin hizalama için hazırlanmış hali. */
class AlignRef(
    val w: Int, val h: Int,
    val l8: Luma, val l2: Luma,
    val rx: Int, val ry: Int, val rs: Int, val region: IntArray
)

/**
 * El titremesini düzeltmek için kareleri birbirine hizalar.
 * Kaba-ince arama: 1/8 boyut -> 1/2 boyut -> tam boyut (piksel hassasiyeti).
 */
object Aligner {

    private fun lum(p: Int) =
        (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8

    fun luma(bmp: Bitmap, scale: Int): Luma {
        val src = if (scale == 1) bmp else Bitmap.createScaledBitmap(
            bmp, max(1, bmp.width / scale), max(1, bmp.height / scale), true
        )
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (src !== bmp) src.recycle()
        for (i in px.indices) px[i] = lum(px[i])
        return Luma(w, h, px)
    }

    private fun regionLuma(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x, y, w, h)
        for (i in px.indices) px[i] = lum(px[i])
        return px
    }

    fun prepare(bmp: Bitmap): AlignRef {
        val rs = min(512, min(bmp.width, bmp.height) / 3)
        val rx = bmp.width / 2 - rs / 2
        val ry = bmp.height / 2 - rs / 2
        return AlignRef(
            bmp.width, bmp.height, luma(bmp, 8), luma(bmp, 2),
            rx, ry, rs, regionLuma(bmp, rx, ry, rs, rs)
        )
    }

    fun matchSize(bmp: Bitmap, ref: AlignRef): Bitmap =
        if (bmp.width == ref.w && bmp.height == ref.h) bmp
        else Bitmap.createScaledBitmap(bmp, ref.w, ref.h, true)

    /** ref(x,y) ≈ img(x+dx, y+dy) olacak şekilde (dx, dy) döndürür. */
    fun shift(ref: AlignRef, img: Bitmap): Pair<Int, Int> {
        // 1) 1/8 boyut, ±8 piksel (tam boyutta ±64)
        val a8 = ref.l8
        val b8 = luma(img, 8)
        val r8 = 8
        val m8 = r8 + 1
        if (a8.w <= 2 * m8 + 8 || a8.h <= 2 * m8 + 8) return 0 to 0
        var (dx, dy) = search(
            a8.v, a8.w, m8, m8, b8.v, b8.w, m8, m8,
            a8.w - 2 * m8, a8.h - 2 * m8, 1, 0, 0, r8
        )

        // 2) 1/2 boyut, ±3
        val a2 = ref.l2
        val b2 = luma(img, 2)
        var cx = dx * 4
        var cy = dy * 4
        val r2 = 3
        val m2 = max(abs(cx), abs(cy)) + r2 + 1
        if (a2.w > 2 * m2 + 16 && a2.h > 2 * m2 + 16) {
            val res = search(
                a2.v, a2.w, m2, m2, b2.v, b2.w, m2, m2,
                a2.w - 2 * m2, a2.h - 2 * m2, 2, cx, cy, r2
            )
            dx = res.first; dy = res.second
        } else {
            dx = cx; dy = cy
        }

        // 3) Tam boyut, merkez bölge, ±1
        cx = dx * 2
        cy = dy * 2
        val m = max(abs(cx), abs(cy)) + 2
        val bx = ref.rx - m
        val by = ref.ry - m
        val bs = ref.rs + 2 * m
        if (bx < 0 || by < 0 || bx + bs > img.width || by + bs > img.height) return cx to cy
        val region = regionLuma(img, bx, by, bs, bs)
        return search(ref.region, ref.rs, 0, 0, region, bs, m, m, ref.rs, ref.rs, 1, cx, cy, 1)
    }

    private fun search(
        a: IntArray, aw: Int, ax0: Int, ay0: Int,
        b: IntArray, bw: Int, bx0: Int, by0: Int,
        ww: Int, wh: Int, step: Int,
        cdx: Int, cdy: Int, r: Int
    ): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bdx = cdx
        var bdy = cdy
        for (dy in cdy - r..cdy + r) for (dx in cdx - r..cdx + r) {
            var sum = 0L
            var y = 0
            while (y < wh) {
                val ar = (ay0 + y) * aw + ax0
                val br = (by0 + y + dy) * bw + bx0 + dx
                var x = 0
                while (x < ww) {
                    sum += abs(a[ar + x] - b[br + x])
                    x += step
                }
                y += step
            }
            if (sum < best) { best = sum; bdx = dx; bdy = dy }
        }
        return bdx to bdy
    }
}

/** Süper detay: hizalanmış kareleri toplayıp ortalar (gürültü düşer, ince doku ortaya çıkar). */
class FrameAverager(private val w: Int, private val h: Int) {
    private val r = ShortArray(w * h)
    private val g = ShortArray(w * h)
    private val b = ShortArray(w * h)
    private val row = IntArray(w)
    private var n = 0

    fun add(bmp: Bitmap, dx: Int, dy: Int) {
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, (y + dy).coerceIn(0, h - 1), w, 1)
            val base = y * w
            for (x in 0 until w) {
                val p = row[(x + dx).coerceIn(0, w - 1)]
                val i = base + x
                r[i] = (r[i] + ((p shr 16) and 255)).toShort()
                g[i] = (g[i] + ((p shr 8) and 255)).toShort()
                b[i] = (b[i] + (p and 255)).toShort()
            }
        }
        n++
    }

    fun result(): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val half = n / 2
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val i = base + x
                row[x] = (0xFF shl 24) or
                        (((r[i] + half) / n) shl 16) or
                        (((g[i] + half) / n) shl 8) or
                        ((b[i] + half) / n)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}

/** Odak yığını: her bölge için en keskin kareyi seçer. */
class FocusStacker(private val w: Int, private val h: Int) {
    private val result = IntArray(w * h)
    private val best = FloatArray(w * h)
    private val px = IntArray(w * h)
    private val sharp = FloatArray(w * h)
    private val row = IntArray(w)
    private var first = true

    private fun lum(p: Int) =
        (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8

    fun add(bmp: Bitmap, dx: Int, dy: Int) {
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, (y + dy).coerceIn(0, h - 1), w, 1)
            val base = y * w
            for (x in 0 until w) px[base + x] = row[(x + dx).coerceIn(0, w - 1)]
        }
        // Keskinlik = Laplace büyüklüğü
        for (y in 0 until h) {
            val up = max(y - 1, 0) * w
            val dn = min(y + 1, h - 1) * w
            val base = y * w
            for (x in 0 until w) {
                val c = lum(px[base + x])
                val l = lum(px[base + max(x - 1, 0)])
                val r = lum(px[base + min(x + 1, w - 1)])
                val u = lum(px[up + x])
                val d = lum(px[dn + x])
                sharp[base + x] = abs(4 * c - l - r - u - d).toFloat()
            }
        }
        boxBlur(sharp, w, h, 4)

        if (first) {
            System.arraycopy(px, 0, result, 0, w * h)
            System.arraycopy(sharp, 0, best, 0, w * h)
            first = false
        } else {
            for (i in 0 until w * h) {
                if (sharp[i] > best[i]) { best[i] = sharp[i]; result[i] = px[i] }
            }
        }
    }

    fun result(): Bitmap = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(max(w, h))
        for (y in 0 until h) {
            val b = y * w
            var sum = 0f
            for (x in -r..r) sum += a[b + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = sum
                sum += a[b + (x + r + 1).coerceIn(0, w - 1)] - a[b + (x - r).coerceIn(0, w - 1)]
            }
            System.arraycopy(tmp, 0, a, b, w)
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += a[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = sum
                sum += a[(y + r + 1).coerceIn(0, h - 1) * w + x] - a[(y - r).coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = tmp[y]
        }
    }
}

/** Bir çok-kare çekimini baştan sona yönetir. */
class StackSession(private val focusMode: Boolean) {
    private var ref: AlignRef? = null
    private var avg: FrameAverager? = null
    private var stack: FocusStacker? = null

    fun add(bmp: Bitmap) {
        val r = ref
        if (r == null) {
            ref = Aligner.prepare(bmp)
            if (focusMode) stack = FocusStacker(bmp.width, bmp.height).also { it.add(bmp, 0, 0) }
            else avg = FrameAverager(bmp.width, bmp.height).also { it.add(bmp, 0, 0) }
        } else {
            val fb = Aligner.matchSize(bmp, r)
            val (dx, dy) = Aligner.shift(r, fb)
            if (focusMode) stack!!.add(fb, dx, dy) else avg!!.add(fb, dx, dy)
            if (fb !== bmp) fb.recycle()
        }
        bmp.recycle()
    }

    fun result(): Bitmap {
        val raw = if (focusMode) stack!!.result() else avg!!.result()
        stack = null; avg = null; ref = null
        val out = ImageProcessor.sharpen(raw, if (focusMode) 0.3f else 0.6f)
        raw.recycle()
        return out
    }
}
