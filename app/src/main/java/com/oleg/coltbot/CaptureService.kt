package com.oleg.coltbot

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CaptureService : Service() {
    companion object {
        @Volatile var record = false
        @Volatile var running = false
        @Volatile var mode = Mode.SHOWDOWN
        // статус для экрана приложения
        @Volatile var st = "ROAM"; @Volatile var sHp = -1f; @Volatile var sAmmo = -1f; @Volatile var sEn = 0
        @Volatile var frames = 0L; @Volatile var framesSaved = 0; @Volatile var recDir = ""
        @Volatile var lastAct = ""; @Volatile var recErr = ""
        @Volatile var lastMatch = ""; @Volatile var tunerInfo = ""
        @Volatile var useYolo = false; @Volatile var yoloGpu = false; @Volatile var yoloInfo = ""
        @Volatile var yoloStretch = true; @Volatile var overlay = false
        @Volatile var yoloRunner: YoloRunner? = null
        /** Что показать на экране приложения про YOLO (обновляется даже если кадры не идут). */
        fun yoloStatus(): String { val r = yoloRunner; return if (r != null) r.statusLine() else yoloInfo }
    }
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ht: HandlerThread? = null
    private var io: ExecutorService? = null

    // --- запись: Загрузки/Brawlbot/rec_<ts>/ (через MediaStore, видно в любом файловом менеджере) ---
    private var recOpen = false
    private var recBase = ""                 // RELATIVE_PATH, например "Download/Brawlbot/rec_123/"
    private var legacyDir: File? = null      // для Android < 10
    private var logOut: OutputStream? = null // один поток на весь сеанс: строки сразу попадают в файл
    private val logLock = Any()

    // открывается лениво - при первом сохранении, даже если запись включили уже после старта бота
    private fun ensureRec() {
        if (recOpen) return
        recOpen = true
        val name = "rec_" + System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                recBase = Environment.DIRECTORY_DOWNLOADS + "/Brawlbot/" + name + "/"
                recDir = recBase
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "log.jsonl")
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, recBase)
                }
                val uri: Uri? = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                logOut = uri?.let { contentResolver.openOutputStream(it, "w") }
                if (logOut == null) recErr = "не удалось создать log.jsonl в Загрузках"
            } else {
                @Suppress("DEPRECATION")
                val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Brawlbot/$name")
                d.mkdirs()
                legacyDir = if (d.canWrite()) d else File(getExternalFilesDir(null), name).also { it.mkdirs() }
                recDir = legacyDir!!.absolutePath
                logOut = FileOutputStream(File(legacyDir!!, "log.jsonl"), true)
            }
        } catch (e: Exception) { recErr = e.javaClass.simpleName + ": " + (e.message ?: "") }
    }

    private fun saveFrame(name: String, bmp: Bitmap) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, recBase)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: return
            try { contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
            catch (e: Exception) { contentResolver.delete(uri, null, null) }
        } else {
            val d = legacyDir ?: return
            FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }
    }

    // разметка и список классов лежат рядом с кадрами (формат YOLO: один .txt на кадр)
    private fun saveText(name: String, text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, recBase)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: return
            try { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            catch (e: Exception) { contentResolver.delete(uri, null, null) }
        } else {
            val d = legacyDir ?: return
            File(d, name).writeText(text)
        }
    }

    private fun writeLog(line: String) {
        synchronized(logLock) {
            try { logOut?.write(line.toByteArray()); logOut?.flush() } catch (e: Exception) { recErr = e.javaClass.simpleName }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "Colt bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot").setContentTitle("Colt bot работает")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")!!
        proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        proj!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))

        val dm = resources.displayMetrics
        val sw = maxOf(dm.widthPixels, dm.heightPixels); val sh = minOf(dm.widthPixels, dm.heightPixels)
        val cw = sw / 3; val ch = sh / 3
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        vd = proj!!.createVirtualDisplay("cap", cw, ch, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)

        ht = HandlerThread("cv").also { it.start() }
        io = Executors.newSingleThreadExecutor()
        framesSaved = 0; frames = 0; running = true; recDir = ""; recErr = ""; recOpen = false
        val brain = Brain(cw, ch)
        val tuner = Tuner(getSharedPreferences("tuner", MODE_PRIVATE))
        brain.tuner = tuner
        yoloRunner?.close(); yoloRunner = null; yoloInfo = ""
        if (useYolo) {
            val mf = File(filesDir, "yolo.tflite")
            if (!mf.exists()) yoloInfo = "YOLO включена, но модель не загружена"
            else yoloRunner = YoloRunner(mf.absolutePath, yoloGpu, cw, ch, yoloStretch)   // грузится в фоне, старт бота не задерживает
        }
        BotService.inst?.setOverlay(overlay)
        var bmp: Bitmap? = null; var px = IntArray(0)
        var last = 0L; var tick = 0

        // событийный цикл: логика запускается, когда пришёл новый кадр; буферы переиспользуются
        reader!!.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) {
                val now = SystemClock.elapsedRealtime()
                if (now - last < 90) { img.close() } else {
                    last = now
                    try {
                        val p = img.planes[0]; val bw = p.rowStride / p.pixelStride
                        if (bmp == null || bmp!!.width != bw) { bmp = Bitmap.createBitmap(bw, ch, Bitmap.Config.ARGB_8888); px = IntArray(bw * ch) }
                        bmp!!.copyPixelsFromBuffer(p.buffer); img.close()
                        bmp!!.getPixels(px, 0, bw, 0, 0, bw, ch)
                        brain.mode = mode
                        val yr = yoloRunner
                        if (yr != null) {
                            yr.submit(px, bw)                       // отдали кадр в фон, не ждём
                            val d = yr.fresh(now, 900)             // берём последний готовый результат, если он не слишком старый
                            brain.dets = d
                            brain.detAge = if (d != null) (now - yr.latestObs) / 1000.0 else 0.0
                            brain.detSeq = yr.runs
                            yoloInfo = yr.statusLine()
                        } else brain.dets = null
                        val a = brain.decide(px, bw, now)
                        lastMatch = brain.lastMatch; tunerInfo = tuner.summary()
                        st = a.state; sHp = a.hp; sAmmo = a.ammo; sEn = a.enemies; frames++
                        lastAct = "дв=(%.1f; %.1f)".format(a.mx, a.my) + (if (a.attack) " огонь" else "") + (if (a.sup) " супер" else "") + (if (a.gadget) " гаджет" else "")
                        BotService.inst?.act(a, sw, sh)
                        if (overlay) {
                            val od = OverlayData()
                            od.capW = cw; od.capH = ch
                            od.dets = ArrayList<Det>(brain.dets ?: emptyList<Det>())
                            od.heur = ArrayList<Det>(brain.hDets)
                            od.meX = a.meX; od.meY = a.meY
                            od.mx = a.mx; od.my = a.my; od.ax = a.ax; od.ay = a.ay; od.attack = a.attack
                            val ycs = yoloRunner
                            val ytxt = if (ycs == null) "YOLO выкл" else if (ycs.failed) "YOLO сбой" else if (ycs.runs == 0) "YOLO грузится" else "YOLO " + ycs.accel + " %.0f мс".format(ycs.ms)
                            od.hud = a.state + " | " + a.why + "\n" + (if (a.tinfo.isNotEmpty()) "цель: " + a.tinfo + "\n" else "") +
                                "хп " + (if (a.hp >= 0f) "" + (a.hp * 100).toInt() + "%" else "?") +
                                "  патр " + (if (a.ammo >= 0f) "%.1f".format(a.ammo * 3) else "?") + "  врагов " + a.enemies +
                                "  | " + ytxt + "  | жесты " + BotService.sent + "/" + BotService.done
                            BotService.inst?.overlayUpdate(od)
                        }
                        if (record && tick % 4 == 0) {
                            ensureRec()
                            // ширина буфера бывает больше кадра (выравнивание строк): режем до реальной ширины, иначе разметка поедет
                            val copy = if (bw == cw) bmp!!.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createBitmap(bmp!!, 0, 0, cw, ch)
                            val name = "f$tick.jpg"; val lname = "f$tick.txt"; val lbl = brain.labelText()
                            val line = """{"t":$now,"f":"$name","st":"${a.state}","mx":${a.mx},"my":${a.my},"ax":${a.ax},"ay":${a.ay},"atk":${a.attack},"tap":${a.attackTap},"sup":${a.sup},"gad":${a.gadget},"en":${a.enemies},"hp":${a.hp},"ammo":${a.ammo},"why":"${a.why}","tgt":"${a.tinfo}"}""" + "\n"
                            io?.execute { // запись на диск в отдельном потоке
                                try {
                                    saveFrame(name, copy)
                                    saveText(lname, lbl)
                                    if (framesSaved == 0) saveText("classes.txt", Cls.NAMES.joinToString("\n") + "\n")
                                    framesSaved++
                                } catch (e: Exception) { recErr = e.javaClass.simpleName }
                                copy.recycle()
                                writeLog(line)
                            }
                        }
                        tick++
                    } catch (e: Exception) {
                        // любой сбой в логике не должен убивать цикл: показываем ошибку в приложении и едем дальше
                        recErr = "brain: " + e.javaClass.simpleName + " " + (e.message ?: "")
                        try { img.close() } catch (_: Exception) {}
                    }
                }
            }
        }, Handler(ht!!.looper))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        try { io?.shutdown(); io?.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        synchronized(logLock) { try { logOut?.flush(); logOut?.close() } catch (_: Exception) {}; logOut = null }
        yoloRunner?.close(); yoloRunner = null
        BotService.inst?.setOverlay(false)
        ht?.quitSafely(); vd?.release(); reader?.close(); proj?.stop(); super.onDestroy()
    }
}
