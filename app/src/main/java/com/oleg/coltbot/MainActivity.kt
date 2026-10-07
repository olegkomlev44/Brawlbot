package com.oleg.coltbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val cBg = Color.parseColor("#0F1220"); private val cCard = Color.parseColor("#1A1F36")
    private val cAccent = Color.parseColor("#FFC107"); private val cGreen = Color.parseColor("#4CAF50")
    private val cRed = Color.parseColor("#EF5350"); private val cText = Color.parseColor("#ECEFF4")
    private val cMuted = Color.parseColor("#8A92B2"); private val cTrack = Color.parseColor("#2A3050")
    private val cBlue = Color.parseColor("#3D5AFE")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT; private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private val stateNames = mapOf(
        "ROAM" to "Ищу цель", "ATTACK" to "Атака", "EVADE" to "Уклоняюсь", "HIDE" to "Прячусь в кусте",
        "DROP" to "Беру кубок", "BOX" to "Фарм ящика", "GHOST" to "Преследую", "POISON" to "Ухожу от газа",
        "PINCH" to "Выхожу из клещей"
    )
    private lateinit var rAcc: Row; private lateinit var rCap: Row
    private lateinit var hint: TextView; private lateinit var btnStart: Button
    private lateinit var tvState: TextView; private lateinit var tvHp: TextView; private lateinit var tvAmmo: TextView
    private lateinit var tvEn: TextView; private lateinit var tvFps: TextView
    private lateinit var barHp: ProgressBar; private lateinit var barAmmo: ProgressBar
    private lateinit var tvFrames: TextView; private lateinit var tvPath: TextView; private lateinit var tvDiag: TextView
    private var prevT = 0L; private var prevF = 0L; private var fps = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class Row(val name: String) {
        val dot = TextView(this@MainActivity).apply { text = "●"; textSize = 14f; setPadding(0, 0, dp(8), 0) }
        val txt = TextView(this@MainActivity).apply { textSize = 14f; setTextColor(cText) }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4)); addView(dot); addView(txt)
        }
        fun set(ok: Boolean, s: String) { dot.setTextColor(if (ok) cGreen else cRed); txt.text = "$name: $s" }
    }

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 12f; setTextColor(cMuted); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f; setPadding(0, 0, 0, dp(8))
        })
    }

    private fun label(t: String = "", size: Float = 14f, color: Int = cText) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, dp(2), 0, dp(2)) }

    private fun bar(color: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(2); bottomMargin = dp(10) }
        progressBackgroundTintList = ColorStateList.valueOf(cTrack); progressTintList = ColorStateList.valueOf(color)
    }

    private fun button(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun sw(t: String, on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
        text = t; textSize = 14f; setTextColor(cText); isChecked = on; setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, c -> f(c) }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cBg; window.navigationBarColor = cBg
        val prefs = getSharedPreferences("s", Context.MODE_PRIVATE)
        CaptureService.record = prefs.getBoolean("rec", false)
        CaptureService.mode = if (prefs.getBoolean("team", false)) Mode.TEAM else Mode.SHOWDOWN

        // ----- левая колонка: статус и управление -----
        val left = column()
        left.addView(TextView(this).apply { text = "Colt Bot"; textSize = 30f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD })
        left.addView(label("автопилот для Brawl Stars", 13f, cMuted).apply { setPadding(0, 0, 0, dp(14)) })
        val status = card("СТАТУС")
        rAcc = Row("Спецвозможности"); rCap = Row("Бот")
        hint = label("", 13f, cMuted).apply { setPadding(0, dp(8), 0, 0) }
        status.addView(rAcc.view); status.addView(rCap.view); status.addView(hint)
        left.addView(status)
        left.addView(button("Открыть спецвозможности", cBlue) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        btnStart = button("▶  Старт", cGreen) { onStartStop() }
        left.addView(btnStart)
        left.addView(button("Проверить управление (джойстик)", cBlue) {
            val b = BotService.inst
            if (b == null) Toast.makeText(this, "Служба спецвозможностей не подключена: выключи и включи Colt Bot заново", Toast.LENGTH_LONG).show()
            else { Toast.makeText(this, "Открой игру: персонаж пойдёт вправо-влево ~2 сек", Toast.LENGTH_LONG).show(); b.selfTest() }
        })

        // ----- правая колонка: живые данные, запись, режим -----
        val right = column()
        val live = card("СЕЙЧАС В БОЮ")
        tvState = label("", 18f, cAccent).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(8)) }
        tvHp = label("Здоровье"); barHp = bar(cGreen)
        tvAmmo = label("Патроны"); barAmmo = bar(cAccent)
        tvEn = label("Врагов в поле зрения: —"); tvFps = label("Кадров/с: —", 13f, cMuted)
        live.addView(tvState); live.addView(tvHp); live.addView(barHp); live.addView(tvAmmo); live.addView(barAmmo)
        live.addView(tvEn); live.addView(tvFps)
        tvDiag = label("", 11f, cMuted); live.addView(tvDiag)
        right.addView(live)

        val set = card("НАСТРОЙКИ")
        set.addView(sw("Командный режим (не Showdown)", CaptureService.mode == Mode.TEAM) { c ->
            CaptureService.mode = if (c) Mode.TEAM else Mode.SHOWDOWN; prefs.edit().putBoolean("team", c).apply()
        })
        set.addView(sw("Записывать кадры и действия", CaptureService.record) { c ->
            CaptureService.record = c; prefs.edit().putBoolean("rec", c).apply()
        })
        tvFrames = label("", 13f, cMuted)
        tvPath = label("", 11f, cMuted).apply { setTextIsSelectable(true) }
        set.addView(tvFrames); set.addView(tvPath)
        right.addView(set)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(left); addView(right)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(root) })
        requestPermissions(if (android.os.Build.VERSION.SDK_INT < 29) arrayOf("android.permission.POST_NOTIFICATIONS", "android.permission.WRITE_EXTERNAL_STORAGE") else arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
    }

    // служба реально подключена только если жив BotService.inst (строка в настройках может остаться после переустановки)
    private fun accOn(): Boolean = BotService.inst != null
    private fun accListed(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName)
    }

    private fun onStartStop() {
        if (CaptureService.running) { stopService(Intent(this, CaptureService::class.java)); return }
        if (!accOn()) { Toast.makeText(this, if (accListed()) "Служба в списке, но не запущена: выключи и включи Colt Bot в спецвозможностях" else "Сначала включи Colt Bot в спецвозможностях", Toast.LENGTH_LONG).show(); return }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }

    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 400) } }
    override fun onResume() { super.onResume(); prevT = SystemClock.elapsedRealtime(); prevF = CaptureService.frames; ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val acc = accOn(); val run = CaptureService.running
        rAcc.set(acc, if (acc) "подключены" else if (accListed()) "включены, но служба не запущена" else "выключены"); rCap.set(run, if (run) "работает" else "остановлен")
        btnStart.text = if (run) "■  Стоп" else "▶  Старт"
        (btnStart.background as GradientDrawable).setColor(if (run) cRed else cGreen)
        hint.text = when {
            !acc -> (if (accListed()) "1. Выключи и снова включи Colt Bot в спецвозможностях (после обновления приложения служба отваливается)" else "1. Включи Colt Bot в спецвозможностях")
            !run -> "2. Нажми «Старт» и разреши запись экрана"
            else -> "3. Открой игру и зайди в бой"
        }
        val now = SystemClock.elapsedRealtime()
        if (now - prevT >= 1000) { fps = ((CaptureService.frames - prevF) * 1000 / (now - prevT)).toInt(); prevT = now; prevF = CaptureService.frames }
        if (run) {
            tvState.text = stateNames[CaptureService.st] ?: CaptureService.st
            val hp = CaptureService.sHp; val am = CaptureService.sAmmo
            tvHp.text = if (hp >= 0f) "Здоровье: ${(hp * 100).toInt()}%" else "Здоровье: персонаж не виден"
            barHp.progress = if (hp >= 0f) (hp * 100).toInt() else 0
            tvAmmo.text = if (am >= 0f) "Патроны: ${(am * 3).toInt()} из 3" else "Патроны: —"
            barAmmo.progress = if (am >= 0f) (am * 100).toInt() else 0
            tvEn.text = "Врагов в поле зрения: ${CaptureService.sEn}"; tvFps.text = "Кадров/с: $fps"
        } else {
            tvState.text = "Бот остановлен"; tvHp.text = "Здоровье"; tvAmmo.text = "Патроны"
            barHp.progress = 0; barAmmo.progress = 0; tvEn.text = "Врагов в поле зрения: —"; tvFps.text = "Кадров/с: —"
        }
        tvDiag.text = "Решение: ${CaptureService.lastAct}\nЖесты: отправлено ${BotService.sent}, выполнено ${BotService.done}, отменено ${BotService.cancelled}, отклонено ${BotService.rejected}" +
            (if (BotService.lastErr.isNotEmpty()) "\nОшибка жеста: ${BotService.lastErr}" else "") +
            (if (CaptureService.recErr.isNotEmpty()) "\nОшибка: ${CaptureService.recErr}" else "")
        tvFrames.text = "Сохранено кадров: ${CaptureService.framesSaved}"
        tvPath.text = "Папка: " + (CaptureService.recDir.ifEmpty { "Загрузки/Brawlbot/ (появится при записи)" })
    }

    @Deprecated("old api")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rq == 1 && rc == RESULT_OK && d != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", rc).putExtra("data", d))
        }
    }
}
