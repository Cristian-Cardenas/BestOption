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
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
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
        private const val MAX_DETECT_WIDTH = 720     // ancho al que se escala antes del OCR (más alto = lee letra pequeña)
        private const val THUMB_WIDTH = 64           // ancho del thumbnail para comparar cambios
        private const val SCREEN_CHANGE_THRESHOLD = 0.005f // fracción de píxeles cambiados para disparar OCR
        private const val PIXEL_DIFF_THRESHOLD = 20  // delta de luminancia por canal (0..255)
        private const val FORCE_OCR_EVERY = 4        // aunque no cambie, reparar OCR cada N ticks
        private const val SHOW_AFTER_FRAMES = 1      // frames seguidos para confirmar el valor
        private const val HIDE_AFTER_FRAMES = 1      // evaluaciones sin precio para ocultar el overlay
        private const val MASK_LEFT = 0.0f            // cubrir TODO el ancho del overlay (para no leerse a sí mismo)
        private const val MASK_RIGHT = 1.0f
        private const val MASK_TOP = 0.06f
        private const val MASK_BOTTOM = 0.30f
        private val AMOUNT_REGEX = Regex("""\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?""")
        private val KM_VALUE_REGEX = Regex("""(?i)([0-9loIO]+(?:[.,][0-9loIO]+)?)\s*km""")
        private val METERS_VALUE_REGEX = Regex("""(?i)([0-9loIO]+(?:[.,][0-9loIO]+)?)\s*m\b""")
        private val MIN_VALUE_REGEX = Regex("""(?i)([0-9loIO]{1,3})\s*min""")
        private const val RATE_NORMAL_MIN = 1300.0
        private const val RATE_NORMAL_MAX = 1500.0
        private const val PROX_LIMIT = 2.0  // primer km: <= límite = Cerca, > límite = Lejos
        private const val INVALID_HIDE_AFTER_FRAMES = 2 // lecturas sin ruta antes de ocultar (anti-parpadeo)
        private const val MAX_ADDRESS_GAP = 170           // píxeles (ancho OCR 720) entre tramo y dirección
        private val COMUNA_HINT = Regex("""(?i)\bcomuna\s*(\d{1,2})""")

        // Rangos amplios para descartar números que no son tarifas de viaje.
        private const val MIN_FARE = 1000.0
        private const val MAX_FARE = 1000000.0
        private const val MIN_TRIP_KM = 0.2
        private const val MAX_TRIP_KM = 200.0
        private const val MIN_TRIP_MIN = 1.0
        private const val MAX_TRIP_MIN = 240.0
        private const val MIN_RATE_PER_KM = 100.0
        private const val MAX_RATE_PER_KM = 20000.0
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayBgView: android.view.View? = null
    private var overlayBgParams: WindowManager.LayoutParams? = null
    private var overlayVisible = false
    private var proxValue: TextView? = null
    private var proxBox: LinearLayout? = null
    private var rateValue: TextView? = null
    private var rateBox: LinearLayout? = null
    private var stopsValue: TextView? = null
    private var stopsBox: LinearLayout? = null
    private var inicioTitle: TextView? = null
    private var inicioValue: TextView? = null
    private var destinoTitle: TextView? = null
    private var destinoValue: TextView? = null
    private lateinit var barrioIndex: BarrioIndex
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
        barrioIndex = BarrioIndex(this)
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

        val density = resources.displayMetrics.density
        val cardW = minOf(
            (360f * density).toInt(),
            resources.displayMetrics.widthPixels - (32f * density).toInt()
        )
        val labelColor = Color.rgb(145, 155, 178)
        val neutralColor = Color.rgb(139, 149, 168)
        val pillColor = Color.argb(22, 255, 255, 255)
        val dividerColor = Color.argb(30, 255, 255, 255)

        fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (14f * density).toInt(), (12f * density).toInt(),
                (14f * density).toInt(), (11f * density).toInt()
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 11, 18), Color.rgb(0, 0, 0))
            ).apply {
                cornerRadius = 18f * density
                setStroke((1f * density).toInt(), Color.argb(70, 255, 255, 255))
            }
            elevation = 8f * density
        }

        fun stat(label: String): LinearLayoutDetail {
            val col = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((2f * density).toInt(), 0, (2f * density).toInt(), 0)
            }
            val tl = TextView(this@OverlayService).apply {
                text = label
                textSize = 9f
                setTextColor(labelColor)
                gravity = Gravity.CENTER
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                letterSpacing = 0.08f
                maxLines = 1
            }
            col.addView(tl)
            val tv = TextView(this@OverlayService).apply {
                text = "—"
                textSize = 15f
                setTextColor(neutralColor)
                gravity = Gravity.CENTER
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(
                    (7f * density).toInt(), (2f * density).toInt(),
                    (7f * density).toInt(), (2f * density).toInt()
                )
            }
            col.addView(tv)
            return LinearLayoutDetail(col, tv)
        }

        fun directionBlock(label: String): DirectionView {
            val col = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(pillColor, 12f)
                setPadding(
                    (12f * density).toInt(), (8f * density).toInt(),
                    (12f * density).toInt(), (8f * density).toInt()
                )
            }
            val tl = TextView(this@OverlayService).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.START
                setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                letterSpacing = 0.05f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            col.addView(tl)
            val tv = TextView(this@OverlayService).apply {
                text = "—"
                textSize = 19f
                setTextColor(Color.WHITE)
                gravity = Gravity.START
                setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 0.95f)
            }
            col.addView(tv)
            return DirectionView(col, tl, tv)
        }

        // Inicio arriba, Destino abajo. Texto grande y siempre blanco.
        val inicioD = directionBlock("Inicio")
        inicioTitle = inicioD.title
        inicioValue = inicioD.value
        val destinoD = directionBlock("Destino")
        destinoTitle = destinoD.title
        destinoValue = destinoD.value

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        header.addView(inicioD.box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        header.addView(destinoD.box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (6f * density).toInt()
        })

        val divider = android.view.View(this).apply { setBackgroundColor(dividerColor) }
        val dividerLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1f * density).toInt()
        ).apply {
            topMargin = (10f * density).toInt()
            bottomMargin = (8f * density).toInt()
        }

        val proxD = stat("DISTANCIA")
        proxBox = proxD.t1
        proxValue = proxD.t2
        val rateD = stat("COP/KM")
        rateValue = rateD.t2
        rateBox = rateD.t1
        val stopsD = stat("PARADAS")
        stopsValue = stopsD.t2
        stopsBox = stopsD.t1
        stopsBox?.visibility = android.view.View.GONE

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        stats.addView(proxD.t1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(rateD.t1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(stopsD.t1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(divider, dividerLp)
        root.addView(stats, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        overlayView = root

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        overlayParams = WindowManager.LayoutParams(
            cardW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER or Gravity.TOP
            y = (64f * density).toInt()
            alpha = 1.0f
        }

        // El sistema limita las ventanas overlay a alpha 0.8. Una segunda ventana
        // negra detrás sube la opacidad efectiva a ~0.96 y la tarjeta se ve sólida.
        val bg = android.view.View(this).apply {
            background = rounded(Color.BLACK, 18f)
        }
        val bgParams = WindowManager.LayoutParams(
            cardW,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER or Gravity.TOP
            y = (64f * density).toInt()
            alpha = 1.0f
        }
        overlayBgView = bg
        overlayBgParams = bgParams

        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom > 0 && bottom != oldBottom) {
                val bp = overlayBgParams ?: return@addOnLayoutChangeListener
                if (bp.height != bottom) {
                    bp.height = bottom
                    val bgView = overlayBgView
                    if (bgView != null) {
                        runCatching { windowManager.updateViewLayout(bgView, bp) }
                    }
                }
            }
        }
    }

    private data class LinearLayoutDetail(val t1: LinearLayout, val t2: TextView)

    private data class DirectionView(val box: LinearLayout, val title: TextView, val value: TextView)

    private fun setOverlayVisible(visible: Boolean) {
        if (visible == overlayVisible) return
        Log.i(TAG, "overlay -> ${if (visible) "SHOW" else "HIDE"}")
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val bg = overlayBgView
        val bgParams = overlayBgParams
        if (visible) {
            try {
                if (bg != null && bgParams != null) {
                    windowManager.addView(bg, bgParams)
                }
                windowManager.addView(view, params)
                overlayVisible = true
            } catch (_: Exception) {
                if (bg != null) runCatching { windowManager.removeView(bg) }
            }
        } else {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
            if (bg != null) runCatching { windowManager.removeView(bg) }
            overlayVisible = false
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

    // Limpia errores comunes de OCR en números (l/I -> 1, O/o -> 0).
    private fun ocrNumber(s: String): String =
        s.replace('l', '1').replace('I', '1').replace('O', '0').replace('o', '0')

    // Distancia de un texto en km ("3 km", "10,9 km", "1.1 km", "661 m").
    private fun distanceKm(text: String): Double? {
        val kmM = KM_VALUE_REGEX.find(text)
        if (kmM != null) {
            return ocrNumber(kmM.groupValues[1]).replace(",", ".").toDoubleOrNull()
        }
        val mM = METERS_VALUE_REGEX.find(text) ?: return null
        val meters = ocrNumber(mM.groupValues[1]).replace(",", ".").toDoubleOrNull() ?: return null
        return meters / 1000.0
    }

    // Extrae los tramos "N min (D km)" de la oferta. Tolera distancias en metros,
    // que el OCR separe la distancia en otra línea y ofertas con paradas.
    private fun extractTrip(result: com.google.mlkit.vision.text.Text): Trip? {
        val legs = mutableListOf<Leg>() // y, km, min
        val allLines = mutableListOf<LineInfo>()
        var hasPixelTops = true
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text
                val box = line.boundingBox
                if (box != null) allLines.add(LineInfo(box.top, text))
                if (!text.lowercase().contains("min")) continue
                val minM = MIN_VALUE_REGEX.find(text) ?: continue
                val minutes = ocrNumber(minM.groupValues[1]).toDoubleOrNull() ?: continue
                val km = distanceKm(text) ?: continue
                Log.d(TAG, "tripLine: '${text}' -> ${km} km, ${minutes} min")
                legs.add(Leg(box?.top ?: 0, km, minutes))
            }
        }

        // Fallback: el OCR a veces parte "8 min" y "(3 km)" en líneas distintas.
        if (legs.size < 2) {
            val fallback = mutableListOf<Leg>()
            val joined = result.text
            for (m in MIN_VALUE_REGEX.findAll(joined)) {
                val minutes = ocrNumber(m.groupValues[1]).toDoubleOrNull() ?: continue
                val tail = joined.substring(m.range.last + 1).take(24)
                val km = distanceKm(tail) ?: continue
                fallback.add(Leg(m.range.first, km, minutes))
            }
            if (fallback.size > legs.size) {
                legs.clear()
                legs.addAll(fallback)
                hasPixelTops = false
                Log.d(TAG, "tripFallback: ${legs.size} tramo(s)")
            }
        }

        if (legs.isEmpty()) return null

        // Se conservan como máximo los dos tramos más bajos en pantalla (recogida y
        // viaje); en ofertas con parada siguen siendo esos dos.
        val chosen = legs.distinctBy { "${it.km}-${it.min}" }
            .sortedByDescending { it.top }
            .take(2)
        val kmSum = chosen.sumOf { it.km }
        val minSum = chosen.sumOf { it.min }
        // COP/km se calcula con el trayecto, no con la distancia de recogida.
        val tripKm = if (chosen.size >= 2) chosen.maxByOrNull { it.top }?.km else null
        // La recogida es el tramo más alto en pantalla. Con un solo tramo no es
        // posible saber si es la recogida o el viaje: se deja sin proximidad.
        val firstKm = if (chosen.size >= 2) chosen.minByOrNull { it.top }?.km else null
        // Coordenada del inicio de la ruta (solo si viene de líneas reales del OCR).
        val routeTop = if (hasPixelTops) chosen.minOfOrNull { it.top } else null

        // Direcciones: en la tarjeta de oferta van justo debajo de cada tramo.
        val usablePixels = hasPixelTops && chosen.all { it.top > 0 }
        val pickupLeg = if (chosen.size >= 2) chosen.minByOrNull { it.top } else null
        val destLeg = chosen.maxByOrNull { it.top }
        val pickupAddress = if (usablePixels && pickupLeg != null) {
            findAddressBelow(pickupLeg.top, destLeg?.top ?: Int.MAX_VALUE, allLines)
        } else null
        val destAddress = if (usablePixels && destLeg != null) {
            findAddressBelow(destLeg.top, Int.MAX_VALUE, allLines)
        } else null
        if (pickupAddress == null && pickupLeg != null && destLeg != null) {
            val nearby = allLines
                .filter { it.top > pickupLeg.top && it.top < destLeg.top }
                .sortedBy { it.top }
                .joinToString(" || ") { "${it.top}:${it.text.take(28)}" }
            Log.d(TAG, "sin dirección recogida (${pickupLeg.top}-${destLeg.top}): $nearby")
        }
        return Trip(kmSum, tripKm, minSum, firstKm, routeTop, pickupAddress, destAddress)
    }

    private fun findAddressBelow(legTop: Int, beforeTop: Int, lines: List<LineInfo>): String? {
        var best: LineInfo? = null
        for (l in lines) {
            if (l.top <= legTop + 4 || l.top >= beforeTop) continue
            if (l.top - legTop > MAX_ADDRESS_GAP) continue
            if (!barrioIndex.looksLikeAddress(l.text)) continue
            if (best == null || l.top < best.top) best = l
        }
        return best?.text?.trim()
    }

    private data class Leg(val top: Int, val km: Double, val min: Double)

    private data class LineInfo(val top: Int, val text: String)

    private data class Trip(
        val kmSum: Double,
        val tripKm: Double?,
        val minSum: Double,
        val firstKm: Double?,
        val routeTop: Int?,
        val pickupAddress: String?,
        val destAddress: String?
    )

    private fun proxLimit(): Double = defaultPrefs().getFloat("prox", PROX_LIMIT.toFloat()).toDouble()

    private fun classifyProximity(firstKm: Double): String =
        if (firstKm <= proxLimit()) "Cerca" else "Lejos"

    private fun proximityColor(prox: String): Int = when (prox) {
        "Cerca" -> Color.rgb(76, 217, 100)
        "Regular" -> Color.rgb(255, 195, 0)
        else -> Color.rgb(255, 69, 58)
    }

    private fun classify(rate: Double): String =
        RateCalculator.classify(rate, normalMin(), normalMax())

    // Número de paradas/destinos SOLO si hay un número junto a "parada" o "destino".
    private fun extractStops(result: com.google.mlkit.vision.text.Text): Pair<Int, String>? {
        val numRe = Regex("""\d{1,2}""")
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val low = line.text.lowercase()
                val noun = when {
                    low.contains("destin") -> "destino"
                    low.contains("parad") -> "parada"
                    else -> continue // "1destino", "2 paradas", "Destinos", etc.
                }
                val m = numRe.find(ocrNumber(line.text)) ?: continue // "l destino"→"1 destino", "2 paradas", etc.
                Log.d(TAG, "STOPS: '${line.text}' -> ${m.value} ($noun)")
                return (m.value.toInt().coerceAtLeast(1)) to noun
            }
        }
        Log.d(TAG, "STOPS: sin paradas/destinos numerados")
        return null
    }

    private fun defaultPrefs(): SharedPreferences = getSharedPreferences("bo2_config", 0)

    private fun normalMin(): Double = defaultPrefs().getFloat("rate_min", RATE_NORMAL_MIN.toFloat()).toDouble()

    private fun normalMax(): Double = defaultPrefs().getFloat("rate_max", RATE_NORMAL_MAX.toFloat()).toDouble()

    // Una oferta real debe tener ruta, valores creíbles y la ruta debajo del precio.
    // Así un saldo o un precio suelto en pantalla no activa los cuadros.
    private fun isValidOffer(price: Double, trip: Trip, priceLine: Text.Line?): Boolean {
        if (price !in MIN_FARE..MAX_FARE) return false
        if (trip.kmSum !in MIN_TRIP_KM..MAX_TRIP_KM) return false
        if (trip.minSum !in MIN_TRIP_MIN..MAX_TRIP_MIN) return false
        val tripKm = trip.tripKm ?: return false
        if (tripKm !in MIN_TRIP_KM..MAX_TRIP_KM) return false
        val perKm = RateCalculator.perKm(price, tripKm) ?: return false
        if (perKm !in MIN_RATE_PER_KM..MAX_RATE_PER_KM) return false
        // En las tarjetas de oferta el detalle de la ruta está debajo del precio.
        val priceBottom = priceLine?.boundingBox?.bottom
        val routeTop = trip.routeTop
        if (priceBottom != null && routeTop != null && routeTop < priceBottom) return false
        return true
    }

    private fun verdictColor(verdict: String): Int = when (verdict) {
        "BUENO" -> Color.rgb(76, 217, 100)
        "REGULAR" -> Color.rgb(255, 195, 0)
        else -> Color.rgb(255, 69, 58)
    }

    // Pinta una métrica. Si falta el dato muestra "—" en gris y sin chip de color.
    private fun renderMetric(value: TextView?, text: String?, color: Int?) {
        value?.text = text ?: "—"
        if (color != null) {
            value?.setTextColor(color)
            value?.background = metricChip(color)
        } else {
            value?.setTextColor(Color.rgb(139, 149, 168))
            value?.background = null
        }
    }

    private fun metricChip(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 7f * resources.displayMetrics.density
        setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun renderAddress(
        title: TextView?,
        value: TextView?,
        label: String,
        entry: BarrioIndex.Entry?,
        fallback: String?
    ) {
        val comuna = entry?.comuna?.toIntOrNull()?.toString()
            ?: fallback?.let { COMUNA_HINT.find(it)?.groupValues?.get(1)?.toIntOrNull()?.toString() }
        title?.text = if (comuna != null) "$label · C$comuna" else label
        val text = entry?.barrio ?: fallback?.replace("\n", " ")?.trim() ?: "—"
        value?.text = text
        value?.setTextColor(Color.WHITE)
    }

    // Alerta (solo pitido) cuando una tarifa es BUENO.
    private fun alertGood() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2)
            mainHandler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 250)
        } catch (_: Exception) {}
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
        val trip = extractTrip(result)
        val kmSum = trip?.kmSum
        val minSum = trip?.minSum
        val firstKm = trip?.firstKm
        val stopsInfo = extractStops(result)
        val isOffer = price != null && trip != null && isValidOffer(price, trip, bestLine)

        val now = System.currentTimeMillis()
        if (now - lastLog > 2000) {
            lastLog = now
            Log.i(TAG, "lines=$totalLines amt='$amountStr' price=$price km=$kmSum min=$minSum stops=${stopsInfo?.first} offer=$isOffer overlay=$overlayVisible")
            if (price != null && !isOffer) {
                Log.d(
                    TAG,
                    "oferta descartada amt=$amountStr km=$kmSum min=$minSum routeTop=${trip?.routeTop} priceBottom=${bestLine?.boundingBox?.bottom}"
                )
            }
        }

        if (price == null || amountStr == null) {
            // Sin precio: contar frames para ocultar el overlay.
            missFrames++
            pendingFrames = 0
            pendingPrice = null
            if (missFrames >= HIDE_AFTER_FRAMES) {
                shownPrice = null
                setOverlayVisible(false)
            }
            return
        }

        if (!isOffer) {
            // Hay un monto, pero no es una oferta de viaje (sin ruta creíble).
            // Si es el mismo monto ya mostrado se tolera una lectura sin ruta para
            // no parpadear; si es otro monto, se oculta de inmediato.
            missFrames++
            pendingFrames = 0
            pendingPrice = null
            val sameAmount = shownPrice == amountStr
            if (!sameAmount || missFrames >= INVALID_HIDE_AFTER_FRAMES) {
                shownPrice = null
                setOverlayVisible(false)
            }
            return
        }

        missFrames = 0
        // Las paradas se refrescan siempre que sea una oferta válida.
        val hasStops = stopsInfo != null
        stopsBox?.visibility = if (hasStops) android.view.View.VISIBLE else android.view.View.GONE
        if (hasStops) {
            renderMetric(stopsValue, stopsInfo!!.first.toString(), Color.rgb(255, 99, 99))
        } else {
            stopsValue?.text = ""
            stopsValue?.background = null
        }

        // Estabilidad: se muestra solo si aparece el mismo precio N frames seguidos.
        if (pendingPrice == amountStr) {
            pendingFrames++
        } else {
            pendingPrice = amountStr
            pendingFrames = 1
        }

        val rate = trip?.tripKm?.let { RateCalculator.perKm(price, it) }
        val verdict = rate?.let { classify(it) }

        if (pendingFrames >= SHOW_AFTER_FRAMES) {
            val pickupAddress = trip?.pickupAddress
            val destAddress = trip?.destAddress
            val pickupEntry = pickupAddress?.let { barrioIndex.resolve(it) }
            val destEntry = destAddress?.let { barrioIndex.resolve(it) }

            setOverlayVisible(true)
            renderAddress(inicioTitle, inicioValue, "Inicio", pickupEntry, pickupAddress)
            renderAddress(destinoTitle, destinoValue, "Destino", destEntry, destAddress)

            val prox = firstKm?.let { classifyProximity(it) }
            val rateText = rate?.let { "%.0f".format(it) }
            renderMetric(proxValue, prox, prox?.let { proximityColor(it) })
            renderMetric(rateValue, rateText, verdict?.let { verdictColor(it) })

            if (shownPrice != amountStr) {
                shownPrice = amountStr
                Log.i(
                    TAG,
                    "RESULT price=$price km=$kmSum tripKm=${trip?.tripKm} min=$minSum prox=$prox rate=$rateText($verdict) stops=${stopsInfo?.first}"
                )
                Log.i(
                    TAG,
                    "ADDR inicio='$pickupAddress' -> ${pickupEntry?.barrio} (C${pickupEntry?.comuna}); destino='$destAddress' -> ${destEntry?.barrio} (C${destEntry?.comuna})"
                )
                if (verdict == "BUENO") alertGood()
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
        overlayBgView = null
        overlayBgParams = null
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