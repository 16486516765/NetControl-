package com.limao.netcontrol

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 极简崩溃报告页：只用原生 View，不依赖 Compose/ViewModel，保证崩溃后也能展示。 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val log: String = try {
            File(filesDir, "crash.log").readText()
        } catch (e: Exception) {
            "(无法读取崩溃日志)"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply {
            text = "NetControl 启动时崩溃了，把下面的日志发给开发者即可定位问题"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        val scroll = ScrollView(this)
        val body = TextView(this).apply {
            text = log
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        scroll.addView(body)
        val copyBtn = Button(this).apply { text = "复制日志" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", log))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        val retryBtn = Button(this).apply { text = "清除日志并重新进入" }
        retryBtn.setOnClickListener {
            (application as NetControlApp).clearCrashLog()
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }
        root.addView(title)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        root.addView(copyBtn)
        root.addView(retryBtn)
        setContentView(root)
    }
}
