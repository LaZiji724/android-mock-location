package com.example.androidmocklocation

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SessionSnapshot(
    val mode: String = "IDLE",
    val target: Point? = null,
    val submitted: Point? = null,
    val submittedAt: Long = 0,
    val result: String = "尚未提交",
    val error: String = "",
    val serviceRunning: Boolean = false,
    val routeMetres: Double = 0.0,
    val routeRemaining: Double = 0.0,
    val canResumeRoute: Boolean = false,
    val historyRevision: Long = 0
)

object Session {
    private val mutable = MutableStateFlow(SessionSnapshot())
    val state = mutable.asStateFlow()
    fun update(block: (SessionSnapshot) -> SessionSnapshot) { mutable.value = block(mutable.value) }
    fun mockAllowed(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
    fun command(context: Context, action: String, point: Point? = null, speed: Double? = null, bearing: Double? = null, points: List<Point>? = null) {
        val intent = Intent(context, MockLocationService::class.java).setAction(action)
        point?.let { intent.putExtra("lat", it.lat).putExtra("lon", it.lon) }
        speed?.let { intent.putExtra("speed", it) }
        bearing?.let { intent.putExtra("bearing", it) }
        points?.let { intent.putExtra("route", it.flatMap { p -> listOf(p.lat, p.lon) }.toDoubleArray()) }
        if (action == MockLocationService.STOP) context.startService(intent)
        else context.startForegroundService(intent)
    }
}

class MockLocationService : Service() {
    companion object {
        const val FIX = "fix"
        const val ROUTE = "route"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val JOYSTICK = "joystick"
        const val STEER = "steer"
        const val RELEASE = "release"
        const val END_JOYSTICK = "end_joystick"
        const val SPEED = "speed"
        const val STOP = "stop"
        private const val CHANNEL = "mock_location_session"
        private const val NOTIFICATION = 18272
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: LocationManager
    private var ticker: Job? = null
    private var providerOwned = false
    private var current = Point(39.9042, 116.4074)
    private var route: RouteProgress? = null
    private var steeringBearing = 0.0
    private var steeringActive = false
    private var speed = 1.4
    private var heading = 0.0
    private var moving = false
    private var mode = "IDLE"

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "模拟定位运行状态", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run { stopSession(); return START_NOT_STICKY }
        if (action == STOP) { stopSession(); return START_NOT_STICKY }
        try {
            if (!Session.mockAllowed(this)) throw SecurityException("请先在开发者选项中选择本应用为模拟位置信息应用")
            showForeground()
            require(action in setOf(FIX, ROUTE, PAUSE, RESUME, JOYSTICK, STEER, RELEASE, END_JOYSTICK, SPEED)) { "未知定位指令" }
            // Late controls must not leave an empty foreground service after STOP.
            if (!providerOwned && action != FIX && action != ROUTE && action != JOYSTICK) {
                stopSession()
                return START_NOT_STICKY
            }
            if (action == FIX || action == ROUTE || action == JOYSTICK) Session.update { it.copy(error = "") }
            when (action) {
                FIX -> {
                    val point = readPoint(intent)
                    require(point.valid()) { "经纬度超出范围" }
                    prepareProvider()
                    current = point; route = null; mode = "FIXED"; moving = false
                    emit()
                    recordHistory("固定位置", point)
                }
                ROUTE -> {
                    val raw = intent.getDoubleArrayExtra("route") ?: doubleArrayOf()
                    require(raw.size % 2 == 0) { "路线坐标必须成对提供" }
                    val track = RouteTrack(raw.toList().chunked(2).filter { it.size == 2 }.map { Point(it[0], it[1]) })
                    speed = intent.getDoubleExtra("speed", speed).also { require(it in 0.1..100.0) }
                    prepareProvider()
                    route = RouteProgress(track, speed)
                    current = track.points.first(); mode = "ROUTE_RUNNING"; moving = true
                    heading = track.bearingAt(0.0); emit()
                    recordHistory("路线起点", current)
                }
                PAUSE -> {
                    if (mode == "ROUTE_RUNNING") { route?.paused = true; mode = "ROUTE_PAUSED" }
                    else if (mode == "FIXED") mode = "FIXED_PAUSED"
                    moving = false
                    publish()
                }
                RESUME -> {
                    if (mode == "ROUTE_PAUSED" || mode == "JOYSTICK") {
                        val progress = requireNotNull(route?.takeUnless { it.finished }) { "没有可继续的路线" }
                        steeringActive = false
                        progress.paused = false; mode = "ROUTE_RUNNING"; moving = true
                        current = progress.track.at(progress.travelled)
                        speed = progress.speedMetresPerSecond
                        heading = progress.track.bearingAt(progress.travelled)
                    } else if (mode == "FIXED_PAUSED") mode = "FIXED"
                    emit()
                }
                JOYSTICK -> {
                    prepareProvider()
                    if (route != null) route?.paused = true
                    if (mode == "IDLE") current = readPoint(intent)
                    require(current.valid())
                    speed = intent.getDoubleExtra("speed", speed).also { require(it in 0.1..100.0) }
                    steeringActive = false; moving = false; mode = "JOYSTICK"; emit()
                    recordHistory("摇杆起点", current)
                }
                STEER -> {
                    if (mode == "JOYSTICK") {
                        val bearing = intent.getDoubleExtra("bearing", 0.0)
                        require(bearing.isFinite()) { "方向必须为有限数值" }
                        val requestedSpeed = intent.getDoubleExtra("speed", speed)
                        require(requestedSpeed in 0.1..100.0) { "速度需在 0.1–100 米/秒" }
                        steeringBearing = ((bearing % 360) + 360) % 360
                        speed = requestedSpeed
                        steeringActive = true; moving = true
                    }
                }
                RELEASE -> if (mode == "JOYSTICK") { steeringActive = false; moving = false; emit() }
                END_JOYSTICK -> if (mode == "JOYSTICK") {
                    steeringActive = false; moving = false
                    mode = if (route != null && route?.finished == false) "ROUTE_PAUSED" else "FIXED"
                    publish(); emit()
                }
                SPEED -> {
                    speed = intent.getDoubleExtra("speed", speed).also { require(it in 0.1..100.0) }
                    route?.speedMetresPerSecond = speed
                    publish()
                }
            }
            ensureTicker()
            showForeground()
        } catch (e: Exception) {
            Session.update { it.copy(result = "失败", error = e.message ?: e.javaClass.simpleName) }
            stopSession()
        }
        return START_NOT_STICKY
    }
    private fun readPoint(intent: Intent) = Point(intent.getDoubleExtra("lat", current.lat), intent.getDoubleExtra("lon", current.lon))
    private fun prepareProvider() {
        if (providerOwned) return
        val properties = ProviderProperties.Builder()
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .build()
        manager.addTestProvider(LocationManager.GPS_PROVIDER, properties)
        providerOwned = true
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var previous = SystemClock.elapsedRealtimeNanos()
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtimeNanos()
                val dt = ((now - previous) / 1e9).coerceIn(0.0, 5.0)
                previous = now
                if (!Session.mockAllowed(this@MockLocationService)) {
                    Session.update { it.copy(error = "模拟定位授权已撤销", result = "停止") }
                    stopSession(); break
                }
                try {
                    if (mode == "ROUTE_RUNNING") {
                        val progress = route ?: continue
                        current = progress.advance(dt)
                        heading = progress.track.bearingAt(progress.travelled)
                        moving = !progress.finished
                        if (progress.finished) mode = "FIXED"
                    } else if (mode == "JOYSTICK" && steeringActive) {
                        current = Geo.offset(current, steeringBearing, speed * dt)
                        heading = steeringBearing; moving = true
                    }
                    if (mode != "FIXED_PAUSED") emit()
                } catch (e: Exception) {
                    Session.update { it.copy(result = "提交失败", error = e.message ?: e.javaClass.simpleName) }
                    stopSession(); break
                }
            }
        }
    }
    private fun emit() {
        if (!providerOwned) return
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = current.lat; longitude = current.lon
            accuracy = 3f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            altitude = 0.0
            speed = if (moving) speed.toFloat() else 0f
            bearing = if (moving) heading.toFloat() else 0f
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
        Session.update {
            it.copy(mode = mode, target = current, submitted = current, submittedAt = fix.time,
                result = "GPS 提交成功", serviceRunning = true,
                routeMetres = route?.travelled ?: 0.0, routeRemaining = route?.remaining ?: 0.0,
                canResumeRoute = route?.finished == false && mode != "ROUTE_RUNNING")
        }
        updateNotification()
    }
    private fun publish() {
        Session.update {
            it.copy(mode = mode, target = current, serviceRunning = true,
                routeMetres = route?.travelled ?: 0.0, routeRemaining = route?.remaining ?: 0.0,
                canResumeRoute = route?.finished == false && mode != "ROUTE_RUNNING")
        }
        updateNotification()
    }
    private fun recordHistory(name: String, point: Point) {
        try {
            Store(this).use { it.addPoint("history", name, point) }
            Session.update { it.copy(historyRevision = it.historyRevision + 1) }
        }
        catch (e: Exception) { Session.update { it.copy(error = "历史记录保存失败：${e.message}") } }
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, MockLocationService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("安卓改定位 · $mode")
            .setContentText(String.format(Locale.US, "%.6f, %.6f", current.lat, current.lon))
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止模拟", stop)
            .build()
    }
    private fun updateNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
        catch (_: SecurityException) { /* Notification permission is optional for foreground service execution. */ }
    }
    private fun showForeground() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification())
    }
    private fun stopSession() {
        ticker?.cancel(); ticker = null
        steeringActive = false; route = null; moving = false; mode = "IDLE"
        if (providerOwned) {
            try { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            catch (e: Exception) { Session.update { it.copy(error = "清理提供者失败：${e.message}") } }
            providerOwned = false
        }
        Session.update { it.copy(mode = "IDLE", target = null, serviceRunning = false, canResumeRoute = false, routeMetres = 0.0, routeRemaining = 0.0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        val interrupted = mode != "IDLE"
        if (providerOwned) {
            try { manager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) { }
            providerOwned = false
        }
        scope.cancel()
        Session.update { it.copy(mode = "IDLE", target = null, serviceRunning = false,
            canResumeRoute = false, routeMetres = 0.0, routeRemaining = 0.0,
            error = if (interrupted) "服务被系统结束；请在应用内重新启动模拟" else it.error) }
        super.onDestroy()
    }
}
