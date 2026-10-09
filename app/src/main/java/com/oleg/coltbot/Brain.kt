package com.oleg.coltbot

import kotlin.math.*
import kotlin.random.Random

object Layout {
    // координаты кнопок сняты со скриншота 2340x1080
    const val JOY_X = 0.15f; const val JOY_Y = 0.77f
    const val ATK_X = 0.84f; const val ATK_Y = 0.625f
    const val SUP_X = 0.71f; const val SUP_Y = 0.736f
    const val GAD_X = 0.79f; const val GAD_Y = 0.88f
    const val TOO_CLOSE = 0.32f; const val TOO_FAR = 0.55f; const val SHOOT = 0.74f   // дальность Кольта ~11 клеток
    const val PLAYER_SPEED = 0.25f; const val BULLET_SPEED = 1.2f
    const val BAR_FULL = 0.0457f     // ширина полной полоски хп в долях ширины кадра
    const val LATENCY = 0.20f      // кадр + расчёт + свайп + доставка жеста (выстрел происходит при ОТПУСКАНИИ пальца)
    const val SLOT_DMG = 0.30f
    // --- новое ---
    const val FIRE_MIN_AMMO = 0.32f  // выстрел возможен от одного полного слота (1/3 шкалы)
    // Speedloader: true = старая версия (мгновенно перезаряжает 2 патрона); false = актуальная (2 быстрых выстрела с замедлением)
    const val GADGET_RELOADS = false
    const val GADGET_CHARGES = 3     // зарядов гаджета за матч
    const val MATCH_GAP_MS = 6000L   // если персонаж не виден дольше - считаем, что начался новый матч
}

enum class Mode { SHOWDOWN, TEAM }

class Action {
    var mx = 0f; var my = 0f; var ax = 0f; var ay = 0f
    var attack = false; var attackTap = false; var sup = false; var gadget = false
    var enemies = 0; var state = "ROAM"; var hp = -1f; var ammo = -1f; var eHp = -1f
    var meX = -1f; var meY = -1f     // где бот видит своего персонажа (px кадра), для оверлея
    var why = ""                      // почему стреляет / не стреляет (для оверлея и лога)
}

class Brain(private val w: Int, private val h: Int) {
    val out = Action()
    /** Режим игры. Пока выставляется вручную (brain.mode = Mode.TEAM). */
    var mode = Mode.SHOWDOWN
    /** Свежие детекции YOLO26 (LiteRT). null = детектор выключен: работают только цветовые эвристики. */
    var dets: List<Det>? = null
    /** Возраст детекций YOLO в секундах: нейросеть считает в фоне, пока я двигаюсь - сдвигаем найденное обратно. */
    var detAge = 0.0
    /** Номер результата YOLO: один и тот же результат нельзя засчитывать как новое наблюдение (скорость врага схлопнется в ноль). */
    var detSeq = 0
    private var lastSeq = -1
    private var holdUntil = 0L; private var holdNext = 0L
    private var curState = "ROAM"; private var stateSince = 0L
    /** Что нашли цветовые эвристики на этом кадре - автоматическая (грубая) разметка для обучения YOLO. */
    val hDets = ArrayList<Det>()

    // ================= (1) ТРЕКИНГ ВРАГОВ =================
    private class Track {
        var id = 0
        var x = 0.0; var y = 0.0      // мировые координаты (dead-reckoning)
        var rx = 0.0; var ry = 0.0    // относительно меня, последнее наблюдение
        var vx = 0.0; var vy = 0.0    // мировая скорость, px/с (сглаженная)
        var hp = 1.0; var hits = 0
        var first = 0L; var last = 0L; var stamp = 0L
        var obsT = 0L                 // когда было последнее НАСТОЯЩЕЕ наблюдение (время кадра, а не время расчёта)
        var cons = 0.5                // 0..1: насколько предсказуемо движется (для упреждения)
    }
    private val tracks = ArrayList<Track>()
    private val vis = ArrayList<Track>()
    private var nextId = 1
    private var tg: Track? = null

    // сырые детекции за кадр (подставь сюда любой другой детектор - например TFLite)
    private val N = 16
    private val sx = DoubleArray(N); private val sy = DoubleArray(N); private val sn = DoubleArray(N)
    private val x0 = DoubleArray(N); private val x1 = DoubleArray(N); private val y0 = DoubleArray(N); private val y1 = DoubleArray(N)
    private var nc = 0
    private val eX = DoubleArray(N); private val eY = DoubleArray(N); private val eH = DoubleArray(N)

    private var lastSuper = 0L; private var lastGadget = 0L; private var lastFlip = 0L; private var flipEvery = 1200L
    private var strafe = 1f; private var lastWander = 0L; private var wAng = 0.0
    private val sig = IntArray(64); private val psig = IntArray(64); private var hasSig = false
    private var stuckSince = 0L; private var escapeUntil = 0L; private var escDir = 1f
    private var lastMx = 0f; private var lastMy = 0f; private var prevHp = -1f
    private var pMeX = 0.0; private var pMeY = 0.0
    private var cdx = 0.0; private var cdy = 0.0
    private var wx = 0.0; private var wy = 0.0; private var lastT = 0L
    private var lastGhostShot = 0L
    private var retreatSince = 0L; private var standUntil = 0L
    private var pBarX = 0.0; private var pBarY = 0.0
    // самонастройка: параметры тактики и статистика матча для награды
    var tuner: Tuner? = null
    @Volatile var lastMatch = ""
    private val defParams = Params()
    private val prm: Params get() = tuner?.current ?: defParams
    private var dealt = 0.0; private var taken = 0.0; private var kills = 0; private var shots = 0
    private var lastAttackAt = 0L; private var matchFinished = true
    private var boxSince = 0L; private var boxIgnoreUntil = 0L   // таймаут: не залипать на недостижимом ящике
    private var matchStart = 0L; private var lastPlayerSeen = 0L; private var gadgetCharges = Layout.GADGET_CHARGES

    // блоки 8x8 для ядовитых облаков и кубков (цвет у них одинаковый, отличаем по размеру)
    private val B = 8; private val bwc = w / 8 + 1; private val bhc = h / 8 + 1
    private val pc = IntArray(bwc * bhc); private val isFull = BooleanArray(bwc * bhc)
    private val candX = IntArray(64); private val candY = IntArray(64)
    // карта поля прямо с экрана: стены/ящики и кусты по блокам 8x8
    private val obst = BooleanArray(bwc * bhc); private val bushB = BooleanArray(bwc * bhc)
    private val wcnt = IntArray(bwc * bhc); private val bcnt = IntArray(bwc * bhc)
    private val gc = FloatArray(bwc * bhc); private val par = IntArray(bwc * bhc); private val closed = BooleanArray(bwc * bhc)
    private val pathBuf = IntArray(bwc * bhc)
    private val heapCap = 8 * bwc * bhc + 16
    private val heapKey = FloatArray(heapCap); private val heapVal = IntArray(heapCap); private var hn = 0
    private var meXf = 0.0; private var meYf = 0.0; private var meBx = 0; private var meBy = 0
    private var healing = false; private var evadeUntil = 0L; private var hpMark = -1f; private var hpMarkT = 0L
    private var pathOk = false; private var pdx = 0.0; private var pdy = 0.0; private var lastPath = 0L
    private var gdx = 0.0; private var gdy = 0.0
    // разведка: клетки 0.5h в мировых координатах (dead-reckoning от старта матча); в какую сторону безопасно (от газа)
    private val GN = 40
    private val gVisit = IntArray(GN * GN); private val gBad = BooleanArray(GN * GN)
    private var exX = 0.0; private var exY = 0.0; private var exHas = false; private var exSince = 0L
    private var safeX = 0.0; private var safeY = 0.0; private var safeT = 0L
    private var pn = 0; private var pvux = 0.0; private var pvuy = 0.0

    // 16 направлений для оценки движения
    private val dirX = DoubleArray(16) { cos(it * PI / 8) }
    private val dirY = DoubleArray(16) { sin(it * PI / 8) }
    private var bestMx = 0.0; private var bestMy = 0.0

    private fun matchReset(now: Long) {
        tracks.clear(); vis.clear(); tg = null
        gadgetCharges = Layout.GADGET_CHARGES; matchStart = now; retreatSince = 0L; standUntil = 0L
        dealt = 0.0; taken = 0.0; kills = 0; shots = 0; lastAttackAt = 0L; matchFinished = false
        wx = 0.0; wy = 0.0; lastSuper = 0L; lastGadget = 0L; evadeUntil = 0L
        hpMark = -1f; healing = false; escapeUntil = 0L; stuckSince = 0L; prevHp = -1f
        boxSince = 0L; boxIgnoreUntil = 0L
        java.util.Arrays.fill(gVisit, 0); java.util.Arrays.fill(gBad, false)
        exHas = false; safeX = 0.0; safeY = 0.0; safeT = 0L
        curState = "ROAM"; stateSince = 0L; holdUntil = 0L; lastSeq = -1
    }

    // итог матча: награда -> самонастройка. Победу/место определить не из чего (нужен скрин итогового экрана),
    // поэтому награда считается по измеряемому: урон по врагам, оценка убийств, выживание, полученный урон
    private fun finishMatch(now: Long) {
        matchFinished = true
        val aliveS = (lastPlayerSeen - matchStart) / 1000.0
        if (aliveS < 15.0) return   // меню/шум, не матч
        val reward = dealt + 1.5 * kills - 0.6 * taken + min(aliveS / 60.0, 3.0)
        val note = tuner?.report(reward) ?: ""
        lastMatch = "жил " + "%.0f".format(aliveS) + " с, урон " + "%.1f".format(dealt) + ", убийств~" + kills +
            ", получено " + "%.1f".format(taken) + ", выстрелов " + shots + " -> награда " + "%.2f".format(reward) + " (" + note + ")"
    }

    // ================= (2) КАРТА: LOS и A* =================
    private fun cl(v: Int, hi: Int) = if (v < 0) 0 else if (v > hi) hi else v

    // Брезенхэм по экранной карте: есть ли стена между блоками (концы не считаются)
    private fun clear(ax0: Int, ay0: Int, bx0: Int, by0: Int): Boolean {
        val ax = cl(ax0, bwc - 1); val ay = cl(ay0, bhc - 1); val bx = cl(bx0, bwc - 1); val by = cl(by0, bhc - 1)
        var x = ax; var y = ay
        val dx = abs(bx - x); val dy = abs(by - y); val stepX = if (x < bx) 1 else -1; val stepY = if (y < by) 1 else -1
        var err = dx - dy
        while (!(x == bx && y == by)) {
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += stepX }
            if (e2 < dx) { err += dx; y += stepY }
            if (!(x == bx && y == by) && obst[y * bwc + x]) return false
        }
        return true
    }

    // "толстая" линия: учитывает ширину персонажа (две параллельные линии по бокам)
    private fun clearThick(ax: Int, ay: Int, bx: Int, by: Int): Boolean {
        if (!clear(ax, ay, bx, by)) return false
        val dx = (bx - ax).toDouble(); val dy = (by - ay).toDouble(); val l = hypot(dx, dy)
        if (l < 1.0) return true
        val ox = (-dy / l * 1.5).roundToInt(); val oy = (dx / l * 1.5).roundToInt()
        if (ox == 0 && oy == 0) return true
        return clear(ax + ox, ay + oy, bx + ox, by + oy) && clear(ax - ox, ay - oy, bx - ox, by - oy)
    }

    private fun los(rx: Double, ry: Double): Boolean =
        clear(meBx, meBy, ((meXf + rx) / B).toInt().coerceIn(0, bwc - 1), ((meYf + ry) / B).toInt().coerceIn(0, bhc - 1))

    private fun hpush(k: Float, v: Int) {
        if (hn >= heapCap) return
        var i = hn++
        while (i > 0) {
            val p = (i - 1) / 2
            if (heapKey[p] <= k) break
            heapKey[i] = heapKey[p]; heapVal[i] = heapVal[p]; i = p
        }
        heapKey[i] = k; heapVal[i] = v
    }

    private fun hpop(): Int {
        val top = heapVal[0]
        hn--
        if (hn > 0) {
            val k = heapKey[hn]; val v = heapVal[hn]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= hn) break
                if (c + 1 < hn && heapKey[c + 1] < heapKey[c]) c++
                if (heapKey[c] >= k) break
                heapKey[i] = heapKey[c]; heapVal[i] = heapVal[c]; i = c
            }
            heapKey[i] = k; heapVal[i] = v
        }
        return top
    }

    // стоимость клетки: у стен дороже, в яде очень дорого, рядом с врагами дороже (если avoid)
    private fun cellCost(x: Int, y: Int, i: Int, avoid: Boolean): Float {
        var c = 1f
        var nearWall = false
        for (yy in max(0, y - 1)..min(bhc - 1, y + 1)) for (xx in max(0, x - 1)..min(bwc - 1, x + 1)) if (obst[yy * bwc + xx]) nearWall = true
        if (nearWall) c += 1.2f
        if (isFull[i]) c += 10f
        if (avoid) {
            val r = h * 0.22
            for (t in vis) {
                val d = hypot(x * B + B / 2 - (meXf + t.rx), y * B + B / 2 - (meYf + t.ry))
                if (d < r) c += (4.0 * (1.0 - d / r)).toFloat()
            }
        }
        return c
    }

    private fun astar(tbx: Int, tby: Int, avoid: Boolean) {
        pathOk = false
        val si = meBy * bwc + meBx; val gi = tby * bwc + tbx
        if (si == gi) return
        java.util.Arrays.fill(gc, 1e9f); java.util.Arrays.fill(closed, false)
        hn = 0; gc[si] = 0f; par[si] = -1; hpush(0f, si)
        var found = false; var iter = 0
        while (hn > 0 && iter < 5000) {
            val ci = hpop()
            if (closed[ci]) continue
            closed[ci] = true; iter++
            if (ci == gi) { found = true; break }
            val cx = ci % bwc; val cy = ci / bwc
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= bwc || ny >= bhc) continue
                val ni = ny * bwc + nx
                if (closed[ni] || (obst[ni] && ni != gi)) continue
                // не режем углы стен по диагонали
                if (dx != 0 && dy != 0 && (obst[cy * bwc + nx] || obst[ny * bwc + cx])) continue
                val step = if (dx != 0 && dy != 0) 1.41f else 1f
                val ng = gc[ci] + step * cellCost(nx, ny, ni, avoid)
                if (ng < gc[ni]) {
                    gc[ni] = ng; par[ni] = ci
                    val ddx = abs(tbx - nx); val ddy = abs(tby - ny)
                    hpush(ng + (ddx + ddy) - 0.59f * min(ddx, ddy), ni)
                }
            }
        }
        if (!found) return
        // путь от цели к старту
        var len = 0; var cur = gi
        while (cur != -1 && len < pathBuf.size) { pathBuf[len++] = cur; cur = par[cur] }
        if (len < 2) return
        // lookahead: берём самую дальнюю точку пути, до которой есть чистая "толстая" линия -> плавное движение
        var pick = 1
        val maxJ = min(len - 1, 14)
        for (j in 1..maxJ) {
            val ni = pathBuf[len - 1 - j]
            if (clearThick(meBx, meBy, ni % bwc, ni / bwc)) pick = j else if (j > pick + 2) break
        }
        val ni = pathBuf[len - 1 - pick]
        val ddx = (ni % bwc - meBx).toDouble(); val ddy = (ni / bwc - meBy).toDouble()
        val l = hypot(ddx, ddy).coerceAtLeast(1.0)
        pdx = ddx / l; pdy = ddy / l; pathOk = true
    }

    // направление к цели: напрямую, а если на пути стена - по маршруту A*
    private fun goalToward(rx: Double, ry: Double, now: Long, avoid: Boolean) {
        val l = hypot(rx, ry).coerceAtLeast(1.0); gdx = rx / l; gdy = ry / l
        val tbx = ((meXf + rx) / B).toInt().coerceIn(0, bwc - 1); val tby = ((meYf + ry) / B).toInt().coerceIn(0, bhc - 1)
        if (clearThick(meBx, meBy, tbx, tby)) return
        if (now - lastPath > 120) { lastPath = now; astar(tbx, tby, avoid) }
        if (pathOk) { gdx = pdx; gdy = pdy }
    }

    // ================= (3) ДВИЖЕНИЕ: оценка 16 направлений =================
    private fun freeRun(dx: Double, dy: Double, maxSteps: Int): Int {
        for (st in 1..maxSteps) {
            val bx = floor((meXf + dx * st * B) / B).toInt(); val by = floor((meYf + dy * st * B) / B).toInt()
            if (bx < 0 || by < 0 || bx >= bwc || by >= bhc || obst[by * bwc + bx]) return st - 1
        }
        return maxSteps
    }

    /**
     * Выбирает лучшее из 16 направлений.
     * (gx,gy) - желаемое направление (единичный вектор или 0), wGoal - его вес;
     * band - желаемая дистанция до врага (доли h), wBand - вес; wStrafe - вес бокового манёвра;
     * wCover - бонус за позицию, из которой врага не видно (укрытие).
     */
    private fun pickMove(gx: Double, gy: Double, wGoal: Double, band: Double, wBand: Double, wStrafe: Double, wCover: Double) {
        val step = Layout.PLAYER_SPEED * h * 0.40
        val tgt = tg
        var perpX = 0.0; var perpY = 0.0
        if (tgt != null) { val d = hypot(tgt.rx, tgt.ry).coerceAtLeast(1.0); perpX = -tgt.ry / d; perpY = tgt.rx / d }
        var best = -1e18; var bk = 0
        for (k in 0 until 16) {
            val dx = dirX[k]; val dy = dirY[k]
            var s = 0.0
            // стены впереди
            val free = freeRun(dx, dy, 6)
            if (free < 3) s -= (3 - free) * 1.3
            if (free == 0) s -= 3.0
            // цель и инерция (чтобы не дрожать)
            s += wGoal * (dx * gx + dy * gy)
            s += 0.3 * (dx * lastMx + dy * lastMy)
            // дистанция до врагов
            if (wBand > 0.0) for (t in vis) {
                val nx = t.rx - dx * step; val ny = t.ry - dy * step
                val dd = hypot(nx, ny) / h
                val b = if (t === tgt) band else max(band, 0.50)
                s -= wBand * (if (t === tgt) 1.0 else 0.5) * abs(dd - b)
            }
            if (tgt != null) {
                s += wStrafe * (dx * perpX + dy * perpY) * strafe
                if (wCover > 0.0) {
                    val cbx = floor((meXf + dx * B * 3) / B).toInt(); val cby = floor((meYf + dy * B * 3) / B).toInt()
                    val ebx = ((meXf + tgt.rx) / B).toInt(); val eby = ((meYf + tgt.ry) / B).toInt()
                    if (!clear(cbx, cby, ebx, eby)) s += wCover
                }
            }
            // яд: не идти в сторону облака и не заходить в него
            if (pn > 0) {
                val toward = -(dx * pvux + dy * pvuy)
                if (toward > 0) s -= 2.5 * toward
                val pbx = floor((meXf + dx * B * 4) / B).toInt(); val pby = floor((meYf + dy * B * 4) / B).toInt()
                if (pbx in 0 until bwc && pby in 0 until bhc && isFull[pby * bwc + pbx]) s -= 2.0
            }
            if (s > best) { best = s; bk = k }
        }
        bestMx = dirX[bk]; bestMy = dirY[bk]
    }

    // ================= (4) ПРИЦЕЛ: точное упреждение =================
    // решаем |r + v*t| = s*t (пуля летит из моей позиции в момент выстрела с постоянной скоростью)
    private fun aimAt(t: Track, o: Action) {
        val s = Layout.BULLET_SPEED * h.toDouble()
        val myv = Layout.PLAYER_SPEED * h.toDouble()
        val mvx = if (stuckSince == 0L) lastMx * myv else 0.0; val mvy = if (stuckSince == 0L) lastMy * myv else 0.0
        // где враг будет к моменту выстрела (задержка конвейера) относительно меня
        val r0x = t.rx + (t.vx - mvx) * Layout.LATENCY; val r0y = t.ry + (t.vy - mvy) * Layout.LATENCY
        val a = t.vx * t.vx + t.vy * t.vy - s * s
        val b = 2 * (r0x * t.vx + r0y * t.vy)
        val c = r0x * r0x + r0y * r0y
        var tt = sqrt(c) / s
        if (abs(a) > 1e-6) {
            val disc = b * b - 4 * a * c
            if (disc >= 0) {
                val sq = sqrt(disc)
                val t1 = (-b - sq) / (2 * a); val t2 = (-b + sq) / (2 * a)
                val lo = min(t1, t2); val hi = max(t1, t2)
                tt = if (lo > 0.0) lo else if (hi > 0.0) hi else tt
            }
        }
        tt = tt.coerceIn(0.0, 1.0)
        // доверие к упреждению: мало наблюдений / враг мечется -> стреляем ближе к текущей позиции
        val conf = if (t.hits >= 4) 0.45 + 0.55 * t.cons else 0.35
        var lx = t.vx * tt * conf; var ly = t.vy * tt * conf
        val ll = hypot(lx, ly); val cap = h * 0.35
        if (ll > cap) { lx *= cap / ll; ly *= cap / ll }
        val tx = r0x + lx; val ty = r0y + ly
        val l = hypot(tx, ty).coerceAtLeast(1.0)
        o.ax = (tx / l).toFloat(); o.ay = (ty / l).toFloat()
    }

    // ================= ВОСПРИЯТИЕ (калибровка под твой экран - без изменений) =================
    private fun addRed(x: Int, y: Int) {
        for (i in 0 until nc) {
            if (abs(sx[i] / sn[i] - x) < w * 0.04 && abs(sy[i] / sn[i] - y) < h * 0.02) {
                sx[i] += x.toDouble(); sy[i] += y.toDouble(); sn[i] += 1.0
                x0[i] = min(x0[i], x.toDouble()); x1[i] = max(x1[i], x.toDouble()); y0[i] = min(y0[i], y.toDouble()); y1[i] = max(y1[i], y.toDouble()); return
            }
        }
        if (nc < N) { sx[nc] = x.toDouble(); sy[nc] = y.toDouble(); sn[nc] = 1.0
            x0[nc] = x.toDouble(); x1[nc] = x.toDouble(); y0[nc] = y.toDouble(); y1[nc] = y.toDouble(); nc++ }
    }

    private fun isOwnGreen(c: Int): Boolean { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return r < 150 && g > 195 && b < 130 }

    private fun ammoCols(px: IntArray, stride: Int, xs: Int, y: Int): Int {
        var cols = 0
        for (x in max(0, xs - 2)..min(w - 1, xs + 40)) {
            var hit = false
            for (yy in y + 3..min(h - 1, y + 11)) {
                val c = px[yy * stride + x]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (r > 190 && g in 90..150 && b < 90) { hit = true; break }
            }
            if (hit) cols++
        }
        return cols
    }

    // колонки, где под полоской хп есть шкала патронов: оранжевая (заряд) или тёмно-синяя (пустой слот)
    private fun barCols(px: IntArray, stride: Int, xs: Int, y: Int): Int {
        var cols = 0
        for (x in max(0, xs - 2)..min(w - 1, xs + 40)) {
            var hit = false
            for (yy in y + 3..min(h - 1, y + 10)) {
                val c = px[yy * stride + x]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if ((r > 190 && g in 90..150 && b < 90) || (r < 80 && g < 85 && b in 55..120 && b - r >= 15)) { hit = true; break }
            }
            if (hit) cols++
        }
        return cols
    }

    private fun runLen(px: IntArray, stride: Int, x: Int, y: Int): Int {
        var n = 0; var xx = x
        while (xx < w && isOwnGreen(px[y * stride + xx])) { n++; xx++ }
        return n
    }

    // ---- разведка: что в поле зрения, то «видели» ----
    private fun markSeen(now: Long) {
        val c = h * 0.5; val t = ((now - matchStart) / 1000).toInt() + 1
        val vx = w * 0.40; val vy = h * 0.38
        val i0 = floor((wx - vx) / c).toInt(); val i1 = floor((wx + vx) / c).toInt()
        val j0 = floor((wy - vy) / c).toInt(); val j1 = floor((wy + vy) / c).toInt()
        for (j in j0..j1) for (i in i0..i1) {
            val ci = i + GN / 2; val cj = j + GN / 2
            if (ci in 0 until GN && cj in 0 until GN) gVisit[cj * GN + ci] = t
        }
    }

    // выбирает клетку, которую давно не видели (и которая в сторону от газа); цель держим до 7 с или пока не дошли
    private fun explorePick(now: Long): Boolean {
        val c = h * 0.5
        if (exHas && now - exSince < 7000 && hypot(exX - wx, exY - wy) > h * 0.30) return true
        exHas = false
        val tSec = ((now - matchStart) / 1000).toInt() + 1
        val ci = floor(wx / c).toInt() + GN / 2; val cj = floor(wy / c).toInt() + GN / 2
        val sl = hypot(safeX, safeY); val swt = if (now - safeT < 20000) 1.0 else 0.3
        var best = -1e9; var bx = 0.0; var by = 0.0
        for (dj in -4..4) for (di in -4..4) {
            val i = ci + di; val j = cj + dj
            if (i < 0 || j < 0 || i >= GN || j >= GN) continue
            val idx = j * GN + i
            if (gBad[idx]) continue
            val cx = (i - GN / 2 + 0.5) * c; val cy = (j - GN / 2 + 0.5) * c
            val dx = cx - wx; val dy = cy - wy; val d = hypot(dx, dy) / h
            if (d < 0.55) continue
            val seen = gVisit[idx]
            val unseen = if (seen == 0) 1.0 else ((tSec - seen).coerceIn(0, 60) / 60.0)
            var sc = 1.2 * unseen - 0.3 * d + Random.nextDouble() * 0.15
            if (sl > 0.3) sc += 0.7 * swt * (dx * safeX + dy * safeY) / (d * h * sl)
            if (sc > best) { best = sc; bx = cx; by = cy }
        }
        if (best < -1e8) return false
        exX = bx; exY = by; exHas = true; exSince = now
        return true
    }

    // авторазметка для обучения YOLO: размер рамки берём от калиброванной ширины полоски хп (~1 клетка)
    private fun lab(cls: Int, x: Double, y: Double) {
        val bwid = (Layout.BAR_FULL * w * 0.95).toFloat()
        hDets.add(Det(cls, x.toFloat(), y.toFloat(), bwid, if (cls == Cls.BOX) bwid else bwid * 1.15f, 1f))
    }

    /** Текст разметки в формате YOLO (класс cx cy w h, всё в долях кадра) для сохранённого кадра. */
    fun labelText(): String {
        val sb = StringBuilder()
        for (d in hDets) {
            val cx = d.cx / w; val cy = d.cy / h
            if (cx < 0f || cx > 1f || cy < 0f || cy > 1f) continue
            sb.append(String.format(java.util.Locale.US, "%d %.5f %.5f %.5f %.5f\n", d.cls, cx, cy, min(d.bw / w, 1f), min(d.bh / h, 1f)))
        }
        return sb.toString()
    }

    // уже есть враг (из YOLO) рядом с этой точкой - не дублируем цветовой находкой
    private fun nearEnemy(n: Int, rx: Double, ry: Double): Boolean {
        for (i in 0 until n) if (hypot(eX[i] - rx, eY[i] - ry) < h * 0.09) return true
        return false
    }

    // хп врага, найденного YOLO: красная полоска прямо над ним; нет полоски - считаем полным
    private fun barHpNear(d: Det, cxd: Double, cyd: Double): Double {
        for (i in 0 until nc) {
            val wd = x1[i] - x0[i] + 1; val ht = y1[i] - y0[i] + 1
            if (sn[i] < 20 || wd < w * 0.023 || wd < 2.5 * ht || ht > 9) continue
            val cxb = (x0[i] + x1[i]) / 2.0
            if (abs(cxb - cxd) < d.bw * 0.9 + h * 0.05 && y0[i] < cyd + h * 0.03 && y0[i] > cyd - d.bh * 1.2 - h * 0.06)
                return (wd / (Layout.BAR_FULL * w * 0.95)).coerceAtMost(1.0)
        }
        return -1.0
    }

    // привязка детекций к трекам: постоянные id, своя скорость у каждого врага
    private fun updateTracks(now: Long, nd: Int, obsMs: Long) {
        val ag = ((now - obsMs) / 1000.0).coerceIn(0.0, 0.6)     // насколько наблюдение старше текущего момента
        for (i in 0 until nd) {
            val nx = wx + eX[i]; val ny = wy + eY[i]
            var best: Track? = null; var bestD = 1e18
            for (t in tracks) {
                if (t.stamp == now) continue
                val dtt = ((obsMs - t.obsT) / 1000.0).coerceIn(0.0, 0.6)
                val gate = h * 0.12 + Layout.PLAYER_SPEED * h * dtt
                val d = hypot(nx - (t.x + t.vx * dtt), ny - (t.y + t.vy * dtt))
                if (d < gate && d < bestD) { bestD = d; best = t }
            }
            if (best == null) {
                if (tracks.size >= 12) continue
                val t = Track(); t.id = nextId++
                t.x = nx; t.y = ny; t.rx = eX[i]; t.ry = eY[i]; t.hp = if (eH[i] < 0) 1.0 else eH[i]
                t.hits = 1; t.first = now; t.last = now; t.stamp = now; t.obsT = obsMs
                tracks.add(t)
            } else {
                val t: Track = best
                val dtt = (obsMs - t.obsT) / 1000.0
                if (dtt > 0.6) { t.vx = 0.0; t.vy = 0.0; t.cons = 0.3 }
                else if (dtt > 0.02) {
                    var mvx = (nx - t.x) / dtt; var mvy = (ny - t.y) / dtt
                    val cap = Layout.PLAYER_SPEED * h * 1.8
                    val mm = hypot(mvx, mvy)
                    if (mm > cap) { mvx *= cap / mm; mvy *= cap / mm }
                    val pm = hypot(t.vx, t.vy); val cm = hypot(mvx, mvy)
                    val still = Layout.PLAYER_SPEED * h * 0.08
                    if (pm > still && cm > still) {
                        val cs = (t.vx * mvx + t.vy * mvy) / (pm * cm)
                        t.cons = 0.8 * t.cons + 0.2 * ((cs + 1) / 2).coerceIn(0.0, 1.0)
                    } else if (pm <= still && cm <= still) t.cons = 0.8 * t.cons + 0.2
                    else t.cons = 0.8 * t.cons + 0.06
                    // быстрое сглаживание: при 10-11 к/с alpha 0.45 даёт ~0.25 c лага в оценке скорости
                    t.vx += 0.70 * (mvx - t.vx); t.vy += 0.70 * (mvy - t.vy)
                }
                val nh = if (eH[i] < 0) t.hp else eH[i]       // полоски хп не нашли - оставляем прежнее значение
                val drop = t.hp - nh   // у врага просела полоска хп сразу после нашего выстрела - засчитываем урон
                if (drop > 0.04 && drop < 0.7 && now - lastAttackAt < 800) dealt += drop
                t.x = nx; t.y = ny; t.hp = nh; t.obsT = obsMs
                t.hits++; t.last = now; t.stamp = now
            }
        }
        // положение «сейчас» = наблюдение + скорость * возраст (YOLO считает в фоне, результат бывает на 100-400 мс старше)
        for (t in tracks) if (t.stamp == now) { t.rx = t.x + t.vx * ag - wx; t.ry = t.y + t.vy * ag - wy }
        // враг не попал в свежий результат (моргнула детекция) - ещё ~0.45 с ведём его по скорости, чтобы не дёргать тактику
        for (t in tracks) {
            if (t.stamp == now || t.hits < 2) continue
            val a2 = (now - t.obsT) / 1000.0
            if (a2 in 0.0..0.45) { t.rx = t.x + t.vx * a2 - wx; t.ry = t.y + t.vy * a2 - wy; t.last = now; t.stamp = now }
        }
        var tk = tracks.size - 1
        while (tk >= 0) {
            val t = tracks[tk]
            if (now - t.last > 2500) {
                // пропал почти без хп и мы недавно по нему стреляли - вероятно убит
                if (t.hits >= 4 && t.hp <= 0.25 && lastAttackAt >= t.last - 1500) kills++
                tracks.removeAt(tk)
            }
            tk--
        }
    }

    fun decide(px: IntArray, stride: Int, now: Long): Action {
        nc = 0; hDets.clear(); java.util.Arrays.fill(pc, 0); java.util.Arrays.fill(wcnt, 0); java.util.Arrays.fill(bcnt, 0)
        val dt = if (lastT > 0) ((now - lastT) / 1000.0).coerceAtMost(0.3) else 0.0
        if (stuckSince == 0L) { wx += lastMx * Layout.PLAYER_SPEED * h * dt; wy += lastMy * Layout.PLAYER_SPEED * h * dt }
        lastT = now

        // ---- один проход по кадру ----
        val xa = (w * 0.09).toInt(); val xb = (w * 0.91).toInt(); val ya = (h * 0.11).toInt()
        val uiLx = (w * 0.224).toInt(); val uiLy = (h * 0.61).toInt(); val uiRx = (w * 0.769).toInt(); val uiRy = (h * 0.458).toInt()
        var nCand = 0
        for (y in ya until h) {
            var run = 0
            for (x in xa until xb) {
                if ((x < uiLx && y > uiLy) || (x > uiRx && y > uiRy)) { run = 0; continue }
                val c = px[y * stride + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (r > 190 && g < 90 && b < 95 && r - g > 105) addRed(x, y)   // только сама полоска хп, не красное кольцо под целью
                val bi = (y / B) * bwc + x / B
                if (g > 200 && r in 141..189 && b in 111..169) pc[bi]++
                else if (b > 150 && r in 80..144 && g in 80..144 && abs(r - g) < 16 && b - r > 40) wcnt[bi]++
                else if (r > 140 && g < 115 && b in 96..149 && r - g > 40) wcnt[bi]++
                else if (b > 232 && r in 170..250 && g in 165..248 && b - r in 8..75) wcnt[bi]++          // светлые стены с крестом
                else if (b > 105 && r in 60..99 && g in 60..99 && abs(r - g) < 12 && b - r in 35..70) wcnt[bi]++ // их тёмные грани
                else if (r < 80 && g in 96..149 && b > 115 && g - r > 40) bcnt[bi]++
                if (r < 150 && g > 195 && b < 130) { run++; if (run == 12 && nCand < 64 && y >= 3 && y + 6 < h) {
                    // настоящая полоска хп тонкая: выше и ниже посередине зелёного нет (отсеивает зелёные зоны и площадки)
                    val xm = x + 1
                    if (!isOwnGreen(px[(y - 3) * stride + xm]) && !isOwnGreen(px[(y + 6) * stride + xm])) { candX[nCand] = x - 11; candY[nCand] = y; nCand++ }
                } } else run = 0
            }
        }
        // ---- мой персонаж: зелёная полоска хп + оранжевые патроны под ней ----
        var meX = w / 2.0; var meY = h / 2.0; var hp = -1f; var ammo = -1f
        var bestScore = 0.0; var bk = -1; var bCols = 0
        for (k in 0 until nCand) {
            val bar = barCols(px, stride, candX[k], candY[k])
            if (bar < 30) continue
            val cols = ammoCols(px, stride, candX[k], candY[k])
            var score = bar + 2.0 * cols
            // не прыгаем между кандидатами: предпочитаем тот, что рядом с прошлой позицией
            if (lastPlayerSeen > 0 && now - lastPlayerSeen < 1500 && abs(candX[k] + Layout.BAR_FULL * w / 2.0 - pBarX) < w * 0.06 && abs(candY[k] - pBarY) < h * 0.06) score += 40.0
            if (score > bestScore) { bestScore = score; bk = k; bCols = cols }
        }
        if (bk >= 0) {
            val xs = candX[bk]; val yb = candY[bk]
            var best = 0
            for (yy in yb + 1..min(h - 1, yb + 4)) best = max(best, runLen(px, stride, xs, yy))
            hp = (best / (Layout.BAR_FULL * w)).toFloat().coerceIn(0f, 1f)
            ammo = (bCols / (0.0397f * w)).coerceIn(0f, 1f)   // пустая шкала = 0 патронов (а не "неизвестно")
            meX = xs + Layout.BAR_FULL * w / 2.0; meY = yb + h * 0.083
            pBarX = meX; pBarY = yb.toDouble()
            lab(Cls.ME, meX, meY)
        }
        if (bk < 0) {
            // полоски нет (например, патроны кончились и шкала не видна) - позицию берём у YOLO
            val ym = dets
            if (ym != null) { var bc = 0f; for (d in ym) if (d.cls == Cls.ME && d.conf >= 0.5f && d.conf > bc) { bc = d.conf; meX = d.cx.toDouble(); meY = d.cy.toDouble() } }   // «я» всегда около центра экрана, сдвиг не нужен
        }
        meXf = meX; meYf = meY
        meBx = (meX / B).toInt().coerceIn(0, bwc - 1); meBy = (meY / B).toInt().coerceIn(0, bhc - 1)
        if (hp < 0f && !matchFinished && matchStart > 0L && lastPlayerSeen > 0L && now - lastPlayerSeen > Layout.MATCH_GAP_MS) finishMatch(now)
        if (hp >= 0f) {
            // (6) новый матч: персонаж долго не был виден (меню/смерть) -> сбрасываем состояние
            if (lastPlayerSeen == 0L || now - lastPlayerSeen > Layout.MATCH_GAP_MS) { if (!matchFinished && matchStart > 0L) finishMatch(now); matchReset(now) }
            lastPlayerSeen = now
            markSeen(now)
            if (hp < 0.6f) healing = true else if (hp > 0.9f) healing = false
            if (now - hpMarkT > 1500) { if (hpMark >= 0f && hpMark - hp > 0.25f) evadeUntil = now + 2000; hpMark = hp; hpMarkT = now }
        }
        // ---- яд, кубки, кусты, карта ----
        for (i in pc.indices) isFull[i] = pc[i] >= 50
        var pvx = 0.0; var pvy = 0.0; pn = 0
        var gax = 0.0; var gay = 0.0; var gn = 0
        var csx = 0.0; var csy = 0.0; var cnn = 0
        var bushD = 1e9; var bushX = 0.0; var bushY = 0.0
        for (by in 0 until bhc) for (bx in 0 until bwc) {
            val i = by * bwc + bx
            obst[i] = wcnt[i] >= 20; bushB[i] = bcnt[i] >= 32 && pc[i] < 10
            if (bushB[i]) { val ddx = bx * B + B / 2 - meX; val ddy = by * B + B / 2 - meY; val dd = hypot(ddx, ddy)
                if (dd < bushD && dd < h * 0.6) { bushD = dd; bushX = ddx; bushY = ddy } }
            if (isFull[i]) {
                val dx = meX - (bx * B + B / 2); val dy = meY - (by * B + B / 2)
                gax += dx; gay += dy; gn++
                if (hypot(dx, dy) < h * 0.30) { pvx += dx; pvy += dy; pn++ }
            } else if (pc[i] >= 20) {
                var near = false
                for (yy in max(0, by - 4)..min(bhc - 1, by + 4)) for (xx in max(0, bx - 4)..min(bwc - 1, bx + 4)) if (isFull[yy * bwc + xx]) near = true
                if (!near) { csx += bx * B + B / 2; csy += by * B + B / 2; cnn++ }
            }
        }
        for (yy in max(0, meBy - 1)..min(bhc - 1, meBy + 1)) for (xx in max(0, meBx - 1)..min(bwc - 1, meBx + 1)) obst[yy * bwc + xx] = false
        if (gn >= 6) {
            val gl = hypot(gax, gay)
            if (gl > 1e-6) { safeX = 0.85 * safeX + 0.15 * gax / gl; safeY = 0.85 * safeY + 0.15 * gay / gl; safeT = now }
        }
        val pl = hypot(pvx, pvy)
        if (pl > 1e-6) { pvux = pvx / pl; pvuy = pvy / pl } else { pvux = 0.0; pvuy = 0.0 }
        val inBush = bushB[meBy * bwc + meBx]
        // кубок: если YOLO его видит - берём ближайший, иначе старый цветовой поиск по блокам
        var yCube = false; var ycx = 0.0; var ycy = 0.0; var ycd = 1e9
        val yd0 = dets
        // пока считалась нейросеть, я шёл (lastMx,lastMy): камера ехала за мной, всё найденное сдвинулось на экране обратно
        val shX = lastMx * Layout.PLAYER_SPEED * h * detAge; val shY = lastMy * Layout.PLAYER_SPEED * h * detAge
        if (yd0 != null) for (d in yd0) if (d.cls == Cls.CUBE) {
            val dd = hypot(d.cx - shX - meX, d.cy - shY - meY)
            if (dd < ycd && dd > h * 0.03) { ycd = dd; ycx = d.cx - shX; ycy = d.cy - shY; yCube = true }
        }
        val cubeOk = yCube || cnn in 1..8
        val dsx = if (yCube) ycx else if (cubeOk) csx / cnn else 0.0
        val dsy = if (yCube) ycy else if (cubeOk) csy / cnn else 0.0
        val dn = if (cubeOk) 1 else 0

        // ---- застревание: экран не скроллится И я на месте ----
        var k = 0
        for (gy in 0 until 8) for (gx in 0 until 8) sig[k++] = px[((gy + 1) * h / 9) * stride + (gx + 1) * w / 9] and 0xFF
        val moved = abs(meX - pMeX) + abs(meY - pMeY)
        pMeX = meX; pMeY = meY
        if (hasSig && lastMx * lastMx + lastMy * lastMy > 0.1f) {
            var d = 0; var lo = 255; var hi = 0
            for (i in 0 until 64) { d += abs(sig[i] - psig[i]); lo = min(lo, sig[i]); hi = max(hi, sig[i]) }
            if (d < 64 * 4 && moved < 2.0 && hi - lo > 30) {
                if (stuckSince == 0L) stuckSince = now
                if (now - stuckSince > 700) {
                    escapeUntil = now + 800; escDir = if (Random.nextBoolean()) 1f else -1f; stuckSince = 0; wAng += 1.6 * escDir
                    if (exHas) { val gi = (floor(exY / (h * 0.5)).toInt() + GN / 2) * GN + floor(exX / (h * 0.5)).toInt() + GN / 2; if (gi in 0 until GN * GN) gBad[gi] = true; exHas = false }
                }
            } else stuckSince = 0
        }
        System.arraycopy(sig, 0, psig, 0, 64); hasSig = true

        // ---- враги (есть имя) и ящики (имени нет) ----
        var nd = 0
        var boxX = 0.0; var boxY = 0.0; var boxD = 1e9; var nb = 0
        // YOLO: враги и ящики по самому телу (центр точный, не зависит от цвета полоски хп)
        var yBox = false
        val yd = dets
        val yFresh = yd != null && detSeq != lastSeq          // новый результат нейросети (а не повтор прошлого)
        if (yd != null) for (d in yd) {
            val rx = d.cx - shX - meX; val ry = d.cy - shY - meY; val dd = hypot(rx, ry)
            if (d.cls == Cls.ENEMY && yFresh && nd < N && dd > h * 0.06 && !nearEnemy(nd, rx, ry)) {   // «Enemy» и «enemy» в датасете - один и тот же враг
                eX[nd] = rx; eY[nd] = ry; eH[nd] = barHpNear(d, meX + rx, meY + ry); nd++
            } else if (d.cls == Cls.BOX && dd > h * 0.08 && d.cy > h * 0.12) {
                yBox = true; nb++
                if (dd < boxD) { boxD = dd; boxX = rx; boxY = ry }
            }
        }
        for (i in 0 until nc) {
            val wd = x1[i] - x0[i] + 1; val ht = y1[i] - y0[i] + 1
            if (sn[i] < 20 || wd < w * 0.023 || wd < 2.5 * ht || ht > 9) continue
            // это МОИ полоски (патроны под хп): раньше принимались за ящик на расстоянии 0
            val cxb = (x0[i] + x1[i]) / 2.0
            if (hp >= 0f && abs(cxb - meX) < w * 0.03 && y0[i] > meY - h * 0.085 && y0[i] < meY - h * 0.03) continue
            var pink = 0
            for (yy in max(0, y0[i].toInt() - 23) until y0[i].toInt()) for (xx in max(0, x0[i].toInt() - 3)..min(w - 1, x1[i].toInt() + 3)) {
                val c = px[yy * stride + xx]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (r > 200 && g > 130 && b > 130 && r - g > 25 && r - b > 20) pink++
            }
            val rx = x0[i] + Layout.BAR_FULL * w / 2.0 - meX; val ry = y0[i] + h * 0.085 - meY
            val d = hypot(rx, ry)
            if (pink >= 6) {
                lab(Cls.ENEMY, meX + rx, meY + ry)
                if (nd < N && yd == null && !nearEnemy(nd, rx, ry)) { eX[nd] = rx; eY[nd] = ry; eH[nd] = (wd / (Layout.BAR_FULL * w * 0.95)).coerceAtMost(1.0); nd++ }   // с YOLO враги только от неё
            } else {
                // ящик: тонкая полоска хп, не у самого себя, не в декоре сверху (тыквы/надгробия), полоска не толстая
                if (d < h * 0.10 || y0[i] < h * 0.19 || ht > max(6.0, h * 0.025)) continue
                lab(Cls.BOX, meX + rx, meY + ry)
                if (!yBox) { nb++; if (d < boxD) { boxD = d; boxX = rx; boxY = ry } }
            }
        }

        if (now < boxIgnoreUntil) nb = 0
        // ---- (1) треки: подтверждённые враги (>=3 наблюдений) ----
        updateTracks(now, nd, if (yd != null) now - (detAge * 1000).toLong() else now)
        if (yd != null) lastSeq = detSeq
        vis.clear()
        val minHits = if (dets != null) 2 else 3
        for (t in tracks) if (t.last == now && t.hits >= minHits) vis.add(t)
        val ne = vis.size
        var tgt: Track? = null; var bestPr = -1.0
        for (t in vis) {
            val d = hypot(t.rx, t.ry)
            val pr = (1.05 - t.hp) / (d / h + 0.1) * (if (los(t.rx, t.ry)) 1.0 else 0.55)
            if (pr > bestPr) { bestPr = pr; tgt = t }
        }
        tg = tgt
        val bd = if (tgt != null) hypot(tgt.rx, tgt.ry) else 1e9
        val nHp = if (tgt != null) tgt.hp.toFloat() else -1f
        val ux = if (tgt != null) tgt.rx / bd else 0.0; val uy = if (tgt != null) tgt.ry / bd else 0.0

        // призрак: враг пропал из виду недавно - идём/стреляем по предсказанной позиции
        var ghostT: Track? = null
        if (ne == 0) for (t in tracks) if (t.hits >= 3 && now - t.last < 2000 && (ghostT == null || t.last > ghostT.last)) ghostT = t
        val ghost = ghostT != null
        var grx = 0.0; var gry = 0.0
        if (ghostT != null) { val gt = (now - ghostT.last) / 1000.0; grx = ghostT.x + ghostT.vx * 0.5 * gt - wx; gry = ghostT.y + ghostT.vy * 0.5 * gt - wy }

        // клещи: двое врагов с противоположных сторон
        var pinch = false; var pnx = 0.0; var pny = 0.0
        for (i in 0 until ne) for (j in i + 1 until ne) {
            val a = vis[i]; val b = vis[j]
            val di = hypot(a.rx, a.ry); val dj = hypot(b.rx, b.ry)
            if (di < h * 0.6 && dj < h * 0.6 && (a.rx * b.rx + a.ry * b.ry) / (di * dj) < -0.5) {
                val ax = a.rx - b.rx; val ay = a.ry - b.ry; val l = hypot(ax, ay).coerceAtLeast(1.0)
                pinch = true; pnx = -ay / l; pny = ax / l
            }
        }

        val o = out
        o.why = ""; o.attack = false; o.attackTap = false; o.sup = false; o.gadget = false; o.enemies = ne; o.hp = hp; o.ammo = ammo; o.eHp = nHp
        o.meX = if (hp >= 0f || bk >= 0) meX.toFloat() else -1f; o.meY = meY.toFloat()
        if (now - lastFlip > flipEvery) { strafe = -strafe; lastFlip = now; flipEvery = Random.nextLong(700, 1500) }
        if (hp >= 0 && prevHp >= 0 && hp < prevHp - 0.03f && ne > 0) { strafe = -strafe; lastFlip = now }
        if (hp >= 0f && prevHp >= 0f && hp < prevHp - 0.05f) taken += (prevHp - hp)
        prevHp = hp
        val dist = (bd / h).toFloat()
        val low = hp in 0f..0.35f

        // "убежать нельзя": я отступаю, а враг всё равно сближается -> он не медленнее меня (рывок/быстрый боец).
        // Бег в этом случае = бесплатный урон, поэтому ~2.5 с стоим, стреляем и тратим гаджет/супер
        if (tgt != null) {
            val spd = Layout.PLAYER_SPEED * h.toDouble()
            val myvx = if (stuckSince == 0L) lastMx * spd else 0.0; val myvy = if (stuckSince == 0L) lastMy * spd else 0.0
            val closing = -((tgt.vx - myvx) * ux + (tgt.vy - myvy) * uy)   // > 0: дистанция сокращается
            val retreating = lastMx * ux + lastMy * uy < -0.3
            if (retreating && closing > spd * 0.10 && dist < 0.55f) {
                if (retreatSince == 0L) retreatSince = now else if (now - retreatSince > 700) { standUntil = now + prm.standMs.toLong(); retreatSince = 0L }
            } else retreatSince = 0L
        }

        // ================= (6) ТАКТИКА: фаза матча и агрессивность =================
        val phase = if (matchStart > 0) (now - matchStart) / 1000.0 else 999.0
        var aggr = if (mode == Mode.SHOWDOWN) (if (phase < 40) 0.30 else if (phase < 100) 0.60 else 0.80) else 0.80
        if (hp >= 0f) aggr *= (0.5 + 0.5 * hp)
        if (ne >= 2) aggr *= 0.7
        aggr = (aggr * prm.aggrMul).coerceIn(0.1, 1.0)
        // в начале шоудауна сначала лутаем кубки/ящики, а с врагами не лезем в драку
        val lootFirst = mode == Mode.SHOWDOWN && aggr < 0.45 && (dn > 0 || nb > 0) && (ne == 0 || (dist > 0.30f && now >= evadeUntil))
        val dropFirst = dn > 0 && (ne == 0 || dist > 0.25f)
        val cand = when {
            pn >= 3 -> "POISON"
            pinch -> "PINCH"
            ne > 0 && now >= standUntil && (low || ne >= 3 || now < evadeUntil) -> "EVADE"
            healing && ne == 0 && bushD < 1e8 -> "HIDE"
            lootFirst && dn > 0 -> "DROP"
            lootFirst -> "BOX"
            dropFirst -> "DROP"
            ne > 0 -> "ATTACK"
            ghost -> "GHOST"
            nb > 0 -> "BOX"
            else -> "ROAM"
        }
        // гистерезис: выбранное поведение держим минимум 1-2 с, если оно ещё применимо (иначе бот метался между ящиком, дракой и разведкой каждый кадр)
        val stillOk = when (curState) {
            "ATTACK", "EVADE" -> ne > 0
            "BOX" -> nb > 0
            "DROP" -> dn > 0
            "HIDE" -> healing && ne == 0 && bushD < 1e8
            else -> false
        }
        val holdMs = when (curState) { "ATTACK" -> 1200L; "EVADE" -> 1500L; "BOX" -> 1500L; "DROP" -> 1200L; "HIDE" -> 2000L; else -> 0L }
        val urgent = cand == "POISON" || cand == "PINCH" || cand == "EVADE"
        val state = if (!urgent && cand != curState && stillOk && now - stateSince < holdMs) curState else cand
        if (state != curState) { curState = state; stateSince = now }
        o.state = state

        val engage = ammo < 0f || nHp < 0f || nHp <= ammo * 3 * Layout.SLOT_DMG * 1.3f + 0.1f
        val reload = ammo in 0f..(Layout.FIRE_MIN_AMMO + 0.02f)
        val avoid = ne > 0
        var moving = true
        gdx = 0.0; gdy = 0.0
        when (state) {
            "POISON" -> { cdx = 0.7 * cdx + 0.3 * pvux; cdy = 0.7 * cdy + 0.3 * pvuy
                pickMove(pvux, pvuy, 3.0, 0.0, 0.0, 0.0, 0.5) }
            "PINCH" -> pickMove(pnx * strafe, pny * strafe, 2.5, 0.0, 0.0, 0.0, 1.0)
            "EVADE" -> pickMove(-ux, -uy, 0.6, 0.85, 3.0, 0.4, 1.5)
            "HIDE" -> {
                if (inBush) moving = false
                else { goalToward(bushX, bushY, now, avoid); pickMove(gdx, gdy, 2.5, 0.5, if (ne > 0) 1.0 else 0.0, 0.0, 0.0) }
            }
            "DROP" -> { goalToward(dsx - meX, dsy - meY, now, avoid); pickMove(gdx, gdy, 2.2, 0.5, if (ne > 0) 1.2 else 0.0, 0.0, 0.0) }
            "BOX" -> {
                if (boxSince == 0L) boxSince = now
                if (now - boxSince > 10000) { boxIgnoreUntil = now + 8000; boxSince = 0L }
                if (boxD > h * 0.38 || !los(boxX, boxY)) { goalToward(boxX, boxY, now, avoid); pickMove(gdx, gdy, 2.2, 0.5, if (ne > 0) 1.2 else 0.0, 0.0, 0.0) }
                else moving = false
            }
            "ATTACK" -> {
                val band = when {
                    now < standUntil -> max(0.25, min(dist.toDouble(), 0.50))   // не бежим от того, от кого не убежать
                    !engage -> 0.62
                    reload -> 0.60
                    else -> prm.bandBase + 0.14 * (1.0 - aggr)
                }
                // стена между нами - идём обходом; иначе дистанцию держит сама оценка направлений
                if (tgt != null && engage && !reload && dist > band + 0.08) goalToward(tgt.rx, tgt.ry, now, ne > 1)
                val wg = if (gdx != 0.0 || gdy != 0.0) 1.6 else 0.0
                // если дистанция в порядке, враг виден и есть патроны - стоим и стреляем,
                // стрейф только короткими перебежками (иначе бот "наворачивает круги" и сам себе мажет прицел)
                val hold = tgt != null && dist > Layout.TOO_CLOSE - 0.05f && dist < band + 0.07f &&
                        los(tgt.rx, tgt.ry) && (ammo < 0f || ammo >= Layout.FIRE_MIN_AMMO)
                pickMove(gdx, gdy, wg, band, 3.0, (if (hold) 0.15 else 0.6) * prm.strafeMul, if (reload || !engage) 1.8 else 0.2)
                // «постоять и прицелиться» - окнами по ~0.4 с, а не случайным дёрганием джойстика каждый кадр
                if (hold) {
                    if (now < holdUntil) moving = false
                    else if (now >= holdNext) { holdNext = now + 450; if (Random.nextFloat() < prm.holdProb) holdUntil = now + 400 }
                } else holdUntil = 0L
            }
            "GHOST" -> {
                val gd = hypot(grx, gry).coerceAtLeast(1.0)
                if (gd > h * Layout.TOO_FAR) { goalToward(grx, gry, now, false); pickMove(gdx, gdy, 2.0, 0.0, 0.0, 0.0, 0.0) }
                else pickMove(-gry / gd * strafe, grx / gd * strafe, 1.5, 0.0, 0.0, 0.0, 0.0)
            }
            else -> {
                if (explorePick(now)) {
                    // разведка: идём туда, где давно не были (и от газа), а не бродим случайно
                    goalToward(exX - wx, exY - wy, now, avoid)
                    pickMove(gdx, gdy, 2.5, 0.0, 0.0, 0.0, 0.0)
                } else {
                    // идём прямо, пока путь свободен; упёрлись в стену - поворачиваем в более свободную сторону
                    if (now - lastWander > 9000) { wAng = Random.nextDouble() * 2 * PI; lastWander = now }
                    var tries = 0
                    while (tries < 6 && freeRun(cos(wAng), sin(wAng), 6) < 4) {
                        val l = freeRun(cos(wAng + 0.8), sin(wAng + 0.8), 6); val r = freeRun(cos(wAng - 0.8), sin(wAng - 0.8), 6)
                        wAng += if (l >= r) 0.8 else -0.8; tries++
                    }
                    pickMove(cos(wAng), sin(wAng), 2.5, 0.0, 0.0, 0.0, 0.0)
                }
            }
        }
        if (state != "BOX") boxSince = 0L
        var mx = if (moving) bestMx.toFloat() else 0f
        var my = if (moving) bestMy.toFloat() else 0f
        if (now < escapeUntil) { val t = mx; mx = -my * escDir; my = t * escDir; if (mx == 0f && my == 0f) mx = escDir }
        o.mx = mx; o.my = my; lastMx = mx; lastMy = my

        // ================= (4)+(5) СТРЕЛЬБА, ПАТРОНЫ, СУПЕР, ГАДЖЕТ =================
        if (tgt != null && dist < Layout.SHOOT) {
            aimAt(tgt, o)
            val sight = los(tgt.rx, tgt.ry)
            val slots = if (ammo >= 0f) (ammo * 3f).toInt() else 3
            val killable = tgt.hp <= slots * Layout.SLOT_DMG + 0.05
            // стреляем только при полном слоте; на дальней дистанции держим один слот в запасе (если не добиваем)
            // карта стен строится по цветам и иногда ошибается: если давно не стреляли, а враг рядом - проверяем выстрелом
            val forceFire = !sight && dist < 0.5f && now - lastAttackAt > 1800 && (ammo < 0f || ammo >= 0.6f)
            var ok = (sight || forceFire) && (ammo < 0f || ammo >= Layout.FIRE_MIN_AMMO)
            var why = if (!sight && !forceFire) "нет линии огня" else if (!ok) "перезарядка" else ""
            if (ok && dist > Layout.TOO_FAR && ammo in 0f..0.66f && !killable) { ok = false; why = "берегу патрон" }
            // тап = встроенное автонаведение игры: бьёт в ближайшего врага без нашей математики и задержек.
            // свайп с упреждением нужен только для дальних/быстрых целей или когда ближе есть другая цель
            var nearest = true
            for (u in vis) if (u !== tgt && hypot(u.rx, u.ry) < dist * h * 0.85) nearest = false
            if (state != "PINCH") {
                o.attack = ok
                if (ok && nearest && dist < 0.46f) o.attackTap = true
                if (ok) why = if (o.attackTap) "огонь (тап)" else "огонь (прицел)"
                o.why = why
                // супер: сколько врагов лежит на линии выстрела
                var value = 0
                for (u in vis) {
                    val d = hypot(u.rx, u.ry)
                    val along = u.rx * o.ax + u.ry * o.ay
                    val cross = abs(u.rx * o.ay - u.ry * o.ax)
                    if (d < Layout.SHOOT * h * 1.1 && along > 0 && cross < h * 0.06) value++
                }
                val behindWall = !sight && dist < 0.55f
                // жмём ульту щедро: двое на линии, добивание, пробитие стены, дуэль на средней дистанции
                // или почти смертельная опасность (ульт Кольта ломает стены и отпугивает)
                if (now - lastSuper > 2500 &&
                    (value >= 2 || (value >= 1 && (tgt.hp <= 0.60 || behindWall || (sight && dist < 0.6f && ne == 1))) ||
                     (low && sight && dist < 0.45f) || (now < standUntil && sight && dist < 0.55f))) {
                    o.sup = true; lastSuper = now
                }
                val standingNow = now < standUntil
                val wantGadget = gadgetCharges > 0 && now - lastGadget > 2000 && (
                    if (Layout.GADGET_RELOADS)
                        dist < 0.65f && ((ammo in 0f..0.40f && (sight || dist < 0.45f)) || (low && ammo in 0f..0.67f))
                    else
                        // актуальный Speedloader: два быстрых выстрела с замедлением - когда враг уже рядом
                        // (сближается, добиваем или отбиваемся)
                        sight && dist in 0.10f..0.45f && (ammo < 0f || ammo >= Layout.FIRE_MIN_AMMO) &&
                            (standingNow || low || tgt.hp <= 0.5 || dist < 0.30f))
                if (wantGadget) { o.gadget = true; lastGadget = now; gadgetCharges-- }
            }
        } else if (state == "GHOST" && hypot(grx, gry) < h * Layout.SHOOT && now - lastGhostShot > 700 && (ammo < 0f || ammo > 0.5f)) {
            val l = hypot(grx, gry).coerceAtLeast(1.0)
            o.ax = (grx / l).toFloat(); o.ay = (gry / l).toFloat(); o.attack = true; lastGhostShot = now
        } else if (state == "BOX" && boxD < h * 0.55 && (ammo < 0f || ammo >= Layout.FIRE_MIN_AMMO)) {
            o.ax = (boxX / boxD).toFloat(); o.ay = (boxY / boxD).toFloat()
            // стреляем только если между нами нет стены (иначе пули уходят в блок и ящик не ломается)
            if (los(boxX, boxY)) {
                o.attack = true
                // ящик близко - тап: авто-наведение игры попадает надёжнее свайпа
                o.attackTap = true
                o.why = "ящик (тап)"
            }
        }
        if (o.attack) { lastAttackAt = now; shots++ }
        if (!o.attack && o.why.isEmpty()) o.why = if (ne == 0) "нет цели" else "далеко"
        return o
    }
}
