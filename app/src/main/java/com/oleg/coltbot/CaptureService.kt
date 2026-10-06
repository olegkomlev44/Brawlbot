package com.oleg.coltbot

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.ContentValues
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
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
    }
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ht: HandlerThread? = null
    private var io: ExecutorService? = null

    // --- запись: Downloads/Brawlbot/rec_<ts>/ (через MediaStore, видно в любом файловом менеджере) ---
    private var recBase = ""                 // RELATIVE_PATH, например "Download/Brawlbot/rec_123/"
    private var legacyDir: File? = null      // запасной вариант для Android < 10
    private var logUri: Uri? = null
    private val logBuf = StringBuilder()
    private val logLock = Any()

    private fun openRecDir(ts: Long) {
        val name = "rec_$ts"
        if (Build.VERSION.SDK_INT >= 29) {
            recBase = Environment.DIRECTORY_DOWNLOADS + "/Brawlbot/" + name + "/"
            recDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Brawlbot/$name").absolutePath
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "log.jsonl")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, recBase)
            }
            logUri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Brawlbot/$name")
            d.mkdirs()
            if (d.canWrite()) { legacyDir = d; recDir = d.absolutePath }
            else { legacyDir = File(getExternalFilesDir(null), name); recDir = legacyDir!!.absolutePath }
        }
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

    private fun appendLog(line: String) {
        synchronized(logLock) { logBuf.append(line) }
    }

    private fun flushLog() {
        val chunk: String
        synchronized(logLock) {
            if (logBuf.isEmpty()) return
            chunk = logBuf.toString(); logBuf.setLength(0)
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val u = logUri ?: return
                contentResolver.openOutputStream(u, "wa")?.use { it.write(chunk.toByteArray()) }
            } else {
                val d = legacyDir ?: return
                FileOutputStream(File(d, "log.jsonl"), true).use { it.write(chunk.toByteArray()) }
            }
        } catch (_: Exception) {}
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
        framesSaved = 0; frames = 0; running = true
        if (record) openRecDir(System.currentTimeMillis()) else recDir = ""
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
                    val p = img.planes[0]; val bw = p.rowStride / p.pixelStride
                    if (bmp == null || bmp!!.width != bw) { bmp = Bitmap.createBitmap(bw, ch, Bitmap.Config.ARGB_8888); px = IntArray(bw * ch) }
                    bmp!!.copyPixelsFromBuffer(p.buffer); img.close()
                    bmp!!.getPixels(px, 0, bw, 0, 0, bw, ch)
                    brain.mode = mode
                    val a = brain.decide(px, bw, now)
                    st = a.state; sHp = a.hp; sAmmo = a.ammo; sEn = a.enemies; frames++
                    BotService.inst?.act(a, sw, sh)
                    if (record && tick % 4 == 0) {
                        val copy = bmp!!.copy(Bitmap.Config.ARGB_8888, false); val name = "f$tick.jpg"
                        val line = """{"t":$now,"f":"$name","st":"${a.state}","mx":${a.mx},"my":${a.my},"ax":${a.ax},"ay":${a.ay},"atk":${a.attack},"tap":${a.attackTap},"sup":${a.sup},"gad":${a.gadget},"en":${a.enemies},"hp":${a.hp},"ammo":${a.ammo}}""" + "\n"
                        io?.execute { // запись на диск в отдельном потоке
                            saveFrame(name, copy); copy.recycle(); framesSaved++
                            appendLog(line)
                            if (framesSaved % 32 == 0) flushLog()
                        }
                    }
                    tick++
                }
            }
        }, Handler(ht!!.looper))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        // дописываем остаток лога синхронно, пока сервис не умер
        try { io?.execute { flushLog() } } catch (_: Exception) {}
        try { io?.shutdown(); io?.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        flushLog()
        ht?.quitSafely(); vd?.release(); reader?.close(); proj?.stop(); super.onDestroy()
    }
}
