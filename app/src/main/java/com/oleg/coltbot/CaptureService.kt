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
                        val a = brain.decide(px, bw, now)
                        st = a.state; sHp = a.hp; sAmmo = a.ammo; sEn = a.enemies; frames++
                        lastAct = "дв=(%.1f; %.1f)".format(a.mx, a.my) + (if (a.attack) " огонь" else "") + (if (a.sup) " супер" else "") + (if (a.gadget) " гаджет" else "")
                        BotService.inst?.act(a, sw, sh)
                        if (record && tick % 4 == 0) {
                            ensureRec()
                            val copy = bmp!!.copy(Bitmap.Config.ARGB_8888, false); val name = "f$tick.jpg"
                            val line = """{"t":$now,"f":"$name","st":"${a.state}","mx":${a.mx},"my":${a.my},"ax":${a.ax},"ay":${a.ay},"atk":${a.attack},"tap":${a.attackTap},"sup":${a.sup},"gad":${a.gadget},"en":${a.enemies},"hp":${a.hp},"ammo":${a.ammo}}""" + "\n"
                            io?.execute { // запись на диск в отдельном потоке
                                try { saveFrame(name, copy); framesSaved++ } catch (e: Exception) { recErr = e.javaClass.simpleName }
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
        ht?.quitSafely(); vd?.release(); reader?.close(); proj?.stop(); super.onDestroy()
    }
}
