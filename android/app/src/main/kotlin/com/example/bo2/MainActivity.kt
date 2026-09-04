package com.example.bo2

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.example.bo2/overlay"
    private val requestCaptureCode = 8791

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "isOverlayPermissionGranted" -> result.success(Settings.canDrawOverlays(this))
                "requestOverlayPermission" -> {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    result.success(null)
                }
                "startOverlay" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        result.error("PERMISSION_DENIED", "Concede el permiso de superposición en Ajustes.", null)
                    } else {
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 42)
                        }
                        // Pedir consentimiento de captura de pantalla (MediaProjection).
                        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        startActivityForResult(mpm.createScreenCaptureIntent(), requestCaptureCode)
                        result.success(null)
                    }
                }
                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(null)
                }
                "getConfig" -> {
                    val prefs = getSharedPreferences("bo2_config", MODE_PRIVATE)
                    result.success(
                        mapOf(
                            "rateMin" to prefs.getFloat("rate_min", 1100f),
                            "rateMax" to prefs.getFloat("rate_max", 1300f)
                        )
                    )
                }
                "saveConfig" -> {
                    val min = (call.argument<Double>("rateMin") ?: 1100.0).toFloat()
                    val max = (call.argument<Double>("rateMax") ?: 1300.0).toFloat()
                    getSharedPreferences("bo2_config", MODE_PRIVATE).edit()
                        .putFloat("rate_min", min)
                        .putFloat("rate_max", max)
                        .apply()
                    result.success(null)
                }
                "resetConfig" -> {
                    getSharedPreferences("bo2_config", MODE_PRIVATE).edit()
                        .putFloat("rate_min", 1100f)
                        .putFloat("rate_max", 1300f)
                        .apply()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCaptureCode) {
            val serviceIntent = Intent(this, OverlayService::class.java)
            if (resultCode == RESULT_OK && data != null) {
                // El usuario autorizó capturar la pantalla.
                serviceIntent.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                serviceIntent.putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            } else {
                // Sin captura: solo el overlay.
                serviceIntent.putExtra(OverlayService.EXTRA_RESULT_CODE, RESULT_CANCELED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // Minimizar la app para que la captura vea la pantalla de fondo.
            moveTaskToBack(true)
        }
    }
}