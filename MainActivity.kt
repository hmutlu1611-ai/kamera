package com.makrokamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ln
import kotlin.math.pow

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class MainActivity : AppCompatActivity() {

    companion object {
        /** Telefonun kendi yakınlaştırma sınırının üstüne eklenen dijital kat. */
        private const val EXTRA_ZOOM = 4f
        private const val ZOOM_STEPS = 1000
        private const val STACK_FRAMES = 10
        private const val STACK_MAX = 3200
        private const val SUPER_FRAMES = 8
        private const val SUPER_MAX = 4096
        private const val MODE_PHOTO = 0
        private const val MODE_STACK = 1
        private const val MODE_SUPER = 2
    }

    private lateinit var previewView: PreviewView
    private lateinit var touchLayer: View
    private lateinit var focusRing: View
    private lateinit var btnMacro: Button
    private lateinit var btnMicro: Button
    private lateinit var statusText: TextView
    private lateinit var modeViews: List<TextView>
    private lateinit var btnLens: Button
    private lateinit var btnTorch: Button
    private lateinit var btnSettings: Button
    private lateinit var settingsPanel: View
    private lateinit var presetRow: LinearLayout
    private lateinit var btnAf: Button
    private lateinit var infoText: TextView
    private lateinit var zoomText: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var focusText: TextView
    private lateinit var focusSeek: SeekBar
    private lateinit var evText: TextView
    private lateinit var evSeek: SeekBar
    private lateinit var btnCapture: ImageButton
    private lateinit var thumb: ImageView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var backCameras: List<CameraInfo> = emptyList()
    private var lensIndex = 0
    private var macroMode = false
    private var torchOn = false
    private var manualFocus = false
    private var minFocusDiopter = 0f
    private var evLower = 0
    private var lastUri: Uri? = null
    private var scaling = false
    private var microMode = false
    private var mode = MODE_PHOTO
    private var busy = false
    private var supportsManual = false
    private val bgExecutor = Executors.newSingleThreadExecutor()

    // Yakınlaştırma: toplam = kamera yakınlaştırması × ekstra dijital
    private var camMin = 1f
    private var camMax = 1f
    private var maxTotalZoom = EXTRA_ZOOM
    private var totalZoom = 1f
    private var extraZoom = 1f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else toast("Kamera izni olmadan uygulama çalışamaz")
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { openEditor(it) }
        }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                handleScan(GmsDocumentScanningResult.fromActivityResultIntent(res.data))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupControls()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        touchLayer = findViewById(R.id.touchLayer)
        focusRing = findViewById(R.id.focusRing)
        btnMacro = findViewById(R.id.btnMacro)
        btnMicro = findViewById(R.id.btnMicro)
        statusText = findViewById(R.id.statusText)
        modeViews = listOf(
            findViewById<TextView>(R.id.modePhoto), findViewById<TextView>(R.id.modeStack), findViewById<TextView>(R.id.modeSuper)
        )
        btnLens = findViewById(R.id.btnLens)
        btnTorch = findViewById(R.id.btnTorch)
        btnSettings = findViewById(R.id.btnSettings)
        settingsPanel = findViewById(R.id.settingsPanel)
        presetRow = findViewById(R.id.presetRow)
        btnAf = findViewById(R.id.btnAf)
        infoText = findViewById(R.id.infoText)
        zoomText = findViewById(R.id.zoomText)
        zoomSeek = findViewById(R.id.zoomSeek)
        focusText = findViewById(R.id.focusText)
        focusSeek = findViewById(R.id.focusSeek)
        evText = findViewById(R.id.evText)
        evSeek = findViewById(R.id.evSeek)
        btnCapture = findViewById(R.id.btnCapture)
        thumb = findViewById(R.id.thumb)
    }

    private fun setupControls() {
        btnMacro.setOnClickListener { toggleMacro() }
        btnLens.setOnClickListener {
            if (backCameras.size > 1) {
                lensIndex = (lensIndex + 1) % backCameras.size
                totalZoom = 1f
                bindCamera()
            } else toast("Telefon başka bir arka lensi uygulamalara açmıyor")
        }
        btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            btnTorch.text = if (torchOn) "Işık ●" else "Işık"
        }
        btnSettings.setOnClickListener {
            val show = settingsPanel.visibility != View.VISIBLE
            settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
            btnSettings.text = if (show) "Ayar ▲" else "Ayar ▼"
        }
        btnAf.setOnClickListener { resetAutoFocus() }
        btnCapture.setOnClickListener { onShutter() }
        btnMicro.setOnClickListener { toggleMicro() }
        modeViews.forEachIndexed { i, v ->
            v.setOnClickListener {
                mode = i
                styleModes()
                when (i) {
                    MODE_STACK -> toast("Odak yığını: $STACK_FRAMES kare farklı odakta çekilir, her yeri net tek foto olur. Telefonu bir yere dayayarak kullan.")
                    MODE_SUPER -> toast("Süper detay: $SUPER_FRAMES kare birleştirilir, gren azalır, ince doku belirginleşir. Telefonu sabit tut.")
                }
            }
        }
        styleModes()
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        thumb.setOnClickListener {
            lastUri?.let { openEditor(it) } ?: toast("Henüz fotoğraf çekilmedi")
        }

        zoomSeek.max = ZOOM_STEPS
        zoomSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setTotalZoom(progressToZoom(p))
            }
        })
        focusSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setManualFocus(p)
            }
        })
        evSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val idx = p + evLower
                evText.text = "Pozlama: $idx"
                if (fromUser) camera?.cameraControl?.setExposureCompensationIndex(idx)
            }
        })

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    scaling = true; return true
                }
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    setTotalZoom(totalZoom * d.scaleFactor)
                    return true
                }
            })
        touchLayer.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> scaling = false
                MotionEvent.ACTION_UP -> if (!scaling) tapToFocus(e.x, e.y)
            }
            true
        }
    }

    // Ses tuşlarıyla deklanşör (makroda telefonu sarsmamak için çok işe yarar)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if ((event?.repeatCount ?: 0) == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Yakınlaştırma ----------------

    private fun progressToZoom(p: Int): Float {
        if (maxTotalZoom <= camMin) return camMin
        return camMin * (maxTotalZoom / camMin).pow(p / ZOOM_STEPS.toFloat())
    }

    private fun zoomToProgress(z: Float): Int {
        if (maxTotalZoom <= camMin) return 0
        return (ln(z / camMin) / ln(maxTotalZoom / camMin) * ZOOM_STEPS).toInt().coerceIn(0, ZOOM_STEPS)
    }

    private val refocusRunnable = Runnable {
        if (!manualFocus && !busy) focusAt(touchLayer.width / 2f, touchLayer.height / 2f, false, true)
    }

    private fun scheduleRefocus() {
        touchLayer.removeCallbacks(refocusRunnable)
        touchLayer.postDelayed(refocusRunnable, 400)
    }

    private fun setTotalZoom(z: Float) {
        val cam = camera ?: return
        val before = totalZoom
        totalZoom = z.coerceIn(camMin, maxTotalZoom)
        val camZ = totalZoom.coerceAtMost(camMax)
        extraZoom = totalZoom / camZ
        cam.cameraControl.setZoomRatio(camZ)
        previewView.scaleX = extraZoom
        previewView.scaleY = extraZoom
        zoomText.text = "%.1fx".format(totalZoom)
        zoomText.setTextColor(if (extraZoom > 1.01f) Color.parseColor("#FF7043") else Color.parseColor("#FFB300"))
        zoomSeek.progress = zoomToProgress(totalZoom)
        if (kotlin.math.abs(totalZoom - before) > 0.001f) scheduleRefocus()
    }

    private fun buildPresets() {
        presetRow.removeAllViews()
        val values = mutableListOf<Float>()
        if (camMin < 0.95f) values += camMin
        values += listOf(1f, 2f, 5f, 10f, 20f, 30f, 50f, 80f)
            .filter { it >= camMin && it < maxTotalZoom - 0.5f }
        values += maxTotalZoom
        val dp = resources.displayMetrics.density
        for (v in values) {
            val tv = TextView(this).apply {
                text = when {
                    v == maxTotalZoom -> "MAX ${v.toInt()}x"
                    v < 1f -> "%.1fx".format(v)
                    else -> "${v.toInt()}x"
                }
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(if (v > camMax + 0.01f) 0x66FF7043 else 0x44FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * dp).toInt() }
                setOnClickListener { setTotalZoom(v) }
            }
            presetRow.addView(tv)
        }
    }

    // ---------------- Kamera ----------------

    private fun cameraId(info: CameraInfo) = Camera2CameraInfo.from(info).cameraId

    private fun minFocusOf(info: CameraInfo): Float =
        Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    private fun isBack(info: CameraInfo) =
        Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val all = provider.availableCameraInfos
            backCameras = all.filter { isBack(it) }.ifEmpty { all }
            lensIndex = 0
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val info = backCameras.getOrNull(lensIndex) ?: return
        val id = cameraId(info)
        val selector = CameraSelector.Builder()
            .addCameraFilter(CameraFilter { list -> list.filter { cameraId(it) == id } })
            .build()

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val capBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolution)
            .setJpegQuality(100)
        Camera2Interop.Extender(capBuilder)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        val capture = capBuilder.build()

        camera?.cameraInfo?.zoomState?.removeObservers(this)
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, selector, preview, capture)
            camera = cam
            imageCapture = capture
            onCameraBound(cam, info)
        } catch (e: Exception) {
            toast("Kamera açılamadı: ${e.message}")
        }
    }

    private fun onCameraBound(cam: Camera, info: CameraInfo) {
        manualFocus = false
        minFocusDiopter = minFocusOf(info)
        supportsManual = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        focusSeek.isEnabled = minFocusDiopter > 0f
        focusSeek.progress = 0
        focusText.text = if (minFocusDiopter > 0f) "Odak: Otomatik" else "Odak: sabit odaklı lens"
        val closest = if (minFocusDiopter > 0f) "%.1f cm".format(100f / minFocusDiopter) else "-"
        infoText.text = "Lens ${lensIndex + 1}/${backCameras.size}  •  En yakın odak: $closest"
        btnLens.text = "Lens ${lensIndex + 1}"

        // Kameranın gerçek yakınlaştırma sınırları açılınca belli olur
        cam.cameraInfo.zoomState.observe(this) { z ->
            if (z.maxZoomRatio != camMax || z.minZoomRatio != camMin) {
                camMin = z.minZoomRatio
                camMax = z.maxZoomRatio
                maxTotalZoom = camMax * EXTRA_ZOOM
                buildPresets()
                setTotalZoom(totalZoom)
            }
        }

        val exp = cam.cameraInfo.exposureState
        if (exp.isExposureCompensationSupported) {
            evLower = exp.exposureCompensationRange.lower
            evSeek.max = exp.exposureCompensationRange.upper - evLower
            evSeek.progress = exp.exposureCompensationIndex - evLower
            evSeek.isEnabled = true
        } else {
            evSeek.isEnabled = false
        }
        evText.text = "Pozlama: ${exp.exposureCompensationIndex}"

        if (torchOn) cam.cameraControl.enableTorch(true)
        touchLayer.post { focusAt(touchLayer.width / 2f, touchLayer.height / 2f, false, true) }
    }

    private fun toggleMacro() {
        macroMode = !macroMode
        if (macroMode && microMode) {
            microMode = false
            btnMicro.text = "Mikro"
        }
        lensIndex = if (macroMode) {
            backCameras.indices.maxByOrNull { minFocusOf(backCameras[it]) } ?: 0
        } else 0
        totalZoom = 1f
        btnMacro.text = if (macroMode) "Makro ●" else "Makro"
        bindCamera()
        if (macroMode) {
            toast("En yakına odaklanan lens seçildi. Odak kaçarsa biraz geri çekil ve yakınlaştır.")
        }
    }

    private fun setManualFocus(p: Int) {
        val cam = camera ?: return
        if (minFocusDiopter <= 0f) return
        manualFocus = true
        val d = minFocusDiopter * p / 100f
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
                .build()
        )
        focusText.text = if (d > 0f) "Odak: %.1f cm (manuel)".format(100f / d) else "Odak: ∞ (manuel)"
    }

    private fun resetAutoFocus() {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        manualFocus = false
        focusSeek.progress = 0
        focusText.text = "Odak: Otomatik"
        focusAt(touchLayer.width / 2f, touchLayer.height / 2f, true)
    }

    private fun tapToFocus(x: Float, y: Float) {
        if (manualFocus) {
            camera?.let { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() }
            manualFocus = false
            focusSeek.progress = 0
            focusText.text = "Odak: Otomatik"
        }
        focusAt(x, y, true)
    }

    private fun focusAt(x: Float, y: Float, showRing: Boolean, afOnly: Boolean = false) {
        val cam = camera ?: return
        // Dijital büyütme varken dokunulan noktayı gerçek görüntüdeki yerine çevir
        val cx = previewView.width / 2f
        val cy = previewView.height / 2f
        val lx = cx + (x - cx) / extraZoom
        val ly = cy + (y - cy) / extraZoom
        // Yakınlaştıkça ölçüm alanını küçült: netleme tam baktığın ayrıntıya yapılsın
        val size = (0.15f / totalZoom.coerceAtLeast(1f).pow(0.5f)).coerceIn(0.04f, 0.15f)
        val point = previewView.meteringPointFactory.createPoint(lx, ly, size)
        val flags = if (afOnly) FocusMeteringAction.FLAG_AF
        else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        // Otomatik iptal kapalı: netleme o noktada kalır ve sürekli takip eder
        val action = FocusMeteringAction.Builder(point, flags).disableAutoCancel().build()
        cam.cameraControl.startFocusAndMetering(action)
        if (showRing) {
            focusRing.x = x - focusRing.width / 2f
            focusRing.y = y - focusRing.height / 2f
            focusRing.animate().cancel()
            focusRing.alpha = 1f
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(400).start()
        }
    }

    // ---------------- Mikro modu ----------------

    private fun toggleMicro() {
        microMode = !microMode
        btnMicro.text = if (microMode) "Mikro ●" else "Mikro"
        if (microMode) {
            if (macroMode) { macroMode = false; btnMacro.text = "Makro" }
            lensIndex = 0
            totalZoom = 1f
            torchOn = true
            btnTorch.text = "Işık ●"
            bindCamera()
            toast("Mikro: Klipsli lensi ana kameraya tak. Işık açıldı, çekim 2 sn gecikmeli. Netlemek için telefonu yavaşça yaklaştırıp uzaklaştır.")
        } else {
            torchOn = false
            camera?.cameraControl?.enableTorch(false)
            btnTorch.text = "Işık"
        }
    }

    // ---------------- Çekim modları ----------------

    private fun styleModes() {
        val dp = resources.displayMetrics.density
        modeViews.forEachIndexed { i, v ->
            val sel = i == mode
            v.setTextColor(if (sel) Color.BLACK else Color.WHITE)
            v.background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(if (sel) Color.parseColor("#FFB300") else 0x44FFFFFF)
            }
        }
    }

    private fun status(s: String?) {
        statusText.visibility = if (s == null) View.GONE else View.VISIBLE
        statusText.text = s ?: ""
    }

    private fun setBusy(b: Boolean) {
        busy = b
        btnCapture.isEnabled = !b
        btnCapture.alpha = if (b) 0.4f else 1f
    }

    private suspend fun countdown(sec: Int) {
        for (s in sec downTo 1) { status("$s"); delay(1000) }
    }

    private fun onShutter() {
        if (busy) return
        when (mode) {
            MODE_STACK -> runMultiFrame(true)
            MODE_SUPER -> runMultiFrame(false)
            else -> if (microMode) {
                lifecycleScope.launch {
                    setBusy(true)
                    try { countdown(2) } finally { status(null); setBusy(false) }
                    takePhoto()
                }
            } else takePhoto()
        }
    }

    /** Kareyi dosyaya yazmadan belleğe alır. */
    private suspend fun captureBitmap(ic: ImageCapture, maxSide: Int): Bitmap =
        suspendCancellableCoroutine { cont ->
            ic.takePicture(bgExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val rot = image.imageInfo.rotationDegrees
                        image.close()
                        cont.resume(ImageProcessor.jpegToBitmap(bytes, rot, maxSide))
                    } catch (e: Throwable) {
                        runCatching { image.close() }
                        cont.resumeWithException(e)
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    cont.resumeWithException(e)
                }
            })
        }

    /** Odak yığını için taranacak odak aralığı (diyoptri: büyük = yakın). */
    private fun focusRange(): Pair<Float, Float> {
        val maxD = minFocusDiopter
        return if (manualFocus && focusSeek.progress > 0) {
            val c = maxD * focusSeek.progress / 100f
            (c * 1.5f).coerceAtMost(maxD) to c * 0.6f
        } else maxD to maxD * 0.2f
    }

    private fun restoreFocus() {
        val cam = camera ?: return
        if (manualFocus) setManualFocus(focusSeek.progress)
        else Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
    }

    private fun runMultiFrame(focusMode: Boolean) {
        val cam = camera ?: return
        val ic = imageCapture ?: return
        if (focusMode && (minFocusDiopter <= 0f || !supportsManual)) {
            toast("Bu lens manuel odak desteklemiyor, odak yığını yapılamıyor. Lens değiştirip dene ya da Süper Detay kullan.")
            return
        }
        setBusy(true)
        val extra = extraZoom
        val frames = if (focusMode) STACK_FRAMES else SUPER_FRAMES
        val maxSide = if (focusMode) STACK_MAX else SUPER_MAX
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val (near, far) = focusRange()
        val title = if (focusMode) "Odak yığını" else "Süper detay"

        lifecycleScope.launch {
            try {
                countdown(2)
                val session = StackSession(focusMode)
                for (i in 0 until frames) {
                    status("$title  ${i + 1}/$frames\nTelefonu kıpırdatma")
                    if (focusMode) {
                        val d = near + (far - near) * i / (frames - 1)
                        c2.setCaptureRequestOptions(
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
                                .build()
                        )
                        delay(500) // merceğin yerine oturmasını bekle
                    }
                    val bmp = captureBitmap(ic, maxSide)
                    withContext(Dispatchers.Default) { session.add(bmp) }
                }
                status("Birleştiriliyor…")
                val uri = withContext(Dispatchers.Default) {
                    val out = cropCenterBitmap(session.result(), extra)
                    val u = ImageProcessor.saveJpeg(contentResolver, out, if (focusMode) "ODAKYIGINI" else "SUPERDETAY")
                    out.recycle()
                    u
                }
                if (uri != null) {
                    lastUri = uri
                    loadThumb(uri)
                    toast("$title kaydedildi. İncelemek için küçük resme dokun.")
                } else toast("Kaydedilemedi")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                toast(if (e is OutOfMemoryError) "Bellek yetmedi, tekrar dene." else "Hata: ${e.message}")
            } finally {
                if (focusMode) restoreFocus()
                status(null)
                setBusy(false)
            }
        }
    }

    private fun cropCenterBitmap(bmp: Bitmap, factor: Float): Bitmap {
        if (factor <= 1.01f) return bmp
        val cw = (bmp.width / factor).toInt().coerceAtLeast(1)
        val ch = (bmp.height / factor).toInt().coerceAtLeast(1)
        val c = Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch)
        if (c !== bmp) bmp.recycle()
        return c
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdown()
    }

    // ---------------- Çekim ----------------

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (!btnCapture.isEnabled || busy) return
        val extra = extraZoom
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MAKRO_${timestamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MakroKamera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        btnCapture.isEnabled = false
        btnCapture.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            btnCapture.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        }.start()

        ic.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    val uri = r.savedUri
                    if (uri == null) { btnCapture.isEnabled = true; return }
                    lifecycleScope.launch {
                        if (extra > 1.01f) {
                            withContext(Dispatchers.Default) { cropCenter(uri, extra) }
                        }
                        btnCapture.isEnabled = true
                        lastUri = uri
                        loadThumb(uri)
                        toast("Kaydedildi: Resimler/MakroKamera")
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    toast("Çekim hatası: ${e.message}")
                }
            })
    }

    /** Ekstra dijital yakınlaştırmada, ekranda görülen orta bölgeyi kırpar. */
    private fun cropCenter(uri: Uri, factor: Float): Boolean {
        var maxSide = 16000
        while (maxSide >= 1024) {
            try {
                val full = ImageProcessor.decode(contentResolver, uri, maxSide)
                val cw = (full.width / factor).toInt().coerceAtLeast(1)
                val ch = (full.height / factor).toInt().coerceAtLeast(1)
                val crop = Bitmap.createBitmap(full, (full.width - cw) / 2, (full.height - ch) / 2, cw, ch)
                if (crop !== full) full.recycle()
                contentResolver.openOutputStream(uri, "wt")?.use {
                    crop.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }
                crop.recycle()
                return true
            } catch (e: OutOfMemoryError) {
                System.gc()
                maxSide /= 2
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun loadThumb(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(uri, Size(200, 200), null) }.getOrNull()
            }
            bmp?.let { thumb.setImageBitmap(it) }
        }
    }

    private fun openEditor(uri: Uri) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    // ---------------- Tarama ----------------

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { toast("Tarayıcı açılamadı: ${it.message}") }
    }

    private fun handleScan(result: GmsDocumentScanningResult?) {
        if (result == null) return
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val stamp = timestamp()
                var pages = 0
                var pdfSaved = false
                result.pages?.forEachIndexed { i, page ->
                    if (copyToMediaStore(
                            page.imageUri, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            "TARAMA_${stamp}_${i + 1}.jpg", "image/jpeg",
                            Environment.DIRECTORY_PICTURES + "/MakroKamera/Taramalar"
                        ) != null
                    ) pages++
                }
                result.pdf?.let { pdf ->
                    pdfSaved = copyToMediaStore(
                        pdf.uri, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "TARAMA_$stamp.pdf", "application/pdf",
                        Environment.DIRECTORY_DOWNLOADS + "/MakroKamera"
                    ) != null
                }
                "$pages sayfa kaydedildi" + if (pdfSaved) " + PDF (İndirilenler/MakroKamera)" else ""
            }
            toast(msg)
        }
    }

    private fun copyToMediaStore(src: Uri, collection: Uri, name: String, mime: String, relPath: String): Uri? =
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val dst = contentResolver.insert(collection, values) ?: return@runCatching null
            contentResolver.openInputStream(src)?.use { input ->
                contentResolver.openOutputStream(dst)?.use { out -> input.copyTo(out) }
            }
            dst
        }.getOrNull()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
