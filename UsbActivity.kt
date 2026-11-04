package com.makrokamera.usb

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.*
import com.serenegiant.usb.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB (UVC) mikroskop / harici kamera önizleme ve çekim.
 * UVCAndroid kütüphanesi (com.herohan:UVCAndroid) kullanılır.
 */
class UsbActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private var helper: ICameraHelper? = null
    private var surface: Surface? = null
    private var device: UsbDevice? = null
    private var cameraOpen = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Kamera izni olmadan USB kamera açılamayabilir")
        }

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            runOnUiThread { statusText.text = "USB kamera bulundu, izin isteniyor…" }
            helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            this@UsbActivity.device = device
            helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            cameraOpen = true
            helper?.startPreview()
            surface?.let { helper?.addSurface(it, false) }
            runOnUiThread { statusText.text = "Bağlı: ${device.productName ?: device.deviceName}" }
        }

        override fun onCameraClose(device: UsbDevice) {
            cameraOpen = false
            surface?.let { helper?.removeSurface(it) }
            runOnUiThread { statusText.text = "Kamera kapandı" }
        }

        override fun onDeviceClose(device: UsbDevice) {}

        override fun onDetach(device: UsbDevice) {
            cameraOpen = false
            runOnUiThread { statusText.text = "USB mikroskobu telefona tak" }
        }

        override fun onCancel(device: UsbDevice) {
            runOnUiThread { statusText.text = "USB izni verilmedi" }
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            runOnUiThread { statusText.text = "Hata: ${e.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb)
        textureView = findViewById(R.id.textureView)
        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.btnShot).setOnClickListener { shoot() }
        findViewById<Button>(R.id.btnCase).setOnClickListener { caseDialog() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) permissionLauncher.launch(Manifest.permission.CAMERA)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                if (cameraOpen) surface?.let { helper?.addSurface(it, false) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surface?.let { helper?.removeSurface(it) }
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onStart() {
        super.onStart()
        helper = CameraHelper().also { it.setStateCallback(callback) }
    }

    override fun onStop() {
        super.onStop()
        helper?.releaseAll()
        helper = null
        cameraOpen = false
    }

    private fun shoot() {
        val h = helper
        if (h == null || !cameraOpen) { toast("Kamera bağlı değil"); return }
        val name = "USB_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val file = File(cacheDir, name)
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        h.takePicture(opts, object : ImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                Thread { publish(file, name) }.start()
            }
            override fun onError(code: Int, message: String, cause: Throwable?) {
                runOnUiThread { toast("Çekim hatası: $message") }
            }
        })
    }

    private fun publish(file: File, name: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MakroKamera")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("kaydedilemedi")
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            file.delete()
            val dev = device
            EvidenceLog.write(
                this, uri, "USB mikroskop / harici kamera",
                listOf(
                    "Kamera" to (dev?.productName ?: dev?.deviceName ?: "bilinmiyor"),
                    "Üretici" to (dev?.manufacturerName ?: "bilinmiyor")
                )
            )
            runOnUiThread { toast("Kaydedildi: Resimler/MakroKamera") }
        } catch (e: Exception) {
            runOnUiThread { toast("Kayıt hatası: ${e.message}") }
        }
    }

    private fun caseDialog() {
        val prefs = getSharedPreferences(EvidenceLog.PREFS, MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val no = EditText(this).apply { hint = "Dosya / vaka no"; setText(prefs.getString("no", "")) }
        val who = EditText(this).apply { hint = "İnceleyen"; setText(prefs.getString("inceleyen", "")) }
        val note = EditText(this).apply { hint = "Not"; setText(prefs.getString("not", "")) }
        box.addView(no); box.addView(who); box.addView(note)
        AlertDialog.Builder(this)
            .setTitle("Vaka bilgisi")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putString("no", no.text.toString())
                    .putString("inceleyen", who.text.toString())
                    .putString("not", note.text.toString()).apply()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
