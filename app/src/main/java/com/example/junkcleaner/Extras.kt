package com.example.junkcleaner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var scanOn: Set<String>
        get() = HashSet(sp.getStringSet("scanOn", Category.values().filter { it.defScan }.map { it.name }.toSet())!!)
        set(v) { sp.edit().putStringSet("scanOn", v).apply() }

    var checked: Set<String>
        get() = HashSet(sp.getStringSet("checked", Category.values().filter { it.defCheck }.map { it.name }.toSet())!!)
        set(v) { sp.edit().putStringSet("checked", v).apply() }

    var whitelist: Set<String>
        get() = HashSet(sp.getStringSet("white", emptySet())!!)
        set(v) { sp.edit().putStringSet("white", v).apply() }

    var customExt: String
        get() = sp.getString("customExt", "")!!
        set(v) { sp.edit().putString("customExt", v).apply() }

    var autoEnabled: Boolean
        get() = sp.getBoolean("autoEnabled", false)
        set(v) { sp.edit().putBoolean("autoEnabled", v).apply() }

    var autoDays: Int
        get() = sp.getInt("autoDays", 7)
        set(v) { sp.edit().putInt("autoDays", v).apply() }

    var lastAuto: String
        get() = sp.getString("lastAuto", "")!!
        set(v) { sp.edit().putString("lastAuto", v).apply() }

    fun config() = ScanConfig(toCats(scanOn), toCats(checked), parseExts(customExt), whitelist)
}

object AutoClean {
    private const val NAME = "auto_clean"

    fun schedule(ctx: Context, days: Int) {
        val req = PeriodicWorkRequestBuilder<CleanWorker>(days.toLong(), TimeUnit.DAYS)
            .setInitialDelay(days.toLong(), TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}

/** 定时任务：只删除“扫描已开启”且“默认勾选”的类型，白名单除外 */
class CleanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        if (!hasStoragePermission(applicationContext)) {
            prefs.lastAuto = "$time 未执行：没有文件权限"
            return Result.success()
        }
        val found = JunkScanner.scan(applicationContext, prefs.config()) { }
        var n = 0
        var freed = 0L
        found.filter { it.selected }.forEach {
            if (JunkScanner.delete(it)) { n++; freed += it.size }
        }
        prefs.lastAuto = "$time 自动清理 $n 项，释放 ${formatSize(freed)}"
        return Result.success()
    }
}
