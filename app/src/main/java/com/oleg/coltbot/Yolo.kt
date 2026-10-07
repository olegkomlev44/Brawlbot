package com.oleg.coltbot

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Одна детекция в пикселях кадра захвата: центр, размер, класс, уверенность. */
class Det(val cls: Int, val cx: Float, val cy: Float, val bw: Float, val bh: Float, val conf: Float)

/** Классы модели. Порядок ВАЖЕН: он же в data.yaml при обучении (tools/train_yolo26.py). */
object Cls {
    const val ME = 0; const val ENEMY = 1; const val ALLY = 2; const val BOX = 3; const val CUBE = 4; const val BULLET = 5
    val NAMES = arrayOf("me", "enemy", "ally", "box", "cube", "bullet")
}

/**
 * Детектор YOLO26 на Google LiteRT (CompiledModel API).
 * Модель экспортируется так: imgsz=(320, 704), float32, nms=False (сквозной выход YOLO26 [300 x 6]).
 * Если выход другой (классический [4+nc, anchors]) - декодируется и фильтруется NMS здесь же.
 */
class Yolo(path: String, wantGpu: Boolean, private val srcW: Int, private val srcH: Int) {
    companion object {
        const val IW = 704; const val IH = 320; const val NC = 6
    }

    var accel = "CPU"; private set
    var err = ""; private set
    var ms = 0f; private set          // сглаженное время кадра: подготовка + нейросеть + разбор
    var count = 0; private set        // сколько объектов найдено на последнем кадре

    private val model: CompiledModel = open(path, wantGpu)
    private val inBufs = model.createInputBuffers()
    private val outBufs = model.createOutputBuffers()

    private val xi0 = IntArray(IW); private val xi1 = IntArray(IW); private val xf = FloatArray(IW)
    private val yi0 = IntArray(IH); private val yi1 = IntArray(IH); private val yf = FloatArray(IH)
    private val inp = FloatArray(IW * IH * 3)
    private val res = ArrayList<Det>()
    // пороги уверенности по классам: me, enemy, ally, box, cube, bullet
    private val thr = floatArrayOf(0.35f, 0.40f, 0.40f, 0.35f, 0.30f, 0.30f)

    init {
        for (x in 0 until IW) {
            val s = ((x + 0.5f) * srcW / IW - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
            val i = s.toInt(); xi0[x] = i; xi1[x] = min(i + 1, srcW - 1); xf[x] = s - i
        }
        for (y in 0 until IH) {
            val s = ((y + 0.5f) * srcH / IH - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val i = s.toInt(); yi0[y] = i; yi1[y] = min(i + 1, srcH - 1); yf[y] = s - i
        }
    }

    private fun open(path: String, gpu: Boolean): CompiledModel {
        if (gpu) {
            try {
                val m = CompiledModel.create(path, CompiledModel.Options(Accelerator.GPU))
                warm(m)
                accel = "GPU"
                return m
            } catch (t: Throwable) {
                err = "GPU не вышло (" + t.javaClass.simpleName + "), работаю на CPU"
            }
        }
        val m = CompiledModel.create(path, CompiledModel.Options(Accelerator.CPU))
        accel = "CPU"
        return m
    }

    // пробный прогон: на некоторых телефонах GPU-делегат падает не при создании, а при первом запуске
    private fun warm(m: CompiledModel) {
        val ib = m.createInputBuffers(); val ob = m.createOutputBuffers()
        ib[0].writeFloat(FloatArray(IW * IH * 3))
        m.run(ib, ob)
        ob[0].readFloat()
    }

    private fun ch(c00: Int, c01: Int, c10: Int, c11: Int, fx: Float, fy: Float, sh: Int): Float {
        val a = ((c00 shr sh) and 255) * (1f - fx) + ((c01 shr sh) and 255) * fx
        val b = ((c10 shr sh) and 255) * (1f - fx) + ((c11 shr sh) and 255) * fx
        return (a * (1f - fy) + b * fy) * (1f / 255f)
    }

    /** px - кадр захвата (ARGB), stride - ширина строки в пикселях. Возвращает общий список (не хранить между кадрами). */
    fun detect(px: IntArray, stride: Int): List<Det> {
        val t0 = System.nanoTime()
        var o = 0
        for (y in 0 until IH) {
            val r0 = yi0[y] * stride; val r1 = yi1[y] * stride; val fy = yf[y]
            for (x in 0 until IW) {
                val a = xi0[x]; val b = xi1[x]; val fx = xf[x]
                val c00 = px[r0 + a]; val c01 = px[r0 + b]; val c10 = px[r1 + a]; val c11 = px[r1 + b]
                inp[o++] = ch(c00, c01, c10, c11, fx, fy, 16)   // R
                inp[o++] = ch(c00, c01, c10, c11, fx, fy, 8)    // G
                inp[o++] = ch(c00, c01, c10, c11, fx, fy, 0)    // B
            }
        }
        inBufs[0].writeFloat(inp)
        model.run(inBufs, outBufs)
        val out = outBufs[0].readFloat()
        res.clear()
        val na = (IW / 8) * (IH / 8) + (IW / 16) * (IH / 16) + (IW / 32) * (IH / 32)   // 4620 якорей
        if (out.size == (4 + NC) * na) decodeRaw(out, na) else decodeE2E(out)
        count = res.size
        val dt = (System.nanoTime() - t0) / 1e6f
        ms = if (ms == 0f) dt else ms * 0.8f + dt * 0.2f
        return res
    }

    private fun isCls(k: Float) = k >= -0.01f && k < NC + 0.5f && abs(k - Math.round(k)) < 0.01f

    // сквозной выход YOLO26: [300][6] или [6][300] = x1,y1,x2,y2,conf,class
    private fun decodeE2E(o: FloatArray) {
        val n = o.size / 6
        if (n <= 0) return
        var rowScore = 0; var colScore = 0
        for (i in 0 until min(n, 30)) {
            val c1 = o[i * 6 + 4]; val k1 = o[i * 6 + 5]
            if (c1 >= 0f && c1 <= 1.001f && isCls(k1)) rowScore++
            val c2 = o[4 * n + i]; val k2 = o[5 * n + i]
            if (c2 >= 0f && c2 <= 1.001f && isCls(k2)) colScore++
        }
        val row = rowScore >= colScore
        // координаты бывают нормированными (0..1) или в пикселях входа
        var mx = 0f
        for (i in 0 until min(n, 40)) mx = max(mx, if (row) o[i * 6 + 2] else o[2 * n + i])
        val norm = mx <= 2.0f
        val kx = srcW / (if (norm) 1f else IW.toFloat()); val ky = srcH / (if (norm) 1f else IH.toFloat())
        for (i in 0 until n) {
            val conf = if (row) o[i * 6 + 4] else o[4 * n + i]
            val cls = Math.round(if (row) o[i * 6 + 5] else o[5 * n + i])
            if (cls < 0 || cls >= NC || conf < thr[cls]) continue
            val x1 = if (row) o[i * 6] else o[i]
            val y1 = if (row) o[i * 6 + 1] else o[n + i]
            val x2 = if (row) o[i * 6 + 2] else o[2 * n + i]
            val y2 = if (row) o[i * 6 + 3] else o[3 * n + i]
            res.add(Det(cls, (x1 + x2) * 0.5f * kx, (y1 + y2) * 0.5f * ky, (x2 - x1) * kx, (y2 - y1) * ky, conf))
            if (res.size >= 60) break
        }
    }

    // классический выход [4+nc][якоря] или [якоря][4+nc]: cx,cy,w,h + оценки классов; NMS делаем сами
    private fun decodeRaw(o: FloatArray, na: Int) {
        val nf = 4 + NC
        val d1 = o[1] - o[0]; val d2 = o[2] - o[1]
        val colMajor = d1 > 0f && abs(d2 - d1) < 0.05f * d1
        var mx = 0f
        for (i in 0 until na) mx = max(mx, if (colMajor) o[i] else o[i * nf])
        val norm = mx <= 2.0f
        val kx = srcW / (if (norm) 1f else IW.toFloat()); val ky = srcH / (if (norm) 1f else IH.toFloat())
        val cand = ArrayList<Det>()
        for (i in 0 until na) {
            var bc = 0; var bs = 0f
            for (c in 0 until NC) {
                val s = if (colMajor) o[(4 + c) * na + i] else o[i * nf + 4 + c]
                if (s > bs) { bs = s; bc = c }
            }
            if (bs < thr[bc]) continue
            val cx = if (colMajor) o[i] else o[i * nf]
            val cy = if (colMajor) o[na + i] else o[i * nf + 1]
            val bw = if (colMajor) o[2 * na + i] else o[i * nf + 2]
            val bh = if (colMajor) o[3 * na + i] else o[i * nf + 3]
            cand.add(Det(bc, cx * kx, cy * ky, bw * kx, bh * ky, bs))
        }
        cand.sortByDescending { it.conf }
        for (d in cand) {
            var keep = true
            for (k in res) if (k.cls == d.cls && iou(k, d) > 0.5f) { keep = false; break }
            if (keep) res.add(d)
            if (res.size >= 60) break
        }
    }

    private fun iou(a: Det, b: Det): Float {
        val ax1 = a.cx - a.bw / 2; val ax2 = a.cx + a.bw / 2; val ay1 = a.cy - a.bh / 2; val ay2 = a.cy + a.bh / 2
        val bx1 = b.cx - b.bw / 2; val bx2 = b.cx + b.bw / 2; val by1 = b.cy - b.bh / 2; val by2 = b.cy + b.bh / 2
        val iw = min(ax2, bx2) - max(ax1, bx1); val ih = min(ay2, by2) - max(ay1, by1)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.bw * a.bh + b.bw * b.bh - inter + 1e-6f)
    }

    fun close() {
        try { model.close() } catch (_: Throwable) {}
    }
}
