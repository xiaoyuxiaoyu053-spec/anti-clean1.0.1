package com.example.junkcleaner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Blue = Color(0xFF2F80ED)
private val Gray = Color(0xFF8A94A6)
private val CardBg = Color(0xFFF5F7FA)
private val Ink = Color(0xFF1F2937)
private val Red = Color(0xFFEB5757)

class MainActivity : ComponentActivity() {
    private val perm = mutableStateOf(false)
    private val legacyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perm.value = hasStoragePermission(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perm.value = hasStoragePermission(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.1"
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Blue, background = Color.White, surface = Color.White)
            ) {
                App(perm.value, ::requestPerm, version)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        perm.value = hasStoragePermission(this)
    }

    private fun requestPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            try { startActivity(i) } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }
}

private enum class Screen { HOME, RESULT, SETTINGS }

@Composable
fun App(hasPerm: Boolean, onRequestPerm: () -> Unit, version: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }

    var scanOn by remember { mutableStateOf(prefs.scanOn) }
    var checked by remember { mutableStateOf(prefs.checked) }
    var custom by remember { mutableStateOf(prefs.customExt) }
    var white by remember { mutableStateOf(prefs.whitelist) }
    var autoOn by remember { mutableStateOf(prefs.autoEnabled) }
    var autoDays by remember { mutableIntStateOf(prefs.autoDays) }
    var lastAuto by remember { mutableStateOf(prefs.lastAuto) }

    var screen by remember { mutableStateOf(Screen.HOME) }
    val junk = remember { mutableStateListOf<JunkItem>() }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableIntStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var autoConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var delDone by remember { mutableIntStateOf(0) }
    var delTotal by remember { mutableIntStateOf(0) }
    val collapsed = remember { mutableStateListOf<Category>() }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun setScan(c: Category, v: Boolean) {
        scanOn = if (v) scanOn + c.name else scanOn - c.name
        prefs.scanOn = scanOn
    }
    fun setCheck(c: Category, v: Boolean) {
        checked = if (v) checked + c.name else checked - c.name
        prefs.checked = checked
    }
    fun addWhite(raw: String) {
        var e = raw.trim()
        if (e.isEmpty()) return
        if (e.contains('/') && !e.startsWith("/")) {
            e = Environment.getExternalStorageDirectory().absolutePath + "/" + e.trimStart('/')
        }
        e = if (e.length > 1) e.trimEnd('/') else e
        white = white + e
        prefs.whitelist = white
    }
    fun removeWhite(e: String) {
        white = white - e
        prefs.whitelist = white
    }

    fun startScan() {
        if (scanOn.isEmpty()) { toast("请至少开启一种扫描类型"); return }
        screen = Screen.RESULT
        scanning = true
        junk.clear()
        scanned = 0
        message = null
        toast("正在扫描")
        scanJob = scope.launch {
            val cfg = ScanConfig(toCats(scanOn), toCats(checked), parseExts(custom), white)
            val r = JunkScanner.scan(ctx, cfg) { scanned = it }
            junk.addAll(r)
            scanning = false
            toast("扫描成功")
        }
    }

    fun doDelete() {
        val targets = junk.filter { it.selected }
        deleting = true
        delDone = 0
        delTotal = targets.size
        scope.launch {
            val (okList, freed) = withContext(Dispatchers.IO) {
                var f = 0L
                val ok = ArrayList<JunkItem>()
                targets.forEach {
                    if (JunkScanner.delete(it)) { f += it.size; ok += it }
                    delDone++
                }
                ok to f
            }
            junk.removeAll(okList.toSet())
            deleting = false
            message = "已清理 ${okList.size} 项，释放 ${formatSize(freed)}"
        }
    }

    fun back() {
        if (scanning) { scanJob?.cancel(); scanning = false }
        screen = Screen.HOME
    }
    BackHandler(enabled = screen != Screen.HOME && !deleting) { back() }

    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (screen != Screen.HOME) {
                Text("‹", fontSize = 32.sp, color = Blue,
                    modifier = Modifier.clickable { back() }.padding(end = 14.dp))
            }
            Text(
                when (screen) { Screen.HOME -> "垃圾清理"; Screen.RESULT -> "扫描结果"; Screen.SETTINGS -> "设置" },
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink
            )
            Spacer(Modifier.weight(1f))
            if (screen == Screen.HOME && hasPerm) {
                Text("设置", color = Blue, fontSize = 15.sp,
                    modifier = Modifier.clickable { screen = Screen.SETTINGS }.padding(end = 14.dp, top = 6.dp, bottom = 6.dp))
            }
            Text("v$version", fontSize = 13.sp, color = Gray)
        }
        Spacer(Modifier.height(12.dp))

        Column(Modifier.weight(1f)) {
            when {
                !hasPerm -> Center {
                    Text("需要“所有文件访问”权限", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("仅用于查找并清理垃圾文件，\n不会扫描系统文件。", color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onRequestPerm, shape = RoundedCornerShape(24.dp)) { Text("授予权限") }
                }

                screen == Screen.HOME -> HomeContent(
                    scanOn = scanOn, checked = checked, custom = custom, white = white,
                    onScan = ::setScan, onCheck = ::setCheck,
                    onCustom = { custom = it; prefs.customExt = it },
                    onAddWhite = ::addWhite, onRemoveWhite = ::removeWhite,
                    onStart = ::startScan
                )

                screen == Screen.SETTINGS -> SettingsContent(
                    autoOn = autoOn, autoDays = autoDays, lastAuto = lastAuto,
                    onToggle = { v ->
                        if (v) autoConfirm = true else {
                            autoOn = false; prefs.autoEnabled = false; AutoClean.cancel(ctx)
                        }
                    },
                    onDays = { d ->
                        autoDays = d; prefs.autoDays = d
                        if (autoOn) AutoClean.schedule(ctx, d)
                    }
                )

                else -> ResultContent(
                    junk = junk, scanning = scanning, scanned = scanned, message = message,
                    collapsed = collapsed,
                    onWhite = { item -> addWhite(item.file.absolutePath); junk.remove(item) },
                    onRescan = ::startScan, onClean = { confirm = true }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "⚠ 安全提示：本应用仅用于清理存储空间。清理后如手机出现问题，本应用不负责，请提前备份重要文件。",
            fontSize = 11.sp, color = Gray, textAlign = TextAlign.Center, lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
    }

    if (confirm) {
        val sel = junk.filter { it.selected }
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text("确认删除？") },
            text = { Text("将删除 ${sel.size} 项，共 ${formatSize(sel.sumOf { it.size })}。\n删除后无法恢复，未勾选的项目不会被删除。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; doDelete() }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }

    if (autoConfirm) {
        AlertDialog(
            onDismissRequest = { autoConfirm = false },
            containerColor = Color.White,
            title = { Text("开启定时自动删除？") },
            text = {
                Text("开启后每隔 $autoDays 天自动扫描，并在不再询问的情况下，删除“扫描类型”中已开启且勾选了“默认勾选”的文件（白名单除外）。删除后无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    autoConfirm = false
                    autoOn = true; prefs.autoEnabled = true
                    AutoClean.schedule(ctx, autoDays)
                }) { Text("开启", color = Red) }
            },
            dismissButton = { TextButton(onClick = { autoConfirm = false }) { Text("取消") } }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { },
            containerColor = Color.White,
            title = { Text("正在删除…") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = if (delTotal == 0) 0f else delDone.toFloat() / delTotal,
                        color = Blue, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$delDone / $delTotal", fontSize = 13.sp, color = Gray)
                }
            },
            confirmButton = { }
        )
    }
}

@Composable
private fun ColumnScope.HomeContent(
    scanOn: Set<String>, checked: Set<String>, custom: String, white: Set<String>,
    onScan: (Category, Boolean) -> Unit, onCheck: (Category, Boolean) -> Unit,
    onCustom: (String) -> Unit, onAddWhite: (String) -> Unit, onRemoveWhite: (String) -> Unit,
    onStart: () -> Unit
) {
    var wlInput by remember { mutableStateOf("") }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("扫描类型", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("开关打开 = 扫描，关闭 = 拒绝扫描。“默认勾选”的类型会在结果里预先勾上，也是定时清理会自动删除的类型。",
            fontSize = 12.sp, color = Gray, lineHeight = 17.sp)
        Text("图片、音频、视频多半是你自己的文件，请谨慎勾选。", fontSize = 12.sp, color = Red)
        Spacer(Modifier.height(8.dp))

        Category.values().forEach { cat ->
            val on = cat.name in scanOn
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(CardBg, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cat.label, fontWeight = FontWeight.Medium, color = Ink)
                        if (cat.exts.isNotEmpty()) {
                            Text(cat.exts.joinToString(" · "), fontSize = 11.sp, color = Gray)
                        }
                    }
                    Switch(checked = on, onCheckedChange = { onScan(cat, it) })
                }
                if (on) {
                    val chk = cat.name in checked
                    Row(Modifier.clickable { onCheck(cat, !chk) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chk, onCheckedChange = { onCheck(cat, it) })
                        Text("默认勾选 / 定时自动删除", fontSize = 12.sp, color = Gray)
                    }
                }
                if (cat == Category.CUSTOM && on) {
                    OutlinedTextField(
                        value = custom, onValueChange = onCustom, singleLine = true,
                        placeholder = { Text("后缀，用逗号分隔，如 pdf,doc", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("白名单", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("白名单里的文件或文件夹不会被扫描，也不会被删除。可填完整路径、相对存储根目录的路径（如 Download/重要），或单个文件名（如 keep.zip，任何位置同名都会跳过）。也可以在扫描结果里点“白名单”直接加入。",
            fontSize = 12.sp, color = Gray, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = wlInput, onValueChange = { wlInput = it }, singleLine = true,
                placeholder = { Text("路径或文件名", fontSize = 13.sp) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAddWhite(wlInput); wlInput = "" }, shape = RoundedCornerShape(20.dp)) { Text("添加") }
        }
        white.sorted().forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("移除", fontSize = 13.sp, color = Red,
                    modifier = Modifier.clickable { onRemoveWhite(e) }.padding(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onStart, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) { Text("开始扫描", fontSize = 16.sp) }
}

@Composable
private fun ColumnScope.SettingsContent(
    autoOn: Boolean, autoDays: Int, lastAuto: String,
    onToggle: (Boolean) -> Unit, onDays: (Int) -> Unit
) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("定时扫描并自动删除", fontWeight = FontWeight.Medium, color = Ink, fontSize = 16.sp)
                    Text("默认关闭，需要你手动开启", fontSize = 12.sp, color = Gray)
                }
                Switch(checked = autoOn, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每隔", color = Ink)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { if (autoDays > 1) onDays(autoDays - 1) },
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp)) { Text("−", fontSize = 18.sp) }
                Text("  $autoDays 天  ", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Blue)
                OutlinedButton(onClick = { if (autoDays < 30) onDays(autoDays + 1) },
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp)) { Text("+", fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Text("执行一次", color = Ink)
            }
            Spacer(Modifier.height(12.dp))
            Text("自动清理只会删除：在首页“扫描类型”中已开启，并且勾选了“默认勾选”的类型（白名单除外）。系统的省电策略可能让执行时间有所延后。",
                fontSize = 12.sp, color = Gray, lineHeight = 17.sp)
            if (lastAuto.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("上次自动清理：$lastAuto", fontSize = 12.sp, color = Blue)
            }
        }
    }
}

@Composable
private fun ColumnScope.ResultContent(
    junk: List<JunkItem>, scanning: Boolean, scanned: Int, message: String?,
    collapsed: MutableList<Category>,
    onWhite: (JunkItem) -> Unit, onRescan: () -> Unit, onClean: () -> Unit
) {
    if (scanning) {
        Center {
            LinearProgressIndicator(color = Blue, modifier = Modifier.fillMaxWidth(0.7f))
            Spacer(Modifier.height(20.dp))
            Text("正在扫描…", fontSize = 16.sp, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text("已检查 $scanned 个文件", fontSize = 13.sp, color = Gray)
        }
        return
    }
    val selected = junk.filter { it.selected }
    val totalSize = junk.sumOf { it.size }
    val selectedSize = selected.sumOf { it.size }

    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Text("共发现垃圾", fontSize = 13.sp, color = Gray)
        Text(formatSize(totalSize), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Blue)
        Spacer(Modifier.height(4.dp))
        Text("已选择 ${selected.size} 项 · ${formatSize(selectedSize)}　（共 ${junk.size} 项）",
            fontSize = 13.sp, color = Gray)
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text("✓ $it", fontSize = 13.sp, color = Color(0xFF27AE60))
        }
    }
    Spacer(Modifier.height(8.dp))

    if (junk.isEmpty()) {
        Center { Text("没有发现垃圾 ✨", color = Gray) }
    } else {
        LazyColumn(Modifier.weight(1f)) {
            Category.values().forEach { cat ->
                val list = junk.filter { it.category == cat }
                if (list.isEmpty()) return@forEach
                item(key = "h_${cat.name}") {
                    val state = when {
                        list.all { it.selected } -> ToggleableState.On
                        list.none { it.selected } -> ToggleableState.Off
                        else -> ToggleableState.Indeterminate
                    }
                    val open = cat !in collapsed
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (open) collapsed.add(cat) else collapsed.remove(cat) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TriStateCheckbox(state = state, onClick = {
                            val to = state != ToggleableState.On
                            list.forEach { it.selected = to }
                        })
                        Column(Modifier.weight(1f)) {
                            Text(cat.label, fontWeight = FontWeight.Medium, color = Ink)
                            Text("${list.size} 项", fontSize = 12.sp, color = Gray)
                        }
                        Text(formatSize(list.sumOf { it.size }), color = Ink, fontSize = 14.sp)
                        Text(if (open) "  ▾" else "  ▸", color = Gray)
                    }
                }
                if (cat !in collapsed) {
                    items(list, key = { it.file.absolutePath }) { item ->
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.selected, onCheckedChange = { c -> item.selected = c })
                            Column(Modifier.weight(1f)) {
                                Text(item.file.name, fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.file.parent ?: "", fontSize = 11.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatSize(item.size), fontSize = 12.sp, color = Gray)
                            Text("白名单", fontSize = 11.sp, color = Blue,
                                modifier = Modifier.clickable { onWhite(item) }.padding(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                }
                item(key = "d_${cat.name}") { HorizontalDivider(color = CardBg) }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRescan, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(48.dp)) {
            Text("重新扫描")
        }
        Button(
            onClick = onClean, enabled = selected.isNotEmpty(), shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f).height(48.dp)
        ) { Text("清理所选 (${formatSize(selectedSize)})") }
    }
}

@Composable
private fun ColumnScope.Center(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}
