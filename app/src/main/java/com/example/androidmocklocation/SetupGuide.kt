package com.example.androidmocklocation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * First-run checklist. [refresh] changes whenever the activity resumes, so states re-read after the user
 * comes back from a settings page.
 */
@Composable
fun SetupGuide(refresh: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    var confirmations by remember { mutableIntStateOf(0) }
    val steps = remember(refresh, confirmations) { DeviceSetup.steps(context) }
    val manual = remember(steps) { steps.associate { step -> step.key to (step.confirmKey?.let { Prefs.get(context, it) } ?: false) } }
    val requiredLeft = steps.count { it.required && it.done != true }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("首次配置向导", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("本机：${DeviceSetup.systemLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)) {
                            Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(if (DeviceSetup.huaweiFamily)
                                "华为/荣耀手机后台管理较严格：前 3 项不做无法模拟，后 4 项不做时锁屏或切后台后模拟可能被系统结束。点“去…”跳到对应设置，返回本页会自动重新检查。"
                            else "前 3 项必做；其余项能让模拟在锁屏和后台时保持运行。返回本页会自动重新检查。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    items(steps, key = { it.key }) { step ->
                        StepCard(steps.indexOf(step) + 1, step, manual[step.key] == true,
                            onAction = { step.open(context) },
                            onConfirm = { checked -> step.confirmKey?.let { Prefs.set(context, it, checked); confirmations++ } })
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (requiredLeft == 0) "必做项已完成，可以开始模拟" else "还有 $requiredLeft 个必做项",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (requiredLeft == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Button(onClick = onClose) { Text(if (requiredLeft == 0) "完成" else "稍后再说") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, step: SetupStep, manualDone: Boolean, onAction: () -> Unit, onConfirm: (Boolean) -> Unit) {
    val done = step.done ?: manualDone
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    else Text("$number", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(10.dp))
                Text(step.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (step.required) "必做" else "推荐", style = MaterialTheme.typography.labelSmall,
                    color = if (step.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(step.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.confirmKey != null) {
                    Checkbox(checked = manualDone, onCheckedChange = onConfirm)
                    Text("我已设置", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(if (done) "已完成" else "未完成", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                if (step.action.isNotEmpty()) FilledTonalButton(onClick = onAction, enabled = step.done != true) { Text(step.action) }
            }
        }
    }
}
