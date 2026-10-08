package com.oleg.coltbot

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Одна детекция в пикселях кадра захвата. cls - РОЛЬ для бота (Cls.ENEMY, Cls.BOX ...) или -1 (прочее: кусты, стены, меню),
 * idx - номер класса в самой модели, name - его название (для оверлея).
 */
class Det(val cls: Int, val cx: Float, val cy: Float, val bw: Float, val bh: Float, val conf: Float,
          val name: String = "", val idx: Int = cls)

/** Роли, которые понимает бот. */
object Cls {
    const val ME = 0; const val ENEMY = 1; const val ALLY = 2; const val BOX = 3; const val CUBE = 4; const val BULLET = 5
    val NAMES = arrayOf("me", "enemy", "ally", "box", "cube", "bullet")

    /** Название класса модели -> роль. Названия из датасета Brawl-Stars-ai (Roboflow) и из нашего train_yolo26.py. */
    fun roleOf(name: String): Int = when (name.lowercase()) {
        "me" -> ME
        "enemy", "safe_enemy" -> ENEMY
        "friendly", "safe_friendly", "ally" -> ALLY
        "box", "pp_box", "power-box", "box_banka", "banka", "megabox" -> BOX
        "power-cube", "pp", "cube" -> CUBE
        "bullet" -> BULLET
        else -> -1
    }
}

/** Форма входа/выхода модели, прочитанная прямо из .tflite (flatbuffer), и названия классов из metadata.json в конце файла. */
class ModelInfo(val inShape: IntArray, val outShape: IntArray, val inType: Int, val names: List<String>) {
    val nchw: Boolean get() = inShape.size == 4 && inShape[1] == 3 && inShape[3] != 3
    val ih: Int get() = if (nchw) inShape[2] else inShape[1]
    val iw: Int get() = if (nchw) inShape[3] else inShape[2]

    companion object {
        fun read(path: String): ModelInfo {
            val data = File(path).readBytes()
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            fun i32(p: Int) = bb.getInt(p)
            fun u16(p: Int) = bb.getShort(p).toInt() and 0xFFFF
            // адрес поля таблицы flatbuffer или -1, если поля нет
            fun fieldPos(t: Int, slot: Int): Int {
                val vt = t - i32(t); val vs = u16(vt); val o = 4 + 2 * slot
                if (o >= vs) return -1
                val f = u16(vt + o)
                return if (f == 0) -1 else t + f
            }
            fun vec(t: Int, slot: Int): Int { val p = fieldPos(t, slot); return if (p < 0) -1 else p + i32(p) }
            fun tbl(v: Int, i: Int): Int { val p = v + 4 + 4 * i; return p + i32(p) }
            fun ints(v: Int): IntArray = if (v < 0) IntArray(0) else IntArray(i32(v)) { i32(v + 4 + 4 * it) }

            val root = i32(0)
            val sg = tbl(vec(root, 2), 0)                 // Model.subgraphs[0]
            val tensors = vec(sg, 0)
            val inIdx = ints(vec(sg, 1)); val outIdx = ints(vec(sg, 2))
            fun shapeOf(idx: Int): IntArray = ints(vec(tbl(tensors, idx), 0))
            val tIn = tbl(tensors, inIdx[0])
            val tp = fieldPos(tIn, 1)
            val inType = if (tp < 0) 0 else bb.get(tp).toInt()
            return ModelInfo(shapeOf(inIdx[0]), shapeOf(outIdx[0]), inType, readNames(data))
        }

        // Ultralytics дописывает в конец .tflite zip с metadata.json (там список классов)
        private fun readNames(data: ByteArray): List<String> {
            try {
                var off = -1
                var i = data.size - 4
                while (i >= 0) {
                    if (data[i] == 0x50.toByte() && data[i + 1] == 0x4B.toByte() && data[i + 2] == 3.toByte() && data[i + 3] == 4.toByte()) { off = i; break }
                    i--
                }
                if (off < 0) return emptyList()
                ZipInputStream(ByteArrayInputStream(data, off, data.size - off)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.name.endsWith("metadata.json")) {
                            val nm = JSONObject(String(z.readBytes())).getJSONObject("names")
                            val out = ArrayList<String>()
                            var k = 0
                            while (nm.has(k.toString())) { out.add(nm.getString(k.toString())); k++ }
                            return out
                        }
                    }
                }
            } catch (_: Throwable) {}
            return emptyList()
        }
    }
}

/**
 * Детектор YOLO26 на Google LiteRT (CompiledModel API).
 * Сам определяет по файлу модели: размер входа, NCHW/NHWC, число классов, сквозной выход [300x6] или классический [4+nc, якоря].
 * stretch = true: кадр растягивается в квадрат (так делает Roboflow при "Stretch to 640x640"), false: с серыми полями (letterbox).
 */
class Yolo(path: String, wantGpu: Boolean, private val srcW: Int, private val srcH: Int, private val stretch: Boolean) {
    var accel = "CPU"; private set
    var err = ""; private set
    var ms = 0f; private set          // сглаженное время кадра: подготовка + нейросеть + разбор
    var count = 0; private set        // сколько объектов найдено на последнем кадре
    val info: ModelInfo = ModelInfo.read(path)
    val names: List<String> = info.names
    private val iw = info.iw; private val ih = info.ih
    private val nchw = info.nchw

    private val model: CompiledModel = open(path, wantGpu)
    private val inBufs = model.createInputBuffers()
    private val outBufs = model.createOutputBuffers()

    private val role = IntArray(max(names.size, 1)) { Cls.roleOf(names.getOrElse(it) { "" }) }
    private val xi0 = IntArray(iw); private val xi1 = IntArray(iw); private val xf = FloatArray(iw)
    private val yi0 = IntArray(ih); private val yi1 = IntArray(ih); private val yf = FloatArray(ih)
    private val xin = BooleanArray(iw); private val yin = BooleanArray(ih)
    private val inp = FloatArray(iw * ih * 3)
    private val res = ArrayList<Det>()
    // letterbox: масштаб и отступы
    private val lbScale = min(iw.toFloat() / srcW, ih.toFloat() / srcH)
    private val padX = (iw - srcW * lbScale) / 2f; private val padY = (ih - srcH * lbScale) / 2f

    init {
        if (info.inType != 0) throw IllegalStateException("модель не float32 (тип " + info.inType + "): экспортируй без int8/half")
        for (x in 0 until iw) {
            val sx = if (stretch) (x + 0.5f) * srcW / iw - 0.5f else (x + 0.5f - padX) / lbScale - 0.5f
            xin[x] = stretch || (sx >= -0.5f && sx <= srcW - 0.5f)
            val s = sx.coerceIn(0f, (srcW - 1).toFloat())
            val i = s.toInt(); xi0[x] = i; xi1[x] = min(i + 1, srcW - 1); xf[x] = s - i
        }
        for (y in 0 until ih) {
            val sy = if (stretch) (y + 0.5f) * srcH / ih - 0.5f else (y + 0.5f - padY) / lbScale - 0.5f
            yin[y] = stretch || (sy >= -0.5f && sy <= srcH - 0.5f)
            val s = sy.coerceIn(0f, (srcH - 1).toFloat())
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
        ib[0].writeFloat(FloatArray(info.iw * info.ih * 3))
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
        val plane = iw * ih
        val pad = 114f / 255f
        for (y in 0 until ih) {
            val r0 = yi0[y] * stride; val r1 = yi1[y] * stride; val fy = yf[y]
            val yok = yin[y]
            for (x in 0 until iw) {
                val o = y * iw + x
                var r = pad; var g = pad; var b = pad
                if (yok && xin[x]) {
                    val a0 = xi0[x]; val a1 = xi1[x]; val fx = xf[x]
                    val c00 = px[r0 + a0]; val c01 = px[r0 + a1]; val c10 = px[r1 + a0]; val c11 = px[r1 + a1]
                    r = ch(c00, c01, c10, c11, fx, fy, 16)
                    g = ch(c00, c01, c10, c11, fx, fy, 8)
                    b = ch(c00, c01, c10, c11, fx, fy, 0)
                }
                if (nchw) { inp[o] = r; inp[o + plane] = g; inp[o + 2 * plane] = b }
                else { inp[o * 3] = r; inp[o * 3 + 1] = g; inp[o * 3 + 2] = b }
            }
        }
        inBufs[0].writeFloat(inp)
        model.run(inBufs, outBufs)
        val out = outBufs[0].readFloat()
        res.clear()
        val os = info.outShape
        if (os.size == 3 && os[2] == 6 && os[1] <= 2000) decodeE2E(out, os[1], true)
        else if (os.size == 3 && os[1] == 6 && os[2] <= 2000) decodeE2E(out, os[2], false)
        else if (os.size == 3 && os[1] < os[2]) decodeRaw(out, os[1], os[2], true)
        else if (os.size == 3) decodeRaw(out, os[2], os[1], false)
        else throw IllegalStateException("неожиданная форма выхода: " + os.joinToString("x"))
        count = res.size
        val dt = (System.nanoTime() - t0) / 1e6f
        ms = if (ms == 0f) dt else ms * 0.8f + dt * 0.2f
        return res
    }

    // порог уверенности: врагов и ящики берём строже, остальное для оверлея мягче
    private fun thr(idx: Int): Float = when (role.getOrElse(idx) { -1 }) {
        Cls.ENEMY -> 0.40f; Cls.ME -> 0.35f; Cls.BOX -> 0.35f; Cls.CUBE -> 0.30f; Cls.ALLY -> 0.40f; else -> 0.45f
    }

    private fun mk(idx: Int, cx: Float, cy: Float, w: Float, h: Float, conf: Float) {
        // из координат входа сети обратно в пиксели кадра захвата
        val sx: Float; val sy: Float; val sw: Float; val sh: Float
        if (stretch) { sx = cx * srcW / iw; sy = cy * srcH / ih; sw = w * srcW / iw; sh = h * srcH / ih }
        else { sx = (cx - padX) / lbScale; sy = (cy - padY) / lbScale; sw = w / lbScale; sh = h / lbScale }
        val nm = names.getOrElse(idx) { "#$idx" }
        res.add(Det(role.getOrElse(idx) { -1 }, sx, sy, sw, sh, conf, nm, idx))
    }

    // сквозной выход YOLO26: x1,y1,x2,y2,conf,class
    private fun decodeE2E(o: FloatArray, n: Int, row: Boolean) {
        var mx = 0f
        for (i in 0 until min(n, 40)) mx = max(mx, if (row) o[i * 6 + 2] else o[2 * n + i])
        val k = if (mx <= 2.0f) 1f else 0f      // нормированные координаты (0..1) или пиксели входа
        for (i in 0 until n) {
            val conf = if (row) o[i * 6 + 4] else o[4 * n + i]
            val cls = Math.round(if (row) o[i * 6 + 5] else o[5 * n + i])
            if (cls < 0 || conf < thr(cls)) continue
            var x1 = if (row) o[i * 6] else o[i]; var y1 = if (row) o[i * 6 + 1] else o[n + i]
            var x2 = if (row) o[i * 6 + 2] else o[2 * n + i]; var y2 = if (row) o[i * 6 + 3] else o[3 * n + i]
            if (k > 0.5f) { x1 *= iw; x2 *= iw; y1 *= ih; y2 *= ih }
            mk(cls, (x1 + x2) * 0.5f, (y1 + y2) * 0.5f, x2 - x1, y2 - y1, conf)
            if (res.size >= 80) break
        }
    }

    // классический выход: [признаки][якоря] (colMajor) или [якоря][признаки]; признаки = cx,cy,w,h + оценки классов. NMS - здесь
    private val bestS = FloatArray(9000); private val bestC = IntArray(9000)
    private fun decodeRaw(o: FloatArray, nf: Int, na: Int, colMajor: Boolean) {
        val nc = nf - 4
        if (na > bestS.size || nc <= 0) throw IllegalStateException("неожиданная форма выхода")
        java.util.Arrays.fill(bestS, 0, na, 0f)
        if (colMajor) {
            for (c in 0 until nc) {
                val base = (4 + c) * na
                for (i in 0 until na) { val s = o[base + i]; if (s > bestS[i]) { bestS[i] = s; bestC[i] = c } }
            }
        } else {
            for (i in 0 until na) {
                var bs = 0f; var bc = 0
                val base = i * nf + 4
                for (c in 0 until nc) { val s = o[base + c]; if (s > bs) { bs = s; bc = c } }
                bestS[i] = bs; bestC[i] = bc
            }
        }
        var mx = 0f
        for (i in 0 until min(na, 200)) mx = max(mx, if (colMajor) o[i] else o[i * nf])
        var mxAll = 0f
        for (i in 0 until na) mxAll = max(mxAll, if (colMajor) o[i] else o[i * nf])
        val norm = mxAll <= 2.0f && mx >= 0f
        val cand = ArrayList<Det>()
        for (i in 0 until na) {
            val s = bestS[i]; val c = bestC[i]
            if (s < thr(c)) continue
            var cx = if (colMajor) o[i] else o[i * nf]; var cy = if (colMajor) o[na + i] else o[i * nf + 1]
            var w = if (colMajor) o[2 * na + i] else o[i * nf + 2]; var h = if (colMajor) o[3 * na + i] else o[i * nf + 3]
            if (norm) { cx *= iw; w *= iw; cy *= ih; h *= ih }
            cand.add(Det(c, cx, cy, w, h, s))      // пока в координатах входа, роль/имя проставим после NMS
        }
        cand.sortByDescending { it.conf }
        val keep = ArrayList<Det>()
        for (d in cand) {
            var ok = true
            for (k in keep) if (k.cls == d.cls && iou(k, d) > 0.5f) { ok = false; break }
            if (ok) keep.add(d)
            if (keep.size >= 80) break
        }
        for (d in keep) mk(d.cls, d.cx, d.cy, d.bw, d.bh, d.conf)
    }

    private fun iou(a: Det, b: Det): Float {
        val ax1 = a.cx - a.bw / 2; val ax2 = a.cx + a.bw / 2; val ay1 = a.cy - a.bh / 2; val ay2 = a.cy + a.bh / 2
        val bx1 = b.cx - b.bw / 2; val bx2 = b.cx + b.bw / 2; val by1 = b.cy - b.bh / 2; val by2 = b.cy + b.bh / 2
        val iw2 = min(ax2, bx2) - max(ax1, bx1); val ih2 = min(ay2, by2) - max(ay1, by1)
        if (iw2 <= 0f || ih2 <= 0f) return 0f
        val inter = iw2 * ih2
        return inter / (a.bw * a.bh + b.bw * b.bh - inter + 1e-6f)
    }

    fun close() {
        try { model.close() } catch (_: Throwable) {}
    }
}
