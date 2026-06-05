# xinlog — 鑫鑫商店应用日志 SDK

给上架到鑫鑫商店的 app 用的**极小、零三方依赖**日志 SDK：加一行依赖即可**自动上报崩溃 / ANR**，也可主动上报自定义日志。日志汇总到鑫鑫商店后台「日志管理」，由你下载分析。

## 接入（方案 A：一行依赖）

1. 根 `settings.gradle(.kts)` 的仓库里加 JitPack：
```kotlin
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```
2. app 模块 `build.gradle(.kts)` 加依赖：
```kotlin
implementation("com.github.daimhim:xinlog:1.0")
```
3. **完成**。SDK 会自动初始化(无需写 init)，自动捕获崩溃和 ANR 并上报。`minSdk 21`，`INTERNET` 权限随 SDK 自动合并。

## 主动上报 / 自定义日志（可选）
```kotlin
XinLog.i("Net", "请求成功")               // 普通(批量发)
XinLog.w("Cache", "命中率偏低")
XinLog.e("DB", "查询失败", e)             // 错误(尽快发)
XinLog.report("error", "Pay", "支付校验失败", e.stackTraceToString()) // 主动报一条并立即发送
XinLog.flush()                            // 立即把缓存全部发出
```

## 自动采集（无需关心）
- 包名、versionName/Code、机型/系统：自动；
- **匿名安装ID**：首次随机 UUID 存本地(不含任何个人信息，卸载重装即变)；
- **Java/Kotlin 崩溃**：全局捕获，落盘后下次启动补发；
- **ANR**：Android 11+ 用系统 `ApplicationExitInfo` 读真实 ANR(含官方 trace)；11 以下用看门狗(主线程阻塞≥5s)抓主线程+全线程堆栈；
- **原生崩溃 / 异常退出(Android 11+)**：通过 `ApplicationExitInfo` 上报 原生崩溃、系统低内存杀(LMK)、资源占用过高、被信号杀、依赖进程死亡、初始化失败(用户主动退出/划掉等正常情况不报)。

## 协程异常（推荐）
未捕获的协程异常默认不一定能抓到，SDK 提供一个处理器，挂到你的协程作用域即可上报：
```kotlin
scope.launch(XinLog.coroutineHandler()) { /* ... */ }
// 或
val scope = CoroutineScope(Dispatchers.Main + XinLog.coroutineHandler())
```
注：`async` 的异常仍需在 `await()` 处处理；建议用 `supervisorScope` 隔离子协程失败。

## 上报与缓存策略（默认，均可配）
- 普通日志攒批发送(满 20 条 / 60 秒 / 进入后台)；错误、主动上报尽快发；崩溃下次启动补发；
- 仅有网时发，失败保留重试；
- 本地缓存上限 **≈1MB / 1000 条 / 7 天**，超出丢最旧(FIFO)，崩溃优先保留；
- 单条 message ≤16KB、stacktrace ≤64KB(超长截断)。

```kotlin
// 可选配置（任意时机，例如 Application.onCreate）
XinLog.enabled = true          // 总开关
XinLog.captureCrash = true
XinLog.captureAnr = true
XinLog.anrTimeoutMs = 5_000
XinLog.batchSize = 20
XinLog.intervalMs = 60_000
XinLog.maxEntries = 1000
XinLog.maxBytes = 1_000_000
XinLog.maxAgeMs = 7L*24*3600*1000
```

## 已知限制
- **Android ≤10 的原生(NDK/C++)崩溃**不捕获(11+ 由系统 `ApplicationExitInfo` 覆盖)；如需全版本原生捕获要另接 native 模块；
- 被 `try/catch` 吞掉的异常不会自动上报，需主动 `XinLog.e/report`；
- 未 `await` 的 `async` 协程异常可能漏报；
- OOM 时写日志可能因内存耗尽而失败(best-effort)；
- 离线缓存的日志若用户始终不再打开 app，则发不出去。

## 隐私
仅采集匿名安装ID + 日志内容(崩溃/ANR/你主动记录的)，不收集姓名/手机号等个人身份信息。请在你 app 的隐私政策中说明，并按需提供关闭入口(`XinLog.enabled = false`)。

## 上报接口（如需自行对接，不用 SDK 也可）
```
POST https://daimhim.top/xinxin/api/v1/logs   Content-Type: application/json
{ "package_name":"你的包名", "install_id":"匿名uuid", "version_name":"1.0.0",
  "version_code":1, "device":"机型/系统",
  "entries":[ {"level":"crash|error|warn|info","tag":"","message":"","stacktrace":"","ts":1690000000000} ] }
```
