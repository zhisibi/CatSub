package com.bosh.substore

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.MenuItem
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val APP_VERSION = "1.1.5"
        const val REPO_URL = "https://github.com/zhisibi/CatSub.git"

        const val DISCLAIMER = "CatSub 是 Sub-Store 的第三方安卓客户端。Sub-Store 为 AGPL-3.0 开源项目，版权归其原作者及全体贡献者所有。\n\n" +
            "本软件仅将 Sub-Store 前后端与 Node.js 运行时打包为安卓应用，不提供、不存储、不分发任何订阅内容或节点信息；您的全部数据仅保存在本机设备。\n\n" +
            "本软件仅供个人学习与技术研究使用。请遵守您所在国家或地区的法律法规，禁止用于任何违法用途；因使用本软件产生的任何后果由使用者自行承担，与本软件开发者无关。\n\n" +
            "点击「同意并继续」即表示您已阅读并接受以上声明。"
    }

    private lateinit var statusDot: TextView
    private lateinit var stageView: TextView
    private lateinit var frame: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var overlayText: TextView
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageLoaded = false
    private var initialized = false
    private var updateRunning = false
    private var shotDone = false
    private var dumpDone = false

    /** 把外壳视图层级（类名/尺寸/位置/可见性）整棵倾倒进日志，一次看清谁在占地方。 */
    private fun dumpHierarchy(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 4) return
        val lp = v.layoutParams
        val lpDesc = when (lp) {
            is LinearLayout.LayoutParams -> "lp=${lp.width}x${lp.height} w=${lp.weight}"
            is FrameLayout.LayoutParams -> "lp=${lp.width}x${lp.height}"
            is ViewGroup.LayoutParams -> "lp=${lp.width}x${lp.height}"
            else -> "lp=null"
        }
        sb.append("  ".repeat(depth))
            .append(v.javaClass.simpleName)
            .append(" wh=${v.width}x${v.height} top=${v.top} vis=${v.visibility} ").append(lpDesc)
        if (v is TextView) sb.append(" text='${v.text?.take(12)}'")
        sb.append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dumpHierarchy(v.getChildAt(i), depth + 1, sb)
        }
    }

    private val stateListener: (NodeRuntime.State) -> Unit = { s ->
        runOnUiThread { updateStatus(s) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 外壳先上屏；免责声明未同意前不做任何初始化
        buildShell()
        setStage("等待确认免责声明…")
        if (Prefs.disclaimerAccepted(this)) {
            proceedInit()
        } else {
            showDisclaimer()
        }
    }

    private fun showDisclaimer() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER + "\n\n开源仓库：$REPO_URL")
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                Prefs.setDisclaimerAccepted(this)
                BootLog.append(this, "用户已同意免责声明")
                proceedInit()
            }
            .setNegativeButton("不同意并退出") { _, _ -> finish() }
            .create()
        dialog.show()
        makeMessageLinksClickable(dialog)
    }

    private fun proceedInit() {
        if (initialized) return
        initialized = true
        setStage("界面已启动")
        BootLog.append(this, "== MainActivity 初始化（外壳已上屏） ==")
        window.decorView.post {
            BootLog.append(this, "第一帧已绘制，开始初始化 WebView 与服务")
            initWebView()
            startBackend()
            waitForServerAndLoad()
            window.decorView.post {
                try {
                    val wv = webView
                    val loc = IntArray(2)
                    wv?.getLocationOnScreen(loc)
                    BootLog.append(
                        this,
                        "窗口几何：multiWindow=${isInMultiWindowMode} " +
                            "winAttrs=${window.attributes.width}x${window.attributes.height} g=${window.attributes.gravity} " +
                            "decor=${window.decorView.width}x${window.decorView.height} " +
                            "wv=${wv?.width}x${wv?.height} wvLoc=${loc[0]},${loc[1]} " +
                            "density=${resources.displayMetrics.density} hwAccel=${wv?.isHardwareAccelerated} layer=${wv?.layerType}"
                    )
                } catch (_: Throwable) {}
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            } catch (_: Throwable) {}
        }
        NodeRuntime.addListener(stateListener)
        updateStatus(NodeRuntime.state)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶栏：高度压缩为原来的 2/3 左右，标题 + 状态圆点 + 三点菜单
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F2329"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(4), dp(2))
        }
        val title = TextView(this).apply {
            text = "CatSub"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 11f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(dp(7), 0, 0, 0)
            contentDescription = "服务状态"
        }
        val btnMore = Button(this).apply {
            text = "⋮"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { showMenu(it) }
        }
        bar.addView(title)
        bar.addView(statusDot)
        bar.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnMore)
        // 1.1.5：顶栏必须显式固定高度。1.1.4 的设备日志实锤：顶栏被测成几乎整屏高
        // （CENTER_VERTICAL 使标题落在屏幕 50% 处），下方 frame 被压到高度 0，
        // WebView 实测 1080x0 位于屏幕底部——这才是“DOM 完整但屏幕全黑”的真根因。
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        stageView = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#FFF3CD"))
            setTextColor(Color.parseColor("#664D03"))
            textSize = 11f
            setPadding(dp(10), dp(2), dp(10), dp(2))
            text = "阶段：初始化…"
        }
        root.addView(stageView)

        frame = FrameLayout(this)
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        }
        val pb = ProgressBar(this)
        overlayText = TextView(this).apply {
            text = "正在启动本机 Sub-Store 服务…"
            setTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        overlay.addView(pb)
        overlay.addView(overlayText)
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun setStage(s: String) {
        stageView.text = "阶段：$s（卡住时请把 下载/SubStore/boot-log.txt 发我）"
    }

    private fun initWebView() {
        try {
            setStage("正在创建 WebView…")
            val wv = WebView(this)
            webView = wv
            // 关键修复（1.1.4）：DOM 完整但屏幕全黑是 WebView 硬件加速合成失败的典型症状
            // （部分机型/驱动），强制软件绘制层绕过 GPU 合成路径
            try {
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                BootLog.append(this, "WebView 已强制软件绘制层（排查硬件合成空白）")
            } catch (_: Throwable) {}
            BootLog.append(this, "WebView UA: ${wv.settings.userAgentString}")
            try {
                val pkg = WebView.getCurrentWebViewPackage()
                BootLog.append(this, "WebView 内核：${pkg?.packageName} ${pkg?.versionName}")
            } catch (_: Throwable) {}
            // 运行时版本变更时清一次缓存，防止旧响应被缓存毒化
            val cacheKey = "wv_cache_" + NodeRuntime.RUNTIME_VERSION
            val sp = getSharedPreferences("substore_prefs", MODE_PRIVATE)
            if (!sp.getBoolean(cacheKey, false)) {
                wv.clearCache(true)
                sp.edit().putBoolean(cacheKey, true).apply()
                BootLog.append(this, "已清理 WebView 缓存（运行时 ${NodeRuntime.RUNTIME_VERSION}）")
            }
            // 每个 App 版本首次运行重置一次 WebView 存储：清掉可能被毒化的
            // ServiceWorker/缓存存储/localStorage（订阅数据在后端文件里，不受影响）
            val resetKey = "storage_reset_$APP_VERSION"
            if (!sp.getBoolean(resetKey, false)) {
                try { WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
                wv.clearCache(true)
                wv.clearHistory()
                sp.edit().putBoolean(resetKey, true).apply()
                BootLog.append(this, "已重置 WebView 存储（版本 $APP_VERSION 首次运行）")
            }
            setupWebView(wv)
            frame.addView(wv, 0, FrameLayout.LayoutParams(-1, -1))
            BootLog.append(this, "WebView 创建成功")
            setStage("WebView 就绪，等待后端…")
        } catch (t: Throwable) {
            BootLog.append(this, "WebView 创建失败：${t.javaClass.simpleName}: ${t.message}")
            overlayText.text = "WebView 创建失败：${t.message}\n（系统 WebView 组件可能被禁用，请在应用商店更新/启用 Android System WebView）"
            setStage("WebView 创建失败")
        }
    }

    private fun startBackend() {
        try {
            NodeService.start(this)
            BootLog.append(this, "NodeService 已拉起")
        } catch (t: Throwable) {
            BootLog.append(this, "NodeService 拉起失败，改由界面直接启动 Node：${t.javaClass.simpleName}: ${t.message}")
            try {
                NodeRuntime.start(this)
            } catch (t2: Throwable) {
                BootLog.append(this, "直接启动 Node 也失败：${t2.javaClass.simpleName}: ${t2.message}")
            }
        }
    }

    private fun setupWebView(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "127.0.0.1" || u.host == "localhost") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Throwable) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                overlay.visibility = View.GONE
                stageView.visibility = View.GONE
                BootLog.append(this@MainActivity, "网页已加载完成：$url")
                // DOM 探针：onPageFinished 不等于渲染成功，1.5 秒后把页面真实状态写进日志
                view.postDelayed({
                    try {
                        view.evaluateJavascript(
                            "(function(){try{var a=document.getElementById('app');" +
                                "return JSON.stringify({href:location.href,title:document.title," +
                                "ready:document.readyState,appChildren:a?a.childElementCount:-1," +
                                "textLen:document.body?document.body.innerText.length:0," +
                                "text:(document.body?document.body.innerText:'').slice(0,80)})" +
                                "}catch(e){return 'probe-error:'+e}})()"
                        ) { result ->
                            BootLog.append(this@MainActivity, "页面 DOM 探针：$result")
                        }
                    } catch (_: Throwable) {}
                }, 1500)
                // 层级倾倒（每进程一次）：3.5 秒后把外壳每个视图的尺寸位置写进日志
                if (!dumpDone) {
                    dumpDone = true
                    view.postDelayed({
                        try {
                            val sb = StringBuilder("视图层级倾倒（root 为窗口内容根）：\n")
                            dumpHierarchy(window.decorView.findViewById<View>(android.R.id.content), 0, sb)
                            BootLog.append(this@MainActivity, sb.toString().trimEnd())
                        } catch (t: Throwable) {
                            BootLog.append(this@MainActivity, "层级倾倒异常：${t.javaClass.simpleName}: ${t.message}")
                        }
                    }, 3500)
                }
                // 窗口截图探针（每进程一次）：PixelCopy 抓窗口实际合成结果，
                // 统计亮像素并存图到 下载/SubStore/window-shot.png，区分“没画出来”与“画了但看不见”
                if (!shotDone) {
                    shotDone = true
                    view.postDelayed({
                        try {
                            val decor = window.decorView
                            if (decor.width > 0 && decor.height > 0) {
                                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                                PixelCopy.request(window, bmp, { result ->
                                    if (result == PixelCopy.SUCCESS) {
                                        var bright = 0L
                                        var total = 0L
                                        val step = 6
                                        var x = 0
                                        while (x < bmp.width) {
                                            var y = 0
                                            while (y < bmp.height) {
                                                val c = bmp.getPixel(x, y)
                                                if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) > 150) bright++
                                                total++
                                                y += step
                                            }
                                            x += step
                                        }
                                        BootLog.append(
                                            this@MainActivity,
                                            "窗口截图探针：PixelCopy 成功 ${bmp.width}x${bmp.height}，采样亮像素 $bright/$total"
                                        )
                                        BootLog.savePublicPng(this@MainActivity, "window-shot.png", bmp)
                                    } else {
                                        BootLog.append(this@MainActivity, "窗口截图探针：PixelCopy 失败 code=$result")
                                    }
                                }, Handler(Looper.getMainLooper()))
                            }
                        } catch (t: Throwable) {
                            BootLog.append(this@MainActivity, "窗口截图探针异常：${t.javaClass.simpleName}: ${t.message}")
                        }
                    }, 2200)
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse
            ) {
                if (response.statusCode >= 400) {
                    BootLog.append(
                        this@MainActivity,
                        "网页资源 HTTP 错误：${response.statusCode} ${request.url}"
                    )
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    BootLog.append(
                        this@MainActivity,
                        "网页主框架加载错误：${error.errorCode} ${error.description} url=${request.url}"
                    )
                }
            }
        }
        wv.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) {}
                Toast.makeText(this, "已用浏览器打开下载链接", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "此下载为网页内生成文件，请用菜单「浏览器打开」后在浏览器里导出", Toast.LENGTH_LONG).show()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params.createIntent()
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, 1001)
                    true
                } catch (_: Throwable) {
                    fileCallback = null
                    false
                }
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                BootLog.append(
                    this@MainActivity,
                    "网页控制台[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                )
                return true
            }
        }
    }

    @Deprecated("传统文件选择回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(results)
            fileCallback = null
        }
    }

    private fun waitForServerAndLoad(url: String = NodeRuntime.BASE_URL + "/") {
        overlay.visibility = View.VISIBLE
        Thread {
            var ok = false
            for (i in 1..120) {
                if (NodeRuntime.probe()) { ok = true; break }
                if (NodeRuntime.state == NodeRuntime.State.FAILED && i > 10) break
                Thread.sleep(500)
            }
            runOnUiThread {
                if (ok) {
                    setStage("后端已就绪，加载页面…")
                    if (!pageLoaded) webView?.loadUrl(url)
                    else if (url != NodeRuntime.BASE_URL + "/") webView?.loadUrl(url)
                } else {
                    overlayText.text = "服务启动超时或失败。\n请打开菜单「运行日志」，或把 下载/SubStore/boot-log.txt 发我。"
                    setStage("后端未就绪（超时/失败）")
                    BootLog.append(this, "waitForServer 超时：后端未就绪，state=${NodeRuntime.state}")
                }
            }
        }.start()
    }

    private fun updateStatus(s: NodeRuntime.State) {
        if (!::statusDot.isInitialized) return
        statusDot.setTextColor(
            when (s) {
                NodeRuntime.State.RUNNING -> Color.parseColor("#7EE787")
                NodeRuntime.State.STARTING -> Color.parseColor("#E3B341")
                NodeRuntime.State.FAILED -> Color.parseColor("#FF7B72")
                NodeRuntime.State.STOPPED -> Color.parseColor("#9AA4B2")
            }
        )
        statusDot.contentDescription = when (s) {
            NodeRuntime.State.RUNNING -> "运行中"
            NodeRuntime.State.STARTING -> "启动中"
            NodeRuntime.State.FAILED -> "启动失败"
            NodeRuntime.State.STOPPED -> "已停止"
        }
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 10, 0, "刷新页面")
        menu.menu.add(0, 11, 0, "运行日志")
        menu.menu.add(0, 1, 0, "浏览器打开")
        menu.menu.add(0, 2, 0, "复制本机地址")
        menu.menu.add(0, 7, 0, "检查更新（Sub-Store 前后端）")
        if (NodeRuntime.isDynamic(this)) {
            menu.menu.add(0, 8, 0, "恢复内置版本")
        }
        menu.menu.add(0, 3, 0, "重启服务")
        menu.menu.add(0, 4, 0, "停止服务")
        val autoItem: MenuItem = menu.menu.add(0, 5, 0, "开机自启")
        autoItem.isCheckable = true
        autoItem.isChecked = Prefs.autostart(this)
        menu.menu.add(0, 6, 0, "关于")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> {
                    val wv = webView
                    if (pageLoaded && wv != null) wv.reload() else waitForServerAndLoad()
                }
                11 -> startActivity(Intent(this, LogActivity::class.java))
                1 -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NodeRuntime.BASE_URL))) } catch (_: Throwable) {}
                2 -> {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("CatSub", NodeRuntime.BASE_URL))
                    Toast.makeText(this, "已复制 ${NodeRuntime.BASE_URL}", Toast.LENGTH_SHORT).show()
                }
                7 -> checkUpdates()
                8 -> {
                    AlertDialog.Builder(this)
                        .setTitle("恢复内置版本")
                        .setMessage("将删除在线更新的前后端，恢复为本 APK 内置版本（后端 ${NodeRuntime.BUNDLED_BACKEND_VERSION} / 前端 ${NodeRuntime.BUNDLED_FRONTEND_VERSION}），并重启服务。订阅数据不受影响。")
                        .setPositiveButton("恢复") { _, _ ->
                            Thread {
                                NodeRuntime.resetToBundled(this)
                                NodeRuntime.ensureExtracted(this)
                                runOnUiThread {
                                    pageLoaded = false
                                    startBackend()
                                    waitForServerAndLoad()
                                    Toast.makeText(this, "已恢复内置版本", Toast.LENGTH_SHORT).show()
                                }
                            }.start()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                3 -> {
                    Toast.makeText(this, "正在重启服务…", Toast.LENGTH_SHORT).show()
                    pageLoaded = false
                    NodeRuntime.stop(this)
                    Thread {
                        Thread.sleep(800)
                        startBackend()
                        runOnUiThread { waitForServerAndLoad() }
                    }.start()
                }
                4 -> {
                    try { NodeService.stop(this) } catch (_: Throwable) {}
                    NodeRuntime.stop(this)
                    Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
                }
                5 -> {
                    val v = !Prefs.autostart(this)
                    Prefs.setAutostart(this, v)
                    Toast.makeText(this, if (v) "开机自启：开" else "开机自启：关", Toast.LENGTH_SHORT).show()
                }
                6 -> showAbout()
            }
            true
        }
        menu.show()
    }

    private fun showAbout() {
        val (dynB, dynF) = NodeRuntime.dynamicVersions(this)
        val backendLine = if (dynB != null) "$dynB（在线更新，内置 ${NodeRuntime.BUNDLED_BACKEND_VERSION}）" else NodeRuntime.BUNDLED_BACKEND_VERSION
        val frontendLine = if (dynF != null) "$dynF（在线更新，内置 ${NodeRuntime.BUNDLED_FRONTEND_VERSION}）" else NodeRuntime.BUNDLED_FRONTEND_VERSION
        val msg = "CatSub 版本：$APP_VERSION\n" +
            "Sub-Store 后端：$backendLine\n" +
            "Sub-Store 前端：$frontendLine\n" +
            "Node.js 运行时：${NodeRuntime.NODE_VERSION}\n" +
            "开源协议：AGPL-3.0（遵循上游 Sub-Store）\n\n" +
            "本软件 GitHub 仓库：$REPO_URL\n" +
            "上游项目：https://github.com/sub-store-org/Sub-Store\n\n" +
            "免责声明：本软件仅供个人学习与技术研究使用，不提供、不存储、不分发任何订阅内容；全部数据仅保存在本机。请遵守当地法律法规，使用后果由使用者自行承担。"
        val dialog = AlertDialog.Builder(this)
            .setTitle("关于 CatSub")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .setNeutralButton("打开仓库") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zhisibi/CatSub"))) } catch (_: Throwable) {}
            }
            .create()
        dialog.show()
        makeMessageLinksClickable(dialog)
    }

    private fun makeMessageLinksClickable(dialog: AlertDialog) {
        try {
            val tv = dialog.findViewById<TextView>(android.R.id.message) ?: return
            Linkify.addLinks(tv, Linkify.WEB_URLS)
            tv.movementMethod = LinkMovementMethod.getInstance()
        } catch (_: Throwable) {}
    }

    // ---------------- 检查更新 ----------------

    private fun checkUpdates() {
        if (updateRunning) {
            Toast.makeText(this, "正在处理更新，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        updateRunning = true
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage("正在检查上游更新…")
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val result = Updater.check()
                runOnUiThread {
                    progress.dismiss()
                    updateRunning = false
                    showUpdateResult(result)
                }
            } catch (t: Throwable) {
                BootLog.append(this, "检查更新失败：${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread {
                    progress.dismiss()
                    updateRunning = false
                    AlertDialog.Builder(this)
                        .setTitle("检查更新失败")
                        .setMessage("无法连接 GitHub：${t.message}\n请检查网络后重试。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showUpdateResult(result: Updater.CheckResult) {
        val curB = NodeRuntime.effectiveBackendVersion(this)
        val curF = NodeRuntime.effectiveFrontendVersion(this)
        val newBackend = result.backend?.takeIf {
            it.assetUrl.isNotEmpty() && Updater.compareVersions(it.version, curB) > 0
        }
        val newFrontend = result.frontend?.takeIf {
            it.assetUrl.isNotEmpty() && Updater.compareVersions(it.version, curF) > 0
        }
        val sb = StringBuilder()
        sb.append("后端：当前 $curB")
        sb.append(
            when {
                result.backend == null -> "，上游版本获取失败"
                newBackend != null -> " → 最新 ${result.backend.version}（可更新）"
                else -> "（已是最新）"
            }
        )
        sb.append("\n前端：当前 $curF")
        sb.append(
            when {
                result.frontend == null -> "，上游版本获取失败"
                newFrontend != null -> " → 最新 ${result.frontend.version}（可更新）"
                else -> "（已是最新）"
            }
        )
        val builder = AlertDialog.Builder(this)
            .setTitle("检查更新")
            .setMessage(sb.toString())
            .setNegativeButton("关闭", null)
        if (newBackend != null || newFrontend != null) {
            builder.setPositiveButton("下载并更新") { _, _ ->
                doUpdate(newBackend, newFrontend)
            }
        }
        builder.show()
    }

    private fun doUpdate(backend: Updater.Release?, frontend: Updater.Release?) {
        updateRunning = true
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setTitle("正在更新 Sub-Store")
            setMessage("准备中…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }
        Thread {
            try {
                Updater.downloadAndApply(this, backend, frontend) { stage, pct ->
                    runOnUiThread {
                        progress.setMessage(stage)
                        progress.progress = pct
                    }
                }
                BootLog.append(this, "在线更新应用成功")
                runOnUiThread {
                    progress.dismiss()
                    updateRunning = false
                    Toast.makeText(this, "更新完成，正在重启服务…", Toast.LENGTH_LONG).show()
                    pageLoaded = false
                    webView?.clearCache(true)
                    startBackend()
                    // 前端若来自上游原版（默认 API 不是本机），用 ?api= 参数把后端指回本机
                    waitForServerAndLoad(
                        NodeRuntime.BASE_URL + "/?api=" + Uri.encode(NodeRuntime.BASE_URL)
                    )
                }
            } catch (t: Throwable) {
                BootLog.append(this, "在线更新失败：${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread {
                    progress.dismiss()
                    updateRunning = false
                    AlertDialog.Builder(this)
                        .setTitle("更新失败")
                        .setMessage("更新未完成，原版本保持不变。\n原因：${t.message}")
                        .setPositiveButton("知道了", null)
                        .show()
                    // 保证服务回到可用状态
                    startBackend()
                    waitForServerAndLoad()
                }
            }
        }.start()
    }

    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        NodeRuntime.removeListener(stateListener)
        super.onDestroy()
    }
}
