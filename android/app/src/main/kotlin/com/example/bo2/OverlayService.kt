package com.example.bo2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    companion object {
        private const val TAG = "BO2Price"
        const val EXTRA_RESULT_CODE = "media_projection_result_code"
        const val EXTRA_RESULT_DATA = "media_projection_result_data"
        const val ACTION_STOP = "com.example.bo2.ACTION_STOP"
        private const val FRAME_INTERVAL_MS = 1500L  // barrido de captura
        private const val MAX_DETECT_WIDTH = 480     // ancho al que se escala antes del OCR (menor = menos CPU)
        private const val THUMB_WIDTH = 64           // ancho del thumbnail para comparar cambios
        private const val SCREEN_CHANGE_THRESHOLD = 0.005f // fracción de píxeles cambiados para disparar OCR
        private const val PIXEL_DIFF_THRESHOLD = 20  // delta de luminancia por canal (0..255)
        private const val FORCE_OCR_EVERY = 4        // aunque no cambie, reparar OCR cada N ticks
        private const val SHOW_AFTER_FRAMES = 1      // frames seguidos para confirmar el valor
        private const val HIDE_AFTER_FRAMES = 1      // evaluaciones sin precio para ocultar el overlay
        private const val MASK_LEFT = 0.20f           // región del overlay (para no leerlo a sí mismo)
        private const val MASK_RIGHT = 0.80f
        private const val MASK_TOP = 0.06f
        private const val MASK_BOTTOM = 0.22f
        private val AMOUNT_REGEX = Regex("""\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?""")
        private val KM_VALUE_REGEX = Regex("""(?i)([0-9]+(?:[.,][0-9]+)?)\s*km""")
        private const val RATE_NORMAL_MIN = 1100.0
        private const val RATE_NORMAL_MAX = 1300.0
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayVisible = false
    private var priceTextView: TextView? = null
    private var rateTextView: TextView? = null
    private var missFrames = 0
    private val channelId = "overlay_service"
    private val notificationId = 1001

    // Captura de pantalla
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var detectionThread: HandlerThread? = null
    private var detectionHandler: Handler? = null
    private val processing = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var pendingPrice: String? = null
    private var pendingFrames = 0
    private var shownPrice: String? = null
    private var lastLog = 0L
    private var prevThumb: Bitmap? = null
    private var tick = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("BO2 Overlay activo")
            .setContentText("Leyendo el precio en pantalla")
            .setSmallIcon(com.example.bo2.R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(notificationId, notification)
        }
        buildOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay BO2", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            startCapture(resultCode, resultData)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // Overlay (solo se muestra cuando hay un precio detectado)
    // ------------------------------------------------------------------
    private fun buildOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 18, 36, 18)
            setBackgroundColor(Color.argb(225, 18, 30, 58))
        }
        priceTextView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        rateTextView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(priceTextView)
        root.addView(rateTextView)
        overlayView = root

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER or Gravity.TOP; y = 160 }
    }

    private fun setOverlayVisible(visible: Boolean) {
        if (visible == overlayVisible) return
        Log.i(TAG, "overlay -> ${if (visible) "SHOW" else "HIDE"}")
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (visible) {
            try {
                windowManager.addView(view, params)
                overlayVisible = true
            } catch (_: Exception) {
            }
        } else {
            try {
                windowManager.removeView(view)
                overlayVisible = false
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // MediaProjection + OCR
    // ------------------------------------------------------------------
    private fun startCapture(resultCode: Int, resultData: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        val projection = mediaProjection ?: return
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCaptureResources()
            }
        }, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "BO2PriceReader",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        detectionThread = HandlerThread("bo2-price-detector").also { it.start() }
        detectionHandler = Handler(detectionThread!!.looper)
        scheduleDetection()
    }

    private fun scheduleDetection() {
        detectionHandler?.postDelayed({ doDetectionTick() }, FRAME_INTERVAL_MS)
    }

    private fun doDetectionTick() {
        if (!processing.compareAndSet(false, true)) {
            scheduleDetection()
            return
        }
        val full = grabBitmap()
        if (full == null) {
            processing.set(false)
            scheduleDetection()
            return
        }
        val detect = scaleDown(full, MAX_DETECT_WIDTH)
        full.recycle()
        maskOverlayRegion(detect)

        // Diff-check: comparar thumbnail con el anterior; OCR solo si la pantalla cambió
        // (o de forma periódica forzada para no depender solo del cambio).
        val thumb = thumbnail(detect)
        val force = (++tick % FORCE_OCR_EVERY == 0)
        val changed = frameChanged(thumb)
        prevThumb?.recycle()
        prevThumb = thumb
        Log.d(TAG, "tick=$tick changed=$changed force=$force")

        if (!changed && !force) {
            detect.recycle()
            processing.set(false)
            scheduleDetection()
            return
        }

        recognizer.process(InputImage.fromBitmap(detect, 0))
            .addOnSuccessListener { result ->
                mainHandler.post { evaluate(result) }
                detect.recycle()
                processing.set(false)
                scheduleDetection()
            }
            .addOnFailureListener {
                detect.recycle()
                processing.set(false)
                scheduleDetection()
            }
    }

    private fun thumbnail(detect: Bitmap): Bitmap {
        val h = max(1, (detect.height.toFloat() * THUMB_WIDTH / detect.width).toInt())
        return Bitmap.createScaledBitmap(detect, THUMB_WIDTH, h, true)
    }

    private fun maskOverlayRegion(detect: Bitmap) {
        val w = detect.width
        val h = detect.height
        val left = (w * MASK_LEFT).toInt()
        val right = (w * MASK_RIGHT).toInt()
        val top = (h * MASK_TOP).toInt()
        val bottom = (h * MASK_BOTTOM).toInt()
        if (right <= left || bottom <= top) return
        // Cubrir con blanco (el OCR ignora esta región -> no se lee a sí mismo).
        val fill = IntArray((right - left) * (bottom - top)) { 0xFFFFFFFF.toInt() }
        detect.setPixels(fill, 0, right - left, left, top, right - left, bottom - top)
    }

    // Extrae un monto (ej: "16,408", "10.725", "2,278.00") de la línea, o null si no es un monto.
    private fun extractAmount(text: String): String? {
        val m = AMOUNT_REGEX.find(text) ?: return null
        val v = m.value
        // Evitar números sueltos tipo "25" de direcciones/etiquetas.
        return if (v.filter { it.isDigit() }.length >= 3) v else null
    }

    // Interpreta un monto como número, tratando un único separador como separador de miles
    // (COP: "16,408" y "10.725" = 16408 y 10725).
    private fun parseAmount(s: String): Double? {
        if (s.isEmpty()) return null
        val hasDot = s.contains('.')
        val hasComma = s.contains(',')
        var t = s
        when {
            hasDot && hasComma -> {
                t = if (s.lastIndexOf('.') > s.lastIndexOf(',')) s.replace(",", "") else s.replace(".", "")
            }
            hasDot -> t = s.replace(".", "")
            hasComma -> t = s.replace(",", "")
        }
        return t.toDoubleOrNull()
    }

    // Suma los dos valores de km de la parte inferior (líneas con "min" y "km").
    private fun extractKmSum(result: com.google.mlkit.vision.text.Text): Double? {
        val kmCandidates = result.textBlocks.flatMap { b -> b.lines.map { it.text } }
            .filter { it.lowercase().contains("km") }
        if (kmCandidates.isNotEmpty()) {
            Log.d(TAG, "kmCandidates: ${kmCandidates.joinToString(" | ")}")
        }
        val found = mutableListOf<Pair<Int, Double>>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val low = line.text.lowercase()
                if (!low.contains("min") || !low.contains("km")) continue
                val m = KM_VALUE_REGEX.find(line.text) ?: continue
                val km = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: continue
                found.add(Pair(line.boundingBox?.top ?: 0, km))
            }
        }
        if (found.size != 2) return null  // solo la pantalla de una tarifa (exactamente 2 km)
        found.sortByDescending { it.first }  // las dos más bajas en pantalla
        return found[0].second + found[1].second
    }

    private fun classify(rate: Double): String {
        val lo = normalMin()
        val hi = normalMax()
        return when {
            rate > hi -> "BUENO"
            rate < lo -> "MALO"
            else -> "NORMAL"
        }
    }

    private fun defaultPrefs(): SharedPreferences = getSharedPreferences("bo2_config", 0)

    private fun normalMin(): Double = defaultPrefs().getFloat("rate_min", RATE_NORMAL_MIN.toFloat()).toDouble()

    private fun normalMax(): Double = defaultPrefs().getFloat("rate_max", RATE_NORMAL_MAX.toFloat()).toDouble()

    private fun verdictColor(verdict: String): Int = when (verdict) {
        "BUENO" -> Color.rgb(76, 217, 100)
        "NORMAL" -> Color.rgb(255, 195, 0)
        else -> Color.rgb(255, 69, 58)
    }

    private fun frameChanged(thumb: Bitmap): Boolean {
        val prev = prevThumb
        if (prev == null || prev.width != thumb.width || prev.height != thumb.height) return true
        val w = thumb.width
        val h = thumb.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        thumb.getPixels(a, 0, w, 0, 0, w, h)
        prev.getPixels(b, 0, w, 0, 0, w, h)
        var changed = 0
        var i = 0
        while (i < a.size) {
            val pa = a[i]
            val pb = b[i]
            val mad = (
                abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                    abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                    abs((pa and 0xFF) - (pb and 0xFF))
                ) / 3
            if (mad > PIXEL_DIFF_THRESHOLD) changed++
            i++
        }
        val frac = changed.toFloat() / (w * h)
        Log.d(TAG, "diff=${"%.4f".format(frac)}")
        return frac > SCREEN_CHANGE_THRESHOLD
    }

    private fun grabBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        } finally {
            image.close()
        }
    }

    private fun scaleDown(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val ratio = targetWidth.toFloat() / src.width
        val w = targetWidth
        val h = (src.height * ratio).toInt()
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun evaluate(result: com.google.mlkit.vision.text.Text) {
        // Buscar la linea con MAYOR altura que tenga formato de monto (precio grande).
        var bestLine: Text.Line? = null
        var bestHeight = 0f
        var totalLines = 0
        for (block in result.textBlocks) {
            for (line in block.lines) {
                totalLines++
                val text = line.text
                if (extractAmount(text) == null) continue
                val h = line.boundingBox?.height()?.toFloat() ?: 0f
                if (h > bestHeight) {
                    bestHeight = h
                    bestLine = line
                }
            }
        }

        val amountStr = bestLine?.text?.let { extractAmount(it) }
        val price = amountStr?.let { parseAmount(it) }
        val kmSum = extractKmSum(result)

        val now = System.currentTimeMillis()
        if (now - lastLog > 2000) {
            lastLog = now
            Log.i(TAG, "lines=$totalLines amt='$amountStr' price=$price kmSum=$kmSum overlay=$overlayVisible")
        }

        if (price == null || kmSum == null || amountStr == null) {
            // Sin precio o sin los dos km: contar frames para ocultar el overlay.
            missFrames++
            pendingFrames = 0
            pendingPrice = null
            if (missFrames >= HIDE_AFTER_FRAMES) {
                shownPrice = null
                setOverlayVisible(false)
            }
            return
        }

        val rate = price / kmSum
        val verdict = classify(rate)

        missFrames = 0
        // Estabilidad: se muestra solo si aparece el mismo precio N frames seguidos.
        if (pendingPrice == amountStr) {
            pendingFrames++
        } else {
            pendingPrice = amountStr
            pendingFrames = 1
        }
        if (pendingFrames >= SHOW_AFTER_FRAMES) {
            setOverlayVisible(true)
            if (shownPrice != amountStr) {
                shownPrice = amountStr
                priceTextView?.text = verdict
                priceTextView?.setTextColor(verdictColor(verdict))
                rateTextView?.text = "${"%.0f".format(rate)} COP/km · $amountStr"
                Log.i(TAG, "RESULT price=$price kmSum=${"%.1f".format(kmSum)} rate=${"%.0f".format(rate)} -> $verdict")
            }
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------
    override fun onDestroy() {
        stopDetection()
        stopCaptureResources()
        prevThumb?.recycle()
        prevThumb = null
        setOverlayVisible(false)
        overlayView = null
        overlayParams = null
        recognizer.close()
        super.onDestroy()
    }

    private fun stopDetection() {
        detectionHandler?.removeCallbacksAndMessages(null)
        detectionThread?.quitSafely()
        detectionThread = null
        detectionHandler = null
    }

    private fun stopCaptureResources() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }
}