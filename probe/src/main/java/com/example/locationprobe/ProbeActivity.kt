package com.example.locationprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import java.util.Locale

/** Different application ID and UID from the injector; reads the framework GPS and network providers. */
class ProbeActivity : Activity() {
    private lateinit var output: TextView
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private var pending = LocationManager.GPS_PROVIDER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 18f; text = "等待读取定位" }
        val gps = Button(this).apply { text = "读取系统 GPS"; setOnClickListener { read(LocationManager.GPS_PROVIDER) } }
        val network = Button(this).apply { text = "读取网络定位"; setOnClickListener { read(LocationManager.NETWORK_PROVIDER) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            addView(gps)
            addView(network)
            addView(output)
        })
    }
    private fun read(provider: String) {
        pending = provider
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        val label = if (provider == LocationManager.GPS_PROVIDER) "GPS" else "网络定位"
        if (!manager.isProviderEnabled(provider)) { output.text = "$label 不可用，请检查系统位置信息开关"; return }
        output.text = "正在读取 $label…"
        try {
            // Compat path: getCurrentLocation is API 30+, HarmonyOS 2/3 phones run an Android 10 base.
            LocationManagerCompat.getCurrentLocation(manager, provider, CancellationSignal(), mainExecutor) { fix ->
                output.text = if (fix == null) "$label 暂无定位，请检查系统定位开关并重试"
                else String.format(Locale.US, "%s: %.6f, %.6f\n时间: %d\n系统标记为模拟: %s\n精度: %.1f m",
                    label, fix.latitude, fix.longitude, fix.time, isMock(fix), fix.accuracy)
            }
        } catch (e: Exception) { output.text = "读取失败：${e.message}" }
    }
    @Suppress("DEPRECATION")
    private fun isMock(fix: Location) = if (Build.VERSION.SDK_INT >= 31) fix.isMock else fix.isFromMockProvider
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) read(pending)
        else output.text = "需要精确位置权限才能独立读取定位"
    }
}
