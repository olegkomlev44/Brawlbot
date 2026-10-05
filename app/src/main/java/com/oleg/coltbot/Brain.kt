package com.oleg.coltbot

import java.util.PriorityQueue
import kotlin.math.*
import kotlin.random.Random

object Layout {
    const val JOY_X = 0.14f; const val JOY_Y = 0.70f
    const val ATK_X = 0.86f; const val ATK_Y = 0.72f
    const val SUP_X = 0.74f; const val SUP_Y = 0.82f
    const val GAD_X = 0.90f; const val GAD_Y = 0.52f
    const val TOO_CLOSE = 0.22f; const val TOO_FAR = 0.40f; const val SHOOT = 0.55f
    const val PLAYER_SPEED = 0.25f; const val BULLET_SPEED = 1.2f
    const val BAR_W = 0.08f
    const val LATENCY = 0.12f    // задержка ввода/кадра в сек, добавляется к упреждению
    const val SLOT_DMG = 0.30f   // какую долю хп врага снимает один патрон-слот Колта (подкрути)
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
    private var maxBarW = 8.0
    private var lastSuper = 0L; private var lastGadget = 0L; private var lastFlip = 0L; private var flipEvery = 1200L
    private var strafe = 1f; private var lastWander = 0L; private var wAng = 0.0
    private var pex = 0.0; private var pey = 0.0; private var pt = 0L; private var vex = 0.0; private var vey = 0.0
    private val sig = IntArray(64); private val psig = IntArray(64); private var hasSig = false
    private var stuckSince = 0L; private var escapeUntil = 0L; private var escDir = 1f
    private var lastMx = 0f; private var lastMy = 0f; private var prevHp = -1f
    private var cdx = 0.0; private var cdy = 0.0 // куда, по ощущениям, центр карты
    // карта + A*
    private val G = 64; private val blocked = ByteArray(G * G); private var nBlocked = 0
    private var wx = 0.0; private var wy = 0.0; private var lastT = 0L
    private val gc = FloatArray(G * G); private val par = IntArray(G * G); private val closed = BooleanArray(G * G)
    private var pathOk = false; private var pdx = 0.0; private var pdy = 0.0; private var lastPath = 0L
    private var tdx = 0f; private var tdy = 0f
    private var gX = 0.0; private var gY = 0.0; private var gvx = 0.0; private var gvy = 0.0
    private var lastSeen = 0L; private var lastGhostShot = 0L
    // блоки для поиска зелёных кубиков
    private val bw12 = w / 12 + 2; private val bc = IntArray(bw12 * (h / 12 + 2))

    private fun cell(v: Double) = (floor(v / (h * 0.08)).toInt() + G / 2).coerceIn(0, G - 1)

    private fun astar(sxc: Int, syc: Int, gx: Int, gy: Int) {
        pathOk = false
        val si = syc * G + sxc; val gi = gy * G + gx
        if (si == gi || blocked[gi].toInt() != 0) return
        java.util.Arrays.fill(gc, 1e9f); java.util.Arrays.fill(closed, false)
        val pq = PriorityQueue<FloatArray>(compareBy<FloatArray> { it[0] })
        gc[si] = 0f; par[si] = -1; pq.add(floatArrayOf(0f, si.toFloat()))
        var found = false; var iter = 0
        while (pq.isNotEmpty() && iter < 3000) {
            val ci = pq.poll()!![1].toInt()
            if (closed[ci]) continue
            closed[ci] = true; iter++
            if (ci == gi) { found = true; break }
            val cx = ci % G; val cy = ci / G
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= G || ny >= G) continue
                val ni = ny * G + nx
                if (blocked[ni].toInt() != 0 || closed[ni]) continue
                val ng = gc[ci] + (if (dx != 0 && dy != 0) 1.41f else 1f)
                if (ng < gc[ni]) { gc[ni] = ng; par[ni] = ci
                    pq.add(floatArrayOf(ng + hypot((gx - nx).toFloat(), (gy - ny).toFloat()), ni.toFloat())) }
            }
        }
        if (!found) return
        var cur = gi
        while (par[cur] != si && par[cur] != -1) cur = par[cur]
        val ddx = (cur % G - sxc).toDouble(); val ddy = (cur / G - syc).toDouble()
        val l = hypot(ddx, ddy).coerceAtLeast(1.0)
        pdx = ddx / l; pdy = ddy / l; pathOk = true
    }

    // направление к цели (px относительно нас), с обходом известных стен
    private fun toward(rx: Double, ry: Double, now: Long) {
        val l = hypot(rx, ry).coerceAtLeast(1.0); tdx = (rx / l).toFloat(); tdy = (ry / l).toFloat()
        if (nBlocked == 0) return
        if (now - lastPath > 250) { lastPath = now; astar(cell(wx), cell(wy), cell(wx + rx), cell(wy + ry)) }
        if (pathOk) { tdx = pdx.toFloat(); tdy = pdy.toFloat() }
    }

    // линия огня по известным стенам (Брезенхэм)
    private fun los(rx: Double, ry: Double): Boolean {
        var x = cell(wx); var y = cell(wy); val tx = cell(wx + rx); val ty = cell(wy + ry)
        val dx = abs(tx - x); val dy = abs(ty - y); val stepX = if (x < tx) 1 else -1; val stepY = if (y < ty) 1 else -1
        var err = dx - dy
        while (!(x == tx && y == ty)) {
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += stepX }
            if (e2 < dx) { err += dx; y += stepY }
            if (!(x == tx && y == ty) && blocked[y * G + x].toInt() != 0) return false
        }
        return true
    }

    private fun addRed(x: Int, y: Int) {
        for (i in 0 until nc) {
            if (abs(sx[i] / sn[i] - x) < w * 0.04 && abs(sy[i] / sn[i] - y) < h * 0.02) {
                sx[i] += x; sy[i] += y; sn[i] += 1.0
                x0[i] = min(x0[i], x.toDouble()); x1[i] = max(x1[i], x.toDouble()); y0[i] = min(y0[i], y.toDouble()); y1[i] = max(y1[i], y.toDouble()); return
            }
        }
        if (nc < N) { sx[nc] = x.toDouble(); sy[nc] = y.toDouble(); sn[nc] = 1.0
            x0[nc] = x.toDouble(); x1[nc] = x.toDouble(); y0[nc] = y.toDouble(); y1[nc] = y.toDouble(); nc++ }
    }

    fun decide(px: IntArray, stride: Int, now: Long): Action {
        nc = 0; java.util.Arrays.fill(bc, 0)
        val dt = if (lastT > 0) ((now - lastT) / 1000.0).coerceAtMost(0.3) else 0.0
        if (stuckSince == 0L) { wx += lastMx * Layout.PLAYER_SPEED * h * dt; wy += lastMy * Layout.PLAYER_SPEED * h * dt }
        lastT = now
        var pgx = 0L; var pgy = 0L; var pgn = 0; var cx = 0L; var cy = 0L; var cn = 0
        var y = (h * 0.12).toInt()
        while (y < h) {
            var x = 0
            while (x < w) {
                if (!(y > h * 0.55 && (x < w * 0.25 || x > w * 0.7))) {
                    val c = px[y * stride + x]
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    if (r > 200 && g < 80 && b < 80) addRed(x, y)
                    else if (g > 170 && r < 110 && b < 110 && (x < w * 0.15 || x > w * 0.85 || y < h * 0.2 || y > h * 0.85)) { pgx += x; pgy += y; pgn++ }
                    else if (g > 200 && r < 90 && b < 120 && !(x > w * 0.43 && x < w * 0.57 && y > h * 0.38 && y < h / 2)) bc[(y / 12) * bw12 + x / 12]++
                    else if (r > 140 && g < 90 && b > 180) { cx += x; cy += y; cn++ }
                }
                x += 2
            }
            y += 2
        }
        // зелёные кубики из дропа: плотные квадратные блоки (тонкие полоски хп не проходят порог)
        var dsx = 0.0; var dsy = 0.0; var dn = 0
        for (i in bc.indices) if (bc[i] >= 14) { dsx += (i % bw12) * 12 + 6; dsy += (i / bw12) * 12 + 6; dn++ }
        // свои хп/патроны
        var gMin = w; var gMax = -1; var oMin = w; var oMax = -1
        for (yy in (h * 0.38).toInt() until (h * 0.64).toInt() step 2) for (xx in (w * 0.43).toInt() until (w * 0.57).toInt() step 2) {
            val c = px[yy * stride + xx]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            if (yy < h / 2 && g > 170 && r < 110 && b < 110) { gMin = min(gMin, xx); gMax = max(gMax, xx) }
            if (yy > h / 2 && r > 230 && g in 120..200 && b < 70) { oMin = min(oMin, xx); oMax = max(oMax, xx) }
        }
        val hp = if (gMax > 0) ((gMax - gMin) / (Layout.BAR_W * w)).coerceIn(0f, 1f) else -1f
        val ammo = if (oMax > 0) ((oMax - oMin) / (Layout.BAR_W * w)).coerceIn(0f, 1f) else -1f
        // застревание -> помечаем клетку впереди как стену
        var k = 0
        for (gy in 0 until 8) for (gx in 0 until 8) sig[k++] = px[((gy + 1) * h / 9) * stride + (gx + 1) * w / 9] and 0xFF
        if (hasSig && lastMx * lastMx + lastMy * lastMy > 0.1f) {
            var d = 0; for (i in 0 until 64) d += abs(sig[i] - psig[i])
            if (d < 64 * 4) {
                if (stuckSince == 0L) stuckSince = now
                if (now - stuckSince > 700) {
                    escapeUntil = now + 800; escDir = if (Random.nextBoolean()) 1f else -1f; stuckSince = 0
                    val l = hypot(lastMx, lastMy).coerceAtLeast(0.01f); val cs = h * 0.08
                    val bi = cell(wy + lastMy / l * cs) * G + cell(wx + lastMx / l * cs)
                    if (blocked[bi].toInt() == 0) { blocked[bi] = 1; nBlocked++ }
                }
            } else stuckSince = 0
        }
        System.arraycopy(sig, 0, psig, 0, 64); hasSig = true

        // враги: позиция + хп по ширине красной полоски
        var ne = 0; var nearest = -1; var bd = 1e9; var bestPr = -1.0
        for (i in 0 until nc) {
            if (sn[i] < 6 || (x1[i] - x0[i]) <= (y1[i] - y0[i])) continue
            val wd = x1[i] - x0[i] + 2; if (wd > maxBarW) maxBarW = wd
            eX[ne] = sx[i] / sn[i] - w / 2.0; eY[ne] = sy[i] / sn[i] + h * 0.07 - h / 2.0; eH[ne] = wd / maxBarW
            // матрица угроз: (1 - хп) * коэфф_класса / дистанция (класс пока = 1)
            val d = hypot(eX[ne], eY[ne]); val pr = (1.05 - eH[ne]) / (d / h + 0.1)
            if (pr > bestPr) { bestPr = pr; bd = d; nearest = ne }; ne++
        }
        val ex = if (nearest >= 0) eX[nearest] else 0.0; val ey = if (nearest >= 0) eY[nearest] else 0.0
        val nHp = if (nearest >= 0) eH[nearest].toFloat() else -1f
        if (ne > 0) {
            val dtt = (now - pt) / 1000.0
            if (pt > 0 && dtt in 0.01..0.5) { vex = 0.6 * vex + 0.4 * (ex - pex) / dtt; vey = 0.6 * vey + 0.4 * (ey - pey) / dtt }
            pex = ex; pey = ey; pt = now
            val sp = Layout.PLAYER_SPEED * h // мировая скорость цели = относительная + моя
            gX = wx + ex; gY = wy + ey; gvx = vex + lastMx * sp; gvy = vey + lastMy * sp; lastSeen = now
        } else { vex = 0.0; vey = 0.0; pt = 0 }
        // призрак: враг пропал (куст) -> экстраполируем его путь до 2 с
        val ghost = ne == 0 && lastSeen > 0 && now - lastSeen < 2000
        var grx = 0.0; var gry = 0.0
        if (ghost) { val gt = (now - lastSeen) / 1000.0; grx = gX + gvx * 0.5 * gt - wx; gry = gY + gvy * 0.5 * gt - wy }
        // клещи: два врага с углом > 120 градусов
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
        if (hp >= 0 && prevHp >= 0 && hp < prevHp - 0.03f && ne > 0) { strafe = -strafe; lastFlip = now } // реакция на урон
        prevHp = hp
        val dist = (bd / h).toFloat()
        val low = hp in 0f..0.35f
        val dropFirst = dn > 0 && (ne == 0 || dist > 0.25f)
        val state = when {
            pgn > 40 -> "POISON"
            pinch -> "PINCH"
            ne > 0 && (low || ne >= 3) -> "EVADE"
            dropFirst -> "DROP"
            ne > 0 -> "ATTACK"
            ghost -> "GHOST"
            cn > 8 -> "LOOT"
            else -> "ROAM"
        }
        o.state = state
        val ux = if (ne > 0) (ex / bd).toFloat() else 0f; val uy = if (ne > 0) (ey / bd).toFloat() else 0f
        // burst limit: хватит ли патронов, чтобы убить
        val engage = ammo < 0f || nHp < 0f || nHp <= ammo * 3 * Layout.SLOT_DMG * 1.3f + 0.1f
        var mx = 0f; var my = 0f; var conc = false
        when (state) {
            "POISON" -> { val dx = w / 2.0 - pgx.toDouble() / pgn; val dy = h / 2.0 - pgy.toDouble() / pgn
                val l = hypot(dx, dy).coerceAtLeast(1.0); mx = (dx / l).toFloat(); my = (dy / l).toFloat()
                cdx = 0.7 * cdx + 0.3 * mx; cdy = 0.7 * cdy + 0.3 * my }
            "PINCH" -> { mx = pnx * strafe; my = pny * strafe }
            "EVADE" -> { mx = -ux; my = -uy }
            "DROP" -> { toward(dsx / dn - w / 2.0, dsy / dn - h / 2.0, now); mx = tdx; my = tdy }
            "ATTACK" -> {
                val close = Layout.TOO_CLOSE + 0.05f * (ne - 1)
                when {
                    dist < close || (!engage && dist < Layout.TOO_FAR) -> { mx = -ux; my = -uy }
                    dist > Layout.TOO_FAR && engage -> { toward(ex, ey, now); mx = tdx; my = tdy }
                    else -> {
                        val sp = Layout.PLAYER_SPEED * h
                        if (hypot(vex + lastMx * sp, vey + lastMy * sp) > 0.1 * sp) { // концентрация: бежим вместе с целью
                            mx = (lastMx + vex / sp).toFloat(); my = (lastMy + vey / sp).toFloat(); conc = true
                        } else { mx = -uy * strafe; my = ux * strafe }
                    }
                }
                val z = if (conc) 0f else sin(now / 160.0).toFloat() * 0.5f // джук: зигзаг
                mx += -uy * z; my += ux * z
                val l = hypot(mx, my); if (l > 1f) { mx /= l; my /= l }
            }
            "GHOST" -> { val gd = hypot(grx, gry).coerceAtLeast(1.0)
                if (gd > h * Layout.TOO_FAR) { toward(grx, gry, now); mx = tdx; my = tdy }
                else { mx = (-gry / gd * strafe).toFloat(); my = (grx / gd * strafe).toFloat() } }
            "LOOT" -> { toward(cx.toDouble() / cn - w / 2.0, cy.toDouble() / cn - h / 2.0, now); mx = tdx; my = tdy }
            else -> { if (now - lastWander > 2500) { wAng = Random.nextDouble() * 2 * PI; lastWander = now }
                mx = (cos(wAng) + 0.6 * cdx).toFloat(); my = (sin(wAng) + 0.6 * cdy).toFloat()
                val l = hypot(mx, my); if (l > 1f) { mx /= l; my /= l } }
        }
        if (now < escapeUntil) { val t = mx; mx = -my * escDir; my = t * escDir; if (mx == 0f && my == 0f) mx = escDir }
        o.mx = mx; o.my = my; lastMx = mx; lastMy = my

        if (ne > 0 && dist < Layout.SHOOT) {
            val tt = bd / (Layout.BULLET_SPEED * h) + Layout.LATENCY
            val tx = ex + (vex + mx * Layout.PLAYER_SPEED * h) * tt; val ty = ey + (vey + my * Layout.PLAYER_SPEED * h) * tt
            val l = hypot(tx, ty).coerceAtLeast(1.0); o.ax = (tx / l).toFloat(); o.ay = (ty / l).toFloat()
            val sight = los(ex, ey)
            if (state != "PINCH") {
                o.attack = sight && (ammo < 0f || ammo > 0.2f)
                // супер пробивает стены: можно добивать врага за укрытием
                if (dist < 0.5f && now - lastSuper > 3000 && (sight || nHp in 0f..0.3f)) { o.sup = true; lastSuper = now }
                if (ammo in 0f..0.1f && nHp in 0f..0.3f && now - lastGadget > 3000) { o.gadget = true; lastGadget = now }
            }
        } else if (state == "GHOST" && hypot(grx, gry) < h * Layout.SHOOT && now - lastGhostShot > 700 && (ammo < 0f || ammo > 0.5f)) {
            val l = hypot(grx, gry).coerceAtLeast(1.0) // редкий выстрел вслепую в куст
            o.ax = (grx / l).toFloat(); o.ay = (gry / l).toFloat(); o.attack = true; lastGhostShot = now
        }
        return o
    }
}
