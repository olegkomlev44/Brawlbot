package com.oleg.coltbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Снимок одного кадра для оверлея (все координаты в пикселях кадра захвата). */
class OverlayData {
    var capW = 1; var capH = 1
    var dets: List<Det> = emptyList()       // что нашёл YOLO
    var heur: List<Det> = emptyList()       // что нашли старые цветовые эвристики (маленькие кружки - для сравнения)
    var meX = -1f; var meY = -1f
    var mx = 0f; var my = 0f; var ax = 0f; var ay = 0f; var attack = false
    var hud = ""
}

/**
 * Прозрачное окно поверх игры: рамки YOLO с названием и уверенностью, позиция персонажа, куда бот ведёт джойстик и куда целится.
 * Цвета подобраны так, чтобы не совпадать с цветами, по которым бот ищет полоски хп, яд и стены (оверлей попадает в тот же захват экрана).
 */
class OverlayView(ctx: Context) : View(ctx) {
    @Volatile var data: OverlayData? = null
    private val dn = ctx.resources.displayMetrics.density
    private val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f * dn; isAntiAlias = false }
    private val dot = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dn }
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dn; isAntiAlias = false }
    private val txt = Paint().apply { textSize = 11f * dn; isAntiAlias = true; setShadowLayer(2f * dn, 0f, 0f, Color.BLACK) }
    private val hud = Paint().apply { color = Color.WHITE; textSize = 11f * dn; isAntiAlias = true; setShadowLayer(3f * dn, 0f, 0f, Color.BLACK) }

    private fun colorOf(role: Int): Int = when (role) {
        Cls.ENEMY -> Color.rgb(255, 0, 255)      // пурпурный
        Cls.ME -> Color.rgb(255, 255, 255)       // белый
        Cls.ALLY -> Color.rgb(0, 255, 255)       // голубой
        Cls.BOX -> Color.rgb(255, 255, 0)        // жёлтый
        Cls.CUBE -> Color.rgb(160, 0, 255)       // фиолетовый
        else -> Color.rgb(128, 128, 128)         // прочее (кусты, стены, меню)
    }

    override fun onDraw(c: Canvas) {
        val d = data ?: return
        val kx = width.toFloat() / d.capW; val ky = height.toFloat() / d.capH
        // оверлей попадает в тот же захват экрана, который читает бот: зону над персонажем (имя, полоска хп, патроны) не трогаем,
        // иначе линия поверх полоски ломает чтение хп
        c.save()
        if (d.meX >= 0f) c.clipOutRect((d.meX - 36f) * kx, (d.meY - 64f) * ky, (d.meX + 36f) * kx, (d.meY - 6f) * ky)
        // старые эвристики: кружки
        for (e in d.heur) {
            dot.color = colorOf(e.cls)
            c.drawCircle(e.cx * kx, e.cy * ky, 7f * dn, dot)
        }
        // YOLO: рамки
        for (e in d.dets) {
            val col = colorOf(e.cls)
            box.color = col
            val l = (e.cx - e.bw / 2) * kx; val t = (e.cy - e.bh / 2) * ky
            val r = (e.cx + e.bw / 2) * kx; val b = (e.cy + e.bh / 2) * ky
            c.drawRect(l, t, r, b, box)
            if (e.cls >= 0 || e.conf >= 0.6f) {
                txt.color = col
                c.drawText(e.name + " " + (e.conf * 100).toInt(), l, t - 3f * dn, txt)
            }
        }
        // где бот видит себя и куда идёт / целится
        if (d.meX >= 0f) {
            val x = d.meX * kx; val y = d.meY * ky
            line.color = Color.WHITE
            c.drawLine(x - 14f * dn, y, x + 14f * dn, y, line); c.drawLine(x, y - 14f * dn, x, y + 14f * dn, line)
            if (d.mx != 0f || d.my != 0f) { line.color = Color.rgb(255, 255, 255); c.drawLine(x, y, x + d.mx * 70f * dn, y + d.my * 70f * dn, line) }
            if (d.attack) { line.color = Color.rgb(0, 160, 255); c.drawLine(x, y, x + d.ax * 220f * dn, y + d.ay * 220f * dn, line) }
        }
        c.restore()
        // строка состояния - в верхней полоске экрана (бот эту область не анализирует)
        var yy = 14f * dn
        for (ln in d.hud.split("\n")) { c.drawText(ln, 12f * dn, yy, hud); yy += 13f * dn }
    }
}
