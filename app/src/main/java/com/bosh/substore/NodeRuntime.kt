package com.bosh.substore

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 负责把 assets 里的 Sub-Store 后端 bundle + 前端释放到私有目录，
 * 并用内嵌的 Termux Node 二进制（jniLibs 里的 libnodebin.so）把后端跑起来。
 */
object NodeRuntime {
    const val PORT = 3000
    const val BASE_URL = "http://127.0.0.1:3000"
    // 内置组件版本；升级内置组件时同步改 RUNTIME_VERSION 触发重新释放
    const val BUNDLED_BACKEND_VERSION = "2.42.6"
    const val BUNDLED_FRONTEND_VERSION = "2.34.1"
    const val NODE_VERSION = "26.4.0"
    const val RUNTIME_VERSION = "backend-2.42.6_frontend-2.34.1_node-26.4.0_v5"

    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    @Volatile var state: State = State.STOPPED
        private set

    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun setState(s: State) {
        state = s
        for (l in listeners) try { l(s) } catch (_: Throwable) {}
    }

    fun isProcessAlive(): Boolean = process?.isAlive == true
    fun isStopRequested(): Boolean = stopRequested

    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime")
    fun dataDir(ctx: Context): File = File(ctx.filesDir, "data")
    fun logFile(ctx: Context): File = File(ctx.filesDir, "logs/node.log")
    fun bundleFile(ctx: Context): File = File(runtimeDir(ctx), "sub-store.bundle.js")
    fun frontendDir(ctx: Context): File = File(runtimeDir(ctx), "frontend")

    private fun dynamicMarker(ctx: Context): File = File(runtimeDir(ctx), "DYNAMIC.txt")

    /** 在线更新后的动态版本（backend to frontend）；未更新过时为 null to null。 */
    fun dynamicVersions(ctx: Context): Pair<String?, String?> {
        val m = dynamicMarker(ctx)
        if (!m.exists()) return null to null
        var b: String? = null
        var f: String? = null
        for (part in m.readText().trim().split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                if (kv[0] == "backend") b = kv[1]
                if (kv[0] == "frontend") f = kv[1]
            }
        }
        return b to f
    }

    fun effectiveBackendVersion(ctx: Context): String =
        dynamicVersions(ctx).first ?: BUNDLED_BACKEND_VERSION

    fun effectiveFrontendVersion(ctx: Context): String =
        dynamicVersions(ctx).second ?: BUNDLED_FRONTEND_VERSION

    fun isDynamic(ctx: Context): Boolean = dynamicMarker(ctx).exists()

    /**
     * 释放 assets/runtime 到 filesDir/runtime；版本一致且文件完整则跳过。
     * 完整性自愈：前端 index.html 缺失时只补释放前端（保留现有 bundle，含在线更新的后端，
     * 救 1.1.0 在线更新失败把前端弄丢的状态）；bundle 缺失/为空时整包重新释放。
     */
    @Synchronized
    fun ensureExtracted(ctx: Context) {
        val bundleOk = bundleFile(ctx).exists() && bundleFile(ctx).length() > 0
        // 前端完整性看入口三件套：只有 index.html、缺 index.js 时页面会黑屏但不报错
        val frontendOk = File(frontendDir(ctx), "index.html").exists() &&
            File(frontendDir(ctx), "index.js").exists() &&
            File(frontendDir(ctx), "registerSW.js").exists()
        if (bundleOk && frontendOk && Prefs.extractedVer(ctx) == RUNTIME_VERSION) return
        if (bundleOk && frontendOk && isDynamic(ctx)) return
        if (bundleOk && !frontendOk) {
            BootLog.append(ctx, "检测到前端文件缺失，自动补释放内置前端")
            frontendDir(ctx).deleteRecursively()
            copyAssetTree(ctx, "runtime/frontend", frontendDir(ctx))
            // 有动态标记时把其中前端版本改为内置版本，保证「关于」显示与实际一致
            val (dynBackend, _) = dynamicVersions(ctx)
            if (dynBackend != null) {
                dynamicMarker(ctx).writeText("backend=$dynBackend;frontend=$BUNDLED_FRONTEND_VERSION")
            }
            Prefs.setExtractedVer(ctx, RUNTIME_VERSION)
            return
        }
        val dir = runtimeDir(ctx)
        dir.deleteRecursively()
        dir.mkdirs()
        copyAssetTree(ctx, "runtime", dir)
        Prefs.setExtractedVer(ctx, RUNTIME_VERSION)
    }

    /**
     * 应用在线更新。newBundle/newFrontendDir 为 null 表示该端不更新，现有文件原样保留
     * （1.1.0 的教训：把现用目录当“新文件”传进来替换，会先自删再拷贝，直接把前端弄丢）。
     * 前端先拷到暂存目录、校验 index.html 后再整体更名替换，中途失败不动现有前端。
     */
    @Synchronized
    fun applyUpdate(ctx: Context, newBundle: File?, newFrontendDir: File?, backendVer: String, frontendVer: String) {
        stop(ctx)
        val dir = runtimeDir(ctx)
        dir.mkdirs()
        if (newBundle != null && newBundle.canonicalPath != bundleFile(ctx).canonicalPath) {
            newBundle.copyTo(bundleFile(ctx), overwrite = true)
        }
        if (newFrontendDir != null && newFrontendDir.canonicalPath != frontendDir(ctx).canonicalPath) {
            val fe = frontendDir(ctx)
            val staging = File(dir, "frontend.new")
            staging.deleteRecursively()
            newFrontendDir.copyRecursively(staging, overwrite = true)
            require(File(staging, "index.html").exists()) { "新前端缺少 index.html，已放弃替换" }
            fe.deleteRecursively()
            check(staging.renameTo(fe)) { "前端目录替换失败" }
        }
        dynamicMarker(ctx).writeText("backend=$backendVer;frontend=$frontendVer")
        Prefs.setExtractedVer(ctx, RUNTIME_VERSION)
        BootLog.append(ctx, "已应用动态更新：后端 $backendVer / 前端 $frontendVer")
    }

    /** 恢复 APK 内置版本（清掉动态更新文件，下次启动重新释放）。 */
    @Synchronized
    fun resetToBundled(ctx: Context) {
        stop(ctx)
        runtimeDir(ctx).deleteRecursively()
        Prefs.setExtractedVer(ctx, "")
        BootLog.append(ctx, "已恢复内置版本，下次启动将重新释放 assets")
    }

    private fun copyAssetTree(ctx: Context, assetPath: String, outDir: File) {
        val am = ctx.assets
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            try {
                am.open(assetPath).use { input ->
                    outDir.outputStream().use { input.copyTo(it) }
                }
            } catch (_: Throwable) {
                outDir.mkdirs() // 空目录：list 为空且 open 失败，按目录处理
            }
            return
        }
        outDir.mkdirs()
        for (child in children) {
            copyAssetTree(ctx, "$assetPath/$child", File(outDir, child))
        }
    }

    @Synchronized
    fun start(ctx: Context) {
        if (isProcessAlive()) { setState(State.RUNNING); return }
        stopRequested = false
        setState(State.STARTING)
        Thread {
            try {
                BootLog.append(ctx, "== NodeRuntime.start 开始 ==")
                ensureExtracted(ctx)
                BootLog.append(ctx, "运行时文件已释放到 ${runtimeDir(ctx)}")
                dataDir(ctx).mkdirs()
                val log = logFile(ctx)
                log.parentFile?.mkdirs()
                val nativeLib = ctx.applicationInfo.nativeLibraryDir
                val pb = ProcessBuilder(
                    "$nativeLib/libnodebin.so",
                    bundleFile(ctx).absolutePath
                )
                pb.directory(ctx.filesDir)
                pb.redirectErrorStream(true)
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                val env = pb.environment()
                env["PATH"] = "/system/bin:$nativeLib"
                env["LD_LIBRARY_PATH"] = nativeLib
                env["HOME"] = ctx.filesDir.absolutePath
                env["TMPDIR"] = File(ctx.filesDir, "tmp").apply { mkdirs() }.absolutePath
                env["TZ"] = "Asia/Shanghai"
                env["NODE_ENV"] = "production"
                env["SUB_STORE_BACKEND_API_HOST"] = "127.0.0.1"
                env["SUB_STORE_BACKEND_API_PORT"] = PORT.toString()
                env["SUB_STORE_DATA_BASE_PATH"] = dataDir(ctx).absolutePath
                env["SUB_STORE_FRONTEND_PATH"] = frontendDir(ctx).absolutePath
                env["SUB_STORE_BACKEND_MERGE"] = "true"
                env["SUB_STORE_FRONTEND_BACKEND_PATH"] = "/"
                // 关键：后端默认 CORS 白名单不含本机地址，带 Origin 的请求会被直接 403，
                // WebView 加载模块脚本与调用 API 都会带 Origin，必须把本机地址放行（1.0.0 黑屏根因）
                env["SUB_STORE_CORS_ALLOWED_ORIGINS"] =
                    "http://127.0.0.1:3000,https://sub-store.vercel.app,http://substore.stash,https://substore.stash"
                BootLog.append(ctx, "启动 node: $nativeLib/libnodebin.so ${bundleFile(ctx)}")
                val p = pb.start()
                process = p
                BootLog.append(ctx, "node 进程已创建 pid 状态 alive=${p.isAlive}")
                startMirror(ctx, p)
                // 等待接口就绪（最多 ~60 秒）
                var up = false
                for (i in 1..120) {
                    if (!p.isAlive) break
                    if (probe()) { up = true; break }
                    Thread.sleep(500)
                }
                if (up) {
                    BootLog.append(ctx, "后端接口已就绪（probe 成功）")
                    setState(State.RUNNING)
                } else if (p.isAlive) {
                    BootLog.append(ctx, "异常：node 进程存活但 60 秒内接口未就绪")
                    setState(State.FAILED) // 进程在但接口不通
                } else {
                    BootLog.append(ctx, "异常：node 进程在接口就绪前已退出")
                    setState(if (stopRequested) State.STOPPED else State.FAILED)
                }
                val code = p.waitFor()
                process = null
                if (!stopRequested) setState(State.FAILED)
                appendServiceLog(ctx, "node 进程退出，code=$code")
            } catch (t: Throwable) {
                process = null
                if (!stopRequested) setState(State.FAILED)
                BootLog.append(ctx, "启动异常：${t.javaClass.simpleName}: ${t.message}")
                appendServiceLog(ctx, "启动失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    /** 把 node 的输出日志持续镜像到 BootLog（含公共下载目录那份）。 */
    private fun startMirror(ctx: Context, p: Process) {
        Thread {
            var offset = 0L
            val log = logFile(ctx)
            while (p.isAlive) {
                try {
                    if (log.exists() && log.length() > offset) {
                        val bytes = log.readBytes()
                        if (bytes.size > offset) {
                            BootLog.appendRaw(
                                ctx,
                                String(bytes, offset.toInt(), bytes.size - offset.toInt())
                            )
                            offset = bytes.size.toLong()
                        }
                    }
                } catch (_: Throwable) {}
                Thread.sleep(1500)
            }
            // 进程退出后把尾巴刷完
            try {
                if (log.exists() && log.length() > offset) {
                    val bytes = log.readBytes()
                    if (bytes.size > offset) {
                        BootLog.appendRaw(ctx, String(bytes, offset.toInt(), bytes.size - offset.toInt()))
                    }
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop(ctx: Context) {
        stopRequested = true
        val p = process
        process = null
        if (p != null) {
            p.destroy()
            Thread {
                Thread.sleep(1500)
                if (p.isAlive) p.destroyForcibly()
            }.start()
        }
        setState(State.STOPPED)
    }

    fun probe(): Boolean {
        return try {
            val conn = URL("$BASE_URL/api/utils/env").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            val ok = conn.responseCode in 200..499
            conn.disconnect()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun appendServiceLog(ctx: Context, msg: String) {
        try {
            val log = logFile(ctx)
            log.parentFile?.mkdirs()
            log.appendText("[app] $msg\n")
        } catch (_: Throwable) {}
    }
}
