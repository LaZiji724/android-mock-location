package com.example.androidmocklocation

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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
    /** Bumped on every resume so setup checks re-read after returning from a settings page. */
    private var resumes by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mockAllowed = Session.mockAllowed(this)
        setContent { AppTheme { App(mockAllowed, resumes) } }
    }
    override fun onResume() {
        super.onResume()
        mockAllowed = Session.mockAllowed(this)
        // A session killed with its process leaves test providers behind, which keep feeding other apps the last fake fix.
        // Checked on every resume: the app may have been opened before it was selected as the mock-location app.
        if (mockAllowed && !Session.state.value.serviceRunning) Session.clearStaleProviders(this)
        resumes++
    }
}

private data class Destination(val title: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val destinations = listOf(
    Destination("地图", AppIcons.Map, AppIcons.Map),
    Destination("路线", AppIcons.Route, AppIcons.Route),
    Destination("收藏", AppIcons.StarBorder, Icons.Rounded.Star),
    Destination("诊断", Icons.Outlined.Build, Icons.Rounded.Build)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(allowed: Boolean, resumes: Int) {
    val context = LocalContext.current
    val snapshot by Session.state.collectAsState()
    val store = remember { Store(context) }
    var selected by remember { mutableStateOf(Prefs.lastTarget(context) ?: Point(39.9042, 116.4074)) }
    var latitude by remember { mutableStateOf(String.format(Locale.US, "%.6f", selected.lat)) }
    var longitude by remember { mutableStateOf(String.format(Locale.US, "%.6f", selected.lon)) }
    var tab by remember { mutableIntStateOf(0) }
    val waypoints = remember { mutableStateListOf<Point>() }
    var speedText by remember { mutableStateOf("1.4") }
    var refresh by remember { mutableIntStateOf(0) }
    var routeName by remember { mutableStateOf("") }
    var favoriteName by remember { mutableStateOf("") }
    var centerRequest by remember { mutableIntStateOf(0) }
    var showSetup by rememberSaveable { mutableStateOf(!Prefs.get(context, Prefs.SETUP_SEEN) || !DeviceSetup.requiredDone(context)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    DisposableEffect(store) { onDispose { store.close() } }
    // History is written by the service after the command has been delivered.
    LaunchedEffect(snapshot.historyRevision) { refresh++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun notify(text: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    }
    fun choose(point: Point) {
        selected = point
        latitude = String.format(Locale.US, "%.6f", point.lat)
        longitude = String.format(Locale.US, "%.6f", point.lon)
        Prefs.setLastTarget(context, point)
    }
    fun parsedPoint(): Point? = Point(latitude.toDoubleOrNull() ?: Double.NaN, longitude.toDoubleOrNull() ?: Double.NaN).takeIf { it.valid() }
    fun parsedSpeed(): Double? = speedText.toDoubleOrNull()?.takeIf { it in 0.1..100.0 }
    fun begin(action: () -> Unit) {
        if (!allowed) { notify("请先在开发者选项中选择本应用为模拟位置信息应用"); showSetup = true; return }
        if (!DeviceSetup.locationEnabled(context)) notify("系统“位置信息”开关已关闭，其他 App 将读不到模拟位置")
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { action(); if (DeviceSetup.locationEnabled(context)) notify("已发送定位指令，请查看状态与诊断页") }
        catch (e: Exception) { notify(e.message ?: "操作失败") }
    }
    fun openDeveloperSettings() {
        if (!DeviceSetup.developerOptionsEnabled(context)) notify("请先连续点击“版本号”7 次打开开发人员选项")
        DeviceSetup.openDeveloperSettings(context)
    }
    if (showSetup) SetupGuide(resumes) { Prefs.set(context, Prefs.SETUP_SEEN, true); showSetup = false }
    val favoritePoints = remember(refresh) { store.points("favorites").map { it.point } }

    Scaffold(
        topBar = { StatusHeader(allowed, snapshot, onStop = { Session.command(context, MockLocationService.STOP) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(if (tab == index) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(item.title) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AnimatedVisibility(!allowed) { PermissionBanner(onOpen = { showSetup = true }) }
            when (tab) {
                0 -> {
                    MapPane(Modifier.weight(1f), selected, snapshot.submitted, waypoints.toList(), favoritePoints, centerRequest,
                        onSelect = { choose(it) }, onCenter = { centerRequest++ }, hint = "点击地图选点")
                    ControlPanel {
                        SectionTitle("目标坐标", "WGS-84")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(longitude, { longitude = it }, label = { Text("经度") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                val point = parsedPoint()
                                if (point == null) notify("经纬度无效") else begin {
                                    choose(point); refresh++
                                    Session.command(context, MockLocationService.FIX, point)
                                }
                            }, enabled = allowed, modifier = Modifier.weight(1f).height(48.dp)) {
                                Icon(AppIcons.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始固定定位")
                            }
                            if (snapshot.mode == "FIXED") FilledTonalIconButton(onClick = { Session.command(context, MockLocationService.PAUSE) }, Modifier.size(48.dp)) { Icon(AppIcons.Pause, "暂停") }
                            if (snapshot.mode == "FIXED_PAUSED") FilledTonalIconButton(onClick = { Session.command(context, MockLocationService.RESUME) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.PlayArrow, "继续") }
                            if (snapshot.serviceRunning) StopButton { Session.command(context, MockLocationService.STOP) }
                        }
                        SubmittedLine(snapshot)
                    }
                }
                1 -> {
                    MapPane(Modifier.weight(0.85f), selected, snapshot.submitted, waypoints.toList(), favoritePoints, centerRequest,
                        onSelect = { point -> choose(point); waypoints += point }, onCenter = { centerRequest++ },
                        hint = if (waypoints.isEmpty()) "点击地图添加途经点" else "已添加 ${waypoints.size} 个途经点")
                    LazyColumn(
                        Modifier.weight(1.15f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (snapshot.mode == "JOYSTICK") item {
                            PanelCard {
                                SectionTitle("虚拟摇杆", "按住移动 · 松开停留")
                                JoystickControls(speedText, onDirection = { bearing -> Session.command(context, MockLocationService.STEER, speed = parsedSpeed() ?: 1.4, bearing = bearing) }, onRelease = { Session.command(context, MockLocationService.RELEASE) })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { Session.command(context, MockLocationService.END_JOYSTICK) }, Modifier.weight(1f)) { Text("退出摇杆") }
                                    if (snapshot.canResumeRoute) Button(onClick = { Session.command(context, MockLocationService.RESUME) }, Modifier.weight(1f)) { Text("继续原路线") }
                                }
                            }
                        }
                        item {
                            PanelCard {
                                SectionTitle("路线控制", "${waypoints.size} 个途经点")
                                OutlinedTextField(speedText, { speedText = it }, label = { Text("速度（米/秒，0.1–100）") },
                                    supportingText = { Text(speedHint(speedText)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = {
                                        val speed = parsedSpeed()
                                        if (speed == null) notify("速度需在 0.1–100 米/秒") else begin {
                                            Session.command(context, MockLocationService.ROUTE, speed = speed, points = waypoints.toList())
                                        }
                                    }, enabled = allowed && waypoints.size >= 2, modifier = Modifier.weight(1f).height(48.dp)) {
                                        Icon(AppIcons.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始路线")
                                    }
                                    if (snapshot.mode == "ROUTE_RUNNING") FilledTonalIconButton(onClick = { Session.command(context, MockLocationService.PAUSE) }, Modifier.size(48.dp)) { Icon(AppIcons.Pause, "暂停") }
                                    if (snapshot.canResumeRoute) FilledTonalIconButton(onClick = { Session.command(context, MockLocationService.RESUME) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.PlayArrow, "继续") }
                                    if (snapshot.serviceRunning) StopButton { Session.command(context, MockLocationService.STOP) }
                                }
                                val routeActive = snapshot.mode == "ROUTE_RUNNING" || snapshot.mode == "ROUTE_PAUSED"
                                OutlinedButton(onClick = {
                                    val speed = parsedSpeed()
                                    if (speed == null) notify("速度无效") else Session.command(context, MockLocationService.SPEED, speed = speed)
                                }, enabled = routeActive, modifier = Modifier.fillMaxWidth()) { Text("应用新速度") }
                                RouteProgressBar(snapshot.routeMetres, snapshot.routeRemaining)
                                if (snapshot.mode != "JOYSTICK") FilledTonalButton(onClick = {
                                    val speed = parsedSpeed()
                                    if (speed == null) notify("速度无效") else begin { Session.command(context, MockLocationService.JOYSTICK, point = snapshot.submitted ?: selected, speed = speed) }
                                }, enabled = allowed, modifier = Modifier.fillMaxWidth()) {
                                    Icon(AppIcons.Gamepad, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("进入虚拟摇杆")
                                }
                            }
                        }
                        item {
                            PanelCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { SectionTitle("途经点", null) }
                                    IconButton(onClick = { if (waypoints.isNotEmpty()) waypoints.removeAt(waypoints.lastIndex) }, enabled = waypoints.isNotEmpty()) { Icon(AppIcons.Undo, "撤销末点") }
                                    IconButton(onClick = { waypoints.clear() }, enabled = waypoints.isNotEmpty()) { Icon(Icons.Rounded.Clear, "清空") }
                                }
                                if (waypoints.isEmpty()) EmptyHint("在上方地图上点击即可依次添加途经点，至少需要 2 个。")
                                waypoints.forEachIndexed { index, point ->
                                    WaypointRow(index, point, waypoints.lastIndex,
                                        onUp = { if (index > 0) { val p = waypoints.removeAt(index); waypoints.add(index - 1, p) } },
                                        onDown = { if (index < waypoints.lastIndex) { val p = waypoints.removeAt(index); waypoints.add(index + 1, p) } },
                                        onDelete = { waypoints.removeAt(index) })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(routeName, { routeName = it }, label = { Text("路线名称") }, modifier = Modifier.weight(1f), singleLine = true)
                                    FilledTonalButton(onClick = {
                                        try { store.addRoute(routeName.ifBlank { "手动路线" }, waypoints.toList()); refresh++; notify("路线已保存") }
                                        catch (e: Exception) { notify(e.message ?: "保存失败") }
                                    }, enabled = waypoints.size >= 2) { Text("保存") }
                                }
                            }
                        }
                    }
                }
                2 -> SavedScreen(store, refresh, selected, snapshot.submitted, favoriteName, { favoriteName = it },
                    onChanged = { refresh++ }, notify = ::notify,
                    onChoose = { choose(it); tab = 0 },
                    onLoadRoute = { route -> waypoints.clear(); waypoints.addAll(route.points); choose(route.points.first()); tab = 1 })
                else -> Diagnostics(allowed, selected, snapshot, resumes, onOpenSettings = ::openDeveloperSettings, onOpenSetup = { showSetup = true }, notify = ::notify)
            }
        }
    }
}

private fun speedHint(text: String): String {
    val speed = text.toDoubleOrNull() ?: return "请输入数字"
    return "约 %.1f 公里/小时 · 步行 1.4 · 骑行 5 · 驾车 15".format(Locale.US, speed * 3.6)
}

@Composable
private fun StatusHeader(allowed: Boolean, snapshot: SessionSnapshot, onStop: () -> Unit) {
    val active = snapshot.serviceRunning
    val paused = snapshot.mode.endsWith("PAUSED")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("安卓改定位", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot = when {
                        !active -> MaterialTheme.colorScheme.outline
                        paused -> Color(0xFFE6A100)
                        else -> Color(0xFF2E9E5B)
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(modeLabel(snapshot.mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (active) {
                FilledTonalButton(onClick = onStop, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(AppIcons.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("停止") }
            } else {
                AssistChip(onClick = {}, enabled = false, label = { Text(if (allowed) "已授权" else "未授权") },
                    leadingIcon = { Icon(if (allowed) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        disabledLeadingIconContentColor = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
            }
        }
    }
}

@Composable
private fun PermissionBanner(onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null)
            Spacer(Modifier.width(12.dp))
            Text("尚未授权模拟定位：需打开开发人员选项，并把“选择模拟位置信息应用”设为安卓改定位。",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen) { Text("配置向导") }
        }
    }
}

@Composable
private fun MapPane(modifier: Modifier, selected: Point, submitted: Point?, route: List<Point>, favorites: List<Point>, centerRequest: Int,
                    onSelect: (Point) -> Unit, onCenter: () -> Unit, hint: String) {
    Box(modifier.fillMaxWidth()) {
        NativeMap(selected, submitted, route, favorites, centerRequest, onSelect, Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 2.dp) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(hint, style = MaterialTheme.typography.labelMedium)
            }
        }
        SmallFloatingActionButton(onClick = onCenter, Modifier.align(Alignment.BottomEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
            Icon(AppIcons.MyLocation, "地图中心移至选点")
        }
        Text(MapSources.ATTRIBUTION, Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333))
    }
}

@Composable
private fun ControlPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 6.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
        Icon(AppIcons.Stop, "停止")
    }
}

@Composable
private fun SubmittedLine(snapshot: SessionSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.MyLocation, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("已提交：${snapshot.submitted ?: "无"} · ${snapshot.result}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RouteProgressBar(metres: Double, remaining: Double) {
    val total = metres + remaining
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(progress = { if (total > 0) (metres / total).toFloat() else 0f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        Text("已走 %.1f 米 · 剩余 %.1f 米".format(Locale.US, metres, remaining), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WaypointRow(index: Int, point: Point, lastIndex: Int, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(
            when (index) { 0 -> MaterialTheme.colorScheme.tertiary; lastIndex -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.primary }),
            contentAlignment = Alignment.Center) {
            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Text(point.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        IconButton(onClick = onUp, enabled = index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "上移") }
        IconButton(onClick = onDown, enabled = index < lastIndex, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "下移") }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "删除") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedScreen(store: Store, refresh: Int, selected: Point, submitted: Point?, name: String, onName: (String) -> Unit,
                        onChanged: () -> Unit, notify: (String) -> Unit, onChoose: (Point) -> Unit, onLoadRoute: (SavedRoute) -> Unit) {
    val favorites = remember(refresh) { store.points("favorites") }
    val history = remember(refresh) { store.points("history") }
    val routes = remember(refresh) { store.routes() }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<SavedPoint?>(null) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, onName, label = { Text("收藏名称") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { store.addPoint("favorites", name.ifBlank { "收藏地点" }, selected); onChanged(); notify("已收藏") }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("收藏选点")
                }
                OutlinedButton(onClick = {
                    if (submitted != null) { store.addPoint("favorites", name.ifBlank { "当前模拟位置" }, submitted); onChanged(); notify("已收藏") }
                }, enabled = submitted != null, modifier = Modifier.weight(1f)) { Text("收藏当前位置") }
            }
            Text("收藏和历史均保存 WGS-84 坐标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrimaryTabRow(selectedTabIndex = section) {
            listOf("收藏 ${favorites.size}", "历史 ${history.size}", "路线 ${routes.size}").forEachIndexed { index, title ->
                Tab(selected = section == index, onClick = { section = index }, text = { Text(title) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (section) {
                0 -> {
                    emptyItem(favorites.isEmpty(), "还没有收藏。选好地点后点击“收藏选点”。")
                    items(favorites, key = { "f${it.id}" }) { item ->
                        SavedItemCard(Icons.Rounded.Star, item.name, item.point.toString(), onClick = { onChoose(item.point) }) {
                            IconButton(onClick = { renaming = item }) { Icon(Icons.Rounded.Edit, "改名") }
                            IconButton(onClick = { store.delete("favorites", item.id); onChanged() }) { Icon(Icons.Rounded.Delete, "删除") }
                        }
                    }
                }
                1 -> {
                    emptyItem(history.isEmpty(), "开始模拟后，位置会自动记录到这里。")
                    items(history, key = { "h${it.id}" }) { item ->
                        SavedItemCard(AppIcons.History, item.name, "${item.point} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.created))}", onClick = { onChoose(item.point) }) {
                            IconButton(onClick = { onChoose(item.point) }) { Icon(AppIcons.MyLocation, "选取") }
                        }
                    }
                }
                else -> {
                    emptyItem(routes.isEmpty(), "在“路线”页添加途经点后可保存路线。")
                    items(routes, key = { "r${it.id}" }) { item ->
                        SavedItemCard(AppIcons.Route, item.name, "${item.points.size} 个途经点", onClick = { onLoadRoute(item) }) {
                            IconButton(onClick = { onLoadRoute(item) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "加载") }
                            IconButton(onClick = { store.delete("routes", item.id); onChanged() }) { Icon(Icons.Rounded.Delete, "删除") }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { item ->
        var newName by remember(item.id) { mutableStateOf(item.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名收藏") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { store.renameFavorite(item.id, newName.ifBlank { item.name }); onChanged(); renaming = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
}

private fun LazyListScope.emptyItem(show: Boolean, text: String) {
    if (show) item { Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { EmptyHint(text) } }
}

@Composable
private fun SavedItemCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}

@Composable
private fun JoystickControls(speed: String, onDirection: (Double) -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf<Double?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(200.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Column {
                listOf(listOf(315.0, 0.0, 45.0), listOf(270.0, null, 90.0), listOf(225.0, 180.0, 135.0)).forEach { row ->
                    Row {
                        row.forEach { bearing ->
                            if (bearing == null) Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                                Text("$speed\nm/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                            else Box(Modifier.size(60.dp).padding(4.dp).clip(CircleShape)
                                .background(if (pressed == bearing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                                .pointerInput(bearing, speed) {
                                    detectTapGestures(onPress = {
                                        pressed = bearing
                                        onDirection(bearing)
                                        try { tryAwaitRelease() } finally { pressed = null; onRelease() }
                                    })
                                }, contentAlignment = Alignment.Center) {
                                Icon(AppIcons.ArrowUpward, null, Modifier.rotate(bearing.toFloat()),
                                    tint = if (pressed == bearing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Diagnostics(allowed: Boolean, selected: Point, snapshot: SessionSnapshot, resumes: Int,
                        onOpenSettings: () -> Unit, onOpenSetup: () -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    var mockNetwork by remember { mutableStateOf(Prefs.mockNetwork(context)) }
    var shieldObserve by remember { mutableStateOf(Prefs.get(context, Prefs.SHIELD_OBSERVE, false)) }
    var shieldBlock by remember { mutableStateOf(Prefs.get(context, Prefs.SHIELD_BLOCK, false)) }
    val shieldRunning by DnsShield.running.collectAsState()
    val shieldBlocking by DnsShield.blocking.collectAsState()
    val shieldTotal by DnsShield.total.collectAsState()
    val shieldBlockedCount by DnsShield.blockedCount.collectAsState()
    val shieldRecent by DnsShield.recent.collectAsState()
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            LocationShieldService.start(context, Prefs.get(context, Prefs.SHIELD_BLOCK, false))
        } else {
            shieldObserve = false
            shieldBlock = false
            Prefs.set(context, Prefs.SHIELD_OBSERVE, false)
            Prefs.set(context, Prefs.SHIELD_BLOCK, false)
            notify("未授予 VPN 权限，网络定位拦截未开启")
        }
    }
    fun applyShield(observe: Boolean, block: Boolean) {
        shieldObserve = observe
        shieldBlock = block
        Prefs.set(context, Prefs.SHIELD_OBSERVE, observe)
        Prefs.set(context, Prefs.SHIELD_BLOCK, block)
        if (!observe && !block) {
            LocationShieldService.stop(context)
            return
        }
        val consent = VpnService.prepare(context)
        if (consent != null) vpnConsent.launch(consent) else LocationShieldService.start(context, block)
    }
    val checks = remember(resumes) {
        Triple(DeviceSetup.locationEnabled(context), DeviceSetup.notificationsEnabled(context), DeviceSetup.ignoringBatteryOptimizations(context))
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("未知")
    }
    val rows = listOf(
        "应用版本" to version,
        "设备" to "${Build.BRAND} ${Build.MODEL}",
        "系统" to DeviceSetup.systemLabel(),
        "模拟定位授权" to if (allowed) "允许" else "未授权",
        "系统位置开关" to if (checks.first) "开启" else "关闭",
        "通知" to if (checks.second) "允许" else "关闭",
        "后台运行" to if (checks.third) "已忽略电池优化" else "受电池优化限制",
        "会话" to "${snapshot.mode}，前台服务：${snapshot.serviceRunning}",
        "模拟提供者" to when {
            !snapshot.serviceRunning -> if (mockNetwork) "GPS + 网络（待启动）" else "仅 GPS（待启动）"
            snapshot.networkMocked -> "GPS + 网络"
            else -> "仅 GPS"
        },
        "地图选中" to "$selected",
        "引擎目标" to "${snapshot.target ?: "无"}",
        "GPS 提交" to "${snapshot.submitted ?: "无"}",
        "提交时间" to if (snapshot.submittedAt == 0L) "无" else DateFormat.getDateTimeInstance().format(Date(snapshot.submittedAt)),
        "API 结果" to snapshot.result,
        "异常" to snapshot.error.ifBlank { "无" },
        "第三方 App 兼容性" to "地图类直接采纳；微信需开启网络定位拦截",
        "模拟位置标记" to "提交坐标带系统 mock 标记，普通应用无法隐藏"
    )
    val report = rows.joinToString("\n", postfix = "\n") { (label, value) -> "$label：$value" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard {
            SectionTitle("诊断信息", if (snapshot.error.isBlank()) "运行正常" else "有异常")
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = if (label == "异常" && snapshot.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Button(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("定位诊断", report))
            notify("诊断信息已复制")
        }, Modifier.fillMaxWidth()) { Icon(AppIcons.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("复制诊断信息") }
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同时模拟网络定位", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("推荐开启。华为等无谷歌服务的手机上，很多 App 优先使用网络定位；关闭后室内可能跳回真实位置。下次开始模拟时生效。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = mockNetwork, onCheckedChange = { mockNetwork = it; Prefs.set(context, Prefs.MOCK_NETWORK, it) })
            }
        }
        PanelCard {
            SectionTitle("网络定位拦截（微信模式）", when {
                !shieldRunning -> "关闭"
                shieldBlocking -> "拦截中"
                else -> "仅记录"
            })
            Text("微信优先使用腾讯自己的 WiFi 网络定位，把系统模拟坐标压回真实位置。开启后本机只接管 DNS（其余流量照常走原网络）：只记录模式列出各 App 解析的域名；拦截模式让腾讯定位域名解析失败，逼微信回退到系统 GPS 模拟。真机验证（nova 5z + 微信 8.0.79）：开启拦截后微信“发送位置”“共享实时位置”均显示模拟坐标，聊天和地图瓦片正常。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("只记录 DNS 查询", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("观察微信定位时解析了哪些域名，用于确定拦截名单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = shieldObserve, onCheckedChange = { applyShield(it, false) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("拦截腾讯定位域名", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("让腾讯定位接口失败，逼微信使用系统模拟坐标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = shieldBlock, onCheckedChange = { applyShield(false, it) })
            }
            Text("累计 DNS 查询 ${shieldTotal} 条，拦截 ${shieldBlockedCount} 条",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shieldRecent.take(6).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = if (line.startsWith("拦截")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val text = buildString {
                        appendLine("VPN：${if (shieldRunning) (if (shieldBlocking) "拦截" else "仅记录") else "关闭"}")
                        appendLine("查询 ${shieldTotal}，拦截 ${shieldBlockedCount}")
                        shieldRecent.forEach { appendLine(it) }
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DNS 记录", text))
                    notify("DNS 记录已复制")
                }, Modifier.weight(1f)) { Text("复制 DNS 记录") }
                OutlinedButton(onClick = { DnsShield.reset(); notify("已清空 DNS 记录") }, Modifier.weight(1f)) { Text("清空") }
            }
        }
        PanelCard {
            SectionTitle("已知限制", "实测结论")
            Text("不开“网络定位拦截”时，微信发送位置/共享实时位置会显示真实网络定位；开启拦截后真机验证改用系统模拟坐标。华为 HMS 定位类系统应用仍会丢弃模拟坐标；高德等走系统定位的地图 App 直接采纳。微信以后更换定位域名时，用“只记录”抓新域名再补拦截名单。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onOpenSetup, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("首次配置向导") }
        OutlinedButton(onClick = onOpenSettings, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("打开模拟定位授权设置") }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)) {
            Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text("系统 GPS 提交成功只代表系统层已注入。高德等地图 App 直接采纳；微信需开启上方“网络定位拦截”，实测后发送位置和共享实时位置都会使用模拟坐标。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun NativeMap(selected: Point, submitted: Point?, route: List<Point>, favorites: List<Point>, centerRequest: Int, onSelect: (Point) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestSelect by rememberUpdatedState(onSelect)
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
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
            loaded.uiSettings.isAttributionEnabled = false
            loaded.uiSettings.isLogoEnabled = false
            loaded.uiSettings.setCompassMargins(0, 160, 24, 0)
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
        if (route.size >= 2) m.addPolyline(PolylineOptions().addAll(route.map(::mapPoint)).color(routeColor).width(5f))
    }
    LaunchedEffect(map, ready, centerRequest) {
        val m = map ?: return@LaunchedEffect
        val point = Coordinates.toMap(selected, MapSources.coordinateSystem)
        m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(point.lat, point.lon)))
    }
}
