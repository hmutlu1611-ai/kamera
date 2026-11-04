package com.makrokamera.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * İki parmakla 50 kata kadar büyütme, kaydırma, çift dokunma.
 * Büyütülünce üstüne tam çözünürlüklü "ayrıntı katmanı" çizilebilir.
 */
class ZoomImageView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : AppCompatImageView(ctx, attrs) {

    private val m = Matrix()
    private val v = FloatArray(9)
    private var baseScale = 1f
    private var bw = 0
    private var bh = 0

    private var overlay: Bitmap? = null
    private val overlayRect = RectF()
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    var overlayEnabled = true
        set(value) { field = value; invalidate() }

    /** Görünüm (büyütme/kaydırma) her değiştiğinde çağrılır. */
    var onViewChanged: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                val target = (cur * d.scaleFactor).coerceIn(baseScale, baseScale * 50f)
                val f = target / cur
                m.postScale(f, f, d.focusX, d.focusY)
                apply()
                return true
            }
        })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy)
            apply()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale() > baseScale * 1.1f) fit() else {
                m.postScale(4f, 4f, e.x, e.y)
                apply()
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setImageBitmapKeepZoom(b: Bitmap) {
        val same = b.width == bw && b.height == bh
        super.setImageBitmap(b)
        overlay = null            // ayarlar değişti, eski ayrıntı katmanı geçersiz
        bw = b.width; bh = b.height
        if (same) apply() else fit()
    }

    /** Ekrandaki 1 pikselin kaç resim pikseli olduğu değil, tersi: resim pikseli başına ekran pikseli. */
    fun currentScale(): Float { m.getValues(v); return v[Matrix.MSCALE_X] }

    /** Ekranda görünen bölge (resim koordinatlarında). */
    fun visibleRect(): RectF {
        val full = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
        val inv = Matrix()
        if (!m.invert(inv)) return full
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(r)
        if (!r.intersect(full)) return full
        return r
    }

    fun setOverlay(b: Bitmap?, rect: RectF?) {
        overlay = b
        if (rect != null) overlayRect.set(rect)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    private fun fit() {
        if (bw == 0 || width == 0 || height == 0) return
        baseScale = min(width / bw.toFloat(), height / bh.toFloat())
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - bw * baseScale) / 2f, (height - bh * baseScale) / 2f)
        apply()
    }

    private fun apply() {
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val iw = bw * s
        val ih = bh * s
        v[Matrix.MTRANS_X] = if (iw <= width) (width - iw) / 2f else v[Matrix.MTRANS_X].coerceIn(width - iw, 0f)
        v[Matrix.MTRANS_Y] = if (ih <= height) (height - ih) / 2f else v[Matrix.MTRANS_Y].coerceIn(height - ih, 0f)
        m.setValues(v)
        // Yumuşak (pürüzsüz) büyütme: blok blok pikseller yerine doğal görünüm
        (drawable as? BitmapDrawable)?.isFilterBitmap = true
        imageMatrix = m
        onViewChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val o = overlay
        if (o != null && overlayEnabled) {
            canvas.save()
            canvas.concat(imageMatrix)
            canvas.drawBitmap(o, null, overlayRect, overlayPaint)
            canvas.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }
}
