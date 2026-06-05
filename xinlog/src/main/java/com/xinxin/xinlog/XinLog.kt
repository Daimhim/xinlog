package com.xinxin.xinlog

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineExceptionHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 鑫鑫商店日志 SDK（xinlog）。零三方依赖、自动初始化、自动抓崩溃/ANR，并提供主动上报。
 *
 * 接入(方案A)：build.gradle 加一行依赖即可，SDK 自动初始化并捕获崩溃；想记自定义日志/主动上报再调下面方法。
 *   XinLog.i/w/e(tag,msg)          // 普通/错误日志(批量发)
 *   XinLog.report(level,tag,msg)   // 主动上报一条并立即发送
 *   XinLog.flush()                 // 立即发送当前缓存
 */
object XinLog {

    // ---- 可配置项（接入方可在初始化后调整）----
    @Volatile var endpoint = "https://daimhim.top/xinxin/api/v1/logs"
    @Volatile var enabled = true            // 总开关
    @Volatile var captureCrash = true       // 抓 Java 崩溃
    @Volatile var captureAnr = true         // 抓 ANR
    @Volatile var anrTimeoutMs = 5_000L     // 看门狗阈值(API<30)
    @Volatile var batchSize = 20            // 攒够多少条触发上报
    @Volatile var intervalMs = 60_000L      // 周期上报间隔
    @Volatile var maxEntries = 1000         // 本地缓存最多条数
    @Volatile var maxBytes = 1_000_000L     // 本地缓存最多字节(~1MB)
    @Volatile var maxAgeMs = 7L * 24 * 3600 * 1000 // 本地缓存最长保留(7天)

    private const val PREFS = "xinlog"
    private lateinit var app: Context
    private var pkg = ""
    private var versionName = ""
    private var versionCode = 0
    private var installId = ""
    private var device = ""
    private lateinit var file: File
    private val lock = Any()
    private val pending = AtomicInteger(0)
    private var started = false
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xinlog").apply { isDaemon = true }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        pkg = app.packageName
        try {
            val pi = app.packageManager.getPackageInfo(pkg, 0)
            versionName = pi.versionName ?: ""
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Throwable) {}
        device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
        installId = loadInstallId()
        file = File(app.filesDir, "xinlog/pending.ndjson").also { it.parentFile?.mkdirs() }

        if (captureCrash) installCrashHandler()
        if (captureAnr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reportExitReasons() // 方案2
            else startWatchdog()                                                     // 方案1
        }
        // 启动补发上次残留(崩溃/离线日志)，并启动周期上报
        scheduleFlush(2_000)
        scheduler.scheduleWithFixedDelay({ doFlush() }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    // ---- 对外 API ----
    fun i(tag: String, msg: String) = enqueue("info", tag, msg, "")
    fun w(tag: String, msg: String) = enqueue("warn", tag, msg, "")
    fun e(tag: String, msg: String, t: Throwable? = null) {
        enqueue("error", tag, msg, t?.let { stack(it) } ?: "")
        scheduleFlush(1_000)
    }

    /** 主动上报一条并立即发送。 */
    fun report(level: String, tag: String, message: String, stacktrace: String = "") {
        enqueue(level, tag, message, stacktrace)
        scheduleFlush(0)
    }

    /** 立即把本地缓存的日志全部发出去。 */
    fun flush() = scheduleFlush(0)

    /**
     * 协程异常处理器：把未捕获的协程异常上报，减少 launch 漏报。
     * 用法：`scope.launch(XinLog.coroutineHandler()) { ... }`
     * 或   `CoroutineScope(Dispatchers.Main + XinLog.coroutineHandler())`
     * 注：`async` 的异常仍需在 `await()` 处处理；建议配合 `supervisorScope` 隔离子协程失败。
     */
    fun coroutineHandler(tag: String = "coroutine"): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, t ->
            report("error", tag, "未捕获协程异常: ${t.message}", t.stackTraceToString())
        }

    // ---- 入队（异步落盘，避免主线程 IO）----
    private fun enqueue(level: String, tag: String, msg: String, stack: String) {
        if (!enabled || !started) return
        val line = entryJson(level, tag, msg, stack)
        scheduler.execute {
            appendLine(line)
            if (pending.incrementAndGet() >= batchSize) doFlush()
            else if (file.length() > maxBytes) doFlush() // 离线堆积超限时压缩
        }
    }

    private fun entryJson(level: String, tag: String, msg: String, stack: String): String =
        JSONObject()
            .put("level", level)
            .put("tag", cut(tag, 200))
            .put("message", cut(msg, 16 * 1024))
            .put("stacktrace", cut(stack, 64 * 1024))
            .put("ts", System.currentTimeMillis())
            .toString()

    private fun appendLine(line: String) {
        synchronized(lock) { try { file.appendText(line + "\n") } catch (_: Throwable) {} }
    }

    private fun scheduleFlush(delayMs: Long) {
        try { scheduler.schedule({ doFlush() }, delayMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
    }

    // ---- 上报（在 scheduler 单线程上执行；网络在锁外）----
    private fun doFlush() {
        if (!enabled) return
        val batch: List<String>
        synchronized(lock) {
            if (!file.exists()) return
            val all = try { file.readLines() } catch (_: Throwable) { return }
            if (all.isEmpty()) { pending.set(0); return }
            batch = capped(all)
            try { file.writeText("") } catch (_: Throwable) {} // 取走这批
        }
        pending.set(0)
        var idx = 0
        var ok = true
        while (idx < batch.size && ok) {
            val chunk = batch.subList(idx, minOf(idx + 50, batch.size))
            ok = postChunk(chunk)
            if (ok) idx += chunk.size
        }
        if (idx < batch.size) { // 没发完：把剩余放回(头部)
            val remaining = batch.subList(idx, batch.size)
            synchronized(lock) {
                val cur = try { if (file.exists()) file.readText() else "" } catch (_: Throwable) { "" }
                try { file.writeText(remaining.joinToString("\n") + "\n" + cur) } catch (_: Throwable) {}
            }
        }
    }

    /** 按 保留期/字节/条数 裁剪，丢最旧、保最新（FIFO）。 */
    private fun capped(all: List<String>): List<String> {
        val now = System.currentTimeMillis()
        val byAge = all.filter { now - tsOf(it, now) <= maxAgeMs }
        val out = ArrayList<String>()
        var bytes = 0L
        for (i in byAge.indices.reversed()) {
            if (out.size >= maxEntries) break
            val b = byAge[i].length.toLong() + 1
            if (bytes + b > maxBytes) break
            out.add(byAge[i]); bytes += b
        }
        out.reverse()
        return out
    }

    private fun postChunk(lines: List<String>): Boolean {
        return try {
            val body = JSONObject()
                .put("package_name", pkg)
                .put("install_id", installId)
                .put("version_name", versionName)
                .put("version_code", versionCode)
                .put("device", device)
            val arr = JSONArray()
            for (l in lines) try { arr.put(JSONObject(l)) } catch (_: Throwable) {}
            body.put("entries", arr)

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("ngrok-skip-browser-warning", "true")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Throwable) { false }
    }

    // ---- 崩溃（全版本）----
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            try { appendLine(entryJson("crash", t.name, ex.toString(), stack(ex))) } catch (_: Throwable) {}
            prev?.uncaughtException(t, ex)
        }
    }

    // ---- ANR 方案1：看门狗（API<30）----
    private fun startWatchdog() {
        val mainThread = Looper.getMainLooper().thread
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            while (started && enabled) {
                val ticked = AtomicBoolean(false)
                mainHandler.post { ticked.set(true) }
                try { Thread.sleep(anrTimeoutMs) } catch (_: InterruptedException) { break }
                if (!ticked.get() && !Debug.isDebuggerConnected()) {
                    enqueue("anr", "ANR", "主线程阻塞≥${anrTimeoutMs}ms", dumpThreads(mainThread))
                    scheduleFlush(0)
                    // 等主线程恢复，避免重复刷
                    while (started && !ticked.get()) {
                        try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }, "xinlog-anr").apply { isDaemon = true; start() }
    }

    private fun dumpThreads(main: Thread): String {
        val sb = StringBuilder("=== main ===\n")
        for (f in main.stackTrace) sb.append("\tat ").append(f).append('\n')
        try {
            for ((th, frames) in Thread.getAllStackTraces()) {
                if (th === main) continue
                sb.append("\n=== ").append(th.name).append(" ===\n")
                for (f in frames.take(20)) sb.append("\tat ").append(f).append('\n')
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }

    // ---- ANR 方案2：ApplicationExitInfo（API>=30，下次启动补报真实 ANR/原生崩溃）----
    @SuppressLint("NewApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportExitReasons() {
        scheduler.execute {
            try {
                val am = app.getSystemService(ActivityManager::class.java) ?: return@execute
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastTs = prefs.getLong("last_exit_ts", 0L)
                var maxTs = lastTs
                for (info in am.getHistoricalProcessExitReasons(app.packageName, 0, 0)) {
                    if (info.timestamp <= lastTs) continue
                    if (info.timestamp > maxTs) maxTs = info.timestamp
                    val level = levelForExit(info.reason) ?: continue // 正常退出/Java崩溃 不报
                    var trace = ""
                    try { info.traceInputStream?.use { trace = it.bufferedReader().readText() } } catch (_: Throwable) {}
                    val msg = buildString {
                        append(exitReasonName(info.reason))
                        info.description?.let { if (it.isNotBlank()) append(": ").append(it) }
                        append(" (importance=").append(info.importance).append(')')
                    }
                    val o = JSONObject()
                        .put("level", level).put("tag", "system")
                        .put("message", cut(msg, 16 * 1024))
                        .put("stacktrace", cut(trace, 64 * 1024))
                        .put("ts", info.timestamp)
                    appendLine(o.toString())
                }
                prefs.edit().putLong("last_exit_ts", maxTs).apply()
            } catch (_: Throwable) {}
        }
    }

    /** 退出原因 → 上报级别；返回 null 表示不上报(正常退出 / Java崩溃由handler负责)。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun levelForExit(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "warn"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "error"
        else -> null // REASON_CRASH(Java) / USER_REQUESTED / USER_STOPPED / EXIT_SELF / OTHER / UNKNOWN ...
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存杀死(LMK)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程死亡"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        else -> "退出原因#$reason"
    }

    // ---- 辅助 ----
    private fun loadInstallId(): String {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = p.getString("install_id", null)
        if (id.isNullOrEmpty()) { id = UUID.randomUUID().toString(); p.edit().putString("install_id", id).apply() }
        return id
    }

    private fun tsOf(line: String, def: Long): Long =
        try { JSONObject(line).optLong("ts", def) } catch (_: Throwable) { def }

    private fun stack(t: Throwable): String {
        val sw = StringWriter(); t.printStackTrace(PrintWriter(sw)); return sw.toString()
    }

    private fun cut(s: String, n: Int): String = if (s.length > n) s.substring(0, n) else s
}
