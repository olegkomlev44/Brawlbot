package com.oleg.coltbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32) }
        l.addView(TextView(this).apply {
            text = "Colt Bot (Null's)\n1) включи Colt Bot в спецвозможностях\n2) старт и разреши запись экрана\n3) открой игру и зайди в бой"
            textSize = 16f
        })
        fun btn(t: String, f: () -> Unit) = l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        btn("Спецвозможности") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        l.addView(Switch(this).apply { text = "Записывать кадры и действия"; setOnCheckedChangeListener { _, c -> CaptureService.record = c } })
        btn("Старт") {
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
        }
        btn("Стоп") { stopService(Intent(this, CaptureService::class.java)) }
        setContentView(l)
        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
    }

    @Deprecated("old api")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rq == 1 && rc == RESULT_OK && d != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", rc).putExtra("data", d))
        }
    }
}
