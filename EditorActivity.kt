package com.makrokamera.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class EditorActivity : AppCompatActivity() {

    companion object {
        private const val PREVIEW_MAX = 4096    // ekranda incelemek için
        private const val SAVE_MAX = 16000      // kayıt: tam çözünürlük
    }

    private lateinit var imageView: ZoomImageView
    private lateinit var progress: ProgressBar
    private lateinit var btnSave: Button

    private var sourceUri: Uri? = null
    private var original: Bitmap? = null
    private var processed: Bitmap? = null
    private var renderJob: Job? = null
    private var detailJob: Job? = null
    private var regionLoader: RegionLoader? = null
    private var adjust = Adjust()
    private val sliders = mutableListOf<Pair<SeekBar, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        imageView = findViewById(R.id.imageView)
        progress = findViewById(R.id.progress)
        imageView.onViewChanged = { scheduleDetail() }
        btnSave = findViewById(R.id.btnSave)

        sourceUri = intent.data
        if (sourceUri == null) { finish(); return }

        slider(R.id.seekBrightness, R.id.labelBrightness, "Parlaklık", 100, { "${it - 100}" }) {
            adjust = adjust.copy(brightness = (it - 100).toFloat())
        }
        slider(R.id.seekContrast, R.id.labelContrast, "Kontrast", 100, { "${it - 100}" }) {
            adjust = adjust.copy(contrast = it / 100f)
        }
        slider(R.id.seekSaturation, R.id.labelSaturation, "Doygunluk", 100, { "${it - 100}" }) {
            adjust = adjust.copy(saturation = it / 100f)
        }
        slider(R.id.seekWarmth, R.id.labelWarmth, "Sıcaklık", 100, { "${it - 100}" }) {
            adjust = adjust.copy(warmth = (it - 100) * 0.4f)
        }
        slider(R.id.seekSharpness, R.id.labelSharpness, "Keskinlik", 0, { "$it" }) {
            adjust = adjust.copy(sharpness = it / 100f)
        }

        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            adjust = adjust.copy(rotation = (adjust.rotation + 90) % 360)
            scheduleRender()
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            adjust = Adjust()
            sliders.forEach { (s, init) -> s.progress = init }
            scheduleRender()
        }
        findViewById<Button>(R.id.btnCompare).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> original?.let {
                    imageView.overlayEnabled = false
                    imageView.setImageBitmapKeepZoom(it)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    imageView.overlayEnabled = true
                    processed?.let { imageView.setImageBitmapKeepZoom(it) }
                }
            }
            true
        }
        btnSave.setOnClickListener { save() }
        val panel = findViewById<View>(R.id.adjustPanel)
        val btnPanel = findViewById<Button>(R.id.btnPanel)
        btnPanel.setOnClickListener {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            btnPanel.text = if (show) "Gizle" else "Ayarlar"
        }

        loadPreview()
    }

    private fun slider(
        seekId: Int, labelId: Int, title: String, initial: Int,
        display: (Int) -> String, onValue: (Int) -> Unit
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(labelId)
        seek.max = 200
        seek.progress = initial
        label.text = "$title: ${display(initial)}"
        sliders += seek to initial
        seek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                label.text = "$title: ${display(p)}"
                onValue(p)
                scheduleRender()
            }
        })
    }

    private fun loadPreview() {
        val uri = sourceUri ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { ImageProcessor.decode(contentResolver, uri, PREVIEW_MAX) }.getOrNull()
            }
            progress.visibility = View.GONE
            if (bmp == null) {
                toast("Resim açılamadı"); finish(); return@launch
            }
            original = bmp
            processed = bmp
            imageView.setImageBitmapKeepZoom(bmp)

            // Büyütünce orijinalden tam çözünürlüklü bölge okumak için hazırlan
            val loader = withContext(Dispatchers.IO) { RegionLoader.open(contentResolver, uri) }
            regionLoader = loader
            if (loader != null) {
                val mp = loader.orientedW.toDouble() * loader.orientedH / 1_000_000.0
                val mpText = "%.1f".format(mp)
                toast("Orijinal fotoğraf: ${loader.orientedW} x ${loader.orientedH} ($mpText MP)")
                scheduleDetail()
            }
        }
    }

    private fun scheduleDetail() {
        detailJob?.cancel()
        detailJob = lifecycleScope.launch {
            delay(350)
            updateDetail()
        }
    }

    /** Büyütülmüş bölgeyi orijinalden tam çözünürlükte okuyup üstüne koyar. */
    private suspend fun updateDetail() {
        val loader = regionLoader ?: return
        val base = original ?: return
        val a = adjust
        val s = imageView.currentScale()
        val pw = base.width.toFloat()
        val ph = base.height.toFloat()
        val k = loader.orientedW / pw
        // Önizleme zaten yeterliyse (küçük resim ya da az büyütme) katmana gerek yok
        if (k < 1.05f || s < 0.9f) { imageView.setOverlay(null, null); return }

        val vis = imageView.visibleRect()
        val dw = if (a.rotation % 180 == 0) pw else ph
        val dh = if (a.rotation % 180 == 0) ph else pw
        vis.inset(-vis.width() * 0.15f, -vis.height() * 0.15f)
        if (!vis.intersect(0f, 0f, dw, dh) || vis.isEmpty()) return

        val ru = rotMatrix(a.rotation, pw, ph)
        val inv = Matrix()
        if (!ru.invert(inv)) return
        val want = RectF(vis)
        inv.mapRect(want)
        want.left *= k; want.right *= k; want.top *= k; want.bottom *= k
        if (!want.intersect(0f, 0f, loader.orientedW.toFloat(), loader.orientedH.toFloat())) return
        val target = (max(vis.width(), vis.height()) * s).toInt().coerceAtLeast(64)

        val result = withContext(Dispatchers.Default) {
            runCatching {
                val reg = loader.decode(want, target) ?: return@runCatching null
                val out = ImageProcessor.process(reg.bitmap, a, recycleSource = true)
                val r = RectF(reg.orientedRect)
                r.left /= k; r.right /= k; r.top /= k; r.bottom /= k
                ru.mapRect(r)
                out to r
            }.getOrNull()
        }
        if (result == null || a != adjust) return
        imageView.setOverlay(result.first, result.second)
    }

    override fun onDestroy() {
        super.onDestroy()
        regionLoader?.close()
    }

    private fun scheduleRender() {
        val src = original ?: return
        val a = adjust
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(80)
            val out = withContext(Dispatchers.Default) {
                ImageProcessor.process(src, a, recycleSource = false)
            }
            processed = out
            imageView.setImageBitmapKeepZoom(out)
        }
    }

    private fun save() {
        val uri = sourceUri ?: return
        val a = adjust
        btnSave.isEnabled = false
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.Default) {
                var maxSide = SAVE_MAX
                var result: Uri? = null
                var done = false
                while (!done && maxSide >= 1024) {
                    try {
                        val full = ImageProcessor.decode(contentResolver, uri, maxSide)
                        val out = ImageProcessor.process(full, a, recycleSource = true)
                        result = saveJpeg(out)
                        out.recycle()
                        done = true
                    } catch (e: OutOfMemoryError) {
                        System.gc()
                        maxSide /= 2   // bellek yetmezse çözünürlüğü düşürüp tekrar dene
                    } catch (e: Exception) {
                        done = true
                    }
                }
                result
            }
            progress.visibility = View.GONE
            btnSave.isEnabled = true
            toast(if (saved != null) "Kaydedildi: Resimler/MakroKamera" else "Kaydedilemedi")
        }
    }

    private fun saveJpeg(bmp: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DUZENLENMIS_${timestamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MakroKamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
