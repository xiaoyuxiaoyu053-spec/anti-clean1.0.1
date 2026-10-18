package com.example.junkcleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

enum class Category(val label: String, val exts: Set<String>, val defScan: Boolean, val defCheck: Boolean) {
    CACHE("缓存文件", emptySet(), true, true),
    TEMP("临时 / 日志文件", setOf("tmp", "temp", "log", "bak", "old", "thumbdata", "dmp", "chk", "crdownload", "part"), true, true),
    EMPTY("空文件 / 空文件夹", emptySet(), true, true),
    APK("安装包", setOf("apk", "xapk", "apks"), true, true),
    EXE("Windows 程序", setOf("exe", "msi"), true, true),
    ARCHIVE("压缩包", setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz"), true, false),
    TEXT("文本文件", setOf("txt"), true, false),
    IMAGE("图片", setOf("png", "jpg", "jpeg", "gif", "webp", "bmp"), false, false),
    AUDIO("音频", setOf("mp3", "ogg", "wav", "flac", "aac", "m4a", "wma"), false, false),
    VIDEO("视频", setOf("mp4", "mkv", "avi", "mov", "flv", "3gp", "wmv"), false, false),
    CUSTOM("自定义后缀", emptySet(), true, false)
}

class JunkItem(val file: File, val size: Long, val category: Category, sel: Boolean) {
    var selected by mutableStateOf(sel)
}

class ScanConfig(
    val on: Set<Category>,
    val checked: Set<Category>,
    val custom: Set<String>,
    val white: Set<String>
)

fun toCats(names: Set<String>): Set<Category> =
    names.mapNotNull { n -> Category.values().firstOrNull { it.name == n } }.toSet()

fun parseExts(s: String): Set<String> =
    s.split(',', ';', ' ', '，', '、').map { it.trim().trimStart('.').lowercase() }
        .filter { it.isNotEmpty() }.toSet()

fun hasStoragePermission(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

/** 白名单匹配：带 / 的按路径(含子路径)匹配，不带 / 的按文件名或文件夹名匹配 */
fun isWhite(f: File, white: Set<String>): Boolean {
    if (white.isEmpty()) return false
    val p = f.absolutePath
    val n = f.name
    return white.any { e ->
        if (e.contains('/')) p == e || p.startsWith(e.trimEnd('/') + "/")
        else n.equals(e, ignoreCase = true)
    }
}

object JunkScanner {
    private val keepEmptyTop = setOf(
        "DCIM", "Download", "Pictures", "Music", "Movies", "Documents",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Android"
    )

    suspend fun scan(ctx: Context, cfg: ScanConfig, onProgress: (Int) -> Unit): List<JunkItem> =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]!!
            val out = ArrayList<JunkItem>()
            val root = Environment.getExternalStorageDirectory()
            var count = 0

            val extMap = HashMap<String, Category>()
            cfg.on.sortedBy { it.ordinal }.forEach { c ->
                val exts = if (c == Category.CUSTOM) cfg.custom else c.exts
                exts.forEach { extMap.putIfAbsent(it, c) }
            }

            // 缓存：本应用缓存 + Android/data/*/cache（Android 11+ 系统限制，可能读不到其他应用）
            if (Category.CACHE in cfg.on) {
                val cacheDirs = LinkedHashMap<String, File>()
                ctx.cacheDir?.let { cacheDirs[it.absolutePath] = it }
                ctx.externalCacheDir?.let { cacheDirs[it.absolutePath] = it }
                File(root, "Android/data").listFiles()?.forEach { app ->
                    val c = File(app, "cache")
                    if (c.isDirectory) cacheDirs[c.absolutePath] = c
                }
                for (c in cacheDirs.values) {
                    job.ensureActive()
                    if (isWhite(c, cfg.white)) continue
                    val s = dirSize(c)
                    if (s > 0) out += JunkItem(c, s, Category.CACHE, Category.CACHE in cfg.checked)
                }
            }

            fun walk(dir: File) {
                job.ensureActive()
                val children = dir.listFiles() ?: return
                if (children.isEmpty()) {
                    val topLevel = dir.parentFile == root
                    if (Category.EMPTY in cfg.on && dir != root && !(topLevel && dir.name in keepEmptyTop)) {
                        out += JunkItem(dir, 0, Category.EMPTY, Category.EMPTY in cfg.checked)
                    }
                    return
                }
                for (f in children) {
                    if (isWhite(f, cfg.white)) continue
                    if (f.isDirectory) {
                        if (f.canonicalPath != f.absolutePath) continue // 跳过符号链接
                        val name = f.name
                        if (name == "Android") continue
                        if (name.startsWith(".")) {
                            if ((name == ".thumbnails" || name == ".cache") && Category.CACHE in cfg.on) {
                                val s = dirSize(f)
                                if (s > 0) out += JunkItem(f, s, Category.CACHE, Category.CACHE in cfg.checked)
                            }
                            continue
                        }
                        walk(f)
                    } else {
                        count++
                        if (count % 50 == 0) onProgress(count)
                        val len = f.length()
                        val cat = extMap[f.extension.lowercase()]
                            ?: if (len == 0L && !f.name.startsWith(".") && Category.EMPTY in cfg.on) Category.EMPTY else null
                        if (cat != null) out += JunkItem(f, len, cat, cat in cfg.checked)
                    }
                }
            }
            walk(root)
            onProgress(count)
            out
        }

    private fun dirSize(d: File): Long =
        d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(item: JunkItem): Boolean = try {
        val f = item.file
        when {
            item.category == Category.CACHE && f.isDirectory -> {
                f.listFiles()?.forEach { it.deleteRecursively() }
                true
            }
            f.isDirectory -> f.deleteRecursively()
            else -> f.delete()
        }
    } catch (e: Exception) {
        false
    }
}
