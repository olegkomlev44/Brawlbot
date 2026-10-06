package com.oleg.coltbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class BotService : AccessibilityService() {
    companion object { var inst: BotService? = null }
    private var stroke: GestureDescription.StrokeDescription? = null
    private var fx = 0f; private var fy = 0f
    @Volatile private var busy = false
    private val cb = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { busy = false }
        override fun onCancelled(g: GestureDescription?) { busy = false; stroke = null }
    }
    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun act(a: Action, w: Int, h: Int) {
        if (busy) return
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
        if (a.attack) {
            val p = Path(); val cx = w * Layout.ATK_X; val cy = h * Layout.ATK_Y
            p.moveTo(cx, cy)
            if (a.attackTap) {
                // тап = встроенное авто-наведение игры (точно бьёт в упор и по ящикам)
                g.addStroke(GestureDescription.StrokeDescription(p, 0, 50)); n++
            } else {
                p.lineTo(cx + a.ax * r, cy + a.ay * r)
                g.addStroke(GestureDescription.StrokeDescription(p, 0, 100)); n++
            }
        }
        if (a.sup) { // супер свайпом с упреждением, как обычная атака
            val p = Path(); val cx = w * Layout.SUP_X; val cy = h * Layout.SUP_Y
            p.moveTo(cx, cy); p.lineTo(cx + a.ax * r, cy + a.ay * r)
            g.addStroke(GestureDescription.StrokeDescription(p, 0, 100)); n++
        }
        if (a.gadget) { val p = Path(); p.moveTo(w * Layout.GAD_X, h * Layout.GAD_Y); g.addStroke(GestureDescription.StrokeDescription(p, 0, 60)); n++ }
        if (n > 0) { busy = true; if (!dispatchGesture(g.build(), cb, null)) { busy = false; stroke = null } }
    }
}
