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
import android.os.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CaptureService : Service() {
    companion object { @Volatile var record = false }
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ht: HandlerThread? = null
    private var io: ExecutorService? = null

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
        val dir = File(getExternalFilesDir(null), "rec_" + System.currentTimeMillis()).apply { mkdirs() }
        val log = File(dir, "log.jsonl")
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
                    val a = brain.decide(px, bw, now)
                    BotService.inst?.act(a, sw, sh)
                    if (record && tick % 4 == 0) {
                        val copy = bmp!!.copy(Bitmap.Config.ARGB_8888, false); val name = "f$tick.jpg"
                        val line = """{"t":$now,"f":"$name","st":"${a.state}","mx":${a.mx},"my":${a.my},"ax":${a.ax},"ay":${a.ay},"atk":${a.attack},"sup":${a.sup},"gad":${a.gadget},"en":${a.enemies},"hp":${a.hp},"ammo":${a.ammo}}""" + "\n"
                        io?.execute { // запись на диск в отдельном потоке
                            FileOutputStream(File(dir, name)).use { copy.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                            log.appendText(line); copy.recycle()
                        }
                    }
                    tick++
                }
            }
        }, Handler(ht!!.looper))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        reader?.setOnImageAvailableListener(null, null)
        ht?.quitSafely(); io?.shutdown(); vd?.release(); reader?.close(); proj?.stop(); super.onDestroy()
    }
}
