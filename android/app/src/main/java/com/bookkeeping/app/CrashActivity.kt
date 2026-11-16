package com.bookkeeping.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 崩溃报告页。纯系统控件，不走 Compose / Hilt，
 * 避免崩溃原因本身也影响展示页。
 */
class CrashActivity : Activity() {
    companion object {
        const val EXTRA_REPORT = "report"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT).orEmpty()
        val padding = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = "清衡崩溃了，日志在这里"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(padding, padding, padding, padding / 2)
        }

        val body = TextView(this).apply {
            text = report
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }

        val copyButton = Button(this).apply {
            text = "复制日志"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("crash", report))
                Toast.makeText(this@CrashActivity, "已复制，粘贴发给 Hermes", Toast.LENGTH_SHORT).show()
            }
        }

        val shareButton = Button(this).apply {
            text = "分享日志"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, "分享崩溃日志"))
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(copyButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(shareButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(buttons)
            addView(
                ScrollView(this@CrashActivity).apply { addView(body) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }
}
