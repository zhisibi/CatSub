package com.bosh.substore

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class LogActivity : Activity() {

    private lateinit var textView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#111111"))
        }
        val title = TextView(this).apply {
            text = "CatSub 运行日志"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val btnRefresh = Button(this).apply { text = "刷新" }
        val btnCopy = Button(this).apply { text = "复制" }
        val btnClear = Button(this).apply { text = "清空" }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btnRefresh)
        bar.addView(btnCopy)
        bar.addView(btnClear)
        root.addView(bar)

        scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        textView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#F2F2F2"))
            setPadding(20, 12, 20, 12)
            setTextIsSelectable(true)
        }
        scroll.addView(textView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        btnRefresh.setOnClickListener { load() }
        btnCopy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("substore-log", textView.text))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }
        btnClear.setOnClickListener {
            BootLog.clear(this)
            try { NodeRuntime.logFile(this).writeText("") } catch (_: Throwable) {}
            load()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
        load()
    }

    private fun load() {
        val sb = StringBuilder()
        val boot: File = BootLog.localFile(this)
        sb.append("===== boot.log（启动诊断） =====\n")
        sb.append(if (boot.exists()) boot.readText() else "（无）")
        sb.append("\n===== node.log（后端输出） =====\n")
        val f: File = NodeRuntime.logFile(this)
        sb.append(if (f.exists()) f.readText() else "（无）")
        var text = sb.toString()
        if (text.length > 128 * 1024) text = text.substring(text.length - 128 * 1024)
        textView.text = text
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
