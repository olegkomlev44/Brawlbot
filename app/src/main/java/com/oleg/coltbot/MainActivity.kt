package com.oleg.coltbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private val cBg = Color.parseColor("#0F1220"); private val cCard = Color.parseColor("#1A1F36")
    private val cAccent = Color.parseColor("#FFC107"); private val cGreen = Color.parseColor("#4CAF50")
    private val cRed = Color.parseColor("#EF5350"); private val cText = Color.parseColor("#ECEFF4")
    private val cMuted = Color.parseColor("#8A92B2"); private val cTrack = Color.parseColor("#2A3050")
    private val cBlue = Color.parseColor("#3D5AFE"); private val cBtn2 = Color.parseColor("#2A3050")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT; private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private val stateNames = mapOf(
        "ROAM" to "Разведка", "ATTACK" to "Атака", "EVADE" to "Уклоняюсь", "HIDE" to "Прячусь в кусте",
        "DROP" to "Беру кубок", "BOX" to "Ломаю ящик", "GHOST" to "Преследую", "POISON" to "Ухожу от газа",
        "PINCH" to "Выхожу из клещей"
    )
    private lateinit var rAcc: Chip; private lateinit var rCap: Chip
    private lateinit var hint: TextView; private lateinit var btnMain: Button
    private lateinit var tvState: TextView; private lateinit var tvInfo: TextView
    private lateinit var mHp: Meter; private lateinit var mAm: Meter
    private lateinit var tvFrames: TextView; private lateinit var tvPath: TextView; private lateinit var tvYolo: TextView; private lateinit var tvDiag: TextView
    private var prevT = 0L; private var prevF = 0L; private var fps = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // маленький индикатор «● подпись»
    private inner class Chip(val name: String) {
        val dot = TextView(this@MainActivity).apply { text = "●"; textSize = 12f; setPadding(0, 0, dp(6), 0) }
        val txt = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(cText) }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f); addView(dot); addView(txt)
        }
        fun set(ok: Boolean, s: String) { dot.setTextColor(if (ok) cGreen else cRed); txt.text = "$name: $s" }
    }

    // строка-шкала: подпись, полоска, значение
    private inner class Meter(name: String, color: Int) {
        val lbl = TextView(this@MainActivity).apply { text = name; textSize = 13f; setTextColor(cMuted); layoutParams = LinearLayout.LayoutParams(dp(70), WRAP) }
        val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(0, dp(10), 1f)
            progressBackgroundTintList = ColorStateList.valueOf(cTrack); progressTintList = ColorStateList.valueOf(color)
        }
        val value = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(cText); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(dp(64), WRAP) }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
            addView(lbl); addView(bar); addView(value)
        }
        fun set(p: Int, t: String) { bar.progress = p; value.text = t }
    }

    private fun roundBg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12)); background = roundBg(cCard, 16)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
    }

    // сворачиваемая карточка: заголовок-кнопка + тело; состояние помнится
    private fun fold(title: String, defOpen: Boolean, prefs: SharedPreferences): Pair<LinearLayout, LinearLayout> {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        body.visibility = if (prefs.getBoolean("fold_$title", defOpen)) View.VISIBLE else View.GONE
        val head = TextView(this).apply { textSize = 14f; setTextColor(cText); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4)) }
        fun upd() { head.text = (if (body.visibility == View.VISIBLE) "▾  " else "▸  ") + title }
        upd()
        head.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            upd(); prefs.edit().putBoolean("fold_$title", body.visibility == View.VISIBLE).apply()
        }
        val c = card(); c.addView(head); c.addView(body)
        return Pair(c, body)
    }

    private fun label(t: String = "", size: Float = 14f, color: Int = cText) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, dp(2), 0, dp(2)) }

    private fun button(t: String, color: Int, h: Int = 52, size: Float = 15f, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = size; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = roundBg(color, 12)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(h)).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun sw(t: String, on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
        text = t; textSize = 14f; setTextColor(cText); isChecked = on; setPadding(0, dp(5), 0, dp(5))
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
        CaptureService.useYolo = prefs.getBoolean("yolo", false)
        CaptureService.yoloGpu = prefs.getBoolean("ygpu", false)
        CaptureService.yoloStretch = prefs.getBoolean("ystretch", true)
        CaptureService.overlay = prefs.getBoolean("ov", false)

        // ----- левая колонка: запуск и что происходит сейчас -----
        val left = column()
        left.addView(TextView(this).apply { text = "Colt Bot"; textSize = 28f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD })
        rAcc = Chip("Спецвозможности"); rCap = Chip("Бот")
        left.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)); addView(rAcc.view); addView(rCap.view)
        })
        btnMain = button("▶  Запустить бота", cGreen, 60, 17f) { onMain() }
        btnMain.layoutParams = LinearLayout.LayoutParams(MATCH, dp(60))
        left.addView(btnMain)
        hint = label("", 12f, cMuted).apply { setPadding(0, dp(6), 0, dp(10)) }
        left.addView(hint)

        val live = card()
        tvState = label("", 20f, cAccent).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(4)) }
        mHp = Meter("Здоровье", cGreen); mAm = Meter("Патроны", cAccent)
        tvInfo = label("", 12f, cMuted).apply { setPadding(0, dp(4), 0, 0) }
        live.addView(tvState); live.addView(mHp.view); live.addView(mAm.view); live.addView(tvInfo)
        left.addView(live)

        // ----- правая колонка: настройки, свёрнутые по умолчанию, кроме YOLO -----
        val right = column()
        val (yCard, yBody) = fold("YOLO и оверлей", true, prefs)
        yBody.addView(sw("YOLO (нужна модель)", CaptureService.useYolo) { c ->
            CaptureService.useYolo = c; prefs.edit().putBoolean("yolo", c).apply()
        })
        yBody.addView(sw("Оверлей: что видит бот", CaptureService.overlay) { c ->
            CaptureService.overlay = c; prefs.edit().putBoolean("ov", c).apply()
            BotService.inst?.setOverlay(c && CaptureService.running)
        })
        yBody.addView(sw("YOLO на GPU", CaptureService.yoloGpu) { c ->
            CaptureService.yoloGpu = c; prefs.edit().putBoolean("ygpu", c).apply()
        })
        yBody.addView(sw("Растягивать кадр (выкл = с полями)", CaptureService.yoloStretch) { c ->
            CaptureService.yoloStretch = c; prefs.edit().putBoolean("ystretch", c).apply()
        })
        tvYolo = label("", 12f, cMuted)
        yBody.addView(tvYolo)
        yBody.addView(button("Загрузить модель (.tflite)", cBtn2, 44, 14f) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2)
        })
        right.addView(yCard)

        val (mCard, mBody) = fold("Режим и запись", false, prefs)
        mBody.addView(sw("Командный режим (не Showdown)", CaptureService.mode == Mode.TEAM) { c ->
            CaptureService.mode = if (c) Mode.TEAM else Mode.SHOWDOWN; prefs.edit().putBoolean("team", c).apply()
        })
        mBody.addView(sw("Записывать кадры и действия", CaptureService.record) { c ->
            CaptureService.record = c; prefs.edit().putBoolean("rec", c).apply()
        })
        tvFrames = label("", 12f, cMuted)
        tvPath = label("", 11f, cMuted).apply { setTextIsSelectable(true) }
        mBody.addView(tvFrames); mBody.addView(tvPath)
        right.addView(mCard)

        val (dCard, dBody) = fold("Диагностика и инструменты", false, prefs)
        tvDiag = label("", 11f, cMuted)
        dBody.addView(tvDiag)
        dBody.addView(button("Проверить джойстик", cBtn2, 44, 14f) {
            val bs = BotService.inst
            if (bs == null) Toast.makeText(this, "Служба спецвозможностей не подключена: выключи и включи Colt Bot заново", Toast.LENGTH_LONG).show()
            else { Toast.makeText(this, "Открой игру: персонаж пойдёт вправо-влево ~2 сек", Toast.LENGTH_LONG).show(); bs.selfTest() }
        })
        dBody.addView(button("Сбросить самонастройку", cBtn2, 44, 14f) {
            getSharedPreferences("tuner", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Сброшено: применится при следующем старте бота", Toast.LENGTH_LONG).show()
        })
        right.addView(dCard)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(left); addView(right)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(root) })
        requestPermissions(if (android.os.Build.VERSION.SDK_INT < 29) arrayOf("android.permission.POST_NOTIFICATIONS", "android.permission.WRITE_EXTERNAL_STORAGE") else arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
    }

    // копируем выбранный .tflite во внутреннюю папку приложения (оттуда его читает LiteRT)
    private fun loadModel(uri: android.net.Uri) {
        try {
            val tmp = File(filesDir, "yolo.tflite.tmp")
            contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            val hd = ByteArray(8)
            val n = tmp.inputStream().use { it.read(hd) }
            // у файлов LiteRT/TFLite на смещении 4 стоит сигнатура "TFL3"
            if (n < 8 || String(hd, 4, 4) != "TFL3") {
                tmp.delete()
                Toast.makeText(this, "Это не .tflite модель", Toast.LENGTH_LONG).show(); return
            }
            val dst = File(filesDir, "yolo.tflite")
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
            Toast.makeText(this, "Модель загружена. Включи YOLO и перезапусти бота", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
    }

    // служба реально подключена только если жив BotService.inst (строка в настройках может остаться после переустановки)
    private fun accOn(): Boolean = BotService.inst != null
    private fun accListed(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName)
    }

    // одна главная кнопка: включить спецвозможности -> запустить -> остановить
    private fun onMain() {
        if (CaptureService.running) { stopService(Intent(this, CaptureService::class.java)); return }
        if (!accOn()) {
            Toast.makeText(this, if (accListed()) "Выключи и снова включи Colt Bot в списке, потом вернись" else "Включи Colt Bot в списке, потом вернись", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }

    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 400) } }
    override fun onResume() { super.onResume(); prevT = SystemClock.elapsedRealtime(); prevF = CaptureService.frames; ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val acc = accOn(); val run = CaptureService.running
        rAcc.set(acc, if (acc) "есть" else if (accListed()) "зависли" else "выкл"); rCap.set(run, if (run) "работает" else "стоп")
        btnMain.text = when { run -> "■  Остановить"; !acc -> "Включить спецвозможности"; else -> "▶  Запустить бота" }
        (btnMain.background as GradientDrawable).setColor(when { run -> cRed; !acc -> cBlue; else -> cGreen })
        hint.text = when {
            !acc -> if (accListed()) "Служба отвалилась (так бывает после обновления): выключи и снова включи Colt Bot в спецвозможностях" else "Нажми кнопку и включи Colt Bot в списке спецвозможностей"
            !run -> "Нажми «Запустить», разреши запись экрана и заходи в бой"
            else -> "Бот играет. Открой Brawl Stars"
        }
        val now = SystemClock.elapsedRealtime()
        if (now - prevT >= 1000) { fps = ((CaptureService.frames - prevF) * 1000 / (now - prevT)).toInt(); prevT = now; prevF = CaptureService.frames }
        if (run) {
            tvState.text = stateNames[CaptureService.st] ?: CaptureService.st
            val hp = CaptureService.sHp; val am = CaptureService.sAmmo
            mHp.set(if (hp >= 0f) (hp * 100).toInt() else 0, if (hp >= 0f) "${(hp * 100).toInt()}%" else "—")
            mAm.set(if (am >= 0f) (am * 100).toInt() else 0, if (am >= 0f) "${(am * 3).toInt()} / 3" else "—")
            val yr = CaptureService.yoloRunner
            val y = if (yr == null) "" else if (yr.failed) "  ·  YOLO: сбой" else if (yr.runs == 0) "  ·  YOLO грузится" else "  ·  YOLO ${yr.ms.toInt()} мс"
            tvInfo.text = "врагов ${CaptureService.sEn}  ·  $fps к/с$y"
        } else {
            tvState.text = "Бот остановлен"; mHp.set(0, "—"); mAm.set(0, "—"); tvInfo.text = ""
        }
        tvDiag.text = "Решение: ${CaptureService.lastAct}\nЖесты: отправлено ${BotService.sent}, выполнено ${BotService.done}, отменено ${BotService.cancelled}, отклонено ${BotService.rejected}" +
            (if (BotService.lastErr.isNotEmpty()) "\nОшибка жеста: ${BotService.lastErr}" else "") +
            (if (CaptureService.recErr.isNotEmpty()) "\nОшибка: ${CaptureService.recErr}" else "") +
            (if (CaptureService.lastMatch.isNotEmpty()) "\nПоследний матч: ${CaptureService.lastMatch}" else "") +
            (if (CaptureService.tunerInfo.isNotEmpty()) "\nСамонастройка: ${CaptureService.tunerInfo}" else "")
        val mf = File(filesDir, "yolo.tflite")
        tvYolo.text = (if (mf.exists()) "Модель загружена: " + "%.1f".format(mf.length() / 1048576.0) + " МБ" else "Модель не загружена") +
            (if (CaptureService.yoloStatus().isNotEmpty()) "\n" + CaptureService.yoloStatus() else "") +
            "\nНастройки применяются при следующем запуске."
        tvFrames.text = "Сохранено кадров: ${CaptureService.framesSaved}"
        tvPath.text = "Папка: " + (CaptureService.recDir.ifEmpty { "Загрузки/Brawlbot/ (появится при записи)" })
    }

    @Deprecated("old api")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rq == 2 && rc == RESULT_OK) {
            val uri = d?.data
            if (uri != null) loadModel(uri)
            return
        }
        if (rq == 1 && rc == RESULT_OK && d != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", rc).putExtra("data", d))
        }
    }
}
