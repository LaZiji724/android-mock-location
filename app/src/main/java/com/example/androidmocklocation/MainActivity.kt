package com.example.androidmocklocation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object MapSources {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val ATTRIBUTION = "© OpenStreetMap contributors · OpenFreeMap"
    val coordinateSystem = CoordinateSystem.WGS84
}

class MainActivity : ComponentActivity() {
    private var mockAllowed by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mockAllowed = Session.mockAllowed(this)
        setContent { MaterialTheme { App(mockAllowed, this) } }
    }
    override fun onResume() {
        super.onResume()
        mockAllowed = Session.mockAllowed(this)
    }
}

@Composable
private fun App(allowed: Boolean, activity: MainActivity) {
    val context = LocalContext.current
    val snapshot by Session.state.collectAsState()
    val store = remember { Store(context) }
    var selected by remember { mutableStateOf(Point(39.9042, 116.4074)) }
    var latitude by remember { mutableStateOf("39.904200") }
    var longitude by remember { mutableStateOf("116.407400") }
    var tab by remember { mutableIntStateOf(0) }
    val waypoints = remember { mutableStateListOf<Point>() }
    var speedText by remember { mutableStateOf("1.4") }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var centerRequest by remember { mutableIntStateOf(0) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun choose(point: Point) {
        selected = point
        latitude = String.format(Locale.US, "%.6f", point.lat)
        longitude = String.format(Locale.US, "%.6f", point.lon)
    }
    fun parsedPoint(): Point? = Point(latitude.toDoubleOrNull() ?: Double.NaN, longitude.toDoubleOrNull() ?: Double.NaN).takeIf { it.valid() }
    fun parsedSpeed(): Double? = speedText.toDoubleOrNull()?.takeIf { it in 0.1..100.0 }
    fun begin(action: () -> Unit) {
        if (!allowed) { message = "请先在开发者选项中选择本应用为模拟位置信息应用"; return }
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { action(); message = "已发送定位指令，请查看状态与诊断页" }
        catch (e: Exception) { message = e.message ?: "操作失败" }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).statusBarsPadding().padding(14.dp)) {
                Text("安卓改定位", style = MaterialTheme.typography.titleLarge)
                Text("${snapshot.mode} · ${if (allowed) "模拟定位已授权" else "需要系统授权"}")
            }
        },
        bottomBar = {
            NavigationBar {
                listOf("地图", "路线", "收藏", "诊断").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text("●") }, label = { Text(title) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!allowed) {
                Text("需先开启开发者选项，并将“选择模拟位置信息应用”设为安卓改定位。返回此页会自动重新检查。", Modifier.padding(12.dp))
                Button(onClick = { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, Modifier.padding(horizontal = 12.dp)) { Text("打开开发者选项") }
            }
            if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
            when (tab) {
                0 -> {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        NativeMap(selected, snapshot.submitted, waypoints.toList(), remember(refresh) { store.points("favorites").map { it.point } }, centerRequest, { choose(it) }, Modifier.fillMaxSize())
                        Text(MapSources.ATTRIBUTION, Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = 0.85f)).padding(3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("点击地图选点 · WGS-84", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { centerRequest++ }) { Text("地图中心移至选点") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(longitude, { longitude = it }, label = { Text("经度") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                val point = parsedPoint()
                                if (point == null) message = "经纬度无效" else begin {
                                    choose(point); refresh++
                                    Session.command(context, MockLocationService.FIX, point)
                                }
                            }, enabled = allowed, modifier = Modifier.weight(1f)) { Text("开始固定定位") }
                            if (snapshot.mode == "FIXED") OutlinedButton(onClick = { Session.command(context, MockLocationService.PAUSE) }) { Text("暂停") }
                            if (snapshot.mode == "FIXED_PAUSED") OutlinedButton(onClick = { Session.command(context, MockLocationService.RESUME) }) { Text("继续") }
                            if (snapshot.serviceRunning) OutlinedButton(onClick = { Session.command(context, MockLocationService.STOP) }) { Text("停止") }
                        }
                        Text("已提交：${snapshot.submitted ?: "无"} · ${snapshot.result}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                1 -> {
                    Box(Modifier.fillMaxWidth().weight(0.9f)) {
                        NativeMap(selected, snapshot.submitted, waypoints.toList(), remember(refresh) { store.points("favorites").map { it.point } }, centerRequest, { point -> choose(point); waypoints += point }, Modifier.fillMaxSize())
                        Text(MapSources.ATTRIBUTION, Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = 0.85f)).padding(3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    Column(Modifier.fillMaxWidth().weight(1.1f).padding(12.dp)) {
                        Text("路线编辑：点地图添加途经点", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(speedText, { speedText = it }, label = { Text("速度（米/秒，0.1–100）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                val speed = parsedSpeed()
                                if (speed == null) message = "速度需在 0.1–100 米/秒" else begin {
                                    Session.command(context, MockLocationService.ROUTE, speed = speed, points = waypoints.toList())
                                }
                            }, enabled = allowed && waypoints.size >= 2, modifier = Modifier.weight(1f)) { Text("开始路线") }
                            if (snapshot.mode == "ROUTE_RUNNING") OutlinedButton(onClick = { Session.command(context, MockLocationService.PAUSE) }) { Text("暂停") }
                            if (snapshot.canResumeRoute) OutlinedButton(onClick = { Session.command(context, MockLocationService.RESUME) }) { Text("继续") }
                            if (snapshot.serviceRunning) OutlinedButton(onClick = { Session.command(context, MockLocationService.STOP) }) { Text("停止") }
                        }
                        OutlinedButton(onClick = {
                            val speed = parsedSpeed()
                            if (speed == null) message = "速度无效" else Session.command(context, MockLocationService.SPEED, speed = speed)
                        }, enabled = snapshot.mode == "ROUTE_RUNNING" || snapshot.mode == "ROUTE_PAUSED") { Text("应用新速度") }
                        Text("已走 %.1f 米 · 剩余 %.1f 米".format(Locale.US, snapshot.routeMetres, snapshot.routeRemaining))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { waypoints.clear() }) { Text("清空") }
                            OutlinedButton(onClick = { if (waypoints.isNotEmpty()) waypoints.removeAt(waypoints.lastIndex) }) { Text("撤销末点") }
                            OutlinedButton(onClick = {
                                try { store.addRoute(name.ifBlank { "手动路线" }, waypoints.toList()); refresh++; message = "路线已保存" }
                                catch (e: Exception) { message = e.message ?: "保存失败" }
                            }, enabled = waypoints.size >= 2) { Text("保存路线") }
                        }
                        OutlinedTextField(name, { name = it }, label = { Text("路线或收藏名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        LazyColumn(Modifier.weight(1f)) {
                            items(waypoints.size) { index ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${waypoints[index]}", Modifier.weight(1f))
                                    TextButton(onClick = { if (index > 0) { val p = waypoints.removeAt(index); waypoints.add(index - 1, p) } }) { Text("↑") }
                                    TextButton(onClick = { if (index < waypoints.lastIndex) { val p = waypoints.removeAt(index); waypoints.add(index + 1, p) } }) { Text("↓") }
                                    TextButton(onClick = { waypoints.removeAt(index) }) { Text("删") }
                                }
                            }
                        }
                        if (snapshot.mode == "JOYSTICK") {
                            Text("摇杆控制中 · 松开方向键保持位置")
                            JoystickControls(speedText, onDirection = { bearing -> Session.command(context, MockLocationService.STEER, speed = parsedSpeed() ?: 1.4, bearing = bearing) }, onRelease = { Session.command(context, MockLocationService.RELEASE) })
                            Row {
                                OutlinedButton(onClick = { Session.command(context, MockLocationService.END_JOYSTICK) }) { Text("退出摇杆") }
                                if (snapshot.canResumeRoute) OutlinedButton(onClick = { Session.command(context, MockLocationService.RESUME) }) { Text("继续原路线") }
                            }
                        } else Button(onClick = {
                            val speed = parsedSpeed()
                            if (speed == null) message = "速度无效" else begin { Session.command(context, MockLocationService.JOYSTICK, point = snapshot.submitted ?: selected, speed = speed) }
                        }, enabled = allowed) { Text("进入虚拟摇杆") }
                    }
                }
                2 -> {
                    val favorites = remember(refresh) { store.points("favorites") }
                    val history = remember(refresh) { store.points("history") }
                    val routes = remember(refresh) { store.routes() }
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text("收藏和历史均保存 WGS-84 坐标", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.weight(1f), singleLine = true)
                            Button(onClick = { store.addPoint("favorites", name.ifBlank { "收藏地点" }, selected); refresh++; message = "已收藏" }) { Text("收藏选点") }
                            OutlinedButton(onClick = {
                                val point = snapshot.submitted
                                if (point != null) { store.addPoint("favorites", name.ifBlank { "当前模拟位置" }, point); refresh++ }
                            }, enabled = snapshot.submitted != null) { Text("收藏当前位置") }
                        }
                        LazyColumn {
                            item { Text("收藏", style = MaterialTheme.typography.titleSmall) }
                            items(favorites, key = { "f${it.id}" }) { item ->
                                SavedPointRow(item, onChoose = { choose(item.point); tab = 0 }, onDelete = { store.delete("favorites", item.id); refresh++ }, onRename = { store.renameFavorite(item.id, name.ifBlank { item.name }); refresh++ })
                            }
                            item { Text("历史", style = MaterialTheme.typography.titleSmall) }
                            items(history, key = { "h${it.id}" }) { item ->
                                SavedPointRow(item, onChoose = { choose(item.point); tab = 0 }, onDelete = null, onRename = null)
                            }
                            item { Text("已保存路线", style = MaterialTheme.typography.titleSmall) }
                            items(routes, key = { "r${it.id}" }) { item ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${item.name} · ${item.points.size} 点", Modifier.weight(1f))
                                    TextButton(onClick = { waypoints.clear(); waypoints.addAll(item.points); choose(item.points.first()); tab = 1 }) { Text("加载") }
                                    TextButton(onClick = { store.delete("routes", item.id); refresh++ }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
                else -> Diagnostics(allowed, selected, snapshot, activity)
            }
        }
    }
}

@Composable
private fun SavedPointRow(item: SavedPoint, onChoose: () -> Unit, onDelete: (() -> Unit)?, onRename: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onChoose)) {
            Text(item.name)
            Text(item.point.toString(), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onChoose) { Text("选取") }
        if (onRename != null) TextButton(onClick = onRename) { Text("改名") }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun JoystickControls(speed: String, onDirection: (Double) -> Unit, onRelease: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(listOf(null, 315.0, 0.0, 45.0, null), listOf(null, 270.0, null, 90.0, null), listOf(null, 225.0, 180.0, 135.0, null)).forEach { row ->
            Row {
                row.forEach { bearing ->
                    if (bearing == null) Spacer(Modifier.size(48.dp))
                    else Box(Modifier.size(48.dp).padding(2.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                        .pointerInput(bearing, speed) {
                            detectTapGestures(onPress = {
                                onDirection(bearing)
                                try { tryAwaitRelease() } finally { onRelease() }
                            })
                        }, contentAlignment = Alignment.Center) {
                        Text(when (bearing.toInt()) { 0 -> "↑"; 45 -> "↗"; 90 -> "→"; 135 -> "↘"; 180 -> "↓"; 225 -> "↙"; 270 -> "←"; else -> "↖" })
                    }
                }
            }
        }
    }
}

@Composable
private fun Diagnostics(allowed: Boolean, selected: Point, snapshot: SessionSnapshot, activity: MainActivity) {
    val context = LocalContext.current
    val report = buildString {
        appendLine("设备：${Build.BRAND} ${Build.MODEL}")
        appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("模拟定位授权：${if (allowed) "允许" else "未授权"}")
        appendLine("会话：${snapshot.mode}，前台服务：${snapshot.serviceRunning}")
        appendLine("地图选中：$selected")
        appendLine("引擎目标：${snapshot.target ?: "无"}")
        appendLine("GPS 提交：${snapshot.submitted ?: "无"}")
        appendLine("提交时间：${if (snapshot.submittedAt == 0L) "无" else DateFormat.getDateTimeInstance().format(Date(snapshot.submittedAt))}")
        appendLine("API 结果：${snapshot.result}")
        appendLine("异常：${snapshot.error.ifBlank { "无" }}")
        appendLine("独立定位读取程序结果：待单独测试")
        appendLine("第三方 App 显示：待人工测试")
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("诊断", style = MaterialTheme.typography.titleLarge)
        Text(report)
        Button(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("定位诊断", report))
        }) { Text("复制诊断信息") }
        OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("打开模拟定位授权设置") }
        Text("系统 GPS 提交成功不代表微信采用该位置；请按测试清单分别验证。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NativeMap(selected: Point, submitted: Point?, route: List<Point>, favorites: List<Point>, centerRequest: Int, onSelect: (Point) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestSelect by rememberUpdatedState(onSelect)
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(mapView, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); mapView.onDestroy() }
    }
    AndroidView(factory = {
        mapView.getMapAsync { loaded ->
            map = loaded
            val center = Coordinates.toMap(selected, MapSources.coordinateSystem)
            loaded.cameraPosition = CameraPosition.Builder().target(LatLng(center.lat, center.lon)).zoom(13.0).build()
            loaded.setStyle(MapSources.STYLE_URL) { ready = true }
            loaded.addOnMapClickListener { position -> latestSelect(Coordinates.fromMap(Point(position.latitude, position.longitude), MapSources.coordinateSystem)); true }
        }
        mapView
    }, modifier = modifier)
    LaunchedEffect(map, ready, selected, submitted, route, favorites) {
        val m = map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        m.removeAnnotations()
        fun mapPoint(point: Point): LatLng = Coordinates.toMap(point, MapSources.coordinateSystem).let { LatLng(it.lat, it.lon) }
        m.addMarker(MarkerOptions().position(mapPoint(selected)).title("选中目标"))
        submitted?.let { m.addMarker(MarkerOptions().position(mapPoint(it)).title("最近提交")) }
        route.forEachIndexed { index, point -> m.addMarker(MarkerOptions().position(mapPoint(point)).title("途经点 ${index + 1}")) }
        favorites.forEach { m.addMarker(MarkerOptions().position(mapPoint(it)).title("收藏位置")) }
        if (route.size >= 2) m.addPolyline(PolylineOptions().addAll(route.map(::mapPoint)).color(android.graphics.Color.BLUE).width(4f))
    }
    LaunchedEffect(centerRequest) {
        val m = map ?: return@LaunchedEffect
        val point = Coordinates.toMap(selected, MapSources.coordinateSystem)
        m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(point.lat, point.lon)))
    }
}
