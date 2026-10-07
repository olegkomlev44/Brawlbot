package com.oleg.coltbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class BotService : AccessibilityService() {
    companion object {
        var inst: BotService? = null
        // счётчики для диагностики на экране приложения: видно, доходят ли жесты до системы
        @Volatile var sent = 0; @Volatile var done = 0; @Volatile var cancelled = 0; @Volatile var rejected = 0
        @Volatile var lastErr = ""
    }
    private var stroke: GestureDescription.StrokeDescription? = null
    private var fx = 0f; private var fy = 0f
    @Volatile private var busy = false
    @Volatile private var busySince = 0L
    private val cb = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { done++; busy = false }
        override fun onCancelled(g: GestureDescription?) { cancelled++; busy = false; stroke = null }
    }
    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun act(a: Action, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        if (busy) {
            if (now - busySince < 500) return
            busy = false; stroke = null          // коллбек потерялся - не зависаем навсегда
        }
        try {
            val r = h * 0.10f; val jx = w * Layout.JOY_X; val jy = h * Layout.JOY_Y
            val g = GestureDescription.Builder(); var n = 0
            val move = a.mx * a.mx + a.my * a.my > 0.01f
            if (move || stroke != null) {
                if (stroke == null) { fx = jx; fy = jy }
                val tx = if (move) jx + a.mx * r else jx; var ty = if (move) jy + a.my * r else jy
                if (tx == fx && ty == fy) ty += 1f
                val p = Path(); p.moveTo(fx, fy); p.lineTo(tx, ty)
                val s = stroke?.continueStroke(p, 0, 100, move) ?: GestureDescription.StrokeDescription(p, 0, 100, move)
                g.addStroke(s); n++
                stroke = if (move) s else null; fx = tx; fy = ty
            }
            // выстрел происходит при ОТПУСКАНИИ пальца, поэтому свайпы короткие (70 мс): меньше задержка прицела
            if (a.attack) {
                val p = Path(); val cx = w * Layout.ATK_X; val cy = h * Layout.ATK_Y
                p.moveTo(cx, cy)
                if (a.attackTap) {
                    // тап = встроенное авто-наведение игры (точно бьёт в упор и по ящикам)
                    g.addStroke(GestureDescription.StrokeDescription(p, 0, 40)); n++
                } else {
                    p.lineTo(cx + a.ax * r, cy + a.ay * r)
                    g.addStroke(GestureDescription.StrokeDescription(p, 0, 70)); n++
                }
            }
            if (a.sup) { // супер свайпом с упреждением, как обычная атака
                val p = Path(); val cx = w * Layout.SUP_X; val cy = h * Layout.SUP_Y
                p.moveTo(cx, cy); p.lineTo(cx + a.ax * r, cy + a.ay * r)
                g.addStroke(GestureDescription.StrokeDescription(p, 0, 70)); n++
            }
            if (a.gadget) { val p = Path(); p.moveTo(w * Layout.GAD_X, h * Layout.GAD_Y); g.addStroke(GestureDescription.StrokeDescription(p, 0, 50)); n++ }
            if (n > 0) {
                busy = true; busySince = now; sent++
                if (!dispatchGesture(g.build(), cb, null)) { busy = false; stroke = null; rejected++ }
            }
        } catch (e: Exception) {
            lastErr = e.javaClass.simpleName + ": " + (e.message ?: ""); busy = false; stroke = null
        }
    }

    /** Проверка управления: джойстик влево-вправо ~2 секунды. Запускай, когда открыта игра (в бою или тренировке). */
    fun selfTest() {
        val dm = resources.displayMetrics
        val sw = maxOf(dm.widthPixels, dm.heightPixels); val sh = minOf(dm.widthPixels, dm.heightPixels)
        val hd = Handler(Looper.getMainLooper()); val a = Action(); var i = 0
        val run = object : Runnable {
            override fun run() {
                if (i >= 14) { a.mx = 0f; a.my = 0f; act(a, sw, sh); return }
                a.mx = if (i < 7) 1f else -1f; a.my = 0f
                act(a, sw, sh); i++; hd.postDelayed(this, 150)
            }
        }
        hd.post(run)
    }
}
