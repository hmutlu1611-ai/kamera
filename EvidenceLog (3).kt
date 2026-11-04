package com.makrokamera.usb

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Her çekimin yanına "kayıt dosyası" yazar: dosya adı, SHA-256, tarih-saat, cihaz, ayarlar, vaka bilgisi.
 * Dosya sonradan değişirse SHA-256 değeri tutmaz; böylece orijinalliği gösterilebilir.
 * Kayıtlar Documents/MakroKamera klasörüne düşer.
 */
object EvidenceLog {

    const val PREFS = "vaka"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("kayit", true)

    fun sha256(cr: ContentResolver, uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val stream = cr.openInputStream(uri) ?: return null
            stream.use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(cr: ContentResolver, uri: Uri): String? = try {
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun dimensions(cr: ContentResolver, uri: Uri): String? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0) "${o.outWidth} x ${o.outHeight}" else null
    } catch (e: Exception) {
        null
    }

    /** Arka planda (IO iş parçacığında) çağır. */
    fun write(ctx: Context, imageUri: Uri, kind: String, details: List<Pair<String, String>>) {
        try {
            val cr = ctx.contentResolver
            val name = displayName(cr, imageUri) ?: "kayit_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (e: Exception) { "?" }

            val sb = StringBuilder()
            sb.append("MAKRO KAMERA - ÇEKİM KAYDI\n")
            sb.append("==========================\n")
            sb.append("Dosya: ").append(name).append('\n')
            sb.append("SHA-256: ").append(sha256(cr, imageUri) ?: "hesaplanamadı").append('\n')
            dimensions(cr, imageUri)?.let { sb.append("Çözünürlük: ").append(it).append('\n') }
            sb.append("Tarih-saat: ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
            sb.append("Tür: ").append(kind).append('\n')
            sb.append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
            sb.append("Uygulama sürümü: ").append(version).append('\n')
            sb.append("--- Vaka bilgisi ---\n")
            sb.append("Dosya/vaka no: ").append(prefs.getString("no", "") ?: "").append('\n')
            sb.append("İnceleyen: ").append(prefs.getString("inceleyen", "") ?: "").append('\n')
            sb.append("Not: ").append(prefs.getString("not", "") ?: "").append('\n')
            if (details.isNotEmpty()) sb.append("--- Ayarlar ---\n")
            for ((k, v) in details) sb.append(k).append(": ").append(v).append('\n')

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name.substringBeforeLast('.') + ".kayit.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/MakroKamera")
            }
            val dst = cr.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            cr.openOutputStream(dst)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            // Kayıt yazılamadıysa fotoğraf yine de saklanır
        }
    }
}
