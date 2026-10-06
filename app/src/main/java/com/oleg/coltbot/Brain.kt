package com.oleg.coltbot

import java.util.PriorityQueue
import kotlin.math.*
import kotlin.random.Random

object Layout {
    // координаты кнопок сняты со скриншота 2340x1080
    const val JOY_X = 0.15f; const val JOY_Y = 0.77f
    const val ATK_X = 0.84f; const val ATK_Y = 0.625f
    const val SUP_X = 0.71f; const val SUP_Y = 0.736f
    const val GAD_X = 0.79f; const val GAD_Y = 0.88f
    const val TOO_CLOSE = 0.32f; const val TOO_FAR = 0.55f; const val SHOOT = 0.70f
    const val PLAYER_SPEED = 0.25f; const val BULLET_SPEED = 1.2f
    const val BAR_FULL = 0.0457f     // ширина полной полоски хп в долях ширины кадра
    const val LATENCY = 0.12f
    const val SLOT_DMG = 0.30f
}

class Action {
    var mx = 0f; var my = 0f; var ax = 0f; var ay = 0f
    var attack = false; var sup = false; var gadget = false
    var enemies = 0; var state = "ROAM"; var hp = -1f; var ammo = -1f; var eHp = -1f
}

class Brain(private val w: Int, private val h: Int) {
    val out = Action()
    private val N = 16
    private val sx = DoubleArray(N); private val sy = DoubleArray(N); private val sn = DoubleArray(N)
    private val x0 = DoubleArray(N); private val x1 = DoubleArray(N); private val y0 = DoubleArray(N); private val y1 = DoubleArray(N)
    private var nc = 0
    private val eX = DoubleArray(N); private val eY = DoubleArray(N); private val eH = DoubleArray(N)
    private var lastSuper = 0L; private var lastGadget = 0L; private var lastFlip = 0L; private var flipEvery = 1200L
    private var strafe = 1f; private var lastWander = 0L; private var wAng = 0.0
    private var pex = 0.0; private var pey = 0.0; private var pt = 0L; private var vex = 0.0; private var vey = 0.0
    private val sig = IntArray(64); private val psig = IntArray(64); private var hasSig = false
    private var stuckSince = 0L; private var escapeUntil = 0L; private var escDir = 1f
    private var lastMx = 0f; private var lastMy = 0f; private var prevHp = -1f; private var seen = 0
    private var pMeX = 0.0; private var pMeY = 0.0
    private var cdx = 0.0; private var cdy = 0.0
    private var wx = 0.0; private var wy = 0.0; private var lastT = 0L
    private var pathOk = false; private var pdx = 0.0; private var pdy = 0.0; private var lastPath = 0L
    private var tdx = 0f; private var tdy = 0f
    private var gX = 0.0; private var gY = 0.0; private var gvx = 0.0; private var gvy = 0.0
    private var lastSeen = 0L; private var lastGhostShot = 0L
    // блоки 8x8 для ядовитых облаков и кубков (цвет у них одинаковый, отличаем по размеру)
    private val B = 8; private val bwc = w / 8 + 1; private val bhc = h / 8 + 1
    private val pc = IntArray(bwc * bhc); private val isFull = BooleanArray(bwc * bhc)
    private val candX = IntArray(16); private val candY = IntArray(16)
    // карта поля прямо с экрана: стены/ящики и кусты по блокам 8x8
    private val obst = BooleanArray(bwc * bhc); private val bushB = BooleanArray(bwc * bhc)
    private val wcnt = IntArray(bwc * bhc); private val bcnt = IntArray(bwc * bhc)
    private val gc = FloatArray(bwc * bhc); private val par = IntArray(bwc * bhc); private val closed = BooleanArray(bwc * bhc)
    private var meXf = 0.0; private var meYf = 0.0; private var meBx = 0; private var meBy = 0
    private var healing = false; private var evadeUntil = 0L; private var hpMark = -1f; private var hpMarkT = 0L
    private val ang = floatArrayOf(0.785f, -0.785f, 1.57f, -1.57f, 2.36f, -2.36f)

    // Брезенхэм по экранной карте: есть ли стена между блоками (концы не считаются)
    private fun clear(ax: Int, ay: Int, bx: Int, by: Int): Boolean {
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

    private fun astar(tbx: Int, tby: Int) {
        pathOk = false
        val si = meBy * bwc + meBx; val gi = tby * bwc + tbx
        if (si == gi) return
        java.util.Arrays.fill(gc, 1e9f); java.util.Arrays.fill(closed, false)
        val pq = PriorityQueue<FloatArray>(compareBy<FloatArray> { it[0] })
        gc[si] = 0f; par[si] = -1; pq.add(floatArrayOf(0f, si.toFloat()))
        var found = false; var iter = 0
        while (pq.isNotEmpty() && iter < 4000) {
            val ci = pq.poll()!![1].toInt()
            if (closed[ci]) continue
            closed[ci] = true; iter++
            if (ci == gi) { found = true; break }
            val cx = ci % bwc; val cy = ci / bwc
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= bwc || ny >= bhc) continue
                val ni = ny * bwc + nx
                if ((obst[ni] && ni != gi) || closed[ni]) continue
                val ng = gc[ci] + (if (dx != 0 && dy != 0) 1.41f else 1f)
                if (ng < gc[ni]) { gc[ni] = ng; par[ni] = ci
                    pq.add(floatArrayOf(ng + hypot((tbx - nx).toFloat(), (tby - ny).toFloat()), ni.toFloat())) }
            }
        }
        if (!found) return
        var cur = gi
        while (par[cur] != si && par[cur] != -1) cur = par[cur]
        val ddx = (cur % bwc - meBx).toDouble(); val ddy = (cur / bwc - meBy).toDouble()
        val l = hypot(ddx, ddy).coerceAtLeast(1.0)
        pdx = ddx / l; pdy = ddy / l; pathOk = true
    }

    // идём к цели: напрямую, а если на пути стена - по маршруту A*
    private fun toward(rx: Double, ry: Double, now: Long) {
        val l = hypot(rx, ry).coerceAtLeast(1.0); tdx = (rx / l).toFloat(); tdy = (ry / l).toFloat()
        val tbx = ((meXf + rx) / B).toInt().coerceIn(0, bwc - 1); val tby = ((meYf + ry) / B).toInt().coerceIn(0, bhc - 1)
        if (clear(meBx, meBy, tbx, tby)) return
        if (now - lastPath > 120) { lastPath = now; astar(tbx, tby) }
        if (pathOk) { tdx = pdx.toFloat(); tdy = pdy.toFloat() }
    }

    private fun los(rx: Double, ry: Double): Boolean =
        clear(meBx, meBy, ((meXf + rx) / B).toInt().coerceIn(0, bwc - 1), ((meYf + ry) / B).toInt().coerceIn(0, bhc - 1))

    private fun blockedAt(dx: Float, dy: Float, steps: Int): Boolean {
        for (st in 1..steps) {
            val bx = ((meXf + dx * st * B) / B).toInt(); val by = ((meYf + dy * st * B) / B).toInt()
            if (bx < 0 || by < 0 || bx >= bwc || by >= bhc || obst[by * bwc + bx]) return true
        }
        return false
    }

    // скольжение вдоль стены: если впереди препятствие, берём ближайшее свободное направление
    private fun steer(mx: Float, my: Float) {
        tdx = mx; tdy = my
        val l = hypot(mx, my); if (l < 0.1f) return
        val ux = mx / l; val uy = my / l
        if (!blockedAt(ux, uy, 4)) return
        for (a in ang) {
            val c = cos(a); val sn2 = sin(a); val rx = ux * c - uy * sn2; val ry = ux * sn2 + uy * c
            if (!blockedAt(rx, ry, 4)) { tdx = rx * l; tdy = ry * l; return }
        }
    }

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
            for (yy in y + 1..min(h - 1, y + 14)) {
                val c = px[yy * stride + x]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (r > 190 && g in 90..150 && b < 90) { hit = true; break }
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

    fun decide(px: IntArray, stride: Int, now: Long): Action {
        nc = 0; java.util.Arrays.fill(pc, 0); java.util.Arrays.fill(wcnt, 0); java.util.Arrays.fill(bcnt, 0)
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
                if (r > 190 && g < 100 && b < 105 && r - g > 90) addRed(x, y)
                val bi = (y / B) * bwc + x / B
                if (g > 200 && r in 141..189 && b in 111..169) pc[bi]++
                else if (b > 150 && r in 80..144 && g in 80..144 && abs(r - g) < 16 && b - r > 40) wcnt[bi]++
                else if (r > 140 && g < 115 && b in 96..149 && r - g > 40) wcnt[bi]++
                else if (r < 80 && g in 96..149 && b > 115 && g - r > 40) bcnt[bi]++
                if (r < 150 && g > 195 && b < 130) { run++; if (run == 12 && nCand < 16) { candX[nCand] = x - 11; candY[nCand] = y; nCand++ } } else run = 0
            }
        }
        // ---- мой персонаж: зелёная полоска хп + оранжевые патроны под ней ----
        var meX = w / 2.0; var meY = h / 2.0; var hp = -1f; var ammo = -1f
        for (k in 0 until nCand) {
            val cols = ammoCols(px, stride, candX[k], candY[k])
            if (cols >= 20) {
                val xs = candX[k]; val yb = candY[k]
                var best = 0
                for (yy in yb + 1..min(h - 1, yb + 4)) best = max(best, runLen(px, stride, xs, yy))
                hp = (best / (Layout.BAR_FULL * w)).toFloat().coerceIn(0f, 1f)
                ammo = (cols / (0.0397f * w)).coerceIn(0f, 1f)
                meX = xs + Layout.BAR_FULL * w / 2.0; meY = yb + h * 0.083
                break
            }
        }
        meXf = meX; meYf = meY
        meBx = (meX / B).toInt().coerceIn(0, bwc - 1); meBy = (meY / B).toInt().coerceIn(0, bhc - 1)
        if (hp >= 0f) {
            if (hp < 0.6f) healing = true else if (hp > 0.9f) healing = false
            if (now - hpMarkT > 1500) { if (hpMark >= 0f && hpMark - hp > 0.25f) evadeUntil = now + 2000; hpMark = hp; hpMarkT = now }
        }
        // ---- яд и кубки ----
        for (i in pc.indices) isFull[i] = pc[i] >= 50
        var pvx = 0.0; var pvy = 0.0; var pn = 0
        var csx = 0.0; var csy = 0.0; var cnn = 0
        var bushD = 1e9; var bushX = 0.0; var bushY = 0.0
        for (by in 0 until bhc) for (bx in 0 until bwc) {
            val i = by * bwc + bx
            obst[i] = wcnt[i] >= 20; bushB[i] = bcnt[i] >= 32 && pc[i] < 10
            if (bushB[i]) { val ddx = bx * B + B / 2 - meX; val ddy = by * B + B / 2 - meY; val dd = hypot(ddx, ddy)
                if (dd < bushD && dd < h * 0.6) { bushD = dd; bushX = ddx; bushY = ddy } }
            if (isFull[i]) {
                val dx = meX - (bx * B + B / 2); val dy = meY - (by * B + B / 2)
                if (hypot(dx, dy) < h * 0.30) { pvx += dx; pvy += dy; pn++ }
            } else if (pc[i] >= 20) {
                var near = false
                for (yy in max(0, by - 4)..min(bhc - 1, by + 4)) for (xx in max(0, bx - 4)..min(bwc - 1, bx + 4)) if (isFull[yy * bwc + xx]) near = true
                if (!near) { csx += bx * B + B / 2; csy += by * B + B / 2; cnn++ }
            }
        }
        val inBush = bushB[meBy * bwc + meBx]
        val cubeOk = cnn in 1..8
        val dsx = if (cubeOk) csx / cnn else 0.0; val dsy = if (cubeOk) csy / cnn else 0.0
        val dn = if (cubeOk) 1 else 0

        // ---- застревание: экран не скроллится И я на месте ----
        var k = 0
        for (gy in 0 until 8) for (gx in 0 until 8) sig[k++] = px[((gy + 1) * h / 9) * stride + (gx + 1) * w / 9] and 0xFF
        val moved = abs(meX - pMeX) + abs(meY - pMeY)
        pMeX = meX; pMeY = meY
        if (hasSig && lastMx * lastMx + lastMy * lastMy > 0.1f) {
            var d = 0; for (i in 0 until 64) d += abs(sig[i] - psig[i])
            if (d < 64 * 4 && moved < 2.0) {
                if (stuckSince == 0L) stuckSince = now
                if (now - stuckSince > 700) {
                    escapeUntil = now + 800; escDir = if (Random.nextBoolean()) 1f else -1f; stuckSince = 0
                }
            } else stuckSince = 0
        }
        System.arraycopy(sig, 0, psig, 0, 64); hasSig = true

        // ---- враги (есть имя) и ящики (имени нет) ----
        var ne = 0; var nearest = -1; var bd = 1e9; var bestPr = -1.0
        var boxX = 0.0; var boxY = 0.0; var boxD = 1e9; var nb = 0
        for (i in 0 until nc) {
            val wd = x1[i] - x0[i] + 1; val ht = y1[i] - y0[i] + 1
            if (sn[i] < 40 || wd < w * 0.023 || wd < 2.5 * ht) continue
            var pink = 0
            for (yy in max(0, y0[i].toInt() - 23) until y0[i].toInt()) for (xx in max(0, x0[i].toInt() - 3)..min(w - 1, x1[i].toInt() + 3)) {
                val c = px[yy * stride + xx]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (r > 200 && g > 130 && b > 130 && r - g > 25 && r - b > 20) pink++
            }
            val rx = x0[i] + Layout.BAR_FULL * w / 2.0 - meX; val ry = y0[i] + h * 0.085 - meY
            val d = hypot(rx, ry)
            if (pink >= 6) {
                if (ne < N) {
                    eX[ne] = rx; eY[ne] = ry; eH[ne] = (wd / (Layout.BAR_FULL * w * 0.95)).coerceAtMost(1.0)
                    val pr = (1.05 - eH[ne]) / (d / h + 0.1)  // матрица угроз
                    if (pr > bestPr) { bestPr = pr; bd = d; nearest = ne }; ne++
                }
            } else { nb++; if (d < boxD) { boxD = d; boxX = rx; boxY = ry } }
        }
        if (ne > 0) seen++ else seen = 0
        if (seen < 3) { ne = 0; nearest = -1; bd = 1e9 }
        val ex = if (nearest >= 0) eX[nearest] else 0.0; val ey = if (nearest >= 0) eY[nearest] else 0.0
        val nHp = if (nearest >= 0) eH[nearest].toFloat() else -1f
        if (ne > 0) {
            val dtt = (now - pt) / 1000.0
            if (pt > 0 && dtt in 0.01..0.5) { vex = 0.6 * vex + 0.4 * (ex - pex) / dtt; vey = 0.6 * vey + 0.4 * (ey - pey) / dtt }
            pex = ex; pey = ey; pt = now
            val sp = Layout.PLAYER_SPEED * h
            gX = wx + ex; gY = wy + ey; gvx = vex + lastMx * sp; gvy = vey + lastMy * sp; lastSeen = now
        } else { vex = 0.0; vey = 0.0; pt = 0 }
        val ghost = ne == 0 && lastSeen > 0 && now - lastSeen < 2000
        var grx = 0.0; var gry = 0.0
        if (ghost) { val gt = (now - lastSeen) / 1000.0; grx = gX + gvx * 0.5 * gt - wx; gry = gY + gvy * 0.5 * gt - wy }
        var pinch = false; var pnx = 0f; var pny = 0f
        for (i in 0 until ne) for (j in i + 1 until ne) {
            val di = hypot(eX[i], eY[i]); val dj = hypot(eX[j], eY[j])
            if (di < h * 0.6 && dj < h * 0.6 && (eX[i] * eX[j] + eY[i] * eY[j]) / (di * dj) < -0.5) {
                val ax = eX[i] - eX[j]; val ay = eY[i] - eY[j]; val l = hypot(ax, ay).coerceAtLeast(1.0)
                pinch = true; pnx = (-ay / l).toFloat(); pny = (ax / l).toFloat()
            }
        }
        val o = out
        o.attack = false; o.sup = false; o.gadget = false; o.enemies = ne; o.hp = hp; o.ammo = ammo; o.eHp = nHp
        if (now - lastFlip > flipEvery) { strafe = -strafe; lastFlip = now; flipEvery = Random.nextLong(700, 1500) }
        if (hp >= 0 && prevHp >= 0 && hp < prevHp - 0.03f && ne > 0) { strafe = -strafe; lastFlip = now }
        prevHp = hp
        val dist = (bd / h).toFloat()
        val low = hp in 0f..0.35f
        val dropFirst = dn > 0 && (ne == 0 || dist > 0.25f)
        val state = when {
            pn >= 3 -> "POISON"
            pinch -> "PINCH"
            ne > 0 && (low || ne >= 3 || now < evadeUntil) -> "EVADE"
            healing && ne == 0 && bushD < 1e8 -> "HIDE"
            dropFirst -> "DROP"
            ne > 0 -> "ATTACK"
            ghost -> "GHOST"
            nb > 0 -> "BOX"
            else -> "ROAM"
        }
        o.state = state
        val ux = if (ne > 0) (ex / bd).toFloat() else 0f; val uy = if (ne > 0) (ey / bd).toFloat() else 0f
        val engage = ammo < 0f || nHp < 0f || nHp <= ammo * 3 * Layout.SLOT_DMG * 1.3f + 0.1f
        var mx = 0f; var my = 0f; var conc = false
        when (state) {
            "POISON" -> { val l = hypot(pvx, pvy).coerceAtLeast(1.0); mx = (pvx / l).toFloat(); my = (pvy / l).toFloat()
                cdx = 0.7 * cdx + 0.3 * mx; cdy = 0.7 * cdy + 0.3 * my }
            "PINCH" -> { mx = pnx * strafe; my = pny * strafe }
            "EVADE" -> { mx = -ux; my = -uy }
            "HIDE" -> { if (!inBush) { toward(bushX, bushY, now); mx = tdx; my = tdy } }
            "DROP" -> { toward(dsx - meX, dsy - meY, now); mx = tdx; my = tdy }
            "ATTACK" -> {
                val close = Layout.TOO_CLOSE + 0.05f * (ne - 1)
                when {
                    dist < close || (!engage && dist < Layout.TOO_FAR) || (ammo in 0f..0.15f && nHp > 0.3f) -> { mx = -ux; my = -uy }
                    dist > Layout.TOO_FAR && engage -> { toward(ex, ey, now); mx = tdx; my = tdy }
                    else -> {
                        val sp = Layout.PLAYER_SPEED * h
                        if (hypot(vex + lastMx * sp, vey + lastMy * sp) > 0.1 * sp) {
                            mx = (lastMx + vex / sp).toFloat(); my = (lastMy + vey / sp).toFloat(); conc = true
                        } else { mx = -uy * strafe; my = ux * strafe }
                    }
                }
                val z = if (conc) 0f else sin(now / 160.0).toFloat() * 0.5f
                mx += -uy * z; my += ux * z
                val l = hypot(mx, my); if (l > 1f) { mx /= l; my /= l }
            }
            "GHOST" -> { val gd = hypot(grx, gry).coerceAtLeast(1.0)
                if (gd > h * Layout.TOO_FAR) { toward(grx, gry, now); mx = tdx; my = tdy }
                else { mx = (-gry / gd * strafe).toFloat(); my = (grx / gd * strafe).toFloat() } }
            "BOX" -> { if (boxD > h * 0.38) { toward(boxX, boxY, now); mx = tdx; my = tdy } }
            else -> { if (now - lastWander > 2500) { wAng = Random.nextDouble() * 2 * PI; lastWander = now }
                mx = (cos(wAng) + 0.6 * cdx).toFloat(); my = (sin(wAng) + 0.6 * cdy).toFloat()
                val l = hypot(mx, my); if (l > 1f) { mx /= l; my /= l } }
        }
        steer(mx, my); mx = tdx; my = tdy
        if (now < escapeUntil) { val t = mx; mx = -my * escDir; my = t * escDir; if (mx == 0f && my == 0f) mx = escDir }
        o.mx = mx; o.my = my; lastMx = mx; lastMy = my

        if (ne > 0 && dist < Layout.SHOOT) {
            val tt = bd / (Layout.BULLET_SPEED * h) + Layout.LATENCY
            val tx = ex + (vex + mx * Layout.PLAYER_SPEED * h) * tt; val ty = ey + (vey + my * Layout.PLAYER_SPEED * h) * tt
            val l = hypot(tx, ty).coerceAtLeast(1.0); o.ax = (tx / l).toFloat(); o.ay = (ty / l).toFloat()
            val sight = los(ex, ey)
            if (state != "PINCH") {
                o.attack = sight && (ammo < 0f || ammo > 0.2f)
                if (dist < 0.5f && now - lastSuper > 3000 && (sight || nHp in 0f..0.3f)) { o.sup = true; lastSuper = now }
                if (ammo in 0f..0.1f && nHp in 0f..0.3f && now - lastGadget > 3000) { o.gadget = true; lastGadget = now }
            }
        } else if (state == "GHOST" && hypot(grx, gry) < h * Layout.SHOOT && now - lastGhostShot > 700 && (ammo < 0f || ammo > 0.5f)) {
            val l = hypot(grx, gry).coerceAtLeast(1.0)
            o.ax = (grx / l).toFloat(); o.ay = (gry / l).toFloat(); o.attack = true; lastGhostShot = now
        } else if (state == "BOX" && boxD < h * Layout.SHOOT && (ammo < 0f || ammo > 0.2f)) {
            o.ax = (boxX / boxD).toFloat(); o.ay = (boxY / boxD).toFloat(); o.attack = true // фарм ящиков ради кубков
        }
        return o
    }
}
