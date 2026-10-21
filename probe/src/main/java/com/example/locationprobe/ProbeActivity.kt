package com.example.locationprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Different application ID and UID from the injector; reads the framework GPS and network providers. */
class ProbeActivity : Activity() {
    private lateinit var output: TextView
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var pending = LocationManager.GPS_PROVIDER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 16f; text = "等待读取定位" }
        val gps = Button(this).apply { text = "读取系统 GPS"; setOnClickListener { read(LocationManager.GPS_PROVIDER) } }
        val network = Button(this).apply { text = "读取网络定位"; setOnClickListener { read(LocationManager.NETWORK_PROVIDER) } }
        val state = Button(this).apply { text = "只看系统定位状态"; setOnClickListener { output.text = status() } }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@ProbeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 36, 24, 24)
                addView(gps); addView(network); addView(state); addView(output)
            })
        })
    }

    /** Provider switches as this app sees them; shown first because a disabled provider explains a null fix. */
    private fun status(): String {
        fun enabled(provider: String) = try { manager.isProviderEnabled(provider).toString() } catch (e: Exception) { "异常 ${e.javaClass.simpleName}" }
        val mode = try { Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) } catch (_: Exception) { -1 }
        val providers = try { manager.allProviders.joinToString() } catch (_: Exception) { "?" }
        return "系统位置总开关: ${manager.isLocationEnabled}\n位置模式(LOCATION_MODE): $mode\n" +
            "GPS 提供者开启: ${enabled(LocationManager.GPS_PROVIDER)}\n网络提供者开启: ${enabled(LocationManager.NETWORK_PROVIDER)}\n" +
            "全部提供者: $providers\n系统: ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\n"
    }

    @Suppress("MissingPermission")
    private fun read(provider: String) {
        pending = provider
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        stopListening()
        val label = if (provider == LocationManager.GPS_PROVIDER) "GPS" else "网络定位"
        val header = status()
        // Do not stop at a disabled provider: report what the system says, then still try to read.
        val last = try { manager.getLastKnownLocation(provider) } catch (e: Exception) { null }
        output.text = header + "\n最近一次已知位置: ${describe(last)}\n正在监听 $label 10 秒…"
        val fixes = StringBuilder()
        val callback = LocationListener { fix ->
            fixes.setLength(0)
            fixes.append("收到实时位置: ${describe(fix)}")
            output.text = header + "\n最近一次已知位置: ${describe(last)}\n$fixes"
        }
        listener = callback
        try {
            manager.requestLocationUpdates(provider, 1000L, 0f, callback, Looper.getMainLooper())
        } catch (e: Exception) { output.text = header + "\n监听失败：${e.javaClass.simpleName} ${e.message}"; listener = null; return }
        handler.postDelayed({
            if (listener === callback) {
                stopListening()
                if (fixes.isEmpty()) output.text = header + "\n最近一次已知位置: ${describe(last)}\n10 秒内没有收到 $label 位置"
            }
        }, 10_000)
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun describe(fix: Location?): String =
        if (fix == null) "无"
        else String.format(Locale.US, "%.6f, %.6f\n  时间 %d  精度 %.1f m\n  系统标记为模拟: %s",
            fix.latitude, fix.longitude, fix.time, fix.accuracy, if (Build.VERSION.SDK_INT >= 31) fix.isMock else fix.isFromMockProvider)

    override fun onPause() { stopListening(); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) read(pending)
        else output.text = "需要精确位置权限才能独立读取定位"
    }
}
