package com.bosh.substore

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 从上游 GitHub 检查并下载 Sub-Store 后端 bundle 与前端 dist，实现应用内更新。
 * 后端：sub-store-org/Sub-Store releases（资产 sub-store.bundle.js）
 * 前端：sub-store-org/Sub-Store-Front-End releases（资产 dist.zip）
 */
object Updater {

    data class Release(val version: String, val assetUrl: String)
    data class CheckResult(val backend: Release?, val frontend: Release?)

    private fun fetchJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "CatSub-Android")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }

    private fun latestRelease(repo: String, assetName: String): Release? {
        val json = fetchJson("https://api.github.com/repos/sub-store-org/$repo/releases/latest")
        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isEmpty()) return null
        val assets = json.optJSONArray("assets") ?: return Release(tag, "")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == assetName) {
                return Release(tag, a.optString("browser_download_url"))
            }
        }
        return Release(tag, "")
    }

    fun check(): CheckResult {
        val backend = latestRelease("Sub-Store", "sub-store.bundle.js")
        val frontend = latestRelease("Sub-Store-Front-End", "dist.zip")
        return CheckResult(backend, frontend)
    }

    /** 版本比较：a>b 返回正数，a<b 返回负数，相等返回 0。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.removePrefix("v").split(".")
        val pb = b.removePrefix("v").split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (na != nb) return na - nb
        }
        return 0
    }

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CatSub-Android")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) onProgress((read * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
    }

    private fun unzip(zip: File, destDir: File) {
        destDir.mkdirs()
        val destCanon = destDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destCanon)) {
                    throw SecurityException("非法 zip 条目：${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * 下载并应用更新。任一端为 null 表示该端不更新。
     * 成功后调用 NodeRuntime.applyUpdate 落盘；失败抛异常并保留旧版本不动。
     */
    fun downloadAndApply(
        ctx: Context,
        backend: Release?,
        frontend: Release?,
        onStage: (String, Int) -> Unit
    ) {
        val work = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        var newBundle: File? = null
        var newFrontendDir: File? = null
        val backendVer = NodeRuntime.effectiveBackendVersion(ctx)
        val frontendVer = NodeRuntime.effectiveFrontendVersion(ctx)
        var targetBackend = backendVer
        var targetFrontend = frontendVer

        if (backend != null) {
            require(backend.assetUrl.isNotEmpty()) { "后端 release 缺少 sub-store.bundle.js 资产" }
            onStage("正在下载后端 ${backend.version}…", 0)
            val f = File(work, "sub-store.bundle.js")
            download(backend.assetUrl, f) { p -> onStage("正在下载后端 ${backend.version}…", p) }
            require(f.length() > 1_000_000) { "后端 bundle 大小异常（${f.length()} 字节）" }
            newBundle = f
            targetBackend = backend.version
        }
        if (frontend != null) {
            require(frontend.assetUrl.isNotEmpty()) { "前端 release 缺少 dist.zip 资产" }
            onStage("正在下载前端 ${frontend.version}…", 0)
            val zipFile = File(work, "dist.zip")
            download(frontend.assetUrl, zipFile) { p -> onStage("正在下载前端 ${frontend.version}…", p) }
            onStage("正在解压前端…", 100)
            val dir = File(work, "frontend")
            unzip(zipFile, dir)
            // dist.zip 可能带顶层 dist/ 目录，也可能直接是文件树，两种都兼容
            val realDir = if (File(dir, "index.html").exists()) dir else File(dir, "dist")
            require(File(realDir, "index.html").exists()) { "前端 dist.zip 结构异常：找不到 index.html" }
            newFrontendDir = realDir
            targetFrontend = frontend.version
        }

        // 只更新的一端传文件，另一端传 null 由 applyUpdate 保留现用文件；
        // 绝不能把现用文件/目录本身当“新文件”传进去（1.1.0 自删前端的根因）
        onStage("正在应用更新…", 100)
        NodeRuntime.applyUpdate(ctx, newBundle, newFrontendDir, targetBackend, targetFrontend)
    }
}
