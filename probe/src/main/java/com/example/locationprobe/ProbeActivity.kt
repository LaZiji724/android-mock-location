package com.example.locationprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Different application ID and UID from the injector; reads the framework GPS provider. */
class ProbeActivity : Activity() {
    private lateinit var output: TextView
    private val manager by lazy { getSystemService(LocationManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 18f; text = "等待读取 GPS" }
        val button = Button(this).apply { text = "读取系统 GPS"; setOnClickListener { read() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            addView(button)
            addView(output)
        })
    }
    private fun read() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        output.text = "正在读取 GPS…"
        try {
            manager.getCurrentLocation(LocationManager.GPS_PROVIDER, CancellationSignal(), mainExecutor) { fix ->
                output.text = if (fix == null) "GPS 暂无定位，请检查系统定位开关并重试"
                else String.format(Locale.US, "GPS: %.6f, %.6f\n时间: %d\n系统标记为模拟: %s\n精度: %.1f m",
                    fix.latitude, fix.longitude, fix.time, fix.isMock, fix.accuracy)
            }
        } catch (e: Exception) { output.text = "读取失败：${e.message}" }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) read()
        else output.text = "需要精确位置权限才能独立读取 GPS"
    }
}
