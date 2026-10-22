package com.bosh.substore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：一份写在私有目录，一份镜像到公共「下载/SubStore/boot-log.txt」，
 * 这样即使 App 界面打不开，也能从文件管理器把日志拿出来定位卡点。
 */
object BootLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var publicUri: Uri? = null
    @Volatile private var publicTried = false

    fun localFile(ctx: Context): File = File(ctx.filesDir, "logs/boot.log")

    @Synchronized
    fun append(ctx: Context, msg: String) {
        val line = "[${fmt.format(Date())}] $msg\n"
        try {
            val f = localFile(ctx)
            f.parentFile?.mkdirs()
            f.appendText(line)
        } catch (_: Throwable) {}
        appendPublic(ctx, line)
    }

    @Synchronized
    fun appendRaw(ctx: Context, text: String) {
        try {
            val f = localFile(ctx)
            f.parentFile?.mkdirs()
            f.appendText(text)
        } catch (_: Throwable) {}
        appendPublic(ctx, text)
    }

    /** 清空日志：私有 boot.log 与公共镜像一起截断（1.1.2 前清空按钮漏了这份，看起来没反应）。 */
    @Synchronized
    fun clear(ctx: Context) {
        try {
            val f = localFile(ctx)
            f.parentFile?.mkdirs()
            f.writeText("")
        } catch (_: Throwable) {}
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = resolvePublicUri(ctx)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ByteArray(0)) }
                }
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SubStore/boot-log.txt"
                )
                if (file.exists()) file.writeText("")
            }
        } catch (_: Throwable) {}
    }

    /** 把位图存到公共「下载/SubStore/」下（同名覆盖），供回传诊断截图。 */
    fun savePublicPng(ctx: Context, name: String, bmp: android.graphics.Bitmap) {
        try {
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection =
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
                val args = arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/SubStore/")
                var uri: Uri? = null
                resolver.query(collection, arrayOf(MediaStore.Downloads._ID), selection, args, null)?.use { c ->
                    if (c.moveToFirst()) uri = Uri.withAppendedPath(collection, c.getLong(0).toString())
                }
                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "image/png")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SubStore")
                    }
                    uri = resolver.insert(collection, values)
                }
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SubStore"
                )
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
        } catch (_: Throwable) {}
    }

    private fun appendPublic(ctx: Context, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = resolvePublicUri(ctx) ?: return
                ctx.contentResolver.openOutputStream(uri, "wa")?.use {
                    it.write(text.toByteArray())
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SubStore"
                )
                dir.mkdirs()
                File(dir, "boot-log.txt").appendText(text)
            }
        } catch (_: Throwable) {
            // 公共日志失败不影响主流程，私有日志仍在
        }
    }

    private fun resolvePublicUri(ctx: Context): Uri? {
        publicUri?.let { return it }
        if (publicTried) return null
        publicTried = true
        try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection =
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            val args = arrayOf("boot-log.txt", Environment.DIRECTORY_DOWNLOADS + "/SubStore/")
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    publicUri = Uri.withAppendedPath(collection, id.toString())
                    return publicUri
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "boot-log.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/SubStore"
                )
            }
            publicUri = resolver.insert(collection, values)
            return publicUri
        } catch (_: Throwable) {
            return null
        }
    }
}
